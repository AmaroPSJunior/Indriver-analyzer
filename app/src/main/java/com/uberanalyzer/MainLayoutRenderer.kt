package com.uberanalyzer

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/** One immutable presentation snapshot; MainActivity remains the owner of ride and map behavior. */
data class MainUiState(
    val layoutId: String,
    val version: String,
    val autoHideEnabled: Boolean,
    val addressFilterEnabled: Boolean,
    val selectedAddress: String,
    val cardsMinimized: Boolean,
    val rideCount: Int,
    val priorityPrice: Double?,
    val priorityPerKm: Double?,
    val priorityPerHour: Double?,
    val priorityDistanceKm: Double?,
    val priorityDurationMin: Int?,
    val priorityScore: Double?,
    val priorityDestination: String
)

interface MainUiCallbacks {
    fun onChooseLayout()
    fun onOpenSettings()
    fun onToggleCards()
}

data class MainUiControls(
    val title: TextView,
    val autoHide: View,
    val addressFilter: View,
    val layoutButton: View,
    val settingsButton: View,
    val permissionButton: View,
    val cardsToggle: Button
)

data class RenderedMainLayout(val root: ViewGroup, val cards: LinearLayout)

interface MainLayoutRenderer {
    fun render(context: Context, state: MainUiState, controls: MainUiControls, map: WebView, callbacks: MainUiCallbacks): RenderedMainLayout
}

object MainLayoutRenderers {
    fun forId(id: String): MainLayoutRenderer = when (LayoutId.normalize(id)) {
        LayoutId.COCKPIT -> CockpitLayoutRenderer
        LayoutId.FLOW -> FlowLayoutRenderer
        else -> AuroraLayoutRenderer
    }
}

/** Aurora layers an opportunity tray over a map canvas, similar to map-first mobility apps. */
private object AuroraLayoutRenderer : MainLayoutRenderer {
    override fun render(context: Context, state: MainUiState, controls: MainUiControls, map: WebView, callbacks: MainUiCallbacks): RenderedMainLayout {
        val p = UiDesign.paletteFor(context, LayoutId.AURORA)
        val root = FrameLayout(context).apply { setBackgroundColor(p.background) }
        val mapCanvas = FrameLayout(context).apply {
            background = UiDesign.rounded(context, p.raisedSurface, 0)
            addView(map, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(mapCanvas, FrameLayout.LayoutParams(-1, -1))

        val topStack = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 13), dp(context, 10), dp(context, 13), dp(context, 10))
            background = UiDesign.rounded(context, p.surface, 20, p.outline)
            elevation = dp(context, 8).toFloat()
        }
        val topLine = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topLine.addView(titleBlock(context, p, "AURORA", "${state.rideCount} oportunidades", state.version, "aurora_ride_count"), LinearLayout.LayoutParams(0, -2, 1f))
        action(context, p, controls.layoutButton, "Layout", callbacks::onChooseLayout)
        action(context, p, controls.settingsButton, "Ajustes", callbacks::onOpenSettings)
        topLine.addView(controls.layoutButton, LinearLayout.LayoutParams(dp(context, 64), dp(context, 46)).apply { marginEnd = dp(context, 5) })
        topLine.addView(controls.settingsButton, LinearLayout.LayoutParams(dp(context, 68), dp(context, 46)))
        topStack.addView(topLine)
        val filterRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        filterRow.addView(controls.autoHide, LinearLayout.LayoutParams(0, dp(context, 50), 1f))
        filterRow.addView(controls.addressFilter, LinearLayout.LayoutParams(0, dp(context, 50), 1.2f))
        topStack.addView(filterRow)
        if (controls.permissionButton.visibility == View.VISIBLE) topStack.addView(controls.permissionButton, LinearLayout.LayoutParams(-1, dp(context, 44)))
        root.addView(topStack, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(context, 10), dp(context, 9), dp(context, 10), 0) })

        val tray = opportunityTray(context, p, state, controls, callbacks, vertical = false, title = "Corridas próximas")
        root.addView(tray.first, FrameLayout.LayoutParams(-1, dp(context, if (state.cardsMinimized) 92 else 226), Gravity.BOTTOM).apply {
            setMargins(dp(context, 8), 0, dp(context, 8), dp(context, 9))
        })
        return RenderedMainLayout(root, tray.second)
    }
}

