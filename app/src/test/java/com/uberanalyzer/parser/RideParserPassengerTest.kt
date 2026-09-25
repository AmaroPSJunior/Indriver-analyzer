package com.uberanalyzer.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class RideParserPassengerTest {
    @Test fun hideActionCannotBecomePassengerName() {
        val ride = RideParser.parseInDriverList("R$ 41 | Ocultar | Rua Avelino Paranhos 126 | Rua Hugo D'Antola 95").single()
        assertEquals("Passageiro inDrive", ride.passenger)
    }

    @Test fun eachPassengerStaysWithTheirFare() {
        val rides = RideParser.parseInDriverList("""
            R$ 2,6/km ~1,0 km | Sônia | R$ 15 | Rua José Pinto 127 | Rua Astorga 10
            R$ 1,6/km ~1,3 km | Delia | R$ 41 | Rua Avelino Paranhos 126 | Rua Hugo D'Antola 95
            R$ 2,1/km ~1,7 km | Sarah | R$ 40 | Rua Santa Bertila 190 | Rua Condessa 303
            R$ 1,6/km ~2,7 km | Iza | R$ 75 | Rua Iemanjá 23 | Rua Aurora 10
        """.trimIndent())
        assertEquals(listOf("Sônia", "Delia", "Sarah", "Iza"), rides.map { it.passenger })
        assertEquals(listOf(15.0, 41.0, 40.0, 75.0), rides.map { it.price })
    }

    @Test fun hideActionDoesNotOverrideVisibleName() {
        val ride = RideParser.parseInDriverList("R$ 41 | Ocultar | Delia | Rua Avelino Paranhos 126 | Rua Hugo D'Antola 95").single()
        assertEquals("Delia", ride.passenger)
    }
}
