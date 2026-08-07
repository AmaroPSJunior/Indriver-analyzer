package com.uberanalyzer.parser

import com.uberanalyzer.model.InDriverRide
import com.uberanalyzer.model.RideCategory
import com.uberanalyzer.model.RideData
import java.util.Locale

object RideParser {
    private val priceRegex = Regex("R\\$\\s*([0-9]{1,3}(?:[.][0-9]{3})*[,.][0-9]{2}|[0-9]+)")
    private val distanceRegex = Regex("([0-9]+[,.][0-9]+|[0-9]+)\\s*km", RegexOption.IGNORE_CASE)
    private val timeRegex = Regex("([0-9]+)\\s*(min|minutos)", RegexOption.IGNORE_CASE)
    private val ratingRegex = Regex("([3-5][.,][0-9])\\s*★?")

    // Forbidden UI buttons, badges, status labels, or system text - MUST NEVER BE CLASSIFIED AS PASSENGER NAME OR ADDRESS
    private val NOISE_KEYWORDS = setOf(
        "reclamar", "preço justo", "preco justo", "bônus", "bonus", "+1 bônus", "+2 bônus", "+3 bônus",
        "escolher no mapa", "definir no mapa", "ar-condicionado", "ar condicionado", "bagagem", "pet",
        "viagem", "corrida", "oferecer", "aceitar", "recusar", "sugerir", "negociar", "sugerir seu preço",
        "dinheiro", "pix", "cartão", "cartao", "troco", "desconto", "oferta", "promocao", "promoção",
        "opções", "opcoes", "detalhes", "cancelar", "voltar", "fechar", "indriver", "indrive", "uber", "99",
        "solicitação", "solicitacao", "embarque", "desembarque", "origem", "destino", "passageiro",
        "motorista", "avaliação", "avaliar", "classificação", "pontuação", "topo", "fila", "leitor ativo",
        "ver detalhes", "toque para ver", "chamar", "suporte", "ajuda", "preço", "preco", "justo",
        "solicitações", "solicitacoes", "aguardando", "lado a lado", "analisador", "analyzer"
    )

    // Indicators for street names, avenues, logradouros, neighborhoods, or landmarks
    private val STREET_KEYWORDS = listOf(
        "rua", "r.", "avenida", "av.", "av", "alameda", "al.", "praça", "praca", "pç.",
        "estrada", "est.", "rodovia", "rod.", "servidão", "servidao", "serv.",
        "viaduto", "vd.", "travessa", "tv.", "largo", "lg.", "ponte", "passagem", "beco",
        "quadra", "qd", "trecho", "acesso", "marginal", "bairro", "jardim", "jd.", "vila", "vl.",
        "parque", "pq.", "condomínio", "condominio", "residencial", "res.", "loteamento",
        "centro", "estação", "estacao", "terminal", "aeroporto", "shopping", "hospital", "universidade", "ponto", "praia",
        "são", "santo", "santa", "norte", "sul", "leste", "oeste"
    )

    // Logradouro prefixes that explicitly mark the beginning or presence of a street name
    private val LOGRADOURO_PREFIXES = listOf(
        "rua", "r.", "avenida", "av.", "av", "alameda", "al.", "praça", "praca", "pç.", "travessa", "tv.",
        "estrada", "est.", "rodovia", "rod.", "servidão", "servidao", "serv.", "viaduto", "vd.", "largo", "lg.",
        "ponte", "passagem", "beco", "quadra", "trecho", "acesso", "marginal", "vila", "vl.", "jardim", "jd.",
        "parque", "pq.", "condomínio", "condominio", "residencial", "res.", "bairro"
    )

