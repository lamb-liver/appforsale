package com.lambliver.stallpos.data

import android.content.Context
import androidx.datastore.preferences.core.*
import com.lambliver.stallpos.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.util.UUID

/** v1.3 DataStore persistence；僅保留供 legacy migration 與相容性測試。正式 runtime 使用 [RoomPosPersistence]。 */
class PosStore(private val context: Context) : PosPersistence {

    companion object {
        const val BACKUP_FORMAT_ID = "stallpos_pos_backup"
        /** v1.0.x 匯出格式；還原時仍接受 */
        const val LEGACY_BACKUP_FORMAT_ID = "appforsale_pos_backup"
        const val BACKUP_SCHEMA_VERSION = 4
    }

    override val snapshot: Flow<PosPersistSnapshot> = context.posPreferencesDataStore.data.map { prefs ->
        val sales = decodeSalesRecordsJson(prefs[SALES_LOG_JSON_KEY].orEmpty())
        val reversals = decodeSaleReversalsJson(prefs[SALES_REVERSAL_LOG_JSON_KEY].orEmpty())
        PosPersistSnapshot(
            products = decodeProducts(prefs[LEGACY_PRODUCTS_JSON_KEY].orEmpty()),
            categories = decodeCategories(prefs[LEGACY_CATEGORIES_JSON_KEY].orEmpty()),
            bundleCategories = decodeBundleCategories(prefs[LEGACY_BUNDLE_CATEGORIES_JSON_KEY].orEmpty()),
            bundles = decodeBundles(prefs[LEGACY_BUNDLES_JSON_KEY].orEmpty()),
            cart = decodePosCartJson(prefs[LEGACY_CART_JSON_KEY].orEmpty()),
            totalSales = prefs[LEGACY_TOTAL_SALES_KEY] ?: 0L,
            txCount = prefs[LEGACY_TX_COUNT_KEY] ?: 0L,
            salesLog = sales,
            reversalLog = reversals,
            lastCheckout = linkLastCheckoutToSales(
                decodeLastCheckoutJson(prefs[LAST_CHECKOUT_JSON_KEY].orEmpty()),
                sales,
            ),
        )
    }

    override val productsFlow: Flow<List<Product>> = snapshot.map { it.products }
    override val cartFlow: Flow<PosCart> = snapshot.map { it.cart }
    override val categoriesFlow: Flow<List<Category>> = snapshot.map { it.categories }
    override val bundleCategoriesFlow: Flow<List<BundleCategory>> = snapshot.map { it.bundleCategories }
    override val bundlesFlow: Flow<List<Bundle>> = snapshot.map { it.bundles }
    override val totalSalesFlow: Flow<Long> = snapshot.map { it.totalSales }
    override val txCountFlow: Flow<Long> = snapshot.map { it.txCount }
    override val salesLogFlow: Flow<List<SaleRecord>> = snapshot.map { it.salesLog }
    override val reversalLogFlow: Flow<List<SaleReversal>> = snapshot.map { it.reversalLog }
    override val lastCheckoutFlow: Flow<LastCheckout?> = snapshot.map { it.lastCheckout }

    override suspend fun applyCatalog(plan: CatalogPersistPlan) {
        context.posPreferencesDataStore.edit { prefs ->
            plan.products?.let { prefs[LEGACY_PRODUCTS_JSON_KEY] = encodeProducts(it) }
            plan.categories?.let { prefs[LEGACY_CATEGORIES_JSON_KEY] = encodeCategories(it) }
            plan.bundleCategories?.let { prefs[LEGACY_BUNDLE_CATEGORIES_JSON_KEY] = encodeBundleCategories(it) }
            plan.bundles?.let { prefs[LEGACY_BUNDLES_JSON_KEY] = encodeBundles(it) }
            plan.cart?.let { prefs[LEGACY_CART_JSON_KEY] = encodePosCartJson(it) }
        }
    }

    override suspend fun saveCart(cart: PosCart) {
        context.posPreferencesDataStore.edit { prefs ->
            prefs[LEGACY_CART_JSON_KEY] = encodePosCartJson(cart)
        }
    }

    override suspend fun clearCart() {
        context.posPreferencesDataStore.edit { prefs ->
            prefs[LEGACY_CART_JSON_KEY] = encodePosCartJson(PosCart())
        }
    }

    override suspend fun exportFullBackupJson(): String {
        val prefs = context.posPreferencesDataStore.data.first()
        val payload = JSONObject().apply {
            put("payloadSchema", BACKUP_SCHEMA_VERSION)
            put("products_json", prefs[LEGACY_PRODUCTS_JSON_KEY] ?: "")
            put("categories_json", prefs[LEGACY_CATEGORIES_JSON_KEY] ?: "")
            put("bundle_categories_json", prefs[LEGACY_BUNDLE_CATEGORIES_JSON_KEY] ?: "")
            put("bundles_json", prefs[LEGACY_BUNDLES_JSON_KEY] ?: "")
            put("cart_json", prefs[LEGACY_CART_JSON_KEY] ?: "")
            put("sales_log_json", prefs[SALES_LOG_JSON_KEY] ?: "")
            put("reversal_log_json", prefs[SALES_REVERSAL_LOG_JSON_KEY] ?: "[]")
            put("last_checkout_json", prefs[LAST_CHECKOUT_JSON_KEY] ?: "")
            put("total_sales", prefs[LEGACY_TOTAL_SALES_KEY] ?: 0L)
            put("tx_count", prefs[LEGACY_TX_COUNT_KEY] ?: 0L)
        }
        return JSONObject().apply {
            put("format", BACKUP_FORMAT_ID)
            put("schemaVersion", BACKUP_SCHEMA_VERSION)
            put("exportedAtMillis", System.currentTimeMillis())
            put("payload", payload)
        }.toString(2)
    }