/** Cockpit prioritizes one clear decision, a vertical alternative queue and a small instrument map. */
private object CockpitLayoutRenderer : MainLayoutRenderer {
    override fun render(context: Context, state: MainUiState, controls: MainUiControls, map: WebView, callbacks: MainUiCallbacks): RenderedMainLayout {
        val p = UiDesign.paletteFor(context, LayoutId.COCKPIT)
        val root = column(context, p.background)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        }
        header.addView(titleBlock(context, p, "COCKPIT", "PAINEL DO MOTORISTA", state.version), LinearLayout.LayoutParams(0, -2, 1f))
        action(context, p, controls.layoutButton, "UI", callbacks::onChooseLayout)
        action(context, p, controls.settingsButton, "⋯", callbacks::onOpenSettings)
        header.addView(controls.layoutButton, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)).apply { marginEnd = dp(context, 6) })
        header.addView(controls.settingsButton, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)))
        root.addView(header)

        val decision = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(context, 17), dp(context, 12), dp(context, 17), dp(context, 12))
            background = UiDesign.rounded(context, p.surface, 16, p.outline)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(context, 12), dp(context, 2), dp(context, 12), dp(context, 7)) }
        }
        decision.addView(label(context, p, if (state.priorityPrice == null) "AGUARDANDO OFERTA" else "DECISÃO ATUAL · 1 DE ${state.rideCount}", 10f).apply { tag = "cockpit_ride_count" })
        val valueRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        valueRow.addView(TextView(context).apply {
            tag = "cockpit_price"
            text = state.priorityPrice?.let { String.format(Locale.getDefault(), "R$ %.2f", it) } ?: "—"
            setTextColor(p.text); textSize = 38f; typeface = Typeface.DEFAULT_BOLD; includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        valueRow.addView(TextView(context).apply {
            tag = "cockpit_per_km"
            text = state.priorityPerKm?.let { String.format(Locale.getDefault(), "R$ %.2f/km", it) } ?: "R$/km —"
            setTextColor(p.accent); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END
        })
        decision.addView(valueRow)
        val instrumentRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf(
            "${state.priorityDistanceKm?.let { String.format(Locale.getDefault(), "%.1f km", it) } ?: "— km"}",
            "${state.priorityDurationMin ?: "—"} min",
            state.priorityPerHour?.let { String.format(Locale.getDefault(), "R$ %.0f/h", it) } ?: "R$/h —",
            "Nota ${state.priorityScore?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"}"
        ).forEachIndexed { index, metric ->
            valueRowMetric(context, p, instrumentRow, metric, listOf("cockpit_distance", "cockpit_duration", "cockpit_per_hour", "cockpit_score")[index])
        }
        decision.addView(instrumentRow)
        decision.addView(TextView(context).apply {
            tag = "cockpit_destination"
            text = state.priorityDestination.ifBlank { "Destino ainda não identificado" }
            setTextColor(p.secondary); textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(context, 5), 0, 0)
        })
        root.addView(decision)

        val controlsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(context, 12), 0, dp(context, 12), 0) }
        controlsRow.addView(controls.autoHide, LinearLayout.LayoutParams(0, dp(context, 54), 1f))
        controlsRow.addView(controls.addressFilter, LinearLayout.LayoutParams(0, dp(context, 54), 1.2f))
        root.addView(controlsRow)
        if (controls.permissionButton.visibility == View.VISIBLE) root.addView(controls.permissionButton, LinearLayout.LayoutParams(-1, dp(context, 46)).apply { setMargins(dp(context, 12), 0, dp(context, 12), dp(context, 5)) })

        val routeTray = opportunityTray(context, p, state, controls, callbacks, vertical = true, title = "FILA CAPTURADA · ORDEM ATUAL")
        root.addView(routeTray.first, LinearLayout.LayoutParams(-1, 0, 1f))
        val mapStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = UiDesign.rounded(context, p.surface, 12, p.outline)
            setPadding(dp(context, 12), dp(context, 6), dp(context, 6), dp(context, 6))
            layoutParams = LinearLayout.LayoutParams(-1, dp(context, 124)).apply { setMargins(dp(context, 12), dp(context, 4), dp(context, 12), dp(context, 8)) }
        }
        mapStrip.addView(label(context, p, "ROTA", 10f), LinearLayout.LayoutParams(dp(context, 55), -2))
        val mapFrame = FrameLayout(context).apply { background = UiDesign.rounded(context, p.raisedSurface, 10, p.outline); clipToOutline = true }
        mapFrame.addView(map, FrameLayout.LayoutParams(-1, -1))
        mapStrip.addView(mapFrame, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(mapStrip)
        return RenderedMainLayout(root, routeTray.second)
    }
}

