package com.lambliver.appforsale.ui.billing

import android.app.Activity
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.lambliver.appforsale.ui.findActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SponsorBillingViewModel(
    app: Application,
) : AndroidViewModel(app), PurchasesUpdatedListener {

    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val billingClient: BillingClient = BillingClient.newBuilder(app)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    private val connectLock = Any()
    private var connectionInFlight = false

    private val _connectionReady = MutableStateFlow(false)
    val connectionReady: StateFlow<Boolean> = _connectionReady.asStateFlow()

    private val _productDetails = MutableStateFlow<Map<String, ProductDetails>>(emptyMap())
    val productDetails: StateFlow<Map<String, ProductDetails>> = _productDetails.asStateFlow()

    private val _billingMessage = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val billingMessage: SharedFlow<String> = _billingMessage.asSharedFlow()

    /** 已兌換之贊助 INAPP（任一）時為 true，主畫面不再顯示 AdMob 橫幅。 */
    private val _removeAdsAfterSponsor =
        MutableStateFlow(prefs.getBoolean(KEY_REMOVE_ADS, false))
    val removeAdsAfterSponsor: StateFlow<Boolean> = _removeAdsAfterSponsor.asStateFlow()

    private fun setRemoveAdsAfterSponsor(value: Boolean) {
        prefs.edit().putBoolean(KEY_REMOVE_ADS, value).apply()
        _removeAdsAfterSponsor.value = value
    }

    private fun refreshSponsorPurchaseOwnership() {
        if (!billingClient.isReady) return
        val sponsorIds = SponsorProductIds.allInApp().toSet()
        billingClient.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build(),
        ) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            val owned = purchases.any { purchase ->
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    purchase.products.any { it in sponsorIds }
            }
            setRemoveAdsAfterSponsor(owned)
        }
    }

    private fun startConnection() {
        synchronized(connectLock) {
            if (billingClient.isReady || connectionInFlight) return
            connectionInFlight = true
        }
        billingClient.startConnection(
            object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    synchronized(connectLock) { connectionInFlight = false }
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        _connectionReady.value = true
                        querySponsorProducts()
                        refreshSponsorPurchaseOwnership()
                    } else {
                        _connectionReady.value = false
                        val hint = billingSetupHint(result.responseCode)
                        emitMessage("無法連線至 Google Play 帳務服務。$hint")
                    }
                }

                override fun onBillingServiceDisconnected() {
                    synchronized(connectLock) { connectionInFlight = false }
                    _connectionReady.value = false
                }
            },
        )
    }

    /** 打開贊助選單時呼叫，確保連線並重新查詢商品詳情。 */
    fun refreshSponsorProducts() {
        when {
            billingClient.isReady -> {
                querySponsorProducts()
                refreshSponsorPurchaseOwnership()
            }
            else -> startConnection()
        }
    }

    private fun billingSetupHint(code: Int): String = when (code) {
        BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ->
            "請使用含 Google Play 商店的系統，並確認 Play 商店已更新且已登入帳號。"
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE ->
            "Play 服務目前忙碌或網路不穩，請稍後再開啟「贊助開發者」重試。"
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED ->
            "與 Play 連線中斷，請稍後再試。"
        BillingClient.BillingResponseCode.DEVELOPER_ERROR ->
            "應用程式設定異常（簽章／applicationId 與 Play Console 不一致等），請檢查上架設定。"
        BillingClient.BillingResponseCode.ERROR ->
            "發生內部錯誤，可嘗試更新 Google Play 服務與 Play 商店。"
        else ->
            "（錯誤碼 $code）"
    }

    private fun querySponsorProducts() {
        val productList = SponsorProductIds.allInApp().map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK || list.isNullOrEmpty()) {
                emitMessage("無法載入贊助方案，請稍後再試")
                return@queryProductDetailsAsync
            }
            _productDetails.value = list.associateBy { it.productId }
        }
    }

    fun launchPurchase(activity: Activity, productId: String) {
        val details = _productDetails.value[productId]
        if (details == null) {
            emitMessage("方案尚未就緒，請稍候")
            refreshSponsorProducts()
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val br = billingClient.launchBillingFlow(activity, flowParams)
        if (br.responseCode != BillingClient.BillingResponseCode.OK) {
            emitMessage("無法開啟付款畫面")
        }
    }

    fun launchPurchaseFromContext(androidContext: android.content.Context, productId: String) {
        val act = androidContext.findActivity() ?: run {
            emitMessage("無法開啟付款畫面")
            return
        }
        launchPurchase(act, productId)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach { handlePurchase(it) }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit
            else -> emitMessage("購買未完成")
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        if (!purchase.isAcknowledged) {
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(ackParams) { result ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    emitMessage("感謝您的贊助！")
                    refreshSponsorPurchaseOwnership()
                }
            }
        } else {
            emitMessage("感謝您的贊助！")
            refreshSponsorPurchaseOwnership()
        }
    }

    private fun emitMessage(msg: String) {
        viewModelScope.launch { _billingMessage.emit(msg) }
    }

    override fun onCleared() {
        billingClient.endConnection()
    }

    private companion object {
        const val PREFS_NAME = "sponsor_billing_prefs"
        private const val KEY_REMOVE_ADS = "remove_ads_after_sponsor"
    }
}
