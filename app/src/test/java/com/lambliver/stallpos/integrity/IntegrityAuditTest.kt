package com.lambliver.stallpos.integrity

import com.lambliver.stallpos.domain.Bundle
import com.lambliver.stallpos.domain.BundleComponent
import com.lambliver.stallpos.domain.BundleRevenueAllocation
import com.lambliver.stallpos.domain.IntegrityAudit
import com.lambliver.stallpos.domain.IntegritySnapshot
import com.lambliver.stallpos.domain.InventoryLevel
import com.lambliver.stallpos.domain.InventoryLocation
import com.lambliver.stallpos.domain.InventoryMovement
import com.lambliver.stallpos.domain.InventoryMovementType
import com.lambliver.stallpos.domain.Product
import com.lambliver.stallpos.domain.SaleCheckoutLine
import com.lambliver.stallpos.domain.SaleRecord
import com.lambliver.stallpos.domain.SaleReversal
import com.lambliver.stallpos.domain.ReversalReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrityAuditTest {
    @Test
    fun emptySnapshot_isOk() {
        assertTrue(IntegrityAudit.audit(IntegritySnapshot()).ok)
    }

    @Test
    fun saleLinesDisagreeingWithHeader_isSALE003() {
        val sale = sale(id = "s1", total = 300, lines = listOf(200L, 200L), netAdjustment = 0)
        val report = IntegrityAudit.audit(IntegritySnapshot(sales = listOf(sale)))
        assertTrue(report.findings.any { it.code == "SALE-003" })
        assertFalse(report.ok)
        assertTrue(report.userMessage().contains("明細加起來跟結帳金額不一樣"))
        assertTrue(report.userMessage().contains("SALE-003"))
    }

    @Test
    fun duplicateSaleIds_isSALE002() {
        val sale = sale(id = "dup", total = 100, lines = listOf(100L))
        val report = IntegrityAudit.audit(IntegritySnapshot(sales = listOf(sale, sale.copy(tsMillis = 2))))
        assertTrue(report.findings.any { it.code == "SALE-002" })
    }

    @Test
    fun voidedSaleExcludedFromReportedRevenue() {
        val sale = sale(id = "s1", total = 100, lines = listOf(100L), tip = 10)
        val voided = SaleReversal("v1", "s1", 2, ReversalReason.UNDO_LAST_CHECKOUT)
        val ok = IntegrityAudit.audit(
            IntegritySnapshot(
                sales = listOf(sale),
                reversals = listOf(voided),
                reportedRevenue = 0,
                reportedTxCount = 0,
            ),
        )
        assertTrue(ok.ok)
        val stale = IntegrityAudit.audit(
            IntegritySnapshot(
                sales = listOf(sale),
                reversals = listOf(voided),
                reportedRevenue = 110,
                reportedTxCount = 1,
            ),
        )
        assertTrue(stale.findings.any { it.code == "AGG-001" })
    }

    @Test
    fun inventoryLevelDisagreeingWithMovements_isINV003() {
        val product = Product("p1", "徽章", 100, stock = 3)
        val level = InventoryLevel("p1", InventoryLocation.General, 9, 1)
        val movement = InventoryMovement(
            id = "m1",
            productId = "p1",
            eventId = null,
            from = null,
            to = InventoryLocation.General,
            type = InventoryMovementType.ADJUSTMENT,
            quantity = 3,
            relatedTransactionId = null,
            occurredAtMillis = 1,
        )
        val report = IntegrityAudit.audit(
            IntegritySnapshot(
                products = listOf(product),
                inventoryLevels = listOf(level),
                inventoryMovements = listOf(movement),
            ),
        )
        assertTrue(report.findings.any { it.code == "INV-003" && it.message.contains("徽章") })
    }

    @Test
    fun bundleAllocationMustMatchLine() {
        val product = Product("p1", "A", 50, stock = 10)
        val bundle = Bundle("b1", "套組", 100, components = listOf(BundleComponent("p1", 1)))
        val sale = sale(id = "s1", total = 100, lines = emptyList()).copy(
            checkoutLines = listOf(
                SaleCheckoutLine.Bundle("b1", 1, 100, 100, "套組"),
            ),
            bundleComponentAllocations = listOf(
                BundleRevenueAllocation(0, 0, "p1", "A", null, 1, 40),
            ),
        )
        val report = IntegrityAudit.audit(
            IntegritySnapshot(products = listOf(product), bundles = listOf(bundle), sales = listOf(sale)),
        )
        assertTrue(report.findings.any { it.code == "BND-002" })
    }

    @Test
    fun consistentBundleAndInventory_isOk() {
        val product = Product("p1", "A", 50, stock = 5)
        val bundle = Bundle("b1", "套組", 100, components = listOf(BundleComponent("p1", 1)))
        val sale = sale(id = "s1", total = 100, lines = emptyList()).copy(
            checkoutLines = listOf(SaleCheckoutLine.Bundle("b1", 1, 100, 100, "套組")),
            bundleComponentAllocations = listOf(
                BundleRevenueAllocation(0, 0, "p1", "A", null, 1, 100),
            ),
        )
        val movement = InventoryMovement(
            "m1", "p1", null, null, InventoryLocation.General,
            InventoryMovementType.ADJUSTMENT, 5, null, 1,
        )
        val report = IntegrityAudit.audit(
            IntegritySnapshot(
                products = listOf(product),
                bundles = listOf(bundle),
                sales = listOf(sale),
                inventoryLevels = listOf(InventoryLevel("p1", InventoryLocation.General, 5, 1)),
                inventoryMovements = listOf(movement),
                reportedRevenue = 100,
                reportedTxCount = 1,
            ),
        )
        assertEquals(emptyList<String>(), report.findings.map { it.code })
    }

    private fun sale(
        id: String,
        total: Long,
        lines: List<Long>,
        netAdjustment: Long = 0,
        tip: Long = 0,
    ) = SaleRecord(
        id = id,
        tsMillis = 1,
        dateKey = "2026-08-26",
        subtotal = total,
        discount = 0,
        total = total,
        cartSnapshot = emptyMap(),
        tipAmount = tip,
        checkoutLines = lines.mapIndexed { index, amount ->
            SaleCheckoutLine.Product("p$index", 1, amount, amount, "x")
        },
        netAdjustment = netAdjustment,
    )
}
