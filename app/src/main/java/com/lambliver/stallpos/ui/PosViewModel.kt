package com.lambliver.stallpos.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.lambliver.stallpos.data.AppUiPreferences
import com.lambliver.stallpos.data.PosBackupSafAdapter
import com.lambliver.stallpos.data.PosCsvExportAdapter
import com.lambliver.stallpos.data.PosPersistSnapshot
import com.lambliver.stallpos.data.PosPersistence
import com.lambliver.stallpos.data.PosStore
import com.lambliver.stallpos.domain.*
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

class PosViewModel @JvmOverloads constructor(
    app: Application,
    posStore: PosPersistence = PosStore(app),
) : AndroidViewModel(app) {

    internal val posStore: PosPersistence = posStore
    private val appUiPrefs = AppUiPreferences(app)
    val hapticEnabledFlow = appUiPrefs.hapticEnabledFlow
    val soundEnabledFlow = appUiPrefs.soundEnabledFlow
    internal val posCheckoutInflight = AtomicBoolean(false)
    internal val posCsvExport = PosCsvExportAdapter()
    internal val posBackupSaf = PosBackupSafAdapter(posStore)

    /** 購物車唯一可寫來源；[PosUiState.cart] 為其投影。 */
    internal val posCartMemory = MutableStateFlow(PosCart())
    internal var posCartDebounceJob: Job? = null

    /** 上次執行庫存 clamp 時的目錄指紋；僅在目錄變更時重新 clamp。 */
    internal var lastCatalogClampKey: String? = null

    private var todaySalesLogCacheKey: TodaySalesLogCacheKey? = null
    private var cachedTodaySalesLog: List<SaleRecord> = emptyList()

    private data class TodaySalesLogCacheKey(
        val todayKey: String,
        val logSize: Int,
        val lastTsMillis: Long,
    )

    internal val posUiState = MutableStateFlow(PosUiState())
    /** 畫面唯一訂閱來源（商品、購物車、統計等皆由此組合）。 */
    val uiState: StateFlow<PosUiState> = posUiState.asStateFlow()

    internal val posToastChannel = Channel<PosToast>(Channel.BUFFERED)
    val toastFlow = posToastChannel.receiveAsFlow()

    internal val posCheckoutSuccessChannel = Channel<Unit>(Channel.BUFFERED)
    val checkoutSuccessFlow = posCheckoutSuccessChannel.receiveAsFlow()

    internal val posCsvShareUriChannel = Channel<String>(Channel.BUFFERED)
    val csvShareUriFlow = posCsvShareUriChannel.receiveAsFlow()

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) = this@PosViewModel.flushCartToDisk()
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        viewModelScope.launch {
            try {
                posCartMemory.value = posStore.cartFlow.first()
                reconcileCartMemoryWithDisk()
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "cart load failed", e)
                emitToast("購物車載入失敗，請重新開啟 App", PosToastSeverity.Error)
            }
            startObserving()
        }
    }

    override fun onCleared() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        flushCartToDisk()
    }

    /** 單元測試用：從類別內部呼叫 protected [onCleared]。 */
    internal fun clearForTest() {
        onCleared()
    }

    fun onCheckoutClicked(
        pricing: CheckoutSheetPricingSnapshot,
        paymentMethod: PaymentMethod = PaymentMethod.CASH,
        tipAmount: Long = 0L,
    ) = onEvent(PosEvent.ConfirmCheckout(pricing, paymentMethod, tipAmount))

    fun onUndoClicked() = onEvent(PosEvent.UndoCheckout)

    fun onUpdateCart(productId: String, qty: Int) = onEvent(PosEvent.SetCartQty(productId, qty))

    fun onUpdateBundleCart(bundleId: String, qty: Int) = onEvent(PosEvent.SetBundleCartQty(bundleId, qty))

    fun onCheckoutCustomDigitsChange(digits: String) {
        posUiState.update { it.copy(checkoutCustomDigits = digits) }
    }

    fun onCheckoutDiscountRequested(amount: Long) {
        posUiState.update { it.copy(checkoutDiscountRequested = amount.coerceAtLeast(0L)) }
    }

    /** 主畫面按「結帳」：鎖定金額快照，供 BottomSheet 與 [PosEvent.ConfirmCheckout] 共用。 */
    fun beginCheckoutSheet() {
        val snap = CheckoutSheetPricingSnapshot.lockedFrom(posUiState.value)
        posUiState.update { it.copy(checkoutSheetSnapshot = snap) }
    }

    fun dismissCheckoutSheet() {
        posUiState.update { it.copy(checkoutSheetSnapshot = null) }
    }

    fun onEvent(event: PosEvent) {
        when (event) {
            is PosEvent.SetCartQty -> setCartQty(event.productId, event.qty)
            is PosEvent.SetBundleCartQty -> setBundleCartQty(event.bundleId, event.qty)
            is PosEvent.AddProduct -> addProduct(event.name, event.price, event.categoryId, event.stock)
            is PosEvent.UpdateProduct -> updateProduct(event.id, event.name, event.price, event.categoryId, event.stock)
            is PosEvent.DeleteProduct -> deleteProduct(event.productId)
            is PosEvent.SetProductStock -> setProductStock(event.productId, event.stock)
            is PosEvent.AddCategory -> addCategory(event.name)
            is PosEvent.UpdateCategory -> updateCategory(event.id, event.name)
            is PosEvent.DeleteCategory -> deleteCategory(event.id)
            is PosEvent.AddBundle -> addBundle(event.name, event.price, event.categoryId, event.components)
            is PosEvent.UpdateBundle -> updateBundle(event.id, event.name, event.price, event.categoryId, event.components)
            is PosEvent.DeleteBundle -> deleteBundle(event.bundleId)
            is PosEvent.AddBundleCategory -> addBundleCategory(event.name)
            is PosEvent.UpdateBundleCategory -> updateBundleCategory(event.id, event.name)
            is PosEvent.DeleteBundleCategory -> deleteBundleCategory(event.id)
            is PosEvent.ConfirmCheckout -> confirmCheckout(event)
            is PosEvent.UndoCheckout -> undoCheckout()
            is PosEvent.ClearCart -> clearCart()
            is PosEvent.ShowDialog -> posUiState.update { it.copy(dialogState = event.state) }
            is PosEvent.DismissDialog -> posUiState.update { it.copy(dialogState = DialogState.None) }
            is PosEvent.ExportCsv -> exportCsv(event.target)
            is PosEvent.ExportBackupJson -> exportBackupJson(event.target)
            is PosEvent.ImportBackupJson -> importBackupJson(event.target)
        }
    }

    fun setHapticEnabled(enabled: Boolean) {
        viewModelScope.launch { appUiPrefs.setHapticEnabled(enabled) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { appUiPrefs.setSoundEnabled(enabled) }
    }

    private fun startObserving() {
        viewModelScope.launch {
            combine(posStore.snapshot, posCartMemory) { storeSnap, cart ->
                storeSnap to buildUiState(storeSnap, cart)
            }.collect { (storeSnap, newState) ->
                posUiState.update { current ->
                    newState.copy(
                        dialogState = current.dialogState,
                        checkoutCustomDigits = current.checkoutCustomDigits,
                        checkoutDiscountRequested = current.checkoutDiscountRequested,
                        checkoutSheetSnapshot = current.checkoutSheetSnapshot,
                    )
                }
                maybeClampCartToStock(storeSnap)
            }
        }
    }

    private fun maybeClampCartToStock(storeSnap: PosPersistSnapshot) {
        val key = catalogClampKey(storeSnap.products, storeSnap.bundles)
        if (key == lastCatalogClampKey) return
        lastCatalogClampKey = key
        clampCartToStock()
    }

    private fun buildUiState(
        snap: PosPersistSnapshot,
        cart: PosCart,
    ): PosUiState {
        val todayKey = getTodayKey()
        val productPart = snap.products.sumOf { (cart.products[it.id] ?: 0).toLong() * it.price }
        val bundlePart = snap.bundles.sumOf { (cart.bundles[it.id] ?: 0).toLong() * it.price }
        val subtotal = productPart + bundlePart
        val todaySalesLog = todaySalesLogFor(snap.salesLog, todayKey)

        return PosUiState(
            isLoading = false,
            products = snap.products.toImmutableList(),
            categories = snap.categories.toImmutableList(),
            bundles = snap.bundles.toImmutableList(),
            bundleCategories = snap.bundleCategories.toImmutableList(),
            cart = cart,
            totalSales = snap.totalSales,
            txCount = snap.txCount,
            salesLog = snap.salesLog.toImmutableList(),
            todaySalesLog = todaySalesLog.toImmutableList(),
            lastCheckout = snap.lastCheckout,
            subtotal = subtotal,
            todayKey = todayKey,
            todaySales = todaySalesLog.sumOf { it.total + it.tipAmount },
        )
    }

    private fun todaySalesLogFor(salesLog: List<SaleRecord>, todayKey: String): List<SaleRecord> {
        val key = TodaySalesLogCacheKey(
            todayKey = todayKey,
            logSize = salesLog.size,
            lastTsMillis = salesLog.lastOrNull()?.tsMillis ?: 0L,
        )
        if (key == todaySalesLogCacheKey) return cachedTodaySalesLog
        todaySalesLogCacheKey = key
        cachedTodaySalesLog = salesLog.filter { it.dateKey == todayKey }
        return cachedTodaySalesLog
    }

    private val dateFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd", Locale.TAIWAN).apply { timeZone = TimeZone.getDefault() }
    }

    private fun getTodayKey(): String {
        val fmt = dateFormatter.get()
        return fmt.format(Date())
    }

    companion object {
        internal const val LOG_TAG = "PosPOS"
        internal const val CART_DEBOUNCE_MS = 2_000L

        internal fun catalogClampKey(products: List<Product>, bundles: List<Bundle>): String =
            buildString {
                products.forEach { append(it.id).append(':').append(it.stock).append(';') }
                append('|')
                bundles.forEach { append(it.id).append(';') }
            }

        private val currencyFmt = ThreadLocal.withInitial {
            NumberFormat.getCurrencyInstance(Locale.TAIWAN)
        }
        fun formatCurrency(amount: Long): String {
            val fmt = currencyFmt.get()
            return fmt.format(amount)
        }
    }
}
