package com.uberanalyzer.parser

/** In-memory shadow experiment. Results never enter the production ride list or map. */
object SpatialParserTrial {
    data class Configuration(
        val profile: StrictSpatialRideParser.Profile,
        val normalizedListViewport: StrictSpatialRideParser.Box
    ) {
        init { require(StrictSpatialRideParser.Box(0.0, 0.0, 1.0, 1.0).contains(normalizedListViewport)) }
    }
    data class Report(val strict: StrictSpatialRideParser.Result, val legacyRideCount: Int)

    @Volatile private var configuration: Configuration? = null
    @Volatile var latestReport: Report? = null
        private set

    /** Call from a debug test after measuring the current device/layout. Null disables the trial. */
    @Synchronized fun configure(value: Configuration?) {
        configuration = value?.copy(profile = value.profile.copy(
            layout = StrictSpatialRideParser.Layout(value.profile.layout.regions.toMap())
        ))
        latestReport = null
    }

    @Synchronized fun compare(lines: List<StrictSpatialRideParser.Line>, width: Int, height: Int, legacyRideCount: Int): Report? {
        val config = configuration ?: return null
        latestReport = null
        require(width > 0 && height > 0)
        val region = config.normalizedListViewport
        val viewport = StrictSpatialRideParser.Box(region.left * width, region.top * height, region.right * width, region.bottom * height)
        val cards = StrictSpatialRideParser.detectCards(lines, viewport, config.profile)
        val result = StrictSpatialRideParser.parse(lines, cards, config.profile.layout, viewport)
        return Report(result, legacyRideCount).also { latestReport = it }
    }
}