/** Flow is a sortable-feeling work queue: summary, comparison rows, then a map strip. */
private object FlowLayoutRenderer : MainLayoutRenderer {
    override fun render(context: Context, state: MainUiState, controls: MainUiControls, map: WebView, callbacks: MainUiCallbacks): RenderedMainLayout {
        val p = UiDesign.paletteFor(context, LayoutId.FLOW)
        val root = column(context, p.background)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 18), dp(context, 11), dp(context, 18), dp(context, 5))
        }
        header.addView(titleBlock(context, p, "FLOW / HOJE", "Fila de oportunidades", state.version), LinearLayout.LayoutParams(0, -2, 1f))
        action(context, p, controls.layoutButton, "Layouts", callbacks::onChooseLayout)
        action(context, p, controls.settingsButton, "Opções", callbacks::onOpenSettings)
        header.addView(controls.layoutButton, LinearLayout.LayoutParams(dp(context, 72), dp(context, 46)).apply { marginEnd = dp(context, 5) })
        header.addView(controls.settingsButton, LinearLayout.LayoutParams(dp(context, 72), dp(context, 46)))
        root.addView(header)

        val filters = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 3), dp(context, 12), dp(context, 3))
        }
        filters.addView(controls.autoHide, LinearLayout.LayoutParams(0, dp(context, 48), 1f))
        filters.addView(controls.addressFilter, LinearLayout.LayoutParams(0, dp(context, 48), 1.25f))
        if (controls.permissionButton.visibility == View.VISIBLE) filters.addView(controls.permissionButton, LinearLayout.LayoutParams(0, dp(context, 48), 1f))
        root.addView(filters)

        val summary = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; background = UiDesign.rounded(context, p.surface, 12, p.outline)
            setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(context, 12), dp(context, 2), dp(context, 12), dp(context, 7)) }
        }
        summaryMetric(context, p, summary, "NA FILA", state.rideCount.toString(), "flow_ride_count")
        summaryMetric(context, p, summary, "PRIMEIRO R$/KM", state.priorityPerKm?.let { String.format(Locale.getDefault(), "%.2f", it) } ?: "—", "flow_best_per_km")
        summaryMetric(context, p, summary, "PRIMEIRO VALOR", state.priorityPrice?.let { String.format(Locale.getDefault(), "R$ %.0f", it) } ?: "—", "flow_best_price")
        root.addView(summary)

        val listPanel = opportunityTray(context, p, state, controls, callbacks, vertical = true, title = "Comparação · ordem recebida")
        root.addView(listPanel.first, LinearLayout.LayoutParams(-1, 0, 1f))

        val mapHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 5), dp(context, 7), dp(context, 5))
            background = UiDesign.rounded(context, p.surface, 12, p.outline)
            layoutParams = LinearLayout.LayoutParams(-1, dp(context, 130)).apply { setMargins(dp(context, 12), dp(context, 3), dp(context, 12), dp(context, 8)) }
        }
        mapHeader.addView(label(context, p, "ROTAS NO MAPA", 10f), LinearLayout.LayoutParams(dp(context, 98), -2))
        val frame = FrameLayout(context).apply { clipToOutline = true; background = UiDesign.rounded(context, p.raisedSurface, 9, p.outline) }
        frame.addView(map, FrameLayout.LayoutParams(-1, -1))
        mapHeader.addView(frame, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(mapHeader)
        return RenderedMainLayout(root, listPanel.second)
    }
}

