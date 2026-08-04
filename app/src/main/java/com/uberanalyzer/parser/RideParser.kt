package com.uberanalyzer.parser

import com.uberanalyzer.model.InDriverRide
import com.uberanalyzer.model.RideCategory
import com.uberanalyzer.model.RideData
import java.util.Locale
import java.util.UUID

object RideParser {
    private val priceRegex = Regex("R\\$\\s*([0-9]{1,3}(?:[.][0-9]{3})*[,.][0-9]{2}|[0-9]+)")
    private val distanceRegex = Regex("([0-9]+[,.][0-9]+|[0-9]+)\\s*km", RegexOption.IGNORE_CASE)
    private val timeRegex = Regex("([0-9]+)\\s*(min|minutos)", RegexOption.IGNORE_CASE)
    private val ratingRegex = Regex("([3-5][.,][0-9])\\s*★?")

    /**
     * Parse multiple rides visible on the inDriver waiting list screen
     */
    fun parseInDriverList(screenText: String): List<InDriverRide> {
        val rides = mutableListOf<InDriverRide>()
        val blocks = screenText.split(Regex("(?=R\\$\\s*[0-9]+|\\bOferecer\\b|\\bAceitar\\b|\\bRecusar\\b)"))

        var index = 1
        for (block in blocks) {
            val trimmed = block.trim()
            if (trimmed.length < 10) continue
            
            val priceMatch = priceRegex.find(trimmed) ?: continue
            val priceStr = priceMatch.groupValues[1].replace(".", "").replace(",", ".")
            val price = priceStr.toDoubleOrNull() ?: continue
            if (price <= 0.0) continue

            // Distances (pickup vs trip)
            val kms = distanceRegex.findAll(trimmed).mapNotNull {
                it.groupValues[1].replace(",", ".").toDoubleOrNull()
            }.toList()

            val pickupKm = if (kms.isNotEmpty()) kms[0] else 0.0
            val tripKm = if (kms.size >= 2) kms[1] else (if (kms.size == 1) kms[0] else 1.0)
            val totalKm = if (kms.size >= 2) pickupKm + tripKm else (if (kms.isNotEmpty()) kms[0] else 1.0)

            // Duration
            val mins = timeRegex.findAll(trimmed).mapNotNull {
                it.groupValues[1].toIntOrNull()
            }.toList()
            val timeMin = if (mins.isNotEmpty()) mins[0] else (totalKm * 2.5).toInt().coerceAtLeast(3)

            // Rating / Passenger
            val ratingMatch = ratingRegex.find(trimmed)
            val rating = ratingMatch?.groupValues?.get(1)?.replace(",", ".") ?: "5.0"

            // Extract potential addresses or locations from block lines
            val lines = trimmed.split("|", "\n", "•").map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("R$") }
            val pickupAddr = lines.firstOrNull { it.contains("Rua", true) || it.contains("Av", true) || it.contains("Bairro", true) || (it.length >= 6 && !it.contains("km", true) && !it.contains("min", true)) } ?: ""
            val dropoffAddr = lines.lastOrNull { (it.contains("Rua", true) || it.contains("Av", true) || it.contains("Centro", true) || (it.length >= 6 && !it.contains("km", true) && !it.contains("min", true))) && it != pickupAddr } ?: ""

            if (pickupAddr.length < 6 || dropoffAddr.length < 6) continue

            val perKm = if (totalKm > 0) price / totalKm else 0.0
            val perHour = if (timeMin > 0) price / (timeMin / 60.0) else 0.0
            val score = calculateScore(price, totalKm, timeMin)

            rides.add(
                InDriverRide(
                    id = "IND-${System.currentTimeMillis() % 100000}-$index",
                    passenger = "Passageiro $index",
                    rating = rating,
                    price = price,
                    pickupAddress = pickupAddr,
                    dropoffAddress = dropoffAddr,
                    distancePickupKm = pickupKm,
                    distanceTripKm = tripKm,
                    totalDistanceKm = totalKm,
                    estimatedTimeMin = timeMin,
                    earningsPerKm = perKm,
                    earningsPerHour = perHour,
                    score = score,
                    rawText = trimmed,
                    pLat = 0.0,
                    pLng = 0.0,
                    dLat = 0.0,
                    dLng = 0.0
                )
            )
            index++
        }

        // If block parsing didn't produce items but text has R$, fall back to full screen parse
        if (rides.isEmpty() && screenText.lowercase().contains("r$")) {
            val single = parseSingleInDriver(screenText)
            if (single != null) rides.add(single)
        }

        // Deduplicate identical rides and strictly limit to up to 10 rides visible in queue
        val uniqueRides = rides.distinctBy {
            "${it.price}_${(it.totalDistanceKm * 10).toInt()}_${it.pickupAddress.take(15)}"
        }

