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
import androidx.appcompat.app.AppCompatActivity
import com.uberanalyzer.settings.SettingsManager

class SettingsActivity : ThemedActivity() {

    private lateinit var settings: SettingsManager
    private val selectedColors = mutableMapOf<String, String>()
    
    // Paleta de cores recomendadas (Vibrantes e Scannable)
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

    private fun buildUI(): ScrollView {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        val visual = UiDesign.palette(this@SettingsActivity)
        val scrollView = ScrollView(this).apply { setBackgroundColor(visual.background); isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }

        root.addView(TextView(this).apply { text = "Configurações do motorista"; setTextColor(visual.text); textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(8)) })
        root.addView(TextView(this).apply {
            text = "Escolha uma proposta visual completa e compare cores, botões, cards e organização da tela principal."
            setTextColor(visual.secondary)
            textSize = 14f
            setPadding(0, 0, 0, dp(14))
        })
        root.addView(Button(this).apply {
            text = "🎨 Comparar as 3 interfaces"
            textSize = 16f
            setTextColor(visual.background)
            background = UiDesign.rounded(this@SettingsActivity, visual.accent, visual.radiusDp)
            layoutParams = LinearLayout.LayoutParams(-1, dp(56)).apply { bottomMargin = dp(18) }
            setOnClickListener { showDesignChooser() }
        })

        // Thresholds
        root.addView(Button(this).apply {
            text = "⬇️ Atualizar aplicativo"
            textSize = 15f
            setTextColor(visual.accent)
            background = UiDesign.rounded(this@SettingsActivity, visual.surface, visual.radiusDp, visual.outline)
            layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(8) }
            setOnClickListener {
                startActivity(android.content.Intent(this@SettingsActivity, AppUpdateActivity::class.java))
            }
        })

        root.addView(createLabel("Meta R$ / KM (ex: 2.0)"))
        val kmInput = createEditText(settings.getMinKmValue().toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        root.addView(kmInput)

        val autoHideCheck = CheckBox(this).apply {
            text = "⚡ Ativar Auto-Ocultar automático para viagens abaixo do R$/km mínimo"
            setTextColor(visual.text)
            textSize = 14f
            isChecked = settings.getAutoHideEnabled()
            buttonTintList = android.content.res.ColorStateList.valueOf(visual.accent)
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(autoHideCheck)

        val confirmHideCheck = CheckBox(this).apply {
            text = "Pedir confirmação ao ocultar manualmente viagens com valor R$/km abaixo da meta"
            setTextColor(UiDesign.palette(this@SettingsActivity).text)
            textSize = 14f
            isChecked = settings.getConfirmHideBelowMinKm()
            buttonTintList = android.content.res.ColorStateList.valueOf(visual.accent)
            setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(confirmHideCheck)

        root.addView(createLabel("Meta R$ / Hora (ex: 45.0)"))
        val hourInput = createEditText(settings.getMinHourValue().toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        root.addView(hourInput)

        root.addView(createLabel("🔔 Alerta Sonoro de Boa Lucratividade (R$/KM Mínimo, ex: 4.0)"))
        val highProfitInput = createEditText(settings.getHighProfitAlertKm().toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        root.addView(highProfitInput)

        // Category Colors
        root.addView(TextView(this).apply { text = "Cores das Categorias"; setTextColor(UiDesign.palette(this@SettingsActivity).text); textSize = 18f; setPadding(0, dp(30), 0, dp(10)) })
        
        root.addView(createColorPickerSection("Uber X", SettingsManager.KEY_COLOR_UBER_X, SettingsManager.DEFAULT_UBER_X_COLOR, dp))
        root.addView(createColorPickerSection("Comfort", SettingsManager.KEY_COLOR_COMFORT, SettingsManager.DEFAULT_COMFORT_COLOR, dp))
        root.addView(createColorPickerSection("Black", SettingsManager.KEY_COLOR_BLACK, SettingsManager.DEFAULT_BLACK_COLOR, dp))
        root.addView(createColorPickerSection("Flash", SettingsManager.KEY_COLOR_FLASH, SettingsManager.DEFAULT_FLASH_COLOR, dp))

        // Rating Colors
        root.addView(TextView(this).apply { text = "Cores das Avaliações (Bordas)"; setTextColor(UiDesign.palette(this@SettingsActivity).text); textSize = 18f; setPadding(0, dp(30), 0, dp(10)) })
        
        root.addView(createColorPickerSection("Excelente", SettingsManager.KEY_COLOR_EXCELLENT, SettingsManager.DEFAULT_EXCELLENT_COLOR, dp))
        root.addView(createColorPickerSection("Boa", SettingsManager.KEY_COLOR_GOOD, SettingsManager.DEFAULT_GOOD_COLOR, dp))
        root.addView(createColorPickerSection("Média/OK", SettingsManager.KEY_COLOR_AVERAGE, SettingsManager.DEFAULT_AVERAGE_COLOR, dp))
        root.addView(createColorPickerSection("Ruim", SettingsManager.KEY_COLOR_BAD, SettingsManager.DEFAULT_BAD_COLOR, dp))

        // Save Button
        val saveBtn = Button(this).apply {
            text = "SALVAR CONFIGURAÇÕES"; background = UiDesign.rounded(this@SettingsActivity, visual.accent, visual.radiusDp); setTextColor(visual.background)
            layoutParams = LinearLayout.LayoutParams(-1, dp(60)).apply { setMargins(0, dp(40), 0, dp(50)) }
            setOnClickListener {
                settings.setMinKmValue(kmInput.text.toString().toFloatOrNull() ?: 2.0f)
                settings.setAutoHideEnabled(autoHideCheck.isChecked)
                settings.setConfirmHideBelowMinKm(confirmHideCheck.isChecked)
                settings.setMinHourValue(hourInput.text.toString().toFloatOrNull() ?: 45.0f)
                settings.setHighProfitAlertKm(highProfitInput.text.toString().toFloatOrNull() ?: 4.0f)
                
                selectedColors.forEach { (key, color) ->
                    if (key.startsWith("rating_")) settings.setRatingColor(key, color)
                    else settings.setCategoryColor(key, color)
                }
                
                Toast.makeText(this@SettingsActivity, "Configurações Salvas!", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(saveBtn)

        scrollView.addView(root)
        return scrollView
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
                            getSharedPreferences("map_layout", MODE_PRIVATE).edit().putString("main_ui_layout", LayoutId.normalize(id)).apply()
                            Toast.makeText(this@SettingsActivity, "$name selecionada. A tela principal será atualizada ao voltar.", Toast.LENGTH_LONG).show()
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
                val size = dp(35)
                layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(0, 0, dp(12), 0) }
                
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
        this.text = text; setTextColor(UiDesign.palette(this@SettingsActivity).secondary); setPadding(0, 10, 0, 8); textSize = 15f
    }

    private fun createEditText(value: String, inputType: Int) = EditText(this).apply {
        setText(value); setTextColor(UiDesign.palette(this@SettingsActivity).text); setBackground(UiDesign.rounded(this@SettingsActivity, UiDesign.palette(this@SettingsActivity).input, UiDesign.palette(this@SettingsActivity).radiusDp, UiDesign.palette(this@SettingsActivity).outline))
        this.inputType = inputType; setPadding(25, 25, 25, 25)
    }
}
