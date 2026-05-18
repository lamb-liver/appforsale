package com.lambliver.appforsale

import com.lambliver.appforsale.domain.*
import com.lambliver.appforsale.data.*

import org.junit.Assert.assertEquals
import org.junit.Test

class SaleRecordReportTest {

    @Test
    fun productQtySoldForReport_usesStockDeductionsWhenPresent() {
        val r = SaleRecord(
            tsMillis = 1L,
            dateKey = "2026-05-13",
            subtotal = 100L,
            discount = 0L,
            total = 100L,
            cartSnapshot = mapOf("x" to 2),
            stockDeductions = mapOf("x" to 7L),
        )
        assertEquals(mapOf("x" to 7L), r.productQtySoldForReport())
    }

    @Test
    fun productQtySoldForReport_fallsBackToCartSnapshot() {
        val r = SaleRecord(
            tsMillis = 1L,
            dateKey = "2026-05-13",
            subtotal = 100L,
            discount = 0L,
            total = 100L,
            cartSnapshot = mapOf("x" to 3, "y" to 1),
            stockDeductions = emptyMap(),
        )
        assertEquals(mapOf("x" to 3L, "y" to 1L), r.productQtySoldForReport())
    }
}