        val topRides = uniqueRides.take(10)
        return topRides.mapIndexed { i, ride ->
            val num = i + 1
            val nameCandidate = extractPassengerName(ride.rawText) ?: "Passageiro"
            val photoCandidate = ride.passengerPhoto
            ride.copy(
                id = "IND-${System.currentTimeMillis() % 100000}-$num",
                passenger = nameCandidate,
                passengerPhoto = photoCandidate
            )
        }
    }

    private fun extractPassengerName(text: String): String? {
        val ignoreWords = setOf(
            "aceitar", "oferecer", "recusar", "indriver", "corrida", "solicitação", "online", "offline",
            "chegada", "embarque", "destino", "opções", "voltar", "fechar", "detalhes", "cancelar",
            "dinheiro", "pix", "cartão", "iniciar", "cheguei", "finalizar", "navegar"
        )
        val lines = text.split("\n", "|", "•").map { it.trim() }
        for (line in lines) {
            val lower = line.lowercase(Locale.getDefault())
            if (line.length in 3..18 && 
                !line.contains("R$") && 
                !line.contains("km", true) && 
                !line.contains("min", true) && 
                !line.contains("Rua", true) && 
                !line.contains("Av", true) &&
                !line.any { it.isDigit() } &&
                ignoreWords.none { lower == it || lower.contains(it) }) {
                if (line.firstOrNull()?.isUpperCase() == true) {
                    return line
                }
            }
        }
        return null
    }

    private fun parseSingleInDriver(text: String): InDriverRide? {
        val priceMatch = priceRegex.find(text) ?: return null
        val priceStr = priceMatch.groupValues[1].replace(".", "").replace(",", ".")
        val price = priceStr.toDoubleOrNull() ?: return null

        val kms = distanceRegex.findAll(text).mapNotNull {
            it.groupValues[1].replace(",", ".").toDoubleOrNull()
        }.toList()

        val totalKm = if (kms.isNotEmpty()) kms.maxOrNull() ?: 1.0 else 1.0
        val pickupKm = if (kms.size >= 2) kms.minOrNull() ?: 0.5 else 0.5
        val timeMin = timeRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: (totalKm * 2.5).toInt().coerceAtLeast(4)

        val lines = text.split("|", "\n", "•").map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("R$") }
        val pickupAddr = lines.firstOrNull { it.contains("Rua", true) || it.contains("Av", true) || it.contains("Bairro", true) || (it.length >= 6 && !it.contains("km", true) && !it.contains("min", true)) } ?: ""
        val dropoffAddr = lines.lastOrNull { (it.contains("Rua", true) || it.contains("Av", true) || it.contains("Centro", true) || (it.length >= 6 && !it.contains("km", true) && !it.contains("min", true))) && it != pickupAddr } ?: ""

        if (pickupAddr.length < 6 || dropoffAddr.length < 6) {
            return null
        }

        val perKm = price / totalKm
        val perHour = price / (timeMin / 60.0)

        return InDriverRide(
            id = "IND-${System.currentTimeMillis() % 100000}-1",
            passenger = extractPassengerName(text) ?: "Passageiro",
            rating = "4.9",
            price = price,
            pickupAddress = pickupAddr,
            dropoffAddress = dropoffAddr,
            distancePickupKm = pickupKm,
            distanceTripKm = totalKm,
            totalDistanceKm = totalKm + pickupKm,
            estimatedTimeMin = timeMin,
            earningsPerKm = perKm,
            earningsPerHour = perHour,
            score = calculateScore(price, totalKm, timeMin),
            rawText = text
        )
    }

    private fun calculateScore(price: Double, distanceKm: Double, timeMin: Int): Double {
        val kmRate = if (distanceKm > 0) price / distanceKm else price
        val hourRate = if (timeMin > 0) price / (timeMin / 60.0) else price
        
        var score = (kmRate * 2.5) + (hourRate / 10.0)
        return score.coerceIn(1.0, 10.0)
    }

    fun parse(text: String): RideData? {
        val lowerText = text.lowercase(Locale.getDefault())
        if (!lowerText.contains("r$")) return null

        val prices = priceRegex.findAll(text).mapNotNull { 
            val clean = it.groupValues[1].replace(".", "").replace(",", ".")
            clean.toDoubleOrNull() 
        }.toList()
        
        val price = prices.maxOrNull() ?: return null

        val kms = distanceRegex.findAll(lowerText).mapNotNull { 
            it.groupValues[1].replace(",", ".").toDoubleOrNull() 
        }.toList()
        
        val dist = if (kms.size >= 2) kms.sum() else if (kms.isNotEmpty()) kms[0] else 1.0

        val mins = timeRegex.findAll(lowerText).mapNotNull { 
            it.groupValues[1].toIntOrNull()
        }.toList()
        
        val time = if (mins.size >= 2) mins.sum() else mins.firstOrNull() ?: 5

        return RideData(price, dist, time, RideCategory.fromString(text), text)
    }
}

