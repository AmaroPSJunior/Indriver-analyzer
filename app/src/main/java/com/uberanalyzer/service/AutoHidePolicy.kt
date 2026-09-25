package com.uberanalyzer.service

/** Choose one removal per fresh scan; equality meets the minimum. */
internal object AutoHidePolicy {
    fun firstBelowMinimum(earningsPerKm: List<Double>, minimum: Double, visibleRideCount: Int = earningsPerKm.size): Int? {
        if (visibleRideCount <= 1) return null
        if (!minimum.isFinite() || minimum <= 0.0) return null
        return earningsPerKm.take(3).indexOfFirst {
            it.isFinite() && it > 0.0 && it < minimum
        }.takeIf { it >= 0 }
    }
}
