package com.lambliver.appforsale.data

import com.lambliver.appforsale.domain.*
import kotlinx.coroutines.flow.Flow

/** 單一持久化 seam：讀用 Flow，寫用語意化原子操作。 */
interface PosPersistence {
    val productsFlow: Flow<List<Product>>
    val categoriesFlow: Flow<List<Category>>
    val bundleCategoriesFlow: Flow<List<BundleCategory>>
    val bundlesFlow: Flow<List<Bundle>>
    val cartFlow: Flow<PosCart>
    val totalSalesFlow: Flow<Long>
    val txCountFlow: Flow<Long>
    val salesLogFlow: Flow<List<SaleRecord>>
    val lastCheckoutFlow: Flow<LastCheckout?>

    val snapshot: Flow<PosPersistSnapshot>

    suspend fun applyCatalog(plan: CatalogPersistPlan)
    suspend fun saveCart(cart: PosCart)
    suspend fun clearCart()
    suspend fun undoLastCheckout()
    suspend fun commitCheckout(request: CheckoutWriteRequest)
    suspend fun exportFullBackupJson(): String
    suspend fun restoreFullBackupJson(jsonText: String): Result<Unit>
}

data class PosPersistSnapshot(
    val products: List<Product> = emptyList(),
    val categories: List<Category> = emptyList(),
    val bundleCategories: List<BundleCategory> = emptyList(),
    val bundles: List<Bundle> = emptyList(),
    val cart: PosCart = PosCart(),
    val totalSales: Long = 0L,
    val txCount: Long = 0L,
    val salesLog: List<SaleRecord> = emptyList(),
    val lastCheckout: LastCheckout? = null,
)

/** 目錄寫入計畫：`null` 欄位表示不更新該鍵。 */
data class CatalogPersistPlan(
    val products: List<Product>? = null,
    val categories: List<Category>? = null,
    val bundleCategories: List<BundleCategory>? = null,
    val bundles: List<Bundle>? = null,
    val cart: PosCart? = null,
)
