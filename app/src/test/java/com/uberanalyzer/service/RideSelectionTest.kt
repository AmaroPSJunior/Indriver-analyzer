package com.uberanalyzer.service

import org.junit.Assert.*
import org.junit.Test

class RideSelectionTest {
    @Test fun recentUnchangedCaptureCanOpenImmediately() {
        assertTrue(RideSelection.isFresh(1000, 1100, 900))
        assertTrue(RideSelection.isFresh(1000, 2500, 900))
    }

    @Test fun rejectsExpiredInvalidatedOrMissingCapture() {
        assertFalse(RideSelection.isFresh(1000, 6001, 900))
        assertFalse(RideSelection.isFresh(1000, 1200, 1800))
        assertFalse(RideSelection.isFresh(0, 100, 0))
        assertFalse(RideSelection.isFresh(1000, 999, 900))
    }

    private fun ride(pickup: String = "Rua São João, 120", destination: String = "Rua B, 40", price: Double = 25.5) =
        RideSelection.Identity(pickup, destination, price)

    @Test fun findsTripAfterQueueReorders() {
        assertEquals(1, RideSelection.findUnique(ride(), listOf(ride("Rua Outra 10"), ride())))
    }
    @Test fun ignoresAccentPunctuationAndCommonStreetAbbreviation() {
        assertEquals(0, RideSelection.findUnique(ride(), listOf(ride("R. Sao Joao 120"))))
    }
    @Test fun rejectsDifferentHouseNumberAndChangedFare() {
        assertNull(RideSelection.findUnique(ride(), listOf(ride("Rua São João 121"), ride(price = 26.0))))
    }
    @Test fun usesDestinationToDisambiguateAndRejectsDuplicates() {
        assertEquals(1, RideSelection.findUnique(ride(), listOf(ride(destination = "Rua C 55"), ride())))
        assertNull(RideSelection.findUnique(ride(), listOf(ride(), ride())))
    }
    @Test fun missingTripAndIncompleteIdentityNeverUseOldIndex() {
        assertNull(RideSelection.findUnique(ride(), emptyList()))
        assertNull(RideSelection.findUnique(ride(pickup = ""), listOf(ride(pickup = ""))))
        assertNull(RideSelection.findUnique(ride(price = Double.NaN), listOf(ride())))
    }
}