    override suspend fun restoreFullBackupJson(jsonText: String): Result<Unit> = runCatching {
        val payload = parseValidatedBackupPayload(jsonText)
        context.posPreferencesDataStore.edit { pref ->
            pref[LEGACY_PRODUCTS_JSON_KEY] = payload.optString("products_json", "")
            pref[LEGACY_CATEGORIES_JSON_KEY] = payload.optString("categories_json", "")
            pref[LEGACY_BUNDLE_CATEGORIES_JSON_KEY] = payload.optString("bundle_categories_json", "")
            pref[LEGACY_BUNDLES_JSON_KEY] = payload.optString("bundles_json", "")
            pref[LEGACY_CART_JSON_KEY] = payload.optString("cart_json", "")
            pref[SALES_LOG_JSON_KEY] = payload.optString("sales_log_json", "")
            pref[SALES_REVERSAL_LOG_JSON_KEY] = payload.optString("reversal_log_json", "[]")
            pref[LAST_CHECKOUT_JSON_KEY] = payload.optString("last_checkout_json", "")
            pref[LEGACY_TOTAL_SALES_KEY] = payload.optLong("total_sales", 0L).coerceAtLeast(0L)
            pref[LEGACY_TX_COUNT_KEY] = payload.optLong("tx_count", 0L).coerceAtLeast(0L)
            pref[TRANSACTION_SCHEMA_VERSION_KEY] = TRANSACTION_SCHEMA_VERSION
        }
    }

    override suspend fun commitCheckout(request: CheckoutWriteRequest) {
        require(request.total >= 0L) { "checkout total must be non-negative, got ${request.total}" }
        val tip = request.tipAmount.coerceAtLeast(0L)
        val now = System.currentTimeMillis()

        context.posPreferencesDataStore.edit { prefs ->
            val saleId = UUID.randomUUID().toString()
            val products = decodeProducts(prefs[LEGACY_PRODUCTS_JSON_KEY].orEmpty())
            val resolvedLines =
                request.checkoutLines.takeIf { it.isNotEmpty() }
                    ?: buildProductLinesFromCart(request.productCart, products)
            val resolvedDeductions =
                request.stockDeductions.takeIf { it.isNotEmpty() }
                    ?: request.productCart.mapValues { it.value.toLong() }

            val record = SaleRecord(
                id = saleId,
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
            prefs[LEGACY_PRODUCTS_JSON_KEY] = encodeProducts(deducted)

            prefs[LAST_CHECKOUT_JSON_KEY] = encodeLastCheckoutJson(
                LastCheckout(
                    saleId = saleId,
                    tsMillis = now,
                    total = request.total + tip,
                    productCart = request.productCart,
                    bundleCart = request.bundleCart,
                    stockDeductions = resolvedDeductions,
                ),
            )
            prefs[LEGACY_TOTAL_SALES_KEY] = (prefs[LEGACY_TOTAL_SALES_KEY] ?: 0L) + request.total + tip
            prefs[LEGACY_TX_COUNT_KEY] = (prefs[LEGACY_TX_COUNT_KEY] ?: 0L) + 1

            val log = decodeSalesRecordsJsonResult(prefs[SALES_LOG_JSON_KEY].orEmpty())
                .getOrThrow()
                .toMutableList()
            log.add(record)
            // ponytail: audit log 暫時無上限；資料量成為實測問題時由 v1.4 Room 承接。
            prefs[SALES_LOG_JSON_KEY] = encodeSalesRecordsJson(log)
            prefs[LEGACY_CART_JSON_KEY] = encodePosCartJson(PosCart())
        }
    }

    override suspend fun undoLastCheckout() {
        context.posPreferencesDataStore.edit { prefs ->
            val sales = decodeSalesRecordsJson(prefs[SALES_LOG_JSON_KEY].orEmpty())
            val snap = PosPersistSnapshot(
                products = decodeProducts(prefs[LEGACY_PRODUCTS_JSON_KEY].orEmpty()),
                cart = decodePosCartJson(prefs[LEGACY_CART_JSON_KEY].orEmpty()),
                totalSales = prefs[LEGACY_TOTAL_SALES_KEY] ?: 0L,
                txCount = prefs[LEGACY_TX_COUNT_KEY] ?: 0L,
                salesLog = sales,
                reversalLog = decodeSaleReversalsJson(prefs[SALES_REVERSAL_LOG_JSON_KEY].orEmpty()),
                lastCheckout = linkLastCheckoutToSales(
                    decodeLastCheckoutJson(prefs[LAST_CHECKOUT_JSON_KEY].orEmpty()),
                    sales,
                ),
            )
            val next = snap.applyUndoIfPossible(
                reversalId = UUID.randomUUID().toString(),
                reversedAtMillis = System.currentTimeMillis(),
            ) ?: return@edit
            prefs[LEGACY_TOTAL_SALES_KEY] = next.totalSales
            prefs[LEGACY_TX_COUNT_KEY] = next.txCount
            prefs[SALES_REVERSAL_LOG_JSON_KEY] = encodeSaleReversalsJson(next.reversalLog)
            prefs[LEGACY_PRODUCTS_JSON_KEY] = encodeProducts(next.products)
            prefs[LEGACY_CART_JSON_KEY] = encodePosCartJson(next.cart)
            prefs[LAST_CHECKOUT_JSON_KEY] = ""
        }
    }
}
