package com.uberanalyzer.settings

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("uber_analyzer_prefs", Context.MODE_PRIVATE)

    companion object {
        // Thresholds
        const val KEY_MIN_KM_VALUE = "min_km_value"
        const val KEY_MIN_HOUR_VALUE = "min_hour_value"
        const val KEY_CONFIRM_HIDE_BELOW_MIN_KM = "confirm_hide_below_min_km"
        const val KEY_AUTO_HIDE_ENABLED = "auto_hide_enabled"
        
        // Category Colors
        const val KEY_COLOR_UBER_X = "color_uber_x"
        const val KEY_COLOR_COMFORT = "color_comfort"
        const val KEY_COLOR_BLACK = "color_black"
        const val KEY_COLOR_FLASH = "color_flash"
        
        // Rating Colors
        const val KEY_COLOR_EXCELLENT = "color_excellent"
        const val KEY_COLOR_GOOD = "color_good"
        const val KEY_COLOR_AVERAGE = "color_average"
        const val KEY_COLOR_BAD = "color_bad"

        // Map & Card Display Settings
        const val KEY_MAX_ROUTES_VALUE = "max_routes_value"
        const val KEY_SHOW_PASSENGER_PHOTO = "show_passenger_photo"
        const val KEY_SHOW_PASSENGER_NAME = "show_passenger_name"
        const val KEY_SHOW_ROUTE_METRICS = "show_route_metrics"

        // Defaults
        const val DEFAULT_MIN_KM = 2.0f
        const val DEFAULT_MIN_HOUR = 45.0f
        const val DEFAULT_MAX_ROUTES = 3
        
        const val DEFAULT_UBER_X_COLOR = "#F2121212"
        const val DEFAULT_COMFORT_COLOR = "#F21A237E"
        const val DEFAULT_BLACK_COLOR = "#F2000000"
        const val DEFAULT_FLASH_COLOR = "#F2E65100"
        
        const val DEFAULT_EXCELLENT_COLOR = "#4CAF50"
        const val DEFAULT_GOOD_COLOR = "#8BC34A"
        const val DEFAULT_AVERAGE_COLOR = "#FFC107"
        const val DEFAULT_BAD_COLOR = "#F44336"
    }

    fun getMinKmValue(): Float = prefs.getFloat(KEY_MIN_KM_VALUE, DEFAULT_MIN_KM)
    fun setMinKmValue(value: Float) = prefs.edit().putFloat(KEY_MIN_KM_VALUE, value).apply()

    fun getConfirmHideBelowMinKm(): Boolean = prefs.getBoolean(KEY_CONFIRM_HIDE_BELOW_MIN_KM, true)
    fun setConfirmHideBelowMinKm(value: Boolean) = prefs.edit().putBoolean(KEY_CONFIRM_HIDE_BELOW_MIN_KM, value).apply()

    fun getAutoHideEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_HIDE_ENABLED, false)
    fun setAutoHideEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_AUTO_HIDE_ENABLED, value).apply()

    fun getMinHourValue(): Float = prefs.getFloat(KEY_MIN_HOUR_VALUE, DEFAULT_MIN_HOUR)
    fun setMinHourValue(value: Float) = prefs.edit().putFloat(KEY_MIN_HOUR_VALUE, value).apply()

    fun getMaxRoutes(): Int = prefs.getInt(KEY_MAX_ROUTES_VALUE, DEFAULT_MAX_ROUTES).coerceIn(1, 4)
    fun setMaxRoutes(value: Int) = prefs.edit().putInt(KEY_MAX_ROUTES_VALUE, value.coerceIn(1, 4)).apply()

    fun getShowPassengerPhoto(): Boolean = prefs.getBoolean(KEY_SHOW_PASSENGER_PHOTO, true)
    fun setShowPassengerPhoto(value: Boolean) = prefs.edit().putBoolean(KEY_SHOW_PASSENGER_PHOTO, value).apply()

    fun getShowPassengerName(): Boolean = prefs.getBoolean(KEY_SHOW_PASSENGER_NAME, true)
    fun setShowPassengerName(value: Boolean) = prefs.edit().putBoolean(KEY_SHOW_PASSENGER_NAME, value).apply()

    fun getShowRouteMetrics(): Boolean = prefs.getBoolean(KEY_SHOW_ROUTE_METRICS, true)
    fun setShowRouteMetrics(value: Boolean) = prefs.edit().putBoolean(KEY_SHOW_ROUTE_METRICS, value).apply()

    fun getCategoryColor(categoryKey: String, default: String): String = prefs.getString(categoryKey, default) ?: default
    fun setCategoryColor(categoryKey: String, color: String) = prefs.edit().putString(categoryKey, color).apply()

    fun getRatingColor(ratingKey: String, default: String): String = prefs.getString(ratingKey, default) ?: default
    fun setRatingColor(ratingKey: String, color: String) = prefs.edit().putString(ratingKey, color).apply()
}