    /**
     * Parse multiple rides visible on the inDriver waiting list screen
     */
    fun parseInDriverList(screenText: String): List<InDriverRide> {
        val rides = mutableListOf<InDriverRide>()
        val blocks = screenText.split(Regex("(?=R\\$\\s*[0-9]+|\\bOferecer\\b|\\bAceitar\\b|\\bRecusar\\b)"))

        var index = 1
        for (block in blocks) {
            val trimmed = block.trim()
            if (trimmed.length < 8) continue

            val priceMatch = priceRegex.find(trimmed) ?: continue
            val priceStr = priceMatch.groupValues[1].replace(".", "").replace(",", ".")
            val price = priceStr.toDoubleOrNull() ?: continue
            if (price <= 0.0) continue

            // Distances (pickup vs trip)
            val kms = distanceRegex.findAll(trimmed).mapNotNull {
                it.groupValues[1].replace(",", ".").toDoubleOrNull()
            }.toList()

            val pickupKm = if (kms.isNotEmpty()) kms[0] else 0.5
            val tripKm = if (kms.size >= 2) kms[1] else (if (kms.size == 1) kms[0] else 1.0)
            val totalKm = if (kms.size >= 2) pickupKm + tripKm else (if (kms.isNotEmpty()) kms[0] else 1.0)
            if (totalKm <= 0.0) continue

            // Duration
            val mins = timeRegex.findAll(trimmed).mapNotNull {
                it.groupValues[1].toIntOrNull()
            }.toList()
            val timeMin = if (mins.isNotEmpty()) mins[0] else (totalKm * 2.5).toInt().coerceAtLeast(3)

            // Rating
            val ratingMatch = ratingRegex.find(trimmed)
            val rating = ratingMatch?.groupValues?.get(1)?.replace(",", ".") ?: "5.0"

            // Extract text lines, filtering out R$ prices, distances, and duration values
            val rawLines = trimmed.split("|", "\n", "•").map { it.trim() }
                .filter { line ->
                    line.isNotBlank() && 
                    !line.startsWith("R$") && 
                    !line.contains("km", true) && 
                    !line.contains("min", true) &&
                    !line.matches(Regex("R\\$\\s*[0-9].*"))
                }

            // 1. Extract Passenger Name FIRST using strict clean name validation
            val passengerName = extractPassengerNameClean(rawLines)
            // STRICT RULE: If passenger name cannot be properly recognized, DO NOT create card or JSON
            if (passengerName.isNullOrBlank()) continue

            // 2. Extract Passenger Photo URL if present
            val passengerPhoto = extractPassengerPhotoUrl(rawLines)

            // 3. Filter remaining lines for address extraction (excluding passenger name and UI noise)
            val addressLines = rawLines.filter { line ->
                val lower = line.lowercase(Locale.getDefault())
                line != passengerName &&
                NOISE_KEYWORDS.none { noise -> lower == noise || lower.contains(noise) }
            }

            // 4. Extract Origin and Destination addresses strictly
            val (pickupAddr, dropoffAddr) = extractAddressesStrict(addressLines, trimmed)
            // STRICT RULE: If pickup or dropoff address cannot be properly recognized, DO NOT create card or JSON
            if (pickupAddr.isBlank() || dropoffAddr.isBlank()) continue

            val perKm = if (totalKm > 0) price / totalKm else 0.0
            val perHour = if (timeMin > 0) price / (timeMin / 60.0) else 0.0
            val score = calculateScore(price, totalKm, timeMin)

            rides.add(
                InDriverRide(
                    id = "IND-${System.currentTimeMillis() % 100000}-$index",
                    passenger = passengerName,
                    passengerPhoto = passengerPhoto,
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
                    rawText = trimmed
                )
            )
            index++
        }

        // If block parsing didn't produce items but text has R$, fall back to full screen parse
        if (rides.isEmpty() && screenText.lowercase(Locale.getDefault()).contains("r$")) {
            val single = parseSingleInDriver(screenText)
            if (single != null) rides.add(single)
        }

        // Deduplicate identical rides and strictly limit to up to 10 rides
        val uniqueRides = rides.distinctBy {
            "${it.price}_${(it.totalDistanceKm * 10).toInt()}_${it.pickupAddress.take(15)}"
        }

        val topRides = uniqueRides.take(10)
        return topRides.mapIndexed { i, ride ->
            val num = i + 1
            ride.copy(id = "IND-${System.currentTimeMillis() % 100000}-$num")
        }
    }

    /**
     * Clean Passenger Name extraction.
     * Verifies strictly that the string represents a person's name:
     * - 1-4 capitalized words
     * - No digits (0-9)
     * - No street or logradouro terms
     * - No UI noise terms
     * - No symbols (★, R$, @, /, #, %, +, =)
     */
    private fun extractPassengerNameClean(lines: List<String>): String? {
        for (line in lines) {
            val lower = line.lowercase(Locale.getDefault())

            // Must NOT match noise keywords
            if (NOISE_KEYWORDS.any { noise -> lower == noise || lower.contains(noise) }) continue

            // Must NOT contain digits (street numbers, prices, ratings)
            if (line.any { it.isDigit() }) continue

            // Must NOT contain street or location indicators
            if (STREET_KEYWORDS.any { street -> lower.contains(street) }) continue

            // Must NOT contain prohibited symbols
            if (line.contains("★") || line.contains("R$") || line.contains("+") || line.contains("@") || line.contains("/") || line.contains("#") || line.contains("%") || line.contains("=")) continue

            val words = line.split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (words.size in 1..4 && line.length in 3..25) {
                // Check if words start with capital letters or standard Brazilian name connectives
                val isCapitalizedName = words.all { w ->
                    w.firstOrNull()?.isUpperCase() == true || 
                    w.lowercase(Locale.getDefault()) in setOf("de", "da", "do", "dos", "das", "e")
                }

                if (isCapitalizedName) {
                    return line
                }
            }
        }
        return null
    }

    private fun extractPassengerPhotoUrl(lines: List<String>): String {
        for (line in lines) {
            if (line.startsWith("http://") || line.startsWith("https://") || line.startsWith("data:image/")) {
                return line
            }
        }
        return ""
    }

