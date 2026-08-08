package com.uberanalyzer.parser

import com.uberanalyzer.model.InDriverRide
import com.uberanalyzer.model.RideCategory
import com.uberanalyzer.model.RideData
import java.util.Locale

object RideParser {
    private val priceRegex = Regex("R\\$\\s*([0-9]{1,3}(?:[.][0-9]{3})*[,.][0-9]{2}|[0-9]+)(?!\\s*/\\s*km)")
    private val perKmPriceRegex = Regex("R\\$\\s*([0-9]+(?:[,.][0-9]+)?)\\s*/\\s*km", RegexOption.IGNORE_CASE)
    private val distanceRegex = Regex("~?\\s*([0-9]+[,.][0-9]+|[0-9]+)\\s*(km|m)\\b", RegexOption.IGNORE_CASE)
    private val timeRegex = Regex("([0-9]+)\\s*(min|minutos)", RegexOption.IGNORE_CASE)
    private val ratingRegex = Regex("([3-5][.,][0-9]{1,2})")

    // Forbidden UI buttons, badges, status labels, or system text
    private val NOISE_KEYWORDS = setOf(
        "reclamar", "preço justo", "preco justo", "bônus", "bonus", "+1 bônus", "+2 bônus", "+3 bônus",
        "escolher no mapa", "definir no mapa", "ar-condicionado", "ar condicionado", "bagagem", "pet",
        "viagem", "corrida", "oferecer", "aceitar", "recusar", "sugerir", "negociar", "sugerir seu preço",
        "dinheiro", "pix", "cartão", "cartao", "troco", "desconto", "oferta", "promocao", "promoção",
        "opções", "opcoes", "detalhes", "cancelar", "voltar", "fechar", "indriver", "indrive", "uber", "99",
        "solicitação", "solicitacao", "embarque", "desembarque", "origem", "destino", "passageiro",
        "motorista", "avaliação", "avaliar", "classificação", "pontuação", "topo", "fila", "leitor ativo",
        "ver detalhes", "toque para ver", "chamar", "suporte", "ajuda", "preço", "preco", "justo",
        "solicitações", "solicitacoes", "aguardando", "lado a lado", "analisador", "analyzer",
        "todos os aplicativos", "todos os", "aplicativos", "recentes", "atalho de acessibilidade", "atalho",
        "acessibilidade", "hierarquia de nós", "hierarquia de nos", "hierarquia", "elementos detectados",
        "copiar hierarquia", "flutuar na tela", "atualizar fila", "ocultar viagem", "auto-ocultar",
        "configurações", "configuracoes", "viewgroup", "cardview", "item de lista", "textview", "button",
        "ação de resposta", "acao de resposta", "lado a lado com indrive", "score da corrida", "valor da corrida",
        "distância total", "distancia total", "pedidos de viagem", "demanda", "desempenho", "offline", "conquista",
        "waze", "notificação", "notificacao", "notificações", "notificacoes", "painéis edge", "paineis edge",
        "edge", "painéis", "paineis", "início", "inicio", "são paulo", "sao paulo", "vitoria", "vitória",
        "spotify", "wi-fi", "wifi", "sinal wi-fi", "sinal wifi", "sinal", "bluetooth", "bateria", "relógio", "relogio",
        "mídia", "midia", "player", "tocador", "volume", "cheio", "sinal wi-fi cheio", "modo avião", "modo aviao",
        "sáb.", "sab.", "dom.", "seg.", "ter.", "qua.", "qui.", "sex.", "sábado", "sabado", "domingo",
        "de ago", "de set", "de out", "de nov", "de dez", "de jan", "de fev", "de mar", "de abr", "de mai", "de jun", "de jul"
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
     * Parse multiple rides visible on the inDriver list screen using spatial bounding boxes
     * extracted from Google ML Kit OCR and regional color bounds.
     */
    fun parseInDriverSpatialLines(
        lines: List<com.uberanalyzer.ocr.MlKitScreenOcrEngine.OcrLine>,
        fullBitmap: android.graphics.Bitmap? = null
    ): List<InDriverRide> {
        if (lines.isEmpty()) return emptyList()

        val screenWidth = fullBitmap?.width ?: 1080

        // Filter out text from left-side system dock or right-side overlay windows
        val validLines = lines.filter { line ->
            val text = line.text.trim()
            val lower = text.lowercase(Locale.getDefault())
            val box = line.boundingBox

            // Filter out text from left-side system dock, launcher, or Waze widgets (X < 22% screen width)
            val isLeftSideSystemDock = box != null && box.right < (screenWidth * 0.22)

            // Filter out right-side overlay windows or system debug titles
            val isRightSideOverlay = box != null && box.left > (screenWidth * 0.52) &&
                    (lower.contains("hierarquia") || lower.contains("elementos") || 
                     lower.contains("copiar") || lower.contains("flutuar") || 
                     lower.contains("recentes") || lower.contains("atalho") || 
                     lower.contains("aplicativos") || lower.contains("viewgroup") ||
                     lower.contains("cardview") || lower.contains("item de lista"))

            val isNoise = NOISE_KEYWORDS.any { noise -> lower == noise || lower.contains(noise) }

            text.isNotBlank() && !isLeftSideSystemDock && !isRightSideOverlay && !isNoise
        }

        if (validLines.isEmpty()) return emptyList()

        // Cluster lines by spatial bounding box / card boundaries
        val cardClusters = mutableListOf<MutableList<com.uberanalyzer.ocr.MlKitScreenOcrEngine.OcrLine>>()
        var currentCluster = mutableListOf<com.uberanalyzer.ocr.MlKitScreenOcrEngine.OcrLine>()

        for (line in validLines) {
            val text = line.text.trim()

            val isPriceOrRate = text.contains("R$", ignoreCase = true) || text.contains("/km", ignoreCase = true)

            val clusterHasPrice = currentCluster.any { it.text.contains("R$", ignoreCase = true) }
            val clusterHasAddress = currentCluster.any { isValidAddressLine(it.text) }

            val prevBox = currentCluster.lastOrNull()?.boundingBox
            val currBox = line.boundingBox
            val verticalGap = if (prevBox != null && currBox != null) (currBox.top - prevBox.bottom) else 0

            val isNameCandidate = extractPassengerNameClean(listOf(text)) != null

            // Split into new card cluster if this line belongs to a new ride card
            val shouldSplit = (clusterHasPrice && clusterHasAddress && (isPriceOrRate || isNameCandidate)) || 
                              (verticalGap > 100 && currentCluster.isNotEmpty())

            if (shouldSplit) {
                cardClusters.add(currentCluster)
                currentCluster = mutableListOf()
            }

            currentCluster.add(line)
        }

        if (currentCluster.isNotEmpty()) {
            cardClusters.add(currentCluster)
        }

        val rides = mutableListOf<InDriverRide>()
        var index = 1

        for (cluster in cardClusters) {
            val clusterTexts = cluster.map { it.text.trim() }.filter { it.isNotBlank() }
            val fullBlockText = clusterTexts.joinToString(" | ")

            // Calculate card boundary box for regional cropping
            val lefts = cluster.mapNotNull { it.boundingBox?.left }
            val tops = cluster.mapNotNull { it.boundingBox?.top }
            val rights = cluster.mapNotNull { it.boundingBox?.right }
            val bottoms = cluster.mapNotNull { it.boundingBox?.bottom }

            val cardLeft = if (lefts.isNotEmpty()) lefts.minOrNull()!! else 0
            val cardTop = if (tops.isNotEmpty()) tops.minOrNull()!! else 0
            val cardRight = if (rights.isNotEmpty()) rights.maxOrNull()!! else screenWidth
            val cardBottom = if (bottoms.isNotEmpty()) bottoms.maxOrNull()!! else (cardTop + 200)

            val cardBox = android.graphics.Rect(cardLeft, cardTop, cardRight, cardBottom)

            // Reject card clusters originating from the far-left system launcher area or containing launcher noise
            if (cardRight < (screenWidth * 0.22)) continue

            val lowerBlock = fullBlockText.lowercase(Locale.getDefault())
            if (lowerBlock.contains("waze") || 
                lowerBlock.contains("spotify") || 
                lowerBlock.contains("sinal") || 
                lowerBlock.contains("wi-fi") || 
                lowerBlock.contains("wifi") || 
                lowerBlock.contains("bluetooth") || 
                lowerBlock.contains("bateria") || 
                lowerBlock.contains("sáb") || 
                lowerBlock.contains("sab.") || 
                lowerBlock.contains("dom.") || 
                lowerBlock.contains("de ago") || 
                lowerBlock.contains("de set") || 
                lowerBlock.contains("notifica") || 
                lowerBlock.contains("painéis") || 
                lowerBlock.contains("paineis") || 
                lowerBlock.contains("edge")) {
                continue
            }

            // RED REGION: Dynamic Crop of Passenger Avatar Photo
            val croppedAvatarBase64 = cropAvatarBase64(fullBitmap, cardBox)
            val passengerPhoto = if (croppedAvatarBase64.isNotBlank()) croppedAvatarBase64 else extractPassengerPhotoUrl(clusterTexts)

            // BLUE REGION: Main price (R$ XX)
            val priceMatch = priceRegex.find(fullBlockText) ?: continue
            val priceStr = priceMatch.groupValues[1].replace(".", "").replace(",", ".")
            val price = priceStr.toDoubleOrNull() ?: continue
            if (price <= 0.0) continue

            // GREEN REGION: Rate per Km badge (R$ X,X/km)
            val perKmMatch = perKmPriceRegex.find(fullBlockText)
            val explicitPerKm = perKmMatch?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()

            // PURPLE REGION: Payment Method
            val paymentMethod = when {
                fullBlockText.contains("maquininha", ignoreCase = true) -> "Maquininha de cartão"
                fullBlockText.contains("cartão", ignoreCase = true) || fullBlockText.contains("cartao", ignoreCase = true) -> "Cartão de crédito/débito"
                fullBlockText.contains("pix", ignoreCase = true) -> "Pix"
                fullBlockText.contains("dinheiro", ignoreCase = true) -> "Dinheiro"
                else -> ""
            }

            // PINK REGION: Distances (displacement to passenger pickup vs trip)
            val distanceMatches = distanceRegex.findAll(fullBlockText).toList()
            val parsedDistances = distanceMatches.mapNotNull { m ->
                val valStr = m.groupValues[1].replace(",", ".")
                val num = valStr.toDoubleOrNull() ?: return@mapNotNull null
                val unit = m.groupValues[2].lowercase(Locale.getDefault())
                if (unit == "m") num / 1000.0 else num
            }

            val pickupKm = if (parsedDistances.isNotEmpty()) parsedDistances[0] else 0.5
            val tripKm = if (parsedDistances.size >= 2) parsedDistances[1] else (if (parsedDistances.size == 1) parsedDistances[0] else 1.0)
            val totalKm = if (parsedDistances.size >= 2) pickupKm + tripKm else (if (parsedDistances.isNotEmpty()) parsedDistances[0] else 1.0)

            // Duration
            val mins = timeRegex.findAll(fullBlockText).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            val timeMin = if (mins.isNotEmpty()) mins[0] else (totalKm * 2.5).toInt().coerceAtLeast(3)

            // Rating
            val ratingMatch = ratingRegex.find(fullBlockText)
            val rating = ratingMatch?.groupValues?.get(1)?.replace(",", ".") ?: "4.9"

            // YELLOW REGION: Passenger Name (canto superior esquerdo do card)
            val passengerName = extractPassengerNameFromCluster(cluster) ?: "Passageiro inDrive"

            // ORANGE REGION: Addresses (Origem e Destino)
            val addressLines = clusterTexts.filter { line ->
                val lower = line.lowercase(Locale.getDefault())
                line != passengerName &&
                !lower.contains("r$") &&
                !lower.contains("/km") &&
                NOISE_KEYWORDS.none { noise -> lower == noise || lower.contains(noise) }
            }

            val (pickupAddr, dropoffAddr) = extractAddressesStrict(addressLines, fullBlockText)
            if (pickupAddr.isBlank()) continue

            val perKm = explicitPerKm ?: (if (totalKm > 0) price / totalKm else 0.0)
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
                    paymentMethod = paymentMethod,
                    rawText = fullBlockText
                )
            )
            index++
        }

