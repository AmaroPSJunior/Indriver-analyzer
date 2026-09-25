package com.uberanalyzer.service

/** Screen coordinates captured from the price label, never guessed from list index. */
internal object SwipeTarget {
    data class Window(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Gesture(val startX: Float, val endX: Float, val y: Float)

    fun plan(window: Window, rowY: Int?, capturedAt: Long, now: Long): Gesture? {
        if (rowY == null || now - capturedAt !in 0..4000L) return null
        val width = window.right - window.left
        if (width <= 0 || window.bottom <= window.top || rowY <= window.top || rowY >= window.bottom) return null
        return Gesture(window.left + width * 0.20f, window.left + width * 0.92f, rowY.toFloat())
    }
}
