#!/usr/bin/env python3
"""flowcheck -- the things a player does often, done on the real app, counted.

Drives Bag-O-Synths on a device over adb, finding controls by the text they
show (uiautomator), never by coordinates, so a layout change does not break it
and a renamed control does. Each flow asserts what the player should see or
hear happen, and has a TAP BUDGET: an action that is meant to be quick and
takes more taps than that fails, so "simple" is measured, not claimed.

    ANDROID_SERIAL=<device> python3 tools/flowcheck.py [flow-name ...]

The app must be installed and in front, on a setlist with the demo songs
(the first-run seed: "Piano", "Organ and piano", "Pad and piano"). Flows that
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


def focused_app():
    return shell("dumpsys window | grep mCurrentFocus")


def logcat_clear():
    adb("logcat", "-c")


def logcat_has(pattern, timeout=4.0):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if re.search(pattern, adb("logcat", "-d", "-s", "bos-stage:I")):
            return True
        time.sleep(0.3)
    return False


def broadcast(action, *extras):
    shell(f"am broadcast -a be.mdw.bagosynths.{action} " + " ".join(extras))


# ---------------------------------------------------------------- flows

class Flow:
    def __init__(self, name, budget):
        self.name, self.budget, self.taps = name, budget, 0

    def tap(self, text, long=False, pick="first", exact=True, times=1, interval=0.45):
        found = find_all(text, exact)
        if not found:
            raise AssertionError(f"nothing reads {text!r}")
        n = {"first": found[0], "last": found[-1],
             "lowest": max(found, key=lambda n: n.y), "highest": min(found, key=lambda n: n.y)}[pick]
        for k in range(times):
            if long:
                shell(f"input swipe {n.x} {n.y} {n.x} {n.y} 900")
            else:
                shell(f"input tap {n.x} {n.y}")
            self.taps += 1
            time.sleep(interval if k + 1 < times else 0.6)

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
    f.tap("Piano", long=True, pick="lowest")
    f.see("Piano: patch")
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


# The shared-patch round trip: share the piano's patch, use it in a second
# song, change it in the first and update it everywhere, check both songs
# follow -- then three Undos leave the library as it was.
PATCH = "FlowPiano"


def flow_share_patch(f):
    to_songs()
    f.tap("1. Piano")
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
    f.tap("3. Pad and piano")
    f.tap("(as loaded)")
    f.tap(PATCH)
    f.tap("Save •")


def flow_update_everywhere(f):
    to_songs()
    f.tap("1. Piano")
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
]


def main(argv):
    if "be.mdw.bagosynths" not in focused_app():
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
