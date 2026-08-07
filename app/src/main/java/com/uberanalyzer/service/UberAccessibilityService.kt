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
    @Volatile
    private var isTaskPending = false
    private var lastScanTime = 0L
    
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()?.lowercase(java.util.Locale.getDefault()) ?: ""
        
        // Support all inDrive package names, Uber, plus allow system/multi-window events
        val isTargetApp = pkg.contains("indrive") || pkg.contains("indriver") || 
                          pkg.contains("rubus") || pkg.contains("ubercab") || 
                          pkg.contains("sinet")

        val now = System.currentTimeMillis()

        // Debounce scan calls to prevent CPU & memory overload
        if (isTaskPending || (now - lastScanTime < 800)) return

        if (!isTargetApp) {
            // In split-screen mode, events might come from system UI or our app while inDrive is visible.
            // Allow a periodic scan if at least 1.5 seconds have passed
            if (now - lastScanTime < 1500) return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && 
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        if (now - lastLogTime > 8000) {
            lastLogTime = now
            sendDebugLog("⚡ Vigiando inDriver [Captura Ativa em Tela Dividida]...")
        }

        lastScanTime = now
        isTaskPending = true
        executor.execute {
            try {
                performInDriverScan()
            } catch (e: Exception) {
                Log.e("UberAccessibility", "Erro ao executar scan: ${e.message}", e)
            } finally {
                isTaskPending = false
            }
        }
    }

    private fun performInDriverScan() {
        // Step 1: Attempt direct ML Kit OCR screen capture if Android 11+
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            com.uberanalyzer.ocr.MlKitScreenOcrEngine.captureAndProcessScreen(
                this,
                object : com.uberanalyzer.ocr.MlKitScreenOcrEngine.OcrResultCallback {
                    override fun onOcrCompleted(
                        extractedText: String,
                        fullImage: android.graphics.Bitmap?,
                        textBlocks: List<com.uberanalyzer.ocr.MlKitScreenOcrEngine.OcrBlock>,
                        lines: List<com.uberanalyzer.ocr.MlKitScreenOcrEngine.OcrLine>
                    ) {
                        var processed = false

                        // 1. Tenta parsing por agrupamento espacial de Bounding Boxes (ML Kit Lines)
                        if (lines.isNotEmpty()) {
                            val spatialRides = RideParser.parseInDriverSpatialLines(lines)
                            if (spatialRides.isNotEmpty()) {
                                processed = processInDriverRides(spatialRides, isOcrSource = true)
                            }
                        }

                        // 2. Se espacial não gerou resultados, tenta parsing por texto corrido
                        if (!processed && extractedText.isNotBlank()) {
                            val lowerText = extractedText.lowercase(java.util.Locale.getDefault())
                            if (lowerText.contains("r$") || lowerText.contains("oferecer") || lowerText.contains("aceitar") || lowerText.contains("recusar")) {
                                processed = processInDriverQueue(extractedText, isOcrSource = true)
                            }
                        }

                        // 3. Fallback para leitura de nós se OCR não capturou nenhuma corrida
                        if (!processed) {
                            performFallbackAccessibilityScan()
                        }
                    }

                    override fun onError(error: Exception) {
                        // Fallback to accessibility nodes on OCR error or cooldown
                        performFallbackAccessibilityScan()
                    }
                }
            )
        } else {
            performFallbackAccessibilityScan()
        }
    }

    private fun performFallbackAccessibilityScan() {
        try {
            val sb = StringBuilder()
            
            // In split-screen / multi-window mode, iterate through all interactive windows to find inDrive
            val activeWindows = try { windows } catch (e: Exception) { null }
            if (!activeWindows.isNullOrEmpty()) {
                for (window in activeWindows) {
                    val root = window.root ?: continue
                    collectTextFast(root, sb)
                    try { root.recycle() } catch (_: Exception) {}
                }
            }

            // Fallback to active window if windows list was empty
            if (sb.isBlank()) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    try {
                        collectTextFast(rootNode, sb)
                    } finally {
                        try { rootNode.recycle() } catch (_: Exception) {}
                    }
                }
            }

            val fullText = sb.toString()
            if (fullText.isNotBlank() && fullText != lastFullText) {
                lastFullText = fullText
                val lowerText = fullText.lowercase(java.util.Locale.getDefault())
                if (lowerText.contains("r$") || lowerText.contains("oferecer") || lowerText.contains("aceitar") || lowerText.contains("recusar")) {
                    processInDriverQueue(fullText, isOcrSource = false)
                }
            }
        } catch (e: Exception) {
            Log.e("UberAccessibility", "Erro no scan de nós: ${e.message}")
        }
    }

    private fun processInDriverQueue(text: String, isOcrSource: Boolean = false): Boolean {
        val settings = com.uberanalyzer.settings.SettingsManager(this)
        val maxRoutes = settings.getMaxRoutes()
        val rides = RideParser.parseInDriverList(text).take(maxRoutes)
        return processInDriverRides(rides, isOcrSource)
    }

    private fun processInDriverRides(rides: List<com.uberanalyzer.model.InDriverRide>, isOcrSource: Boolean): Boolean {
        if (rides.isEmpty()) return false

        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < 600) return false // Cooldown between overlay updates
        
        val settings = com.uberanalyzer.settings.SettingsManager(this)
        val minKm = settings.getMinKmValue().toDouble()
        val autoHide = settings.getAutoHideEnabled()
        val maxRoutes = settings.getMaxRoutes()

        val capturedRides = rides.take(maxRoutes)
        if (capturedRides.isNotEmpty()) {
            lastProcessedTime = now

            if (autoHide) {
                for (i in capturedRides.indices) {
                    val ride = capturedRides[i]
                    val earningsPerKm = if (ride.earningsPerKm > 0) ride.earningsPerKm else (if (ride.totalDistanceKm > 0) ride.price / ride.totalDistanceKm else 0.0)
                    if (earningsPerKm < minKm && earningsPerKm > 0.0) {
                        val formattedVal = String.format(java.util.Locale.getDefault(), "R$ %.2f/km", earningsPerKm)
                        val formattedMin = String.format(java.util.Locale.getDefault(), "R$ %.2f/km", minKm)
                        sendDebugLog("⚡ Auto-Ocultar Ativo: Corrida ${i + 1} ($formattedVal < Meta $formattedMin). Disparando gesto de deslizar no inDrive...")
                        performSwipeHideItem(i)
                        break
                    }
                }
            }

            val sourceName = if (isOcrSource) "ML Kit OCR (Pixels)" else "Acessibilidade (Nós)"
            sendDebugLog("📥 Capturadas ${capturedRides.size} corridas via $sourceName na Lista inDrive!")
            
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

            // BROADCAST ROUTE DIRECTLY TO SIDE-BY-SIDE MAP ACTIVITY & OVERLAY
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
            return true
        }
        return false
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

            // Horizontal swipe in split screen mode (left side): ~15% to ~45% width
            // Starting at 15% avoids the Android 10+ System Back Gesture edge zone (0% to ~10%)
            val startX = width * 0.15f
            val endX = width * 0.45f

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
            val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 280)
            gestureBuilder.addStroke(stroke)

            val success = dispatchGesture(gestureBuilder.build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    sendDebugLog("🖐️ Gesto de deslizar (item ${itemIndex + 1}) executado no inDrive!")
                }

                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    sendDebugLog("⚠️ Gesto de deslizar cancelado pelo sistema Android (verifique se em modo split-screen).")
                }
            }, null)

            // FALLBACK: Also attempt to click any decline/close/ocultar node in active window
            try {
                findAndClickDeclineNode(rootInActiveWindow)
            } catch (e: Exception) {
                // ignore node search errors
            }

            sendDebugLog("👆 Disparando toque/deslize físico na tela para item ${itemIndex + 1} (X: ${startX.toInt()}➔${endX.toInt()}, Y: ${targetY.toInt()})...")
            success
        } catch (e: Exception) {
            Log.e("UberAccessibility", "Erro ao disparar gesto de ocultar: ${e.message}")
            false
        }
    }

    private fun findAndClickDeclineNode(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val text = node.text?.toString()?.lowercase(java.util.Locale.getDefault()) ?: ""
        val desc = node.contentDescription?.toString()?.lowercase(java.util.Locale.getDefault()) ?: ""
        if (text.contains("ocultar") || text.contains("recusar") || desc.contains("ocultar") || desc.contains("recusar") || desc.contains("close") || desc.contains("fechar")) {
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                findAndClickDeclineNode(child)
                try { child.recycle() } catch (e: Exception) {}
            }
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

