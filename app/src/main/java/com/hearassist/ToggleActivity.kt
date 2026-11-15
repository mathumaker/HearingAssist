package com.hearassist

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible, instant activity that widget taps go through.
 *
 * Android only lets an app open the microphone from a foreground service if the service was
 * started while the app was visible. Routing the tap through this see-through activity makes
 * that true on every phone and Android version, so the one-tap toggle always works.
 */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (HearingService.isRunning) {
            HearingService.stop(this)
        } else {
            when (HearingService.checkReady(this)) {
                null -> HearingService.start(this)
                HearingService.Problem.NO_HEADSET ->
                    Toast.makeText(this, "Plug in wired earphones first", Toast.LENGTH_SHORT).show()
                HearingService.Problem.NO_PERMISSION -> {
                    Toast.makeText(this, "Allow microphone access to use hearing assist", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
