package com.uberanalyzer

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import android.widget.ScrollView
import android.widget.Button
import android.widget.EditText
import android.text.InputType
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.RadioGroup
import android.widget.RadioButton
import android.accessibilityservice.AccessibilityService
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.uberanalyzer.service.UberAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

class MainActivity : ThemedActivity() {

    private lateinit var accStatusView: TextView
    private lateinit var accButton: Button
    private lateinit var titleText: TextView
    private lateinit var routesCardsContainer: LinearLayout
    private lateinit var webView: WebView

    private var isMapLoaded = false
    private var pendingRoutes: List<RouteData>? = null
    private var currentActiveRoutes: MutableList<RouteData> = mutableListOf()
    private lateinit var settingsManager: com.uberanalyzer.settings.SettingsManager
    private lateinit var autoHideSwitch: androidx.appcompat.widget.SwitchCompat

    private var userLat: Double? = null
    private var userLng: Double? = null
    private var locationManager: LocationManager? = null
    private var currentLocationListener: LocationListener? = null
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    companion object {
        val ROUTE_COLORS = listOf(
            "#00E5FF", // 1: Cyan Neon
            "#22C55E", // 2: Verde Esmeralda
            "#F59E0B", // 3: Laranja Âmbar
            "#EC4899", // 4: Rosa Magenta
            "#A855F7", // 5: Roxo Neon
            "#EAB308", // 6: Amarelo Ouro
            "#14B8A6", // 7: Turquesa
            "#3B82F6", // 8: Azul Real
            "#F43F5E"  // 9: Vermelho Rosa
        )
    }

    data class RouteData(
        val pickup: String,
        val dropoff: String,
        val price: Double,
        val distanceKm: Double,
        val timeMin: Int,
        val earningsPerKm: Double,
        val score: Double,
        val passenger: String = "Passageiro",
        val passengerPhoto: String = ""
    )

    private var lastDetectedRidesJsonStr: String? = null
    private var activeJsonDialog: androidx.appcompat.app.AlertDialog? = null

    private val routeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val ridesJsonStr = intent?.getStringExtra("rides_json")
            if (!ridesJsonStr.isNullOrBlank()) {
                lastDetectedRidesJsonStr = ridesJsonStr
            }
            val routesList = mutableListOf<RouteData>()

