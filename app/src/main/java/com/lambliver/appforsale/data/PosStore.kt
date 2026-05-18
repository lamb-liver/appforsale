package com.lambliver.appforsale.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.lambliver.appforsale.domain.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore(name = "pos_store")

/**
 * DataStore 協調：**偏好鍵讀寫**、**結帳／復原交易**。
 *
 * JSON 形狀見 [PosPersistCatalogJson]（目錄）、[PosPersistJson]（購物車／銷售／備份封包）；網域型別見 [PosPersistModels]。
 */
class PosStore(private val context: Context) : PosPersistence {

    companion object {
        const val BACKUP_FORMAT_ID = "appforsale_pos_backup"
        const val BACKUP_SCHEMA_VERSION = 2
    }

    private val PRODUCTS_JSON = stringPreferencesKey("products_json")
    private val CATEGORIES_JSON = stringPreferencesKey("categories_json")
    private val BUNDLE_CATEGORIES_JSON = stringPreferencesKey("bundle_categories_json")
    private val BUNDLES_JSON = stringPreferencesKey("bundles_json")
    private val CART_JSON = stringPreferencesKey("cart_json")
    private val TOTAL_SALES = longPreferencesKey("total_sales")
    private val TX_COUNT = longPreferencesKey("tx_count")
    private val SALES_LOG_JSON = stringPreferencesKey("sales_log_json")
    private val LAST_CHECKOUT_JSON = stringPreferencesKey("last_checkout_json")

    override val snapshot: Flow<PosPersistSnapshot> = context.dataStore.data.map { prefs ->
        PosPersistSnapshot(
            products = decodeProducts(prefs[PRODUCTS_JSON].orEmpty()),
            categories = decodeCategories(prefs[CATEGORIES_JSON].orEmpty()),
            bundleCategories = decodeBundleCategories(prefs[BUNDLE_CATEGORIES_JSON].orEmpty()),
            bundles = decodeBundles(prefs[BUNDLES_JSON].orEmpty()),
            cart = decodePosCartJson(prefs[CART_JSON].orEmpty()),
            totalSales = prefs[TOTAL_SALES] ?: 0L,
            txCount = prefs[TX_COUNT] ?: 0L,
            salesLog = decodeSalesRecordsJson(prefs[SALES_LOG_JSON].orEmpty()),
            lastCheckout = decodeLastCheckoutJson(prefs[LAST_CHECKOUT_JSON].orEmpty()),
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
    override val lastCheckoutFlow: Flow<LastCheckout?> = snapshot.map { it.lastCheckout }

    override suspend fun applyCatalog(plan: CatalogPersistPlan) {
        context.dataStore.edit { prefs ->
            plan.products?.let { prefs[PRODUCTS_JSON] = encodeProducts(it) }
            plan.categories?.let { prefs[CATEGORIES_JSON] = encodeCategories(it) }
            plan.bundleCategories?.let { prefs[BUNDLE_CATEGORIES_JSON] = encodeBundleCategories(it) }
            plan.bundles?.let { prefs[BUNDLES_JSON] = encodeBundles(it) }
            plan.cart?.let { prefs[CART_JSON] = encodePosCartJson(it) }
        }
    }

    override suspend fun saveCart(cart: PosCart) {
        context.dataStore.edit { prefs ->
            prefs[CART_JSON] = encodePosCartJson(cart)
        }
    }

    override suspend fun clearCart() {
        context.dataStore.edit { prefs ->
            prefs[CART_JSON] = encodePosCartJson(PosCart())
        }
    }

    override suspend fun exportFullBackupJson(): String {
        val prefs = context.dataStore.data.first()
        val payload = JSONObject().apply {
            put("payloadSchema", BACKUP_SCHEMA_VERSION)
            put("products_json", prefs[PRODUCTS_JSON] ?: "")
            put("categories_json", prefs[CATEGORIES_JSON] ?: "")
            put("bundle_categories_json", prefs[BUNDLE_CATEGORIES_JSON] ?: "")
            put("bundles_json", prefs[BUNDLES_JSON] ?: "")
            put("cart_json", prefs[CART_JSON] ?: "")
            put("sales_log_json", prefs[SALES_LOG_JSON] ?: "")
            put("last_checkout_json", prefs[LAST_CHECKOUT_JSON] ?: "")
            put("total_sales", prefs[TOTAL_SALES] ?: 0L)
            put("tx_count", prefs[TX_COUNT] ?: 0L)
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
        context.dataStore.edit { pref ->
            pref[PRODUCTS_JSON] = payload.optString("products_json", "")
            pref[CATEGORIES_JSON] = payload.optString("categories_json", "")
            pref[BUNDLE_CATEGORIES_JSON] = payload.optString("bundle_categories_json", "")
            pref[BUNDLES_JSON] = payload.optString("bundles_json", "")
            pref[CART_JSON] = payload.optString("cart_json", "")
            pref[SALES_LOG_JSON] = payload.optString("sales_log_json", "")
            pref[LAST_CHECKOUT_JSON] = payload.optString("last_checkout_json", "")
            pref[TOTAL_SALES] = payload.optLong("total_sales", 0L).coerceAtLeast(0L)
            pref[TX_COUNT] = payload.optLong("tx_count", 0L).coerceAtLeast(0L)
        }
    }

    override suspend fun commitCheckout(request: CheckoutWriteRequest) {
        require(request.total >= 0L) { "checkout total must be non-negative, got ${request.total}" }
        val tip = request.tipAmount.coerceAtLeast(0L)
        val now = System.currentTimeMillis()

        context.dataStore.edit { prefs ->
            val products = decodeProducts(prefs[PRODUCTS_JSON].orEmpty())
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
            prefs[PRODUCTS_JSON] = encodeProducts(deducted)

            prefs[LAST_CHECKOUT_JSON] = encodeLastCheckoutJson(
                LastCheckout(now, request.total + tip, request.productCart, request.bundleCart, resolvedDeductions),
            )
            prefs[TOTAL_SALES] = (prefs[TOTAL_SALES] ?: 0L) + request.total + tip
            prefs[TX_COUNT] = (prefs[TX_COUNT] ?: 0L) + 1

            val log = decodeSalesRecordsJson(prefs[SALES_LOG_JSON].orEmpty()).toMutableList()
            log.add(record)
            prefs[SALES_LOG_JSON] = encodeSalesRecordsJson(if (log.size > 5000) log.takeLast(5000) else log)
            prefs[CART_JSON] = encodePosCartJson(PosCart())
        }
    }

    override suspend fun undoLastCheckout() {
        context.dataStore.edit { prefs ->
            val snap = PosPersistSnapshot(
                products = decodeProducts(prefs[PRODUCTS_JSON].orEmpty()),
                cart = decodePosCartJson(prefs[CART_JSON].orEmpty()),
                totalSales = prefs[TOTAL_SALES] ?: 0L,
                txCount = prefs[TX_COUNT] ?: 0L,
                salesLog = decodeSalesRecordsJson(prefs[SALES_LOG_JSON].orEmpty()),
                lastCheckout = decodeLastCheckoutJson(prefs[LAST_CHECKOUT_JSON].orEmpty()),
            )
            val next = snap.applyUndoIfPossible() ?: return@edit
            prefs[TOTAL_SALES] = next.totalSales
            prefs[TX_COUNT] = next.txCount
            prefs[SALES_LOG_JSON] = encodeSalesRecordsJson(next.salesLog)
            prefs[PRODUCTS_JSON] = encodeProducts(next.products)
            prefs[CART_JSON] = encodePosCartJson(next.cart)
            prefs[LAST_CHECKOUT_JSON] = ""
        }
    }
}
