package com.uberanalyzer.parser

/** Experimental parser. Geometry must be calibrated against real screenshots, never guessed. */
object StrictSpatialRideParser {
    data class Box(val left: Double, val top: Double, val right: Double, val bottom: Double) {
        val width get() = right - left
        val height get() = bottom - top
        val valid get() = listOf(left, top, right, bottom).all { it.isFinite() } && width > 0 && height > 0
        fun contains(other: Box) = valid && other.valid && other.left >= left && other.top >= top &&
            other.right <= right && other.bottom <= bottom
        fun overlaps(other: Box) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
        fun relativeTo(parent: Box) = Box((left - parent.left) / parent.width, (top - parent.top) / parent.height,
            (right - parent.left) / parent.width, (bottom - parent.top) / parent.height)
    }

    data class Line(val text: String, val box: Box?)
    enum class Field { PASSENGER, RATING, PRICE, PICKUP_DISTANCE, PICKUP_ADDRESS, TRIP_DISTANCE, DROPOFF_ADDRESS }
    data class Layout(val regions: Map<Field, Box>) {
        init {
            require(regions.isNotEmpty())
            require(regions.values.all { Box(0.0, 0.0, 1.0, 1.0).contains(it) })
            require(regions.values.toList().let { boxes -> boxes.indices.all { i ->
                (i + 1 until boxes.size).none { j -> boxes[i].overlaps(boxes[j]) }
            } }) { "Field regions must not overlap" }
        }
    }

    /** Bounds are for the WHOLE card, including portions outside the viewport. */
    data class Card(val bounds: Box)
    data class Evidence(val text: String, val sourceLine: Int, val box: Box)
    data class Ride(val bounds: Box, val fields: Map<Field, Evidence>) {
        val price get() = fields[Field.PRICE]?.text?.let { money.matchEntire(it)?.groupValues?.get(1) }?.let(::decimal)
        val pickupDistanceKm get() = distanceValue(fields[Field.PICKUP_DISTANCE]?.text)
        val tripDistanceKm get() = distanceValue(fields[Field.TRIP_DISTANCE]?.text)
    }
    data class Result(val rides: List<Ride>, val rejectedCards: Int, val unusedLines: Int)

    private val money = Regex("R\\$\\s*([0-9]+(?:\\.[0-9]{3})*,[0-9]{2})", RegexOption.IGNORE_CASE)
    private val distance = Regex("([0-9]+(?:[,.][0-9]+)?)\\s*(km|m)", RegexOption.IGNORE_CASE)
    private val rating = Regex("[0-5][,.][0-9]{1,2}")
    private val name = Regex("\\p{L}+(?:[ '\u2019-]\\p{L}+){0,5}")
    private val address = Regex("(?:Rua|R\\.|Avenida|Av\\.|Travessa|Tv\\.|Alameda|Praça|Praca|Estrada|Rodovia)\\s+.*\\p{L}.*", RegexOption.IGNORE_CASE)
    private val noise = setOf("aceitar", "recusar", "oferecer", "dinheiro", "pix", "cartão", "cartao", "passageiro", "origem", "destino", "indrive", "offline", "online")

    private fun decimal(text: String) = text.replace(".", "").replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
    private fun distanceValue(text: String?): Double? {
        val match = text?.let { distance.matchEntire(it) } ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        return if (match.groupValues[2].equals("m", true)) value / 1000.0 else value
    }

    private fun accepts(field: Field, text: String): Boolean = when (field) {
        Field.PRICE -> money.matches(text) && (money.matchEntire(text)?.groupValues?.get(1)?.let(::decimal) ?: 0.0) > 0
        Field.PICKUP_DISTANCE, Field.TRIP_DISTANCE -> distanceValue(text) != null
        Field.RATING -> rating.matches(text) && (text.replace(',', '.').toDoubleOrNull() ?: -1.0) in 0.0..5.0
        Field.PASSENGER -> name.matches(text) && text.lowercase(java.util.Locale.ROOT) !in noise && !address.matches(text)
        Field.PICKUP_ADDRESS, Field.DROPOFF_ADDRESS -> address.matches(text) && !text.contains("R$", true)
    }

    /** No text fallback, defaults, cross-frame cache, address completion or inferred distances. */
    fun parse(lines: List<Line>, cards: List<Card>, layout: Layout, viewport: Box): Result {
        require(viewport.valid)
        val accepted = cards.filterIndexed { index, card ->
            viewport.contains(card.bounds) && cards.indices.none { other ->
                other != index && cards[other].bounds.valid && card.bounds.overlaps(cards[other].bounds)
            }
        }.sortedWith(compareBy<Card> { it.bounds.top }.thenBy { it.bounds.left })
        val used = mutableSetOf<Int>()
        val rides = accepted.map { card ->
            val fields = linkedMapOf<Field, Evidence>()
            for ((field, region) in layout.regions) {
                // Count ALL intersecting lines before validating text. Ambiguity never picks a winner.
                val candidates = lines.withIndex().filter { (_, line) ->
                    line.box?.let { it.valid && region.overlaps(it.relativeTo(card.bounds)) } == true
                }
                val candidate = candidates.singleOrNull() ?: continue
                val box = candidate.value.box ?: continue
                val text = candidate.value.text.trim()
                if (card.bounds.contains(box) && region.contains(box.relativeTo(card.bounds)) && accepts(field, text)) {
                    fields[field] = Evidence(text, candidate.index, box)
                    used.add(candidate.index)
                }
            }
            Ride(card.bounds, fields)
        }
        return Result(rides, cards.size - accepted.size, lines.size - used.size)
    }

    /**
     * Opt-in, fixed-layout detector for a calibrated inDrive list viewport.
     * A standalone price anchors each card. Height and price center come from calibration,
     * not from the last OCR line or the next price (which could belong to another card).
     * Variable-height/unrecognized layouts require explicit card bounds instead.
     */
    data class Profile(val layout: Layout, val cardHeightToWidth: Double) {
        init {
            require(cardHeightToWidth.isFinite() && cardHeightToWidth > 0)
            require(layout.regions.containsKey(Field.PRICE))
        }
    }

    fun detectCards(lines: List<Line>, listViewport: Box, profile: Profile): List<Card> {
        require(listViewport.valid)
        val priceRegion = profile.layout.regions.getValue(Field.PRICE)
        val height = listViewport.width * profile.cardHeightToWidth
        return lines.mapNotNull { line ->
            val box = line.box ?: return@mapNotNull null
            if (!listViewport.contains(box) || !accepts(Field.PRICE, line.text.trim())) return@mapNotNull null
            val x = box.relativeTo(listViewport)
            if (x.left < priceRegion.left || x.right > priceRegion.right) return@mapNotNull null
            val top = (box.top + box.bottom) / 2 - height * (priceRegion.top + priceRegion.bottom) / 2
            Card(Box(listViewport.left, top, listViewport.right, top + height))
        }
    }
}
