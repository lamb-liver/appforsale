package com.lambliver.stallpos.data

import com.lambliver.stallpos.domain.*
import org.json.JSONArray
import org.json.JSONObject

internal fun buildBundleAllocations(
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

internal fun SaleRecord.toLineEntities(): List<SaleLineEntity> {
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

internal fun SaleEntity.toDomain(
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

internal fun ProductEntity.toDomain(stock: Long?, cost: Long?, isActive: Boolean = true) =
    Product(id, name, price, categoryId, stock, cost, isActive)

internal fun EventEntity.toDomain() = MarketEvent(
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

internal fun MarketEvent.toEntity() = EventEntity(
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

internal fun InventoryLevelEntity.toDomain() = InventoryLevel(
    productId = productId,
    location = InventoryLocation(InventoryLocationType.valueOf(locationType), eventId),
    quantity = quantity,
    updatedAtMillis = updatedAtMillis,
)

internal fun InventoryLevel.toEntity() =
    InventoryLevelEntity(productId, location.key, location.type.name, location.eventId, quantity, updatedAtMillis)

internal fun InventoryMovementEntity.toDomain() = InventoryMovement(
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

internal fun InventoryMovement.toEntity() = InventoryMovementEntity(
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

internal fun String.toLocation() =
    if (this == InventoryLocation.GENERAL_KEY) InventoryLocation.General
    else InventoryLocation.event(removePrefix("EVENT:"))

internal fun InventoryLocation.level(productId: String, quantity: Long, now: Long) =
    InventoryLevelEntity(productId, key, type.name, eventId, quantity, now)

internal fun ReversalEntity.toDomain(meta: ReversalV2MetaEntity?) = SaleReversal(
    id = id,
    saleId = saleId,
    tsMillis = tsMillis,
    reason = ReversalReason.valueOf(reason),
    eventId = meta?.eventId,
    deviceId = meta?.deviceId,
    paymentMethod = meta?.paymentMethod?.let(::decodePaymentMethodPersist) ?: PaymentMethod.CASH,
)

internal fun SaleRecord.toLastCheckout() = LastCheckout(
    saleId = id,
    tsMillis = tsMillis,
    total = total + tipAmount,
    productCart = cartSnapshot,
    bundleCart = bundleCartSnapshot,
    stockDeductions = stockDeductions,
)

internal fun SaleRecord.withMissingDisplayNames(
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

internal fun JSONObject.toSnapshot(): PosPersistSnapshot {
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

internal fun <T> JSONObject.decodeArray(key: String, decode: (String) -> List<T>): List<T> {
    val raw = requiredString(key)
    if (raw.isBlank()) return emptyList()
    val expected = JSONArray(raw).length()
    return decode(raw).also { require(it.size == expected) { "backup $key is invalid" } }
}

internal fun JSONObject.requiredString(key: String): String {
    val value = opt(key)
    require(value is String) { "backup $key is missing or invalid" }
    return value
}

internal fun requireUniqueIds(key: String, ids: List<String>) {
    require(ids.all { it.isNotBlank() }) { "backup $key contains a blank id" }
    require(ids.size == ids.toSet().size) { "backup $key contains duplicate ids" }
}

