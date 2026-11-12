"""1.6.1 check of the two Android toggle fixes, on a disposable emulator only.

1. Skipping a repeating plan and tapping its "−" box reopens it (planned) and
   removes the successor the skip generated, instead of marking it completed.
2. Tapping the "✓" box of a completed plan reopens it and deletes only the
   record that completed it; an earlier partial focus record is kept.

The debug QA seed loads Scripts/fixtures/workflow-1.6.json. The script then
stops the app, moves that fixture's plans and records to today on the
emulator clock, adds one earlier partial focus record to the completed plan,
and writes the snapshot back into the app's Room database. Each step is done
by tapping the real UI, and each result is checked in the database.
"""
import datetime as dt
import json
from pathlib import Path
import re
import sqlite3
import subprocess
import tempfile
import time
import uuid
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/1.6.1-qa/manual"
ADB = "/Users/Shared/JerreaderAndroidSdk/platform-tools/adb"
SERIAL = "emulator-5556"
PACKAGE = "com.raydon.moji.android"
REPEATING = "整理 1.6.0 发布说明"   # weekly plan in the fixture
COMPLETED = "整理会议纪要"           # completed plan with its completion record
RESULTS = []


def adb(*args, **kwargs):
    return subprocess.run([ADB, "-s", SERIAL, *args], check=True, timeout=60, **kwargs)


def text(*args):
    return adb(*args, capture_output=True, text=True).stdout


def dump(name=None):
    adb("shell", "uiautomator", "dump", "/sdcard/moji161-qa.xml", stdout=subprocess.DEVNULL)
    xml = text("shell", "cat", "/sdcard/moji161-qa.xml")
    if name:
        (OUT / f"{name}.xml").write_text(xml)
    return ET.fromstring(xml)


def bounds(n):
    return tuple(map(int, re.findall(r"\d+", n.get("bounds", ""))))


def wait_for(predicate, label, attempts=12):
    for _ in range(attempts):
        matches = [n for n in dump().iter("node") if predicate(n)]
        if matches:
            return matches[0]
        time.sleep(0.5)
    raise RuntimeError(f"Expected UI was not visible: {label}")


def tap_node(n):
    x1, y1, x2, y2 = bounds(n)
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(0.8)


