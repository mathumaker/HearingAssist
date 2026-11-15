package com.hearassist

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/** Settings screen: permission, on/off, amplification, equalizer, presets, safety limit. */
class MainActivity : Activity() {

    private lateinit var toggle: Button
    private lateinit var status: TextView
    private val bandBars = ArrayList<SeekBar>()
    private val bandLabels = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestPermissionsIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        HearingService.stateListener = { refreshState() }
        refreshState()
    }

    override fun onPause() {
        super.onPause()
        HearingService.stateListener = null
    }

    private fun requestPermissionsIfNeeded() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        refreshState()
    }

    private fun onTogglePressed() {
        if (HearingService.isRunning) {
            HearingService.stop(this)
            return
        }
        when (HearingService.checkReady(this)) {
            null -> HearingService.start(this)
            HearingService.Problem.NO_HEADSET ->
                Toast.makeText(this, "Plug in wired earphones first", Toast.LENGTH_SHORT).show()
            HearingService.Problem.NO_PERMISSION -> requestPermissionsIfNeeded()
        }
    }

    private fun refreshState() {
        val on = HearingService.isRunning
        toggle.text = if (on) "Turn OFF" else "Turn ON"
        status.text = when {
            on -> "● Listening — adjust the sliders while you listen"
            HearingService.checkReady(this) == HearingService.Problem.NO_PERMISSION -> "Microphone permission needed"
            HearingService.checkReady(this) == HearingService.Problem.NO_HEADSET -> "Plug in wired earphones to start"
            else -> "Off"
        }
    }

    // ---------- UI (built in code to keep the project small) ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        col.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(6), 0, dp(12)) }
        col.addView(status)
        toggle = Button(this).apply { textSize = 18f; setOnClickListener { onTogglePressed() } }
        col.addView(toggle, LinearLayout.LayoutParams(MATCH_PARENT, dp(64)))

        // Amplification
        col.addView(header("Amplification"))
        val ctx = this
        val p = HearingSettings.load(this)
        col.addView(slider(0, HearingSettings.GAIN_MAX_DB, p.gainDb.toInt(), { "Boost  +$it dB" }) {
            HearingSettings.setGain(ctx, it)
        })

        // Equalizer
        col.addView(header("Equalizer"))
        col.addView(hint("Raise the frequencies you find hard to hear. Speech clarity lives around 1–4 kHz."))
        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        HearingSettings.PRESETS.keys.forEach { name ->
            presets.addView(Button(this).apply {
                text = name; textSize = 12f; isAllCaps = false
                setOnClickListener { HearingSettings.applyPreset(ctx, name); syncBands() }
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        col.addView(presets)
        HearingSettings.BAND_FREQS.indices.forEach { i ->
            col.addView(slider(
                HearingSettings.BAND_MIN_DB, HearingSettings.BAND_MAX_DB, p.bandsDb[i].toInt(),
                { "${HearingSettings.BAND_LABELS[i]}   ${if (it > 0) "+" else ""}$it dB" },
                register = true,
            ) { HearingSettings.setBand(ctx, i, it) })
        }

        // Safety
        col.addView(header("Hearing protection"))
        col.addView(hint("A limiter keeps loud sounds (doors, dishes) from ever going above this level."))
        col.addView(slider(HearingSettings.LIMIT_MIN_DB, 0, p.limitDb.toInt(), { "Max loudness  $it dB" }) {
            HearingSettings.setLimit(ctx, it)
        })

        col.addView(header("Widget"))
        col.addView(hint(
            "Long-press your home screen → Widgets → HearAssist, and drop it on the home screen. " +
                "One tap turns hearing assist on, another turns it off. Settings here are remembered.\n\n" +
                "Start with your phone's media volume low and raise it slowly. Wired earphones only: " +
                "Bluetooth adds too much delay for live listening."
        ))
        return ScrollView(this).apply { addView(col) }
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(24), 0, dp(4))
    }

    private fun hint(t: String) = TextView(this).apply { text = t; textSize = 13f; alpha = 0.75f; setPadding(0, 0, 0, dp(8)) }

    /** Labelled SeekBar over [min]..[max]; [onChange] fires as the user drags (applied live). */
    private fun slider(
        min: Int, max: Int, value: Int, label: (Int) -> String,
        register: Boolean = false, onChange: (Int) -> Unit,
    ): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(2)) }
        val text = TextView(this).apply { textSize = 14f; this.text = label(value) }
        val bar = SeekBar(this).apply {
            this.max = max - min
            progress = value.coerceIn(min, max) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, prog: Int, fromUser: Boolean) {
                    text.text = label(prog + min)
                    if (fromUser) onChange(prog + min)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        if (register) { bandBars.add(bar); bandLabels.add(text) }
        box.addView(text)
        box.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        return box
    }

    /** After a preset: move the band sliders to the new values (fires label updates, not saves). */
    private fun syncBands() {
        bandBars.forEachIndexed { i, bar -> bar.progress = HearingSettings.bandDb(this, i) - HearingSettings.BAND_MIN_DB }
    }
}