    /**
     * Extract clean Pickup and Dropoff addresses with strict validation:
     * Must contain explicit logradouro prefix, street number, neighborhood, or city structure.
     * If an address cannot be validated as a real geographic location, returns Pair("", "").
     */
    private fun extractAddressesStrict(lines: List<String>, fullBlockText: String): Pair<String, String> {
        val validCandidates = mutableListOf<String>()

        for (line in lines) {
            val lower = line.lowercase(Locale.getDefault())

            // Ignore noise keywords
            if (NOISE_KEYWORDS.any { noise -> lower == noise || lower.contains(noise) }) continue

            if (isValidAddressLine(line)) {
                val cleaned = cleanAddressString(line)
                if (cleaned.length >= 5 && !validCandidates.contains(cleaned)) {
                    validCandidates.add(cleaned)
                }
            }
        }

        // Prioritize candidates that start with an explicit logradouro prefix
        validCandidates.sortByDescending { addr ->
            val lower = addr.lowercase(Locale.getDefault())
            if (LOGRADOURO_PREFIXES.any { lower.startsWith(it) }) 2 else 1
        }

        var pickup = if (validCandidates.isNotEmpty()) validCandidates[0] else ""
        var dropoff = if (validCandidates.size >= 2) validCandidates[1] else ""

        // Check if destination is flexible / choose on map in inDrive
        val lowerBlock = fullBlockText.lowercase(Locale.getDefault())
        if (dropoff.isBlank() && (lowerBlock.contains("escolher no mapa") || lowerBlock.contains("definir no mapa"))) {
            dropoff = "Definir destino no mapa"
        }

        // Strict validation: if either address is missing, invalid, or generic, return empty pair
        if (pickup.isBlank() || dropoff.isBlank()) {
            return Pair("", "")
        }

        return Pair(pickup, dropoff)
    }

    private fun isValidAddressLine(line: String): Boolean {
        val lower = line.lowercase(Locale.getDefault())

        // Cannot contain rating symbol or price
        if (line.contains("★") || line.contains("R$")) return false

        // 1. Check explicit logradouro prefix
        val startsWithLogradouro = LOGRADOURO_PREFIXES.any { prefix ->
            lower.startsWith(prefix) || lower.contains(" $prefix ") || lower.contains(" $prefix.")
        }

        // 2. Check address punctuation and house/building numbers (e.g. ", 120" or "nº 45")
        val hasAddressNumberOrPunctuation = line.contains(",") || line.contains("-") ||
                line.contains("nº", true) || line.contains("n°", true) ||
                Regex(",\\s*[0-9]+").containsMatchIn(line)

        // 3. Check street keywords or landmarks
        val hasStreetOrLandmark = STREET_KEYWORDS.any { street -> lower.contains(street) }

        return (startsWithLogradouro || (hasAddressNumberOrPunctuation && hasStreetOrLandmark) || (hasStreetOrLandmark && line.length in 8..80))
    }

    private fun cleanAddressString(addr: String): String {
        var clean = addr
        // Remove OCR hashtags and codes like #8573311-!#
        clean = clean.replace(Regex("#[0-9A-Za-z\\-!#]+"), "")
        // Remove noise words
        NOISE_KEYWORDS.forEach { noise ->
            clean = clean.replace(Regex("(?i)\\b${Regex.escape(noise)}\\b"), "")
        }
        clean = clean.replace(Regex("\\s+"), " ").trim()
        
        // Remove leading/trailing commas or dashes
        clean = clean.removePrefix(",").removePrefix("-").removeSuffix(",").removeSuffix("-").trim()
        return clean.ifBlank { addr }
    }

    private fun parseSingleInDriver(text: String): InDriverRide? {
        val priceMatch = priceRegex.find(text) ?: return null
        val priceStr = priceMatch.groupValues[1].replace(".", "").replace(",", ".")
        val price = priceStr.toDoubleOrNull() ?: return null
        if (price <= 0.0) return null

        val kms = distanceRegex.findAll(text).mapNotNull {
            it.groupValues[1].replace(",", ".").toDoubleOrNull()
        }.toList()

        val totalKm = if (kms.isNotEmpty()) kms.maxOrNull() ?: 1.0 else 1.0
        val pickupKm = if (kms.size >= 2) kms.minOrNull() ?: 0.5 else 0.5
        if (totalKm <= 0.0) return null

        val timeMin = timeRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: (totalKm * 2.5).toInt().coerceAtLeast(4)

        val rawLines = text.split("|", "\n", "•").map { it.trim() }
            .filter { line ->
                line.isNotBlank() && 
                !line.startsWith("R$") && 
                !line.contains("km", true) && 
                !line.contains("min", true) &&
                !line.matches(Regex("R\\$\\s*[0-9].*"))
            }

        val passengerName = extractPassengerNameClean(rawLines) ?: return null
        val passengerPhoto = extractPassengerPhotoUrl(rawLines)

        val addressLines = rawLines.filter { line ->
            val lower = line.lowercase(Locale.getDefault())
            line != passengerName &&
            NOISE_KEYWORDS.none { noise -> lower == noise || lower.contains(noise) }
        }

        val (pickupAddr, dropoffAddr) = extractAddressesStrict(addressLines, text)
        if (pickupAddr.isBlank() || dropoffAddr.isBlank()) return null

        val perKm = price / totalKm
        val perHour = price / (timeMin / 60.0)

        return InDriverRide(
            id = "IND-${System.currentTimeMillis() % 100000}-1",
            passenger = passengerName,
            passengerPhoto = passengerPhoto,
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


