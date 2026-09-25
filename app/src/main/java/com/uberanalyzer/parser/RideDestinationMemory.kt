package com.uberanalyzer.parser

import com.uberanalyzer.model.InDriverRide
import java.util.Locale

/** Short-lived evidence, keyed by identity rather than queue position. */
class RideDestinationMemory(private val ttlMs: Long = 8_000) {
    private data class Evidence(val destination: String, val readAt: Long)
    private var previous = emptyMap<String, Evidence>()
    private fun key(ride: InDriverRide): String? {
        val name = ride.passenger.trim().lowercase(Locale.ROOT)
        if (name.isBlank() || name.startsWith("passageiro") || !RideAddressParser.isAddress(ride.pickupAddress)) return null
        return "$name|${ride.price}|${ride.pickupAddress.trim().lowercase(Locale.ROOT)}"
    }

    @Synchronized fun update(rides: List<InDriverRide>, now: Long): List<InDriverRide> {
        val counts = rides.mapNotNull(::key).groupingBy { it }.eachCount()
        val next = mutableMapOf<String, Evidence>()
        val result = rides.map { ride ->
            val id = key(ride)
            if (id == null || counts[id] != 1) return@map ride
            if (RideAddressParser.isAddress(ride.dropoffAddress)) {
                next[id] = Evidence(ride.dropoffAddress, now)
                ride
            } else {
                val evidence = previous[id]?.takeIf { now - it.readAt in 0..ttlMs }
                // Only repair an absent field. Never override an explicit new destination/placeholder.
                if (evidence != null && ride.dropoffAddress.isBlank()) {
                    next[id] = evidence
                    ride.copy(dropoffAddress = evidence.destination)
                } else ride
            }
        }
        previous = next // A removed ride cannot later inherit stale data.
        return result
    }
}
