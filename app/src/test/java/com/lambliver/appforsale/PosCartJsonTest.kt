package com.lambliver.appforsale

import com.lambliver.appforsale.domain.*
import com.lambliver.appforsale.data.*

import org.junit.Assert.assertEquals
import org.junit.Test

class PosCartJsonTest {

    @Test
    fun roundTrip_v2_productsAndBundles() {
        val cart = PosCart(
            products = mapOf("p1" to 2, "p2" to 1),
            bundles = mapOf("b1" to 3),
        )
        val json = encodePosCartJson(cart)
        assertEquals(cart, decodePosCartJson(json))
    }

    @Test
    fun legacy_flatObject_noVersion_readsAsProductCartOnly() {
        val json = """{"同人本":3,"吊飾":1}"""
        assertEquals(
            PosCart(products = mapOf("同人本" to 3, "吊飾" to 1), bundles = emptyMap()),
            decodePosCartJson(json),
        )
    }

    @Test
    fun legacy_skipsReservedKeys_v_p_b() {
        val json = """{"v":99,"p":1,"b":2,"realItem":4}"""
        assertEquals(
            PosCart(products = mapOf("realItem" to 4), bundles = emptyMap()),
            decodePosCartJson(json),
        )
    }

    @Test
    fun blankOrMalformed_becomesEmptyCart() {
        assertEquals(PosCart(), decodePosCartJson(""))
        assertEquals(PosCart(), decodePosCartJson("not-json"))
    }
}
