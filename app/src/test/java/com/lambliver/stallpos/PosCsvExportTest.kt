package com.lambliver.stallpos

import com.lambliver.stallpos.domain.*
import com.lambliver.stallpos.data.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PosCsvExportTest {

    @Test
    fun formatSaleRecordDetails_legacyCart_escapesProductNameQuotes() {
        val prod = Product("p1", "明信片「特別版」", 100L)
        val pm = mapOf("p1" to prod)
        val r = SaleRecord(
            id = "sale-1",
            tsMillis = 1L,
            dateKey = "2026-05-13",
            subtotal = 100L,
            discount = 0L,
            total = 100L,
            cartSnapshot = mapOf("p1" to 1),
        )
        val details = formatSaleRecordDetailsForCsv(r, pm, emptyMap())
        assertEquals("明信片「特別版」x1", details)
        val csv = buildPosSalesCsv("2026-05-13", listOf(r), emptyList(), listOf(prod), emptyList())
        assertTrue(csv.contains("\"明信片「特別版」x1\""))
    }

    @Test
    fun csv_doublesEmbeddedQuotesInDetailsField() {
        val prod = Product("p1", "Say\"Hi", 10L)
        val r = SaleRecord(
            id = "sale-2",
            tsMillis = 4L,
            dateKey = "2026-05-13",
            subtotal = 10L,
            discount = 0L,
            total = 10L,
            cartSnapshot = mapOf("p1" to 1),
        )
        val csv = buildPosSalesCsv("2026-05-13", listOf(r), emptyList(), listOf(prod), emptyList())
        assertTrue(csv.contains("\"Say\"\"Hix1\""))
    }

    @Test
    fun csv_includesSpecificPaymentLabel() {
        val r = SaleRecord(
            id = "sale-3",
            tsMillis = 2L,
            dateKey = "2026-05-13",
            subtotal = 50L,
            discount = 0L,
            total = 50L,
            cartSnapshot = mapOf("x" to 1),
            paymentMethod = PaymentMethod.JKOPAY,
        )
        val csv = buildPosSalesCsv("2026-05-13", listOf(r), emptyList(), emptyList(), emptyList())
        assertTrue(csv.contains("\"街口支付\""))
    }

    @Test
    fun formatSaleRecordDetails_checkoutLines_preferredOverLegacyCart() {
        val prod = Product("a", "本子", 120L)
        val bundle = Bundle(
            "b1",
            "套組A",
            300L,
            "",
            listOf(BundleComponent("a", 2L)),
        )
        val pm = mapOf("a" to prod)
        val bm = mapOf("b1" to bundle)
        val lines = listOf(
            SaleCheckoutLine.Product("a", qty = 1, unitPrice = 120L, lineSubtotal = 120L),
            SaleCheckoutLine.Bundle("b1", qty = 1, unitPrice = 300L, lineSubtotal = 300L),
        )
        val r = SaleRecord(
            id = "sale-4",
            tsMillis = 3L,
            dateKey = "2026-05-13",
            subtotal = 420L,
            discount = 0L,
            total = 420L,
            cartSnapshot = mapOf("wrong" to 99),
            bundleCartSnapshot = emptyMap(),
            checkoutLines = lines,
            stockDeductions = mapOf("a" to 3L),
        )
        val details = formatSaleRecordDetailsForCsv(r, pm, bm)
        assertEquals("本子x1;套組Ax1 |展開:本子x3", details)
    }

    @Test
    fun csv_isUserFacingActiveSalesOnly_andSummaryIncludesTips() {
        val reversed = SaleRecord(
            id = "sale-old",
            tsMillis = 1L,
            dateKey = "2026-05-13",
            subtotal = 100L,
            discount = 0L,
            total = 100L,
            tipAmount = 20L,
            cartSnapshot = emptyMap(),
        )
        val active = reversed.copy(id = "sale-active", tsMillis = 2L, total = 50L, tipAmount = 5L)
        val reversal = SaleReversal("reversal-1", reversed.id, 3L, ReversalReason.UNDO_LAST_CHECKOUT)

        val csv = buildPosSalesCsv(
            "2026-05-13",
            listOf(reversed, active),
            listOf(reversal),
            emptyList(),
            emptyList(),
        )

        assertTrue(csv.contains("報表,累積總額,55"))
        assertTrue(csv.contains("報表,累積筆數,1"))
        assertTrue(csv.contains("報表,今日營收(2026-05-13),55"))
        assertTrue(csv.contains("報表,CSV明細,有效交易"))
        assertTrue(csv.contains("時間戳,日期,付款方式,小計,折扣,總額,小費,購買明細"))
        assertTrue(csv.contains("\n2,2026-05-13"))
        assertFalse(csv.contains("\n1,2026-05-13"))
        assertFalse(csv.contains("交易ID"))
        assertFalse(csv.contains("sale-old"))
        assertFalse(csv.contains("reversal-1"))
        assertFalse(csv.contains("Reversal"))
    }

    @Test
    fun csv_prefersCheckoutNameSnapshot_overRenamedCatalog() {
        val sale = SaleRecord(
            id = "sale-name",
            tsMillis = 1L,
            dateKey = "2026-05-13",
            subtotal = 100L,
            discount = 0L,
            total = 100L,
            cartSnapshot = mapOf("p" to 1),
            checkoutLines = listOf(SaleCheckoutLine.Product("p", 1, 100L, 100L, "舊名稱")),
        )
        val csv = buildPosSalesCsv(
            "2026-05-13",
            listOf(sale),
            emptyList(),
            listOf(Product("p", "新名稱", 100L)),
            emptyList(),
        )
        assertTrue(csv.contains("舊名稱x1"))
        assertFalse(csv.contains("新名稱x1"))
    }
}
