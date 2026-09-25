package com.uberanalyzer.parser

/** Address text stays verbatim and in screen order, including city and house number. */
object RideAddressParser {
    private val street = Regex("(?i)(?<![\\p{L}])(?:rua|r\\.|avenida|av\\.|alameda|praça|praca|estrada|rodovia|travessa|largo|viela|beco|quadra|via)\\s+")
    private val landmark = Regex("(?i)\\b(?:metrô|metro|estação|estacao|terminal|aeroporto|shopping|hospital|universidade|academia|restaurante|parque|praça|praca)\\b")
    private val numbered = Regex("[\\p{L}].*[, -]\\s*\\d{1,5}\\b")
    private val placeholder = Regex("(?i)^(?:definir|escolher|não identificado|nao identificado|não capturado|nao capturado|não especificado|nao especificado|sem destino|destino informado|origem não|destino não|a definir|endereço de|endereco de)")

    fun isAddress(text: String?): Boolean {
        if (text.isNullOrBlank() || placeholder.containsMatchIn(text.trim())) return false
        if (text.contains("R$", true) || text.contains("/km", true) || text.contains('★')) return false
        if (text.count { it == '(' } != text.count { it == ')' }) return false
        return street.containsMatchIn(text) || landmark.containsMatchIn(text) || numbered.containsMatchIn(text)
    }

    fun extract(lines: List<String>): Pair<String, String> {
        val addresses = mutableListOf<String>()
        var pending = ""
        for (raw in lines) {
            val line = raw.trim().replace(Regex("\\s+"), " ")
            if (line.isBlank() || line.contains("R$", true) || line.equals("PIX", true)) continue
            val startsAddress = street.containsMatchIn(line) || landmark.containsMatchIn(line) || numbered.containsMatchIn(line)
            // A new street at the start is another stop, even if the previous OCR line was cut off.
            val newStreet = street.find(line)?.range?.first == 0
            if (pending.isNotEmpty() && !newStreet) {
                pending += " $line"
            } else if (startsAddress) {
                if (pending.isNotEmpty()) addresses.add("") // Do not promote a destination over an unreadable pickup.
                pending = line
            } else continue
            if (isAddress(pending) && !pending.endsWith(",") && !pending.endsWith("-")) {
                if (addresses.lastOrNull() != pending) addresses.add(pending)
                pending = ""
            }
        }
        if (pending.isNotEmpty()) addresses.add("")
        // On a multi-stop trip, the final address is the destination.
        return addresses.getOrElse(0) { "" } to if (addresses.size >= 2) addresses.last() else ""
    }
}
