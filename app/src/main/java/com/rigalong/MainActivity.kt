package com.rigalong

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * The one activity: the screen (ui/AppUi.kt) on the process's [Rig], and
 * what only an activity has -- the lifecycle, permission answers, keys.
 *
 * It owns nothing that plays. The engine goes on behind [EngineService]
 * while no activity shows it, so everything that drives it is the rig's; an
 * activity made later in the same process shows the same rig.
 */
class MainActivity : androidx.activity.ComponentActivity() {

    private lateinit var rig: Rig

    private val handler = Handler(Looper.getMainLooper())
    private var pollTick = 0

    /** In front: the watchdog, newly seen devices, and the status, four times a second. */
    private val poller = object : Runnable {
        override fun run() {
            if (rig.ended) return
            // BEFORE the status, because it is what resolves the output's current
            // device id and the status reports against that. The other way
            // round, every reading would describe the previous one.
            rig.router.watch()
            rig.router.recogniseDevices()
            rig.status.update()
            if (++pollTick % 4 == 0) rig.router.watchUsbAltSetting()
            handler.postDelayed(this, 250)
        }
    }

    /**
     * What keeps watch while the activity is NOT in front.
     *
     * Since the instrument keeps playing when backgrounded (see
     * [EngineService]), it also has to keep RECOVERING when backgrounded. It
     * matters because of the division of labour in a recovery. The native
     * retry re-opens the device id it remembers, which catches a cable that was
     * merely nudged; only the Kotlin side can ask AudioManager for the CP-88
     * BY NAME, which is the only thing that works after a real re-enumeration,
     * because that hands out a new id and makes every remembered one useless.
     * Without this, a replug behind a dialog left a silent instrument that
     * believed it was playing.
     *
     * Once a second rather than four times, and no status work at all: nothing
     * is on screen to update. It also keeps [NativeBridge.stats] being called,
     * which is what emits the health heartbeat in the log.
     */
    private val backgroundPoller = object : Runnable {
        override fun run() {
            if (rig.ended) return
            rig.router.watch()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        rig = Rig.get(this)
        rig.activity = this
        setContentView(androidx.compose.ui.platform.ComposeView(this).apply {
            setContent { com.rigalong.ui.AppUi(rig.player, rig.library, rig.host) }
        })
        // A STRAY BACK GESTURE MUST NOT END THE INSTRUMENT MID-SONG (found the
        // hard way on 2026-10-11): Back goes to the stage, and on the stage it
        // does nothing.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!rig.player.onStage) rig.player.backToStage?.invoke()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (rig.ended) return
        rig.activity = this
        rig.applyStageDnd(true)

        // Before anything else, and while this activity is genuinely in front:
        // starting a foreground service from the background is refused on
        // Android 12 and later, so this is the only moment it is legal.
        EngineService.start(this)
        handler.removeCallbacks(backgroundPoller)
        rig.bringUp()
        handler.post(poller)
    }

    override fun onPause() {
        super.onPause()
        if (rig.ended) return
        rig.applyStageDnd(false)
        handler.removeCallbacks(poller)
        // Hand over to the cut-down watchdog. The instrument keeps playing, so
        // it has to keep recovering too.
        handler.post(backgroundPoller)

        // THE AUDIO AND MIDI DELIBERATELY STAY UP.
        //
        // Stopping them here is correct behaviour for an app and wrong
        // behaviour for an instrument: it meant anything that backgrounded the
        // activity killed the sound. Measured 2026-09-20 -- a press of the
        // power button stopped audio outright, and it stayed stopped until the
        // app was brought back to the front.
        //
        // FLAG_KEEP_SCREEN_ON already stops the screen timing out on its own. It
        // does not stop a power button caught with a sleeve, a system dialog, an
        // alarm, or an incoming call on a tablet that has a SIM. On a stage every
        // one of those was a dead instrument in the middle of a song.
        //
        // EngineService is what makes keeping it running legitimate. The rig
        // ends when this activity finishes, or with the notification's Stop.
    }

    override fun onDestroy() {
        super.onDestroy()
        // Both runnables hold a reference to this activity, and one that
        // outlived it would keep the whole view hierarchy alive.
        handler.removeCallbacksAndMessages(null)
        // isFinishing distinguishes the player closing the instrument from
        // Android taking the activity away: only the first ends the rig. An
        // activity destroyed otherwise leaves it playing, unwatched until the
        // next activity resumes. And an activity that hears of its end late,
        // when a newer one already shows the rig, ends nothing.
        val shown = rig.activity === this || rig.activity == null
        if (rig.activity === this) rig.activity = null
        if (isFinishing && shown) rig.end()
    }

    @Deprecated("ComponentActivity's own callback; this app asks with the platform API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // By NAME rather than by position: the request carries either or both of
        // RECORD_AUDIO and POST_NOTIFICATIONS. Only the measurement's request
        // opens the input: the one for USB "Always" does not.
        permissions.forEachIndexed { i, permission ->
            val ok = grantResults.getOrNull(i) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (ok && permission == android.Manifest.permission.RECORD_AUDIO && requestCode == REQUEST_MEASURE) {
                rig.openMicrophone()
            }
        }
    }

    /**
     * Page turners and footswitches arrive as keys (I9.2). On the stage:
     * Page Down / right / down arrow / space the next live set, Page Up /
     * left / up arrow the previous one.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (::rig.isInitialized && rig.player.onStage && event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_PAGE_DOWN, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_SPACE -> {
                    rig.player.forwardKey(); return true
                }
                android.view.KeyEvent.KEYCODE_PAGE_UP, android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    rig.player.backKey(); return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        /** Permission requests: the measurement's opens the microphone when granted, the one for USB "Always" does not. */
        const val REQUEST_MEASURE = 1
        const val REQUEST_USB_ALWAYS = 2
    }
}
