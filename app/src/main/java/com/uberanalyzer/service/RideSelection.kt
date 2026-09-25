package com.uberanalyzer.service

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Match identity, never the old displayed index, after a fresh screen capture. */
internal object RideSelection {
    fun isFresh(capturedAt: Long, now: Long, invalidatedAt: Long): Boolean =
        capturedAt > 0L && now >= capturedAt && now - capturedAt <= 5000L && invalidatedAt <= capturedAt + 750L

    data class Identity(val pickup: String, val dropoff: String, val price: Double)

    fun normalize(address: String): String = Normalizer.normalize(address, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim().replace(Regex("\\s+"), " ")
        .replace(Regex("^r "), "rua ")
        .replace(Regex("^av "), "avenida ")

    fun findUnique(target: Identity, candidates: List<Identity>): Int? {
        val pickup = normalize(target.pickup)
        if (pickup.length < 5 || !target.price.isFinite() || target.price <= 0.0) return null
        val matches = candidates.indices.filter {
            normalize(candidates[it].pickup) == pickup && candidates[it].price.isFinite() &&
                abs(candidates[it].price - target.price) < 0.005
        }
        if (matches.size == 1) return matches.single()
        val dropoff = normalize(target.dropoff)
        if (dropoff.length < 5) return null
        return matches.filter { normalize(candidates[it].dropoff) == dropoff }.singleOrNull()
    }
}
