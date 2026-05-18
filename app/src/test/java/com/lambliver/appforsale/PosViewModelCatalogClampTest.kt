package com.lambliver.appforsale

import com.lambliver.appforsale.domain.Bundle
import com.lambliver.appforsale.domain.Product
import com.lambliver.appforsale.ui.PosViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PosViewModelCatalogClampTest {

    @Test
    fun catalogClampKey_changesWhenStockChanges() {
        val p1 = listOf(Product("a", "A", 10L, stock = 5L))
        val key1 = PosViewModel.catalogClampKey(p1, emptyList())
        val key2 = PosViewModel.catalogClampKey(
            listOf(Product("a", "A", 10L, stock = 3L)),
            emptyList(),
        )
        assertNotEquals(key1, key2)
    }

    @Test
    fun catalogClampKey_stableForSameCatalog() {
        val products = listOf(Product("a", "A", 10L, stock = 5L))
        val bundles = listOf(Bundle("b", "B", 20L, "", emptyList()))
        val k1 = PosViewModel.catalogClampKey(products, bundles)
        val k2 = PosViewModel.catalogClampKey(products, bundles)
        assertEquals(k1, k2)
    }
}
