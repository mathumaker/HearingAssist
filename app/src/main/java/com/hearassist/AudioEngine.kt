package com.hearassist

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlin.math.max

/**
 * Microphone -> [SoundProcessor] -> wired earphones, on a dedicated high-priority thread.
 *
 * Latency measures:
 *  - runs at the device's native output rate and burst size (no resampling, no extra buffering)
 *  - AudioTrack in PERFORMANCE_MODE_LOW_LATENCY with its buffer trimmed to two bursts
 *  - capture uses the UNPROCESSED (or VOICE_RECOGNITION) source, which skips the phone's
 *    call-style noise/echo processing and its delay
 *  - THREAD_PRIORITY_URGENT_AUDIO and blocking reads/writes of one burst at a time
 */
class AudioEngine(private val context: Context, private val onStopped: (error: String?) -> Unit) {

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var processor: SoundProcessor? = null

    fun start(params: HearingSettings.Params) {
        if (running) return
        running = true
        thread = Thread({ runLoop(params) }, "HearAssist-audio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    fun updateParams(params: HearingSettings.Params) {
        processor?.setParams(params)
    }

    @SuppressLint("MissingPermission") // checked by HearingService before starting
    private fun runLoop(params: HearingSettings.Params) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val sampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        val burst = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()
            ?.takeIf { it in 32..4096 } ?: 256

        var record: AudioRecord? = null
        var track: AudioTrack? = null
        var failure: String? = null
        try {
            val source = if (audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
                MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION

            val inMin = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            require(inMin > 0) { "This phone's microphone doesn't support $sampleRate Hz" }
            val rec = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setBufferSizeInBytes(max(inMin, burst * 2 * 2))
                .build()
            record = rec
            require(rec.state == AudioRecord.STATE_INITIALIZED) { "Couldn't open the microphone (is another app using it?)" }
            // Use the phone's own mic, even if the earphones have one.
            findDevice(AudioManager.GET_DEVICES_INPUTS, AudioDeviceInfo.TYPE_BUILTIN_MIC)?.let { rec.setPreferredDevice(it) }

            val outMin = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val trk = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(outMin)
                .build()
            track = trk
            require(trk.state == AudioTrack.STATE_INITIALIZED) { "Couldn't open audio output" }

            val headset = findHeadset(audioManager) ?: error("Plug in wired earphones first")
            trk.setPreferredDevice(headset)
            trk.setBufferSizeInFrames(burst * 2)

            // If the output ever moves to the loudspeaker (earphones pulled out), stop at once:
            // speaker + live mic = loud feedback squeal.
            trk.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { router ->
                if (router.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) running = false
            }, mainHandler)

            val proc = SoundProcessor(sampleRate, params).also { processor = it }
            val inShorts = ShortArray(burst)
            val inFloats = FloatArray(burst)
            val outFloats = FloatArray(burst)
            val outShorts = ShortArray(burst * 2)

            rec.startRecording()
            trk.write(ShortArray(burst * 2), 0, burst * 2) // one burst of silence avoids a start-up glitch
            trk.play()
            Log.i(TAG, "Running: $sampleRate Hz, burst $burst frames, source $source")

            while (running) {
                val n = rec.read(inShorts, 0, burst, AudioRecord.READ_BLOCKING)
                if (n < 0) error("Microphone stopped (code $n)")
                if (n == 0) continue
                for (i in 0 until n) inFloats[i] = inShorts[i] / 32768f
                proc.process(inFloats, outFloats, n)
                for (i in 0 until n) {
                    val s = (outFloats[i] * 32767f).toInt().toShort()
                    outShorts[2 * i] = s
                    outShorts[2 * i + 1] = s
                }
                val w = trk.write(outShorts, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                if (w < 0) error("Audio output stopped (code $w)")
            }
            if (findHeadset(audioManager) == null) failure = "Earphones unplugged"
        } catch (e: Exception) {
            Log.e(TAG, "Audio loop failed", e)
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            running = false
            processor = null
            runCatching { record?.stop() }; record?.release()
            runCatching { track?.pause(); track?.flush() }; track?.release()
            mainHandler.post { onStopped(failure) }
        }
    }

    private fun findDevice(flag: Int, type: Int) =
        audioManager.getDevices(flag).firstOrNull { it.type == type }

    companion object {
        private const val TAG = "HearAssist"

        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,     // 3.5 mm jack, earphones with mic
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,  // 3.5 mm jack, no mic
            AudioDeviceInfo.TYPE_USB_HEADSET,       // USB-C earphones / USB-C-to-3.5 mm adapter
            AudioDeviceInfo.TYPE_USB_DEVICE,
        )

        /** Wired earphones currently plugged in, or null. Bluetooth is deliberately excluded (too much delay). */
        fun findHeadset(am: AudioManager): AudioDeviceInfo? =
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type in HEADSET_TYPES }
    }
}
