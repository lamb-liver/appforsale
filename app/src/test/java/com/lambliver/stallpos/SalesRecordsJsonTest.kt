package com.lambliver.stallpos

import com.lambliver.stallpos.domain.*
import com.lambliver.stallpos.data.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SalesRecordsJsonTest {

    @Test
    fun decode_minimalLegacyCompatibleRow() {
        val json =
            """[{"ts":10,"date":"2026-05-13","subtotal":100,"discount":0,"total":100,"cart":{"x":2},"bundles":{},"paymentMethod":"CASH","tipAmount":0,"lines":[],"stockDeductions":{}}]"""
        val rows = decodeSalesRecordsJson(json)
        assertEquals(1, rows.size)
        val r = rows[0]
        assertEquals(10L, r.tsMillis)
        assertEquals(mapOf("x" to 2), r.cartSnapshot)
        assertEquals(emptyMap<String, Int>(), r.bundleCartSnapshot)
        assertEquals(PaymentMethod.CASH, r.paymentMethod)
        assertEquals(emptyList<SaleCheckoutLine>(), r.checkoutLines)
        assertEquals(emptyMap<String, Long>(), r.stockDeductions)
    }

    @Test
    fun decode_digitalPayment_caseInsensitive() {
        val json =
            """[{"ts":1,"date":"d","subtotal":1,"discount":0,"total":1,"cart":{},"bundles":{},"paymentMethod":"digital","tipAmount":0,"lines":[],"stockDeductions":{}}]"""
        assertEquals(PaymentMethod.DIGITAL, decodeSalesRecordsJson(json).single().paymentMethod)
    }

    @Test
    fun decode_linesAndStockDeductions() {
        val json =
            """[{"ts":2,"date":"d","subtotal":400,"discount":0,"total":400,"cart":{"ignore":9},"bundles":{"b1":1},"paymentMethod":"CASH","tipAmount":0,"lines":[{"kind":"product","productId":"a","qty":1,"unitPrice":100,"lineSubtotal":100},{"kind":"bundle","bundleId":"b1","qty":1,"unitPrice":300,"lineSubtotal":300}],"stockDeductions":{"a":5}}]"""
        val r = decodeSalesRecordsJson(json).single()
        assertEquals(
            listOf(
                SaleCheckoutLine.Product("a", 1, 100L, 100L),
                SaleCheckoutLine.Bundle("b1", 1, 300L, 300L),
            ),
            r.checkoutLines,
        )
        assertEquals(mapOf("a" to 5L), r.stockDeductions)
        assertEquals(mapOf("ignore" to 9), r.cartSnapshot)
        assertEquals(mapOf("b1" to 1), r.bundleCartSnapshot)
    }

    @Test
    fun encodeDecode_roundTrip_preservesSaleRecords() {
        val rich = SaleRecord(
            tsMillis = 99L,
            dateKey = "2026-05-14",
            subtotal = 500L,
            discount = 50L,
            total = 450L,
            cartSnapshot = mapOf("p1" to 1),
            bundleCartSnapshot = mapOf("bb" to 2),
            paymentMethod = PaymentMethod.DIGITAL,
            tipAmount = 10L,
            checkoutLines = listOf(SaleCheckoutLine.Product("p1", 1, 100L, 100L)),
            stockDeductions = mapOf("p1" to 3L),
        )
        val minimal = SaleRecord(
            tsMillis = 1L,
            dateKey = "d",
            subtotal = 0L,
            discount = 0L,
            total = 0L,
            cartSnapshot = emptyMap(),
        )
        val original = listOf(rich, minimal)
        assertEquals(original, decodeSalesRecordsJson(encodeSalesRecordsJson(original)))
    }

    @Test
    fun encodeDecode_roundTrip_preservesLastCheckout() {
        val lc = LastCheckout(
            tsMillis = 100L,
            total = 999L,
            productCart = mapOf("a" to 2),
            bundleCart = mapOf("b" to 1),
            stockDeductions = mapOf("a" to 5L),
        )
        assertEquals(lc, decodeLastCheckoutJson(encodeLastCheckoutJson(lc)))
        assertEquals(null, decodeLastCheckoutJson(""))
    }

    @Test
    fun encode_emptySalesLog_roundTripsToEmpty() {
        assertTrue(decodeSalesRecordsJson(encodeSalesRecordsJson(emptyList())).isEmpty())
    }

    @Test
    fun decode_invalid_returnsEmpty() {
        assertTrue(decodeSalesRecordsJson("oops").isEmpty())
        assertTrue(decodeSalesRecordsJson("[]").isEmpty())
    }
}
