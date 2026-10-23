package com.uberanalyzer.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.graphics.PixelFormat
import com.uberanalyzer.MainActivity
import com.uberanalyzer.model.InDriverRide

/**
 * Non-interactive highlighting over the original inDrive list.
 * Only draws rides present in the same ordered snapshot sent to the map.
 * Does not alter the inDrive UI or intercept touches.
 */
object RideBorderOverlay {
    private val handler = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var view: BorderView? = null
    private var owner: AccessibilityService? = null
    private var generation = 0L

    fun show(service: AccessibilityService, rides: List<InDriverRide>, window: Rect?) {
        handler.post {
            if (window == null || window.isEmpty || rides.isEmpty()) {
                clearOnMain()
                return@post
            }
            val markers = rides.mapIndexedNotNull { index, ride ->
                val left = ride.screenPriceLeft ?: return@mapIndexedNotNull null
                val top = ride.screenPriceTop ?: return@mapIndexedNotNull null
                val right = ride.screenPriceRight ?: return@mapIndexedNotNull null
                val bottom = ride.screenPriceBottom ?: return@mapIndexedNotNull null
                val priceBounds = Rect(left, top, right, bottom)
                if (priceBounds.isEmpty || !Rect.intersects(window, priceBounds)) return@mapIndexedNotNull null
                Marker(priceBounds, Color.parseColor(MainActivity.ROUTE_COLORS[index % MainActivity.ROUTE_COLORS.size]))
            }
            if (markers.isEmpty()) {
                clearOnMain()
                return@post
            }
            try {
                if (owner !== service || view == null) {
                    clearOnMain()
                    manager = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
                    view = BorderView(service)
                    manager?.addView(view, WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START })
                    owner = service
                }
                view?.update(Rect(window), markers)
                val thisGeneration = ++generation
                handler.postDelayed({
                    if (generation == thisGeneration) clearOnMain()
                }, 4500L)
            } catch (_: Exception) {
                clearOnMain()
            }
        }
    }

    fun clear() { handler.post { clearOnMain() } }

    private fun clearOnMain() {
        ++generation
        try { view?.let { manager?.removeViewImmediate(it) } } catch (_: Exception) {}
        view = null
        manager = null
        owner = null
    }

    private data class Marker(val priceBounds: Rect, val color: Int)

    private class BorderView(service: AccessibilityService) : View(service) {
        private var bounds = Rect()
        private var markers = emptyList<Marker>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 4f
        }

        fun update(window: Rect, current: List<Marker>) {
            bounds = window
            markers = current
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val horizontalPadding = 9f * density
            val verticalPadding = 6f * density
            val radius = 15f * density
            for (marker in markers) {
                paint.color = marker.color
                val price = marker.priceBounds
                val halfStroke = paint.strokeWidth / 2
                val left = maxOf(bounds.left.toFloat() + halfStroke, price.left - horizontalPadding)
                val top = maxOf(bounds.top.toFloat() + halfStroke, price.top - verticalPadding)
                val right = minOf(bounds.right.toFloat() - halfStroke, price.right + horizontalPadding)
                val bottom = minOf(bounds.bottom.toFloat() - halfStroke, price.bottom + verticalPadding)
                if (right > left && bottom > top) {
                    canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint)
                }
            }
        }
    }
}
