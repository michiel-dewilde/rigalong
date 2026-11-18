package com.rigalong.play

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rigalong.model.Digest
import com.rigalong.model.Store

/** The song's lead sheet on the stage, and how it pages (S9; REVIEW S3). */
class LeadSheet(private val prefs: SharedPreferences, private val store: Store) {

    /** The current song's lead sheet as a file in the store, or null (S9.1). */
    var file by mutableStateOf<java.io.File?>(null); private set
    var page by mutableIntStateOf(0); private set
    var pages by mutableIntStateOf(0)
    /** Settings of the environment (S9.2): 2-up, half turns, and what the page turner turns. */
    var twoUp by mutableStateOf(prefs.getBoolean("sheet.twoUp", true))
    var halfTurn by mutableStateOf(prefs.getBoolean("sheet.halfTurn", true))
    var keysTurnPages by mutableStateOf(prefs.getBoolean("sheet.keysTurnPages", true))

    /** A song's lead sheet ([sheet], its object in the store, or none), from its first page. */
    fun open(sheet: Digest?) {
        page = 0
        pages = 0
        file = sheet?.let { java.io.File(store.root, "blobs/sha256/${it.hex}") }?.takeIf { it.exists() }
    }

    /** A PDF into the library; returns its object. */
    fun storePdf(bytes: ByteArray): Digest {
        require(bytes.size > 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "%PDF") { "not a PDF" }
        return store.put(bytes)
    }

    fun saveSettings() {
        prefs.edit().putBoolean("sheet.twoUp", twoUp).putBoolean("sheet.halfTurn", halfTurn)
            .putBoolean("sheet.keysTurnPages", keysTurnPages).apply()
    }

    /**
     * How many pages the stage SHOWS now: two side by side only in landscape,
     * whatever [twoUp] says (the stage sets it). Turning counted from the
     * setting stopped an upright sheet of two pages at its first page, for the
     * swipe and the page keys alike (Michiel, 2026-11-18).
     */
    var showing by mutableIntStateOf(1)

    /** With two pages shown a half turn moves one page, so the right page becomes the left (I9.3); else both. */
    private fun turnStep() = if (showing == 2 && !halfTurn) 2 else 1

    fun forward() {
        val last = (pages - showing).coerceAtLeast(0)
        page = (page + turnStep()).coerceAtMost(last)
    }

    fun back() { page = (page - turnStep()).coerceAtLeast(0) }

    /** A page-turner key turns pages here: the song has a lead sheet and the setup says so. */
    /**
     * The screen has room for a lead sheet (the stage says; see StageScreen):
     * where it has not, none is shown and the page keys select live sets.
     */
    var screenFits by mutableStateOf(true)

    /** The sheet as large as the screen allows, the stage's controls out of the way (Michiel, 2026-11-18). */
    var fullScreen by mutableStateOf(false)

    /** Full screen is in force: asked for, a sheet open, and room for it. */
    val fullScreenShown: Boolean get() = fullScreen && file != null && screenFits

    val turnsPages: Boolean get() = keysTurnPages && file != null && screenFits
}
