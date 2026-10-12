package be.mdw.rigalong

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * A foreground service whose only job is to let the instrument keep playing when
 * the activity is not in front.
 *
 * ## Why this exists
 *
 * `onPause()` used to call `nativeStop()`, which closed the audio stream. That
 * is the correct, polite behaviour for an app, and the wrong behaviour for an
 * instrument: **anything that backgrounds the activity killed the sound.**
 * Measured 2026-09-20 — a press of the power button stopped audio outright, and
 * it did not come back until the app was returned to the foreground.
 *
 * `FLAG_KEEP_SCREEN_ON` is already set and stops the screen timing out on its
 * own, which covers the commonest case. It does not cover a power button caught
 * with a sleeve, a system dialog, an alarm, or — on this tablet, which has a SIM
 * — an incoming call. On a stage every one of those is a dead instrument in the
 * middle of a song.
 *
 * So the audio now keeps running, and this service is what makes that legitimate
 * rather than something Android tolerates until it decides not to. A foreground
 * service with `mediaPlayback` type is the supported way to say "this process is
 * producing audio the user is listening to right now"; without it the process is
 * a background app holding an EXCLUSIVE MMAP stream, which the system is within
 * its rights to tear down whenever it likes.
 *
 * ## What it deliberately does NOT do
 *
 * It owns no audio. The engine is still started and stopped by the activity;
 * this only holds the process at foreground priority and shows the notification
 * that the user can stop it from. Keeping the audio lifecycle in one place is
 * worth more than the symmetry of moving it in here, because the one thing that
 * must never happen is two owners disagreeing about whether the stream is open.
 */
class EngineService : Service() {

    /**
     * Held for as long as the instrument runs.
     *
     * The AAudio path never needed it: AudioFlinger holds a wake lock on behalf
     * of any app with an active AudioTrack. The direct-USB modes have no
     * AudioTrack -- that is their point -- so nothing holds one for them, and a
     * foreground service alone keeps the process uncached without keeping the
     * SoC awake. Measured with the usbhost prototype on 2026-09-22. Harmless on
     * the AAudio path, where the same lock is already held by the framework.
     */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's Stop button. Close the instrument properly:
            // release the stream, then take the notification down with us.
            NativeBridge.nativeCloseMidi()
            NativeBridge.nativeStop()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "rigalong:engine")
                .apply { setReferenceCounted(false); acquire() }
        }

        // NOT sticky. If the system kills this process, the audio stream and
        // every engine's state went with it, and a service quietly restarting
        // into an app with no UI and no instrument loaded would be worse than
        // nothing -- it would look like it was working.
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Instrument running",
                // LOW: no sound, no vibration, no heads-up. A notification that
                // made a noise on a stage would be its own bug.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while the audio engine holds the USB stream."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, EngineService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("RigAlong")
            .setContentText("Holding the CP-88 audio stream")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Stop", stop)
                    .build()
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "rigalong-engine"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "be.mdw.rigalong.STOP"

        /**
         * Safe to call repeatedly: a second start of a running foreground
         * service just re-delivers the intent.
         *
         * Called from the activity while it is in front, which is what makes it
         * legal at all -- starting a foreground service from the background is
         * refused on Android 12 and later.
         */
        fun start(context: Context) {
            val intent = Intent(context, EngineService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }
    }
}