private fun opportunityTray(
    context: Context,
    p: UiDesign.Palette,
    state: MainUiState,
    controls: MainUiControls,
    callbacks: MainUiCallbacks,
    vertical: Boolean,
    title: String
): Pair<LinearLayout, LinearLayout> {
    val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
        background = UiDesign.rounded(context, p.surface, p.radiusDp, p.outline)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 7)) }
    }
    val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    heading.addView(label(context, p, title, 11f), LinearLayout.LayoutParams(0, dp(context, 40), 1f))
    controls.cardsToggle.apply {
        text = if (state.cardsMinimized) "Expandir" else "Recolher"
        textSize = 11f; minHeight = dp(context, 42); setTextColor(p.accent)
        background = UiDesign.rounded(context, p.raisedSurface, 10, p.outline)
        setOnClickListener { callbacks.onToggleCards() }
    }
    heading.addView(controls.cardsToggle, LinearLayout.LayoutParams(-2, dp(context, 42)))
    panel.addView(heading)
    val scroll: ViewGroup = if (vertical) ScrollView(context).apply { isVerticalScrollBarEnabled = false } else HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
    val cards = LinearLayout(context).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; tag = "ride_cards" }
    if (scroll is ScrollView) scroll.addView(cards, LinearLayout.LayoutParams(-1, -2)) else (scroll as HorizontalScrollView).addView(cards)
    scroll.layoutParams = if (vertical && !state.cardsMinimized) {
        LinearLayout.LayoutParams(-1, 0, 1f)
    } else {
        LinearLayout.LayoutParams(-1, dp(context, if (state.cardsMinimized) 58 else 166))
    }
    panel.addView(scroll)
    return panel to cards
}

private fun titleBlock(context: Context, p: UiDesign.Palette, eyebrow: String, title: String, version: String, valueTag: String? = null): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    addView(label(context, p, eyebrow, 10f))
    addView(TextView(context).apply { tag = valueTag; text = title; setTextColor(p.text); textSize = 17f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
    addView(label(context, p, "inDrive Analyzer  ·  $version", 10f))
}

private fun action(context: Context, p: UiDesign.Palette, view: View, text: String, onClick: () -> Unit) {
    (view as? Button)?.apply {
        this.text = text; textSize = 11f; setTextColor(p.accent)
        background = UiDesign.rounded(context, p.raisedSurface, 11, p.outline)
        minimumWidth = 0; minWidth = 0; contentDescription = text
        setOnClickListener { onClick() }
    }
}

private fun valueRowMetric(context: Context, p: UiDesign.Palette, row: LinearLayout, value: String, tag: String) {
    row.addView(TextView(context).apply {
        this.tag = tag; text = value; textSize = 11f; setTextColor(p.secondary); gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, dp(context, 28), 1f)
    })
}

private fun summaryMetric(context: Context, p: UiDesign.Palette, row: LinearLayout, label: String, value: String, valueTag: String) {
    row.addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        addView(TextView(context).apply { text = label; setTextColor(p.secondary); textSize = 9f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1 })
        addView(TextView(context).apply { tag = valueTag; text = value; setTextColor(p.text); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1 })
    })
}

private fun column(context: Context, color: Int) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color); layoutParams = LinearLayout.LayoutParams(-1, -1) }
private fun label(context: Context, p: UiDesign.Palette, text: String, size: Float) = TextView(context).apply {
    this.text = text; setTextColor(p.secondary); textSize = size; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
}
private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
