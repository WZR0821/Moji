"""Capture opt-in Debug fixtures in disposable simulators; never seed a real device.

Run with the bundled Python/Pillow runtime. Output is evidence, not a pixel-match
claim: status bars, modal presentation and system fonts remain platform-owned.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET
from PIL import Image, ImageDraw, ImageStat

ROOT = Path(__file__).resolve().parents[1]
ADB = "/Users/Shared/JerreaderAndroidSdk/platform-tools/adb"
SIM = "4DB16103-3C6C-4FCB-A567-142F58531965"
ENV = dict(os.environ, DEVELOPER_DIR="/Users/raydon/Downloads/Xcode.app/Contents/Developer")
ROUTES = ["plans", "plan-expanded", "plan-completed", "plan-editor", "plan-copy", "library", "templates", "memo", "memo-note", "memo-checklist", "memo-convert", "moments", "moment-past", "moment-editor", "focus", "review", "review-week", "review-month", "review-records", "record-editor", "record-untimed", "goal", "reflection", "next-week", "settings"]

IOS_ROUTE_TEXT = {
    "settings-pomodoro": "长休息间隔", "settings-defaults": "默认安排",
    "settings-widgets:0": "按钮文字", "settings-notifications": "全天计划提醒",
    "settings-appearance": "显示模式", "settings-permissions": "请求通知权限",
    "settings-backup": "从单个备份文件导入", "settings-about": "数据格式",
    "plan-inline": "快捷编辑", "plan-editor-advanced": "高级设置",
    "record-untimed": "编辑记录", "calendar-month": "当日安排",
    "calendar-week": "星期一", "calendar-day": "具体时间", "calendar-jump": "跳转到",
}

def verify_ios_route(route, staging, ocr_tool):
    expected = IOS_ROUTE_TEXT.get(route)
    if not expected or not ocr_tool: return True
    data = json.loads(run([str(ocr_tool), str(staging)], capture_output=True, text=True).stdout)
    text = "".join(data[str(staging)]).replace(" ", "")
    return expected in text

def verify_android_route(route):
    expected = {"plan-inline": "快捷编辑", "record-untimed": "编辑记录", "plan-editor-advanced": "高级设置", "calendar-jump": "跳转到"}.get(route)
    if not expected: return
    for attempt in range(3):
        run([ADB, "-s", "emulator-5554", "shell", "uiautomator", "dump", "/sdcard/moji-qa-window.xml"], stdout=subprocess.DEVNULL)
        xml = run([ADB, "-s", "emulator-5554", "shell", "cat", "/sdcard/moji-qa-window.xml"], capture_output=True, text=True).stdout
        texts = [node.get("text", "") for node in ET.fromstring(xml).iter("node")]
        if expected in texts: return
        time.sleep(2)
    raise RuntimeError(f"Android route is not visible: {route} (expected {expected})")

def run(args, **kwargs):
    return subprocess.run(args, check=True, timeout=40, env=kwargs.pop("env", ENV), **kwargs)

def ios_tab(route):
    if route.startswith("memo"): return "memos"
    if route.startswith(("moment", "focus")): return "countdowns"
    if route.startswith(("review", "record", "settings")) or route in ["goal", "reflection", "next-week"]: return "profile"
    return "checklist"

def capture(platform, route, mode, output, settle_seconds=15, ocr_tool=None):
    destination = output / platform / f"{route}-{mode}.png"
    destination.parent.mkdir(parents=True, exist_ok=True)
    staging = destination.with_name(f"{route}-{mode}.pending.png")
    if platform == "ios":
        scenario = "pomodoro-duration-editor" if route == "focus-duration" else f"workflow-{route}"
        env = dict(ENV, SIMCTL_CHILD_MOJI_QA_SEED="1", SIMCTL_CHILD_MOJI_QA_SCENARIO=scenario, SIMCTL_CHILD_MOJI_QA_TAB=ios_tab(route), SIMCTL_CHILD_MOJI_QA_COUNTDOWN_MODE="pomodoro" if route.startswith("focus") else "anniversary" if route == "moment-past" else "countdown")
        run(["xcrun", "simctl", "ui", SIM, "appearance", mode], stdout=subprocess.DEVNULL)
        run(["xcrun", "simctl", "launch", "--terminate-running-process", SIM, "com.raydon.minuteplan", "-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"], env=env, stdout=subprocess.DEVNULL)
        # A cold simulator launch can display its blank launch view long after
        # simctl returns. Do not silently count that or SpringBoard as app QA.
        for attempt in range(4):
            time.sleep(settle_seconds if attempt == 0 else 10)
            run(["xcrun", "simctl", "io", SIM, "screenshot", str(staging)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            with Image.open(staging) as shot:
                sample = shot.convert("RGB").crop((60, 350, 1120, 2350)).resize((106, 200))
                colorful = sum(max(rgb)-min(rgb) > 55 for rgb in sample.getdata()) / (106*200)
                blank = max(ImageStat.Stat(sample).stddev) < 8
            if not blank and colorful < 0.04 and verify_ios_route(route, staging, ocr_tool): break
        else: raise RuntimeError(f"iOS screenshot did not settle into Moji: {route}")
    else:
        run([ADB, "-s", "emulator-5554", "shell", "cmd", "uimode", "night", "yes" if mode == "dark" else "no"], stdout=subprocess.DEVNULL)
        run([ADB, "-s", "emulator-5554", "shell", "am", "force-stop", "com.raydon.moji.android"], stdout=subprocess.DEVNULL)
        run([ADB, "-s", "emulator-5554", "shell", "am", "start", "-W", "-n", "com.raydon.moji.android/.MainActivity", "--ez", "moji_qa_seed", "true", "--es", "moji_qa_route", route], stdout=subprocess.DEVNULL)
        # `am start -W` waits for the first draw. Allow the fixture route and
        # its sheet animation to settle after that, instead of timing splash.
        time.sleep(3)
        verify_android_route(route)
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
    parser.add_argument("--output", type=Path, default=ROOT / "build/1.5.1-qa")
    parser.add_argument("--settle-seconds", type=int, default=15)
    parser.add_argument("--ocr-tool", type=Path, help="Optional compiled Scripts/qa_ocr.swift on macOS")
    args = parser.parse_args()
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    routes = args.routes.split(",")
    if args.platform in ["ios", "both"]:
        container = run(["xcrun", "simctl", "get_app_container", SIM, "com.raydon.minuteplan", "data"], capture_output=True, text=True).stdout.strip()
        run(["ditto", str(ROOT / "Scripts/fixtures/workflow-1.5.json"), str(Path(container) / "Documents/MojiVisualQA.json")])
    if args.platform != "compare":
        for route in routes:
            for platform in ["ios", "android"] if args.platform == "both" else [args.platform]: capture(platform, route, args.mode, output, args.settle_seconds, args.ocr_tool)
    comparisons(routes, args.mode, output)
