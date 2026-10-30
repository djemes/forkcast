"""Small adb + UI Automator helpers for tools/screenshots/capture.py. Emulator only (CLAUDE.md, Phone safety)."""
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

SERIAL = os.environ.get("ANDROID_SERIAL", "emulator-5554")
if not SERIAL.startswith("emulator-"):
    raise SystemExit("Screenshots run on the emulator only, never on a phone (set ANDROID_SERIAL=emulator-NNNN).")

SDK = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
ADB = os.path.join(SDK, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
ENV = dict(os.environ, MSYS_NO_PATHCONV="1")


def adb(*args, check=True, binary=False):
    out = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, check=check, env=ENV)
    return out.stdout if binary else out.stdout.decode("utf-8", "replace")


def sh(cmd, check=True):
    return adb("shell", cmd, check=check)


def nodes():
    """Every node of the current screen: (text, content-desc, bounds center, clickable, scrollable, resource-id)."""
    for _ in range(3):
        sh("uiautomator dump /sdcard/window.xml >/dev/null 2>&1", check=False)
        xml = adb("exec-out", "cat /sdcard/window.xml", check=False)
        if xml.strip().startswith("<?xml"):
            break
        time.sleep(0.5)
    else:
        return []
    out = []
    for n in ET.fromstring(xml).iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        out.append({
            "text": n.get("text", ""), "desc": n.get("content-desc", ""), "id": n.get("resource-id", ""),
            "x": (x1 + x2) // 2, "y": (y1 + y2) // 2, "box": (x1, y1, x2, y2),
            "clickable": n.get("clickable") == "true", "scrollable": n.get("scrollable") == "true",
            "checked": n.get("checked") == "true",
        })
    return out


def find(text=None, desc=None, contains=False, timeout=8.0):
    """The first node whose text or content-desc matches; waits for it up to [timeout] seconds."""
    end = time.time() + timeout
    while True:
        for n in nodes():
            for want, have in ((text, n["text"]), (desc, n["desc"])):
                if want is None:
                    continue
                if (want in have) if contains else (have == want):
                    return n
        if time.time() > end:
            return None
        time.sleep(0.5)


def tap(text=None, desc=None, contains=False, timeout=8.0, required=True):
    n = find(text, desc, contains, timeout)
    if n is None:
        if required:
            raise RuntimeError(f"Not on screen: text={text!r} desc={desc!r}")
        return False
    sh(f"input tap {n['x']} {n['y']}")
    time.sleep(0.8)
    return True


def tap_xy(x, y):
    sh(f"input tap {x} {y}")
    time.sleep(0.8)


def scroll_to(text=None, desc=None, contains=False, tries=8):
    """Swipes up until the node is on screen."""
    size = screen_size()
    for _ in range(tries):
        n = find(text, desc, contains, timeout=1.0)
        if n is not None:
            return n
        sh(f"input swipe {size[0] // 2} {int(size[1] * 0.7)} {size[0] // 2} {int(size[1] * 0.35)} 400")
        time.sleep(0.6)
    return None


def screen_size():
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    return int(m.group(1)), int(m.group(2))


def screenshot(path):
    png = adb("exec-out", "screencap -p", binary=True)
    with open(path, "wb") as f:
        f.write(png)


def system_bar_heights():
    """Status bar and navigation bar heights in pixels, from the window insets (0 if not found)."""
    dump = sh("dumpsys window", check=False)
    top = re.findall(r"type=statusBars[^\n]*?frame=\[\d+,\d+\]\[\d+,(\d+)\]", dump)
    bottom = re.findall(r"type=navigationBars[^\n]*?frame=\[\d+,(\d+)\]\[\d+,(\d+)\]", dump)
    status = int(top[0]) if top else 0
    nav = (int(bottom[0][1]) - int(bottom[0][0])) if bottom else 0
    return status, nav


def wait_idle(seconds=1.5):
    time.sleep(seconds)
