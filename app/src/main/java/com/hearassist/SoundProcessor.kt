package com.hearassist

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * One second-order IIR filter section (RBJ "Audio EQ Cookbook"), transposed direct form II.
 * Changing coefficients keeps the filter state, so slider moves don't click.
 */
private class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
    private var a1 = 0f; private var a2 = 0f
    private var z1 = 0f; private var z2 = 0f

    fun setPeaking(fs: Float, freq: Float, q: Float, gainDb: Float) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * freq / fs
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val a0 = 1.0 + alpha / a
        set((1 + alpha * a) / a0, (-2 * c) / a0, (1 - alpha * a) / a0, (-2 * c) / a0, (1 - alpha / a) / a0)
    }

    fun setHighPass(fs: Float, freq: Float, q: Float) {
        val w0 = 2.0 * PI * freq / fs
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val a0 = 1.0 + alpha
        set((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, (-2 * c) / a0, (1 - alpha) / a0)
    }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na1: Double, na2: Double) {
        b0 = nb0.toFloat(); b1 = nb1.toFloat(); b2 = nb2.toFloat(); a1 = na1.toFloat(); a2 = na2.toFloat()
    }

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/**
 * Signal chain, run on the audio thread:
 * rumble filter (80 Hz high-pass) -> amplification -> 6-band equalizer -> safety limiter.
 *
 * Settings arrive from the UI thread through [setParams] and are picked up at the start of the
 * next block, so the audio thread never waits on a lock.
 */
class SoundProcessor(private val sampleRate: Int, initial: HearingSettings.Params) {

    private val fs = sampleRate.toFloat()
    private val rumble = Biquad().apply { setHighPass(fs, 80f, 0.707f) }
    private val bands = Array(HearingSettings.BAND_FREQS.size) { Biquad() }
    private val pending = AtomicReference<HearingSettings.Params?>(initial)

    private var targetGain = 1f
    private var gain = 1f
    private var ceiling = 0.5f
    private var envelope = 0f

    // Gain glides over ~20 ms; limiter releases over ~80 ms. Attack is instant, so it never clips.
    private val gainSmooth = exp(-1.0 / (0.020 * sampleRate)).toFloat()
    private val release = exp(-1.0 / (0.080 * sampleRate)).toFloat()

    fun setParams(p: HearingSettings.Params) = pending.set(p)

    private fun apply(p: HearingSettings.Params) {
        val nyquist = fs / 2f
        for (i in bands.indices) {
            val f = HearingSettings.BAND_FREQS[i]
            // Skip a band that would sit too close to Nyquist on low-sample-rate hardware.
            bands[i].setPeaking(fs, f.coerceAtMost(nyquist * 0.9f), 1.41f, if (f < nyquist * 0.9f) p.bandsDb[i] else 0f)
        }
        targetGain = 10f.pow(p.gainDb / 20f)
        ceiling = 10f.pow(p.limitDb / 20f)
    }

    /** Processes [n] mono samples from [input] into [output] (both -1..1 floats). */
    fun process(input: FloatArray, output: FloatArray, n: Int) {
        pending.getAndSet(null)?.let { apply(it) }
        for (i in 0 until n) {
            gain = targetGain + (gain - targetGain) * gainSmooth
            var y = rumble.process(input[i]) * gain
            for (b in bands) y = b.process(y)

            val level = abs(y)
            envelope = if (level > envelope) level else envelope * release + level * (1f - release)
            if (envelope > ceiling) y *= ceiling / envelope
            output[i] = y.coerceIn(-1f, 1f)
        }
    }
}
