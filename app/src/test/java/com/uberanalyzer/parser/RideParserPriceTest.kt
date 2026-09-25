package com.uberanalyzer.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideParserPriceTest {
    @Test
    fun perKmBadgeIsNeverUsedAsRidePrice() {
        val ride = RideParser.parse("R$ 2,50 / km | R$ 25,00 | 3,0 km | 10 min")

        assertEquals(25.0, ride!!.price, 0.001)
    }

    @Test
    fun textContainingOnlyPerKmBadgeHasNoRidePrice() {
        assertNull(RideParser.parse("R$ 2,50/km | 3,0 km | 10 min"))
    }

    @Test
    fun decimalPointIsNotMistakenForThousandsSeparator() {
        val ride = RideParser.parse("R$ 25.50 | 3.0 km | 10 min")

        assertEquals(25.5, ride!!.price, 0.001)
    }

    @Test
    fun brazilianThousandsSeparatorIsParsedCorrectly() {
        val prices = RideParser.extractRidePrices("R$ 1.234,56 | R$ 4,20/km")

        assertEquals(listOf(1234.56), prices)
    }
}
