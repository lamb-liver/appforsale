package com.lambliver.stallpos

import com.lambliver.stallpos.domain.allocateLargestRemainder
import org.junit.Assert.assertEquals
import org.junit.Test

class MoneyAllocationTest {
    @Test
    fun largestRemainder_isExactAndDeterministic() {
        assertEquals(listOf(34L, 33L, 33L), allocateLargestRemainder(100, listOf(1, 1, 1)))
        assertEquals(listOf(67L, 33L), allocateLargestRemainder(100, listOf(2, 1)))
        assertEquals(listOf(5L, 0L), allocateLargestRemainder(5, listOf(0, 0)))
        assertEquals(100L, allocateLargestRemainder(100, listOf(3, 7, 11)).sum())
    }
}
