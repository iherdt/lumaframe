package com.iherdt.lumaframe

import android.os.PowerManager
import android.service.dreams.DreamService
import android.util.Log

/**
 * Thin trampoline screensaver.
 *
 * Portal's ambient/dream manager kills an interactive in-dream UI within ~1s
 * (DREAM_FINISHED), which made a self-hosted slideshow flicker in and out. So
 * instead of rendering inside the dream, we use the dream purely as the
 * "device went idle" trigger: launch the full-screen interactive
 * [SlideshowComposeActivity] (which renders and handles swipe/tap reliably) and exit
 * the dream. The Activity keeps the screen on, so nothing loops; a tap finishes
 * the Activity -> device idles -> dream fires again -> relaunch.
 *
 * With photos off and nothing shown instead, the Activity does not keep the screen on. Once
 * its black screen is up the dream ends without opening anything: on a Portal+ the system then
 * finds nothing awake and puts the display to sleep, where opening a screen wakes it again.
 */
class FrameDreamService : DreamService() {
    override fun onCreate() {
        super.onCreate()
        // A dream's window keeps the screen on unless told otherwise, and that alone is enough
        // for the system to wake the Portal, not sleep, when the dream ends.
        if (SlideshowComposeActivity.sleepsWhenIdle(this)) isScreenBright = false
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        if (letDisplaySleep()) {
            Log.i(TAG, "Dream: photos are off, leaving the display to sleep")
            finish()
            return
        }
        try {
            startActivity(SlideshowComposeActivity.launchIntent(this))
            Log.i(TAG, "Dream launched SlideshowComposeActivity")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch slideshow from dream", e)
        }
        finish() // hand off to the Activity
    }

    /** True when the black photos-off screen is already up, or the display is already asleep. */
    private fun letDisplaySleep(): Boolean {
        if (!SlideshowComposeActivity.sleepsWhenIdle(this)) return false
        val awake = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        return SlideshowComposeActivity.darkShowing || !awake
    }

    private companion object {
        private const val TAG = "Lumaframe"
    }
}
