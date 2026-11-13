package com.rigalong

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Where the click can go besides the main mix (S12.4, REVIEW P10): the
 * tablet's speaker, headphones, a Bluetooth headset, a USB audio device. The
 * main mix stays the default ("great for rehearsals"); on stage the click can
 * then go to the player's ears and not to the audience.
 *
 * A choice is kept as a [key] that survives the device going and coming back
 * ("speaker", "headphones", "bluetooth", "usb:<product name>"): Android's
 * device ids change with every connection. While the chosen device is away
 * the click is in the main mix; it moves back when the device returns.
 *
 * Main thread.
 */
class ClickOutputs(context: Context) {

    /** One place the click can go. */
    data class Output(val key: String, val label: String, val id: Int)

    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var wanted: String? = null
    /** The device the click plays on now; -1: the main mix. */
    var playingOn = -1
        private set

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = reapply()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = reapply()
    }

    init {
        audio.registerAudioDeviceCallback(callback, handler)
    }

    /** The outputs there are now, one per key. */
    fun outputs(): List<Output> {
        val out = LinkedHashMap<String, Output>()
        for (d in audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            val name = d.productName?.toString()?.trim().orEmpty()
            val (key, label) = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker" to "Tablet speaker"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "headphones" to "Headphones"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "headphones" to (if (name.isEmpty()) "Headphones" else "Headphones: $name")
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER ->
                    "bluetooth" to (if (name.isEmpty()) "Bluetooth" else "Bluetooth: $name")
                AudioDeviceInfo.TYPE_USB_DEVICE -> "usb:$name" to (if (name.isEmpty()) "USB audio" else "USB: $name")
                else -> continue
            }
            if (key !in out) out[key] = Output(key, label, d.id)
        }
        return out.values.toList()
    }

    /** The click to the output of [key], or into the main mix (null). */
    fun use(key: String?) {
        wanted = key
        reapply()
    }

    /** Ends: the click back in the main mix, no more watching for devices. */
    fun close() {
        audio.unregisterAudioDeviceCallback(callback)
        wanted = null
        if (playingOn >= 0) NativeBridge.nativeClickOutput(-1)
        playingOn = -1
    }

    private fun reapply() {
        val id = wanted?.let { k -> outputs().firstOrNull { it.key == k }?.id } ?: -1
        if (id == playingOn && (id < 0 || stillThere(id))) return
        playingOn = NativeBridge.nativeClickOutput(id)
        Log.i("RigAlong", "click output: ${wanted ?: "main mix"} -> " +
            if (playingOn >= 0) "device $playingOn" else "the main mix")
    }

    private fun stillThere(id: Int) = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.id == id }

    private companion object {
        // AudioDeviceInfo.TYPE_BLE_HEADSET and TYPE_BLE_SPEAKER (API 31).
        const val TYPE_BLE_HEADSET = 26
        const val TYPE_BLE_SPEAKER = 27
    }
}
