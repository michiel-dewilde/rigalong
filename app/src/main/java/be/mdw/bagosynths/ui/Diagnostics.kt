package be.mdw.bagosynths.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The diagnostic log (REQUIREMENTS S19.2): it stays on the device, and leaves
 * only when the user shares it.
 *
 * Two files rotate in the app's own storage: the health line every 10 s (the
 * transport's holds and errors, xruns, the path, the heat) and the full status
 * every 5 minutes and whenever the health line changes its state. 512 kB a
 * file, two files: about ten hours of playing at the steady rate.
 *
 * "Share diagnostics" writes one text file -- device, app version, the routing,
 * both log files and this process's own logcat (the engine's and the
 * transport's own messages; an app may read its own) -- and hands it to the
 * share sheet, so the user picks where it goes, or nowhere.
 */
class Diagnostics(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "diagnostics").apply { mkdirs() }
    private val current = File(dir, "diag.log")
    private val previous = File(dir, "diag.1.log")
    private var lastHealth = 0L
    private var lastFull = 0L
    private var lastState = ""

    private fun stamp() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    @Synchronized
    private fun append(text: String) {
        runCatching {
            if (current.length() > MAX_BYTES) {
                previous.delete()
                current.renameTo(previous)
            }
            current.appendText(text)
        }
    }

    /** One line, for events worth finding again: a song, a reroute, a failure. */
    fun event(what: String) = append("${stamp()}  $what\n")

    /** Called often (the UI's poll); samples at its own pace. */
    fun tick(host: EngineHost) {
        val now = System.currentTimeMillis()
        if (now - lastHealth < HEALTH_MS) return
        lastHealth = now
        val health = runCatching { host.healthLine() }.getOrDefault("")
        // The state words only (running, holds...), not the figures that move every time.
        val state = health.replace(Regex("[0-9.,]+"), "#")
        append("${stamp()}  $health\n")
        if (now - lastFull >= FULL_MS || state != lastState) {
            lastFull = now
            lastState = state
            val lines = runCatching { host.statusLines() }.getOrDefault(emptyList())
            append(lines.joinToString("\n", prefix = "${stamp()}  status\n", postfix = "\n") { "    $it" })
        }
    }

    /** Writes the bundle and opens the share sheet. */
    fun share(context: Context, extra: List<String>) {
        // Off the UI thread: reading logcat takes a moment the stage should not freeze for.
        Thread {
            val send = runCatching { bundle(extra) }
                .onFailure { android.util.Log.e("bos-diag", "diagnostics not written: $it") }.getOrNull() ?: return@Thread
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }.start()
    }

    private fun bundle(extra: List<String>): Intent {
        val out = File(app.cacheDir, "diagnostics").apply { mkdirs() }
        out.listFiles()?.forEach { it.delete() }
        val f = File(out, "bagosynths-diagnostics-${stamp().replace(Regex("[^0-9]"), "")}.txt")
        f.bufferedWriter().use { w ->
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            w.write("Bag-O-Synths ${info.versionName}\n")
            w.write("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            w.write("written ${stamp()}\n\n")
            for (l in extra) w.write("$l\n")
            for (file in listOf(previous, current)) {
                if (!file.exists()) continue
                w.write("\n===== ${file.name}\n")
                file.bufferedReader().use { it.copyTo(w) }
            }
            w.write("\n===== logcat (this app only)\n")
            runCatching {
                val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=${android.os.Process.myPid()}")
                    .redirectErrorStream(true).start()
                p.inputStream.bufferedReader().use { it.copyTo(w) }
                p.waitFor()
            }.onFailure { w.write("(logcat unavailable: ${it.message})\n") }
        }
        android.util.Log.i("bos-diag", "wrote ${f.name}: ${f.length()} B, ${f.useLines { it.count() }} lines")
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.diagnostics", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Bag-O-Synths diagnostics")
            // As ClipData too: only then does the read grant reach the share
            // sheet itself, which previews the file (else: Permission Denial).
            clipData = android.content.ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return send
    }

    companion object {
        private const val MAX_BYTES = 512 * 1024L
        private const val HEALTH_MS = 10_000L
        private const val FULL_MS = 5 * 60_000L
    }
}
