package com.uberanalyzer

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

object LayoutId {
    const val AURORA = "aurora"
    const val COCKPIT = "cockpit"
    const val FLOW = "flow"
    val all = listOf(AURORA, COCKPIT, FLOW)

    fun normalize(raw: String?): String = when (raw?.lowercase()) {
        "classic", AURORA -> AURORA
        "driver", COCKPIT -> COCKPIT
        "queue", FLOW -> FLOW
        else -> AURORA
    }
}

object ThemeMode {
    fun isDark(mode: Int, systemDark: Boolean): Boolean = when (mode) {
        1 -> false
        2 -> true
        else -> systemDark
    }
}

/** Layout identity and brightness are resolved independently: each layout has a light and dark token set. */
object UiDesign {
    data class Palette(
        val background: Int, val surface: Int, val raisedSurface: Int, val accent: Int,
        val text: Int, val secondary: Int, val input: Int, val outline: Int,
        val success: Int, val warning: Int, val negative: Int, val radiusDp: Int
    )

    fun selectedId(context: Context): String {
        val prefs = context.getSharedPreferences("map_layout", Context.MODE_PRIVATE)
        val stored = prefs.getString("main_ui_layout", null)
        val resolved = LayoutId.normalize(stored)
        if (stored != resolved) prefs.edit().putString("main_ui_layout", resolved).apply()
        return resolved
    }

    fun palette(context: Context): Palette = paletteFor(context, selectedId(context))

    fun paletteFor(context: Context, rawId: String): Palette {
        val id = LayoutId.normalize(rawId)
        val mode = context.getSharedPreferences("uber_analyzer_prefs", Context.MODE_PRIVATE).getInt("theme_mode", -1)
        val systemDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val dark = ThemeMode.isDark(mode, systemDark)
        return when (id) {
            LayoutId.COCKPIT -> if (dark) Palette(Color.rgb(9, 17, 20), Color.rgb(15, 29, 32), Color.rgb(22, 43, 45), Color.rgb(45, 212, 191), Color.rgb(236, 253, 250), Color.rgb(161, 194, 190), Color.rgb(20, 39, 42), Color.rgb(45, 86, 84), Color.rgb(74, 222, 128), Color.rgb(251, 191, 36), Color.rgb(248, 113, 113), 14) else Palette(Color.rgb(235, 245, 243), Color.WHITE, Color.rgb(220, 239, 234), Color.rgb(15, 118, 110), Color.rgb(12, 35, 34), Color.rgb(68, 101, 97), Color.rgb(226, 240, 237), Color.rgb(180, 210, 203), Color.rgb(21, 128, 61), Color.rgb(161, 98, 7), Color.rgb(185, 28, 28), 14)
            LayoutId.FLOW -> if (dark) Palette(Color.rgb(15, 19, 27), Color.rgb(24, 31, 43), Color.rgb(34, 43, 58), Color.rgb(96, 165, 250), Color.rgb(241, 245, 249), Color.rgb(166, 180, 199), Color.rgb(31, 40, 54), Color.rgb(59, 73, 94), Color.rgb(74, 222, 128), Color.rgb(251, 191, 36), Color.rgb(248, 113, 113), 10) else Palette(Color.rgb(244, 247, 251), Color.WHITE, Color.rgb(232, 239, 247), Color.rgb(29, 78, 137), Color.rgb(22, 36, 54), Color.rgb(92, 107, 124), Color.rgb(236, 241, 247), Color.rgb(208, 218, 230), Color.rgb(21, 128, 61), Color.rgb(161, 98, 7), Color.rgb(185, 28, 28), 10)
            else -> if (dark) Palette(Color.rgb(9, 17, 31), Color.rgb(18, 30, 49), Color.rgb(27, 43, 66), Color.rgb(56, 189, 248), Color.rgb(241, 245, 249), Color.rgb(163, 180, 199), Color.rgb(25, 40, 61), Color.rgb(52, 75, 103), Color.rgb(74, 222, 128), Color.rgb(251, 191, 36), Color.rgb(248, 113, 113), 18) else Palette(Color.rgb(238, 246, 251), Color.WHITE, Color.rgb(224, 238, 248), Color.rgb(3, 105, 161), Color.rgb(17, 36, 56), Color.rgb(76, 99, 120), Color.rgb(230, 239, 247), Color.rgb(190, 210, 226), Color.rgb(21, 128, 61), Color.rgb(161, 98, 7), Color.rgb(185, 28, 28), 16)
        }
    }

    fun rounded(context: Context, color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusDp * context.resources.displayMetrics.density
        strokeColor?.let { setStroke((strokeDp * context.resources.displayMetrics.density).toInt(), it) }
    }

    /** A miniature composition preview mirrors the actual hierarchy of its layout. */
    fun layoutPreview(context: Context, rawId: String): View {
        val id = LayoutId.normalize(rawId); val p = paletteFor(context, id)
        val dp = { n: Int -> (n * context.resources.displayMetrics.density).toInt() }
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(7), dp(8), dp(7)); background = rounded(context, p.background, p.radiusDp, p.outline); layoutParams = LinearLayout.LayoutParams(-1, dp(148)) }
        fun block(label: String, color: Int, height: Int, weight: Float = 1f): TextView = TextView(context).apply {
            text = label; gravity = Gravity.CENTER; setTextColor(p.text); textSize = 9f; typeface = Typeface.DEFAULT_BOLD
            background = rounded(context, color, maxOf(5, p.radiusDp / 2))
            layoutParams = LinearLayout.LayoutParams(0, dp(height), weight).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
        }
        val toolbar = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; addView(block(id.uppercase(), p.surface, 20, 2f)); addView(block("Filtros", p.raisedSurface, 20)); addView(block("•••", p.raisedSurface, 20, .45f)) }
        root.addView(toolbar)
        when (id) {
            LayoutId.COCKPIT -> {
                root.addView(block("R$ 42,50     R$ 3,80/km", p.surface, 39))
                root.addView(block("MAPA · rota ativa", p.raisedSurface, 31))
                root.addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; addView(block("1 · 42,50", p.surface, 30)); addView(block("2 · 31,00", p.surface, 30)) })
            }
            LayoutId.FLOW -> {
                root.addView(block("#1   R$ 42,50   11,2 km   8,9", p.surface, 25))
                root.addView(block("#2   R$ 31,00    8,1 km   8,2", p.surface, 25))
                root.addView(block("#3   R$ 28,00    7,4 km   7,8", p.surface, 25))
                root.addView(block("MAPA CONTEXTUAL", p.raisedSurface, 30))
            }
            else -> {
                root.addView(block("PRIORIDADE  ·  R$ 42,50", p.surface, 32))
                root.addView(block("MAPA PRINCIPAL · rotas", p.raisedSurface, 43))
                root.addView(LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; addView(block("R$ 31,00", p.surface, 24)); addView(block("R$ 28,00", p.surface, 24)) })
            }
        }
        return root
    }

    fun applyWindow(activity: Activity) {
        val p = palette(activity); activity.window.statusBarColor = p.background; activity.window.navigationBarColor = p.background
        val light = Color.luminance(p.background) > .5f
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            var flags = activity.window.decorView.systemUiVisibility
            flags = if (light) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            activity.window.decorView.systemUiVisibility = flags
        }
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    }
}
