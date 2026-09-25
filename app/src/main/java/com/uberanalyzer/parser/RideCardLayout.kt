package com.uberanalyzer.parser

/** Pure geometry so card boundaries can be regression-tested without Android. */
object RideCardLayout {
    data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun group(lines: List<Line>): List<List<Int>> {
        val ordered = lines.indices.sortedWith(compareBy<Int> { lines[it].top }.thenBy { lines[it].left })
        val prices = ordered.filter { RideParser.extractRidePrices(lines[it].text).isNotEmpty() }
        val rates = ordered.filter { Regex("(?i)R\\$.*?/\\s*km").containsMatchIn(lines[it].text) }
        val boundaries = (prices.mapIndexed { index, priceIndex ->
            val price = lines[priceIndex]
            val previousBottom = if (index == 0) Int.MIN_VALUE else lines[prices[index - 1]].bottom
            val rate = rates.lastOrNull { lines[it].top > previousBottom && lines[it].top <= price.top &&
                price.top - lines[it].bottom <= (price.bottom - price.top).coerceAtLeast(1) * 4 }
            rate?.let { lines[it].top } ?: (price.top - (price.bottom - price.top).coerceAtLeast(1))
        } + rates.map { lines[it].top }).distinct().sorted()
        return boundaries.mapIndexed { index, top ->
            val bottom = boundaries.getOrElse(index + 1) { Int.MAX_VALUE }
            ordered.filter { lines[it].top >= top && lines[it].top < bottom }
        }
    }
}
