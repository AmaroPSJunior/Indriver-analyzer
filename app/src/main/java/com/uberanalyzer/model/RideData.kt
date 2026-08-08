package com.uberanalyzer.model

import org.json.JSONArray
import org.json.JSONObject

data class InDriverRide(
    val id: String,
    val passenger: String = "Passageiro",
    val passengerPhoto: String = "",
    val rating: String = "5.0",
    val price: Double,
    val pickupAddress: String = "Origem não especificada",
    val dropoffAddress: String = "Destino não especificado",
    val distancePickupKm: Double = 0.0,
    val distanceTripKm: Double = 0.0,
    val totalDistanceKm: Double = 0.0,
    val estimatedTimeMin: Int = 0,
    val earningsPerKm: Double = 0.0,
    val earningsPerHour: Double = 0.0,
    val score: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis(),
    val rawText: String = "",
    val paymentMethod: String = "",
    val pLat: Double = 0.0,
    val pLng: Double = 0.0,
    val dLat: Double = 0.0,
    val dLng: Double = 0.0
) {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("passenger", passenger)
        json.put("passenger_photo", passengerPhoto)
        json.put("rating", rating)
        json.put("price_brl", price)
        json.put("pickup_address", pickupAddress)
        json.put("dropoff_address", dropoffAddress)
        json.put("pickup_distance_km", distancePickupKm)
        json.put("trip_distance_km", distanceTripKm)
        json.put("total_distance_km", totalDistanceKm)
        json.put("estimated_time_min", estimatedTimeMin)
        json.put("earnings_per_km_brl", if (earningsPerKm > 0) String.format(java.util.Locale.US, "%.2f", earningsPerKm).toDoubleOrNull() ?: earningsPerKm else 0.0)
        json.put("earnings_per_hour_brl", if (earningsPerHour > 0) String.format(java.util.Locale.US, "%.2f", earningsPerHour).toDoubleOrNull() ?: earningsPerHour else 0.0)
        json.put("score", score)
        json.put("timestamp", timestamp)
        json.put("payment_method", paymentMethod)
        json.put("raw_text", rawText.take(200))
        json.put("pLat", pLat)
        json.put("pLng", pLng)
        json.put("dLat", dLat)
        json.put("dLng", dLng)
        return json
    }
}

object InDriverJsonFormatter {
    fun toJsonArray(rides: List<InDriverRide>): JSONArray {
        val array = JSONArray()
        rides.forEach { array.put(it.toJsonObject()) }
        return array
    }

    fun toFormattedJson(rides: List<InDriverRide>, indentSpaces: Int = 2): String {
        val root = JSONObject()
        root.put("app", "inDriver Driver Analyzer")
        root.put("captured_at", System.currentTimeMillis())
        root.put("total_rides_in_queue", rides.size)
        root.put("rides", toJsonArray(rides))
        return root.toString(indentSpaces)
    }
}

data class RideData(
    val price: Double, 
    val distanceKm: Double, 
    val timeMin: Int, 
    val category: RideCategory, 
    val raw: String,
    val pickupAddress: String = "",
    val dropoffAddress: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

enum class RideCategory(val displayName: String, val weight: Double, val colorHex: String) {
    INDRIVER_CITY("inDrive Cidade", 1.0, "#F21B5E20"), 
    INDRIVER_INTERCITY("inDrive Interurbano", 1.3, "#F20D47A1"), 
    INDRIVER_DELIVERY("inDrive Entrega", 0.9, "#F2E65100"), 
    UBER_X("inDrive Normal", 1.0, "#F2121212"),
    COMFORT("inDrive Conforto", 1.3, "#F21A237E"),
    BLACK("inDrive Premium", 1.6, "#F2000000"),
    FLASH("inDrive Flash", 0.9, "#F2E65100"),
    UNKNOWN("inDrive", 1.0, "#F2121212");

    companion object {
        fun fromString(s: String) = when {
            s.contains("Inter", true) -> INDRIVER_INTERCITY
            s.contains("Entreg", true) || s.contains("Deliver", true) -> INDRIVER_DELIVERY
            s.contains("Comfort", true) || s.contains("Confort", true) -> COMFORT
            else -> INDRIVER_CITY
        }
    }
}

enum class ScoreRating(val label: String, val colorHex: String) {
    EXCELLENT("Excelente", "#4CAF50"), GOOD("Boa", "#8BC34A"), AVERAGE("OK", "#FFC107"), BAD("Ruim", "#F44336");
    companion object {
        fun fromScore(s: Double) = when { s >= 8.0 -> EXCELLENT; s >= 6.0 -> GOOD; s >= 4.0 -> AVERAGE; else -> BAD }
    }
}

