#!/usr/bin/env python3
"""flowcheck -- the things a player does often, done on the real app, counted.

Drives RigAlong on a device over adb, finding controls by the text they
show (uiautomator), never by coordinates, so a layout change does not break it
and a renamed control does. Each flow asserts what the player should see or
hear happen, and has a TAP BUDGET: an action that is meant to be quick and
takes more taps than that fails, so "simple" is measured, not claimed.

    ANDROID_SERIAL=<device> python3 tools/flowcheck.py [flow-name ...]

The app must be installed and in front, on a setlist with the demo songs
(the first-run seed: "sfizz", "setBfree and sfizz", "Surge XT and sfizz"). Flows that
save leave the library as they found it, by their own Undo. Never sends Back
to the app and never turns the screen off.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB") or os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
if not os.path.exists(ADB):
    ADB = "adb"


def adb(*args, capture=True):
    r = subprocess.run([ADB, *args], capture_output=capture, timeout=60,
                       env={**os.environ, "MSYS_NO_PATHCONV": "1"})
    if capture and b"more than one device" in r.stderr:
        sys.exit("adb sees more than one device: set ANDROID_SERIAL")
    return r.stdout.decode("utf-8", "replace") if capture else ""


def shell(cmd):
    return adb("shell", cmd)


# ------------------------------------------------------------------ the screen

class Node:
    def __init__(self, text, desc, bounds):
        self.text, self.desc = text, desc
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", bounds))
        self.box = (x1, y1, x2, y2)
        self.x, self.y = (x1 + x2) // 2, (y1 + y2) // 2

    def __repr__(self):
        return f"<{self.text or self.desc!r} at {self.x},{self.y}>"


def screen():
    """Every node with a text or a description, read from the live UI."""
    for _ in range(10):
        xml = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        start = xml.find("<?xml")
        end = xml.rfind("</hierarchy>")
        if start >= 0 and end > start:
            root = ET.fromstring(xml[start:end + len("</hierarchy>")])
            return [Node(n.get("text", ""), n.get("content-desc", ""), n.get("bounds"))
                    for n in root.iter("node") if n.get("text") or n.get("content-desc")]
        time.sleep(1.0)   # "could not get idle state", or uiautomator still busy with the last dump
    raise RuntimeError("no UI dump")


def find_all(text, exact=True):
    out = []
    for n in screen():
        label = n.text or n.desc
        if (label == text) if exact else (text in label):
            out.append(n)
    return out


def wait_for(text, exact=True, timeout=6.0):
    t0 = time.time()
    while time.time() - t0 < timeout:
        found = find_all(text, exact)
        if found:
            return found
        time.sleep(0.3)
    return []


def see_scrolling(text, exact=True, tries=10):
    """Waits for [text], scrolling the screen's middle down a page at a time until it shows."""
    for _ in range(tries):
        if wait_for(text, exact, timeout=1.5):
            return True
        shell("input swipe 1480 1300 1480 600 400")
        time.sleep(0.8)
    return False


def focused_app():
    return shell("dumpsys window | grep mCurrentFocus")


def logcat_clear():
    adb("logcat", "-c")


def logcat_has(pattern, timeout=4.0):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if re.search(pattern, adb("logcat", "-d", "-s", "rigalong-stage:I")):
            return True
        time.sleep(0.3)
    return False


def broadcast(action, *extras):
    shell(f"am broadcast -a be.mdw.rigalong.{action} " + " ".join(extras))


# ---------------------------------------------------------------- flows

