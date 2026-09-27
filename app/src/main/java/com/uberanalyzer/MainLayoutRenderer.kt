package com.uberanalyzer

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.webkit.WebView

/** Read-only snapshot of the main screen. Business state remains owned by MainActivity. */
data class MainUiState(
    val layoutId: String,
    val version: String,
    val autoHideEnabled: Boolean,
    val addressFilterEnabled: Boolean,
    val selectedAddress: String,
    val cardsMinimized: Boolean
)

/** User intents emitted by layout renderers; these do not implement filtering or map logic. */
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
    fun render(
        context: Context,
        state: MainUiState,
        controls: MainUiControls,
        map: WebView,
        callbacks: MainUiCallbacks
    ): RenderedMainLayout
}

object MainLayoutRenderers {
    fun forId(id: String): MainLayoutRenderer = when (LayoutId.normalize(id)) {
        LayoutId.COCKPIT -> CockpitLayoutRenderer
        LayoutId.FLOW -> FlowLayoutRenderer
        else -> AuroraLayoutRenderer
    }
}

private object AuroraLayoutRenderer : MainLayoutRenderer {
    override fun render(context: Context, state: MainUiState, controls: MainUiControls, map: WebView, callbacks: MainUiCallbacks): RenderedMainLayout {
        val p = UiDesign.paletteFor(context, LayoutId.AURORA)
        val root = Layouts.column(context, p.background)
        val top = Layouts.topBar(context, p, controls, state, callbacks, "AURORA", "Mapa e oportunidades")
        root.addView(top)
        val mapFrame = FrameLayout(context).apply {
            background = UiDesign.rounded(context, p.raisedSurface, 18, p.outline)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(Layouts.dp(context, 12), Layouts.dp(context, 8), Layouts.dp(context, 12), 0) }
        }
        mapFrame.addView(map, FrameLayout.LayoutParams(-1, -1))
        root.addView(mapFrame)
        root.addView(Layouts.cardDock(context, p, state, controls, callbacks, vertical = false, title = "CORRIDAS EM FOCO"), LinearLayout.LayoutParams(-1, -2))
        return RenderedMainLayout(root, root.findViewWithTag<LinearLayout>("ride_cards")!!)
    }
}

private object CockpitLayoutRenderer : MainLayoutRenderer {
    override fun render(context: Context, state: MainUiState, controls: MainUiControls, map: WebView, callbacks: MainUiCallbacks): RenderedMainLayout {
        val p = UiDesign.paletteFor(context, LayoutId.COCKPIT)
        val root = Layouts.column(context, p.background)
        root.addView(Layouts.topBar(context, p, controls, state, callbacks, "COCKPIT", "Leitura instantânea"))
        val telemetry = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Layouts.dp(context, 14), Layouts.dp(context, 7), Layouts.dp(context, 14), Layouts.dp(context, 7))
            background = UiDesign.rounded(context, p.surface, 14, p.outline)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(Layouts.dp(context, 12), Layouts.dp(context, 6), Layouts.dp(context, 12), 0) }
        }
        telemetry.addView(Layouts.label(context, p, "FILTROS DE VIAGEM", 10f))
        val switches = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        listOf(controls.autoHide, controls.addressFilter).forEachIndexed { i, control ->
            (control.parent as? ViewGroup)?.removeView(control)
            switches.addView(control, LinearLayout.LayoutParams(0, Layouts.dp(context, 54), if (i == 0) 1f else 1.4f))
        }
        telemetry.addView(switches)
        root.addView(telemetry)
        val mapFrame = FrameLayout(context).apply {
            background = UiDesign.rounded(context, p.raisedSurface, 12, p.outline); clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(-1, 0, .32f).apply { setMargins(Layouts.dp(context, 12), Layouts.dp(context, 8), Layouts.dp(context, 12), 0) }
        }
        mapFrame.addView(map, FrameLayout.LayoutParams(-1, -1))
        root.addView(mapFrame)
        root.addView(Layouts.cardDock(context, p, state, controls, callbacks, vertical = false, title = "PRÓXIMAS OPORTUNIDADES"), LinearLayout.LayoutParams(-1, 0, .68f))
        return RenderedMainLayout(root, root.findViewWithTag<LinearLayout>("ride_cards")!!)
    }
}

private object FlowLayoutRenderer : MainLayoutRenderer {
    override fun render(context: Context, state: MainUiState, controls: MainUiControls, map: WebView, callbacks: MainUiCallbacks): RenderedMainLayout {
        val p = UiDesign.paletteFor(context, LayoutId.FLOW)
        val root = Layouts.column(context, p.background)
        root.addView(Layouts.topBar(context, p, controls, state, callbacks, "FLOW", "Fila organizada"))
        val listPanel = Layouts.cardDock(context, p, state, controls, callbacks, vertical = true, title = "RANKING DE CORRIDAS")
        root.addView(listPanel, LinearLayout.LayoutParams(-1, 0, .60f))
        val mapHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Layouts.dp(context, 14), Layouts.dp(context, 7), Layouts.dp(context, 14), Layouts.dp(context, 7))
            background = UiDesign.rounded(context, p.surface, 12, p.outline)
            layoutParams = LinearLayout.LayoutParams(-1, 0, .40f).apply { setMargins(Layouts.dp(context, 12), Layouts.dp(context, 7), Layouts.dp(context, 12), Layouts.dp(context, 10)) }
        }
        mapHeader.addView(Layouts.label(context, p, "MAPA CONTEXTUAL", 11f), LinearLayout.LayoutParams(0, -2, 1f))
        val frame = FrameLayout(context).apply { clipToOutline = true; background = UiDesign.rounded(context, p.raisedSurface, 10, p.outline) }
        frame.addView(map, FrameLayout.LayoutParams(-1, -1))
        mapHeader.addView(frame, LinearLayout.LayoutParams(Layouts.dp(context, 164), -1))
        root.addView(mapHeader)
        return RenderedMainLayout(root, root.findViewWithTag<LinearLayout>("ride_cards")!!)
    }
}

