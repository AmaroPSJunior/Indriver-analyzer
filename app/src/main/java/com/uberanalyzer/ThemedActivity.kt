package com.uberanalyzer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.uberanalyzer.settings.SettingsManager

/** System appearance by default; an explicit user choice persists across launches. */
open class ThemedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = SettingsManager(this).getThemeMode()
        super.onCreate(savedInstanceState)
    }
}
