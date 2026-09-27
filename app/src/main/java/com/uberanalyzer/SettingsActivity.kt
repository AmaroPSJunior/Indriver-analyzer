package com.uberanalyzer

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.*
import com.uberanalyzer.settings.SettingsManager

class SettingsActivity : ThemedActivity() {

    private lateinit var settings: SettingsManager
    private val selectedColors = mutableMapOf<String, String>()
    private var persistForm: (() -> Unit)? = null
    
    // Accessible presets for service categories and passenger ratings.
    private val palette = listOf(
        "#F2121212", "#F21A237E", "#F2000000", "#F2E65100", 
        "#F21B5E20", "#F2311B92", "#F20D47A1", "#F2B71C1C",
        "#4CAF50", "#8BC34A", "#FFC107", "#F44336", "#00BCD4", "#9C27B0"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UiDesign.applyWindow(this)
        settings = SettingsManager(this)
        setContentView(buildUI())
    }

    private fun buildUI(): View {
        val dp = { value: Int -> (value * resources.displayMetrics.density).toInt() }
        val visual = UiDesign.palette(this)
        val currentLayout = UiDesign.selectedId(this)

        val title = TextView(this).apply {
            text = when (currentLayout) {
                LayoutId.COCKPIT -> "Ajustes do painel"
                LayoutId.FLOW -> "Preferências"
                else -> "Seu centro de operação"
            }
            setTextColor(visual.text); textSize = 25f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(4))
        }
        val description = TextView(this).apply {
            text = when (currentLayout) {
                LayoutId.COCKPIT -> "Defina os limites e alertas que orientam suas decisões na rua."
                LayoutId.FLOW -> "Ajuste critérios, automações e identificação visual da sua fila."
                else -> "Configure sua régua de ganhos e deixe as oportunidades mais fáceis de ler."
            }
            setTextColor(visual.secondary); textSize = 14f; setPadding(0, 0, 0, dp(12))
        }
        val layoutButton = Button(this).apply {
            text = "${currentLayout.uppercase()}  ·  Comparar interfaces"
            textSize = 13f; setTextColor(visual.background)
            background = UiDesign.rounded(this@SettingsActivity, visual.accent, visual.radiusDp)
            minHeight = dp(50); setOnClickListener { showDesignChooser() }
        }
        val updateButton = Button(this).apply {
            text = "Verificar atualizações"
            textSize = 13f; setTextColor(visual.accent)
            background = UiDesign.rounded(this@SettingsActivity, visual.raisedSurface, visual.radiusDp, visual.outline)
            setOnClickListener { startActivity(android.content.Intent(this@SettingsActivity, AppUpdateActivity::class.java)) }
        }