private object Layouts {
    fun dp(context: Context, n: Int) = (n * context.resources.displayMetrics.density).toInt()
    fun column(context: Context, color: Int) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(color); layoutParams = LinearLayout.LayoutParams(-1, -1) }
    fun label(context: Context, p: UiDesign.Palette, text: String, size: Float) = TextView(context).apply { this.text = text; setTextColor(p.secondary); textSize = size; typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER_VERTICAL }

    fun topBar(context: Context, p: UiDesign.Palette, c: MainUiControls, s: MainUiState, callbacks: MainUiCallbacks, eyebrow: String, subtitle: String): LinearLayout {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 8))
            background = UiDesign.rounded(context, p.surface, p.radiusDp, p.outline)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(context, 8), dp(context, 8), dp(context, 8), 0) }
        }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        titles.addView(label(context, p, eyebrow, 10f))
        c.title.text = s.version
        c.title.setTextColor(p.text); c.title.textSize = 19f; c.title.typeface = android.graphics.Typeface.DEFAULT_BOLD
        titles.addView(c.title)
        head.addView(titles)
        styleAction(context, p, c.layoutButton, "Layout", "Escolher experiência visual")
        styleAction(context, p, c.settingsButton, "Ajustes", "Abrir configurações")
        c.layoutButton.setOnClickListener { callbacks.onChooseLayout() }
        c.settingsButton.setOnClickListener { callbacks.onOpenSettings() }
        head.addView(c.layoutButton, LinearLayout.LayoutParams(dp(context, 68), dp(context, 48)).apply { marginEnd = dp(context, 5) })
        head.addView(c.settingsButton, LinearLayout.LayoutParams(dp(context, 68), dp(context, 48)))
        root.addView(head)
        root.addView(label(context, p, subtitle + if (s.selectedAddress.isNotBlank() && s.addressFilterEnabled) " · ${s.selectedAddress}" else "", 12f).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf(c.autoHide, c.addressFilter).forEachIndexed { i, view ->
            (view.parent as? ViewGroup)?.removeView(view)
            controls.addView(view, LinearLayout.LayoutParams(0, dp(context, 52), if (i == 0) 1f else 1.4f))
        }
        root.addView(controls)
        val permission = c.permissionButton
        (permission.parent as? ViewGroup)?.removeView(permission)
        root.addView(permission, LinearLayout.LayoutParams(-1, dp(context, 44)))
        return root
    }

    private fun styleAction(context: Context, p: UiDesign.Palette, view: View, text: String, description: String) {
        (view as? Button)?.apply { this.text = text; textSize = 12f; setTextColor(p.accent); background = UiDesign.rounded(context, p.raisedSurface, 12, p.outline); minWidth = 0; minimumWidth = 0; contentDescription = description }
    }

    fun cardDock(context: Context, p: UiDesign.Palette, s: MainUiState, c: MainUiControls, callbacks: MainUiCallbacks, vertical: Boolean, title: String): LinearLayout {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(context, 12), dp(context, 9), dp(context, 12), dp(context, 9))
            background = UiDesign.rounded(context, p.surface, p.radiusDp, p.outline)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 9)) }
        }
        val heading = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heading.addView(label(context, p, title, 11f), LinearLayout.LayoutParams(0, -2, 1f))
        c.cardsToggle.text = if (s.cardsMinimized) "Expandir" else "Recolher"
        c.cardsToggle.textSize = 11f; c.cardsToggle.minHeight = dp(context, 44); c.cardsToggle.setTextColor(p.accent)
        c.cardsToggle.background = UiDesign.rounded(context, p.raisedSurface, 10, p.outline)
        c.cardsToggle.setOnClickListener { callbacks.onToggleCards() }
        heading.addView(c.cardsToggle, LinearLayout.LayoutParams(-2, dp(context, 44)))
        panel.addView(heading)
        val holder = if (vertical) ScrollView(context).apply { isVerticalScrollBarEnabled = false } else HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        val cards = LinearLayout(context).apply { orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL; tag = "ride_cards" }
        if (holder is ScrollView) holder.addView(cards, LinearLayout.LayoutParams(-1, -2)) else (holder as HorizontalScrollView).addView(cards)
        holder.layoutParams = LinearLayout.LayoutParams(-1, if (s.cardsMinimized) dp(context, 58) else dp(context, if (vertical) 210 else 190))
        panel.addView(holder)
        return panel
    }
}