class Flow:
    def __init__(self, name, budget):
        self.name, self.budget, self.taps = name, budget, 0

    def tap(self, text, long=False, pick="first", exact=True, times=1, interval=0.45):
        found = find_all(text, exact)
        if not found:
            raise AssertionError(f"nothing reads {text!r}")
        low = max(n.y for n in found)
        n = {"first": found[0], "last": found[-1],
             "lowest": max(found, key=lambda n: n.y), "highest": min(found, key=lambda n: n.y),
             # the leftmost of the lowest row: a fader's name, not the panel beside it
             "lowest-left": min((n for n in found if n.y > low - 40), key=lambda n: n.x)}[pick]
        for k in range(times):
            if long:
                shell(f"input swipe {n.x} {n.y} {n.x} {n.y} 900")
            else:
                shell(f"input tap {n.x} {n.y}")
            self.taps += 1
            time.sleep(interval if k + 1 < times else 0.6)

    def slide(self, label, at=0.05):
        """A tap on the slider in the row of parameter [label], [at] of the way along: one tap."""
        found = find_all(label)
        if not found:
            raise AssertionError(f"no parameter {label!r}")
        n = found[0]
        # The slider runs from the end of the name column to the value column, the next text in the row.
        right = [m for m in screen() if abs(m.y - n.y) < 12 and m.box[0] > n.box[2]]
        if not right:
            raise AssertionError(f"no value beside {label!r}")
        x1 = n.box[2] + 30
        x2 = min(m.box[0] for m in right) - 30
        shell(f"input tap {int(x1 + (x2 - x1) * at)} {n.y}")
        self.taps += 1
        time.sleep(0.6)

    def see(self, text, exact=True, timeout=6.0):
        if not wait_for(text, exact, timeout):
            raise AssertionError(f"expected to see {text!r}")


def to_stage():
    """Back to the stage on the second song, live set 1 (not counted)."""
    for n in find_all("Stage"):
        shell(f"input tap {n.x} {n.y}")
        break
    time.sleep(0.6)
    broadcast("STAGE", "--ei song 2 --ei liveset 1")
    time.sleep(1.5)


def flow_select_live_set(f):
    logcat_clear()
    f.tap("Split")
    assert logcat_has(r"live set 3 'Split'"), "live set 3 not selected"


def flow_next_song(f):
    logcat_clear()
    f.tap("▶")
    assert logcat_has(r"song 3 '"), "the next song did not open"


def flow_quick_edit_live_set(f):
    # One long press on a live set button: its song in the editor, at that live set.
    f.tap("Split", long=True)
    f.see("Setlist:", exact=False)
    f.see("Live set 3", exact=False)


def flow_quick_edit_patch(f):
    # One long press on a sound's name above its fader: its patch.
    f.tap("sfizz", long=True, pick="lowest")
    f.see("sfizz: patch")
    f.tap("Done")


def flow_count_in(f):
    logcat_clear()
    f.tap("TAP", times=4)          # found once: four taps a beat apart, not a screen read apart
    f.tap("COUNT")
    assert logcat_has(r"click: 4 beats"), "no count-in"


def type_text(text):
    shell("input text " + text.replace(" ", "%s"))
    time.sleep(0.4)


def to_songs():
    """The Songs screen: from the stage, where it opens on a long press (not counted)."""
    to_stage()
    for n in find_all("Songs"):
        shell(f"input swipe {n.x} {n.y} {n.x} {n.y} 900")
        break
    time.sleep(1.0)


def to_library():
    """The Library screen: from the stage, where it opens on a long press (not counted)."""
    to_stage()
    for n in find_all("Library"):
        shell(f"input swipe {n.x} {n.y} {n.x} {n.y} 900")
        break
    time.sleep(1.0)


# The shared-patch round trip: share sfizz's patch, use it in a second
# song, change it in the first and update it everywhere, check both songs
# follow -- then three Undos leave the library as it was.
PATCH = "FlowSfizz"


def flow_share_patch(f):
    to_songs()
    f.tap("1. sfizz")
    f.tap("Patch…")
    f.tap("Save as library patch…")
    f.tap("Patch name")
    shell("input keyevent KEYCODE_MOVE_END")
    for _ in range(12):
        shell("input keyevent KEYCODE_DEL")
    type_text(PATCH)
    f.tap("Save")
    f.see(f"Library patch “{PATCH}”", exact=False)
    f.tap("Done")
    f.tap("Save •")                    # the song keeps the link once it is saved


def flow_reuse_patch(f):
    to_songs()
    f.tap("3. Surge XT and sfizz")
    f.tap("(as loaded)")
    f.tap(PATCH)
    f.tap("Save •")


