package com.hearassist

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

/**
 * Foreground service (type "microphone") that keeps hearing assist running with the screen off
 * or another app open. Shows an ongoing notification with a "Turn off" button.
 */
class HearingService : Service() {

    enum class Problem { NO_PERMISSION, NO_HEADSET }

    companion object {
        private const val ACTION_STOP = "com.hearassist.STOP"
        private const val CHANNEL_ID = "hearing"
        private const val NOTIFICATION_ID = 1

        /** True while audio is flowing. Read by the widget and the settings screen. */
        @Volatile var isRunning = false
            private set

        /** Called on the main thread whenever [isRunning] changes. */
        var stateListener: ((Boolean) -> Unit)? = null

        fun checkReady(ctx: Context): Problem? = when {
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> Problem.NO_PERMISSION
            AudioEngine.findHeadset(ctx.getSystemService(AudioManager::class.java)) == null -> Problem.NO_HEADSET
            else -> null
        }

        /** Must be called while the app is in the foreground (an Activity), as Android requires for mic services. */
        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, HearingService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, HearingService::class.java))
        }
    }

    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var engine: AudioEngine? = null

    // Live settings: slider moves on the settings screen are applied to the running audio.
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        engine?.updateParams(HearingSettings.load(this))
    }

    // Earphones removed -> stop before Android re-routes the mic to the loudspeaker.
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = shutdown("Earphones unplugged — hearing assist off")
    }
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (AudioEngine.findHeadset(audioManager) == null) shutdown("Earphones unplugged — hearing assist off")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown(null)
            return START_NOT_STICKY
        }
        if (engine != null) return START_NOT_STICKY // already running

        // Started with startForegroundService(): go foreground right away, as Android requires.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            shutdown("Couldn't start: ${e.message}")
            return START_NOT_STICKY
        }

        val problem = checkReady(this)
        if (problem != null) {
            shutdown(if (problem == Problem.NO_HEADSET) "Plug in wired earphones first" else "Microphone permission needed")
            return START_NOT_STICKY
        }

        engine = AudioEngine(this) { error -> shutdown(error) }.also { it.start(HearingSettings.load(this)) }
        HearingSettings.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        val noisy = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, noisy, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(noisyReceiver, noisy)
        }
        setRunning(true)
        // Not sticky: if Android ever kills the process, don't silently restart a live microphone.
        return START_NOT_STICKY
    }

    private var shuttingDown = false

    /** Stops audio and the service. Safe to call more than once. */
    private fun shutdown(message: String?) {
        if (shuttingDown) return
        shuttingDown = true
        if (message != null) Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        engine?.let {
            it.stop()
            HearingSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            runCatching { unregisterReceiver(noisyReceiver) }
        }
        engine = null
        setRunning(false)
        super.onDestroy()
    }

    private fun setRunning(value: Boolean) {
        isRunning = value
        HearingWidget.updateAll(this)
        stateListener?.invoke(value)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Hearing assist", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the microphone is playing to your earphones"
                    setShowBadge(false)
                }
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HearingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ear_on)
            .setContentTitle("Hearing assist is on")
            .setContentText("Microphone → earphones. Tap to adjust sound.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_ear_off), "Turn off", stop).build()
            )
            .build()
    }
}
