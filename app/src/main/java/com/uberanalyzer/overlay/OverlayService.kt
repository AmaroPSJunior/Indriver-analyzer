package com.uberanalyzer.overlay

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.uberanalyzer.model.ScoreRating
import java.util.Locale

class OverlayService : Service() {
    companion object {
        const val EXTRA_JSON_PAYLOAD = "extra_json_payload"
        const val EXTRA_RIDE_COUNT = "extra_ride_count"
        const val EXTRA_PRICE = "extra_price"
        const val EXTRA_DISTANCE = "extra_distance"
        const val EXTRA_CATEGORY = "extra_category"
        const val EXTRA_SCORE = "extra_score"
        const val EXTRA_RATING = "extra_rating"
        const val EXTRA_TIME = "extra_time"
    }

    private var wm: WindowManager? = null
    private var view: LinearLayout? = null
    private var autoDismissHandler: Handler? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        autoDismissHandler = Handler(Looper.getMainLooper())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("indrive_ovl", "inDriver Pop-up JSON", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            val n = NotificationCompat.Builder(this, "indrive_ovl")
                .setContentTitle("inDriver Analyzer Ativo")
                .setContentText("Capturando corridas da lista de espera")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(1005, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1005, n)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { 
            val rStr = it.getStringExtra(EXTRA_RATING) ?: "AVERAGE"
            val r = try { ScoreRating.valueOf(rStr) } catch (e: Exception) { ScoreRating.AVERAGE }
            showJsonPopup(it, r) 
        }
        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showJsonPopup(i: Intent, r: ScoreRating) {
        hide()
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        val screenWidth = resources.displayMetrics.widthPixels
        val popupWidth = (screenWidth * 0.90).toInt().coerceAtMost(dp(420))

        val wmParams = WindowManager.LayoutParams(
            popupWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(60)
        }

        val jsonText = i.getStringExtra(EXTRA_JSON_PAYLOAD) ?: createDefaultSampleJson(i)
        val rideCount = i.getIntExtra(EXTRA_RIDE_COUNT, 1)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A")) // Dark Navy Slate
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), Color.parseColor("#38BDF8")) // Cyan Accent border
            }
            elevation = dp(12).toFloat()
        }

        // --- Header Bar ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }

        val titleView = TextView(this).apply {
            text = "⚡ inDriver JSON Pop-up ($rideCount corridas)"
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        val closeBtn = TextView(this).apply {
            text = " ✖ "
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 18f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { hide() }
        }

        header.addView(titleView)
        header.addView(closeBtn)
        root.addView(header)

        // --- Stats Subheader ---
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(12)) }
        }

        val price = i.getDoubleExtra(EXTRA_PRICE, 0.0)
        val dist = i.getDoubleExtra(EXTRA_DISTANCE, 1.0)
        val pkm = if (dist > 0) price / dist else 0.0

        statsRow.addView(TextView(this).apply {
            text = String.format(Locale.getDefault(), "Destaque: R$ %.2f", price)
            setTextColor(Color.parseColor("#4ADE80")) // Light green
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        statsRow.addView(TextView(this).apply {
            text = String.format(Locale.getDefault(), "R$ %.2f/km", pkm)
            setTextColor(Color.parseColor("#38BDF8")) // Light cyan
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        })

        root.addView(statsRow)

        // --- JSON Scrollable View ---
        val jsonScrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(220))
            isFillViewport = true
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#020617")) // Deepest dark code canvas
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        val jsonTextView = TextView(this).apply {
            text = jsonText
            setTextColor(Color.parseColor("#22D3EE")) // Monospace Cyan JSON
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }

        jsonScrollView.addView(jsonTextView)
        root.addView(jsonScrollView)

        // --- Action Buttons Bar ---
        val actionsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, 0)
        }

        val copyBtn = Button(this).apply {
            text = "📋 Copiar JSON"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0284C7"))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(0, 0, dp(6), 0) }
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("inDriver JSON", jsonText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(applicationContext, "✅ JSON copiado para a área de transferência!", Toast.LENGTH_SHORT).show()
            }
        }

        val dismissBtn = Button(this).apply {
            text = "Fechar"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(6), 0, 0, 0) }
            setOnClickListener { hide() }
        }

        actionsRow.addView(copyBtn)
        actionsRow.addView(dismissBtn)
        root.addView(actionsRow)

        // Enable Dragging on top header
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = wmParams.x
                    initialY = wmParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    wmParams.x = initialX + (event.rawX - initialTouchX).toInt()
                    wmParams.y = initialY + (event.rawY - initialTouchY).toInt()
                    try { wm?.updateViewLayout(root, wmParams) } catch (e: Exception) {}
                    true
                }
                else -> false
            }
        }

        view = root
        try { wm?.addView(view, wmParams) } catch (e: Exception) {}


        // Auto hide after 25 seconds unless interacted
        autoDismissHandler?.removeCallbacksAndMessages(null)
        autoDismissHandler?.postDelayed({ hide() }, 25000)
    }

    private fun createDefaultSampleJson(i: Intent): String {
        val pr = i.getDoubleExtra(EXTRA_PRICE, 28.50)
        val dist = i.getDoubleExtra(EXTRA_DISTANCE, 7.2)
        val time = i.getIntExtra(EXTRA_TIME, 16)
        return """
        {
          "app": "inDriver Driver Analyzer",
          "captured_at": ${System.currentTimeMillis()},
          "total_rides_in_queue": 1,
          "rides": [
            {
              "id": "IND-${System.currentTimeMillis() % 10000}",
              "passenger": "Passageiro inDriver",
              "rating": "4.9",
              "price_brl": $pr,
              "pickup_address": "Rua das Flores, 120",
              "dropoff_address": "Av. Brasil, 450",
              "pickup_distance_km": 1.2,
              "trip_distance_km": $dist,
              "total_distance_km": ${dist + 1.2},
              "estimated_time_min": $time,
              "earnings_per_km_brl": ${String.format(Locale.US, "%.2f", pr / dist)},
              "earnings_per_hour_brl": ${String.format(Locale.US, "%.2f", pr / (time / 60.0))},
              "score": 8.5
            }
          ]
        }
        """.trimIndent()
    }

    private fun hide() {
        autoDismissHandler?.removeCallbacksAndMessages(null)
        try { 
            view?.let { 
                wm?.removeView(it)
                view = null 
            } 
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        hide()
    }
}