def flow_update_everywhere(f):
    to_songs()
    f.tap("1. sfizz")
    f.tap("(as loaded)")
    f.tap("Salamander Grand (tightened)", exact=False)
    f.tap("Save •")
    f.see("A library patch changed")
    f.see("1 other song uses it.", exact=False)
    f.tap("Save")
    # Both songs on the new version (checked in the Library, not counted).
    to_library()
    details = find_all("Details")
    if not details:
        raise AssertionError("no Details")
    n = details[-1]                    # the patches list comes last
    shell(f"input tap {n.x} {n.y}")
    time.sleep(1.0)
    if len(find_all("latest version")) != 2:
        raise AssertionError("expected two songs on the latest version")
    for d in find_all("Done"):
        shell(f"input tap {d.x} {d.y}")


def flow_undo_all(f):
    # Four actions to undo: the patch shared, the song saved with it, the
    # second song, and the update everywhere.
    to_library()
    for _ in range(4):
        f.tap("Undo", exact=False)
    if wait_for(PATCH, timeout=2):
        raise AssertionError(f"{PATCH} still in the library after four Undos")


def flow_second_sfizz(f):
    # A sound is an instance: a second sfizz in a song is a second sfizz on stage.
    to_songs()
    f.tap("1. sfizz")
    logcat_clear()
    f.tap("+ sfizz")
    f.see("sfizz 2")
    f.tap("Save •")
    to_stage()
    broadcast("STAGE", "--ei song 1 --ei liveset 1")
    time.sleep(1.5)
    if not (wait_for("sfizz 1") and wait_for("sfizz 2")):
        raise AssertionError("the stage does not show both sfizz instances")
    if not logcat_has(r"instance \d+ added for sfizz", timeout=3):
        raise AssertionError("no second sfizz instance on the engine")
    # Leave the library as it was.
    to_library()
    for n in find_all("Undo", exact=False):
        shell(f"input tap {n.x} {n.y}")
        break
    time.sleep(1.0)


def undo_last(expect=None):
    """Undo the last save, from the Library (not counted); with [expect], only if the Undo names it --
    a flow that failed before its change, or an import that added nothing, must not undo another's."""
    to_library()
    for n in find_all("Undo", exact=False):
        if expect is not None and expect not in (n.text or n.desc):
            print(f"    (not undoing {n.text!r}: it is not {expect!r})")
            break
        shell(f"input tap {n.x} {n.y}")
        break
    time.sleep(1.0)


def stage_switch(liveset, pattern):
    """Selects a live set of the stage song, true if the log then matches [pattern]."""
    logcat_clear()
    broadcast("STAGE", f"--ei liveset {liveset}")
    return logcat_has(pattern, timeout=3)


# A live set's own sound values: setBfree's 16' drawbar pulled in live set 1
# ("setBfree") only. From the stage the sound opens on the live set playing.
def flow_tweak_in_live_set(f):
    f.tap("setBfree", long=True, pick="lowest-left")
    f.see("Only in “setBfree”")
    f.slide("Upper 16", at=0.0)
    f.see("Upper 16 ●")
    f.see("↺")
    f.tap("Done")
    f.tap("Save •")
    to_stage()
    try:
        if not stage_switch(2, r"live set 2 'sfizz'.*setbfree\.Upper 16=[1-9]"):
            raise AssertionError("leaving the live set did not put the song's drawbar back")
        if not stage_switch(1, r"live set 1 'setBfree'.*setbfree\.Upper 16=0[.,]00"):
            raise AssertionError("the live set's own drawbar was not applied")
    finally:
        undo_last()


def flow_use_in_whole_song(f):
    f.tap("setBfree", long=True, pick="lowest-left")
    f.slide("Upper 16", at=0.0)
    f.tap("Use in song")
    if wait_for("Upper 16 ●", timeout=1.5):
        raise AssertionError("still the live set's own after Use in song")
    f.tap("Done")
    f.tap("Save •")
    to_stage()
    try:
        # The song's value now: the same in every live set, so no change on switching.
        if stage_switch(2, r"setbfree\.Upper 16="):
            raise AssertionError("the drawbar still differs between live sets")
    finally:
        undo_last()


