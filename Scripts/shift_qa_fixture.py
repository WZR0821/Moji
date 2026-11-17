"""Write a copy of the visual-QA fixture whose "today" is a chosen day.

The fixture was recorded with 2026-10-12 as today. Simulators run on the host
clock, so screenshots taken on another day see every plan as overdue. This
shifts every timestamp by whole days and moves each weekStart to the Monday of
its shifted week, so "this week" and "next week" stay what they were.

    python3 Scripts/shift_qa_fixture.py --out build/1.7.0-qa/fixtures/workflow-1.6.json
"""
import argparse
import datetime as dt
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_TODAY = dt.date(2026, 10, 12)
TIMESTAMP = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$")
DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def shift(value, days, key=None):
    if isinstance(value, dict):
        return {k: shift(v, days, k) for k, v in value.items()}
    if isinstance(value, list):
        return [shift(v, days, key) for v in value]
    if isinstance(value, str) and TIMESTAMP.match(value):
        stamp = dt.datetime.fromisoformat(value.replace("Z", "+00:00")) + dt.timedelta(days=days)
        return stamp.strftime("%Y-%m-%dT%H:%M:%S") + (value[19:-1] if "." in value else "") + "Z"
    if isinstance(value, str) and DATE.match(value):
        day = dt.date.fromisoformat(value) + dt.timedelta(days=days)
        if key == "weekStart":
            day -= dt.timedelta(days=day.weekday())
        return day.isoformat()
    return value


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, default=ROOT / "Scripts/fixtures/workflow-1.6.json")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--today", type=dt.date.fromisoformat, default=dt.date.today())
    args = parser.parse_args()
    days = (args.today - FIXTURE_TODAY).days
    data = shift(json.loads(args.source.read_text(encoding="utf-8")), days)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"shifted by {days} days -> {args.out}")
