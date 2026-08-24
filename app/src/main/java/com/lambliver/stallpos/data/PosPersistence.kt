package com.lambliver.stallpos.data

import com.lambliver.stallpos.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** 單一持久化 seam：讀用 Flow，寫用語意化原子操作。 */
interface PosPersistence {
    val productsFlow: Flow<List<Product>>
    val categoriesFlow: Flow<List<Category>>
    val bundleCategoriesFlow: Flow<List<BundleCategory>>
    val bundlesFlow: Flow<List<Bundle>>
    val cartFlow: Flow<PosCart>
    /** 由 active Sales 派生的有效營收。 */
    val totalSalesFlow: Flow<Long>
    /** 由 active Sales 派生的有效筆數。 */
    val txCountFlow: Flow<Long>
    val salesLogFlow: Flow<List<SaleRecord>>
    val reversalLogFlow: Flow<List<SaleReversal>>
    val lastCheckoutFlow: Flow<LastCheckout?>
    val eventsFlow: Flow<List<MarketEvent>> get() = flowOf(emptyList())
    val inventoryLevelsFlow: Flow<List<InventoryLevel>> get() = flowOf(emptyList())

    val snapshot: Flow<PosPersistSnapshot>

    suspend fun applyCatalog(plan: CatalogPersistPlan)
    suspend fun saveCart(cart: PosCart)
    suspend fun clearCart()
    suspend fun undoLastCheckout()
    suspend fun commitCheckout(request: CheckoutWriteRequest)
    suspend fun exportFullBackupJson(): String
    suspend fun restoreFullBackupJson(jsonText: String): Result<Unit>
    suspend fun saveEvent(event: MarketEvent): Unit = error("Events require v2 Room persistence")
    suspend fun changeEventStatus(eventId: String, status: MarketEventStatus): Unit =
        error("Events require v2 Room persistence")
    suspend fun moveInventory(
        productId: String,
        quantity: Long,
        from: InventoryLocation?,
        to: InventoryLocation?,
        type: InventoryMovementType,
    ): Unit = error("Inventory requires v2 Room persistence")
}

data class PosPersistSnapshot(
    val products: List<Product> = emptyList(),
    val categories: List<Category> = emptyList(),
    val bundleCategories: List<BundleCategory> = emptyList(),
    val bundles: List<Bundle> = emptyList(),
    val cart: PosCart = PosCart(),
    /** 由 active Sales 派生的有效營收。 */
    val totalSales: Long = 0L,
    /** 由 active Sales 派生的有效筆數。 */
    val txCount: Long = 0L,
    val salesLog: List<SaleRecord> = emptyList(),
    val reversalLog: List<SaleReversal> = emptyList(),
    val lastCheckout: LastCheckout? = null,
    val events: List<MarketEvent> = emptyList(),
    val inventoryLevels: List<InventoryLevel> = emptyList(),
)

/** 目錄寫入計畫：`null` 欄位表示不更新該鍵。 */
data class CatalogPersistPlan(
    val products: List<Product>? = null,
    val categories: List<Category>? = null,
    val bundleCategories: List<BundleCategory>? = null,
    val bundles: List<Bundle>? = null,
    val cart: PosCart? = null,
)
