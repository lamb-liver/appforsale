package com.lambliver.stallpos.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import com.lambliver.stallpos.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Room 2 runtime persistence；DataStore 只作一次性 legacy import 與 UI preferences。 */
internal class RoomPosPersistence(
    context: Context,
    private val database: StallPosV2Database = StallPosV2Database.get(context),
) : PosPersistence {
    private val appContext = context.applicationContext
    private val dao = database.posDao()
    private val v2Dao = database.v2Dao()

    override val snapshot: Flow<PosPersistSnapshot> = flow {
        ensureLegacyImported()
        emitAll(
            database.invalidationTracker
                .createFlow(*BUSINESS_TABLES)
                .map { readSnapshot() },
        )
    }

    override val productsFlow = snapshot.map { it.products }
    override val categoriesFlow = snapshot.map { it.categories }
    override val bundleCategoriesFlow = snapshot.map { it.bundleCategories }
    override val bundlesFlow = snapshot.map { it.bundles }
    override val cartFlow = snapshot.map { it.cart }
    override val totalSalesFlow = snapshot.map { it.totalSales }
    override val txCountFlow = snapshot.map { it.txCount }
    override val salesLogFlow = snapshot.map { it.salesLog }
    override val reversalLogFlow = snapshot.map { it.reversalLog }
    override val lastCheckoutFlow = snapshot.map { it.lastCheckout }
    override val eventsFlow = snapshot.map { it.events }
    override val inventoryLevelsFlow = snapshot.map { it.inventoryLevels }
    override val syncStateFlow: Flow<SyncUiState> = combine(
        v2Dao.observeOutboxCounts(),
        v2Dao.observeCloudValue(SyncCloudKeys.ACCESS_TOKEN),
    ) { counts, accessToken ->
        when {
            accessToken == null -> SyncUiState.LocalOnly
            counts.blockedCount > 0 -> SyncUiState(
                SyncUiStatus.BLOCKED,
                pendingCount = counts.pendingCount,
                blockedCount = counts.blockedCount,
            )
            counts.pendingCount > 0 -> SyncUiState(SyncUiStatus.PENDING, pendingCount = counts.pendingCount)
            else -> SyncUiState(SyncUiStatus.SYNCED)
        }
    }

    override suspend fun applyCatalog(plan: CatalogPersistPlan) {
        ensureLegacyImported()
        val now = System.currentTimeMillis()
        database.withTransaction {
            val previousProducts = plan.products?.let { readProducts() }.orEmpty()
            val previousCategories = plan.categories?.let { dao.categories().map { row -> Category(row.id, row.name) } }.orEmpty()
            val previousBundleCategories = plan.bundleCategories
                ?.let { dao.bundleCategories().map { row -> BundleCategory(row.id, row.name) } }.orEmpty()
            val previousBundles = plan.bundles?.let { readBundles() }.orEmpty()
            if (plan.bundles != null) dao.deleteAllBundleComponents()
            val movements = plan.products?.let { replaceProducts(it, now) }.orEmpty()
            plan.bundles?.let { replaceBundles(it) }
            plan.categories?.let { replaceCategories(it) }
            plan.bundleCategories?.let { replaceBundleCategories(it) }
            plan.cart?.let { replaceCart(it) }
            v2Dao.enqueueCatalogOperations(
                plan,
                previousProducts,
                previousCategories,
                previousBundleCategories,
                previousBundles,
                movements,
                now,
            )
        }
        scheduleSyncIfConfigured()
    }

    override suspend fun saveCart(cart: PosCart) {
        ensureLegacyImported()
        database.withTransaction { replaceCart(cart) }
    }

    override suspend fun clearCart() = saveCart(PosCart())

    override suspend fun commitCheckout(request: CheckoutWriteRequest) {
        require(request.total >= 0L) { "checkout total must be non-negative, got ${request.total}" }
        require(request.productCart.values.all { it >= 0 } && request.bundleCart.values.all { it >= 0 }) {
            "checkout cart quantity must be non-negative"
        }
        ensureLegacyImported()
        database.withTransaction {
            val products = readProducts()
            val bundles = readBundles()
            val deductions = request.stockDeductions.takeIf { it.isNotEmpty() }
                ?: request.productCart.mapValues { it.value.toLong() }
            require(deductions.values.all { it > 0L }) { "stock deduction must be positive" }
            val productsById = products.associateBy { it.id }
            deductions.forEach { (productId, quantity) ->
                val product = productsById[productId]
                    ?: throw IllegalStateException("checkout product missing: $productId")
                product.stock?.let { stock ->
                    check(stock >= quantity) { "checkout stock insufficient: $productId" }
                }
            }

            val productNames = products.associate { it.id to it.name }
            val bundleNames = bundles.associate { it.id to it.name }
            val sourceLines = request.checkoutLines.takeIf { it.isNotEmpty() }
                ?: buildProductLinesFromCart(request.productCart, products)
            val lines = sourceLines.map { line ->
                when (line) {
                    is SaleCheckoutLine.Product -> line.copy(
                        displayName = productNames[line.productId] ?: line.displayName,
                    )
                    is SaleCheckoutLine.Bundle -> line.copy(
                        displayName = bundleNames[line.bundleId] ?: line.displayName,
                    )
                }
            }
            val now = System.currentTimeMillis()
            val activeEvents = v2Dao.activeEvents()
            check(activeEvents.size <= 1) { "more than one active event" }
            val activeEvent = activeEvents.singleOrNull()
            val location = activeEvent?.let { InventoryLocation.event(it.id) } ?: InventoryLocation.General
            val auditOrder = dao.nextSaleAuditOrder()
            val saleId = UUID.randomUUID().toString()
            val sale = SaleRecord(
                id = saleId,
                tsMillis = now,
                dateKey = request.dateKey,
                subtotal = request.subtotal,
                discount = request.discount,
                total = request.total,
                cartSnapshot = request.productCart,
                bundleCartSnapshot = request.bundleCart,
                paymentMethod = request.paymentMethod,
                tipAmount = request.tipAmount.coerceAtLeast(0L),
                checkoutLines = lines,
                stockDeductions = deductions,
            )
            insertSale(sale, auditOrder)
            val originalAmounts = lines.map { it.lineSubtotal }
            val catalogSubtotal = originalAmounts.sum()
            val signedAdjustment = request.total - catalogSubtotal
            val discountAmount = (-signedAdjustment).coerceAtLeast(0)
            val positiveAdjustment = signedAdjustment.coerceAtLeast(0)
            val allocatedDiscount = allocateLargestRemainder(discountAmount, originalAmounts)
            val allocatedAdjustment = allocateLargestRemainder(positiveAdjustment, originalAmounts)
            val device = deviceStateOrCreate(now)
            val receiptPrefix = activeEvent?.code ?: "GENERAL"
            val receiptNumber = "$receiptPrefix-${device.shortCode}-${(auditOrder + 1).toString().padStart(4, '0')}"
            v2Dao.insertSaleMeta(
                SaleV2MetaEntity(
                    saleId = saleId,
                    eventId = activeEvent?.id,
                    deviceId = device.deviceId,
                    receiptNumber = receiptNumber,
                    discountType = if (discountAmount > 0) "FIXED_AMOUNT" else null,
                    discountValue = discountAmount.takeIf { it > 0 },
                    discountAmount = discountAmount,
                    netAdjustment = positiveAdjustment,
                ),
            )
            val snapshots = lines.mapIndexed { index, line ->
                val unitCost = (line as? SaleCheckoutLine.Product)?.let { productsById[it.productId]?.cost }
                SaleLineSnapshotEntity(
                    saleId = saleId,
                    lineIndex = index,
                    unitCostSnapshot = unitCost,
                    originalAmount = originalAmounts[index],
                    allocatedDiscount = allocatedDiscount[index],
                    allocatedAdjustment = allocatedAdjustment[index],
                    finalAmount = originalAmounts[index] - allocatedDiscount[index] + allocatedAdjustment[index],
                )
            }
            if (snapshots.isNotEmpty()) v2Dao.insertSaleLineSnapshots(snapshots)
            val allocations = buildBundleAllocations(saleId, lines, snapshots, bundles, productsById)
            if (allocations.isNotEmpty()) v2Dao.insertBundleAllocations(allocations)
            val movements = mutableListOf<InventoryMovementEntity>()
            deductions.forEach { (productId, quantity) ->
                if (productsById.getValue(productId).stock == null) return@forEach
                movements += moveInventoryInternal(
                    productId = productId,
                    quantity = quantity,
                    from = location,
                    to = null,
                    type = InventoryMovementType.SALE,
                    relatedTransactionId = saleId,
                    now = now,
                )
            }
            dao.deleteAllCartItems()
            dao.replaceLastCheckout(LastCheckoutEntity(saleId = sale.id))
            val wireSale = sale.copy(
                eventId = activeEvent?.id,
                deviceId = device.deviceId,
                receiptNumber = receiptNumber,
                discountType = if (discountAmount > 0) "FIXED_AMOUNT" else null,
                discountValue = discountAmount.takeIf { it > 0 },
                discountAmount = discountAmount,
                netAdjustment = positiveAdjustment,
                lineFinancialSnapshots = snapshots.map { row ->
                    SaleLineFinancialSnapshot(
                        row.lineIndex,
                        row.unitCostSnapshot,
                        row.originalAmount,
                        row.allocatedDiscount,
                        row.allocatedAdjustment,
                        row.finalAmount,
                    )
                },
                bundleComponentAllocations = allocations.map { row ->
                    BundleRevenueAllocation(
                        row.lineIndex,
                        row.allocationIndex,
                        row.productId,
                        row.productNameSnapshot,
                        row.unitCostSnapshot,
                        row.quantity,
                        row.allocatedRevenue,
                    )
                },
            )
            v2Dao.enqueueSyncOperation("TRANSACTION", "SALE", saleId, "APPEND", wireSale.toSyncJson(movements), now)
        }
        scheduleSyncIfConfigured()
    }

    override suspend fun undoLastCheckout() {
        ensureLegacyImported()
        var changed = false
        database.withTransaction {
            val last = dao.lastCheckout() ?: return@withTransaction
            val saleEntity = dao.sale(last.saleId) ?: return@withTransaction
            if (dao.reversalCountForSale(last.saleId) != 0) return@withTransaction
            val sale = saleEntity.toDomain(
                dao.saleLines(last.saleId),
                dao.stockDeductions(last.saleId),
                v2Dao.saleMeta(last.saleId),
                v2Dao.saleLineSnapshots(last.saleId),
                v2Dao.bundleAllocations(last.saleId),
            )
            val saleMeta = v2Dao.saleMeta(sale.id)
                ?: error("sale v2 metadata missing: ${sale.id}")
            val location = saleMeta.eventId?.let(InventoryLocation::event) ?: InventoryLocation.General
            val reversalId = UUID.randomUUID().toString()
            val reversedAt = System.currentTimeMillis()
            val deviceId = saleMeta.deviceId ?: deviceStateOrCreate(reversedAt).deviceId
            val movements = mutableListOf<InventoryMovementEntity>()
            sale.stockDeductions.forEach { (productId, quantity) ->
                val tracked = v2Dao.productMeta().firstOrNull { it.productId == productId }?.trackInventory == true
                if (!tracked) return@forEach
                movements += moveInventoryInternal(
                    productId = productId,
                    quantity = quantity,
                    from = null,
                    to = location,
                    type = InventoryMovementType.VOID,
                    relatedTransactionId = sale.id,
                    now = reversedAt,
                )
            }
            replaceCart(PosCart(sale.cartSnapshot, sale.bundleCartSnapshot))
            dao.insertReversal(
                ReversalEntity(
                    id = reversalId,
                    auditOrder = dao.nextReversalAuditOrder(),
                    saleId = sale.id,
                    tsMillis = reversedAt,
                    reason = ReversalReason.UNDO_LAST_CHECKOUT.name,
                ),
            )
            v2Dao.insertReversalMeta(
                ReversalV2MetaEntity(
                    reversalId = reversalId,
                    eventId = saleMeta.eventId,
                    deviceId = deviceId,
                    paymentMethod = sale.paymentMethod.name,
                ),
            )
            dao.deleteLastCheckout()
            val reversal = SaleReversal(
                id = reversalId,
                saleId = sale.id,
                tsMillis = reversedAt,
                reason = ReversalReason.UNDO_LAST_CHECKOUT,
                eventId = saleMeta.eventId,
                deviceId = deviceId,
                paymentMethod = sale.paymentMethod,
            )
            v2Dao.enqueueSyncOperation(
                "TRANSACTION",
                "VOID",
                reversalId,
                "APPEND",
                reversal.toSyncJson(movements),
                reversedAt,
            )
            changed = true
        }
        if (changed) scheduleSyncIfConfigured()
    }

    override suspend fun saveEvent(event: MarketEvent) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val current = v2Dao.event(event.id)
            if (current != null && v2Dao.saleCountForEvent(event.id) > 0) {
                require(event.code == current.code) { "event code is locked after first sale" }
                require(event.timezone == current.timezone) { "event timezone is locked after first sale" }
            }
            if (current != null) require(event.status.name == current.status) {
                "event status must change through changeEventStatus"
            }
            if (event.status == MarketEventStatus.ACTIVE) {
                check(v2Dao.activeEvents().none { it.id != event.id }) { "another event is already active" }
            }
            val row = event.copy(updatedAtMillis = now).toEntity()
            v2Dao.upsertEvent(row)
            v2Dao.enqueueSyncOperation("MASTER", "EVENT", row.id, "UPSERT", row.toSyncJson(), now)
        }
        scheduleSyncIfConfigured()
    }

    override suspend fun changeEventStatus(eventId: String, status: MarketEventStatus) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            changeEventStatusInternal(eventId, status, now)
            val row = requireNotNull(v2Dao.event(eventId))
            v2Dao.enqueueSyncOperation("MASTER", "EVENT", row.id, "UPSERT", row.toSyncJson(), now)
        }
        scheduleSyncIfConfigured()
    }

    override suspend fun closeEventAndReturnInventory(eventId: String) {
        database.withTransaction {
            val location = InventoryLocation.event(eventId)
            val now = System.currentTimeMillis()
            val movements = mutableListOf<InventoryMovementEntity>()
            v2Dao.inventoryLevels()
                .filter { it.locationKey == location.key && it.quantity > 0 }
                .forEach { level ->
                    movements += moveInventoryInternal(
                        productId = level.productId,
                        quantity = level.quantity,
                        from = location,
                        to = InventoryLocation.General,
                        type = InventoryMovementType.RETURN_FROM_EVENT,
                        relatedTransactionId = null,
                        now = now,
                    )
                }
            changeEventStatusInternal(eventId, MarketEventStatus.CLOSED, now)
            val event = requireNotNull(v2Dao.event(eventId))
            v2Dao.enqueueSyncOperation("MASTER", "EVENT", event.id, "UPSERT", event.toSyncJson(), now)
            movements.forEach { row ->
                v2Dao.enqueueSyncOperation("INVENTORY", "INVENTORY_MOVEMENT", row.id, "APPEND", row.toSyncJson(), now)
            }
        }
        scheduleSyncIfConfigured()
    }

    override suspend fun moveInventory(
        productId: String,
        quantity: Long,
        from: InventoryLocation?,
        to: InventoryLocation?,
        type: InventoryMovementType,
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            val row = moveInventoryInternal(productId, quantity, from, to, type, null, now)
            v2Dao.enqueueSyncOperation("INVENTORY", "INVENTORY_MOVEMENT", row.id, "APPEND", row.toSyncJson(), now)
        }
        scheduleSyncIfConfigured()
    }

    override suspend fun exportFullBackupJson(): String {
        ensureLegacyImported()
        val snap = readSnapshot()
        val payload = JSONObject().apply {
            put("payloadSchema", PosStore.BACKUP_SCHEMA_VERSION)
            put("products_json", encodeProducts(snap.products))
            put("categories_json", encodeCategories(snap.categories))
            put("bundle_categories_json", encodeBundleCategories(snap.bundleCategories))
            put("bundles_json", encodeBundles(snap.bundles))
            put("cart_json", encodePosCartJson(snap.cart))
            put("sales_log_json", encodeSalesRecordsJson(snap.salesLog))
            put("reversal_log_json", encodeSaleReversalsJson(snap.reversalLog))
            put("last_checkout_json", snap.lastCheckout?.let(::encodeLastCheckoutJson).orEmpty())
            put("events_json", encodeMarketEvents(snap.events))
            put("inventory_levels_json", encodeInventoryLevels(snap.inventoryLevels))
            put("inventory_movements_json", encodeInventoryMovements(snap.inventoryMovements))
            put("total_sales", snap.totalSales)
            put("tx_count", snap.txCount)
        }
        return JSONObject().apply {
            put("format", PosStore.BACKUP_FORMAT_ID)
            put("schemaVersion", PosStore.BACKUP_SCHEMA_VERSION)
            put("exportedAtMillis", System.currentTimeMillis())
            put("payload", payload)
        }.toString(2)
    }

    override suspend fun restoreFullBackupJson(jsonText: String): Result<Unit> = runCatching {
        ensureLegacyImported()
        val payload = parseValidatedBackupPayload(jsonText)
        val restored = payload.toSnapshot()
        database.withTransaction { replaceBusinessData(restored) }
    }

    private suspend fun ensureLegacyImported() {
        val importComplete = dao.metaValue(LEGACY_IMPORT_VERSION_KEY) == LEGACY_IMPORT_VERSION
        val cleanupComplete = dao.metaValue(LEGACY_CLEANUP_STATE_KEY) in LEGACY_CLEANUP_FINAL_STATES
        if (importComplete && cleanupComplete) return
        // ponytail: process-local lock is sufficient for this single-process app; add cross-process coordination only if the app gains another process.
        legacyImportMutex.withLock {
            if (dao.metaValue(LEGACY_IMPORT_VERSION_KEY) != LEGACY_IMPORT_VERSION) {
                val legacy = readLegacySnapshot()
                database.withTransaction {
                    if (dao.metaValue(LEGACY_IMPORT_VERSION_KEY) != LEGACY_IMPORT_VERSION) {
                        replaceBusinessData(legacy)
                        dao.putMeta(AppMetaEntity(LEGACY_IMPORT_VERSION_KEY, LEGACY_IMPORT_VERSION))
                    }
                }
            }
            retireLegacyBusinessData()
        }
    }

    /** Post-migration retirement only；此路徑不得讀取或改寫 Room business tables。 */
    private suspend fun retireLegacyBusinessData() {
        if (dao.metaValue(LEGACY_CLEANUP_STATE_KEY) in LEGACY_CLEANUP_FINAL_STATES) return
        val legacy = try {
            appContext.posPreferencesDataStore.data.first()
        } catch (_: IOException) {
            return
        }
        if (!legacy.isLegacyBusinessDataSafeToRetire()) {
            dao.putMeta(AppMetaEntity(LEGACY_CLEANUP_STATE_KEY, LEGACY_CLEANUP_PRESERVED_INVALID))
            return
        }
        try {
            if (legacy.hasLegacyBusinessKeys()) {
                appContext.posPreferencesDataStore.edit { it.removeLegacyBusinessKeys() }
            }
            if (!appContext.posPreferencesDataStore.data.first().hasLegacyBusinessKeys()) {
                dao.putMeta(AppMetaEntity(LEGACY_CLEANUP_STATE_KEY, LEGACY_CLEANUP_CLEARED))
            }
        } catch (_: IOException) {
            // Cleanup 不是啟動必要條件；不寫 state，下一次啟動再冪等重試。
        }
    }

    private suspend fun readLegacySnapshot(): PosPersistSnapshot {
        val prefs = appContext.posPreferencesDataStore.data.first()
        val products = decodeProducts(prefs[LEGACY_PRODUCTS_JSON_KEY].orEmpty())
        val productIds = products.mapTo(hashSetOf()) { it.id }
        val bundles = decodeBundles(prefs[LEGACY_BUNDLES_JSON_KEY].orEmpty()).map { bundle ->
            bundle.copy(components = bundle.components.filter { it.productId in productIds })
        }
        val saleResult = decodeSalesRecordsJsonResult(prefs[SALES_LOG_JSON_KEY].orEmpty())
        val rawSales = saleResult.getOrElse { emptyList() }
        val productNames = products.associate { it.id to it.name }
        val bundleNames = bundles.associate { it.id to it.name }
        val sales = rawSales.map { it.withMissingDisplayNames(productNames, bundleNames) }
        val saleIds = sales.mapTo(hashSetOf()) { it.id }
        val reversals = if (saleResult.isSuccess) {
            decodeSaleReversalsJson(prefs[SALES_REVERSAL_LOG_JSON_KEY].orEmpty())
                .filter { it.saleId in saleIds }
                .distinctBy { it.saleId }
        } else {
            emptyList()
        }
        val last = if (saleResult.isSuccess) {
            linkLastCheckoutToSales(
                decodeLastCheckoutJson(prefs[LAST_CHECKOUT_JSON_KEY].orEmpty()),
                sales,
            )
        } else {
            null
        }
        val active = activeSales(sales, reversals)
        return PosPersistSnapshot(
            products = products,
            categories = decodeCategories(prefs[LEGACY_CATEGORIES_JSON_KEY].orEmpty()),
            bundleCategories = decodeBundleCategories(prefs[LEGACY_BUNDLE_CATEGORIES_JSON_KEY].orEmpty()),
            bundles = bundles,
            cart = decodePosCartJson(prefs[LEGACY_CART_JSON_KEY].orEmpty()),
            totalSales = active.sumOf { it.total + it.tipAmount },
            txCount = active.size.toLong(),
            salesLog = sales,
            reversalLog = reversals,
            lastCheckout = last,
        )
    }

    private suspend fun readSnapshot(): PosPersistSnapshot = database.withTransaction {
        val products = readProducts()
        val bundles = readBundles()
        val sales = readSales()
        val reversalMeta = v2Dao.reversalMeta().associateBy { it.reversalId }
        val reversals = dao.reversals().map { it.toDomain(reversalMeta[it.id]) }
        val events = v2Dao.events().map { it.toDomain() }
        val inventoryLevels = v2Dao.inventoryLevels().map { it.toDomain() }
        val inventoryMovements = v2Dao.inventoryMovements().map { it.toDomain() }
        val summary = dao.activeSalesSummary()
        val last = dao.lastCheckout()?.saleId?.let { saleId ->
            sales.find { it.id == saleId }?.toLastCheckout()
        }
        val cartRows = dao.cartItems()
        PosPersistSnapshot(
            products = products,
            categories = dao.categories().map { Category(it.id, it.name) },
            bundleCategories = dao.bundleCategories().map { BundleCategory(it.id, it.name) },
            bundles = bundles,
            cart = PosCart(
                products = cartRows.filter { it.itemType == ROOM_ITEM_PRODUCT }.associate { it.itemId to it.quantity },
                bundles = cartRows.filter { it.itemType == ROOM_ITEM_BUNDLE }.associate { it.itemId to it.quantity },
            ),
            totalSales = summary.revenue,
            txCount = summary.txCount,
            salesLog = sales,
            reversalLog = reversals,
            lastCheckout = last,
            events = events,
            inventoryLevels = inventoryLevels,
            inventoryMovements = inventoryMovements,
        )
    }

    private suspend fun readProducts(): List<Product> {
        val meta = v2Dao.productMeta().associateBy { it.productId }
        val levels = v2Dao.inventoryLevels().associateBy { it.productId to it.locationKey }
        val active = v2Dao.activeEvents().also { check(it.size <= 1) }.singleOrNull()
        val locationKey = active?.let { InventoryLocation.event(it.id).key } ?: InventoryLocation.GENERAL_KEY
        return dao.products().map { row ->
            val v2 = meta[row.id]
            val tracked = v2?.trackInventory ?: (row.stock != null)
            row.toDomain(
                stock = if (tracked) levels[row.id to locationKey]?.quantity ?: 0 else null,
                cost = v2?.cost,
            )
        }
    }

    private suspend fun readBundles(): List<Bundle> {
        val components = dao.bundleComponents().groupBy { it.bundleId }
        return dao.bundles().map { row ->
            Bundle(
                id = row.id,
                name = row.name,
                price = row.price,
                categoryId = row.categoryId,
                components = components[row.id].orEmpty().map { BundleComponent(it.productId, it.quantity) },
            )
        }
    }

    private suspend fun readSales(): List<SaleRecord> {
        val lines = dao.saleLines().groupBy { it.saleId }
        val deductions = dao.stockDeductions().groupBy { it.saleId }
        val meta = v2Dao.saleMeta().associateBy { it.saleId }
        val snapshots = v2Dao.saleLineSnapshots().groupBy { it.saleId }
        val allocations = v2Dao.bundleAllocations().groupBy { it.saleId }
        return dao.sales().map { row ->
            row.toDomain(
                lines[row.id].orEmpty(),
                deductions[row.id].orEmpty(),
                meta[row.id],
                snapshots[row.id].orEmpty(),
                allocations[row.id].orEmpty(),
            )
        }
    }

    internal suspend fun localDeviceId(): String = database.withTransaction {
        deviceStateOrCreate(System.currentTimeMillis()).deviceId
    }

    override suspend fun diagnosticInfo(): SyncDiagnosticInfo = SyncDiagnosticInfo(
        deviceId = v2Dao.deviceState()?.deviceId,
        lastSyncAtMillis = v2Dao.lastSyncedAtMillis(),
        recentRequestIds = v2Dao.cloudValue(SyncCloudKeys.RECENT_REQUEST_IDS).jsonStrings(),
        recentErrorCodes = v2Dao.cloudValue(SyncCloudKeys.RECENT_ERROR_CODES).jsonStrings(),
    )

    internal suspend fun activateCloudSession(
        baseUrl: String,
        accessToken: String,
        refreshToken: String,
        userId: String,
        deviceId: String,
        cloudEpoch: Long,
        resetBaseline: Boolean,
    ) {
        val snapshot = if (resetBaseline) readSnapshot() else null
        val now = System.currentTimeMillis()
        database.withTransaction {
            v2Dao.putCloudState(
                listOf(
                    CloudStateEntity(SyncCloudKeys.BASE_URL, baseUrl.trimEnd('/')),
                    CloudStateEntity(SyncCloudKeys.ACCESS_TOKEN, accessToken),
                    CloudStateEntity(SyncCloudKeys.REFRESH_TOKEN, refreshToken),
                    CloudStateEntity(SyncCloudKeys.USER_ID, userId),
                ),
            )
            val previous = v2Dao.deviceState()
            v2Dao.putDeviceState(
                DeviceStateEntity(
                    deviceId = deviceId,
                    shortCode = previous?.shortCode ?: "A",
                    name = android.os.Build.MODEL.take(100).ifBlank { "Android" },
                    status = "ACTIVE",
                    cloudEpoch = cloudEpoch,
                    registeredAtMillis = previous?.registeredAtMillis ?: now,
                    lastSeenAtMillis = now,
                    retiredAtMillis = null,
                ),
            )
            if (snapshot != null) enqueueBaseline(snapshot, now)
        }
        SyncScheduler.enqueue(appContext)
    }

    internal suspend fun restoreCloudBootstrap(snapshot: PosPersistSnapshot, commitToken: String) {
        database.withTransaction {
            replaceBusinessData(snapshot)
            v2Dao.deleteAllOutbox()
            v2Dao.putCloudState(listOf(CloudStateEntity(SyncCloudKeys.TRANSFER_COMMIT_TOKEN, commitToken)))
        }
    }

    private suspend fun enqueueBaseline(snapshot: PosPersistSnapshot, now: Long) {
        v2Dao.deleteAllOutbox()
        snapshot.categories.forEachIndexed { index, row ->
            v2Dao.enqueueSyncOperation("MASTER", "CATEGORY", row.id, "UPSERT", row.toSyncJson("PRODUCT", index, now), now)
        }
        snapshot.bundleCategories.forEachIndexed { index, row ->
            v2Dao.enqueueSyncOperation("MASTER", "CATEGORY", row.id, "UPSERT", row.toSyncJson(index, now), now)
        }
        snapshot.products.forEach { row ->
            v2Dao.enqueueSyncOperation("MASTER", "PRODUCT", row.id, "UPSERT", row.toSyncJson(now), now)
        }
        snapshot.bundles.forEach { row ->
            v2Dao.enqueueSyncOperation("MASTER", "BUNDLE", row.id, "UPSERT", row.toSyncJson(now), now)
        }
        snapshot.events.forEach { row ->
            v2Dao.enqueueSyncOperation("MASTER", "EVENT", row.id, "UPSERT", row.toEntity().toSyncJson(), now)
        }
        snapshot.inventoryMovements.forEach { row ->
            v2Dao.enqueueSyncOperation("INVENTORY", "INVENTORY_MOVEMENT", row.id, "APPEND", row.toEntity().toSyncJson(), now)
        }
        snapshot.salesLog.forEach { sale ->
            val movements = snapshot.inventoryMovements.filter { it.relatedTransactionId == sale.id }.map { it.toEntity() }
            v2Dao.enqueueSyncOperation("TRANSACTION", "SALE", sale.id, "APPEND", sale.toSyncJson(movements), now)
        }
        snapshot.reversalLog.forEach { reversal ->
            val movements = snapshot.inventoryMovements.filter { it.relatedTransactionId == reversal.saleId && it.type == InventoryMovementType.VOID }
                .map { it.toEntity() }
            v2Dao.enqueueSyncOperation("TRANSACTION", "VOID", reversal.id, "APPEND", reversal.toSyncJson(movements), now)
        }
    }

    private suspend fun replaceBusinessData(snap: PosPersistSnapshot) {
        dao.deleteLastCheckout()
        v2Dao.deleteAllReversalMeta()
        dao.deleteAllReversals()
        v2Dao.deleteAllBundleAllocations()
        v2Dao.deleteAllSaleLineSnapshots()
        v2Dao.deleteAllSaleMeta()
        dao.deleteAllStockDeductions()
        dao.deleteAllSaleLines()
        dao.deleteAllSales()
        dao.deleteAllCartItems()
        dao.deleteAllBundleComponents()
        v2Dao.deleteAllBundleMeta()
        dao.deleteAllBundles()
        v2Dao.deleteAllInventoryMovements()
        v2Dao.deleteAllInventoryLevels()
        v2Dao.deleteAllProductMeta()
        dao.deleteAllProducts()
        v2Dao.deleteCategoryMeta("PRODUCT")
        dao.deleteAllCategories()
        v2Dao.deleteCategoryMeta("BUNDLE")
        dao.deleteAllBundleCategories()
        v2Dao.deleteAllEvents()

        insertCategories(snap.categories)
        insertProducts(snap.products)
        insertBundleCategories(snap.bundleCategories)
        insertBundles(snap.bundles)
        snap.events.forEach { v2Dao.upsertEvent(it.toEntity()) }
        replaceCart(snap.cart)
        snap.salesLog.forEachIndexed { index, sale ->
            insertSale(sale, index.toLong())
            insertLegacySaleV2(sale, index)
        }
        snap.reversalLog.forEachIndexed { index, reversal ->
            dao.insertReversal(
                ReversalEntity(
                    reversal.id,
                    index.toLong(),
                    reversal.saleId,
                    reversal.tsMillis,
                    reversal.reason.name,
                ),
            )
            val sale = snap.salesLog.first { it.id == reversal.saleId }
            v2Dao.insertReversalMeta(
                ReversalV2MetaEntity(
                    reversal.id,
                    reversal.eventId,
                    reversal.deviceId,
                    reversal.paymentMethod.takeIf { reversal.eventId != null || reversal.deviceId != null }?.name
                        ?: sale.paymentMethod.name,
                ),
            )
        }
        if (snap.inventoryLevels.isNotEmpty() || snap.inventoryMovements.isNotEmpty()) {
            v2Dao.deleteAllInventoryMovements()
            v2Dao.deleteAllInventoryLevels()
            if (snap.inventoryLevels.isNotEmpty()) {
                v2Dao.upsertInventoryLevels(snap.inventoryLevels.map { it.toEntity() })
            }
            if (snap.inventoryMovements.isNotEmpty()) {
                v2Dao.insertInventoryMovements(snap.inventoryMovements.map { it.toEntity() })
            }
        }
        snap.lastCheckout?.let { dao.replaceLastCheckout(LastCheckoutEntity(saleId = it.saleId)) }
    }

    private suspend fun replaceCategories(rows: List<Category>) {
        insertCategories(rows)
        if (rows.isEmpty()) {
            v2Dao.deleteCategoryMeta("PRODUCT")
            dao.deleteAllCategories()
        } else {
            val ids = rows.map { it.id }
            v2Dao.deleteCategoryMetaNotIn("PRODUCT", ids)
            dao.deleteCategoriesNotIn(ids)
        }
    }

    private suspend fun replaceProducts(
        rows: List<Product>,
        now: Long = System.currentTimeMillis(),
    ): List<InventoryMovementEntity> {
        val movements = insertProducts(rows, now)
        if (rows.isEmpty()) {
            v2Dao.deleteAllInventoryLevels()
            v2Dao.deleteAllProductMeta()
            dao.deleteAllProducts()
        } else {
            val ids = rows.map { it.id }
            v2Dao.deleteInventoryLevelsNotIn(ids)
            v2Dao.deleteProductMetaNotIn(ids)
            dao.deleteProductsNotIn(ids)
        }
        return movements
    }

    private suspend fun replaceBundleCategories(rows: List<BundleCategory>) {
        insertBundleCategories(rows)
        if (rows.isEmpty()) {
            v2Dao.deleteCategoryMeta("BUNDLE")
            dao.deleteAllBundleCategories()
        } else {
            val ids = rows.map { it.id }
            v2Dao.deleteCategoryMetaNotIn("BUNDLE", ids)
            dao.deleteBundleCategoriesNotIn(ids)
        }
    }

    private suspend fun replaceBundles(rows: List<Bundle>) {
        insertBundles(rows)
        if (rows.isEmpty()) {
            v2Dao.deleteAllBundleMeta()
            dao.deleteAllBundles()
        } else {
            val ids = rows.map { it.id }
            v2Dao.deleteBundleMetaNotIn(ids)
            dao.deleteBundlesNotIn(ids)
        }
    }

    private suspend fun insertCategories(rows: List<Category>) {
        if (rows.isEmpty()) return
        dao.upsertCategories(rows.mapIndexed { i, row -> CategoryEntity(row.id, row.name, i) })
        val now = System.currentTimeMillis()
        v2Dao.upsertCategoryMeta(rows.map { CategoryV2MetaEntity("PRODUCT", it.id, now, null) })
    }

    private suspend fun insertProducts(
        rows: List<Product>,
        now: Long = System.currentTimeMillis(),
    ): List<InventoryMovementEntity> {
        if (rows.isEmpty()) return emptyList()
        dao.upsertProducts(rows.mapIndexed { i, row ->
            ProductEntity(row.id, row.name, row.price, row.categoryId, null, i)
        })
        v2Dao.upsertProductMeta(rows.map { row ->
            require(row.cost == null || row.cost >= 0) { "product cost must be non-negative or null" }
            ProductV2MetaEntity(row.id, row.cost, row.stock != null, true, now, null)
        })
        val active = v2Dao.activeEvents().also { check(it.size <= 1) }.singleOrNull()
        val location = active?.let { InventoryLocation.event(it.id) } ?: InventoryLocation.General
        val movements = mutableListOf<InventoryMovementEntity>()
        rows.forEach { row ->
            if (row.stock == null) {
                v2Dao.deleteInventoryLevels(row.id)
            } else {
                val current = v2Dao.inventoryLevel(row.id, location.key)?.quantity ?: 0L
                when {
                    row.stock > current -> movements += moveInventoryInternal(
                        row.id, row.stock - current, null, location,
                        InventoryMovementType.ADJUSTMENT, null, now,
                    )
                    row.stock < current -> movements += moveInventoryInternal(
                        row.id, current - row.stock, location, null,
                        InventoryMovementType.ADJUSTMENT, null, now,
                    )
                    current == 0L && v2Dao.inventoryLevel(row.id, location.key) == null ->
                        v2Dao.upsertInventoryLevel(location.level(row.id, 0, now))
                }
            }
        }
        return movements
    }

    private suspend fun insertBundleCategories(rows: List<BundleCategory>) {
        if (rows.isNotEmpty()) {
            dao.upsertBundleCategories(rows.mapIndexed { i, row -> BundleCategoryEntity(row.id, row.name, i) })
            val now = System.currentTimeMillis()
            v2Dao.upsertCategoryMeta(rows.map { CategoryV2MetaEntity("BUNDLE", it.id, now, null) })
        }
    }

    private suspend fun insertBundles(rows: List<Bundle>) {
        if (rows.isEmpty()) return
        dao.upsertBundles(rows.mapIndexed { i, row -> BundleEntity(row.id, row.name, row.price, row.categoryId, i) })
        val now = System.currentTimeMillis()
        v2Dao.upsertBundleMeta(rows.map { BundleV2MetaEntity(it.id, true, now, null) })
        val components = rows.flatMap { bundle ->
            bundle.components.mapIndexed { i, component ->
                BundleComponentEntity(bundle.id, i, component.productId, component.qty)
            }
        }
        if (components.isNotEmpty()) dao.insertBundleComponents(components)
    }

    private suspend fun replaceCart(cart: PosCart) {
        dao.deleteAllCartItems()
        val rows = cart.products.filterValues { it > 0 }.map { CartItemEntity(ROOM_ITEM_PRODUCT, it.key, it.value) } +
            cart.bundles.filterValues { it > 0 }.map { CartItemEntity(ROOM_ITEM_BUNDLE, it.key, it.value) }
        if (rows.isNotEmpty()) dao.insertCartItems(rows)
    }

    private suspend fun insertSale(sale: SaleRecord, auditOrder: Long) {
        dao.insertSale(
            SaleEntity(
                sale.id,
                auditOrder,
                sale.tsMillis,
                sale.dateKey,
                sale.subtotal,
                sale.discount,
                sale.total,
                sale.paymentMethod.name,
                sale.tipAmount,
            ),
        )
        val lines = sale.toLineEntities()
        if (lines.isNotEmpty()) dao.insertSaleLines(lines)
        val deductions = sale.stockDeductions.map { (productId, quantity) ->
            SaleStockDeductionEntity(sale.id, productId, quantity)
        }
        if (deductions.isNotEmpty()) dao.insertStockDeductions(deductions)
    }

    private suspend fun insertLegacySaleV2(sale: SaleRecord, auditOrder: Int) {
        v2Dao.insertSaleMeta(
            SaleV2MetaEntity(
                saleId = sale.id,
                eventId = sale.eventId,
                deviceId = sale.deviceId,
                receiptNumber = sale.receiptNumber
                    ?: "LEGACY-A-${(auditOrder + 1).toString().padStart(4, '0')}",
                discountType = sale.discountType,
                discountValue = sale.discountValue,
                discountAmount = sale.discountAmount,
                netAdjustment = sale.netAdjustment,
            ),
        )
        val snapshots = sale.lineFinancialSnapshots.takeIf { it.isNotEmpty() }?.map { row ->
            SaleLineSnapshotEntity(
                sale.id,
                row.lineIndex,
                row.unitCostSnapshot,
                row.originalAmount,
                row.allocatedDiscount,
                row.allocatedAdjustment,
                row.finalAmount,
            )
        } ?: sale.checkoutLines.mapIndexed { index, line ->
            SaleLineSnapshotEntity(sale.id, index, null, line.lineSubtotal, 0, 0, line.lineSubtotal)
        }
        if (snapshots.isNotEmpty()) v2Dao.insertSaleLineSnapshots(snapshots)
        val allocations = sale.bundleComponentAllocations.map { row ->
            BundleComponentAllocationEntity(
                sale.id,
                row.lineIndex,
                row.allocationIndex,
                row.productId,
                row.productNameSnapshot,
                row.unitCostSnapshot,
                row.quantity,
                row.allocatedRevenue,
            )
        }
        if (allocations.isNotEmpty()) v2Dao.insertBundleAllocations(allocations)
    }

    private suspend fun moveInventoryInternal(
        productId: String,
        quantity: Long,
        from: InventoryLocation?,
        to: InventoryLocation?,
        type: InventoryMovementType,
        relatedTransactionId: String?,
        now: Long,
    ): InventoryMovementEntity {
        require(quantity > 0) { "inventory movement quantity must be positive" }
        require(from != null || to != null) { "inventory movement needs a source or destination" }
        require(from?.key != to?.key) { "inventory movement source and destination must differ" }
        check(v2Dao.productMeta(productId)?.trackInventory == true) { "product does not track inventory: $productId" }
        if (from != null) {
            val source = v2Dao.inventoryLevel(productId, from.key)
                ?: error("inventory source is missing: ${from.key}")
            check(source.quantity >= quantity) { "inventory is insufficient: $productId" }
            v2Dao.upsertInventoryLevel(source.copy(quantity = source.quantity - quantity, updatedAtMillis = now))
        }
        if (to != null) {
            val destination = v2Dao.inventoryLevel(productId, to.key)
            v2Dao.upsertInventoryLevel(
                destination?.copy(quantity = destination.quantity + quantity, updatedAtMillis = now)
                    ?: to.level(productId, quantity, now),
            )
        }
        val movement = InventoryMovementEntity(
                id = UUID.randomUUID().toString(),
                productId = productId,
                eventId = from?.eventId ?: to?.eventId,
                fromLocationKey = from?.key,
                toLocationKey = to?.key,
                movementType = type.name,
                quantity = quantity,
                relatedTransactionId = relatedTransactionId,
                occurredAtMillis = now,
            )
        v2Dao.insertInventoryMovement(movement)
        return movement
    }

    private suspend fun changeEventStatusInternal(
        eventId: String,
        status: MarketEventStatus,
        now: Long,
    ) {
        val current = v2Dao.event(eventId) ?: error("event not found: $eventId")
        val from = MarketEventStatus.valueOf(current.status)
        require((from == MarketEventStatus.PLANNED && status == MarketEventStatus.ACTIVE) ||
            (from == MarketEventStatus.ACTIVE && status == MarketEventStatus.CLOSED)) {
            "invalid event transition: $from -> $status"
        }
        if (status == MarketEventStatus.ACTIVE) {
            check(v2Dao.activeEvents().none { it.id != eventId }) { "another event is already active" }
        }
        v2Dao.upsertEvent(
            current.copy(
                status = status.name,
                actualOpenAtMillis = current.actualOpenAtMillis ?: now.takeIf { status == MarketEventStatus.ACTIVE },
                actualCloseAtMillis = now.takeIf { status == MarketEventStatus.CLOSED },
                updatedAtMillis = now,
            ),
        )
    }

    private fun buildBundleAllocations(
        saleId: String,
        lines: List<SaleCheckoutLine>,
        snapshots: List<SaleLineSnapshotEntity>,
        bundles: List<Bundle>,
        products: Map<String, Product>,
    ): List<BundleComponentAllocationEntity> {
        val bundlesById = bundles.associateBy { it.id }
        return lines.flatMapIndexed { lineIndex, line ->
            if (line !is SaleCheckoutLine.Bundle) return@flatMapIndexed emptyList()
            val bundle = bundlesById[line.bundleId] ?: return@flatMapIndexed emptyList()
            val quantities = bundle.components.map { it.qty * line.qty.toLong() }
            val weights = bundle.components.mapIndexed { index, component ->
                (products[component.productId]?.price ?: 0L) * quantities[index]
            }
            val revenue = allocateLargestRemainder(snapshots[lineIndex].finalAmount, weights)
            bundle.components.mapIndexed { allocationIndex, component ->
                val product = products[component.productId]
                    ?: error("bundle product missing: ${component.productId}")
                BundleComponentAllocationEntity(
                    saleId = saleId,
                    lineIndex = lineIndex,
                    allocationIndex = allocationIndex,
                    productId = product.id,
                    productNameSnapshot = product.name,
                    unitCostSnapshot = product.cost,
                    quantity = quantities[allocationIndex],
                    allocatedRevenue = revenue[allocationIndex],
                )
            }
        }
    }

    private fun SaleRecord.toLineEntities(): List<SaleLineEntity> {
        val productCart = cartSnapshot.toMutableMap()
        val bundleCart = bundleCartSnapshot.toMutableMap()
        val rows = checkoutLines.mapIndexed { index, line ->
            when (line) {
                is SaleCheckoutLine.Product -> SaleLineEntity(
                    id,
                    index,
                    ROOM_ITEM_PRODUCT,
                    line.productId,
                    productCart.remove(line.productId),
                    line.qty,
                    line.unitPrice,
                    line.lineSubtotal,
                    line.displayName,
                )
                is SaleCheckoutLine.Bundle -> SaleLineEntity(
                    id,
                    index,
                    ROOM_ITEM_BUNDLE,
                    line.bundleId,
                    bundleCart.remove(line.bundleId),
                    line.qty,
                    line.unitPrice,
                    line.lineSubtotal,
                    line.displayName,
                )
            }
        }.toMutableList()
        productCart.toSortedMap().forEach { (itemId, quantity) ->
            rows += SaleLineEntity(id, rows.size, ROOM_ITEM_PRODUCT, itemId, quantity, null, null, null, null)
        }
        bundleCart.toSortedMap().forEach { (itemId, quantity) ->
            rows += SaleLineEntity(id, rows.size, ROOM_ITEM_BUNDLE, itemId, quantity, null, null, null, null)
        }
        return rows
    }

    private fun SaleEntity.toDomain(
        lines: List<SaleLineEntity>,
        deductions: List<SaleStockDeductionEntity>,
        meta: SaleV2MetaEntity?,
        snapshots: List<SaleLineSnapshotEntity>,
        allocations: List<BundleComponentAllocationEntity>,
    ): SaleRecord = SaleRecord(
        id = id,
        tsMillis = tsMillis,
        dateKey = dateKey,
        subtotal = subtotal,
        discount = discount,
        total = total,
        cartSnapshot = lines.filter { it.itemType == ROOM_ITEM_PRODUCT && it.cartQuantity != null }
            .associate { it.itemRefId to it.cartQuantity!! },
        bundleCartSnapshot = lines.filter { it.itemType == ROOM_ITEM_BUNDLE && it.cartQuantity != null }
            .associate { it.itemRefId to it.cartQuantity!! },
        paymentMethod = decodePaymentMethodPersist(paymentMethod),
        tipAmount = tipAmount,
        checkoutLines = lines.mapNotNull { line ->
            val quantity = line.checkoutQuantity ?: return@mapNotNull null
            val unitPrice = line.unitPrice ?: return@mapNotNull null
            val subtotal = line.lineSubtotal ?: return@mapNotNull null
            when (line.itemType) {
                ROOM_ITEM_BUNDLE -> SaleCheckoutLine.Bundle(line.itemRefId, quantity, unitPrice, subtotal, line.displayName)
                else -> SaleCheckoutLine.Product(line.itemRefId, quantity, unitPrice, subtotal, line.displayName)
            }
        },
        stockDeductions = deductions.associate { it.productId to it.quantity },
        eventId = meta?.eventId,
        deviceId = meta?.deviceId,
        receiptNumber = meta?.receiptNumber,
        discountType = meta?.discountType,
        discountValue = meta?.discountValue,
        discountAmount = meta?.discountAmount ?: discount,
        netAdjustment = meta?.netAdjustment ?: 0,
        lineFinancialSnapshots = snapshots.map {
            SaleLineFinancialSnapshot(
                it.lineIndex,
                it.unitCostSnapshot,
                it.originalAmount,
                it.allocatedDiscount,
                it.allocatedAdjustment,
                it.finalAmount,
            )
        },
        bundleComponentAllocations = allocations.map {
            BundleRevenueAllocation(
                it.lineIndex,
                it.allocationIndex,
                it.productId,
                it.productNameSnapshot,
                it.unitCostSnapshot,
                it.quantity,
                it.allocatedRevenue,
            )
        },
    )

    private fun ProductEntity.toDomain(stock: Long?, cost: Long?) =
        Product(id, name, price, categoryId, stock, cost)

    private fun EventEntity.toDomain() = MarketEvent(
        id = id,
        name = name,
        code = code,
        type = MarketEventType.valueOf(eventType),
        startAtMillis = startAtMillis,
        endAtMillis = endAtMillis,
        actualOpenAtMillis = actualOpenAtMillis,
        actualCloseAtMillis = actualCloseAtMillis,
        timezone = timezone,
        location = location,
        status = MarketEventStatus.valueOf(status),
        updatedAtMillis = updatedAtMillis,
        deletedAtMillis = deletedAtMillis,
    )

    private fun MarketEvent.toEntity() = EventEntity(
        id = id,
        name = name,
        code = code,
        eventType = type.name,
        startAtMillis = startAtMillis,
        endAtMillis = endAtMillis,
        actualOpenAtMillis = actualOpenAtMillis,
        actualCloseAtMillis = actualCloseAtMillis,
        timezone = timezone,
        location = location,
        status = status.name,
        updatedAtMillis = updatedAtMillis,
        deletedAtMillis = deletedAtMillis,
    )

    private fun InventoryLevelEntity.toDomain() = InventoryLevel(
        productId = productId,
        location = InventoryLocation(InventoryLocationType.valueOf(locationType), eventId),
        quantity = quantity,
        updatedAtMillis = updatedAtMillis,
    )

    private fun InventoryLevel.toEntity() =
        InventoryLevelEntity(productId, location.key, location.type.name, location.eventId, quantity, updatedAtMillis)

    private fun InventoryMovementEntity.toDomain() = InventoryMovement(
        id = id,
        productId = productId,
        eventId = eventId,
        from = fromLocationKey?.toLocation(),
        to = toLocationKey?.toLocation(),
        type = InventoryMovementType.valueOf(movementType),
        quantity = quantity,
        relatedTransactionId = relatedTransactionId,
        occurredAtMillis = occurredAtMillis,
    )

    private fun InventoryMovement.toEntity() = InventoryMovementEntity(
        id,
        productId,
        eventId,
        from?.key,
        to?.key,
        type.name,
        quantity,
        relatedTransactionId,
        occurredAtMillis,
    )

    private fun String.toLocation() =
        if (this == InventoryLocation.GENERAL_KEY) InventoryLocation.General
        else InventoryLocation.event(removePrefix("EVENT:"))

    private fun InventoryLocation.level(productId: String, quantity: Long, now: Long) =
        InventoryLevelEntity(productId, key, type.name, eventId, quantity, now)

    private fun ReversalEntity.toDomain(meta: ReversalV2MetaEntity?) = SaleReversal(
        id = id,
        saleId = saleId,
        tsMillis = tsMillis,
        reason = ReversalReason.valueOf(reason),
        eventId = meta?.eventId,
        deviceId = meta?.deviceId,
        paymentMethod = meta?.paymentMethod?.let(::decodePaymentMethodPersist) ?: PaymentMethod.CASH,
    )

    private fun SaleRecord.toLastCheckout() = LastCheckout(
        saleId = id,
        tsMillis = tsMillis,
        total = total + tipAmount,
        productCart = cartSnapshot,
        bundleCart = bundleCartSnapshot,
        stockDeductions = stockDeductions,
    )

    private fun SaleRecord.withMissingDisplayNames(
        productNames: Map<String, String>,
        bundleNames: Map<String, String>,
    ) = copy(
        checkoutLines = checkoutLines.map { line ->
            when (line) {
                is SaleCheckoutLine.Product -> if (line.displayName.isNullOrBlank()) {
                    line.copy(displayName = productNames[line.productId])
                } else line
                is SaleCheckoutLine.Bundle -> if (line.displayName.isNullOrBlank()) {
                    line.copy(displayName = bundleNames[line.bundleId])
                } else line
            }
        },
    )

    private fun JSONObject.toSnapshot(): PosPersistSnapshot {
        require(optInt("payloadSchema", -1) == PosStore.BACKUP_SCHEMA_VERSION) {
            "backup payload schema is invalid"
        }
        val products = decodeArray("products_json", ::decodeProducts)
        val categories = decodeArray("categories_json", ::decodeCategories)
        val bundleCategories = decodeArray("bundle_categories_json", ::decodeBundleCategories)
        val bundles = decodeArray("bundles_json", ::decodeBundles)
        val events = decodeArray("events_json", ::decodeMarketEvents)
        val inventoryLevels = decodeArray("inventory_levels_json", ::decodeInventoryLevels)
        val inventoryMovements = decodeArray("inventory_movements_json", ::decodeInventoryMovements)
        val sales = decodeSalesRecordsJsonResult(requiredString("sales_log_json")).getOrThrow()
        val reversals = decodeArray("reversal_log_json", ::decodeSaleReversalsJson)
        requireUniqueIds("products_json", products.map { it.id })
        requireUniqueIds("categories_json", categories.map { it.id })
        requireUniqueIds("bundle_categories_json", bundleCategories.map { it.id })
        requireUniqueIds("bundles_json", bundles.map { it.id })
        requireUniqueIds("sales_log_json", sales.map { it.id })
        requireUniqueIds("reversal_log_json", reversals.map { it.id })
        requireUniqueIds("reversal saleId", reversals.map { it.saleId })
        val saleIds = sales.mapTo(hashSetOf()) { it.id }
        require(reversals.all { it.saleId in saleIds }) { "backup reversal has no matching sale" }

        val lastRaw = requiredString("last_checkout_json")
        val decodedLast = decodeLastCheckoutJson(lastRaw)
        require(lastRaw.isBlank() || decodedLast != null) { "backup last checkout is invalid" }
        val cartRaw = requiredString("cart_json")
        if (cartRaw.isNotBlank()) JSONObject(cartRaw)
        val active = activeSales(sales, reversals)
        return PosPersistSnapshot(
            products = products,
            categories = categories,
            bundleCategories = bundleCategories,
            bundles = bundles,
            cart = decodePosCartJson(cartRaw),
            totalSales = active.sumOf { it.total + it.tipAmount },
            txCount = active.size.toLong(),
            salesLog = sales,
            reversalLog = reversals,
            lastCheckout = linkLastCheckoutToSales(decodedLast, sales),
            events = events,
            inventoryLevels = inventoryLevels,
            inventoryMovements = inventoryMovements,
        )
    }

    private fun <T> JSONObject.decodeArray(key: String, decode: (String) -> List<T>): List<T> {
        val raw = requiredString(key)
        if (raw.isBlank()) return emptyList()
        val expected = JSONArray(raw).length()
        return decode(raw).also { require(it.size == expected) { "backup $key is invalid" } }
    }

    private fun JSONObject.requiredString(key: String): String {
        val value = opt(key)
        require(value is String) { "backup $key is missing or invalid" }
        return value
    }

    private fun requireUniqueIds(key: String, ids: List<String>) {
        require(ids.all { it.isNotBlank() }) { "backup $key contains a blank id" }
        require(ids.size == ids.toSet().size) { "backup $key contains duplicate ids" }
    }

    private suspend fun deviceStateOrCreate(now: Long): DeviceStateEntity {
        v2Dao.deviceState()?.let { return it }
        return DeviceStateEntity(
            deviceId = UUID.randomUUID().toString(),
            shortCode = "A",
            name = android.os.Build.MODEL.take(100).ifBlank { "Android" },
            status = "ACTIVE",
            cloudEpoch = 0,
            registeredAtMillis = now,
            lastSeenAtMillis = now,
            retiredAtMillis = null,
        ).also { v2Dao.putDeviceState(it) }
    }

    private suspend fun scheduleSyncIfConfigured() {
        if (v2Dao.cloudValue(SyncCloudKeys.ACCESS_TOKEN) != null) SyncScheduler.enqueue(appContext)
    }

    companion object {
        private const val LEGACY_IMPORT_VERSION = "3"
        private val LEGACY_CLEANUP_FINAL_STATES = setOf(
            LEGACY_CLEANUP_CLEARED,
            LEGACY_CLEANUP_PRESERVED_INVALID,
        )
        private val legacyImportMutex = Mutex()
        private val BUSINESS_TABLES = arrayOf(
            "categories",
            "products",
            "bundle_categories",
            "bundles",
            "bundle_components",
            "cart_items",
            "sales",
            "sale_lines",
            "sale_stock_deductions",
            "reversals",
            "last_checkout",
            "product_v2_meta",
            "events",
            "inventory_levels",
            "inventory_movements",
            "sale_v2_meta",
            "sale_line_snapshots",
            "bundle_component_allocations",
            "reversal_v2_meta",
        )
    }
}

private fun String?.jsonStrings(): List<String> = runCatching {
    val rows = JSONArray(this ?: "[]")
    List(rows.length()) { rows.getString(it) }
}.getOrDefault(emptyList())
