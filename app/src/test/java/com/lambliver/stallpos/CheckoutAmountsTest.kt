package com.lambliver.stallpos

import com.lambliver.stallpos.domain.CheckoutAmounts
import com.lambliver.stallpos.domain.CheckoutAmounts.AmountDue
import com.lambliver.stallpos.domain.CheckoutAmounts.CatalogSubtotal
import com.lambliver.stallpos.domain.CheckoutAmounts.ConfirmationSnapshot
import com.lambliver.stallpos.domain.CheckoutAmounts.NetPaymentAdjustment
import com.lambliver.stallpos.domain.CheckoutAmounts.ReconcileReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 結帳金額模組之測試面＝[CheckoutAmounts] 公開介面本身（非 ViewModel 呼叫順序）。
 *
 * 意圖分組：
 * - [AmountDue] 不變量
 * - [amountDue] 公式
 * - [Breakdown.toSaleRecordAmountFields] 持久化 seam
 * - [reconcile] 對帳（競態／表面應收）
 */
class CheckoutAmountsTest {

    // ── AmountDue ───────────────────────────────────────────────────────────

    @Test
    fun amountDue_clampsNegativeToZero() {
        assertEquals(0L, AmountDue.clamped(-1L).cents)
        assertEquals(0L, AmountDue.clamped(Long.MIN_VALUE).cents)
    }

    @Test
    fun amountDue_preservesNonNegative() {
        assertEquals(0L, AmountDue.clamped(0L).cents)
        assertEquals(1L, AmountDue.clamped(1L).cents)
    }

    // ── amountDue(catalog, net) ─────────────────────────────────────────────

    @Test
    fun amountDue_sumsCatalogAndNet_thenClamps() {
        val catalog = CatalogSubtotal(100L)
        val net = NetPaymentAdjustment(-150L)
        assertEquals(0L, CheckoutAmounts.amountDue(catalog, net).cents)
    }

    @Test
    fun amountDue_positiveSum() {
        assertEquals(
            50L,
            CheckoutAmounts.amountDue(CatalogSubtotal(30L), NetPaymentAdjustment(20L)).cents,
        )
    }

    @Test
    fun amountDue_breakdown_matchesPairOverload() {
        val breakdown = CheckoutAmounts.Breakdown(CatalogSubtotal(30L), NetPaymentAdjustment(20L))
        assertEquals(
            CheckoutAmounts.amountDue(breakdown.catalogSubtotal, breakdown.netAdjustment).cents,
            CheckoutAmounts.amountDue(breakdown).cents,
        )
    }

    @Test
    fun amountDue_negativeCatalogSubtotal_stillClamps() {
        assertEquals(
            0L,
            CheckoutAmounts.amountDue(
                CatalogSubtotal(-10L),
                NetPaymentAdjustment(0L),
            ).cents,
        )
        assertEquals(0L, CheckoutAmounts.amountDue(CatalogSubtotal(-50L), NetPaymentAdjustment(30L)).cents)
        assertEquals(100L, CheckoutAmounts.amountDue(CatalogSubtotal(-50L), NetPaymentAdjustment(150L)).cents)
    }

    // ── Breakdown → SaleRecord ──────────────────────────────────────────────

    @Test
    fun breakdown_toSaleRecordAmountFields_discountAlwaysZero() {
        val fields = CheckoutAmounts.Breakdown(
            CatalogSubtotal(100L),
            NetPaymentAdjustment(-40L),
        ).toSaleRecordAmountFields()
        assertEquals(60L, fields.persistedSubtotal)
        assertEquals(0L, fields.discount)
        assertEquals(60L, fields.total)
        assertEquals(fields.persistedSubtotal, fields.total)
    }

    @Test
    fun breakdown_persistedSubtotalIsAmountDue_notCatalogSubtotal() {
        val fields = CheckoutAmounts.Breakdown(
            CatalogSubtotal(200L),
            NetPaymentAdjustment(-50L),
        ).toSaleRecordAmountFields()
        assertEquals(150L, fields.persistedSubtotal)
        assertEquals(150L, fields.total)
        assertNotEquals(200L, fields.persistedSubtotal)
    }