def flow_sound_for_live_set(f):
    # A different preset in one live set: a sound of its own, loaded with the song.
    f.tap("setBfree", long=True, pick="lowest-left")
    f.tap("New sound for this live set")
    f.see("setBfree: patch")          # the preset picker for the new sound
    picks = [n for n in screen() if n.text and n.text not in ("setBfree: patch", "As loaded", "Cancel", "Search")
             and n.y > find_all("Search")[0].y + 60]
    if len(picks) < 2:
        raise AssertionError("no presets to pick")
    shell(f"input tap {picks[1].x} {picks[1].y}")
    f.taps += 1
    time.sleep(0.8)
    f.tap("Save •")
    to_stage()
    try:
        if not (wait_for("setBfree 1") and wait_for("setBfree 2")):
            raise AssertionError("the stage does not show the new sound")
        if not stage_switch(2, r"live set 2 'sfizz'"):
            raise AssertionError("no live set 2")
        # From live set 2, where neither setBfree plays: only the new one comes on.
        if not stage_switch(1, r"live set 1 'setBfree'.*setbfree-2\.midi=on"):
            raise AssertionError("live set 1 does not play the new sound")
        if re.search(r"setbfree\.midi=on", adb("logcat", "-d", "-s", "rigalong-stage:I")):
            raise AssertionError("live set 1 still plays the old setBfree too")
        # The stage's setBfree panel is the setBfree playing: "setBfree 2" on its fader and the panel here,
        # "setBfree 1" where neither plays.
        time.sleep(0.8)
        if len(find_all("setBfree 2")) != 2:
            raise AssertionError("the setBfree panel does not show the one playing (setBfree 2)")
        stage_switch(2, r"live set 2")
        time.sleep(0.8)
        if len(find_all("setBfree 1")) != 2:
            raise AssertionError("with no setBfree playing the panel should show the first")
    finally:
        undo_last()


def cc(number, value):
    broadcast("CC", f"--ei cc {number} --ei value {value}")
    time.sleep(0.4)


# A drawbar pulled on the stage, then held into the live set (I6.9).
def flow_store_in_live_set(f):
    label = find_all("16'")
    if not label:
        raise AssertionError("no setBfree panel")
    n = label[0]
    shell(f"input swipe {n.x} {n.y + 120} {n.x} {n.y + 700} 400")   # drag down: fewer feet of 16'
    f.taps += 1
    time.sleep(0.6)
    logcat_clear()
    f.tap("Store in this live set (hold)", long=True)
    try:
        if not logcat_has(r"stored into live set 1: the levels and \[setbfree\.Upper 16\]"):
            raise AssertionError("the drawbar was not stored into the live set")
        to_stage()
        if not stage_switch(2, r"live set 2 'sfizz'.*setbfree\.Upper 16=8[.,]00"):
            raise AssertionError("live set 2 does not have the song's drawbar")
        if not stage_switch(1, r"live set 1 'setBfree'.*setbfree\.Upper 16=[0-7][.,]00"):
            raise AssertionError("live set 1 does not have its stored drawbar")
    finally:
        undo_last()


# A knob for a sound's parameter: learned in the sound dialog, then it moves
# the drawbar on the stage -- only after it has picked up the drawbar's value.
def flow_knob_controls_sound(f):
    f.tap("setBfree", long=True, pick="lowest-left")
    row = find_all("Upper 16")[0]
    learn = [n for n in find_all("MIDI") if abs(n.y - row.y) < 20]
    if not learn:
        raise AssertionError("no MIDI learn on the 16' row")
    shell(f"input tap {learn[0].x} {learn[0].y}")
    f.taps += 1
    f.see("Move a control…")
    cc(21, 64)
    f.see("CC 21 ✕")
    f.tap("Done")
    f.tap("Save •")
    to_stage()
    try:
        logcat_clear()
        cc(21, 0)            # the drawbar is at 8: a knob at 0 or 2 has not reached it
        cc(21, 40)
        if logcat_has(r"control setbfree\.Upper 16", timeout=1.5):
            raise AssertionError("the knob moved the drawbar before picking it up")
        cc(21, 127)          # reaches 8: picked up
        cc(21, 0)
        if not logcat_has(r"control setbfree\.Upper 16 -> 0[.,]00"):
            raise AssertionError("the knob does not move the drawbar")
    finally:
        undo_last()


# A CP88/CP73 file into the library through the file picker (I8.8, I4.17):
# a page of cp-liveset's sample sounds, pushed to Downloads first.
X9P = "sample-page-7.X9P"


