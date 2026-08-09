package com.uberanalyzer.analyzer

import com.uberanalyzer.model.RideData
import com.uberanalyzer.model.ScoreRating

object RideAnalyzer {
    data class AnalysisResult(val score: Double, val rating: ScoreRating, val pricePerKm: Double)

    fun analyze(ride: RideData, minKmValue: Double = 2.0, minHourValue: Double = 45.0): AnalysisResult {
        val pricePerKm = ride.price / ride.distanceKm
        val hours = ride.timeMin / 60.0
        val pricePerHour = if (hours > 0) ride.price / hours else 0.0
        
        // Speed check as a proxy for traffic (KM/h)
        val speed = if (hours > 0) ride.distanceKm / hours else 0.0
        val trafficFactor = when {
            speed > 40 -> 1.2  // Fluid traffic
            speed < 15 -> 0.7  // Heavy traffic/congestion
            else -> 1.0        // Normal
        }
        
        // Base score on dynamic targets
        val kmScore = (pricePerKm / minKmValue) * 5.0
        val hourScore = (pricePerHour / minHourValue) * 5.0
        
        var score = (kmScore + hourScore).coerceIn(0.0, 10.0)
        
        // Apply traffic factor and category weight
        score *= trafficFactor
        score *= ride.category.weight
        score = score.coerceIn(0.0, 10.0)

        return AnalysisResult(score, ScoreRating.fromScore(score), pricePerKm)
    }
}