        // Deduplicate and limit to up to 10
        val uniqueRides = rides.distinctBy {
            "${it.price}_${(it.totalDistanceKm * 10).toInt()}_${it.pickupAddress.take(15)}"
        }

        return uniqueRides.take(10).mapIndexed { i, ride ->
            val num = i + 1
            ride.copy(id = "IND-${System.currentTimeMillis() % 100000}-$num")
        }
    }

    /**
     * Parse multiple rides visible on the inDriver waiting list screen
     */
    fun parseInDriverList(screenText: String): List<InDriverRide> {
        val rides = mutableListOf<InDriverRide>()
        val lowerText = screenText.lowercase(Locale.getDefault())

        // Splitting strategy: split by per-km rate badge (R$ X/km) or main price / action buttons
        val blocks = if (lowerText.contains("/km")) {
            screenText.split(Regex("(?=R\\$\\s*[0-9]+(?:[,.][0-9]+)?\\s*/\\s*km|\\bOferecer\\b|\\bAceitar\\b|\\bRecusar\\b)"))
        } else {
            screenText.split(Regex("(?=R\\$\\s*[0-9]+(?!\\s*/\\s*km)|\\bOferecer\\b|\\bAceitar\\b|\\bRecusar\\b)"))
        }

        var index = 1
        for (block in blocks) {
            val trimmed = block.trim()
            if (trimmed.length < 8) continue

            val lowerBlock = trimmed.lowercase(Locale.getDefault())
            if (lowerBlock.contains("spotify") || 
                lowerBlock.contains("waze") || 
                lowerBlock.contains("sinal") || 
                lowerBlock.contains("wi-fi") || 
                lowerBlock.contains("wifi") || 
                lowerBlock.contains("bluetooth") || 
                lowerBlock.contains("bateria") || 
                lowerBlock.contains("sáb") || 
                lowerBlock.contains("sab.") || 
                lowerBlock.contains("dom.") || 
                lowerBlock.contains("de ago") || 
                lowerBlock.contains("de set") || 
                lowerBlock.contains("notifica") || 
                lowerBlock.contains("painéis") || 
                lowerBlock.contains("paineis") || 
                lowerBlock.contains("edge")) {
                continue
            }

            // Main ride price (ignoring rate badge R$ X/km)
            val priceMatch = priceRegex.find(trimmed) ?: continue
            val priceStr = priceMatch.groupValues[1].replace(".", "").replace(",", ".")
            val price = priceStr.toDoubleOrNull() ?: continue
            if (price <= 0.0) continue

            // Distances (pickup vs trip)
            val distanceMatches = distanceRegex.findAll(trimmed).toList()
            val parsedDistances = distanceMatches.mapNotNull { m ->
                val valStr = m.groupValues[1].replace(",", ".")
                val num = valStr.toDoubleOrNull() ?: return@mapNotNull null
                val unit = m.groupValues[2].lowercase(Locale.getDefault())
                if (unit == "m") num / 1000.0 else num
            }

            val pickupKm = if (parsedDistances.isNotEmpty()) parsedDistances[0] else 0.5
            val tripKm = if (parsedDistances.size >= 2) parsedDistances[1] else (if (parsedDistances.size == 1) parsedDistances[0] else 1.0)
            val totalKm = if (parsedDistances.size >= 2) pickupKm + tripKm else (if (parsedDistances.isNotEmpty()) parsedDistances[0] else 1.0)

            // Duration
            val mins = timeRegex.findAll(trimmed).mapNotNull {
                it.groupValues[1].toIntOrNull()
            }.toList()
            val timeMin = if (mins.isNotEmpty()) mins[0] else (totalKm * 2.5).toInt().coerceAtLeast(3)

            // Rating
            val ratingMatch = ratingRegex.find(trimmed)
            val rating = ratingMatch?.groupValues?.get(1)?.replace(",", ".") ?: "4.9"

            // Extract text lines, filtering out R$ prices and noise
            val rawLines = trimmed.split("|", "\n", "•").map { it.trim() }
                .filter { line ->
                    line.isNotBlank() && 
                    !line.contains("km", true) && 
                    !line.contains("min", true)
                }

            // 1. Extract Passenger Name
            val passengerName = extractPassengerNameClean(rawLines) ?: "Passageiro inDrive"

            // 2. Extract Passenger Photo URL if present
            val passengerPhoto = extractPassengerPhotoUrl(rawLines)

            // 3. Filter remaining lines for address extraction
            val addressLines = rawLines.filter { line ->
                val lower = line.lowercase(Locale.getDefault())
                line != passengerName &&
                NOISE_KEYWORDS.none { noise -> lower == noise || lower.contains(noise) }
            }

            // 4. Extract Origin and Destination addresses
            val (pickupAddr, dropoffAddr) = extractAddressesStrict(addressLines, trimmed)
            // Require at least a valid pickup address
            if (pickupAddr.isBlank()) continue

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

        // Fallback: If block parsing yielded no rides but text contains R$, try single card or fallback split
        if (rides.isEmpty() && lowerText.contains("r$")) {
            val single = parseSingleInDriver(screenText)
            if (single != null) rides.add(single)
        }

        // Deduplicate identical rides and limit to up to 10 rides
        val uniqueRides = rides.distinctBy {
            "${it.price}_${(it.totalDistanceKm * 10).toInt()}_${it.pickupAddress.take(15)}"
        }

        val topRides = uniqueRides.take(10)
        return topRides.mapIndexed { i, ride ->
            val num = i + 1
            ride.copy(id = "IND-${System.currentTimeMillis() % 100000}-$num")
        }
    }

    private fun extractPassengerNameFromCluster(cluster: List<com.uberanalyzer.ocr.MlKitScreenOcrEngine.OcrLine>): String? {
        val sorted = cluster.sortedWith(compareBy<com.uberanalyzer.ocr.MlKitScreenOcrEngine.OcrLine> { it.boundingBox?.top ?: 0 }.thenBy { it.boundingBox?.left ?: 0 })
        for (line in sorted) {
            val text = line.text.trim()
            val cleaned = cleanNameCandidate(text) ?: continue
            val lower = cleaned.lowercase(Locale.getDefault())

            // Block explicit system labels, overlay titles, media apps, and debug strings
            if (lower.contains("todos os") || lower.contains("aplicativos") || 
                lower.contains("recentes") || lower.contains("atalho") || 
                lower.contains("acessibilidade") || lower.contains("hierarquia") ||
                lower.contains("elemento") || lower.contains("viewgroup") || lower.contains("cardview") ||
                lower.contains("spotify") || lower.contains("waze") || lower.contains("system") ||
                lower.contains("wi-fi") || lower.contains("wifi") || lower.contains("bateria") ||
                lower.contains("sinal") || lower.contains("notifica") || lower.contains("mídia") ||
                lower.contains("midia") || lower.contains("player") || lower.contains("music")) continue

            if (NOISE_KEYWORDS.any { noise -> lower == noise || lower.contains(noise) }) continue
            if (cleaned.any { it.isDigit() }) continue
            if (STREET_KEYWORDS.any { street -> lower.contains(street) }) continue
            if (cleaned.contains("★") || cleaned.contains("R$") || cleaned.contains("+") || 
                cleaned.contains("@") || cleaned.contains("/") || cleaned.contains("#") || 
                cleaned.contains("%") || cleaned.contains("=")) continue

            val words = cleaned.split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (words.size in 1..4 && cleaned.length in 2..30) {
                val isAlpha = words.all { w -> w.all { c -> c.isLetter() || c == '-' || c == '\'' } }
                if (isAlpha) {
                    return cleaned
                }
            }
        }
        return null
    }

    private fun cropAvatarBase64(fullBitmap: android.graphics.Bitmap?, cardBox: android.graphics.Rect): String {
        if (fullBitmap == null || fullBitmap.isRecycled) return ""
        try {
            val left = cardBox.left.coerceIn(0, fullBitmap.width - 1)
            val top = cardBox.top.coerceIn(0, fullBitmap.height - 1)
            
            val cardHeight = cardBox.height().coerceAtLeast(100)
            val size = (cardHeight * 0.40).toInt().coerceIn(40, (fullBitmap.width - left).coerceAtMost(fullBitmap.height - top))
            if (size <= 10) return ""

            val avatarBitmap = android.graphics.Bitmap.createBitmap(fullBitmap, left, top, size, size)
            val outputStream = java.io.ByteArrayOutputStream()
            avatarBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outputStream)
            val bytes = outputStream.toByteArray()
            try { avatarBitmap.recycle() } catch (_: Exception) {}
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * Clean Passenger Name extraction.
     */
    private fun extractPassengerNameClean(lines: List<String>): String? {
        for (line in lines) {
            val cleaned = cleanNameCandidate(line) ?: continue
            val lower = cleaned.lowercase(Locale.getDefault())

            // Must NOT match noise keywords
            if (NOISE_KEYWORDS.any { noise -> lower == noise || lower.contains(noise) }) continue

            // Must NOT contain digits
            if (cleaned.any { it.isDigit() }) continue

            // Must NOT contain street or location indicators
            if (STREET_KEYWORDS.any { street -> lower.contains(street) }) continue

            // Must NOT contain prohibited symbols
            if (cleaned.contains("★") || cleaned.contains("R$") || cleaned.contains("+") || 
                cleaned.contains("@") || cleaned.contains("/") || cleaned.contains("#") || 
                cleaned.contains("%") || cleaned.contains("=")) continue

            val words = cleaned.split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (words.size in 1..4 && cleaned.length in 2..30) {
                val isAlpha = words.all { w -> w.all { c -> c.isLetter() || c == '-' || c == '\'' } }
                if (isAlpha) {
                    return cleaned
                }
            }
        }
        return null
    }

    private fun cleanNameCandidate(raw: String): String? {
        var text = raw.trim()
        if (text.isBlank()) return null

        // Remove rating numbers like ★ 4.96 or 4.96 ★
        text = text.replace(Regex("★\\s*[0-9]+[.,][0-9]+|[0-9]+[.,][0-9]+\\s*★?"), "")
        // Remove counts in parentheses like (186)
        text = text.replace(Regex("\\([0-9]+\\)"), "")
        // Remove time tag like 55 seg. or 1 min.
        text = text.replace(Regex("[0-9]+\\s*(?:seg|min|minutos)\\.?"), "")
        // Remove distance tag like ~654 m or ~1,1 km
        text = text.replace(Regex("~?\\s*[0-9]+(?:[,.][0-9]+)?\\s*(?:m|km)\\.?"), "")
        // Remove currency tag like R$ 29
        text = text.replace(Regex("R\\$\\s*[0-9]+(?:[,.][0-9]+)?(?:/km)?"), "")

        NOISE_KEYWORDS.forEach { noise ->
            text = text.replace(Regex("(?i)\\b${Regex.escape(noise)}\\b"), "")
        }

        text = text.replace(Regex("\\s+"), " ").trim()
        return if (text.isBlank()) null else text
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
     * Extract clean Pickup and Dropoff addresses with strict validation
     */
    private fun extractAddressesStrict(lines: List<String>, fullBlockText: String): Pair<String, String> {
        val validCandidates = mutableListOf<String>()

        for (line in lines) {
            val lower = line.lowercase(Locale.getDefault())

            if (NOISE_KEYWORDS.any { noise -> lower == noise || lower.contains(noise) }) continue

            if (isValidAddressLine(line)) {
                val cleaned = cleanAddressString(line)
                if (cleaned.length >= 3 && !validCandidates.contains(cleaned)) {
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

        val lowerBlock = fullBlockText.lowercase(Locale.getDefault())
        if (dropoff.isBlank() && (lowerBlock.contains("escolher no mapa") || lowerBlock.contains("definir no mapa"))) {
            dropoff = "Definir destino no mapa"
        }

        if (pickup.isBlank() && lines.isNotEmpty()) {
            val fallbackLine = lines.firstOrNull { l -> 
                l.length in 5..80 && !l.startsWith("R$") && isValidAddressLine(l)
            }
            if (fallbackLine != null) {
                pickup = cleanAddressString(fallbackLine)
            }
        }

        if (pickup.isNotBlank() && dropoff.isBlank()) {
            dropoff = "Destino informado no app"
        }

        if (pickup.isBlank()) {
            pickup = "Origem no inDrive"
        }

        return Pair(pickup, dropoff)
    }

    private fun isValidAddressLine(line: String): Boolean {
        val lower = line.lowercase(Locale.getDefault())

        if (line.contains("★") || line.startsWith("R$") || line.contains("Pix", true)) return false
        if (lower.contains("spotify") || lower.contains("waze") || lower.contains("sinal") ||
            lower.contains("wi-fi") || lower.contains("wifi") || lower.contains("bluetooth") ||
            lower.contains("bateria") || lower.contains("notifica") || lower.contains("cheio") ||
            lower.contains("sáb") || lower.contains("dom") || lower.contains("seg") ||
            lower.contains("de ago") || lower.contains("de set") || lower.contains("de out") ||
            lower.contains("de nov") || lower.contains("de dez")) return false

        if (NOISE_KEYWORDS.any { noise -> lower == noise || lower.contains(noise) }) return false

        val startsWithLogradouro = LOGRADOURO_PREFIXES.any { prefix ->
            lower.startsWith(prefix) || lower.contains(" $prefix ") || lower.contains(" $prefix.")
        }

        val hasAddressNumberOrPunctuation = line.contains(",") || line.contains("-") ||
                line.contains("nº", true) || line.contains("n°", true) ||
                Regex("\\b[0-9]{1,5}\\b").containsMatchIn(line)

        val hasStreetOrLandmark = STREET_KEYWORDS.any { street -> lower.contains(street) }

        return (startsWithLogradouro || hasAddressNumberOrPunctuation || hasStreetOrLandmark)
    }

    private fun cleanAddressString(addr: String): String {
        var clean = addr
        clean = clean.replace(Regex("#[0-9A-Za-z\\-!#]+"), "")
        NOISE_KEYWORDS.forEach { noise ->
            clean = clean.replace(Regex("(?i)\\b${Regex.escape(noise)}\\b"), "")
        }
        clean = clean.replace(Regex("\\s+"), " ").trim()
        clean = clean.removePrefix(",").removePrefix("-").removeSuffix(",").removeSuffix("-").trim()
        return clean.ifBlank { addr }
    }

    private fun parseSingleInDriver(text: String): InDriverRide? {
        val priceMatch = priceRegex.find(text) ?: return null
        val priceStr = priceMatch.groupValues[1].replace(".", "").replace(",", ".")
        val price = priceStr.toDoubleOrNull() ?: return null
        if (price <= 0.0) return null

        val distanceMatches = distanceRegex.findAll(text).toList()
        val parsedDistances = distanceMatches.mapNotNull { m ->
            val valStr = m.groupValues[1].replace(",", ".")
            val num = valStr.toDoubleOrNull() ?: return@mapNotNull null
            val unit = m.groupValues[2].lowercase(Locale.getDefault())
            if (unit == "m") num / 1000.0 else num
        }

        val totalKm = if (parsedDistances.isNotEmpty()) parsedDistances.maxOrNull() ?: 1.0 else 1.0
        val pickupKm = if (parsedDistances.size >= 2) parsedDistances.minOrNull() ?: 0.5 else 0.5

        val timeMin = timeRegex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: (totalKm * 2.5).toInt().coerceAtLeast(4)

        val rawLines = text.split("|", "\n", "•").map { it.trim() }
            .filter { line -> line.isNotBlank() }

        val passengerName = extractPassengerNameClean(rawLines) ?: "Passageiro inDriver"
        val passengerPhoto = extractPassengerPhotoUrl(rawLines)

        val addressLines = rawLines.filter { line ->
            val lower = line.lowercase(Locale.getDefault())
            line != passengerName &&
            NOISE_KEYWORDS.none { noise -> lower == noise || lower.contains(noise) }
        }

        val (pickupAddr, dropoffAddr) = extractAddressesStrict(addressLines, text)
        if (pickupAddr.isBlank()) return null

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



