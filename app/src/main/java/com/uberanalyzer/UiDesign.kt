package com.uberanalyzer

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.WindowManager
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** Color and surface tokens shared by the three comparable driver-oriented UI proposals. */
object UiDesign {
    data class Palette(
        val background: Int,
        val surface: Int,
        val raisedSurface: Int,
        val accent: Int,
        val text: Int,
        val secondary: Int,
        val input: Int,
        val outline: Int,
        val radiusDp: Int
    )

    fun selectedId(context: Context): String = context
        .getSharedPreferences("map_layout", Context.MODE_PRIVATE)
        .getString("main_ui_layout", "classic")
        ?.takeIf { it in listOf("classic", "driver", "queue") } ?: "classic"

    fun palette(context: Context): Palette = paletteFor(context, selectedId(context))

    fun paletteFor(context: Context, id: String): Palette {
        val base = paletteFor(id)
        val configuredMode = context.getSharedPreferences("uber_analyzer_prefs", Context.MODE_PRIVATE)
            .getInt("theme_mode", -1)
        val dark = when (configuredMode) {
            1 -> false
            2 -> true
            else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val accent = when (id) {
            "driver" -> if (dark) Color.rgb(45, 212, 191) else Color.rgb(15, 118, 110)
            "queue" -> if (dark) Color.rgb(96, 165, 250) else Color.rgb(21, 91, 158)
            else -> if (dark) Color.rgb(56, 189, 248) else Color.rgb(3, 105, 161)
        }
        return if (dark) base.copy(
            background = Color.rgb(9, 17, 31),
            surface = Color.rgb(18, 30, 49),
            raisedSurface = Color.rgb(27, 43, 66),
            text = Color.rgb(241, 245, 249),
            secondary = Color.rgb(163, 180, 199),
            input = Color.rgb(25, 40, 61),
            outline = Color.rgb(52, 75, 103),
            accent = accent
        ) else base.copy(
            background = Color.rgb(239, 246, 252),
            surface = Color.WHITE,
            raisedSurface = Color.rgb(225, 238, 248),
            text = Color.rgb(17, 36, 56),
            secondary = Color.rgb(76, 99, 120),
            input = Color.rgb(230, 239, 247),
            outline = Color.rgb(190, 210, 226),
            accent = accent
        )
    }

    fun paletteFor(id: String): Palette = when (id) {
        // Cabine: dark teal cockpit surfaces with large, calm controls.
        "driver" -> Palette(
            Color.rgb(7, 26, 28), Color.rgb(13, 43, 44), Color.rgb(21, 61, 61),
            Color.rgb(45, 212, 191), Color.rgb(240, 253, 250), Color.rgb(165, 205, 199),
            Color.rgb(20, 53, 54), Color.rgb(43, 105, 101), 18
        )
        // Fila: bright, high legibility, blue actions and white cards.
        "queue" -> Palette(
            Color.rgb(239, 246, 252), Color.WHITE, Color.rgb(225, 238, 248),
            Color.rgb(21, 91, 158), Color.rgb(17, 36, 56), Color.rgb(76, 99, 120),
            Color.rgb(230, 239, 247), Color.rgb(190, 210, 226), 12
        )
        // Aurora: deep graphite/navy with a clear cyan focus color.
        else -> Palette(
            Color.rgb(9, 17, 31), Color.rgb(18, 30, 49), Color.rgb(27, 43, 66),
            Color.rgb(56, 189, 248), Color.rgb(241, 245, 249), Color.rgb(163, 180, 199),
            Color.rgb(25, 40, 61), Color.rgb(52, 75, 103), 14
        )
    }

    fun rounded(context: Context, color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * context.resources.displayMetrics.density
            strokeColor?.let { setStroke((strokeDp * context.resources.displayMetrics.density).toInt(), it) }
        }

    /** A compact, structural preview so the choices can be compared before applying one. */
    fun layoutPreview(context: Context, id: String): View {
        val p = paletteFor(context, id)
        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = rounded(context, p.background, p.radiusDp, p.outline)
            layoutParams = LinearLayout.LayoutParams(-1, dp(144))
        }
        fun textBlock(label: String, fill: Int, foreground: Int, height: Int, width: Int = -1): TextView =
            TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(foreground)
                background = rounded(context, fill, maxOf(4, p.radiusDp / 2))
                layoutParams = LinearLayout.LayoutParams(width, dp(height)).apply {
                    setMargins(dp(2), dp(2), dp(2), dp(2))
                }
            }
        fun toolbar() {
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(textBlock("v1.0", p.surface, p.text, 24, 0).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(24), 1f)
                })
                addView(textBlock("🎨", p.raisedSurface, p.accent, 24, dp(34)))
                addView(textBlock("☰", p.raisedSurface, p.accent, 24, dp(34)))
            })
        }
        fun mapPanel(weight: Float) {
            root.addView(textBlock("🗺️ MAPA", p.raisedSurface, p.secondary, 0), LinearLayout.LayoutParams(-1, 0, weight))
        }
        fun cardRow(labels: List<String>, fullWidth: Boolean = false) {
            val row = LinearLayout(context).apply {
                orientation = if (fullWidth) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            }
            labels.forEach { label ->
                row.addView(textBlock(label, p.surface, p.accent, if (fullWidth) 20 else 28, if (fullWidth) -1 else 0),
                    if (fullWidth) LinearLayout.LayoutParams(-1, dp(22)) else LinearLayout.LayoutParams(0, dp(30), 1f))
            }
            root.addView(row)
        }
        toolbar()
        when (id) {
            "driver" -> {
                root.addView(textBlock("💰/km       📍 Endereço", p.surface, p.text, 27))
                mapPanel(1f)
                cardRow(listOf("R$ 42,50", "R$ 31,00"))
            }
            "queue" -> {
                cardRow(listOf("R$ 42,50 · topo", "R$ 31,00", "R$ 28,00"), fullWidth = true)
                mapPanel(1f)
            }
            else -> {
                root.addView(textBlock("💰/km       📍 Endereço", p.surface, p.text, 24))
                cardRow(listOf("R$ 42,50", "R$ 31,00"))
                mapPanel(1f)
            }
        }
        return root
    }

    fun applyWindow(activity: Activity) {
        val p = palette(activity)
        activity.window.statusBarColor = p.background
        activity.window.navigationBarColor = p.background
        val lightBars = Color.luminance(p.background) > 0.5f
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            var flags = activity.window.decorView.systemUiVisibility
            flags = if (lightBars) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
            activity.window.decorView.systemUiVisibility = flags
        }
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    }
}
