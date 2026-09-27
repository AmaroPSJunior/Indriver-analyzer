package com.uberanalyzer.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.uberanalyzer.model.InDriverJsonFormatter
import com.uberanalyzer.parser.RideParser
import android.location.Geocoder
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class UberAccessibilityService : AccessibilityService() {
    
    private val destinationMemory = com.uberanalyzer.parser.RideDestinationMemory()
    private val executor = Executors.newSingleThreadExecutor()
    private val destinationFilterExecutor = Executors.newSingleThreadExecutor()
    private val destinationCoordinateCache = ConcurrentHashMap<String, Pair<Double, Double>>()
    @Volatile
    private var isTaskPending = false
    private var lastScanTime = 0L
    @Volatile private var recentRides = emptyList<com.uberanalyzer.model.InDriverRide>()
    @Volatile private var recentWindow: android.graphics.Rect? = null
    @Volatile private var capturedAt = 0L
    @Volatile private var scanStartedAt = 0L
    @Volatile private var selectionInvalidatedAt = 0L
    
    private val scanHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var destroyed = false
    @Volatile private var gesturePending = false
    @Volatile private var destinationFilterCheckPending = false
    @Volatile private var nextScanAfter = 0L
    private data class SelectionRequest(
        val identity: RideSelection.Identity,
        val requestedAt: Long,
        val result: (String) -> Unit
    )
    @Volatile private var pendingSelection: SelectionRequest? = null

    fun requestOpenRide(pickup: String, dropoff: String, price: Double, result: (String) -> Unit) {
        if (destroyed || gesturePending) {
            result("Aguarde o gesto atual e tente novamente.")
            return
        }
        if (findRideWindowBounds(inDriveOnly = true) == null) {
            result("Mantenha a lista do inDrive visível em tela dividida para selecionar a corrida.")
            return
        }
        val request = SelectionRequest(RideSelection.Identity(pickup, dropoff, price), android.os.SystemClock.elapsedRealtime(), result)
        pendingSelection = request
        // Reuse a current, uniquely matched capture before scheduling any new OCR.
        tryOpenRequestedRide(recentRides, recentWindow)
        if (pendingSelection !== request) return
        scanHandler.postDelayed({
            if (pendingSelection === request) {
                pendingSelection = null
                result("Não foi possível identificar uma única corrida na lista atual. Atualize o mapa e tente novamente.")
            }
        }, 8000L)
        requestImmediateInDriverScan()
    }

    private fun tryOpenRequestedRide(rides: List<com.uberanalyzer.model.InDriverRide>, bounds: android.graphics.Rect?) {
        val request = pendingSelection ?: return
        val scanTime = capturedAt
        if (bounds == null || !RideSelection.isFresh(scanTime, android.os.SystemClock.elapsedRealtime(), selectionInvalidatedAt)) return
        val index = RideSelection.findUnique(request.identity, rides.map {
            RideSelection.Identity(it.pickupAddress, it.dropoffAddress, it.price)
        }) ?: return
        val y = rides[index].screenRowY ?: return
        val open = Runnable {
            val now = android.os.SystemClock.elapsedRealtime()
            if (destroyed || pendingSelection !== request || gesturePending || recentRides !== rides ||
                now - request.requestedAt >= 8000L || !RideSelection.isFresh(scanTime, now, selectionInvalidatedAt) ||
                bounds != findRideWindowBounds(inDriveOnly = true) || y <= bounds.top || y >= bounds.bottom) return@Runnable
            pendingSelection = null
            gesturePending = true
            val path = android.graphics.Path().apply { moveTo(bounds.exactCenterX(), y.toFloat()) }
            val gesture = android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 1)).build()
            fun finish(message: String) {
                nextScanAfter = android.os.SystemClock.elapsedRealtime() + 2000L
                gesturePending = false
                recentRides = emptyList()
                recentWindow = null
                request.result(message)
            }
            try {
                val sent = dispatchGesture(gesture, object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        finish("Toque enviado à corrida correspondente no inDrive.")
                    }
                    override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) {
                        finish("O toque foi cancelado. Tente novamente.")
                    }
                }, scanHandler)
                if (!sent) finish("Não foi possível enviar o toque ao inDrive.")
            } catch (_: Exception) {
                finish("Não foi possível enviar o toque ao inDrive.")
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) open.run()
        else scanHandler.post(open)
    }

    private val autoHideMonitor = object : Runnable {
        override fun run() {
            if (destroyed) return
            val settings = com.uberanalyzer.settings.SettingsManager(this@UberAccessibilityService)
            if (pendingSelection != null || settings.getAutoHideEnabled() || settings.getDestinationDirectionFilterEnabled()) {
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

        if (isTargetApp && (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            selectionInvalidatedAt = android.os.SystemClock.elapsedRealtime()
        }

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
                                val spatialRides = RideParser.parseInDriverSpatialLines(
                                    rideLines,
                                    fullImage,
                                    com.uberanalyzer.settings.SettingsManager(this@UberAccessibilityService)
                                        .getDestinationDirectionFilterEnabled()
                                )
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
                                    processed = processInDriverRides(spatialRides, isOcrSource = true, capturedWindow = targetBounds)
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
        pkg.contains("indrive", true) || pkg.contains("rubus", true) || pkg.contains("sinet", true) || pkg.contains("ubercab", true)

    private fun findRideWindowBounds(inDriveOnly: Boolean = pendingSelection != null): android.graphics.Rect? {
        return try {
            windows?.firstNotNullOfOrNull { window ->
                val root = window.root ?: return@firstNotNullOfOrNull null
                try {
                    if (isRidePackage(root.packageName?.toString().orEmpty()) && (!inDriveOnly || !root.packageName.toString().contains("ubercab", true))) {
                        android.graphics.Rect().also { window.getBoundsInScreen(it) }.takeUnless { it.isEmpty }
                    } else null
                } finally { root.recycle() }
            } ?: rootInActiveWindow?.let { root ->
                try {
                    if (isRidePackage(root.packageName?.toString().orEmpty()) && (!inDriveOnly || !root.packageName.toString().contains("ubercab", true))) {
                        android.graphics.Rect().also { root.getBoundsInScreen(it) }.takeUnless { it.isEmpty }
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
                    if (!isRidePackage(pkg) || (pendingSelection != null && pkg.contains("ubercab"))) {
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
                    if (isRidePackage(pkg) && (pendingSelection == null || !pkg.contains("ubercab"))) {
                        try {
                            collectPositionedText(rootNode, capturedLines)
                        } finally {
                            try { rootNode.recycle() } catch (_: Exception) {}
                        }
                    }
                }
            }

            // Preserve positions: flattening the list loses both card boundaries and swipe targets.
            val bounds = findRideWindowBounds() ?: return
            val visible = capturedLines.filter { it.boundingBox?.let(bounds::contains) == true }
                .distinctBy { "${it.text}:${it.boundingBox}" }
            val rides = RideParser.parseInDriverSpatialLines(
                visible,
                includeAddresslessCards = com.uberanalyzer.settings.SettingsManager(this).getDestinationDirectionFilterEnabled()
            )
            processInDriverRides(rides, isOcrSource = false, capturedWindow = bounds)

        } catch (e: Exception) {
            Log.e("UberAccessibility", "Erro no scan de nós: ${e.message}")
        }
    }

    private fun processInDriverQueue(text: String, isOcrSource: Boolean = false): Boolean {
        val rides = RideParser.parseInDriverList(text)
        return processInDriverRides(rides, isOcrSource)
    }

    private fun processInDriverRides(
        rides: List<com.uberanalyzer.model.InDriverRide>,
        isOcrSource: Boolean,
        capturedWindow: android.graphics.Rect? = null,
        directionFilterAlreadyChecked: Boolean = false,
        directionFilterVerified: Boolean = false
    ): Boolean {
        if (destinationFilterCheckPending && !directionFilterAlreadyChecked) return true
        recentRides = rides
        recentWindow = capturedWindow?.let { android.graphics.Rect(it) }
        capturedAt = scanStartedAt
        tryOpenRequestedRide(rides, capturedWindow)
        if (rides.isEmpty()) {
            destinationMemory.update(emptyList(), System.currentTimeMillis())
            if (com.uberanalyzer.settings.SettingsManager(this).getDestinationDirectionFilterEnabled()) {
                sendBroadcast(Intent("com.uberanalyzer.ACTION_INDRIVE_ROUTE_DETECTED").apply {
                    setPackage(packageName)
                    putExtra("rides_json", "[]")
                    putExtra("direction_filter_verified", false)
                })
            }
            return false
        }

        val now = System.currentTimeMillis()
        if (!directionFilterAlreadyChecked && now - lastProcessedTime < 600) return false // Cooldown between overlay updates
        
        val settings = com.uberanalyzer.settings.SettingsManager(this)
        val directionFilterEnabled = settings.getDestinationDirectionFilterEnabled()
        if (directionFilterEnabled && !directionFilterAlreadyChecked && pendingSelection == null && !gesturePending) {
            val firstPositionedRide = rides.any { it.screenListIndex == 0 && it.screenRowY != null }
            if (capturedWindow == null || !firstPositionedRide) {
                sendDebugLog("⚠️ Filtro por endereço aguardando captura espacial para deslizar a corrida certa.")
                return true
            }
            if (!destinationFilterCheckPending) {
                destinationFilterCheckPending = true
                val snapshot = rides
                val targetAddress = settings.getSelectedDestinationFilterAddress()
                destinationFilterExecutor.execute {
                    val check = checkFirstThreeRidesDirection(snapshot, targetAddress)
                    scanHandler.post {
                        destinationFilterCheckPending = false
                        if (destroyed) return@post

                        val currentSettings = com.uberanalyzer.settings.SettingsManager(this)
                        if (pendingSelection != null || gesturePending) {
                            requestImmediateInDriverScan()
                            return@post
                        }
                        if (recentRides !== snapshot) {
                            requestImmediateInDriverScan()
                            return@post
                        }
                        if (!currentSettings.getDestinationDirectionFilterEnabled()) {
                            processInDriverRides(snapshot, isOcrSource, capturedWindow, directionFilterAlreadyChecked = true)
                            return@post
                        }
                        if (currentSettings.getSelectedDestinationFilterAddress() != targetAddress) {
                            requestImmediateInDriverScan()
                            return@post
                        }
                        if (!check.targetResolved) {
                            sendDebugLog("⚠️ Não foi possível localizar o endereço do filtro; nenhuma corrida foi removida.")
                            processInDriverRides(snapshot, isOcrSource, capturedWindow, directionFilterAlreadyChecked = true)
                        } else if (!check.canInspectFirstThree) {
                            sendDebugLog("⚠️ Captura sem posições confiáveis; o filtro aguardará uma nova leitura.")
                            requestImmediateInDriverScan()
                        } else if (check.invalidScreenIndex != null) {
                            val rejectedNumber = check.invalidScreenIndex + 1
                            sendDebugLog("📍 Corrida $rejectedNumber fora da direção selecionada; deslizando para removê-la.")
                            if (!performSwipeHideItem(check.invalidScreenIndex, automatic = true, addressFilter = true)) {
                                scanHandler.postDelayed({ if (!destroyed) requestImmediateInDriverScan() }, 1500L)
                            }
                        } else {
                            processInDriverRides(
                                snapshot,
                                isOcrSource,
                                capturedWindow,
                                directionFilterAlreadyChecked = true,
                                directionFilterVerified = true
                            )
                        }
                    }
                }
            }
            return true
        }

        val minKm = settings.getMinKmValue().toDouble()
        val autoHide = settings.getAutoHideEnabled()
        val maxRoutes = settings.getMaxRoutes()

        val visibleRouteLimit = if (directionFilterEnabled) minOf(maxRoutes, 3) else maxRoutes
        val capturedRides = destinationMemory.update(rides, now).take(visibleRouteLimit)
        if (capturedRides.isNotEmpty()) {
            lastProcessedTime = now

            if (autoHide && pendingSelection == null && !gesturePending && android.os.SystemClock.elapsedRealtime() >= nextScanAfter) {
                val values = (0..2).map { position ->
                    val ride = rides.firstOrNull { it.screenListIndex == position }
                    ride?.earningsPerKm ?: 0.0
                }
                val index = AutoHidePolicy.firstBelowMinimum(values, minKm, rides.size)
                if (index != null && capturedWindow != null) {
                    val snapshot = recentRides
                    scanHandler.post {
                        val currentSettings = com.uberanalyzer.settings.SettingsManager(this)
                        val latestMinimum = currentSettings.getMinKmValue().toDouble()
                        if (!destroyed && pendingSelection == null && recentRides === snapshot && currentSettings.getAutoHideEnabled() &&
                            AutoHidePolicy.firstBelowMinimum(values, latestMinimum, snapshot.size) == index) {
                            performSwipeHideItem(index, automatic = true)
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
                putExtra("direction_filter_verified", directionFilterEnabled && directionFilterVerified)
                if (directionFilterEnabled && directionFilterVerified) {
                    putExtra("direction_filter_address", settings.getSelectedDestinationFilterAddress())
                }
            }
            sendBroadcast(intent)
            return true
        }
        return false
    }

    private data class DestinationDirectionCheck(
        val targetResolved: Boolean,
        val canInspectFirstThree: Boolean,
        val invalidScreenIndex: Int? = null
    )

    private fun checkFirstThreeRidesDirection(
        rides: List<com.uberanalyzer.model.InDriverRide>,
        selectedAddress: String
    ): DestinationDirectionCheck {
        val target = resolveDirectionFilterCoordinates(selectedAddress)
            ?: return DestinationDirectionCheck(targetResolved = false, canInspectFirstThree = false)

        val firstRide = rides.firstOrNull { it.screenListIndex == 0 && it.screenRowY != null }
            ?: return DestinationDirectionCheck(targetResolved = true, canInspectFirstThree = false)

        for (screenIndex in 0..2) {
            val ride = rides.firstOrNull { it.screenListIndex == screenIndex } ?: break
            if (ride.screenRowY == null) {
                return DestinationDirectionCheck(targetResolved = true, canInspectFirstThree = false)
            }
            if (!RideParser.isRealAddress(ride.pickupAddress) || !RideParser.isRealAddress(ride.dropoffAddress)) {
                return DestinationDirectionCheck(targetResolved = true, canInspectFirstThree = true, invalidScreenIndex = screenIndex)
            }

            val origin = resolveDirectionFilterCoordinates(ride.pickupAddress)
                ?: return DestinationDirectionCheck(targetResolved = true, canInspectFirstThree = true, invalidScreenIndex = screenIndex)
            val destination = resolveDirectionFilterCoordinates(ride.dropoffAddress)
                ?: return DestinationDirectionCheck(targetResolved = true, canInspectFirstThree = true, invalidScreenIndex = screenIndex)

            if (distanceBetweenCoordinates(destination, target) >= distanceBetweenCoordinates(origin, target)) {
                return DestinationDirectionCheck(targetResolved = true, canInspectFirstThree = true, invalidScreenIndex = screenIndex)
            }
        }
        return DestinationDirectionCheck(targetResolved = true, canInspectFirstThree = true)
    }

    private fun resolveDirectionFilterCoordinates(address: String): Pair<Double, Double>? {
        if (!RideParser.isRealAddress(address)) return null
        val query = cleanDirectionFilterAddress(address)
        if (query.isBlank()) return null
        destinationCoordinateCache[query]?.let { cached ->
            return cached.takeUnless { it == Pair(0.0, 0.0) }
        }

        var resolved: Pair<Double, Double>? = null
        try {
            if (Geocoder.isPresent()) {
                @Suppress("DEPRECATION")
                val result = Geocoder(this, Locale("pt", "BR")).getFromLocationName(query, 1)
                if (!result.isNullOrEmpty()) resolved = Pair(result[0].latitude, result[0].longitude)
            }
        } catch (e: Exception) {
            Log.w("UberAccessibility", "Geocoder não resolveu endereço do filtro: ${e.message}")
        }

        if (resolved == null) {
            var connection: HttpURLConnection? = null
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                connection = (URL("https://nominatim.openstreetmap.org/search?format=json&q=$encoded&limit=1&countrycodes=br")
                    .openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "inDriveAnalyzer1.0 (Android)")
                    connectTimeout = 3000
                    readTimeout = 3000
                }
                if (connection.responseCode == 200) {
                    val results = org.json.JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                    if (results.length() > 0) {
                        val item = results.getJSONObject(0)
                        resolved = Pair(item.getDouble("lat"), item.getDouble("lon"))
                    }
                }
            } catch (e: Exception) {
                Log.w("UberAccessibility", "Nominatim não resolveu endereço do filtro: ${e.message}")
            } finally {
                connection?.disconnect()
            }
        }

        destinationCoordinateCache[query] = resolved ?: Pair(0.0, 0.0)
        return resolved
    }

    private fun cleanDirectionFilterAddress(rawAddress: String): String {
        var clean = rawAddress.trim()
            .replace(Regex("#[0-9A-Za-z\\-!#]+"), "")
            .replace(Regex("(?i)district\\s+of\\s+[a-zA-Z0-9\\s\\-!#]+"), "")
            .replace("(", ", ").replace(")", ", ").replace("-", ", ").replace("#", "")
        val parts = clean.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("R$") }
        clean = parts.distinct().joinToString(", ")
        if (clean.isBlank()) return ""
        if (!clean.contains("São Paulo", ignoreCase = true) && !clean.contains("SP", ignoreCase = true)) {
            clean += ", São Paulo, SP, Brasil"
        } else if (!clean.contains("Brasil", ignoreCase = true)) {
            clean += ", Brasil"
        }
        return clean
    }

    private fun distanceBetweenCoordinates(first: Pair<Double, Double>, second: Pair<Double, Double>): Double {
        val earthRadiusMeters = 6_371_000.0
        val firstLatitude = Math.toRadians(first.first)
        val secondLatitude = Math.toRadians(second.first)
        val latitudeDelta = Math.toRadians(second.first - first.first)
        val longitudeDelta = Math.toRadians(second.second - first.second)
        val sinLat = kotlin.math.sin(latitudeDelta / 2)
        val sinLon = kotlin.math.sin(longitudeDelta / 2)
        val a = (sinLat * sinLat + kotlin.math.cos(firstLatitude) * kotlin.math.cos(secondLatitude) * sinLon * sinLon)
            .coerceIn(0.0, 1.0)
        return earthRadiusMeters * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1.0 - a))
    }

    private fun collectPositionedText(
        node: AccessibilityNodeInfo,
        lines: MutableList<com.uberanalyzer.ocr.MlKitScreenOcrEngine.OcrLine>
    ) {
        if (!node.isVisibleToUser || node.packageName?.toString() == packageName) return
        run {
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
    fun performSwipeHideItem(itemIndex: Int = 0, automatic: Boolean = false, addressFilter: Boolean = false): Boolean {
        if (automatic && recentRides.size <= 1 && !addressFilter) return false
        if (destroyed || pendingSelection != null || itemIndex < 0 || (automatic && itemIndex > 2) || gesturePending || android.os.SystemClock.elapsedRealtime() < nextScanAfter) return false
        val settings = com.uberanalyzer.settings.SettingsManager(this)
        if (addressFilter && !settings.getDestinationDirectionFilterEnabled()) return false
        val rideWindow = findRideWindowBounds() ?: return false
        if (rideWindow != recentWindow) return false
        val ride = if (automatic) recentRides.firstOrNull { it.screenListIndex == itemIndex }
                   else recentRides.getOrNull(itemIndex)
        val target = SwipeTarget.plan(
            SwipeTarget.Window(rideWindow.left, rideWindow.top, rideWindow.right, rideWindow.bottom),
            ride?.screenRowY, capturedAt, android.os.SystemClock.elapsedRealtime()
        ) ?: return false
        if (automatic && !addressFilter && !settings.getAutoHideEnabled()) return false
        gesturePending = true
        return try {
            val startX = target.startX
            val endX = target.endX
            val targetY = target.y
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
        recentRides = emptyList()
        recentWindow = null
        scanHandler.postDelayed({ if (!destroyed) requestImmediateInDriverScan() }, 1300L)
    }
    @Synchronized
    fun requestImmediateInDriverScan() {
        val now = System.currentTimeMillis()
        if (destroyed || gesturePending || destinationFilterCheckPending || android.os.SystemClock.elapsedRealtime() < nextScanAfter || isTaskPending || (now - lastScanTime < 1500)) return
        lastScanTime = now
        isTaskPending = true
        scanStartedAt = android.os.SystemClock.elapsedRealtime()
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
        pendingSelection = null
        scanHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        destinationFilterExecutor.shutdownNow()
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
