package com.lambliver.stallpos.domain

/**
 * 結帳成功後寫入持久化層的指令（domain 契約）。
 *
 * 由 [PosCheckoutCoordinator] 產生，經 [com.lambliver.stallpos.data.PosPersistence.commitCheckout] 原子寫入。
 */
data class CheckoutWriteRequest(
    val productCart: Map<String, Int>,
    val bundleCart: Map<String, Int>,
    val subtotal: Long,
    val discount: Long,
    val total: Long,
    val dateKey: String,
    val paymentMethod: PaymentMethod,
    val checkoutLines: List<SaleCheckoutLine>,
    val stockDeductions: Map<String, Long>,
    val tipAmount: Long,
)
