package com.uberanalyzer.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.uberanalyzer.model.InDriverJsonFormatter
import com.uberanalyzer.parser.RideParser
import java.util.concurrent.Executors

class UberAccessibilityService : AccessibilityService() {
    
    private val destinationMemory = com.uberanalyzer.parser.RideDestinationMemory()
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var isTaskPending = false
    private var lastScanTime = 0L
    @Volatile private var lastFullText = ""
    
    private val scanHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var destroyed = false
    @Volatile private var gesturePending = false
    @Volatile private var nextScanAfter = 0L
    private val autoHideMonitor = object : Runnable {
        override fun run() {
            if (destroyed) return
            if (com.uberanalyzer.settings.SettingsManager(this@UberAccessibilityService).getAutoHideEnabled()) {
                requestImmediateInDriverScan()
            }
            scanHandler.postDelayed(this, 1800L)
        }
    }

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

        requestImmediateInDriverScan()
    }

    private fun performInDriverScan() {
        val targetBounds = findRideWindowBounds()
        if (targetBounds == null) {
            try { performFallbackAccessibilityScan() } finally { isTaskPending = false }
            return
        }
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
                        try {
                            if (destroyed) return
                            val rideLines = lines.filter { it.boundingBox?.let(targetBounds::contains) == true }
                            val rideText = rideLines.joinToString(" | ") { it.text }
                            var processed = false

                            // 1. Tenta parsing por agrupamento espacial de Bounding Boxes (ML Kit Lines)
                            run {
                                val spatialRides = RideParser.parseInDriverSpatialLines(rideLines, fullImage)
                                // Shadow trial: explicitly calibrated debug builds only; never changes live rides.
                                if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 && fullImage != null) {
                                    try {
                                        val trialLines = lines.map { line ->
                                            com.uberanalyzer.parser.StrictSpatialRideParser.Line(line.text, line.boundingBox?.let {
                                                com.uberanalyzer.parser.StrictSpatialRideParser.Box(
                                                    it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble()
                                                )
                                            })
                                        }
                                        com.uberanalyzer.parser.SpatialParserTrial.compare(
                                            trialLines, fullImage.width, fullImage.height, spatialRides.size
                                        )?.let { report ->
                                            Log.d("SpatialParserTrial", "legacy=${report.legacyRideCount}, strict=${report.strict.rides.size}, rejected=${report.strict.rejectedCards}, unusedLines=${report.strict.unusedLines}")
                                        }
                                    } catch (e: Exception) {
                                        Log.w("SpatialParserTrial", "Trial failed; production parser is unchanged", e)
                                    }
                                }
                                if (spatialRides.isNotEmpty()) {
                                    processed = processInDriverRides(spatialRides, isOcrSource = true)
                                }
                            }

                            // 2. Se espacial não gerou resultados, tenta parsing por texto corrido
                            if (!processed && rideText.isNotBlank()) {
                                val lowerText = rideText.lowercase(java.util.Locale.getDefault())
                                if (lowerText.contains("r$") || lowerText.contains("oferecer") || lowerText.contains("aceitar") || lowerText.contains("recusar")) {
                                    processed = processInDriverQueue(rideText, isOcrSource = true)
                                }
                            }

                            // 3. Fallback para leitura de nós se OCR não capturou nenhuma corrida
                            if (!processed) {
                                performFallbackAccessibilityScan()
                            }
                        } finally { isTaskPending = false }
                    }

                    override fun onError(error: Exception) {
                        // Fallback to accessibility nodes on OCR error or cooldown
                        try { if (!destroyed) performFallbackAccessibilityScan() } finally { isTaskPending = false }
                    }
                }
            )
        } else {
            try { performFallbackAccessibilityScan() } finally { isTaskPending = false }
        }
    }

    private fun isRidePackage(pkg: String): Boolean =
        pkg.contains("indrive") || pkg.contains("rubus") || pkg.contains("sinet") || pkg.contains("ubercab")

    private fun findRideWindowBounds(): android.graphics.Rect? {
        return try {
            windows?.firstNotNullOfOrNull { window ->
                val root = window.root ?: return@firstNotNullOfOrNull null
                try {
                    if (isRidePackage(root.packageName?.toString().orEmpty())) {
                        android.graphics.Rect().also { window.getBoundsInScreen(it) }.takeUnless { it.isEmpty }
                    } else null
                } finally { root.recycle() }
            }
        } catch (_: Exception) { null }
    }

    private fun performFallbackAccessibilityScan() {
        try {
            val capturedLines = mutableListOf<com.uberanalyzer.ocr.MlKitScreenOcrEngine.OcrLine>()
            
            // In split-screen / multi-window mode, iterate through all interactive windows to find inDrive
            val activeWindows = try { windows } catch (e: Exception) { null }
            if (!activeWindows.isNullOrEmpty()) {
                for (window in activeWindows) {
                    val root = window.root ?: continue
                    val pkg = root.packageName?.toString()?.lowercase(java.util.Locale.getDefault()) ?: ""
                    if (!isRidePackage(pkg)) {
                        try { root.recycle() } catch (_: Exception) {}
                        continue
                    }
                    collectPositionedText(root, capturedLines)
                    try { root.recycle() } catch (_: Exception) {}
                }
            }

            // Fallback to active window if windows list was empty
            if (capturedLines.isEmpty()) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    val pkg = rootNode.packageName?.toString()?.lowercase(java.util.Locale.getDefault()) ?: ""
                    if (isRidePackage(pkg)) {
                        try {
                            collectPositionedText(rootNode, capturedLines)
                        } finally {
                            try { rootNode.recycle() } catch (_: Exception) {}
                        }
                    }
                }
            }

            val fullText = capturedLines.joinToString(" | ") { it.text }
            if (fullText.isNotBlank() && (fullText != lastFullText || com.uberanalyzer.settings.SettingsManager(this).getAutoHideEnabled())) {
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
        val rides = RideParser.parseInDriverList(text)
        return processInDriverRides(rides, isOcrSource)
    }

    private fun processInDriverRides(rides: List<com.uberanalyzer.model.InDriverRide>, isOcrSource: Boolean): Boolean {
        if (rides.isEmpty()) { destinationMemory.update(emptyList(), System.currentTimeMillis()); return false }

        val now = System.currentTimeMillis()
        if (now - lastProcessedTime < 600) return false // Cooldown between overlay updates
        
        val settings = com.uberanalyzer.settings.SettingsManager(this)
        val minKm = settings.getMinKmValue().toDouble()
        val autoHide = settings.getAutoHideEnabled()
        val maxRoutes = settings.getMaxRoutes()

        val capturedRides = destinationMemory.update(rides, now).take(maxRoutes)
        if (capturedRides.isNotEmpty()) {
            lastProcessedTime = now

            if (autoHide && !gesturePending && android.os.SystemClock.elapsedRealtime() >= nextScanAfter) {
                val index = AutoHidePolicy.firstBelowMinimum(rides.map { ride ->
                    if (ride.earningsPerKm > 0.0) ride.earningsPerKm
                    else if (ride.totalDistanceKm > 0.0) ride.price / ride.totalDistanceKm else 0.0
                }, minKm)
                if (index != null) {
                    scanHandler.post {
                        // Recheck the switch immediately before dispatch, including while OCR was running.
                        if (!destroyed && com.uberanalyzer.settings.SettingsManager(this).getAutoHideEnabled()) {
                            performSwipeHideItem(index)
                        }
                    }
                }
            }
            val sourceName = if (isOcrSource) "ML Kit OCR (Pixels)" else "Acessibilidade (Nós)"
            sendDebugLog("📥 Capturadas ${capturedRides.size} corridas via $sourceName na Lista inDrive!")

            // CHECK HIGH PROFIT ALERT THRESHOLD AND SOUND BEEP ALARM
            val highProfitKmThreshold = settings.getHighProfitAlertKm().toDouble()
            val hasHighProfitRide = capturedRides.any { ride ->
                val eKm = if (ride.earningsPerKm > 0) ride.earningsPerKm else (if (ride.totalDistanceKm > 0) ride.price / ride.totalDistanceKm else 0.0)
                eKm >= highProfitKmThreshold && eKm > 0.0
            }
            if (hasHighProfitRide) {
                sendDebugLog("🔔 Corrida de Alta Lucratividade Detectada (>= R$ ${String.format(java.util.Locale.US, "%.2f", highProfitKmThreshold)}/km)! Disparando bip sonoro...")
                com.uberanalyzer.audio.SoundManager(this).playHighProfitAlert()
            }
            
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

    private fun collectPositionedText(
        node: AccessibilityNodeInfo,
        lines: MutableList<com.uberanalyzer.ocr.MlKitScreenOcrEngine.OcrLine>
    ) {
        if (!node.isVisibleToUser || node.packageName?.toString() == packageName) return
        if (node.childCount == 0) {
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
                ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (text != null) {
                val box = android.graphics.Rect()
                node.getBoundsInScreen(box)
                if (!box.isEmpty) lines.add(com.uberanalyzer.ocr.MlKitScreenOcrEngine.OcrLine(text, box))
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { collectPositionedText(child, lines) } finally { child.recycle() }
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
    @Synchronized
    fun performSwipeHideItem(itemIndex: Int = 0): Boolean {
        if (destroyed || itemIndex !in 0..2 || gesturePending || android.os.SystemClock.elapsedRealtime() < nextScanAfter) return false
        val rideWindow = findRideWindowBounds() ?: return false
        gesturePending = true
        return try {
            // Coordinates belong to the ride window, including split-screen on either side.
            val startX = rideWindow.left + rideWindow.width() * 0.30f
            val endX = rideWindow.left + rideWindow.width() * 0.90f
            val targetYRatio = when (itemIndex) { 1 -> 0.40f; 2 -> 0.55f; else -> 0.25f }
            val targetY = rideWindow.top + rideWindow.height() * targetYRatio
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
                    finishHideGesture()
                    sendDebugLog("🖐️ Gesto de deslizar (item ${itemIndex + 1}) executado no inDrive!")
                }

                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    finishHideGesture()
                    sendDebugLog("⚠️ Gesto de deslizar cancelado pelo sistema Android (verifique se em modo split-screen).")
                }
            }, null)

            if (!success) finishHideGesture()

            sendDebugLog("👆 Disparando toque/deslize físico na tela para item ${itemIndex + 1} (X: ${startX.toInt()}➔${endX.toInt()}, Y: ${targetY.toInt()})...")
            success
        } catch (e: Exception) {
            finishHideGesture()
            Log.e("UberAccessibility", "Erro ao disparar gesto de ocultar: ${e.message}")
            false
        }
    }

    private fun finishHideGesture() {
        // Wait for list animation, then capture the new order before another gesture.
        nextScanAfter = android.os.SystemClock.elapsedRealtime() + 1200L
        gesturePending = false
        lastFullText = ""
    }
    @Synchronized
    fun requestImmediateInDriverScan() {
        val now = System.currentTimeMillis()
        if (destroyed || gesturePending || android.os.SystemClock.elapsedRealtime() < nextScanAfter || isTaskPending || (now - lastScanTime < 1500)) return
        lastScanTime = now
        isTaskPending = true
        executor.execute {
            try {
                performInDriverScan()
            } catch (e: Exception) {
                Log.e("UberAccessibility", "Erro no scan manual: ${e.message}", e)
                isTaskPending = false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        destroyed = false
        scanHandler.removeCallbacks(autoHideMonitor)
        scanHandler.post(autoHideMonitor)
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
        destroyed = true
        scanHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
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

        fun triggerScan(context: android.content.Context) {
            val activeInstance = instance
            if (activeInstance != null) {
                activeInstance.requestImmediateInDriverScan()
            }
        }

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