    // ── reconcile ───────────────────────────────────────────────────────────

    private fun snapshot(
        catalog: Long,
        net: Long,
        surface: Long,
    ) = ConfirmationSnapshot(
        catalogSubtotal = CatalogSubtotal(catalog),
        netAdjustment = NetPaymentAdjustment(net),
        surfaceAmountDue = AmountDue.clamped(surface),
    )

    @Test
    fun reconcile_accepts_whenLiveMatchesSnapshotAndFormula() {
        val result = CheckoutAmounts.reconcile(
            liveCatalogSubtotal = CatalogSubtotal(100L),
            confirmation = snapshot(catalog = 100L, net = -40L, surface = 60L),
        )
        val ok = result as CheckoutAmounts.ReconcileResult.Accepted
        assertEquals(100L, ok.breakdown.catalogSubtotal.cents)
        assertEquals(-40L, ok.breakdown.netAdjustment.cents)
        assertEquals(60L, ok.amountDue.cents)
    }

    @Test
    fun reconcile_rejects_cartChanged() {
        val result = CheckoutAmounts.reconcile(
            liveCatalogSubtotal = CatalogSubtotal(100L),
            confirmation = snapshot(catalog = 99L, net = 0L, surface = 99L),
        )
        assertTrue(result is CheckoutAmounts.ReconcileResult.Rejected)
        assertEquals(ReconcileReason.CartChanged, (result as CheckoutAmounts.ReconcileResult.Rejected).reason)
    }

    @Test
    fun reconcile_rejects_surfaceMismatch() {
        val result = CheckoutAmounts.reconcile(
            liveCatalogSubtotal = CatalogSubtotal(100L),
            confirmation = snapshot(catalog = 100L, net = 10L, surface = 999L),
        )
        assertTrue(result is CheckoutAmounts.ReconcileResult.Rejected)
        assertEquals(ReconcileReason.SurfaceMismatch, (result as CheckoutAmounts.ReconcileResult.Rejected).reason)
    }

    @Test
    fun reconcile_accepts_zeroAmountDue() {
        val result = CheckoutAmounts.reconcile(
            liveCatalogSubtotal = CatalogSubtotal(100L),
            confirmation = snapshot(catalog = 100L, net = -100L, surface = 0L),
        )
        val ok = result as CheckoutAmounts.ReconcileResult.Accepted
        assertEquals(0L, ok.amountDue.cents)
    }

    @Test
    fun reconcile_rejects_whenSnapshotSurfaceNotFromFormula() {
        val catalogCents = 100L
        val netCents = -40L
        val formulaDue = CheckoutAmounts.amountDue(
            CatalogSubtotal(catalogCents),
            NetPaymentAdjustment(netCents),
        ).cents
        assertEquals(60L, formulaDue)

        val result = CheckoutAmounts.reconcile(
            liveCatalogSubtotal = CatalogSubtotal(catalogCents),
            confirmation = ConfirmationSnapshot(
                catalogSubtotal = CatalogSubtotal(catalogCents),
                netAdjustment = NetPaymentAdjustment(netCents),
                // 呼叫方傳入的表面應收；與 catalog+net 公式差 1 cent
                surfaceAmountDue = AmountDue.clamped(formulaDue + 1L),
            ),
        )

        assertTrue(result is CheckoutAmounts.ReconcileResult.Rejected)
        val rejected = result as CheckoutAmounts.ReconcileResult.Rejected
        assertEquals(ReconcileReason.SurfaceMismatch, rejected.reason)
    }

    // ── 型別區分（文件化測試）────────────────────────────────────────────────

    @Test
    fun types_distinctAtCompileTime() {
        val catalog = CatalogSubtotal(100L)
        val net = NetPaymentAdjustment(-10L)
        val due = CheckoutAmounts.amountDue(catalog, net)
        assertNotEquals(catalog.cents, due.cents) // 語意不同；此例數值亦不同
    }
}
