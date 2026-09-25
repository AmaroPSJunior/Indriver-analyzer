package com.uberanalyzer.parser

import com.uberanalyzer.parser.StrictSpatialRideParser.Box
import com.uberanalyzer.parser.StrictSpatialRideParser.Card
import com.uberanalyzer.parser.StrictSpatialRideParser.Field
import com.uberanalyzer.parser.StrictSpatialRideParser.Layout
import com.uberanalyzer.parser.StrictSpatialRideParser.Line
import com.uberanalyzer.parser.StrictSpatialRideParser.Profile
import org.junit.Assert.*
import org.junit.Test

class StrictSpatialRideParserTest {
    // SYNTHETIC fixture, not a calibrated inDrive layout.
    private val layout = Layout(mapOf(
        Field.PASSENGER to Box(.05, .02, .55, .14),
        Field.PRICE to Box(.65, .02, .98, .14),
        Field.RATING to Box(.05, .16, .3, .24),
        Field.PICKUP_DISTANCE to Box(.05, .28, .3, .36),
        Field.PICKUP_ADDRESS to Box(.05, .4, .95, .5),
        Field.TRIP_DISTANCE to Box(.05, .56, .3, .64),
        Field.DROPOFF_ADDRESS to Box(.05, .7, .95, .8)
    ))
    private val viewport = Box(0.0, 0.0, 1000.0, 2000.0)
    private fun card(top: Double) = Card(Box(0.0, top, 1000.0, top + 500))
    private fun line(text: String, field: Field, top: Double): Line {
        val region = layout.regions.getValue(field)
        return Line(text, Box(region.left * 1000 + 5, top + region.top * 500 + 5,
            region.right * 1000 - 5, top + region.bottom * 500 - 5))
    }
    private fun lines(top: Double, passenger: String = "Ana", price: String = "R$ 20,00") = listOf(
        line(passenger, Field.PASSENGER, top), line(price, Field.PRICE, top),
        line("4,9", Field.RATING, top), line("250 m", Field.PICKUP_DISTANCE, top),
        line("Rua Alfa, 10", Field.PICKUP_ADDRESS, top), line("2,3 km", Field.TRIP_DISTANCE, top),
        line("Avenida Beta, 20", Field.DROPOFF_ADDRESS, top)
    )
    private fun parse(input: List<Line>, cards: List<Card> = listOf(card(100.0))) =
        StrictSpatialRideParser.parse(input, cards, layout, viewport)