            if (!ridesJsonStr.isNullOrBlank()) {
                try {
                    val array = JSONArray(ridesJsonStr)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val pickup = obj.optString("pickup_address", "").trim()
                        val dropoff = obj.optString("dropoff_address", "").trim()
                        val price = obj.optDouble("price_brl", 0.0)
                        val dist = obj.optDouble("total_distance_km", 1.0)
                        val time = obj.optInt("estimated_time_min", 15)
                        val earningsKm = obj.optDouble("earnings_per_km_brl", if (dist > 0) price / dist else 0.0)
                        val score = obj.optDouble("score", 8.5)
                        val passName = obj.optString("passenger", "").trim()
                        val passPhoto = obj.optString("passenger_photo", "")
                        
                        val isValidName = passName.isNotBlank() && 
                            !passName.equals("Passageiro", true) && 
                            !passName.equals("Passageiro inDrive", true) && 
                            !passName.equals("Passageiro inDriver", true) &&
                            !passName.contains("Spotify", true) &&
                            !passName.contains("Waze", true) &&
                            !passName.contains("Wi-Fi", true) &&
                            !passName.contains("Wifi", true) &&
                            !passName.contains("System", true) &&
                            !passName.contains("Atalho", true)

                        val isValidPickup = com.uberanalyzer.parser.RideParser.isRealAddress(pickup)
                        val isValidDropoff = com.uberanalyzer.parser.RideParser.isRealAddress(dropoff)

                        val hasAnyAddress = isValidPickup || isValidDropoff || dropoff.contains("mapa", true) || pickup.contains("mapa", true)

                        if (price > 0.0 && dist > 0.0 && isValidName && hasAnyAddress) {
                            routesList.add(RouteData(pickup, dropoff, price, dist, time, earningsKm, score, passName, passPhoto))
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (routesList.isEmpty() && intent != null) {
                val pickup = intent.getStringExtra("pickup_address") ?: ""
                val dropoff = intent.getStringExtra("dropoff_address") ?: ""
                val passName = intent.getStringExtra("passenger") ?: "Passageiro"
                
                val isValidName = passName.isNotBlank() && 
                    !passName.equals("Passageiro", true) && 
                    !passName.equals("Passageiro inDrive", true) && 
                    !passName.equals("Passageiro inDriver", true) &&
                    !passName.contains("Spotify", true) &&
                    !passName.contains("Waze", true) &&
                    !passName.contains("Wi-Fi", true)

                val isValidPickup = com.uberanalyzer.parser.RideParser.isRealAddress(pickup)
                val isValidDropoff = com.uberanalyzer.parser.RideParser.isRealAddress(dropoff)
                val hasAnyAddress = isValidPickup || isValidDropoff || dropoff.contains("mapa", true) || pickup.contains("mapa", true)

                if (isValidName && hasAnyAddress) {
                    val price = intent.getDoubleExtra("price", 0.0)
                    val dist = intent.getDoubleExtra("distance_km", 0.0)
                    val time = intent.getIntExtra("time_min", 0)
                    val earningsKm = intent.getDoubleExtra("earnings_km", if (dist > 0) price / dist else 0.0)
                    val score = intent.getDoubleExtra("score", 8.5)
                    val passPhoto = intent.getStringExtra("passenger_photo") ?: ""
                    routesList.add(RouteData(pickup, dropoff, price, dist, time, earningsKm, score, passName, passPhoto))
                }
            }

            val maxCount = settingsManager.getMaxRoutes()
            val topRoutes = routesList.take(maxCount)
            displayRoutesOnMap(topRoutes)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        settingsManager = com.uberanalyzer.settings.SettingsManager(this)
        setContentView(buildUI())

        val filter = IntentFilter("com.uberanalyzer.ACTION_INDRIVE_ROUTE_DETECTED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(routeReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(routeReceiver, filter)
        }

        setupWebView()
        setupLocationTracking()

        // Automatically display initial queue routes on start
        loadInitialQueueRoutes()

        // Gatilho automático para verificar permissões e ativar tela dividida
        window.decorView.postDelayed({
            if (!isAccessibilityServiceEnabled() || !isOverlayPermissionGranted()) {
                showPermissionChecklistDialog()
            } else {
                launchSplitScreenWithInDrive(force = false)
            }
        }, 650)
    }

    private var splitScreenTriggered = false

    private fun getInDriveLaunchIntent(): Intent? {
        val packages = listOf(
            "sinet.startup.inDriver",
            "com.indriver.android",
            "com.indrive.driver",
            "com.indrive.passenger",
            "com.indriver",
            "com.ubercab",
            "com.ubercab.driver"
        )
        for (pkg in packages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) return intent
        }

        // Fallback 1: Query launcher intents for any package containing inDrive/inDriver
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
            for (ri in resolveInfos) {
                val pkgName = ri.activityInfo.packageName
                if (pkgName.contains("indriver", ignoreCase = true) ||
                    pkgName.contains("indrive", ignoreCase = true) ||
                    pkgName.contains("startup", ignoreCase = true)) {
                    val intent = packageManager.getLaunchIntentForPackage(pkgName)
                    if (intent != null) return intent
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback 2: Direct launch intent creation for sinet.startup.inDriver
        return try {
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage("sinet.startup.inDriver")
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun launchSplitScreenWithInDrive(force: Boolean = false) {
        if (!force && (splitScreenTriggered || isInMultiWindowMode)) return
        splitScreenTriggered = true

        val inDriveIntent = getInDriveLaunchIntent()
        if (inDriveIntent == null) {
            if (force) {
                Toast.makeText(this, "⚠️ App inDrive não encontrado no dispositivo.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val accService = UberAccessibilityService.instance
            if (accService != null) {
                // Abre o inDrive primeiro na metade esquerda/superior (principal)
                inDriveIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                startActivity(inDriveIntent)

                // Aguarda o inDrive focar e aciona a tela dividida, abrindo nosso app ao lado (direita/inferior)
                window.decorView.postDelayed({
                    accService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    window.decorView.postDelayed({
                        val myIntent = Intent(this, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        }
                        startActivity(myIntent)
                    }, 450)
                }, 400)
            } else {
                // Sem serviço ativo, utiliza flag LAUNCH_ADJACENT para abrir o inDrive adjacente à nossa tela
                inDriveIntent.addFlags(
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
                startActivity(inDriveIntent)
                if (force) {
                    Toast.makeText(this, "💡 Ative o Leitor na Acessibilidade para Tela Dividida automática!", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: android.content.ActivityNotFoundException) {
            e.printStackTrace()
            if (force) {
                Toast.makeText(this, "⚠️ App inDrive (sinet.startup.inDriver) não encontrado.", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                inDriveIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(inDriveIntent)
            } catch (ex: Exception) {
                if (force) {
                    Toast.makeText(this, "⚠️ Erro ao abrir inDrive.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::webView.isInitialized) { webView.removeJavascriptInterface("AndroidBridge"); webView.destroy() }
        try { currentLocationListener?.let { locationManager?.removeUpdates(it) } } catch (_: Exception) {}
        try { unregisterReceiver(routeReceiver) } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        updateStatusView()
        if (::autoHideSwitch.isInitialized) {
            val isEnabled = settingsManager.getAutoHideEnabled()
            autoHideSwitch.isChecked = isEnabled
            if (isEnabled) UberAccessibilityService.triggerScan(this)
        }
    }

    private fun updateStatusView() {
        val accEnabled = isAccessibilityServiceEnabled()
        accStatusView.text = if (accEnabled) "✅ LEITOR ATIVO (LADO A LADO COM INDRIVE)" else "⚠️ LEITOR DESATIVADO — ATIVE PARA LER DA TELA"
        accStatusView.setTextColor(if (accEnabled) getColor(R.color.app_success) else getColor(R.color.app_warning))
        accButton.visibility = if (accEnabled) View.GONE else View.VISIBLE
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, UberAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains(expected)
    }

    private fun buildUI(): View {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.app_background))
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }

        // --- Top Header Panel ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.app_surface))
            }
        }

        val titleScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleText = TextView(this).apply {
            text = "⚡ inDrive Analyzer ${getAppVersionName()}"
            setTextColor(getColor(R.color.app_text))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, dp(8), 0)
        }

        val refreshButton = Button(this).apply {
            text = "🔄 Atualizar Fila"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#10B981"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(dp(4), 0, 0, 0)
            }
            setOnClickListener {
                UberAccessibilityService.triggerScan(this@MainActivity)
            }
        }

        autoHideSwitch = androidx.appcompat.widget.SwitchCompat(this).apply {
            text = "⚡ Auto-Ocultar "
            textSize = 11f
            setTextColor(getColor(R.color.app_text))
            isChecked = settingsManager.getAutoHideEnabled()
            setPadding(dp(6), dp(2), dp(6), dp(2))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(dp(4), 0, 0, 0)
            }
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.setAutoHideEnabled(isChecked)
                UberAccessibilityService.triggerScan(this@MainActivity)
                if (isChecked) {
                    Toast.makeText(this@MainActivity, "⚡ Auto-Ocultar ATIVADO (Monitoramento Contínuo)...", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "⏸️ Auto-Ocultar DESATIVADO", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val configButton = Button(this).apply {
            text = "⚙️ Configurações"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4F46E5"))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(dp(4), 0, 0, 0)
            }
            setOnClickListener { showMapSettingsDialog() }
        }

        titleRow.addView(titleText)
        titleRow.addView(refreshButton)
        titleRow.addView(autoHideSwitch)
        titleRow.addView(configButton)
        titleScrollView.addView(titleRow)
        header.addView(titleScrollView)

        accStatusView = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(4))
        }
        header.addView(accStatusView)

        accButton = Button(this).apply {
            text = "📋 Checklist de Permissões (Ativar Leitor)"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3B82F6"))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(2), 0, dp(4)) }
            setOnClickListener { showPermissionChecklistDialog() }
        }
        header.addView(accButton)

        // --- Horizontal Scroll View for Multi-Route Cards ---
        val scrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, 0) }
        }

        routesCardsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        scrollView.addView(routesCardsContainer)
        header.addView(scrollView)

        root.addView(header)

        // --- Interactive Map View ---
        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            setBackgroundColor(getColor(R.color.app_background))
        }
        root.addView(webView)

        return root
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setGeolocationEnabled(true)
            userAgentString = userAgentString + " inDriveAnalyzer"
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        webView.addJavascriptInterface(object {
            @android.webkit.JavascriptInterface
            fun onMapReady() {
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    isMapLoaded = true
                    applyMapProvider()
                    userLat?.let { uLat ->
                        userLng?.let { uLng ->
                            updateDriverLocationOnMap(uLat, uLng)
                        }
                    }
                    val routes = pendingRoutes ?: currentActiveRoutes.toList()
                    pendingRoutes = null
                    displayRoutesOnMap(routes)
                }
            }

            @android.webkit.JavascriptInterface
            fun hideTopTrip() {
                runOnUiThread {
                    hideTopRideAndCascade(0)
                }
            }
        }, "AndroidBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }
        }
        webView.webViewClient = WebViewClient()
        val mapProvidersJs = assets.open("map-providers.js").bufferedReader().use { it.readText() }
        isMapLoaded = false

        val mapHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <style>
                    #map-status { position: absolute; top: 8px; left: 8px; right: 8px; z-index: 1000; padding: 8px; border-radius: 6px; background: #FFFFFF; color: #0F172A; font: 13px sans-serif; }
                    body, html, #map { margin: 0; padding: 0; width: 100%; height: 100%; background: #0F172A; }
                    .leaflet-popup-content-wrapper { background: #1E293B; color: #F8FAFC; border-radius: 8px; border: 1px solid #38BDF8; font-family: sans-serif; font-size: 13px; }
                    /* Only OSM base tiles are filtered; route colors and markers stay intact. */
                    .osm-dark-tiles { filter: invert(1) hue-rotate(180deg) brightness(0.85) contrast(0.9); }
                    .leaflet-control-attribution { background: #1E293B !important; color: #F8FAFC; }
                    .leaflet-control-attribution a { color: #7DD3FC; }
                    .leaflet-popup-tip { background: #1E293B; }
                    .custom-badge {
                        width: 32px; height: 32px; border-radius: 50%;
                        display: flex; align-items: center; justify-content: center;
                        border: 2px solid #FFF; box-shadow: 0 3px 10px rgba(0,0,0,0.8);
                        font-weight: 900; font-size: 13px; color: #0F172A;
                    }
                    .avatar-badge-container {
                        position: absolute; bottom: 0; width: 32px; display: flex; flex-direction: column; align-items: center; pointer-events: none;
                    }
                    .avatar-circle {
                        width: 38px; height: 38px; border-radius: 50%;
                        display: flex; align-items: center; justify-content: center;
                        border: 3px solid #FFF;
                        box-shadow: 0 4px 12px rgba(0,0,0,0.85);
                        background: #1E293B;
                        overflow: visible;
                        position: relative;
                    }
                    .avatar-circle img {
                        width: 100%; height: 100%; object-fit: cover; border-radius: 50%;
                    }
                    .avatar-default {
                        font-size: 20px; line-height: 1;
                    }
                    .avatar-num-badge {
                        position: absolute;
                        top: -6px; right: -6px;
                        background: #0F172A;
                        color: #FFF;
                        font-size: 11px;
                        font-weight: 900;
                        padding: 1px 5px;
                        border-radius: 8px;
                        border: 1.5px solid #38BDF8;
                    }
                    .passenger-name-pill {
                        margin-top: 3px;
                        background: #0F172A;
                        color: #F8FAFC;
                        font-size: 11px;
                        font-weight: 800;
                        padding: 2px 8px;
                        border-radius: 12px;
                        border: 1.5px solid #FFF;
                        box-shadow: 0 2px 6px rgba(0,0,0,0.9);
                        white-space: nowrap;
                        text-align: center;
                    }
                    .origin-circle, .destination-circle {
                        font-family: sans-serif;
                        font-weight: 900;
                    }
                    .origin-circle {
                        box-sizing: border-box; flex-shrink: 0; width: 32px; height: 32px; border-radius: 50%; font-size: 18px;
                        display: flex; align-items: center; justify-content: center;
                        background: #16A34A; color: #FFFFFF;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.6);
                        margin-top: 4px;
                    }
                    .destination-circle {
                        box-sizing: border-box; flex-shrink: 0; width: 32px; height: 32px; border-radius: 50%; font-size: 18px;
                        display: flex; align-items: center; justify-content: center;
                        background: #DC2626; color: #FFFFFF;
                        box-shadow: 0 2px 4px rgba(0,0,0,0.6);
                    }
                ${if ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) != android.content.res.Configuration.UI_MODE_NIGHT_YES) "@media all" else "@media not all"} {
                        body, html, #map { background: #F8FAFC; }
                        .leaflet-popup-content-wrapper, .leaflet-popup-tip { background: #FFFFFF; color: #0F172A; }
                        .passenger-name-pill, .avatar-num-badge { background: #FFFFFF; color: #0F172A; }
                    }
                </style>
            </head>
            <body>
                <div id="map"></div>
                <div id="map-status" role="status">Carregando mapa…</div>
                <script>
                    function initializeMap() {
                    var map = L.map('map', {zoomControl: false}).setView([-23.56168, -46.65598], 13);
                    ${mapProvidersJs}

                    function escapeHtml(value) {
                        return String(value).replace(/[&<>"']/g, function(c) {
                            return {'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[c];
                        });
                    }
                    var routeLayers = [];
                    var mapGeneration = 0;
                    var ROUTE_COLORS = ['#00E5FF', '#22C55E', '#F59E0B', '#EC4899', '#A855F7', '#EAB308', '#14B8A6', '#3B82F6', '#F43F5E'];
                    var allRoutesData = [];
                    var routeLinesMap = {};
                    var driverLocationMarker = null;

                    function updateDriverLocation(lat, lng) {
                        if (!lat || !lng) return;
                        var userIcon = L.divIcon({
                            className: '',
                            html: '<div style="display:flex;align-items:center;justify-content:center;box-sizing:border-box;width:30px;height:30px;background:#2563EB;border:2px solid #FFFFFF;border-radius:50%;box-shadow:0 2px 4px rgba(0,0,0,0.6);font-size:18px;">🚘</div>',
                            iconSize: [30, 30],
                            iconAnchor: [15, 15]
                        });
                        if (driverLocationMarker) {
                            driverLocationMarker.setLatLng([lat, lng]);
                        } else {
                            driverLocationMarker = L.marker([lat, lng], {icon: userIcon, zIndexOffset: 2000}).addTo(map)
                                .bindPopup('<b>🚘 Minha Localização Atual (Motorista)</b>');
                        }
                    }

                                function isRealAddressJS(addr) {
                                    if (!addr || typeof addr !== 'string') return false;
                                    var raw = addr.trim();
                                    if (raw.length < 3) return false;
                                    var lower = raw.toLowerCase();
                                    if (lower.indexOf('definir') !== -1 ||
                                        lower.indexOf('escolher no mapa') !== -1 ||
                                        lower.indexOf('no mapa') !== -1 ||
                                        lower.indexOf('informado no app') !== -1 ||
                                        lower.indexOf('não especificado') !== -1 ||
                                        lower.indexOf('nao especificado') !== -1 ||
                                        lower.indexOf('não capturado') !== -1 ||
                                        lower.indexOf('nao capturado') !== -1 ||
                                        lower.indexOf('não identificado') !== -1 ||
                                        lower.indexOf('nao identificado') !== -1 ||
                                        lower.indexOf('sem destino') !== -1 ||
                                        lower.indexOf('endereço de') !== -1) {
                                        return false;
                                    }
                                    return true;
                                }


                    function updateMultiRouteMap(routesJsonStr) {
                        var generation = ++mapGeneration;
                        for (var i = 0; i < routeLayers.length; i++) {
                            map.removeLayer(routeLayers[i]);
                        }
                        routeLayers = [];
                        routeLinesMap = {};

                        var routes = [];
                        try {
                            routes = JSON.parse(routesJsonStr);
                            allRoutesData = routes;
                        } catch(e) {
                            console.error(e);
                            return;
                        }

                        if (!routes || routes.length === 0) return;

                        var groupLayers = [];
                        if (driverLocationMarker) {
                            groupLayers.push(driverLocationMarker);
                        }

                        for (var idx = 0; idx < routes.length; idx++) {
                            (function(idx) {
                                var r = routes[idx];
                                var color = ROUTE_COLORS[idx % ROUTE_COLORS.length];

                                var photoHtml = '';
                                if (r.showPhoto !== false && r.passengerPhoto && r.passengerPhoto.length > 5) {
                                    photoHtml = '<div class="avatar-circle" style="border-color: ' + color + ';">' +
                                        '<img src="' + escapeHtml(r.passengerPhoto) + '" onerror="this.style.display=&#39;none&#39;;" />' +
                                    '</div>';
                                }

                                var nameHtml = '';
                                if (r.showName !== false) {
                                    var passName = r.passenger ? r.passenger : 'Passageiro inDrive';
                                    nameHtml = '<div class="passenger-name-pill" style="border-color: ' + color + ';">👤 ' + escapeHtml(passName) + '</div>';
                                }

                                var pickupArrow = '&#8593;';
                                var dropoffArrow = '&#8595;';

                                var pickupHtml = '<div class="avatar-badge-container">' + 
                                    photoHtml + nameHtml +
                                    '<div class="origin-circle" style="background-color: #16A34A; border: 2px solid ' + color + ';">' + pickupArrow + '</div>' +
                                '</div>';

                                var dropoffHtml = '<div class="destination-circle" style="background-color: #DC2626; border: 2px solid ' + color + ';">' + dropoffArrow + '</div>';

                                var pickupIcon = L.divIcon({
                                    className: '',
                                    html: pickupHtml,
                                    iconSize: [32, 32],
                                    iconAnchor: [16, 16]
                                });
                                var dropoffIcon = L.divIcon({
                                    className: '',
                                    html: dropoffHtml,
                                    iconSize: [32, 32],
                                    iconAnchor: [16, 16]
                                });

                                var hasPickup = r.pLat && r.pLng && Math.abs(r.pLat) > 0.001 && Math.abs(r.pLng) > 0.001 && isRealAddressJS(r.pickup);
                                var hasDropoff = r.dLat && r.dLng && Math.abs(r.dLat) > 0.001 && Math.abs(r.dLng) > 0.001 && isRealAddressJS(r.dropoff);

                                if (hasPickup) {
                                    var pMarker = L.marker([r.pLat, r.pLng], {icon: pickupIcon}).addTo(map)
                                        .bindPopup('<b>👤 ' + (r.passenger || 'Passageiro') + ' (🟢 EMBARQUE)</b><br><b>R$ ' + r.price.toFixed(2) + ' (' + r.distanceKm + ' km)</b><br>📍 ' + r.pickup);
                                    routeLayers.push(pMarker);
                                    groupLayers.push(pMarker);
                                }

                                if (hasDropoff) {
                                    var dMarker = L.marker([r.dLat, r.dLng], {icon: dropoffIcon}).addTo(map)
                                        .bindPopup('<b>🟠 DESTINO • 👤 ' + (r.passenger || 'Passageiro') + '</b><br><b>R$ ' + r.price.toFixed(2) + ' (' + r.distanceKm + ' km)</b><br>🏁 ' + r.dropoff);
                                    routeLayers.push(dMarker);
                                    groupLayers.push(dMarker);
                                }

                                // Draw polyline route ONLY if both pickup and dropoff locations are valid
                                if (hasPickup && hasDropoff) {
                                    var osrmUrl = 'https://router.project-osrm.org/route/v1/driving/' + r.pLng + ',' + r.pLat + ';' + r.dLng + ',' + r.dLat + '?overview=full&geometries=geojson';
                                    fetch(osrmUrl)
                                        .then(function(res) { return res.json(); })
                                        .then(function(data) {
                                            if (generation !== mapGeneration) return;
                                            var latlngs;
                                            if (data && data.routes && data.routes.length > 0) {
                                                latlngs = data.routes[0].geometry.coordinates.map(function(c) { return [c[1], c[0]]; });
                                            } else {
                                                latlngs = [[r.pLat, r.pLng], [r.dLat, r.dLng]];
                                            }
                                            var line = L.polyline(latlngs, {
                                                color: color,
                                                weight: 6,
                                                opacity: 0.95,
                                                smoothFactor: 1
                                            }).addTo(map);

                                            line.bindTooltip('👤 ' + (r.passenger || 'Passageiro') + ' • R$ ' + r.price.toFixed(2), {permanent: false, sticky: true});
                                            routeLayers.push(line);
                                            groupLayers.push(line);
                                            routeLinesMap[idx] = line;
                                        })
                                        .catch(function(err) {
                                            if (generation !== mapGeneration) return;
                                            var latlngs = [[r.pLat, r.pLng], [r.dLat, r.dLng]];
                                            var line = L.polyline(latlngs, {
                                                color: color,
                                                weight: 6,
                                                opacity: 0.95,
                                                smoothFactor: 1
                                            }).addTo(map);
                                            line.bindTooltip('👤 ' + (r.passenger || 'Passageiro') + ' • R$ ' + r.price.toFixed(2), {permanent: false, sticky: true});
                                            routeLayers.push(line);
                                            groupLayers.push(line);
                                            routeLinesMap[idx] = line;
                                        });
                                }
                            })(idx);
                        }

                        if (groupLayers.length > 0) {
                            var group = new L.featureGroup(groupLayers);
                            map.fitBounds(group.getBounds(), {padding: [50, 50]});
                        }
                    }

                    function focusRouteByIdx(idx) {
                        if (idx >= 0 && idx < allRoutesData.length) {
                            var r = allRoutesData[idx];
                            var hasPickup = r.pLat && r.pLng && Math.abs(r.pLat) > 0.001 && isRealAddressJS(r.pickup);
                            var hasDropoff = r.dLat && r.dLng && Math.abs(r.dLat) > 0.001 && isRealAddressJS(r.dropoff);
                            if (hasPickup && hasDropoff) {
                                map.flyTo([(r.pLat + r.dLat)/2, (r.pLng + r.dLng)/2], 14, {duration: 0.8});
                            } else if (hasPickup) {
                                map.flyTo([r.pLat, r.pLng], 15, {duration: 0.8});
                            } else if (hasDropoff) {
                                map.flyTo([r.dLat, r.dLng], 15, {duration: 0.8});
                            }
                            if (routeLinesMap[idx]) {
                                routeLinesMap[idx].setStyle({weight: 9, opacity: 1.0});
                                routeLinesMap[idx].bringToFront();
                            }
                        }
                    }
                    window.setMapProvider = setMapProvider;
                    window.updateDriverLocation = updateDriverLocation;
                    window.updateMultiRouteMap = updateMultiRouteMap;
                    window.focusRouteByIdx = focusRouteByIdx;
                    window.addEventListener('resize', function() { map.invalidateSize(); });
                    AndroidBridge.onMapReady();
                    }
                    var leafletAttempt = 0;
                    var mapInitialized = false;
                    function loadLeaflet() {
                        var hosts = ['https://unpkg.com/leaflet@1.9.4/dist/', 'https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/'];
                        if (leafletAttempt >= hosts.length) {
                            document.getElementById('map-status').textContent = 'Não foi possível carregar o mapa. Verifique a conexão e toque aqui para tentar novamente.';
                            document.getElementById('map-status').onclick = function() { leafletAttempt = 0; loadLeaflet(); };
                            return;
                        }
                        var host = hosts[leafletAttempt++];
                        var css = document.createElement('link'); css.rel = 'stylesheet'; css.href = host + 'leaflet.css';
                        document.head.appendChild(css);
                        var script = document.createElement('script');
                        script.src = host + 'leaflet.js';
                        var finished = false;
                        function failed() {
                            if (finished || mapInitialized) return;
                            finished = true;
                            loadLeaflet();
                        }
                        var timeout = setTimeout(failed, 10000);
                        script.onload = function() {
                            clearTimeout(timeout);
                            finished = true;
                            if (!mapInitialized) {
                                mapInitialized = true;
                                initializeMap();
                            }
                        };
                        script.onerror = function() { clearTimeout(timeout); failed(); };
                        document.head.appendChild(script);
                    }
                    loadLeaflet();
                </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL("https://indrive-analyzer.invalid/", mapHtml, "text/html", "UTF-8", null)
    }

    private fun setupLocationTracking() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
            return
        }

        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    userLat = location.latitude
                    userLng = location.longitude
                    updateDriverLocationOnMap(location.latitude, location.longitude)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            if (locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                currentLocationListener = locationListener
                locationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 5f, locationListener)
                val lastGps = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (lastGps != null) {
                    userLat = lastGps.latitude
                    userLng = lastGps.longitude
                    updateDriverLocationOnMap(lastGps.latitude, lastGps.longitude)
                }
            }
            if (locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                currentLocationListener = locationListener
                locationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, locationListener)
                val lastNet = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (lastNet != null && userLat == null) {
                    userLat = lastNet.latitude
                    userLng = lastNet.longitude
                    updateDriverLocationOnMap(lastNet.latitude, lastNet.longitude)
                }
            }
        } catch (e: Exception) {
            Log.e("Location", "Error setting up location listener: ${e.message}")
        }
    }

    private fun updateDriverLocationOnMap(lat: Double, lng: Double) {
        if (isMapLoaded) {
            runOnUiThread {
                webView.evaluateJavascript("updateDriverLocation($lat, $lng)", null)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setupLocationTracking()
            }
        }
    }

    private val geocodeCache = ConcurrentHashMap<String, Pair<Double, Double>>()

    private var routeRenderGeneration = 0L

    private fun displayRoutesOnMap(routes: List<RouteData>) {
        val generation = ++routeRenderGeneration
        val maxRoutesConfig = settingsManager.getMaxRoutes()
        val limitedRoutes = routes.take(maxRoutesConfig)
        if (!isMapLoaded) {
            pendingRoutes = limitedRoutes
            return
        }

        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

        currentActiveRoutes = limitedRoutes.toMutableList()
        titleText.text = "⚡ inDrive Analyzer ${getAppVersionName()}"

        if (limitedRoutes.isEmpty()) {
            routesCardsContainer.removeAllViews()
            val waitingCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(getColor(R.color.app_background))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(2), getColor(R.color.app_accent))
                }
                layoutParams = LinearLayout.LayoutParams(dp(300), -2).apply {
                    setMargins(0, 0, dp(8), 0)
                }
            }
            val titleWait = TextView(this).apply {
                text = "🟢 AGUARDANDO SOLICITAÇÕES DO INDRIVE"
                setTextColor(getColor(R.color.app_accent))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
            }
            val descWait = TextView(this).apply {
                text = "Abra o aplicativo do inDrive lado a lado. O Leitor de Tela capturará automaticamente as corridas originais e flotará as rotas no mapa em tempo real."
                setTextColor(getColor(R.color.app_secondary))
                textSize = 11f
                maxLines = 3
            }
            waitingCard.addView(titleWait)
            waitingCard.addView(descWait)
            routesCardsContainer.addView(waitingCard)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView.evaluateJavascript("updateMultiRouteMap('[]')", null)
            } else {
                webView.loadUrl("javascript:updateMultiRouteMap('[]');")
            }
            return
        }

