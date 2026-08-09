package com.uberanalyzer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.uberanalyzer.db.RideHistoryManager
import com.uberanalyzer.db.RideRecord
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class HistoryActivity : AppCompatActivity() {

    private lateinit var db: RideHistoryManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = RideHistoryManager(this)
        setContentView(buildUI())
    }

    private fun buildUI(): ScrollView {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        val scrollView = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#121212")); isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }

        val rides = db.getAllRides()

        // Header
        root.addView(TextView(this).apply { 
            text = "⚡ Histórico em JSON - inDriver"; setTextColor(Color.WHITE); textSize = 22f; typeface = Typeface.DEFAULT_BOLD 
            setPadding(0, 0, 0, dp(12))
        })

        if (rides.isEmpty()) {
            root.addView(TextView(this).apply { 
                text = "Nenhuma corrida registrada da lista de espera inDriver ainda."; setTextColor(Color.GRAY); textSize = 16f
                gravity = Gravity.CENTER; setPadding(0, dp(50), 0, 0)
            })
            scrollView.addView(root)
            return scrollView
        }

        // Export Full Session JSON Button
        val exportJsonBtn = Button(this).apply {
            text = "📋 COPIAR JSON COMPLETO DAS CORRIDAS (${rides.size})"
            setBackgroundColor(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { setMargins(0, 0, 0, dp(16)) }
            setOnClickListener { showFullJsonDialog(rides) }
        }
        root.addView(exportJsonBtn)

        // Analytics Card
        root.addView(buildAnalyticsCard(rides, dp))

        // List Header
        root.addView(TextView(this).apply { 
            text = "Últimas Corridas Capturadas"; setTextColor(Color.LTGRAY); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(10))
        })

        // Rides List
        val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        rides.forEach { ride ->
            root.addView(buildRideItem(ride, sdf, dp))
        }

        // Clear Button
        val clearBtn = Button(this).apply {
            text = "LIMPAR HISTÓRICO"; setBackgroundColor(Color.parseColor("#B71C1C")); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(30), 0, dp(30)) }
            setOnClickListener {
                db.clearHistory()
                recreate()
            }
        }
        root.addView(clearBtn)

        scrollView.addView(root)
        return scrollView
    }

    private fun buildAnalyticsCard(rides: List<RideRecord>, dp: (Int) -> Int): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply { setColor(Color.parseColor("#1E1E1E")); cornerRadius = dp(12).toFloat() }
        }

        val totalValue = rides.sumOf { it.price }
        val avgKm = rides.map { it.price / (if (it.distance > 0) it.distance else 1.0) }.average()
        val bestCategory = rides.groupBy { it.category }.maxByOrNull { it.value.size }?.key ?: "inDrive"

        card.addView(TextView(this).apply { text = "RESUMO GERAL IN-DRIVER"; setTextColor(Color.GRAY); textSize = 12f; typeface = Typeface.DEFAULT_BOLD })
        
        val statsGrid = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; weightSum = 3f; setPadding(0, dp(10), 0, 0) }
        
        statsGrid.addView(createStatItem("Total R$", String.format("%.2f", totalValue), 1f))
        statsGrid.addView(createStatItem("Média R$/KM", String.format("%.2f", avgKm), 1f))
        statsGrid.addView(createStatItem("Corridas", rides.size.toString(), 1f))

        card.addView(statsGrid)
        return card
    }

    private fun createStatItem(label: String, value: String, weight: Float) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, -2, weight)
        gravity = Gravity.CENTER
        addView(TextView(context).apply { text = label; setTextColor(Color.GRAY); textSize = 10f; gravity = Gravity.CENTER })
        addView(TextView(context).apply { text = value; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
    }

    private fun buildRideItem(ride: RideRecord, sdf: SimpleDateFormat, dp: (Int) -> Int): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) }
            background = GradientDrawable().apply { 
                setColor(Color.parseColor("#1A1A1A"))
                cornerRadius = dp(8).toFloat() 
                setStroke(dp(1), if (ride.score >= 8) Color.GREEN else if (ride.score >= 5) Color.YELLOW else Color.RED)
            }
        }

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val infoLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        infoLayout.addView(TextView(this).apply { text = "${ride.category} • ${sdf.format(Date(ride.timestamp))}"; setTextColor(Color.GRAY); textSize = 11f })
        infoLayout.addView(TextView(this).apply { text = "R$ ${String.format("%.2f", ride.price)} | ${ride.distance} KM"; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD })
        
        val scoreLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        scoreLayout.addView(TextView(this).apply { text = "NOTA"; setTextColor(Color.GRAY); textSize = 10f })
        scoreLayout.addView(TextView(this).apply { text = String.format("%.1f", ride.score); setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD })

        topRow.addView(infoLayout)
        topRow.addView(scoreLayout)
        item.addView(topRow)

        if (ride.lat != 0.0 && ride.lon != 0.0) {
            item.addView(TextView(this).apply {
                text = "📍 Ver no Mapa (${String.format("%.4f", ride.lat)}, ${String.format("%.4f", ride.lon)})"
                setTextColor(Color.parseColor("#38BDF8"))
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
                setOnClickListener {
                    try {
                        val uri = android.net.Uri.parse("geo:${ride.lat},${ride.lon}?q=${ride.lat},${ride.lon}")
                        startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                    } catch (e: Exception) {
                        Toast.makeText(context, "Não foi possível abrir o mapa", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        }

        // Raw text preview
        if (ride.rawText.isNotBlank()) {
            item.addView(TextView(this).apply {
                text = "📄 ${ride.rawText.take(90)}..."
                setTextColor(Color.parseColor("#818CF8"))
                textSize = 10f
                setPadding(0, dp(4), 0, 0)
            })
        }

        return item
    }

    private fun showFullJsonDialog(rides: List<RideRecord>) {
        val root = JSONObject()
        root.put("app", "inDriver Driver Analyzer")
        root.put("total_records", rides.size)
        
        val array = JSONArray()
        rides.forEach { r ->
            val item = JSONObject()
            item.put("id", r.id)
            item.put("timestamp", r.timestamp)
            item.put("price_brl", r.price)
            item.put("distance_km", r.distance)
            item.put("duration_min", r.time)
            item.put("category", r.category)
            item.put("score", r.score)
            item.put("lat", r.lat)
            item.put("lon", r.lon)
            item.put("raw_summary", r.rawText.take(120))
            array.put(item)
        }
        root.put("rides", array)

        val jsonFormatted = root.toString(2)

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("inDriver History JSON", jsonFormatted)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "📋 JSON de todo o histórico copiado para a área de transferência!", Toast.LENGTH_LONG).show()

        val tv = TextView(this).apply {
            text = jsonFormatted
            setTextColor(Color.parseColor("#22D3EE"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val sv = ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle("JSON do Histórico inDriver")
            .setView(sv)
            .setPositiveButton("Copiar Novamente") { _, _ ->
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Copiado!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Fechar", null)
            .show()
    }
}

