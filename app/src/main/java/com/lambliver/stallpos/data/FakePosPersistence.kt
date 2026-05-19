package com.lambliver.stallpos.data

import androidx.annotation.VisibleForTesting
import com.lambliver.stallpos.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * 記憶體版 [PosPersistence]，供單元測試 mock 單一 seam。
 * 正式 UI 路徑請使用 [PosStore]；Lint `VisibleForTests` 會攔截 main 誤用。
 */
@VisibleForTesting
internal class FakePosPersistence(initial: PosPersistSnapshot = PosPersistSnapshot()) : PosPersistence {

    /** 設為非 null 時，[commitCheckout] 會拋出（供 VM 失敗還原測試）。 */
    var checkoutThrows: Throwable? = null

    private val state = MutableStateFlow(initial)

    override val snapshot: Flow<PosPersistSnapshot> = state

    override val productsFlow: Flow<List<Product>> = state.map { it.products }
    override val categoriesFlow: Flow<List<Category>> = state.map { it.categories }
    override val bundleCategoriesFlow: Flow<List<BundleCategory>> = state.map { it.bundleCategories }
    override val bundlesFlow: Flow<List<Bundle>> = state.map { it.bundles }
    override val cartFlow: Flow<PosCart> = state.map { it.cart }
    override val totalSalesFlow: Flow<Long> = state.map { it.totalSales }
    override val txCountFlow: Flow<Long> = state.map { it.txCount }
    override val salesLogFlow: Flow<List<SaleRecord>> = state.map { it.salesLog }
    override val lastCheckoutFlow: Flow<LastCheckout?> = state.map { it.lastCheckout }

    override suspend fun applyCatalog(plan: CatalogPersistPlan) {
        state.update { cur ->
            cur.copy(
                products = plan.products ?: cur.products,
                categories = plan.categories ?: cur.categories,
                bundleCategories = plan.bundleCategories ?: cur.bundleCategories,
                bundles = plan.bundles ?: cur.bundles,
                cart = plan.cart ?: cur.cart,
            )
        }
    }

    override suspend fun saveCart(cart: PosCart) {
        state.update { it.copy(cart = cart) }
    }

    override suspend fun clearCart() {
        state.update { it.copy(cart = PosCart()) }
    }

    override suspend fun commitCheckout(request: CheckoutWriteRequest) {
        checkoutThrows?.let { throw it }
        require(request.total >= 0L) { "checkout total must be non-negative, got ${request.total}" }
        val tip = request.tipAmount.coerceAtLeast(0L)
        val now = System.currentTimeMillis()
        state.update { cur ->
            val products = cur.products
            val resolvedLines =
                request.checkoutLines.takeIf { it.isNotEmpty() }
                    ?: buildProductLinesFromCart(request.productCart, products)
            val resolvedDeductions =
                request.stockDeductions.takeIf { it.isNotEmpty() }
                    ?: request.productCart.mapValues { it.value.toLong() }

            val record = SaleRecord(
                tsMillis = now,
                dateKey = request.dateKey,
                subtotal = request.subtotal,
                discount = request.discount,
                total = request.total,
                cartSnapshot = request.productCart,
                bundleCartSnapshot = request.bundleCart,
                paymentMethod = request.paymentMethod,
                tipAmount = tip,
                checkoutLines = resolvedLines,
                stockDeductions = resolvedDeductions,
            )

            val deducted = products.map { p ->
                val qty = resolvedDeductions[p.id] ?: return@map p
                val s = p.stock ?: return@map p
                p.copy(stock = (s - qty).coerceAtLeast(0L))
            }

            val log = cur.salesLog.toMutableList()
            log.add(record)
            val trimmed = if (log.size > 5000) log.takeLast(5000) else log

            cur.copy(
                products = deducted,
                cart = PosCart(),
                lastCheckout = LastCheckout(
                    now,
                    request.total + tip,
                    request.productCart,
                    request.bundleCart,
                    resolvedDeductions,
                ),
                totalSales = cur.totalSales + request.total + tip,
                txCount = cur.txCount + 1,
                salesLog = trimmed,
            )
        }
    }

    override suspend fun undoLastCheckout() {
        state.update { cur -> cur.applyUndoIfPossible() ?: cur }
    }

    override suspend fun exportFullBackupJson(): String {
        val cur = state.value
        val payload = JSONObject().apply {
            put("payloadSchema", PosStore.BACKUP_SCHEMA_VERSION)
            put("products_json", encodeProducts(cur.products))
            put("categories_json", encodeCategories(cur.categories))
            put("bundle_categories_json", encodeBundleCategories(cur.bundleCategories))
            put("bundles_json", encodeBundles(cur.bundles))
            put("cart_json", encodePosCartJson(cur.cart))
            put("sales_log_json", encodeSalesRecordsJson(cur.salesLog))
            put("last_checkout_json", cur.lastCheckout?.let { encodeLastCheckoutJson(it) } ?: "")
            put("total_sales", cur.totalSales)
            put("tx_count", cur.txCount)
        }
        return JSONObject().apply {
            put("format", PosStore.BACKUP_FORMAT_ID)
            put("schemaVersion", PosStore.BACKUP_SCHEMA_VERSION)
            put("exportedAtMillis", System.currentTimeMillis())
            put("payload", payload)
        }.toString(2)
    }

    override suspend fun restoreFullBackupJson(jsonText: String): Result<Unit> = runCatching {
        val payload = parseValidatedBackupPayload(jsonText)
        state.value = PosPersistSnapshot(
            products = decodeProducts(payload.optString("products_json", "")),
            categories = decodeCategories(payload.optString("categories_json", "")),
            bundleCategories = decodeBundleCategories(payload.optString("bundle_categories_json", "")),
            bundles = decodeBundles(payload.optString("bundles_json", "")),
            cart = decodePosCartJson(payload.optString("cart_json", "")),
            salesLog = decodeSalesRecordsJson(payload.optString("sales_log_json", "")),
            lastCheckout = decodeLastCheckoutJson(payload.optString("last_checkout_json", "")),
            totalSales = payload.optLong("total_sales", 0L).coerceAtLeast(0L),
            txCount = payload.optLong("tx_count", 0L).coerceAtLeast(0L),
        )
    }
}
