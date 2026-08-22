package com.lambliver.stallpos.data

import com.lambliver.stallpos.domain.*
import kotlinx.coroutines.flow.Flow

/** 單一持久化 seam：讀用 Flow，寫用語意化原子操作。 */
interface PosPersistence {
    val productsFlow: Flow<List<Product>>
    val categoriesFlow: Flow<List<Category>>
    val bundleCategoriesFlow: Flow<List<BundleCategory>>
    val bundlesFlow: Flow<List<Bundle>>
    val cartFlow: Flow<PosCart>
    /** Legacy cached aggregate；reporting 必須由 Sales + Reversals 派生。 */
    val totalSalesFlow: Flow<Long>
    /** Legacy cached aggregate；reporting 必須由 Sales + Reversals 派生。 */
    val txCountFlow: Flow<Long>
    val salesLogFlow: Flow<List<SaleRecord>>
    val reversalLogFlow: Flow<List<SaleReversal>>
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
    /** Legacy cached aggregate；不是 reporting source of truth。 */
    val totalSales: Long = 0L,
    /** Legacy cached aggregate；不是 reporting source of truth。 */
    val txCount: Long = 0L,
    val salesLog: List<SaleRecord> = emptyList(),
    val reversalLog: List<SaleReversal> = emptyList(),
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
