package com.lambliver.stallpos.ui

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.lambliver.stallpos.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PosViewModel.cartState() = PosCartCoordinator.CartState(
    products = posUiState.value.products,
    bundles = posUiState.value.bundles,
    cart = posCartMemory.value,
)

internal fun PosViewModel.applyCartResult(result: PosCartCoordinator.CartResult) {
    when (result) {
        PosCartCoordinator.CartResult.Ignored -> Unit
        is PosCartCoordinator.CartResult.UserMessage ->
            emitToastAsync(result.message, PosToastSeverity.Error)
        is PosCartCoordinator.CartResult.Ready -> {
            posCartMemory.value = result.cart
            if (result.toasts.isNotEmpty()) {
                viewModelScope.launch {
                    result.toasts.forEach { emitToast(it, PosToastSeverity.Error) }
                }
            }
            scheduleCartFlush(result.cart)
        }
    }
}

internal fun PosViewModel.clampCartToStock() {
    val products = posUiState.value.products
    val bundles = posUiState.value.bundles
    val cur = posCartMemory.value
    val (next, changed) = PosCartCoordinator.clampCartToStock(products, bundles, cur)
    if (changed) {
        posCartMemory.value = next
        scheduleCartFlush(next)
    }
}

private fun PosCart.isEffectivelyEmpty(): Boolean = products.isEmpty() && bundles.isEmpty()

/** 啟動或結帳失敗後：記憶體購物車已空但磁碟仍有內容時，以磁碟為準還原。 */
internal suspend fun PosViewModel.reconcileCartMemoryWithDisk() {
    try {
        val diskCart = posStore.cartFlow.first()
        if (posCartMemory.value.isEffectivelyEmpty() && !diskCart.isEffectivelyEmpty()) {
            posCartMemory.value = diskCart
        }
    } catch (e: Throwable) {
        Log.e(PosViewModel.LOG_TAG, "reconcileCartMemoryWithDisk failed", e)
    }
}

internal fun PosViewModel.setCartQty(productId: String, qty: Int) {
    applyCartResult(
        PosCartCoordinator.execute(
            cartState(),
            PosCartCoordinator.CartCommand.SetProductQty(productId, qty),
        ),
    )
}

internal fun PosViewModel.setBundleCartQty(bundleId: String, qty: Int) {
    applyCartResult(
        PosCartCoordinator.execute(
            cartState(),
            PosCartCoordinator.CartCommand.SetBundleQty(bundleId, qty),
        ),
    )
}

internal fun PosViewModel.scheduleCartFlush(cart: PosCart) {
    posCartDebounceJob?.cancel()
    posCartDebounceJob = viewModelScope.launch {
        delay(PosViewModel.CART_DEBOUNCE_MS)
        try {
            posStore.saveCart(cart)
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "scheduleCartFlush saveCart failed", e)
            emitToast("購物車自動儲存失敗", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.flushCartToDisk() {
    posCartDebounceJob?.cancel()
    posCartDebounceJob = viewModelScope.launch {
        try {
            posStore.saveCart(posCartMemory.value)
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "flushCartToDisk saveCart failed", e)
            emitToast("購物車儲存失敗", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.confirmCheckout(event: PosEvent.ConfirmCheckout) {
    val state = posUiState.value
    val confirmation = event.pricing.toConfirmationSnapshot()

    val prep = PosCheckoutCoordinator.prepare(
        currentCatalogSubtotal = state.subtotal,
        confirmation = confirmation,
        products = state.products,
        bundles = state.bundles,
        cart = posCartMemory.value,
        dateKey = state.todayKey,
        paymentMethod = event.paymentMethod,
        tipAmountRaw = event.tipAmount,
    )

    val request = when (prep) {
        is PosCheckoutCoordinator.PrepareResult.UserMessage -> {
            emitToastAsync(prep.message, PosToastSeverity.Error)
            return
        }
        is PosCheckoutCoordinator.PrepareResult.Ready -> prep.request
    }

    if (!posCheckoutInflight.compareAndSet(false, true)) {
        emitToastAsync("結帳處理中，請稍候")
        return
    }

    posCartDebounceJob?.cancel()
    posCartDebounceJob = null

    viewModelScope.launch {
        try {
            try {
                posStore.commitCheckout(request)
                PosOpsLog.checkoutCommitted(
                    txCount = posUiState.value.txCount + 1,
                    receivableCents = request.total,
                )
                posCartMemory.value = PosCart()
                posUiState.update {
                    it.copy(
                        dialogState = DialogState.None,
                        checkoutCustomDigits = "",
                        checkoutDiscountRequested = 0L,
                        checkoutSheetSnapshot = null,
                    )
                }
                posCheckoutSuccessChannel.send(Unit)
            } catch (e: Throwable) {
                Log.e(PosViewModel.LOG_TAG, "checkout failed", e)
                reconcileCartMemoryWithDisk()
                emitToast("結帳失敗，請再試一次", PosToastSeverity.Error)
            }
        } finally {
            posCheckoutInflight.set(false)
        }
    }
}

internal fun PosViewModel.undoCheckout() {
    viewModelScope.launch {
        try {
            posStore.undoLastCheckout()
            posCartMemory.value = posStore.cartFlow.first()
            PosOpsLog.checkoutUndone(txCount = posUiState.value.txCount)
            emitToast("已復原上一筆結帳")
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "undoCheckout failed", e)
            emitToast("復原失敗，請再試一次", PosToastSeverity.Error)
        }
    }
}

internal fun PosViewModel.clearCart() {
    posCartDebounceJob?.cancel()
    posCartMemory.value = PosCart()
    posUiState.update { it.copy(checkoutDiscountRequested = 0L) }
    viewModelScope.launch {
        try {
            posStore.clearCart()
        } catch (e: Throwable) {
            Log.e(PosViewModel.LOG_TAG, "clearCart failed", e)
            emitToast("清空失敗：${e.message}", PosToastSeverity.Error)
        }
    }
}
