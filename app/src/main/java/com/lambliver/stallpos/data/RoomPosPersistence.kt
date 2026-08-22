package com.lambliver.stallpos.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.lambliver.stallpos.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/** Room 3 runtime persistence；DataStore 只作一次性 legacy import 與 UI preferences。 */
internal class RoomPosPersistence(
    context: Context,
    private val database: StallPosDatabase = StallPosDatabase.get(context),
) : PosPersistence {
    private val appContext = context.applicationContext
    private val dao = database.posDao()

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

    override suspend fun applyCatalog(plan: CatalogPersistPlan) {
        ensureLegacyImported()
        database.withWriteTransaction {
            if (plan.bundles != null) dao.deleteAllBundleComponents()
            plan.products?.let { replaceProducts(it) }
            plan.bundles?.let { replaceBundles(it) }
            plan.categories?.let { replaceCategories(it) }
            plan.bundleCategories?.let { replaceBundleCategories(it) }
            plan.cart?.let { replaceCart(it) }
        }
    }

    override suspend fun saveCart(cart: PosCart) {
        ensureLegacyImported()
        database.withWriteTransaction { replaceCart(cart) }
    }

    override suspend fun clearCart() = saveCart(PosCart())

    override suspend fun commitCheckout(request: CheckoutWriteRequest) {
        require(request.total >= 0L) { "checkout total must be non-negative, got ${request.total}" }
        require(request.productCart.values.all { it >= 0 } && request.bundleCart.values.all { it >= 0 }) {
            "checkout cart quantity must be non-negative"
        }
        ensureLegacyImported()
        database.withWriteTransaction {
            val products = dao.products().map { it.toDomain() }
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
            val sale = SaleRecord(
                id = UUID.randomUUID().toString(),
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
            insertSale(sale, dao.nextSaleAuditOrder())
            deductions.forEach { (productId, quantity) ->
                val stock = productsById.getValue(productId).stock ?: return@forEach
                dao.updateProductStock(productId, stock - quantity)
            }
            dao.deleteAllCartItems()
            dao.replaceLastCheckout(LastCheckoutEntity(saleId = sale.id))
        }
    }

    override suspend fun undoLastCheckout() {
        ensureLegacyImported()
        database.withWriteTransaction {
            val last = dao.lastCheckout() ?: return@withWriteTransaction
            val saleEntity = dao.sale(last.saleId) ?: return@withWriteTransaction
            if (dao.reversalCountForSale(last.saleId) != 0) return@withWriteTransaction
            val sale = saleEntity.toDomain(
                dao.saleLines(last.saleId),
                dao.stockDeductions(last.saleId),
            )
            val products = dao.products().associateBy { it.id }
            sale.stockDeductions.forEach { (productId, quantity) ->
                val product = products[productId] ?: return@forEach
                product.stock?.let { dao.updateProductStock(productId, it + quantity) }
            }
            replaceCart(PosCart(sale.cartSnapshot, sale.bundleCartSnapshot))
            dao.insertReversal(
                ReversalEntity(
                    id = UUID.randomUUID().toString(),
                    auditOrder = dao.nextReversalAuditOrder(),
                    saleId = sale.id,
                    tsMillis = System.currentTimeMillis(),
                    reason = ReversalReason.UNDO_LAST_CHECKOUT.name,
                ),
            )
            dao.deleteLastCheckout()
        }
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
        database.withWriteTransaction { replaceBusinessData(restored) }
    }

    private suspend fun ensureLegacyImported() {
        val importComplete = dao.metaValue(LEGACY_IMPORT_VERSION_KEY) == LEGACY_IMPORT_VERSION
        val cleanupComplete = dao.metaValue(LEGACY_CLEANUP_STATE_KEY) in LEGACY_CLEANUP_FINAL_STATES
        if (importComplete && cleanupComplete) return
        // ponytail: process-local lock is sufficient for this single-process app; add cross-process coordination only if the app gains another process.
        legacyImportMutex.withLock {
            if (dao.metaValue(LEGACY_IMPORT_VERSION_KEY) != LEGACY_IMPORT_VERSION) {
                val legacy = readLegacySnapshot()
                database.withWriteTransaction {
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

    private suspend fun readSnapshot(): PosPersistSnapshot = database.withReadTransaction {
        val products = dao.products().map { it.toDomain() }
        val bundles = readBundles()
        val sales = readSales()
        val reversals = dao.reversals().map { it.toDomain() }
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
        )
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
        return dao.sales().map { row -> row.toDomain(lines[row.id].orEmpty(), deductions[row.id].orEmpty()) }
    }

    private suspend fun replaceBusinessData(snap: PosPersistSnapshot) {
        dao.deleteLastCheckout()
        dao.deleteAllReversals()
        dao.deleteAllStockDeductions()
        dao.deleteAllSaleLines()
        dao.deleteAllSales()
        dao.deleteAllCartItems()
        dao.deleteAllBundleComponents()
        dao.deleteAllBundles()
        dao.deleteAllProducts()
        dao.deleteAllCategories()
        dao.deleteAllBundleCategories()

        insertCategories(snap.categories)
        insertProducts(snap.products)
        insertBundleCategories(snap.bundleCategories)
        insertBundles(snap.bundles)
        replaceCart(snap.cart)
        snap.salesLog.forEachIndexed { index, sale -> insertSale(sale, index.toLong()) }
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
        }
        snap.lastCheckout?.let { dao.replaceLastCheckout(LastCheckoutEntity(saleId = it.saleId)) }
    }

    private suspend fun replaceCategories(rows: List<Category>) {
        insertCategories(rows)
        if (rows.isEmpty()) dao.deleteAllCategories() else dao.deleteCategoriesNotIn(rows.map { it.id })
    }

    private suspend fun replaceProducts(rows: List<Product>) {
        insertProducts(rows)
        if (rows.isEmpty()) dao.deleteAllProducts() else dao.deleteProductsNotIn(rows.map { it.id })
    }

    private suspend fun replaceBundleCategories(rows: List<BundleCategory>) {
        insertBundleCategories(rows)
        if (rows.isEmpty()) dao.deleteAllBundleCategories() else dao.deleteBundleCategoriesNotIn(rows.map { it.id })
    }

    private suspend fun replaceBundles(rows: List<Bundle>) {
        insertBundles(rows)
        if (rows.isEmpty()) dao.deleteAllBundles() else dao.deleteBundlesNotIn(rows.map { it.id })
    }

    private suspend fun insertCategories(rows: List<Category>) {
        if (rows.isNotEmpty()) dao.upsertCategories(rows.mapIndexed { i, row -> CategoryEntity(row.id, row.name, i) })
    }

    private suspend fun insertProducts(rows: List<Product>) {
        if (rows.isNotEmpty()) {
            dao.upsertProducts(rows.mapIndexed { i, row ->
                ProductEntity(row.id, row.name, row.price, row.categoryId, row.stock, i)
            })
        }
    }

    private suspend fun insertBundleCategories(rows: List<BundleCategory>) {
        if (rows.isNotEmpty()) {
            dao.upsertBundleCategories(rows.mapIndexed { i, row -> BundleCategoryEntity(row.id, row.name, i) })
        }
    }

    private suspend fun insertBundles(rows: List<Bundle>) {
        if (rows.isEmpty()) return
        dao.upsertBundles(rows.mapIndexed { i, row -> BundleEntity(row.id, row.name, row.price, row.categoryId, i) })
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
    )

    private fun ProductEntity.toDomain() = Product(id, name, price, categoryId, stock)

    private fun ReversalEntity.toDomain() = SaleReversal(
        id = id,
        saleId = saleId,
        tsMillis = tsMillis,
        reason = ReversalReason.valueOf(reason),
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
        )
    }
}