def long_press(n):
    x1, y1, x2, y2 = bounds(n)
    x, y = str((x1 + x2) // 2), str((y1 + y2) // 2)
    adb("shell", "input", "swipe", x, y, x, y, "900")
    time.sleep(0.8)


def box_left_of(title, mark):
    """The check box ("−" or "✓") on the same row as the plan title."""
    tx1, ty1, tx2, ty2 = bounds(title)
    centre = (ty1 + ty2) // 2
    marks = [n for n in dump().iter("node") if n.get("text") == mark]
    near = [n for n in marks if bounds(n)[2] <= tx1 and bounds(n)[1] - 80 <= centre <= bounds(n)[3] + 80]
    if not near:
        raise RuntimeError(f"No {mark} box beside {title.get('text')!r}")
    return min(near, key=lambda n: abs((bounds(n)[1] + bounds(n)[3]) // 2 - centre))


def shot(name):
    dump(name)
    with (OUT / f"{name}.png").open("wb") as out:
        adb("exec-out", "screencap", "-p", stdout=out)


def launch(seed=False):
    adb("shell", "am", "force-stop", PACKAGE)
    args = ["shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity"]
    if seed:
        # No route extra: `adb shell` joins arguments, so an empty string is dropped
        # and `am start` fails; the app treats a missing route as "".
        args += ["--ez", "moji_qa_seed", "true"]
    adb(*args, stdout=subprocess.DEVNULL)
    time.sleep(2.5)


def read_state():
    """Snapshot JSON from the app's Room database (WAL merged locally)."""
    with tempfile.TemporaryDirectory() as tmp:
        for suffix in ("", "-wal", "-shm"):
            data = subprocess.run([ADB, "-s", SERIAL, "exec-out", "run-as", PACKAGE, "cat", f"databases/moji.db{suffix}"],
                                  capture_output=True, timeout=60).stdout
            if data and not data.startswith(b"cat:"):
                Path(tmp, f"moji.db{suffix}").write_bytes(data)
        con = sqlite3.connect(Path(tmp, "moji.db"))
        row = con.execute("select json from moji_state where id = 1").fetchone()
        con.close()
    return json.loads(row[0])


def write_state(state):
    with tempfile.TemporaryDirectory() as tmp:
        for suffix in ("", "-wal", "-shm"):
            data = subprocess.run([ADB, "-s", SERIAL, "exec-out", "run-as", PACKAGE, "cat", f"databases/moji.db{suffix}"],
                                  capture_output=True, timeout=60).stdout
            if data and not data.startswith(b"cat:"):
                Path(tmp, f"moji.db{suffix}").write_bytes(data)
        db = Path(tmp, "moji.db")
        con = sqlite3.connect(db)
        con.execute("update moji_state set json = ?, updatedAtMillis = ? where id = 1",
                    (json.dumps(state, ensure_ascii=False), int(time.time() * 1000)))
        con.commit()
        con.execute("pragma wal_checkpoint(TRUNCATE)")
        con.close()
        staged = "/data/local/tmp/moji161-qa.db"
        adb("push", str(db), staged, stdout=subprocess.DEVNULL)
        adb("shell", "chmod", "644", staged)
        adb("shell", "run-as", PACKAGE, "rm", "-f", "databases/moji.db-wal", "databases/moji.db-shm")
        adb("shell", "run-as", PACKAGE, "cp", staged, "databases/moji.db")
        adb("shell", "rm", "-f", staged)
    if read_state() != state:
        raise RuntimeError("Database write did not round-trip")


def settle(predicate, attempts=20):
    """Re-read the database until predicate(state) holds (about 10 s), then return the last state."""
    state = None
    for _ in range(attempts):
        try:
            state = read_state()
        except Exception:
            state = None
        if state is not None and predicate(state):
            return state
        time.sleep(0.5)
    return state if state is not None else read_state()


def status_of(state, item_id):
    return next((i["status"] for i in state["checkInItems"] if i["id"] == item_id), None)


def iso(value):
    return value.astimezone(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse(value):
    return dt.datetime.fromisoformat(value.replace("Z", "+00:00"))


def plan(state, title, **extra):
    hits = [i for i in state["checkInItems"] if i["title"] == title and all(i.get(k) == v for k, v in extra.items())]
    if len(hits) != 1:
        raise RuntimeError(f"Expected one {title!r} {extra}, found {len(hits)}")
    return hits[0]


def check(label, condition, detail=""):
    RESULTS.append({"check": label, "passed": bool(condition), "detail": detail})
    if not condition:
        raise RuntimeError(f"FAILED: {label} {detail}")


def prepare():
    launch(seed=True)
    state = settle(lambda s: any(i["title"] == COMPLETED for i in s["checkInItems"]))
    adb("shell", "am", "force-stop", PACKAGE)
    zone = ZoneInfo(text("shell", "getprop", "persist.sys.timezone").strip() or "UTC")
    done = plan(state, COMPLETED)
    # Move everything so the completed plan sits at 10:00 today on the emulator clock.
    target = dt.datetime.now(zone).replace(hour=10, minute=0, second=0, microsecond=0)
    delta = target - parse(done["scheduledStart"])
    keys = ("scheduledStart", "completedAt", "actualStartDate", "actualEndDate", "createdAt", "startDate", "endDate")
    for entry in state["checkInItems"] + state["records"]:
        for key in keys:
            if entry.get(key):
                entry[key] = iso(parse(entry[key]) + delta)
    done = plan(state, COMPLETED)
    finishing = [r for r in state["records"] if r.get("checkInItemID") == done["id"]]
    check("fixture has exactly one completion record for the completed plan", len(finishing) == 1, str(len(finishing)))
    partial = dict(finishing[0])
    partial.update(id=str(uuid.uuid4()).upper(),
                   startDate=iso(parse(done["actualStartDate"]) - dt.timedelta(minutes=50)),
                   endDate=iso(parse(done["actualStartDate"]) - dt.timedelta(minutes=30)),
                   note="1.6.1 QA: earlier partial focus")
    state["records"].append(partial)
    write_state(state)
    return done["id"], finishing[0]["id"], partial["id"], zone.key


def main():
    if text("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        raise SystemExit("Refusing to touch anything except the disposable emulator")
    OUT.mkdir(parents=True, exist_ok=True)
    adb("shell", "cmd", "uimode", "night", "no", stdout=subprocess.DEVNULL)
    done_id, completion_id, partial_id, zone = prepare()

    launch()
    repeating = wait_for(lambda n: n.get("text") == REPEATING, "repeating plan in today's list")
    shot("android-toggle-start")

    # 1. Skip the repeating plan through its long-press menu.
    long_press(repeating)
    tap_node(wait_for(lambda n: n.get("text") == "跳过", "skip menu item"))
    state = settle(lambda s: any(i["title"] == REPEATING and i["status"] == "skipped" for i in s["checkInItems"]))
    original = plan(state, REPEATING, status="skipped")
    successors = [i for i in state["checkInItems"] if i.get("generatedFromOccurrenceID") == original["id"]]
    check("skip marks the plan skipped and creates one successor", len(successors) == 1 and successors[0]["status"] == "planned",
          f"successors={[(s['status'], s['scheduledStart']) for s in successors]}")

    header = wait_for(lambda n: n.get("text", "").startswith("今日已结束"), "ended section header")
    tap_node(header)
    skipped_row = wait_for(lambda n: n.get("text") == REPEATING, "skipped plan in ended section")
    shot("android-skip-ended")
    tap_node(box_left_of(skipped_row, "−"))
    state = settle(lambda s: status_of(s, original["id"]) != "skipped")
    reopened = plan(state, REPEATING, id=original["id"])
    left = [i for i in state["checkInItems"] if i.get("generatedFromOccurrenceID") == original["id"]]
    check("tapping − reopens the skipped plan instead of completing it",
          reopened["status"] == "planned" and reopened.get("completedAt") is None, f"status={reopened['status']}")
    check("reopening the skipped plan removes its untouched successor", not left, f"left={len(left)}")
    shot("android-skip-reopened")

    # 2. Reopen the completed plan through its ✓ box.
    completed_row = wait_for(lambda n: n.get("text") == COMPLETED, "completed plan in ended section")
    tap_node(box_left_of(completed_row, "✓"))
    state = settle(lambda s: status_of(s, done_id) != "completed")
    item = plan(state, COMPLETED, id=done_id)
    ids = {r["id"] for r in state["records"]}
    check("tapping ✓ reopens the completed plan", item["status"] == "planned" and item.get("actualStartDate") is None,
          f"status={item['status']}")
    check("the record that completed it is deleted", completion_id not in ids)
    check("the earlier partial focus record is kept", partial_id in ids)
    shot("android-completed-reopened")

    # 3. Survives a cold relaunch.
    launch()
    wait_for(lambda n: n.get("text") == COMPLETED, "reopened plan after relaunch")
    state = read_state()
    check("after relaunch both plans stay planned and the partial record remains",
          plan(state, REPEATING, id=original["id"])["status"] == "planned"
          and plan(state, COMPLETED, id=done_id)["status"] == "planned"
          and partial_id in {r["id"] for r in state["records"]})
    shot("android-toggle-relaunch")

    (OUT / "toggle-results.json").write_text(json.dumps(
        {"emulatorTimeZone": zone, "results": RESULTS}, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(RESULTS, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Keep whatever was checked before the failure as evidence.
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / "toggle-results.json").write_text(json.dumps(
            {"error": str(error), "results": RESULTS}, ensure_ascii=False, indent=2) + "\n")
        raise
