package com.lambliver.appforsale

import com.lambliver.appforsale.domain.CheckoutAmounts
import com.lambliver.appforsale.domain.CheckoutSheetPricingSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckoutSheetPricingSnapshotTest {

    @Test
    fun toConfirmationSnapshot_preservesCents() {
        val sheet = CheckoutSheetPricingSnapshot(catalogSubtotal = 100L, paymentAdjustment = -40L)
        val confirmation = sheet.toConfirmationSnapshot()

        assertEquals(sheet.catalogSubtotal, confirmation.catalogSubtotal.cents)
        assertEquals(sheet.paymentAdjustment, confirmation.netAdjustment.cents)
        assertEquals(sheet.surfaceReceivable, confirmation.surfaceAmountDue.cents)
    }

    @Test
    fun from_roundTripsWithToConfirmationSnapshot() {
        val original = CheckoutSheetPricingSnapshot(catalogSubtotal = 30L, paymentAdjustment = 20L)
        val roundTrip = CheckoutSheetPricingSnapshot.from(original.toConfirmationSnapshot())

        assertEquals(original.catalogSubtotal, roundTrip.catalogSubtotal)
        assertEquals(original.paymentAdjustment, roundTrip.paymentAdjustment)
        assertEquals(original.surfaceReceivable, roundTrip.surfaceReceivable)
    }
}
