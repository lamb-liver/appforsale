package com.lambliver.stallpos

import com.lambliver.stallpos.domain.*
import com.lambliver.stallpos.data.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosCsvExportTest {

    @Test
    fun formatSaleRecordDetails_legacyCart_escapesProductNameQuotes() {
        val prod = Product("p1", "明信片「特別版」", 100L)
        val pm = mapOf("p1" to prod)
        val r = SaleRecord(
            tsMillis = 1L,
            dateKey = "2026-05-13",
            subtotal = 100L,
            discount = 0L,
            total = 100L,
            cartSnapshot = mapOf("p1" to 1),
        )
        val details = formatSaleRecordDetailsForCsv(r, pm, emptyMap())
        assertEquals("明信片「特別版」x1", details)
        val csv = buildPosSalesCsv(100L, 1L, 100L, "2026-05-13", listOf(r), listOf(prod), emptyList())
        assertTrue(csv.contains("\"明信片「特別版」x1\""))
    }

    @Test
    fun csv_doublesEmbeddedQuotesInDetailsField() {
        val prod = Product("p1", "Say\"Hi", 10L)
        val r = SaleRecord(
            tsMillis = 4L,
            dateKey = "2026-05-13",
            subtotal = 10L,
            discount = 0L,
            total = 10L,
            cartSnapshot = mapOf("p1" to 1),
        )
        val csv = buildPosSalesCsv(10L, 1L, 10L, "2026-05-13", listOf(r), listOf(prod), emptyList())
        assertTrue(csv.contains("\"Say\"\"Hix1\""))
    }

    @Test
    fun csv_includeDigitalPaymentLabel() {
        val r = SaleRecord(
            tsMillis = 2L,
            dateKey = "2026-05-13",
            subtotal = 50L,
            discount = 0L,
            total = 50L,
            cartSnapshot = mapOf("x" to 1),
            paymentMethod = PaymentMethod.DIGITAL,
        )
        val csv = buildPosSalesCsv(50L, 1L, 50L, "2026-05-13", listOf(r), emptyList(), emptyList())
        assertTrue(csv.contains("\"行動支付\""))
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
}