def import_device_file(f, fname, count, seen):
    here = os.path.dirname(os.path.abspath(__file__))
    adb("push", os.path.join(here, "..", "model", "src", "test", "resources", "cp88", fname), "/sdcard/Download/" + fname)
    to_library()
    f.tap("Import…")
    time.sleep(2.0)
    if not wait_for(fname, timeout=2):
        # The picker opens where it was last; the first time, Downloads is in its roots drawer.
        for n in screen():
            if n.desc in ("Show roots", "Roots tonen") or n.text in ("Show roots", "Roots tonen"):
                shell(f"input tap {n.x} {n.y}")
                f.taps += 1
                time.sleep(1.0)
                break
        f.tap("Downloads")
        time.sleep(1.5)
    f.tap(fname)
    try:
        f.see(f"{count} Yamaha CP88/CP73 sounds in the file", exact=False, timeout=8)
        # It may be in the library already (a page read back before a write keeps its sounds there).
        if not see_scrolling(seen):
            raise AssertionError(f"expected to see {seen!r}")
    finally:
        if "be.mdw.rigalong" in focused_app():
            undo_last(fname)
        shell(f"rm -f /sdcard/Download/{fname}")


def flow_import_cp88_file(f):
    import_device_file(f, X9P, 8, "Test 7-6")


# cp-liveset's full JSON, a document over several pages.
def flow_import_cp88_json(f):
    import_device_file(f, "json-doc.json", 8, "Test 3-4")


# A live set given a CP-88 sound from the library: written into the
# keyboard's reserved page when the song opens.
def flow_cp88_sound_from_library(f):
    here = os.path.dirname(os.path.abspath(__file__))
    adb("push", os.path.join(here, "..", "model", "src", "test", "resources", "cp88", X9P), "/sdcard/Download/" + X9P)
    to_library()
    for n in find_all("Import…"):
        shell(f"input tap {n.x} {n.y}")
    time.sleep(2.0)
    for n in find_all(X9P):
        shell(f"input tap {n.x} {n.y}")
    msg = wait_for("Yamaha CP88/CP73 sounds in the file", exact=False, timeout=8)
    if not msg:
        raise AssertionError("setup: the page did not import")
    # An import that added nothing is no change, and must not be undone (it would undo another's).
    imported = not re.search(r"\b0 new\b", msg[0].text)
    saves = 0
    try:
        to_stage()
        f.tap("setBfree", long=True, pick="highest")          # live set 1 in the editor
        f.see("Live set 1", exact=False)
        f.tap("Own sound", pick="first")
        f.tap("From library…", pick="first")
        see_scrolling("Test 7-6")
        f.tap("Test 7-6")
        logcat_clear()
        f.tap("Save •")
        saves += 1
        if not logcat_has(r"CP-88: page \d+, [1-9]\d* slot\(s\) written", timeout=5):
            raise AssertionError("the sound was not written into the reserved page")
    finally:
        for _ in range(saves + (1 if imported else 0)):
            undo_last()
        shell(f"rm -f /sdcard/Download/{X9P}")


# A CP-88 sound edited as cp-liveset's YAML in the Library: the text is
# checked as it is typed, and saving keeps it as the sound's new version.
def flow_cp88_yaml_edit(f):
    here = os.path.dirname(os.path.abspath(__file__))
    adb("push", os.path.join(here, "..", "model", "src", "test", "resources", "cp88", X9P), "/sdcard/Download/" + X9P)
    to_library()
    for n in find_all("Import…"):
        shell(f"input tap {n.x} {n.y}")
    time.sleep(2.0)
    for n in find_all(X9P):
        shell(f"input tap {n.x} {n.y}")
    msg = wait_for("Yamaha CP88/CP73 sounds in the file", exact=False, timeout=8)
    if not msg:
        raise AssertionError("setup: the page did not import")
    # An import that added nothing is no change, and must not be undone (it would undo another's).
    imported = not re.search(r"\b0 new\b", msg[0].text)
    saves = 1 if imported else 0
    try:
        # The YAML… of Test 7-6's own row.
        see_scrolling("Test 7-6")
        row = find_all("Test 7-6")[0]
        button = min(find_all("YAML…"), key=lambda n: abs(n.y - row.y))
        shell(f"input tap {button.x} {button.y}")
        f.taps += 1
        f.see("Reads as “Test 7-6”")
        f.see("name: Test 7-6", exact=False)
        f.tap("name: Test 7-6", exact=False)                # into the text
        shell("input keycombination KEYCODE_CTRL_LEFT KEYCODE_A")
        shell("input text 'name:%sFlow%sYaml'")
        f.see("Reads as “Flow Yaml” •")
        f.tap("Save")
        saves += 1
        f.see("Flow Yaml")
    finally:
        for _ in range(saves):
            undo_last()
        shell(f"rm -f /sdcard/Download/{X9P}")


