package com.uberanalyzer.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.uberanalyzer.analyzer.RideAnalyzer
import com.uberanalyzer.model.InDriverJsonFormatter
import com.uberanalyzer.parser.RideParser
import java.util.concurrent.Executors

class UberAccessibilityService : AccessibilityService() {
    
    private val executor = Executors.newSingleThreadExecutor()
    private var isTaskPending = false
    
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: ""
        // Support inDriver package names as well as Uber
        if (!pkg.contains("indriver", true) && !pkg.contains("startup.inDriver", true) && !pkg.contains("ubercab", true)) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && 
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        if (System.currentTimeMillis() - lastLogTime > 8000) {
            lastLogTime = System.currentTimeMillis()
            sendDebugLog("⚡ Vigiando inDriver [Captura da Lista de Espera]...")
        }

        if (isTaskPending) return
        
        isTaskPending = true
        executor.execute {
            try {
                performInDriverScan()
            } finally {
                isTaskPending = false
            }
        }
    }

    private fun performInDriverScan() {
        val rootNode = rootInActiveWindow ?: return
        try {
            val sb = StringBuilder()
            collectTextFast(rootNode, sb)
            val fullText = sb.toString()

            if (fullText == lastFullText) return
            lastFullText = fullText

            val lowerText = fullText.lowercase()
            if (lowerText.contains("r$") || lowerText.contains("oferecer") || lowerText.contains("aceitar") || lowerText.contains("recusar")) {
                processInDriverQueue(fullText)
            }
        } finally {
            try { rootNode.recycle() } catch (e: Exception) {}
        }
    }

    private fun processInDriverQueue(text: String) {
        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < 800) return // Cooldown between overlay updates
        
        val settings = com.uberanalyzer.settings.SettingsManager(this)
        val minKm = settings.getMinKmValue().toDouble()
        val minHour = settings.getMinHourValue().toDouble()
        val maxRoutes = settings.getMaxRoutes()

        val capturedRides = RideParser.parseInDriverList(text).take(maxRoutes)
        if (capturedRides.isNotEmpty()) {
            lastProcessedTime = now
            val jsonPayload = InDriverJsonFormatter.toFormattedJson(capturedRides)
            sendDebugLog("📥 Capturadas ${capturedRides.size} corridas (Máx $maxRoutes) na Lista de Espera inDriver!")
            
            // SAVE TO HISTORY DATABASE
            val db = com.uberanalyzer.db.RideHistoryManager(this)
            capturedRides.forEach { ride ->
                val rideData = com.uberanalyzer.model.RideData(
                    price = ride.price,
                    distanceKm = ride.totalDistanceKm,
                    timeMin = ride.estimatedTimeMin,
                    category = com.uberanalyzer.model.RideCategory.INDRIVER_CITY,
                    raw = ride.rawText,
                    pickupAddress = ride.pickupAddress,
                    dropoffAddress = ride.dropoffAddress
                )
                db.saveRide(rideData, ride.score, com.uberanalyzer.model.ScoreRating.fromScore(ride.score).name)
            }

            // BROADCAST ROUTE DIRECTLY TO SIDE-BY-SIDE MAP ACTIVITY
            val bestRide = capturedRides.maxByOrNull { it.earningsPerKm } ?: capturedRides[0]
            val intent = Intent("com.uberanalyzer.ACTION_INDRIVE_ROUTE_DETECTED").apply {
                setPackage(packageName)
                putExtra("pickup_address", bestRide.pickupAddress)
                putExtra("dropoff_address", bestRide.dropoffAddress)
                putExtra("price", bestRide.price)
                putExtra("distance_km", bestRide.totalDistanceKm)
                putExtra("time_min", bestRide.estimatedTimeMin)
                putExtra("earnings_km", bestRide.earningsPerKm)
                putExtra("score", bestRide.score)
                putExtra("passenger", bestRide.passenger)
                putExtra("rating", bestRide.rating)
                putExtra("raw_text", bestRide.rawText)
                putExtra("rides_json", InDriverJsonFormatter.toJsonArray(capturedRides).toString())
            }
            sendBroadcast(intent)
        }
    }

    private fun collectTextFast(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        node.text?.let { if (it.isNotBlank()) sb.append(it).append(" | ") }
        node.contentDescription?.let { desc ->
            if (desc.isNotBlank()) {
                val str = desc.toString()
                if (str.startsWith("http://") || str.startsWith("https://") || str.startsWith("data:image/")) {
                    sb.append(str).append(" | ")
                } else {
                    sb.append(str).append(" | ")
                }
            }
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectTextFast(child, sb)
                try { child.recycle() } catch (e: Exception) {}
            }
        }
    }

    private fun sendDebugLog(text: String) {
        val intent = Intent("DEBUG_LOG")
        intent.putExtra("log_text", text)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private val hideTripReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val idx = intent?.getIntExtra("item_index", 0) ?: 0
            performSwipeHideItem(idx)
        }
    }

    override fun onInterrupt() {}

    /**
     * Executes a left-to-right swipe gesture on a specific item (index 0, 1, or 2) in the inDrive list to hide/dismiss the trip
     */
    fun performSwipeHideItem(itemIndex: Int = 0): Boolean {
        return try {
            val displayMetrics = resources.displayMetrics
            val width = displayMetrics.widthPixels.toFloat()
            val height = displayMetrics.heightPixels.toFloat()

            // Horizontal swipe in split screen mode (left side): ~6% to ~44% width
            val startX = width * 0.06f
            val endX = width * 0.44f

            // Position Y corresponding to item index (index 0 = ~25%, index 1 = ~40%, index 2 = ~55%)
            val targetYRatio = when (itemIndex) {
                1 -> 0.40f
                2 -> 0.55f
                else -> 0.25f
            }
            val targetY = height * targetYRatio

            val path = android.graphics.Path().apply {
                moveTo(startX, targetY)
                lineTo(endX, targetY)
            }

            val gestureBuilder = android.accessibilityservice.GestureDescription.Builder()
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 250)
            gestureBuilder.addStroke(stroke)

            val success = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    sendDebugLog("🖐️ Gesto de deslizar (item ${itemIndex + 1}) executado no inDrive!")
                }

                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    sendDebugLog("⚠️ Gesto de deslizar cancelado pelo sistema Android.")
                }
            }, null)

            sendDebugLog("👆 Disparando toque/deslize físico na tela para item ${itemIndex + 1} (Y: ${targetY.toInt()})...")
            success
        } catch (e: Exception) {
            Log.e("UberAccessibility", "Erro ao disparar gesto de ocultar: ${e.message}")
            false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val filter = android.content.IntentFilter("com.uberanalyzer.ACTION_HIDE_INDRIVE_TOP_TRIP")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(hideTripReceiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(hideTripReceiver, filter)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        sendDebugLog("⚡ Leitor inDriver pronto para capturar corridas e ocultar viagens!")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(hideTripReceiver)
        } catch (e: Exception) {
            // ignore
        }
        if (instance == this) {
            instance = null
        }
    }

    companion object {
        var instance: UberAccessibilityService? = null
            private set
        private var lastProcessedTime = 0L
        private var lastLogTime = 0L
        private var lastFullText = ""

        fun triggerHideTopTrip(context: android.content.Context, itemIndex: Int = 0) {
            val activeInstance = instance
            if (activeInstance != null) {
                activeInstance.performSwipeHideItem(itemIndex)
            } else {
                val intent = Intent("com.uberanalyzer.ACTION_HIDE_INDRIVE_TOP_TRIP").apply {
                    setPackage(context.packageName)
                    putExtra("item_index", itemIndex)
                }
                context.sendBroadcast(intent)
            }
        }
    }
}

