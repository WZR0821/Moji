"""Small interaction smoke check on the disposable Android emulator, never a phone."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/1.6.0-qa/manual"
ADB = "/Users/Shared/JerreaderAndroidSdk/platform-tools/adb"
SERIAL = "emulator-5554"
PACKAGE = "com.raydon.moji.android"
ORIGINAL = "整理 1.6.0 发布说明"
SAVED = "Moji 1.6.0 saved check"
RESULTS = []

def adb(*args, **kwargs):
    return subprocess.run([ADB, "-s", SERIAL, *args], check=True, timeout=35, **kwargs)

def text(*args):
    return adb(*args, capture_output=True, text=True).stdout

def dump(name=None):
    adb("shell", "uiautomator", "dump", "/sdcard/moji16-qa.xml", stdout=subprocess.DEVNULL)
    xml = text("shell", "cat", "/sdcard/moji16-qa.xml")
    if name:
        (OUT / f"{name}.xml").write_text(xml)
    return ET.fromstring(xml)

def wait_for(predicate, label, attempts=8):
    for _ in range(attempts):
        root = dump()
        matches = [n for n in root.iter("node") if predicate(n)]
        if matches:
            return matches[0]
        time.sleep(0.5)
    raise RuntimeError(f"Expected UI was not visible: {label}")

def node(label):
    return wait_for(lambda n: n.get("text") == label or n.get("content-desc") == label, label)

def tap_node(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds", "")))
    adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
    time.sleep(0.4)

def tap(label):
    tap_node(node(label))

def shot(name):
    dump(name)
    with (OUT / f"{name}.png").open("wb") as out:
        adb("exec-out", "screencap", "-p", stdout=out)

def launch(route=None):
    adb("shell", "am", "force-stop", PACKAGE)
    args = ["shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity"]
    if route:
        args += ["--ez", "moji_qa_seed", "true", "--es", "moji_qa_route", route]
    adb(*args, stdout=subprocess.DEVNULL)
    time.sleep(1)

def enter_title(value):
    editable = wait_for(lambda n: n.get("class") == "android.widget.EditText" and n.get("text") in (ORIGINAL, SAVED, "Discard this draft"), "plan title input")
    tap_node(editable)
    adb("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
    adb("shell", "input", "text", value.replace(" ", "%s"))
    wait_for(lambda n: n.get("class") == "android.widget.EditText" and n.get("text") == value, "updated title")

def focus_entry():
    launch("plan-expanded")
    tap("去专注")
    node(ORIGINAL)
    launch()
    node("专注待开始")
    shot("android-focus-prepared")
    RESULTS.append({"check": "linking a plan opens focus without starting or reporting a pause", "passed": True})

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--focus-only", action="store_true")
    args = parser.parse_args()
    if text("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise SystemExit("Refusing to seed anything except the disposable emulator")
    OUT.mkdir(parents=True, exist_ok=True)
    adb("shell", "cmd", "uimode", "night", "no", stdout=subprocess.DEVNULL)
    if args.focus_only:
        previous = OUT / "interaction-results.json"
        if previous.exists():
            RESULTS.extend(json.loads(previous.read_text()))
        focus_entry()
        previous.write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(RESULTS, ensure_ascii=False), flush=True)
        return

    launch("plan-inline")
    node("编辑内容")
    enter_title(SAVED)
    shot("android-content-input")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("保存")
    node(SAVED)
    shot("android-content-saved")
    launch()
    node(SAVED)
    shot("android-content-relaunch")
    RESULTS.append({"check": "title input, save, force-stop and relaunch", "passed": True})

    launch("plan-inline")
    node("编辑内容")
    enter_title("Discard this draft")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    tap("取消")
    node("放弃未保存的修改？")
    shot("android-discard-confirmation")
    tap("继续编辑")
    node("Discard this draft")
    tap("取消")
    tap("放弃修改")
    launch()
    node(ORIGINAL)
    if any(n.get("text") == "Discard this draft" for n in dump().iter("node")):
        raise RuntimeError("A discarded draft was persisted")
    RESULTS.append({"check": "continue editing and discard leave persisted title unchanged", "passed": True})

    launch("plan-quick-date")
    node("修改日期")
    tap("明天")
    tap("保存")
    tap("计划库与模板")
    node("计划库")
    node(ORIGINAL)
    wait_for(lambda n: "10月13日" in n.get("text", "") and "工作" in n.get("text", ""), "moved plan date and retained category")
    shot("android-date-library")
    RESULTS.append({"check": "direct date edit moves plan to tomorrow and retains category", "passed": True})

    focus_entry()

    (OUT / "interaction-results.json").write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(RESULTS, ensure_ascii=False), flush=True)

if __name__ == "__main__":
    main()