# A library patch saved as its engine's own file -- Surge XT's .fxp, a DX7
# bank for Dexed, a SoundFont for FluidSynth -- and the file imported back.
# Counted: the patch's save button and the file picker's Save.
SAVE_BUTTONS = ("OPSLAAN", "SAVE", "Save", "Opslaan")


def make_patch(engine, search, pick, name, param, at):
    """Setup, not counted: library patch [name], an [engine] sound of song 3 set to [pick], [param] moved."""
    s = Flow("setup", 99)
    to_songs()
    s.tap("3. Surge XT and sfizz")
    if engine != "Surge XT":
        s.tap("+ " + engine)
        time.sleep(1.5)
    end = find_all("Add a sound:")[0].y
    row = [n for n in find_all(engine) if n.y < end and n.x < 900][-1]
    choice = [n for n in screen() if abs(n.y - row.y) < 12 and 1200 < n.x < 1900][0]
    shell(f"input tap {choice.x} {choice.y}")
    time.sleep(1.2)
    if search:                         # a short list is tapped as it is
        s.tap("Search")
        type_text(search)
    s.tap(pick, pick="last")
    time.sleep(1.5)
    patch = [n for n in find_all("Patch…") if abs(n.y - row.y) < 12][0]
    shell(f"input tap {patch.x} {patch.y}")
    time.sleep(1.2)
    s.slide(param, at)
    s.tap("Save as library patch…")
    s.tap("Patch name")
    shell("input keyevent KEYCODE_MOVE_END")
    for _ in range(40):
        shell("input keyevent KEYCODE_DEL")
    type_text(name)
    s.tap("Save")
    s.see(f"Library patch “{name}”", exact=False)
    s.tap("Done")


def pull_download(fname):
    r = subprocess.run([ADB, "exec-out", "cat", "/sdcard/Download/" + fname], capture_output=True, timeout=60)
    return r.stdout


def export_patch(f, engine, search, pick, name, param, at, ext, check):
    fname = f"{name}.{ext}"
    shell(f"rm -f /sdcard/Download/{fname}")
    saved = False
    try:
        make_patch(engine, search, pick, name, param, at)
        saved = True
        to_library()
        if not see_scrolling(name):
            raise AssertionError(f"no patch {name!r} in the Library")
        row = find_all(name)[0]
        button = [n for n in find_all("." + ext) if abs(n.y - row.y) < 15]
        if not button:
            raise AssertionError(f"no .{ext} beside {name!r}")
        shell(f"input tap {button[0].x} {button[0].y}")
        f.taps += 1
        for _ in range(20):
            if any(find_all(t) for t in SAVE_BUTTONS):
                break
            time.sleep(0.5)
        f.tap(next(t for t in SAVE_BUTTONS if find_all(t)))
        f.see(f"Saved “{fname}”", exact=False, timeout=10)
        data = pull_download(fname)
        why = check(data)
        if why:
            raise AssertionError(f"{fname}: {why}")
        # Back in through Import: the engine takes its own file.
        for n in find_all("Import…"):
            shell(f"input tap {n.x} {n.y}")
        time.sleep(2.0)
        if not wait_for(fname, timeout=2):
            for t in ("Show roots", "Roots tonen"):
                for n in find_all(t):
                    shell(f"input tap {n.x} {n.y}")
                    time.sleep(1.0)
            for n in find_all("Downloads"):
                shell(f"input tap {n.x} {n.y}")
                break
            time.sleep(1.5)
        for n in find_all(fname):
            shell(f"input tap {n.x} {n.y}")
            break
        if not (wait_for(f"for {engine}: it is now in its patch lists", exact=False, timeout=10)
                or wait_for("is installed already", exact=False, timeout=1)):
            raise AssertionError(f"{fname} did not import back into {engine}")
    finally:
        if "be.mdw.rigalong" in focused_app():
            if saved:
                undo_last(name)
            broadcast("RESCAN", "--es", "remove", fname)
        shell(f"rm -f /sdcard/Download/{fname}")


