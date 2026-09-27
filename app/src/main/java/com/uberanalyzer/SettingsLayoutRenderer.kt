package com.uberanalyzer

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

data class SettingsUiParts(
    val title: View,
    val description: View,
    val layoutChooser: View,
    val updater: View,
    val decisionRules: View,
    val automation: View,
    val categoryColors: View,
    val ratingColors: View,
    val save: View
)

interface SettingsLayoutRenderer {
    fun render(context: Context, p: UiDesign.Palette, parts: SettingsUiParts): View
}

object SettingsLayoutRenderers {
    fun forId(id: String): SettingsLayoutRenderer = when (LayoutId.normalize(id)) {
        LayoutId.COCKPIT -> CockpitSettingsRenderer
        LayoutId.FLOW -> FlowSettingsRenderer
        else -> AuroraSettingsRenderer
    }
}

private object AuroraSettingsRenderer : SettingsLayoutRenderer {
    override fun render(context: Context, p: UiDesign.Palette, parts: SettingsUiParts): View {
        val page = page(context, p)
        val content = column(context, 18)
        val hero = card(context, p, 20, 18)
        hero.addView(kicker(context, p, "AURORA  /  CENTRAL DE CONTROLE"))
        hero.addView(parts.title)
        hero.addView(parts.description)
        hero.addView(parts.layoutChooser)
        content.addView(hero)
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(parts.updater, LinearLayout.LayoutParams(0, dp(context, 50), 1f))
        content.addView(actions)
        content.addView(section(context, p, "Critérios de rentabilidade", "Defina o piso de ganho antes de avaliar uma oferta.", parts.decisionRules))
        content.addView(section(context, p, "Automação", "Regras aplicadas durante a captura.", parts.automation))
        content.addView(section(context, p, "Categorias", "Identifique a classe do serviço no primeiro olhar.", parts.categoryColors))
        content.addView(section(context, p, "Qualidade do passageiro", "Personalize as cores de leitura da nota.", parts.ratingColors))
        content.addView(parts.save)
        page.addView(content)
        return page
    }
}

private object CockpitSettingsRenderer : SettingsLayoutRenderer {
    override fun render(context: Context, p: UiDesign.Palette, parts: SettingsUiParts): View {
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(p.background) }
        val top = card(context, p, 0, 15)
        top.addView(kicker(context, p, "COCKPIT  /  CONFIGURAÇÃO DO MOTORISTA"))
        top.addView(parts.title)
        top.addView(parts.description)
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(parts.layoutChooser, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(parts.updater, LinearLayout.LayoutParams(dp(context, 112), dp(context, 48)))
        top.addView(actions)
        root.addView(top)

        root.addView(kicker(context, p, "AJUSTE RÁPIDO"), LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 4))
        })
        val scroll = ScrollView(context).apply { isFillViewport = false; clipToPadding = false }
        val content = column(context, 14)
        content.addView(section(context, p, "Automação de pista", "Ative os filtros que podem agir automaticamente.", parts.automation))
        content.addView(section(context, p, "Metas de decisão", "R$/km, R$/hora e alerta de alta rentabilidade.", parts.decisionRules))
        content.addView(section(context, p, "Cores de serviço", "Uber X, Comfort, Black e Flash.", parts.categoryColors))
        content.addView(section(context, p, "Cores de avaliação", "Faixas visuais por nota do passageiro.", parts.ratingColors))
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(context).apply {
            setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
            setBackgroundColor(p.surface)
            elevation = dp(context, 8).toFloat()
        }
        footer.addView(parts.save, LinearLayout.LayoutParams(-1, dp(context, 56)))
        root.addView(footer)
        return root
    }
}

private object FlowSettingsRenderer : SettingsLayoutRenderer {
    override fun render(context: Context, p: UiDesign.Palette, parts: SettingsUiParts): View {
        val page = page(context, p)
        val content = column(context, 16)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 2), dp(context, 8), dp(context, 2), dp(context, 10))
        }
        val titleColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        titleColumn.addView(kicker(context, p, "FLOW  /  PREFERÊNCIAS"))
        titleColumn.addView(parts.title)
        header.addView(titleColumn)
        header.addView(parts.updater, LinearLayout.LayoutParams(dp(context, 116), dp(context, 48)))
        content.addView(header)
        content.addView(parts.description)
        content.addView(section(context, p, "Experiência", "Escolha a organização da tela principal.", parts.layoutChooser))
        content.addView(section(context, p, "Filtros e metas", "Organize sua régua de aceitação.", parts.decisionRules))
        content.addView(section(context, p, "Como agir", "Controle as ações automáticas e confirmações.", parts.automation))
        content.addView(section(context, p, "Legenda de categorias", "Cores associadas aos serviços.", parts.categoryColors))
        content.addView(section(context, p, "Legenda de avaliações", "Cores associadas à qualidade.", parts.ratingColors))
        content.addView(parts.save)
        page.addView(content)
        return page
    }
}

private fun page(context: Context, p: UiDesign.Palette): ScrollView = ScrollView(context).apply {
    setBackgroundColor(p.background); isFillViewport = true; clipToPadding = false
}

private fun column(context: Context, horizontalPadding: Int) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(context, horizontalPadding), dp(context, 12), dp(context, horizontalPadding), dp(context, 24))
}

private fun card(context: Context, p: UiDesign.Palette, margin: Int, padding: Int) = LinearLayout(context).apply {
    orientation = LinearLayout.VERTICAL; setPadding(dp(context, padding), dp(context, padding), dp(context, padding), dp(context, padding))
    background = UiDesign.rounded(context, p.surface, p.radiusDp, p.outline)
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(context, margin), 0, dp(context, 10)) }
}

private fun section(context: Context, p: UiDesign.Palette, heading: String, detail: String, child: View): LinearLayout = card(context, p, 8, 14).apply {
    addView(TextView(context).apply { text = heading; setTextColor(p.text); textSize = 16f; typeface = Typeface.DEFAULT_BOLD })
    addView(TextView(context).apply { text = detail; setTextColor(p.secondary); textSize = 12f; setPadding(0, dp(context, 3), 0, dp(context, 8)) })
    (child.parent as? ViewGroup)?.removeView(child)
    addView(child)
}

private fun kicker(context: Context, p: UiDesign.Palette, text: String) = TextView(context).apply {
    this.text = text; setTextColor(p.accent); textSize = 10f; typeface = Typeface.DEFAULT_BOLD
    setPadding(0, 0, 0, dp(context, 4))
}

private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
