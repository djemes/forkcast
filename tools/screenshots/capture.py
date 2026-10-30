"""Captures the README's screenshots on the emulator, with demo data only (roadmap 2.0, Phase 5.3).

  python -X utf8 tools/screenshots/capture.py [--apk path/to/app.apk] [--out assets/readme]

What it does, on emulator-5554 (or ANDROID_SERIAL=emulator-NNNN; it refuses anything else):
  1. optionally installs --apk over the app (any build of app.forkcast)
  2. clears the app's data, builds the demo seed (tools/seed, --demo: fixed random seed, no brands,
     no photos) and restores it through the welcome screen's "Restore a backup", like a user would
  3. for light and dark (cmd uimode night): Today, the Log sheet, Today's budget, Trends and
     Backup & data; screens are cropped below the status bar and above the navigation bar
  4. tools/screenshots/compose.py turns the raw captures into assets/readme (resized WebP,
     the hero banners and the social preview)

Never run it against a phone: it clears the app's data. The emulator needs to be unlocked, in
English (en-US), with the app's units at their defaults. The ring depends on the time of day,
which a Play Store emulator image can't fix, so run it in the afternoon for a natural-looking day.
"""
import argparse
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui  # noqa: E402

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
PACKAGE = "app.forkcast"
SEED_ON_DEVICE = "/sdcard/Download/forkcast-demo.forkcast"


def launch():
    ui.sh(f"monkey -p {PACKAGE} -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
    ui.wait_idle(3)


def restore_demo(raw_dir):
    seed = os.path.join(raw_dir, "demo.forkcast")
    subprocess.run([sys.executable, "-X", "utf8", os.path.join(ROOT, "tools", "seed", "make_seed_backup.py"), seed, "--demo", "--days", "180"], check=True)
    ui.adb("push", seed, SEED_ON_DEVICE)
    ui.sh(f"pm clear {PACKAGE}")
    ui.sh("input keyevent KEYCODE_WAKEUP")
    launch()
    ui.tap("I am 18 or older")
    ui.tap("I have read and agree to the Terms of Use and Disclaimer")
    ui.tap("I agree")
    ui.tap("Restore a backup", contains=True)
    ui.wait_idle(2.5)
    ui.tap(desc="Search")
    ui.sh("input text forkcast-demo")
    ui.sh("input keyevent 66")
    ui.tap("forkcast-demo.forkcast", timeout=10)
    ui.tap("Restore", timeout=15)
    ui.tap("OK", timeout=30)
    ui.wait_idle(2)
    if ui.find(desc="Log", timeout=10) is None:
        raise RuntimeError("Today didn't open after the restore")


def back_to_today():
    for _ in range(4):
        if ui.find(desc="Open trends", timeout=1.0) is not None:
            return
        ui.sh("input keyevent 4")
        ui.wait_idle(0.8)
    launch()


def capture_theme(theme, raw_dir):
    ui.sh(f"cmd uimode night {'yes' if theme == 'dark' else 'no'}")
    ui.wait_idle(2.5)
    back_to_today()
    ui.wait_idle(1.5)
    shot = lambda name: ui.screenshot(os.path.join(raw_dir, f"{name}-{theme}.png"))

    shot("today")

    ui.tap("Budget", contains=True)
    ui.wait_idle(1.5)
    shot("budget")
    ui.sh("input keyevent 4")
    ui.wait_idle(1)

    ui.tap(desc="Log")
    ui.wait_idle(1.5)
    shot("log")
    ui.sh("input keyevent 4")
    ui.wait_idle(1)

    ui.tap(desc="Open trends")
    ui.wait_idle(2.5)
    shot("trends")
    back_to_today()

    ui.tap(desc="Settings")
    ui.wait_idle(1.5)
    if ui.scroll_to("Backup & data") is not None:
        ui.tap("Backup & data")
        ui.wait_idle(2)
        shot("backup")
    back_to_today()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk")
    parser.add_argument("--out", default=os.path.join(ROOT, "assets", "readme"))
    parser.add_argument("--raw", default=os.path.join(ROOT, "build", "readme-raw"))
    parser.add_argument("--skip-restore", action="store_true", help="reuse the demo data already on the emulator")
    args = parser.parse_args()
    os.makedirs(args.raw, exist_ok=True)

    if args.apk:
        ui.adb("install", "-r", args.apk)
    if not args.skip_restore:
        restore_demo(args.raw)
    try:
        for theme in ("light", "dark"):
            capture_theme(theme, args.raw)
    finally:
        ui.sh("cmd uimode night no", check=False)

    status, nav = ui.system_bar_heights()
    subprocess.run([sys.executable, "-X", "utf8", os.path.join(os.path.dirname(__file__), "compose.py"),
                    "--raw", args.raw, "--out", args.out, "--crop-top", str(status), "--crop-bottom", str(nav)], check=True)
    print(f"Done in {time.process_time():.0f} s of CPU; images in {args.out}")


if __name__ == "__main__":
    main()