def check_fxp(name):
    return lambda b: None if b[:4] == b"CcnK" and b[16:20] == b"cjs3" and b[28:28 + len(name)] == name.encode() \
        else "not a Surge XT patch of that name"


def check_syx(name):
    return lambda b: None if len(b) == 4104 and b[:6] == bytes([0xF0, 0x43, 0, 9, 0x20, 0]) and \
        b[6 + 118:6 + 118 + len(name)] == name[:10].encode() else "not a DX7 bank with that voice first"


def check_soundfont(name):
    return lambda b: None if b[:4] == b"RIFF" and b[8:12] == b"sfbk" and b"INAM" in b[:400] and \
        name.encode() in b[:400] else "not a SoundFont of that name"


def flow_export_surge_patch(f):
    export_patch(f, "Surge XT", "Behemoth", "Basses/Behemoth", "FlowSurge", "Macro 1", 0.3, "fxp", check_fxp("FlowSurge"))


def flow_export_dexed_patch(f):
    export_patch(f, "Dexed", "PHAROH", "Dexed_01/PHAROH", "FlowDexed", "Transpose", 0.25, "syx", check_syx("FlowDexed"))


def flow_export_fluid_patch(f):
    export_patch(f, "FluidSynth", None, "VintageDreamsWaves-v2.sf3", "FlowFluid", "Transpose", 0.66, "sf3",
                 check_soundfont("FlowFluid"))


FLOWS = [
    # name, budget (taps), function
    ("select-live-set", 1, flow_select_live_set),
    ("next-song", 1, flow_next_song),
    ("quick-edit-live-set", 1, flow_quick_edit_live_set),
    ("quick-edit-patch", 2, flow_quick_edit_patch),
    ("count-in", 5, flow_count_in),
    ("share-patch", 8, flow_share_patch),
    ("reuse-patch", 4, flow_reuse_patch),
    ("update-everywhere", 5, flow_update_everywhere),
    ("undo-all", 4, flow_undo_all),
    ("second-sfizz", 3, flow_second_sfizz),
    ("tweak-in-live-set", 4, flow_tweak_in_live_set),
    ("use-in-whole-song", 5, flow_use_in_whole_song),
    ("sound-for-live-set", 4, flow_sound_for_live_set),
    ("store-in-live-set", 2, flow_store_in_live_set),
    ("knob-controls-sound", 4, flow_knob_controls_sound),
    ("import-cp88-file", 4, flow_import_cp88_file),
    ("import-cp88-json", 4, flow_import_cp88_json),
    ("cp88-sound-from-library", 5, flow_cp88_sound_from_library),
    ("cp88-yaml-edit", 3, flow_cp88_yaml_edit),
    ("export-surge-patch", 2, flow_export_surge_patch),
    ("export-dexed-patch", 2, flow_export_dexed_patch),
    ("export-fluid-patch", 2, flow_export_fluid_patch),
]


def main(argv):
    if "be.mdw.rigalong" not in focused_app():
        print("the app is not in front; start it first")
        return 2
    want = set(argv) or {name for name, _, _ in FLOWS}
    failed = 0
    for name, budget, fn in FLOWS:
        if name not in want:
            continue
        to_stage()
        f = Flow(name, budget)
        try:
            fn(f)
            ok = f.taps <= budget
            why = "" if ok else f"took {f.taps} taps, budget {budget}"
        except AssertionError as e:
            ok, why = False, str(e)
        failed += 0 if ok else 1
        print(f"  {name:<24} {'ok' if ok else 'FAILED'}  ({f.taps} taps){'  ' + why if why else ''}")
    to_stage()
    print(f"{len(want)} flows, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
