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

class SettingsActivity : AppCompatActivity() {

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
        settings = SettingsManager(this)
        setContentView(buildUI())
    }

    private fun buildUI(): ScrollView {
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt() }
        val scrollView = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#121212")); isFillViewport = true }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }

        root.addView(TextView(this).apply { text = "Configurações de Análise"; setTextColor(Color.WHITE); textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setPadding(0, 0, 0, dp(25)) })

        // Thresholds
        root.addView(createLabel("Meta R$ / KM (ex: 2.0)"))
        val kmInput = createEditText(settings.getMinKmValue().toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        root.addView(kmInput)

        root.addView(createLabel("Meta R$ / Hora (ex: 45.0)"))
        val hourInput = createEditText(settings.getMinHourValue().toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        root.addView(hourInput)

        // Category Colors
        root.addView(TextView(this).apply { text = "Cores das Categorias"; setTextColor(Color.WHITE); textSize = 18f; setPadding(0, dp(30), 0, dp(10)) })
        
        root.addView(createColorPickerSection("Uber X", SettingsManager.KEY_COLOR_UBER_X, SettingsManager.DEFAULT_UBER_X_COLOR, dp))
        root.addView(createColorPickerSection("Comfort", SettingsManager.KEY_COLOR_COMFORT, SettingsManager.DEFAULT_COMFORT_COLOR, dp))
        root.addView(createColorPickerSection("Black", SettingsManager.KEY_COLOR_BLACK, SettingsManager.DEFAULT_BLACK_COLOR, dp))
        root.addView(createColorPickerSection("Flash", SettingsManager.KEY_COLOR_FLASH, SettingsManager.DEFAULT_FLASH_COLOR, dp))

        // Rating Colors
        root.addView(TextView(this).apply { text = "Cores das Avaliações (Bordas)"; setTextColor(Color.WHITE); textSize = 18f; setPadding(0, dp(30), 0, dp(10)) })
        
        root.addView(createColorPickerSection("Excelente", SettingsManager.KEY_COLOR_EXCELLENT, SettingsManager.DEFAULT_EXCELLENT_COLOR, dp))
        root.addView(createColorPickerSection("Boa", SettingsManager.KEY_COLOR_GOOD, SettingsManager.DEFAULT_GOOD_COLOR, dp))
        root.addView(createColorPickerSection("Média/OK", SettingsManager.KEY_COLOR_AVERAGE, SettingsManager.DEFAULT_AVERAGE_COLOR, dp))
        root.addView(createColorPickerSection("Ruim", SettingsManager.KEY_COLOR_BAD, SettingsManager.DEFAULT_BAD_COLOR, dp))

        // Save Button
        val saveBtn = Button(this).apply {
            text = "SALVAR CONFIGURAÇÕES"; setBackgroundColor(Color.parseColor("#4CAF50")); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-1, dp(60)).apply { setMargins(0, dp(40), 0, dp(50)) }
            setOnClickListener {
                settings.setMinKmValue(kmInput.text.toString().toFloatOrNull() ?: 2.0f)
                settings.setMinHourValue(hourInput.text.toString().toFloatOrNull() ?: 45.0f)
                
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
        this.text = text; setTextColor(Color.LTGRAY); setPadding(0, 10, 0, 8); textSize = 15f
    }

    private fun createEditText(value: String, inputType: Int) = EditText(this).apply {
        setText(value); setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2C2C2C"))
        this.inputType = inputType; setPadding(25, 25, 25, 25)
    }
}
