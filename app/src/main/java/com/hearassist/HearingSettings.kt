package com.hearassist

import android.content.Context
import android.content.SharedPreferences

/** User settings, stored in SharedPreferences so the widget, the service and the screen share them. */
object HearingSettings {

    /** Centre frequencies of the equalizer bands (Hz), one octave apart. */
    val BAND_FREQS = floatArrayOf(250f, 500f, 1000f, 2000f, 4000f, 8000f)
    val BAND_LABELS = arrayOf("250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz")

    const val BAND_MIN_DB = -12
    const val BAND_MAX_DB = 15
    const val GAIN_MAX_DB = 30
    const val LIMIT_MIN_DB = -24

    private const val FILE = "hearing_settings"
    private const val KEY_GAIN = "gain_db"
    private const val KEY_LIMIT = "limit_db"
    private const val KEY_BAND = "band_db_"

    /** Built-in presets: dB per band, in BAND_FREQS order. */
    val PRESETS: LinkedHashMap<String, IntArray> = linkedMapOf(
        "Flat" to intArrayOf(0, 0, 0, 0, 0, 0),
        "Speech" to intArrayOf(-3, 0, 3, 6, 8, 5),
        "Treble boost" to intArrayOf(0, 0, 2, 6, 10, 12),
        "Less rumble" to intArrayOf(-10, -5, 0, 2, 3, 3),
    )

    class Params(val bandsDb: FloatArray, val gainDb: Float, val limitDb: Float)

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): Params {
        val p = prefs(ctx)
        val bands = FloatArray(BAND_FREQS.size) { p.getInt(KEY_BAND + it, PRESETS.getValue("Speech")[it]).toFloat() }
        return Params(bands, p.getInt(KEY_GAIN, 12).toFloat(), p.getInt(KEY_LIMIT, -6).toFloat())
    }

    fun bandDb(ctx: Context, i: Int) = load(ctx).bandsDb[i].toInt()
    fun setBand(ctx: Context, i: Int, db: Int) = prefs(ctx).edit().putInt(KEY_BAND + i, db).apply()
    fun setGain(ctx: Context, db: Int) = prefs(ctx).edit().putInt(KEY_GAIN, db).apply()
    fun setLimit(ctx: Context, db: Int) = prefs(ctx).edit().putInt(KEY_LIMIT, db).apply()

    fun applyPreset(ctx: Context, name: String) {
        val values = PRESETS[name] ?: return
        val e = prefs(ctx).edit()
        values.forEachIndexed { i, db -> e.putInt(KEY_BAND + i, db) }
        e.apply()
    }
}
