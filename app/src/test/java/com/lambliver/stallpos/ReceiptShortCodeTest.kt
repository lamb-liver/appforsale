package com.lambliver.stallpos

import com.lambliver.stallpos.data.receiptShortCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiptShortCodeTest {
    @Test
    fun newDevicesUseFiveHexDigitsAndSkipReservedA() {
        val code = receiptShortCode("20000000-0000-4000-8000-00000000000b")
        assertEquals("0000B", code)
        assertNotEquals("A", code)
        assertTrue(code.matches(Regex("^[A-Z0-9]{5,8}$")))
    }

    @Test
    fun lengthensWhenFiveDigitsWouldBeReserved() {
        assertEquals("0AAAAA", receiptShortCode("00000000-0000-4000-8000-0000000aaaaa", reserved = setOf("AAAAA")))
    }
}
