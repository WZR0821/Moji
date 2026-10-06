"""Capture opt-in Debug fixtures in disposable simulators; never seed a real device.

Run with the bundled Python/Pillow runtime. Output is evidence, not a pixel-match
claim: status bars, modal presentation and system fonts remain platform-owned.
"""
import argparse
import os
from pathlib import Path
import subprocess
import time
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
ADB = "/Users/Shared/JerreaderAndroidSdk/platform-tools/adb"
SIM = "4DB16103-3C6C-4FCB-A567-142F58531965"
ENV = dict(os.environ, DEVELOPER_DIR="/Users/raydon/Downloads/Xcode.app/Contents/Developer")
ROUTES = ["plans", "plan-expanded", "plan-completed", "plan-editor", "plan-copy", "library", "templates", "memo", "memo-note", "memo-checklist", "memo-convert", "moments", "moment-past", "moment-editor", "focus", "review", "review-week", "review-month", "review-records", "record-editor", "record-untimed", "goal", "reflection", "next-week", "settings"]

def run(args, **kwargs):
    return subprocess.run(args, check=True, timeout=40, env=kwargs.pop("env", ENV), **kwargs)

def ios_tab(route):
    if route.startswith("memo"): return "memos"
    if route.startswith(("moment", "focus")): return "countdowns"
    if route.startswith(("review", "record", "settings")) or route in ["goal", "reflection", "next-week"]: return "profile"
    return "checklist"

def capture(platform, route, mode, output):
    destination = output / platform / f"{route}-{mode}.png"
    destination.parent.mkdir(parents=True, exist_ok=True)
    staging = destination.with_name(f"{route}-{mode}.pending.png")
    if platform == "ios":
        env = dict(ENV, SIMCTL_CHILD_MOJI_QA_SEED="1", SIMCTL_CHILD_MOJI_QA_SCENARIO=f"workflow-{route}", SIMCTL_CHILD_MOJI_QA_TAB=ios_tab(route), SIMCTL_CHILD_MOJI_QA_COUNTDOWN_MODE="pomodoro" if route.startswith("focus") else "anniversary" if route == "moment-past" else "countdown")
        run(["xcrun", "simctl", "ui", SIM, "appearance", mode], stdout=subprocess.DEVNULL)
        run(["xcrun", "simctl", "launch", "--terminate-running-process", SIM, "com.raydon.minuteplan", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"], env=env, stdout=subprocess.DEVNULL)
        time.sleep(5)
        run(["xcrun", "simctl", "io", SIM, "screenshot", str(staging)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    else:
        run([ADB, "-s", "emulator-5554", "shell", "cmd", "uimode", "night", "yes" if mode == "dark" else "no"], stdout=subprocess.DEVNULL)
        run([ADB, "-s", "emulator-5554", "shell", "am", "force-stop", "com.raydon.moji.android"], stdout=subprocess.DEVNULL)
        run([ADB, "-s", "emulator-5554", "shell", "am", "start", "-W", "-n", "com.raydon.moji.android/.MainActivity", "--ez", "moji_qa_seed", "true", "--es", "moji_qa_route", route], stdout=subprocess.DEVNULL)
        # `am start -W` waits for the first draw. Allow the fixture route and
        # its sheet animation to settle after that, instead of timing splash.
        time.sleep(3)
        with staging.open("wb") as stream:
            run([ADB, "-s", "emulator-5554", "exec-out", "screencap", "-p"], stdout=stream)
    with Image.open(staging) as captured:
        captured.verify()
    staging.replace(destination)
    print(f"captured {platform}: {route} / {mode}", flush=True)

def comparisons(routes, mode, output):
    pairs = []
    for route in routes:
        ios = output / "ios" / f"{route}-{mode}.png"
        android = output / "android" / f"{route}-{mode}.png"
        if not (ios.exists() and android.exists()): continue
        pair = Image.new("RGB", (690, 772), "#e7e3dc")
        draw = ImageDraw.Draw(pair)
        draw.text((10, 8), f"{route} / {mode}: iOS | Android", fill="black")
        for column, path in enumerate([ios, android]):
            with Image.open(path) as original:
                thumb = original.convert("RGB").resize((330, 715), Image.Resampling.LANCZOS)
            pair.paste(thumb, (10 + column * 345, 32))
        directory = output / "comparisons"
        directory.mkdir(exist_ok=True)
        pair.save(directory / f"{route}-{mode}.png")
        pairs.append(pair)
    for page in range(0, len(pairs), 4):
        sheet = Image.new("RGB", (1380, 1544), "#e7e3dc")
        for index, pair in enumerate(pairs[page:page+4]): sheet.paste(pair, ((index % 2)*690, (index//2)*772))
        sheet.save(output / "comparisons" / f"sheet-{mode}-{page//4+1:02}.png")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--platform", choices=["ios", "android", "both", "compare"], default="both")
    parser.add_argument("--routes", default=",".join(ROUTES))
    parser.add_argument("--mode", choices=["light", "dark"], default="light")
    args = parser.parse_args()
    output = ROOT / "build/1.5.0-qa"
    routes = args.routes.split(",")
    if args.platform in ["ios", "both"]:
        container = run(["xcrun", "simctl", "get_app_container", SIM, "com.raydon.minuteplan", "data"], capture_output=True, text=True).stdout.strip()
        run(["ditto", str(ROOT / "Scripts/fixtures/workflow-1.5.json"), str(Path(container) / "Documents/MojiVisualQA.json")])
    if args.platform != "compare":
        for route in routes:
            for platform in ["ios", "android"] if args.platform == "both" else [args.platform]: capture(platform, route, args.mode, output)
    comparisons(routes, args.mode, output)