        // Asynchronously resolve real coordinates on background thread
        Thread {
            val jsRoutesArray = JSONArray()

            limitedRoutes.forEachIndexed { index, route ->
                val (pLat, pLng) = resolveCoordinates(route.pickup, true, route.distanceKm, index)
                val (dLat, dLng) = resolveCoordinates(route.dropoff, false, route.distanceKm, index)

                val jsObj = JSONObject().apply {
                    put("pickup", route.pickup.replace("'", "\\'").replace("\"", ""))
                    put("dropoff", route.dropoff.replace("'", "\\'").replace("\"", ""))
                    put("price", route.price)
                    put("distanceKm", route.distanceKm)
                    put("pLat", pLat)
                    put("pLng", pLng)
                    put("dLat", dLat)
                    put("dLng", dLng)
                    put("passenger", route.passenger)
                    put("passengerPhoto", route.passengerPhoto)
                    put("showPhoto", settingsManager.getShowPassengerPhoto())
                    put("showName", settingsManager.getShowPassengerName())
                }
                jsRoutesArray.put(jsObj)
            }

            runOnUiThread {
                if (generation == routeRenderGeneration) renderCardsAndMapUi(limitedRoutes, jsRoutesArray)
            }
        }.start()
    }

    fun hideTopRideAndCascade(rideIndex: Int = 0) {
        if (com.uberanalyzer.service.UberAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                "⚠️ Serviço de Acessibilidade desativado! Ative-o em Configurações ➔ Acessibilidade para permitir a simulação do toque na tela.",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }

        val minKm = settingsManager.getMinKmValue().toDouble()
        val targetRide = currentActiveRoutes.getOrNull(rideIndex) ?: currentActiveRoutes.firstOrNull()

        if (targetRide != null) {
            val valuePerKm = if (targetRide.earningsPerKm > 0) targetRide.earningsPerKm else (if (targetRide.distanceKm > 0) targetRide.price / targetRide.distanceKm else 0.0)
            val formattedKmVal = String.format(Locale.getDefault(), "R$ %.2f/km", valuePerKm)
            val formattedMinKm = String.format(Locale.getDefault(), "R$ %.2f/km", minKm)

            val tag = if (valuePerKm < minKm) "(Abaixo da Meta $formattedMinKm)" else "(Meta $formattedMinKm OK)"
            Toast.makeText(
                this,
                "🙈 Ocultando corrida ${rideIndex + 1}: $formattedKmVal $tag...",
                Toast.LENGTH_SHORT
            ).show()
        }

        // 1. Dispatch swipe gesture (left-to-right) via Accessibility Service
        com.uberanalyzer.service.UberAccessibilityService.triggerHideTopTrip(this, itemIndex = rideIndex)

        // A fresh service capture confirms removal and updates both cards and map.

    }

    private fun renderCardsAndMapUi(limitedRoutes: List<RouteData>, jsRoutesArray: JSONArray) {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        routesCardsContainer.removeAllViews()
        val minKm = settingsManager.getMinKmValue().toDouble()

        val highProfitKmThreshold = settingsManager.getHighProfitAlertKm().toDouble()
        val hasHighProfitRide = limitedRoutes.any { route -> route.earningsPerKm >= highProfitKmThreshold && route.earningsPerKm > 0.0 }
        if (hasHighProfitRide) {
            com.uberanalyzer.audio.SoundManager(this).playHighProfitAlert()
        }

        limitedRoutes.forEachIndexed { index, route ->
            val colorHex = ROUTE_COLORS[index % ROUTE_COLORS.size]
            val colorInt = Color.parseColor(colorHex)

            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = GradientDrawable().apply {
                    setColor(getColor(R.color.app_background))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(2), colorInt)
                }
                layoutParams = LinearLayout.LayoutParams(dp(230), -2).apply {
                    setMargins(0, 0, dp(8), 0)
                }
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val badge = TextView(this).apply {
                text = if (index == 0) "TOPO" else "FILA"
                setTextColor(Color.parseColor("#0F172A"))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    setColor(colorInt)
                    cornerRadius = dp(12).toFloat()
                }
            }

            val priceTitle = TextView(this).apply {
                text = String.format(Locale.getDefault(), "  R$ %.2f", route.price)
                setTextColor(colorInt)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }

            topRow.addView(badge)
            topRow.addView(priceTitle)
            card.addView(topRow)

            val passRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }

            val passIcon = TextView(this).apply {
                text = "👤"
                textSize = 13f
                setPadding(0, 0, dp(4), 0)
            }

            val passName = TextView(this).apply {
                text = if (route.passenger.isNotBlank()) route.passenger else "Passageiro inDrive"
                setTextColor(getColor(R.color.app_text))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }

            val photoBitmap = if (settingsManager.getShowPassengerPhoto() &&
                route.passengerPhoto.startsWith("data:image/") && route.passengerPhoto.contains(";base64,")) {
                try {
                    val bytes = android.util.Base64.decode(route.passengerPhoto.substringAfter(";base64,"), android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } catch (_: IllegalArgumentException) { null }
            } else null
            if (photoBitmap != null) {
                passRow.addView(android.widget.ImageView(this).apply {
                    setImageBitmap(photoBitmap)
                    contentDescription = "Foto do passageiro"
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(6) }
                })
            } else {
                passRow.addView(passIcon)
            }
            passRow.addView(passName)
            card.addView(passRow)

            if (settingsManager.getShowRouteMetrics()) {
                val valuePerKm = if (route.earningsPerKm > 0) route.earningsPerKm else (if (route.distanceKm > 0) route.price / route.distanceKm else 0.0)
                val isBelowMin = valuePerKm < minKm
                val statusTag = if (isBelowMin) "⚠️ Abaixo da Meta" else "✔ Meta OK"
                val statusColor = if (isBelowMin) Color.parseColor("#EF4444") else Color.parseColor("#22C55E")

                val infoText = TextView(this).apply {
                    text = String.format(Locale.getDefault(), "%.1f km • R$ %.2f/km (%s)", route.distanceKm, valuePerKm, statusTag)
                    setTextColor(statusColor)
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(2), 0, dp(3))
                }
                card.addView(infoText)
            }

            val isRealPickup = com.uberanalyzer.parser.RideParser.isRealAddress(route.pickup)
            val pickupDisplay = if (isRealPickup) route.pickup else (if (route.pickup.isNotBlank()) route.pickup else "Não identificada")
            val pickupText = TextView(this).apply {
                text = "🟢 Origem: $pickupDisplay"
                setTextColor(if (isRealPickup) getColor(R.color.app_success) else Color.parseColor("#64748B"))
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            card.addView(pickupText)

            val isRealDropoff = com.uberanalyzer.parser.RideParser.isRealAddress(route.dropoff)
            val dropoffDisplay = if (isRealDropoff) route.dropoff else (if (route.dropoff.isNotBlank()) route.dropoff else "Destino não capturado")
            val dropoffText = TextView(this).apply {
                text = "🟠 Destino: $dropoffDisplay"
                setTextColor(if (isRealDropoff) getColor(R.color.app_orange) else Color.parseColor("#64748B"))
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            card.addView(dropoffText)

            card.setOnClickListener {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    webView.evaluateJavascript("focusRouteByIdx($index)", null)
                } else {
                    webView.loadUrl("javascript:focusRouteByIdx($index);")
                }
            }

            routesCardsContainer.addView(card)
        }

        val jsonStr = jsRoutesArray.toString().replace("'", "\\'")
        val jsCall = "javascript:updateMultiRouteMap('$jsonStr');"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            webView.evaluateJavascript("updateMultiRouteMap('$jsonStr')", null)
        } else {
            webView.loadUrl(jsCall)
        }

        // Trigger Auto-Hide evaluation on active routes if switch is ON
    }

    private fun cleanAddressForGeocoding(rawAddress: String): String {
        if (!com.uberanalyzer.parser.RideParser.isRealAddress(rawAddress)) return ""
        var clean = rawAddress
        // Remove OCR noise, hashtags and inDrive internal codes like #8573311-!#
        clean = clean.replace(Regex("#[0-9A-Za-z\\-!#]+"), "")
        // Remove internal prefixes like "District of Freedom"
        clean = clean.replace(Regex("(?i)district\\s+of\\s+[a-zA-Z0-9\\s\\-!#]+"), "")
        // Convert parentheses and dashes to clean commas
        clean = clean.replace("(", ", ").replace(")", ", ").replace("-", ", ").replace("#", "")
        
        val parts = clean.split(",").map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("R$") }
        var result = parts.distinct().joinToString(", ")

        if (result.isBlank()) return ""

        if (!result.contains("São Paulo", ignoreCase = true) && !result.contains("SP", ignoreCase = true)) {
            result += ", São Paulo, SP, Brasil"
        } else if (!result.contains("Brasil", ignoreCase = true)) {
            result += ", Brasil"
        }
        return result
    }

    private fun resolveCoordinates(rawAddress: String, isPickup: Boolean, distanceKm: Double, routeIndex: Int): Pair<Double, Double> {
        if (!com.uberanalyzer.parser.RideParser.isRealAddress(rawAddress)) return Pair(0.0, 0.0)

        val clean = cleanAddressForGeocoding(rawAddress)
        if (clean.isBlank()) return Pair(0.0, 0.0)

        if (geocodeCache.containsKey(clean)) {
            return geocodeCache[clean]!!
        }

        // 1. Try Android native Geocoder
        try {
            if (Geocoder.isPresent()) {
                val geocoder = Geocoder(this, Locale("pt", "BR"))
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocationName(clean, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    val res = Pair(addr.latitude, addr.longitude)
                    geocodeCache[clean] = res
                    return res
                }
            }
        } catch (e: Exception) {
            Log.e("Geocoding", "Android Geocoder error for $clean: ${e.message}")
        }

        // 2. Try OpenStreetMap Nominatim API fallback
        try {
            val query = URLEncoder.encode(clean, "UTF-8")
            val url = URL("https://nominatim.openstreetmap.org/search?format=json&q=$query&limit=1&countrycodes=br")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "inDriveAnalyzer1.0 (Android)")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().readText()
                val arr = JSONArray(jsonStr)
                if (arr.length() > 0) {
                    val obj = arr.getJSONObject(0)
                    val lat = obj.getDouble("lat")
                    val lon = obj.getDouble("lon")
                    val res = Pair(lat, lon)
                    geocodeCache[clean] = res
                    return res
                }
            }
        } catch (e: Exception) {
            Log.e("Geocoding", "Nominatim OSM error for $clean: ${e.message}")
        }

        // 3. Fallback to precise local keyword mapping (evaluating specific stations/neighborhoods FIRST)
        val fallbackRes = resolveCoordinatesFallback(rawAddress + " " + clean, isPickup, distanceKm, routeIndex)
        if (fallbackRes != Pair(0.0, 0.0)) {
            geocodeCache[clean] = fallbackRes
            return fallbackRes
        }

        return Pair(0.0, 0.0)
    }

    private fun resolveCoordinatesFallback(address: String, isPickup: Boolean, distanceKm: Double, routeIndex: Int): Pair<Double, Double> {
        val lower = address.lowercase(Locale.getDefault())
        return when {
            // Specific stations, neighborhoods & landmarks FIRST
            lower.contains("engenheiro goulart") || lower.contains("eng goulart") || lower.contains("keralux") -> Pair(-23.4883, -46.5222)
            lower.contains("salinas de mossoró") || lower.contains("salinas de mossoro") || lower.contains("vila itaim") || lower.contains("itaim paulista") -> Pair(-23.4975, -46.4063)
            lower.contains("itaquera") || lower.contains("corinthians") -> Pair(-23.5350, -46.4580)
            lower.contains("penha") -> Pair(-23.5235, -46.5492)
            lower.contains("cangaiba") || lower.contains("cangaíba") -> Pair(-23.5021, -46.5268)
            lower.contains("ermelino") || lower.contains("matarazzo") -> Pair(-23.4862, -46.4839)
            lower.contains("guaianases") || lower.contains("guaianazes") -> Pair(-23.5423, -46.4137)
            lower.contains("são miguel") || lower.contains("sao miguel") -> Pair(-23.4939, -46.4419)
            lower.contains("tatuapé") || lower.contains("tatuape") -> Pair(-23.5408, -46.5767)
            lower.contains("mooca") -> Pair(-23.5542, -46.5989)
            lower.contains("santana") -> Pair(-23.5015, -46.6261)
            lower.contains("tucuruvi") -> Pair(-23.4800, -46.6033)
            lower.contains("pacaembu") -> Pair(-23.5433, -46.6631)
            lower.contains("moema") -> Pair(-23.6011, -46.6667)
            lower.contains("morumbi") -> Pair(-23.6001, -46.7200)
            lower.contains("pinheiros") -> Pair(-23.567280, -46.702046)
            lower.contains("itaim bibi") -> Pair(-23.585500, -46.678900)
            lower.contains("paulista") -> Pair(-23.561684, -46.655981)
            lower.contains("augusta") -> Pair(-23.554316, -46.658390)
            lower.contains("consolação") || lower.contains("consolacao") -> Pair(-23.548842, -46.643329)
            lower.contains("faria lima") -> Pair(-23.586803, -46.682220)
            lower.contains("berrini") -> Pair(-23.608331, -46.697079)
            lower.contains("sé") || lower.contains("praça da sé") || lower.contains("praca da se") -> Pair(-23.550520, -46.633308)
            lower.contains("ibirapuera") -> Pair(-23.587416, -46.657634)
            lower.contains("aeroporto") || lower.contains("congonhas") -> Pair(-23.626111, -46.656389)
            lower.contains("santo andré") || lower.contains("santo andre") -> Pair(-23.6666, -46.5322)
            lower.contains("são bernardo") || lower.contains("sao bernardo") -> Pair(-23.6939, -46.5650)
            lower.contains("são caetano") || lower.contains("sao caetano") -> Pair(-23.6226, -46.5588)
            lower.contains("guarulhos") -> Pair(-23.4542, -46.5333)
            lower.contains("osasco") -> Pair(-23.5329, -46.7917)
            lower.contains("diadema") -> Pair(-23.6865, -46.6234)
            else -> Pair(0.0, 0.0)
        }
    }

    private fun loadInitialQueueRoutes() {
        displayRoutesOnMap(emptyList())
    }

    private fun applyMapProvider() {
        if (!isMapLoaded) return
        val provider = org.json.JSONObject.quote(settingsManager.getMapProvider())
        val key = org.json.JSONObject.quote(settingsManager.getCartoMapKey())
        val darkMode = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        webView.evaluateJavascript("setMapProvider($provider, $key, $darkMode)", null)
    }

    private fun showMapSettingsDialog() {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        
        var dialog: androidx.appcompat.app.AlertDialog? = null

        val scrollContainer = ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.app_background))
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val title = TextView(this).apply {
            text = "⚙️ Configurações & Ferramentas"
            textSize = 18f
            setTextColor(getColor(R.color.app_text))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(14))
        }
        dialogView.addView(title)

        dialogView.addView(TextView(this).apply {
            text = "🎨 Aparência do aplicativo e do mapa"
            setTextColor(getColor(R.color.app_text))
            textSize = 16f
        })
        val themeGroup = RadioGroup(this)
        listOf(-1 to "📱 Seguir sistema", 1 to "☀️ Claro", 2 to "🌙 Escuro").forEach { (mode, label) ->
            themeGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = mode
                text = label
                setTextColor(getColor(R.color.app_text))
                isChecked = settingsManager.getThemeMode() == mode
            })
        }
        themeGroup.setOnCheckedChangeListener { group, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId)?.tag as? Int ?: return@setOnCheckedChangeListener
            if (selected != settingsManager.getThemeMode()) {
                settingsManager.setThemeMode(selected)
                dialog?.dismiss()
                delegate.localNightMode = selected
            }
        }
        dialogView.addView(themeGroup)

        val mapProviders = listOf("osm", "openfreemap", "carto")
        val providerNames = listOf("OpenStreetMap — grátis, sem chave", "OpenFreeMap — grátis, sem chave", "CARTO — mapa claro/escuro")
        dialogView.addView(TextView(this).apply {
            text = "🗺️ Provedor do mapa"
            setTextColor(getColor(R.color.app_text))
            textSize = 16f
        })
        val providerGroup = android.widget.RadioGroup(this)
        mapProviders.forEachIndexed { index, provider ->
            providerGroup.addView(android.widget.RadioButton(this).apply {
                id = android.view.View.generateViewId()
                tag = provider
                text = providerNames[index]
                setTextColor(getColor(R.color.app_text))
                isChecked = settingsManager.getMapProvider() == provider
            })
        }
        dialogView.addView(providerGroup)
        dialogView.addView(TextView(this).apply {
            text = "Se o mapa atingir o limite ou ficar indisponível, escolha outro e toque em Aplicar. Serviços gratuitos têm políticas de uso e podem ficar indisponíveis."
            setTextColor(getColor(R.color.app_secondary))
            textSize = 12f
        })
        dialogView.addView(Button(this).apply {
            text = "🗺️ Aplicar mapa"
            setOnClickListener {
                val selected = providerGroup.findViewById<android.widget.RadioButton>(providerGroup.checkedRadioButtonId)
                val provider = selected?.tag as? String ?: "osm"
                settingsManager.setMapProvider(provider)
                if (isMapLoaded) applyMapProvider() else setupWebView()

            }
        })

        // --- Seção: Ferramentas & Ações Rápida ---
        val toolsLabel = TextView(this).apply {
            text = "🛠️ FERRAMENTAS & ATALHOS"
            textSize = 12f
            setTextColor(getColor(R.color.app_accent))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        dialogView.addView(toolsLabel)

        val toolsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.app_surface))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#334155"))
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(16)) }
        }

        val jsonBtn = Button(this).apply {
            text = "📜 Hierarquia de Acessibilidade"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#059669"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) }
            setOnClickListener {
                dialog?.dismiss()
                showDetectedJsonDialog()
            }
        }

        val permBtn = Button(this).apply {
            text = "📋 Checklist de Permissões"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0284C7"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) }
            setOnClickListener {
                dialog?.dismiss()
                showPermissionChecklistDialog()
            }
        }

        val splitBtn = Button(this).apply {
            text = "📱 Dividir Tela com inDrive"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#8B5CF6"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) }
            setOnClickListener {
                dialog?.dismiss()
                launchSplitScreenWithInDrive(force = true)
            }
        }

        val advSettingsBtn = Button(this).apply {
            text = "🎨 Metas de Valores & Cores Avançadas"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4F46E5"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setOnClickListener {
                dialog?.dismiss()
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }

        toolsContainer.addView(jsonBtn)
        toolsContainer.addView(permBtn)
        toolsContainer.addView(splitBtn)
        toolsContainer.addView(advSettingsBtn)
        dialogView.addView(toolsContainer)

        // --- Seção: Exibição do Mapa ---
        val mapLabel = TextView(this).apply {
            text = "🗺️ EXIBIÇÃO DO MAPA & CARDS"
            textSize = 12f
            setTextColor(getColor(R.color.app_accent))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(8))
        }
        dialogView.addView(mapLabel)

        val maxRoutesText = TextView(this).apply {
            text = "Quantidade de Rotas no Mapa: ${settingsManager.getMaxRoutes()} (Máximo 4)"
            textSize = 13f
            setTextColor(getColor(R.color.app_text))
            setPadding(0, dp(4), 0, dp(6))
        }
        dialogView.addView(maxRoutesText)

        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }

        var currentMax = settingsManager.getMaxRoutes()
        listOf(1, 2, 3, 4).forEach { count ->
            val btn = Button(this).apply {
                text = "$count rotas"
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(if (currentMax == count) getColor(R.color.app_accent) else Color.parseColor("#334155"))
                setPadding(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                    setMargins(dp(3), 0, dp(3), 0)
                }
                setOnClickListener {
                    currentMax = count
                    maxRoutesText.text = "Quantidade de Rotas no Mapa: $currentMax (Máximo 4)"
                    for (i in 0 until buttonsRow.childCount) {
                        val b = buttonsRow.getChildAt(i) as? Button
                        val valStr = b?.text?.toString() ?: ""
                        b?.setBackgroundColor(if (valStr.startsWith("$currentMax ")) getColor(R.color.app_accent) else Color.parseColor("#334155"))
                    }
                }
            }
            buttonsRow.addView(btn)
        }
        dialogView.addView(buttonsRow)

        val showPhotoCheck = android.widget.CheckBox(this).apply {
            text = "📸 Exibir Foto do Usuário no Ponto A (Embarque)"
            setTextColor(getColor(R.color.app_text))
            textSize = 13f
            isChecked = settingsManager.getShowPassengerPhoto()
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        dialogView.addView(showPhotoCheck)

        val showNameCheck = android.widget.CheckBox(this).apply {
            text = "👤 Exibir Nome do Usuário logo abaixo da Foto"
            setTextColor(getColor(R.color.app_text))
            textSize = 13f
            isChecked = settingsManager.getShowPassengerName()
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        dialogView.addView(showNameCheck)

        val showMetricsCheck = android.widget.CheckBox(this).apply {
            text = "📊 Exibir Métricas e R$/km nos Cards"
            setTextColor(getColor(R.color.app_text))
            textSize = 13f
            isChecked = settingsManager.getShowRouteMetrics()
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        dialogView.addView(showMetricsCheck)

        // --- Seção: Meta de Valor/KM e Confirmação ao Ocultar ---
        val kmLabel = TextView(this).apply {
            text = "💰 REGRAS DE VALOR MÍNIMO E OCULTAÇÃO"
            textSize = 12f
            setTextColor(getColor(R.color.app_accent))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, dp(6))
        }
        dialogView.addView(kmLabel)

        val kmInputLabel = TextView(this).apply {
            text = "Valor Mínimo R$/KM para Ocultar (ex: 2.00):"
            textSize = 13f
            setTextColor(getColor(R.color.app_text))
            setPadding(0, dp(2), 0, dp(4))
        }
        dialogView.addView(kmInputLabel)

        val minKmInput = EditText(this).apply {
            setText(String.format(Locale.US, "%.2f", settingsManager.getMinKmValue()))
            setTextColor(getColor(R.color.app_text))
            setBackgroundColor(getColor(R.color.app_surface))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) }
        }
        dialogView.addView(minKmInput)

        val autoHideCheck = android.widget.CheckBox(this).apply {
            text = "⚡ Ativar Auto-Ocultar automático para viagens abaixo do valor R$/km mínimo"
            setTextColor(getColor(R.color.app_text))
            textSize = 13f
            isChecked = settingsManager.getAutoHideEnabled()
            setPadding(dp(8), dp(4), dp(8), dp(6))
        }
        dialogView.addView(autoHideCheck)

        val confirmHideCheck = android.widget.CheckBox(this).apply {
            text = "⚠️ Pedir confirmação ao ocultar manualmente viagens com valor menor que a meta (R$/km)"
            setTextColor(getColor(R.color.app_text))
            textSize = 13f
            isChecked = settingsManager.getConfirmHideBelowMinKm()
            setPadding(dp(8), dp(4), dp(8), dp(12))
        }
        dialogView.addView(confirmHideCheck)

        val highProfitLabel = TextView(this).apply {
            text = "🔔 Valor Mínimo R$/KM para Alerta Sonoro de Alta Lucratividade (ex: 4.00):"
            textSize = 13f
            setTextColor(getColor(R.color.app_text))
            setPadding(0, dp(6), 0, dp(4))
        }
        dialogView.addView(highProfitLabel)

        val highProfitKmInput = EditText(this).apply {
            setText(String.format(Locale.US, "%.2f", settingsManager.getHighProfitAlertKm()))
            setTextColor(getColor(R.color.app_text))
            setBackgroundColor(getColor(R.color.app_surface))
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) }
        }
        dialogView.addView(highProfitKmInput)

        scrollContainer.addView(dialogView)

        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(scrollContainer)
            .setPositiveButton("Salvar e Atualizar") { _, _ ->
                settingsManager.setMaxRoutes(currentMax)
                settingsManager.setShowPassengerPhoto(showPhotoCheck.isChecked)
                settingsManager.setShowPassengerName(showNameCheck.isChecked)
                settingsManager.setShowRouteMetrics(showMetricsCheck.isChecked)
                settingsManager.setAutoHideEnabled(autoHideCheck.isChecked)
                if (::autoHideSwitch.isInitialized) {
                    autoHideSwitch.isChecked = autoHideCheck.isChecked
                }

                val parsedMinKm = minKmInput.text.toString().replace(",", ".").toFloatOrNull() ?: 2.0f
                settingsManager.setMinKmValue(parsedMinKm)
                val parsedHighProfit = highProfitKmInput.text.toString().replace(",", ".").toFloatOrNull() ?: 4.0f
                settingsManager.setHighProfitAlertKm(parsedHighProfit)
                settingsManager.setConfirmHideBelowMinKm(confirmHideCheck.isChecked)

                displayRoutesOnMap(pendingRoutes ?: currentActiveRoutes.toList())

                UberAccessibilityService.triggerScan(this@MainActivity)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun getAppVersionName(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            "v" + pInfo.versionName
        } catch (e: Exception) {
            "v1.0"
        }
    }

    private fun isOverlayPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    private fun isLocationPermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(packageName)
    }

    private var activePermissionDialog: androidx.appcompat.app.AlertDialog? = null

    private fun showPermissionChecklistDialog() {
        activePermissionDialog?.dismiss()

        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(getColor(R.color.app_background))
        }

        // Title & Header
        val titleView = TextView(this).apply {
            text = "🛡️ Checklist de Permissões"
            textSize = 18f
            setTextColor(getColor(R.color.app_text))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        }
        container.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = "Siga o passo a passo com atalhos diretos para liberar as permissões no seu dispositivo:"
            textSize = 12f
            setTextColor(getColor(R.color.app_secondary))
            setPadding(0, 0, 0, dp(12))
        }
        container.addView(subtitleView)

        // Calculate Status
        val accOk = isAccessibilityServiceEnabled()
        val overlayOk = isOverlayPermissionGranted()
        val locationOk = isLocationPermissionGranted()
        val notificationOk = isNotificationListenerEnabled()

        var countActive = 0
        if (accOk) countActive++
        if (overlayOk) countActive++
        if (locationOk) countActive++
        if (notificationOk) countActive++

        // Summary Card
        val summaryCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(if (accOk && overlayOk) Color.parseColor("#065F46") else Color.parseColor("#78350F"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) }
        }

        val summaryText = TextView(this).apply {
            text = if (accOk && overlayOk) {
                "🎉 $countActive de 4 Permissões Ativas! O inDrive Analyzer está pronto para rodar."
            } else {
                "⚠️ Status: $countActive de 4 Permissões Concluídas. Complete as etapas abaixo:"
            }
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        }
        summaryCard.addView(summaryText)
        container.addView(summaryCard)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(380))
        }

        val stepsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Helper to build a Step Card
        fun createStepCard(
            stepNumber: String,
            title: String,
            description: String,
            isGranted: Boolean,
            isOptional: Boolean = false,
            buttonText: String,
            onButtonClick: () -> Unit
        ): View {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    setColor(getColor(R.color.app_surface))
                    cornerRadius = dp(8).toFloat()
                    setStroke(dp(1), if (isGranted) Color.parseColor("#22C55E") else Color.parseColor("#334155"))
                }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(10)) }
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val badgeText = TextView(this).apply {
                text = stepNumber
                textSize = 11f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(6), dp(2), dp(6), dp(2))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#2563EB"))
                    cornerRadius = dp(4).toFloat()
                }
            }
            headerRow.addView(badgeText)

            val stepTitle = TextView(this).apply {
                text = "  $title"
                textSize = 13f
                setTextColor(getColor(R.color.app_text))
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            headerRow.addView(stepTitle)

            val statusTag = TextView(this).apply {
                text = when {
                    isGranted -> "✅ ATIVO"
                    isOptional -> "⚠️ OPCIONAL"
                    else -> "❌ PENDENTE"
                }
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(
                    when {
                        isGranted -> getColor(R.color.app_success)
                        isOptional -> getColor(R.color.app_warning)
                        else -> getColor(R.color.app_error)
                    }
                )
            }
            headerRow.addView(statusTag)
            card.addView(headerRow)

            val descText = TextView(this).apply {
                text = description
                textSize = 12f
                setTextColor(getColor(R.color.app_secondary))
                setPadding(0, dp(6), 0, dp(8))
            }
            card.addView(descText)

            val actionBtn = Button(this).apply {
                text = buttonText
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(if (isGranted) Color.parseColor("#334155") else Color.parseColor("#2563EB"))
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { onButtonClick() }
            }
            card.addView(actionBtn)

            return card
        }

        // PASSO 1: Configuração Restrita (Android 13/14+)
        stepsLayout.addView(
            createStepCard(
                stepNumber = "1️⃣ PASSO 1",
                title = "Configuração Restrita (Android 13/14+)",
                description = "Se a opção nos 3 pontinhos (⋮) NÃO aparecer:\n1º Abra o PASSO 2 (Acessibilidade) PRIMEIRO e tente ativar o inDrive Analyzer para o Android gerar o aviso de 'Configuração Restrita'.\n2º Volte aqui e toque no botão abaixo para abrir 'Informações do App'.\n3º No canto superior direito da tela que abrir, toque nos 3 pontinhos (⋮) e selecione 'Permitir configurações restritas'.",
                isGranted = accOk,
                isOptional = false,
                buttonText = "⚙️ Abrir Informações do App (3 Pontinhos ⋮)",
                onButtonClick = {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        Toast.makeText(this, "No canto superior direito, toque nos 3 pontinhos (⋮) -> Permitir configurações restritas", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Abra Configurações do Celular -> Aplicativos -> inDrive Analyzer", Toast.LENGTH_LONG).show()
                    }
                }
            )
        )

        // PASSO 2: Leitura de Tela (Acessibilidade)
        stepsLayout.addView(
            createStepCard(
                stepNumber = "2️⃣ PASSO 2",
                title = "Leitura de Tela (Acessibilidade)",
                description = "Ative o 'inDrive Analyzer' para capturar valores, endereços e rotas em tempo real.",
                isGranted = accOk,
                isOptional = false,
                buttonText = if (accOk) "✅ Leitura de Tela Ativa" else "🔓 Ativar Leitura de Tela nas Configurações",
                onButtonClick = {
                    try {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Abra Configurações -> Acessibilidade -> inDrive Analyzer", Toast.LENGTH_LONG).show()
                    }
                }
            )
        )

        // PASSO 3: Janela Flutuante (Overlay)
        stepsLayout.addView(
            createStepCard(
                stepNumber = "3️⃣ PASSO 3",
                title = "Janela Flutuante (Sobreposição)",
                description = "Permite desenhar o mapa flutuante e cards sobre o app inDrive.",
                isGranted = overlayOk,
                isOptional = false,
                buttonText = if (overlayOk) "✅ Janela Flutuante Permitida" else "🪟 Autorizar Janela Flutuante",
                onButtonClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                            startActivity(intent)
                        } catch (e: Exception) {
                            startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    }
                }
            )
        )

        // PASSO 4: Localização GPS
        stepsLayout.addView(
            createStepCard(
                stepNumber = "4️⃣ PASSO 4",
                title = "Localização GPS (Motorista)",
                description = "Exibe sua localização exata no mapa para calcular distâncias de embarque.",
                isGranted = locationOk,
                isOptional = false,
                buttonText = if (locationOk) "✅ GPS Ativo" else "📍 Autorizar Acesso ao GPS",
                onButtonClick = {
                    setupLocationTracking()
                    Toast.makeText(this, "Verificando permissão de GPS...", Toast.LENGTH_SHORT).show()
                }
            )
        )

        // PASSO 5: Notificações (Opcional)
        stepsLayout.addView(
            createStepCard(
                stepNumber = "5️⃣ PASSO 5",
                title = "Acesso a Notificações",
                description = "Opcional. Captura ofertas recebidas através de alertas do sistema.",
                isGranted = notificationOk,
                isOptional = true,
                buttonText = if (notificationOk) "✅ Notificações Ativas" else "🔔 Ativar Leitura de Notificações",
                onButtonClick = {
                    try {
                        startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Abra Configurações -> Acesso a Notificações", Toast.LENGTH_LONG).show()
                    }
                }
            )
        )

        scrollView.addView(stepsLayout)
        container.addView(scrollView)

        // Bottom Action Row
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        val recheckBtn = Button(this).apply {
            text = "🔄 Re-verificar"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#475569"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(8), 0) }
            setOnClickListener {
                showPermissionChecklistDialog()
            }
        }

        val closeBtn = Button(this).apply {
            text = "Concluir e Fechar"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#10B981"))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener {
                activePermissionDialog?.dismiss()
                updateStatusView()
            }
        }

        bottomRow.addView(recheckBtn)
        bottomRow.addView(closeBtn)
        container.addView(bottomRow)

        activePermissionDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(container)
            .setCancelable(true)
            .show()
    }

    private fun showDetectedJsonDialog() {
        activeJsonDialog?.dismiss()

        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }

        val formattedJson: String
        var rideCount = 0

        if (!lastDetectedRidesJsonStr.isNullOrBlank()) {
            val raw = lastDetectedRidesJsonStr!!
            val parsed = try {
                val array = JSONArray(raw)
                rideCount = array.length()
                array.toString(2)
            } catch (e: Exception) {
                rideCount = 1
                raw
            }
            formattedJson = parsed
        } else if (currentActiveRoutes.isNotEmpty()) {
            rideCount = currentActiveRoutes.size
            val jsonArray = JSONArray()
            currentActiveRoutes.forEachIndexed { idx, r ->
                val obj = JSONObject().apply {
                    put("posicao", idx + 1)
                    put("passageiro", if (r.passenger.isNotBlank()) r.passenger else "Passageiro inDrive")
                    put("origem", r.pickup)
                    put("destino", r.dropoff)
                    put("valor_brl", r.price)
                    put("distancia_km", r.distanceKm)
                    put("tempo_min", r.timeMin)
                    put("ganho_por_km_brl", String.format(Locale.US, "%.2f", r.earningsPerKm).toDoubleOrNull() ?: r.earningsPerKm)
                    put("score", r.score)
                }
                jsonArray.put(obj)
            }
            formattedJson = jsonArray.toString(2)
        } else {
            rideCount = 0
            val emptyObj = JSONObject().apply {
                put("status", "Aguardando capturar corridas do inDrive")
                put("mensagem", "Abra o aplicativo inDrive lado a lado. O leitor de tela extrairá as corridas automaticamente e disponibilizará o JSON aqui.")
                put("timestamp", System.currentTimeMillis())
            }
            formattedJson = emptyObj.toString(2)
        }

        val formattedHierarchy = com.uberanalyzer.model.AccessibilityHierarchyFormatter.formatJsonToHierarchy(formattedJson)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.app_background))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(2), getColor(R.color.app_accent))
            }
        }

        // Header Title Bar
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        val titleView = TextView(this).apply {
            text = "📜 Hierarquia de Nós de Acessibilidade [$rideCount]"
            textSize = 15f
            setTextColor(getColor(R.color.app_text))
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        val closeX = TextView(this).apply {
            text = " ✖ "
            textSize = 18f
            setTextColor(getColor(R.color.app_secondary))
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { activeJsonDialog?.dismiss() }
        }

        titleRow.addView(titleView)
        titleRow.addView(closeX)
        container.addView(titleRow)

        // Subtitle Info
        val subText = TextView(this).apply {
            text = if (rideCount > 0) "Estrutura hierárquica organizada dos nós de acessibilidade da tela:" else "Nenhum nó capturado no momento. A hierarquia será atualizada ao vigiar a tela do inDrive."
            textSize = 12f
            setTextColor(getColor(R.color.app_secondary))
            setPadding(0, 0, 0, dp(10))
        }
        container.addView(subText)

        // Hierarchy Scroll View
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(280))
            background = GradientDrawable().apply {
                setColor(getColor(R.color.app_input))
                cornerRadius = dp(8).toFloat()
                setStroke(dp(1), getColor(R.color.app_surface))
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val jsonTextView = TextView(this).apply {
            text = formattedHierarchy
            textSize = 11f
            setTextColor(getColor(R.color.app_accent))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        scrollView.addView(jsonTextView)
        container.addView(scrollView)

        // Actions Bar
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }

        val copyBtn = Button(this).apply {
            text = "📋 Copiar Hierarquia"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0284C7"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(6), 0) }
            setOnClickListener {
                val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipManager.setPrimaryClip(ClipData.newPlainText("inDrive_Accessibility_Hierarchy", formattedHierarchy))
                Toast.makeText(this@MainActivity, "Hierarquia de acessibilidade copiada!", Toast.LENGTH_SHORT).show()
            }
        }

        val floatBtn = Button(this).apply {
            text = "🪟 Flutuar na Tela"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#8B5CF6"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(0, 0, dp(6), 0) }
            setOnClickListener {
                if (isOverlayPermissionGranted()) {
                    val intent = Intent(this@MainActivity, com.uberanalyzer.overlay.OverlayService::class.java).apply {
                        putExtra(com.uberanalyzer.overlay.OverlayService.EXTRA_JSON_PAYLOAD, formattedJson)
                        putExtra(com.uberanalyzer.overlay.OverlayService.EXTRA_RIDE_COUNT, rideCount)
                        if (currentActiveRoutes.isNotEmpty()) {
                            val first = currentActiveRoutes[0]
                            putExtra(com.uberanalyzer.overlay.OverlayService.EXTRA_PRICE, first.price)
                            putExtra(com.uberanalyzer.overlay.OverlayService.EXTRA_DISTANCE, first.distanceKm)
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    Toast.makeText(this@MainActivity, "Modal flutuante de acessibilidade ativado na tela!", Toast.LENGTH_SHORT).show()
                    activeJsonDialog?.dismiss()
                } else {
                    Toast.makeText(this@MainActivity, "Autorize a permissão de Janela Flutuante no Checklist de Permissões!", Toast.LENGTH_LONG).show()
                    showPermissionChecklistDialog()
                }
            }
        }

        val closeBtn = Button(this).apply {
            text = "Fechar"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#475569"))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(-2, -2)
            setOnClickListener { activeJsonDialog?.dismiss() }
        }

        actionRow.addView(copyBtn)
        actionRow.addView(floatBtn)
        actionRow.addView(closeBtn)
        container.addView(actionRow)

        activeJsonDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(container)
            .setCancelable(true)
            .show()
    }
}
