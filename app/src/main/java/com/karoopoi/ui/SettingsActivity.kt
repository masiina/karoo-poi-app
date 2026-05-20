package com.karoopoi.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.karoopoi.R
import com.karoopoi.prefs.PoiPreferences
import com.karoopoi.prefs.PoiPreferencesImpl
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: PoiPreferencesImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = PoiPreferences.getInstance(this)

        bindSwitch(
            switch = findViewById(R.id.swim_switch),
            flow = prefs.categorySwimming,
            setter = { prefs.setSwimming(it) }
        )
        bindSwitch(
            switch = findViewById(R.id.beach_switch),
            flow = prefs.categoryBeach,
            setter = { prefs.setBeach(it) }
        )
        bindSwitch(
            switch = findViewById(R.id.supermarket_switch),
            flow = prefs.categorySupermarket,
            setter = { prefs.setSupermarket(it) }
        )
        bindSwitch(
            switch = findViewById(R.id.convenience_switch),
            flow = prefs.categoryConvenience,
            setter = { prefs.setConvenience(it) }
        )

        val thresholdSlider = findViewById<Slider>(R.id.threshold_slider)
        val thresholdValue = findViewById<TextView>(R.id.threshold_value)

        thresholdSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val intVal = slider.value.toInt()
                lifecycleScope.launch { prefs.setThreshold(intVal) }
            }
        })
        thresholdSlider.addOnChangeListener { _, value, _ ->
            thresholdValue.text = "${value.toInt()}m"
        }

        lifecycleScope.launch {
            prefs.thresholdMeters.collect {
                thresholdSlider.value = it.toFloat()
                thresholdValue.text = "${it}m"
            }
        }
    }

    private fun bindSwitch(
        switch: SwitchMaterial,
        flow: Flow<Boolean>,
        setter: suspend (Boolean) -> Unit
    ) {
        switch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch { setter(checked) }
        }
        lifecycleScope.launch {
            flow.collect { enabled ->
                switch.setOnCheckedChangeListener(null)
                switch.isChecked = enabled
                switch.setOnCheckedChangeListener { _, checked ->
                    lifecycleScope.launch { setter(checked) }
                }
            }
        }
    }
}