package com.lambliver.stallpos.data

import com.lambliver.stallpos.domain.Bundle
import com.lambliver.stallpos.domain.BundleComponent
import com.lambliver.stallpos.domain.IntegritySnapshot
import com.lambliver.stallpos.domain.LastCheckout
import com.lambliver.stallpos.domain.OutboxAuditRow
import com.lambliver.stallpos.domain.Product
import org.json.JSONObject

internal object IntegritySnapshots {
    fun fromBackupPayload(payload: JSONObject): IntegritySnapshot = IntegritySnapshot(
        products = decodeProducts(payload.optString("products_json", "[]")),
        categories = decodeCategories(payload.optString("categories_json", "[]")),
        bundleCategories = decodeBundleCategories(payload.optString("bundle_categories_json", "[]")),
        bundles = decodeBundles(payload.optString("bundles_json", "[]")),
        sales = decodeSalesRecordsJson(payload.optString("sales_log_json", "[]")),
        reversals = decodeSaleReversalsJson(payload.optString("reversal_log_json", "[]")),
        events = decodeMarketEvents(payload.optString("events_json", "[]")),
        inventoryLevels = decodeInventoryLevels(payload.optString("inventory_levels_json", "[]")),
        inventoryMovements = decodeInventoryMovements(payload.optString("inventory_movements_json", "[]")),
        lastCheckout = decodeLastCheckoutJson(payload.optString("last_checkout_json", "")),
    )

    suspend fun fromV2(posDao: PosRoomDao, v2Dao: V2RoomDao): IntegritySnapshot {
        val products = posDao.products()
        val meta = v2Dao.productMeta().associateBy { it.productId }
        val components = posDao.bundleComponents().groupBy { it.bundleId }
        val lines = posDao.saleLines().groupBy { it.saleId }
        val deductions = posDao.stockDeductions().groupBy { it.saleId }
        val saleMeta = v2Dao.saleMeta().associateBy { it.saleId }
        val snapshots = v2Dao.saleLineSnapshots().groupBy { it.saleId }
        val allocations = v2Dao.bundleAllocations().groupBy { it.saleId }
        val reversalMeta = v2Dao.reversalMeta().associateBy { it.reversalId }
        val summary = posDao.activeSalesSummary()
        return IntegritySnapshot(
            products = products.map { row ->
                val productMeta = meta[row.id]
                val tracked = productMeta?.trackInventory ?: (row.stock != null)
                Product(
                    row.id,
                    row.name,
                    row.price,
                    row.categoryId,
                    if (tracked) row.stock else null,
                    productMeta?.cost,
                    productMeta?.isActive ?: true,
                )
            },
            categories = posDao.categories().map { com.lambliver.stallpos.domain.Category(it.id, it.name) },
            bundleCategories = posDao.bundleCategories().map {
                com.lambliver.stallpos.domain.BundleCategory(it.id, it.name)
            },
            bundles = posDao.bundles().map { bundle ->
                Bundle(
                    bundle.id,
                    bundle.name,
                    bundle.price,
                    bundle.categoryId,
                    components[bundle.id].orEmpty().sortedBy { it.componentIndex }.map {
                        BundleComponent(it.productId, it.quantity)
                    },
                )
            },
            sales = posDao.sales().map { sale ->
                sale.toDomain(
                    lines[sale.id].orEmpty(),
                    deductions[sale.id].orEmpty(),
                    saleMeta[sale.id],
                    snapshots[sale.id].orEmpty(),
                    allocations[sale.id].orEmpty(),
                )
            },
            reversals = posDao.reversals().map { it.toDomain(reversalMeta[it.id]) },
            events = v2Dao.events().map { it.toDomain() },
            inventoryLevels = v2Dao.inventoryLevels().map { it.toDomain() },
            inventoryMovements = v2Dao.inventoryMovements().map { it.toDomain() },
            outbox = v2Dao.outboxRows().map {
                OutboxAuditRow(it.operationId, it.status, it.lastErrorCode)
            },
            lastCheckout = posDao.lastCheckout()?.let {
                LastCheckout(it.saleId, 0L, 0L, emptyMap())
            },
            reportedRevenue = summary.revenue,
            reportedTxCount = summary.txCount,
        )
    }
}