        val kmInput = createEditText(settings.getMinKmValue().toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val hourInput = createEditText(settings.getMinHourValue().toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val highProfitInput = createEditText(settings.getHighProfitAlertKm().toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val decisionRules = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(createLabel("Piso de ganho · R$/km")); addView(kmInput)
            addView(createLabel("Meta de produtividade · R$/hora")); addView(hourInput)
            addView(createLabel("Avisar a partir de · R$/km")); addView(highProfitInput)
        }
        val autoHideCheck = CheckBox(this).apply {
            text = "Ocultar automaticamente corridas abaixo do piso de R$/km"
            setTextColor(visual.text); textSize = 14f; isChecked = settings.getAutoHideEnabled()
            buttonTintList = android.content.res.ColorStateList.valueOf(visual.accent)
            minHeight = dp(52); setPadding(0, dp(5), 0, dp(5))
        }
        val confirmHideCheck = CheckBox(this).apply {
            text = "Pedir confirmação ao ocultar uma corrida abaixo da meta"
            setTextColor(visual.text); textSize = 14f; isChecked = settings.getConfirmHideBelowMinKm()
            buttonTintList = android.content.res.ColorStateList.valueOf(visual.accent)
            minHeight = dp(52); setPadding(0, dp(5), 0, dp(5))
        }
        val automation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(autoHideCheck); addView(confirmHideCheck)
        }
        val categoryColors = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(createColorPickerSection("Uber X", SettingsManager.KEY_COLOR_UBER_X, SettingsManager.DEFAULT_UBER_X_COLOR, dp))
            addView(createColorPickerSection("Comfort", SettingsManager.KEY_COLOR_COMFORT, SettingsManager.DEFAULT_COMFORT_COLOR, dp))
            addView(createColorPickerSection("Black", SettingsManager.KEY_COLOR_BLACK, SettingsManager.DEFAULT_BLACK_COLOR, dp))
            addView(createColorPickerSection("Flash", SettingsManager.KEY_COLOR_FLASH, SettingsManager.DEFAULT_FLASH_COLOR, dp))
        }
        val ratingColors = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(createColorPickerSection("Excelente", SettingsManager.KEY_COLOR_EXCELLENT, SettingsManager.DEFAULT_EXCELLENT_COLOR, dp))
            addView(createColorPickerSection("Boa", SettingsManager.KEY_COLOR_GOOD, SettingsManager.DEFAULT_GOOD_COLOR, dp))
            addView(createColorPickerSection("Média / OK", SettingsManager.KEY_COLOR_AVERAGE, SettingsManager.DEFAULT_AVERAGE_COLOR, dp))
            addView(createColorPickerSection("Ruim", SettingsManager.KEY_COLOR_BAD, SettingsManager.DEFAULT_BAD_COLOR, dp))
        }
        persistForm = {
            settings.setMinKmValue(kmInput.text.toString().toFloatOrNull() ?: 2.0f)
            settings.setAutoHideEnabled(autoHideCheck.isChecked)
            settings.setConfirmHideBelowMinKm(confirmHideCheck.isChecked)
            settings.setMinHourValue(hourInput.text.toString().toFloatOrNull() ?: 45.0f)
            settings.setHighProfitAlertKm(highProfitInput.text.toString().toFloatOrNull() ?: 4.0f)
            selectedColors.forEach { (key, color) ->
                if (key.startsWith("rating_")) settings.setRatingColor(key, color)
                else settings.setCategoryColor(key, color)
            }
        }
        val saveButton = Button(this).apply {
            text = "Salvar preferências"; textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            background = UiDesign.rounded(this@SettingsActivity, visual.accent, visual.radiusDp)
            setTextColor(visual.background); minHeight = dp(56)
            layoutParams = LinearLayout.LayoutParams(-1, dp(58)).apply { setMargins(0, dp(16), 0, dp(10)) }
            setOnClickListener {
                persistForm?.invoke()
                Toast.makeText(this@SettingsActivity, "Preferências salvas", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        return SettingsLayoutRenderers.forId(currentLayout).render(this, visual, SettingsUiParts(
            title, description, layoutButton, updateButton, decisionRules, automation,
            categoryColors, ratingColors, saveButton
        ))
    }

    private fun showDesignChooser() {
        val proposals = listOf(
            Triple(LayoutId.AURORA, "AURORA — Dashboard inteligente", "Mapa protagonista, oferta prioritária em destaque e indicadores de produtividade."),
            Triple(LayoutId.COCKPIT, "COCKPIT — Leitura instantânea", "Painel automotivo escuro, números grandes e decisão com poucos elementos."),
            Triple(LayoutId.FLOW, "FLOW — Fila organizada", "Ranking limpo com valores e métricas alinhados para comparar oportunidades.")
        )
        val selected = UiDesign.selectedId(this)
        val density = resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = ScrollView(this).apply { addView(list) }
        proposals.forEach { (id, name, description) ->
            val colors = UiDesign.paletteFor(this@SettingsActivity, id)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = UiDesign.rounded(this@SettingsActivity, colors.surface, colors.radiusDp, colors.outline)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            }
            card.addView(TextView(this).apply {
                text = if (id == selected) "✓ $name · ATUAL" else name
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(colors.text)
            })
            card.addView(TextView(this).apply {
                text = description
                textSize = 14f
                setTextColor(colors.secondary)
                setPadding(0, dp(5), 0, dp(7))
            })
            card.addView(UiDesign.layoutPreview(this@SettingsActivity, id))
            card.addView(Button(this).apply {
                text = if (id == selected) "Interface selecionada" else "Experimentar este layout"
                isEnabled = id != selected
                setTextColor(if (isEnabled) colors.background else colors.secondary)
                background = UiDesign.rounded(this@SettingsActivity, colors.accent, colors.radiusDp)
                layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) }
                setOnClickListener {
                    if (id == selected) return@setOnClickListener
                    androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Aplicar $name?")
                        .setMessage("A tela principal será reorganizada e receberá outra identidade visual. Corridas, filtros e configurações ficam preservados.")
                        .setNegativeButton("Continuar comparando", null)
                        .setPositiveButton("Confirmar interface") { _, _ ->
                            persistForm?.invoke()
                            getSharedPreferences("map_layout", MODE_PRIVATE).edit().putString("main_ui_layout", LayoutId.normalize(id)).apply()
                            Toast.makeText(this@SettingsActivity, "$name selecionado.", Toast.LENGTH_SHORT).show()
                            recreate()
                        }
                        .show()
                }
            })
            list.addView(card)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Compare as propostas de interface")
            .setView(scroll)
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun createColorPickerSection(label: String, key: String, default: String, dp: (Int) -> Int): LinearLayout {
        val section = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
        section.addView(createLabel(label))
        
        val currentColor = settings.let { if (key.contains("rating")) it.getRatingColor(key, default) else it.getCategoryColor(key, default) }
        selectedColors[key] = currentColor

        val paletteContainer = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        
        palette.forEach { colorStr ->
            val colorView = View(this).apply {
                val size = dp(48)
                contentDescription = "Selecionar cor $colorStr para $label"
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(0, 0, dp(8), 0) }
                
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(colorStr))
                    if (colorStr.lowercase() == selectedColors[key]?.lowercase()) {
                        setStroke(dp(3), Color.WHITE)
                    } else {
                        setStroke(dp(1), Color.parseColor("#444444"))
                    }
                }
                
                setOnClickListener {
                    selectedColors[key] = colorStr
                    // Refresh current row
                    for (i in 0 until colorRow.childCount) {
                        val child = colorRow.getChildAt(i)
                        val childColor = palette[i]
                        child.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor(childColor))
                            if (childColor == colorStr) {
                                setStroke(dp(3), Color.WHITE)
                            } else {
                                setStroke(dp(1), Color.parseColor("#444444"))
                            }
                        }
                    }
                }
            }
            colorRow.addView(colorView)
        }
        
        paletteContainer.addView(colorRow)
        section.addView(paletteContainer)
        return section
    }

    private fun createLabel(text: String) = TextView(this).apply { 
        val density = resources.displayMetrics.density
        this.text = text; setTextColor(UiDesign.palette(this@SettingsActivity).secondary); setPadding(0, (8 * density).toInt(), 0, (6 * density).toInt()); textSize = 14f
    }

    private fun createEditText(value: String, inputType: Int) = EditText(this).apply {
        setText(value); setTextColor(UiDesign.palette(this@SettingsActivity).text); setBackground(UiDesign.rounded(this@SettingsActivity, UiDesign.palette(this@SettingsActivity).input, UiDesign.palette(this@SettingsActivity).radiusDp, UiDesign.palette(this@SettingsActivity).outline))
        val density = resources.displayMetrics.density
        this.inputType = inputType; setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
        minHeight = (52 * density).toInt(); textSize = 16f
    }
}