    @Test fun isolatesAndOrdersThreeCardsEvenWhenOcrIsShuffled() {
        val input = (lines(100.0) + lines(700.0, "Bruno", "R$ 30,00") + lines(1300.0, "Carla", "R$ 40,00")).reversed()
        val result = parse(input, listOf(card(1300.0), card(100.0), card(700.0)))
        assertEquals(listOf("Ana", "Bruno", "Carla"), result.rides.map { it.fields[Field.PASSENGER]?.text })
        assertEquals(listOf(20.0, 30.0, 40.0), result.rides.map { it.price })
        val sources = result.rides.flatMap { it.fields.values.map { e -> e.sourceLine } }
        assertEquals(21, sources.size)
        assertEquals(sources.size, sources.toSet().size)
        result.rides.forEach { ride -> ride.fields.values.forEach { assertTrue(ride.bounds.contains(it.box)) } }
    }
    @Test fun missingFieldsRemainAbsentAndDistancesAreNotEstimated() {
        val ride = parse(listOf(line("R$ 20,00", Field.PRICE, 100.0))).rides.single()
        assertEquals(setOf(Field.PRICE), ride.fields.keys)
        assertNull(ride.pickupDistanceKm)
        assertNull(ride.tripDistanceKm)
    }
    @Test fun convertsOnlyExplicitDistanceUnits() {
        val ride = parse(lines(100.0)).rides.single()
        assertEquals(.25, ride.pickupDistanceKm!!, .00001)
        assertEquals(2.3, ride.tripDistanceKm!!, .00001)
    }
    @Test fun incorrectContentNeverMovesToAnotherField() {
        val result = parse(listOf(line("2,3 km", Field.PICKUP_ADDRESS, 100.0), line("Rua Alfa", Field.TRIP_DISTANCE, 100.0)))
        assertTrue(result.rides.single().fields.isEmpty())
    }
    @Test fun duplicateAndConflictingLinesInvalidateTheField() {
        val result = parse(lines(100.0) + line("???", Field.PRICE, 100.0))
        assertNull(result.rides.single().price)
        assertEquals("Ana", result.rides.single().fields[Field.PASSENGER]?.text)
    }
    @Test fun crossingRegionBoundaryIsRejected() {
        val crossing = Line("Rua Alfa", Box(50.0, 295.0, 900.0, 355.0))
        assertTrue(parse(listOf(crossing)).rides.single().fields.isEmpty())
    }
    @Test fun overlappingCardBoundsRejectBothCards() {
        val result = parse(lines(100.0), listOf(card(100.0), card(400.0)))
        assertTrue(result.rides.isEmpty())
        assertEquals(2, result.rejectedCards)
    }
    @Test fun clippedCardsDoNotContaminateVisibleCards() {
        val result = parse(lines(-300.0) + lines(300.0, "Bruno") + lines(1700.0), listOf(card(-300.0), card(300.0), card(1700.0)))
        assertEquals(2, result.rejectedCards)
        assertEquals("Bruno", result.rides.single().fields[Field.PASSENGER]?.text)
    }
    @Test fun nullAndInvalidBoxesAreUnused() {
        val result = parse(listOf(Line("R$ 20,00", null), Line("Ana", Box(Double.NaN, 0.0, 1.0, 2.0))))
        assertTrue(result.rides.single().fields.isEmpty())
        assertEquals(2, result.unusedLines)
    }
    @Test fun translationAndScalingPreserveFields() {
        val original = lines(100.0)
        fun transform(b: Box) = Box(b.left * .6 + 200, b.top * .6 + 50, b.right * .6 + 200, b.bottom * .6 + 50)
        val result = StrictSpatialRideParser.parse(original.map { it.copy(box = transform(it.box!!)) },
            listOf(Card(transform(card(100.0).bounds))), layout, transform(viewport))
        assertEquals(parse(original).rides.single().fields.mapValues { it.value.text }, result.rides.single().fields.mapValues { it.value.text })
    }
    @Test fun calibratedDetectorUsesPriceAnchorsAndRejectsOverlaps() {
        val input = lines(100.0) + lines(700.0, "Bruno")
        val cards = StrictSpatialRideParser.detectCards(input, viewport, Profile(layout, .5))
        assertEquals(listOf(card(100.0), card(700.0)), cards)
        assertEquals(2, parse(input, cards).rides.size)
        val duplicates = StrictSpatialRideParser.detectCards(input + line("R$ 21,00", Field.PRICE, 100.0), viewport, Profile(layout, .5))
        assertEquals(1, parse(input, duplicates).rides.size)
    }
    @Test fun perKmPricesAndMissingAnchorsNeverCreateCards() {
        val input = listOf(line("R$ 2,00/km", Field.PRICE, 100.0), line("R$ 20,00", Field.PICKUP_ADDRESS, 700.0))
        assertTrue(StrictSpatialRideParser.detectCards(input, viewport, Profile(layout, .5)).isEmpty())
    }
    @Test fun trialIsOptInAndClearsPreviousFrame() {
        SpatialParserTrial.configure(null)
        assertNull(SpatialParserTrial.compare(lines(100.0), 1000, 2000, 1))
        try {
            SpatialParserTrial.configure(SpatialParserTrial.Configuration(Profile(layout, .5), Box(0.0, 0.0, 1.0, 1.0)))
            assertEquals(1, SpatialParserTrial.compare(lines(100.0), 1000, 2000, 9)!!.strict.rides.size)
            assertEquals(9, SpatialParserTrial.latestReport!!.legacyRideCount)
            assertTrue(SpatialParserTrial.compare(emptyList(), 1000, 2000, 0)!!.strict.rides.isEmpty())
        } finally { SpatialParserTrial.configure(null) }
        assertNull(SpatialParserTrial.latestReport)
    }
    @Test(expected = IllegalArgumentException::class) fun overlappingRegionsAreInvalid() {
        Layout(mapOf(Field.PRICE to Box(0.0, 0.0, 1.0, 1.0), Field.RATING to Box(.1, .1, .2, .2)))
    }
}
