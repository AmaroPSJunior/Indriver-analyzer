package com.uberanalyzer.parser

import com.uberanalyzer.model.InDriverRide
import org.junit.Assert.*
import org.junit.Test

class RideDestinationTest {
    private val pickup = "Rua Avelino Paranho 126 (Vila Talarico)"
    private val destination = "Rua Hugo D’Antola, 95 (Lapa, São Paulo - Estado de São Paulo)"
    private fun ride(name: String = "Delia", fare: Double = 50.0, origin: String = pickup, dest: String = destination) =
        InDriverRide(id = "changing-id", passenger = name, price = fare, pickupAddress = origin, dropoffAddress = dest)

    @Test fun preservesCityAndOriginalOrder() {
        val result = RideParser.parseInDriverList("R$ 1,8/km ~1,3 km | Delia | R$ 50 | $pickup | $destination").single()
        assertEquals(pickup, result.pickupAddress)
        assertEquals(destination, result.dropoffAddress)
    }

    @Test fun streetNamesAreNotSystemNoise() {
        listOf("Rua Dom Pedro, 10 - São Paulo", "Rua Segunda, 99", "Rua Vitória 50", "Rua Domingo de Agosto, 77").forEach {
            assertTrue(it, RideParser.isRealAddress(it))
        }
        listOf("Ocultar", "Definir destino no mapa", "São Paulo", "R$ 40", "31 seg.").forEach {
            assertFalse(it, RideParser.isRealAddress(it))
        }
    }

    @Test fun joinsWrappedDestinationAndKeepsAdditionalStopSeparate() {
        val (origin, dest) = RideAddressParser.extract(listOf(
            "Rua Xique-Xique, 562 (Cidade Patriarca, São Paulo - SP)",
            "Panobianco Academia - Vila Nhocuné (Rua Pontal - Vila Nhocuné,",
            "São Paulo - SP)",
            "Casa do Pastel Artur Alvim (Rua Doutor Campos Moura - Parque Artur",
            "Alvim, São Paulo - SP)"
        ))
        assertEquals("Rua Xique-Xique, 562 (Cidade Patriarca, São Paulo - SP)", origin)
        assertEquals("Casa do Pastel Artur Alvim (Rua Doutor Campos Moura - Parque Artur Alvim, São Paulo - SP)", dest)
    }

    @Test fun unreadablePickupDoesNotPromoteDestination() {
        assertEquals("" to destination, RideAddressParser.extract(listOf("Rua Avelino 126 (Vila", destination)))
    }

    @Test fun groupsByPriceGeometryDespiteShuffledOcrAndMissingDestination() {
        val lines = listOf(
            RideCardLayout.Line("R$ 1,8/km ~1,3 km", 150, 390, 350, 412),
            RideCardLayout.Line("R$ 50", 150, 420, 230, 447),
            RideCardLayout.Line("Delia", 105, 437, 135, 450),
            RideCardLayout.Line(pickup, 150, 463, 640, 480),
            RideCardLayout.Line(destination, 150, 492, 745, 510),
            RideCardLayout.Line("R$ 3,5/km ~1,4 km", 150, 540, 350, 562),
            RideCardLayout.Line("R$ 9", 150, 570, 225, 597),
            RideCardLayout.Line("Alvino", 105, 590, 140, 605),
            RideCardLayout.Line("Rua Pais de Linhares 549 (Jardim Maringá)", 150, 615, 650, 635),
            RideCardLayout.Line("Rua Loureiro 161 (Jardim Santa Maria)", 150, 645, 650, 662)
        ).reversed()
        val groups = RideCardLayout.group(lines).map { indices -> indices.map { lines[it].text } }
        assertEquals(2, groups.size)
        assertTrue(groups[0].contains("Delia"))
        assertFalse(groups[0].contains("Alvino"))
        assertTrue(groups[1].contains("Alvino"))
        val rides = groups.flatMap { RideParser.parseInDriverList(it.joinToString(" | ")) }
        assertEquals(listOf("Delia", "Alvino"), rides.map { it.passenger })
        assertEquals(listOf(destination, "Rua Loureiro 161 (Jardim Santa Maria)"), rides.map { it.dropoffAddress })
        val partial = lines.filter { it.text != destination }
        assertEquals(2, RideCardLayout.group(partial).size)
        val missingFare = partial.filter { it.text != "R$ 9" }
        val separated = RideCardLayout.group(missingFare).map { group -> group.map { missingFare[it].text } }
        assertFalse(separated.first().contains("Alvino"))
        assertFalse(separated.first().any { it.startsWith("Rua Loureiro") })
    }

    @Test fun transientOcrMissIsRepairedWithoutExtendingEvidenceLifetime() {
        val memory = RideDestinationMemory()
        memory.update(listOf(ride()), 0)
        assertEquals(destination, memory.update(listOf(ride(dest = "")), 1000).single().dropoffAddress)
        assertEquals(destination, memory.update(listOf(ride(dest = "")), 7000).single().dropoffAddress)
        assertEquals("", memory.update(listOf(ride(dest = "")), 9000).single().dropoffAddress)
    }

    @Test fun identityAndRemovalPreventCrossRideReuse() {
        for (changed in listOf(ride(name = "Alvino", dest = ""), ride(fare = 9.0, dest = ""),
            ride(origin = "Rua Outra 55", dest = ""), ride(name = "Passageiro inDrive", dest = ""))) {
            val memory = RideDestinationMemory()
            memory.update(listOf(ride()), 0)
            assertEquals("", memory.update(listOf(changed), 1000).single().dropoffAddress)
        }
        val memory = RideDestinationMemory()
        memory.update(listOf(ride()), 0)
        memory.update(emptyList(), 1000)
        assertEquals("", memory.update(listOf(ride(dest = "")), 2000).single().dropoffAddress)
    }

    @Test fun reorderUsesIdentityAndNewReadReplacesOldDestination() {
        val memory = RideDestinationMemory()
        val other = ride("Alvino", 9.0, "Rua Pais 549", "Rua Loureiro 161")
        memory.update(listOf(ride(), other), 0)
        assertEquals(listOf("Rua Loureiro 161", destination), memory.update(
            listOf(other.copy(dropoffAddress = ""), ride(dest = "")), 1000).map { it.dropoffAddress })
        assertEquals("Rua Nova 20", memory.update(listOf(ride(dest = "Rua Nova 20")), 2000).single().dropoffAddress)
    }

    @Test fun ambiguousIdentitiesNeverReuseDestination() {
        val memory = RideDestinationMemory()
        memory.update(listOf(ride()), 0)
        assertTrue(memory.update(listOf(ride(dest = ""), ride(dest = "")), 1000).all { it.dropoffAddress.isEmpty() })
    }

    @Test fun truncatedFinalStopIsNotReplacedWithIntermediateStop() {
        assertEquals(pickup to "", RideAddressParser.extract(listOf(pickup, destination, "Rua Final 20 (Centro")))
    }
}
