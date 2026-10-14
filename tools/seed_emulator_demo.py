"""EMULATOR-ONLY: fills the app's database with ~75 days of realistic sessions for UI screenshots.

Usage:  python tools/seed_emulator_demo.py
Env:    ADB (path to adb), SERIAL (optional)

It REPLACES the app's data on the target device, so it refuses to run unless the device reports
itself as an emulator (ro.kernel.qemu / ro.boot.qemu). The app itself never contains demo data.
The database is built from Room's exported schema (app/schemas), so Room accepts it as-is.
"""
from __future__ import annotations

import json
import os
import random
import sqlite3
import subprocess
import sys
import tempfile
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path

ADB = os.environ.get("ADB", "adb")
SERIAL = os.environ.get("SERIAL")
PKG = "com.focussounds"
ROOT = Path(__file__).resolve().parents[1]
SCHEMA = ROOT / "app" / "schemas" / "com.focussounds.data.FocusDatabase" / "1.json"

BUILT_INS = [("studying", "Studying"), ("programming", "Programming"), ("reading", "Reading"),
             ("writing", "Writing"), ("project", "Working on a project"), ("meditation", "Meditation")]


def adb(*args: str, data: bytes | None = None) -> str:
    cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, input=data, check=True, capture_output=True).stdout.decode(errors="replace")


def push(local: Path, app_relative: str) -> None:
    """Copies a file into the app's private storage via /data/local/tmp and run-as."""
    staged = f"/data/local/tmp/{local.name}"
    adb("push", str(local), staged)
    adb("shell", "chmod", "644", staged)
    adb("shell", "run-as", PKG, "cp", staged, app_relative)
    adb("shell", "rm", staged)
    listing = adb("shell", "run-as", PKG, "ls", "-l", app_relative)
    if local.stat().st_size and str(local.stat().st_size) not in listing:
        raise SystemExit(f"copy of {local.name} failed: {listing}")


def main() -> None:
    qemu = adb("shell", "getprop", "ro.kernel.qemu").strip() or adb("shell", "getprop", "ro.boot.qemu").strip()
    if qemu != "1":
        raise SystemExit("Refusing: target is not an emulator. This tool would replace real history.")

    schema = json.loads(SCHEMA.read_text())["database"]
    tmp = Path(tempfile.mkdtemp()) / "focus.db"
    con = sqlite3.connect(tmp)
    for e in schema["entities"]:
        con.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for idx in e.get("indices", []):
            con.execute(idx["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    for q in schema["setupQueries"]:
        con.execute(q)
    con.execute(f"PRAGMA user_version = {schema['version']}")

    now = datetime.now()
    created = int((now - timedelta(days=90)).timestamp() * 1000)
    for i, (key, name) in enumerate(BUILT_INS, start=1):
        con.execute("INSERT INTO activities (id, name, builtInKey, createdAtMs) VALUES (?,?,?,?)", (i, name, key, created))
    con.execute("INSERT INTO activities (id, name, builtInKey, createdAtMs) VALUES (7, 'Guitar practice', NULL, ?)", (created,))
    con.execute("INSERT INTO activities (id, name, builtInKey, createdAtMs) VALUES (8, 'Thesis research', NULL, ?)", (created,))

    rng = random.Random(7)
    acts = [(2, "Programming", 34), (1, "Studying", 24), (3, "Reading", 14), (8, "Thesis research", 9), (4, "Writing", 8),
            (5, "Working on a project", 5), (6, "Meditation", 3), (7, "Guitar practice", 2), (None, "Chess", 1)]
    sounds = [("brown_noise", 38), ("ocean", 22), ("stream", 18), ("forest", 14), ("white_noise", 8)]

    def pick(items):
        return rng.choices(items, weights=[w for *_, w in items])[0]

    rows = 0
    for d in range(74, -1, -1):
        day = (now - timedelta(days=d)).date()
        if d > 0 and rng.random() < (0.22 if day.weekday() < 5 else 0.45):
            continue  # a day off
        n = rng.choice([1, 2, 2, 3]) if d > 0 else 2
        hours = sorted(rng.sample([8, 9, 10, 11, 14, 15, 16, 17, 20, 21], n))
        for h in hours:
            start = datetime.combine(day, datetime.min.time()) + timedelta(hours=h, minutes=rng.randint(0, 50))
            if start > now - timedelta(minutes=70):
                continue
            aid, aname, _ = pick(acts)
            sid, _ = pick(sounds)
            planned = rng.choice([25, 25, 45, 60, None])
            r = rng.random()
            if planned and r < 0.62:
                outcome, active = "completed", planned * 60_000
            elif r < 0.93:
                outcome = "stopped"
                active = int(rng.uniform(8, (planned or 95)) * 60_000)
            elif r < 0.97:
                outcome, active = "interrupted", int(rng.uniform(5, 30) * 60_000)
            else:
                outcome, active = "cancelled", 0
            paused = int(rng.choice([0, 0, 0, 2, 5]) * 60_000)
            s_ms = int(start.timestamp() * 1000)
            e_ms = s_ms + active + paused
            con.execute(
                "INSERT INTO sessions (id, activityId, activityName, soundId, startedAtMs, endedAtMs, plannedDurationMs, "
                "activeDurationMs, state, endReason, lastCheckpointMs) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (str(uuid.UUID(int=rng.getrandbits(128))), aid, aname, sid, s_ms, e_ms,
                 planned * 60_000 if planned else None, active, "finished", outcome, e_ms),
            )
            rows += 1
    con.commit()
    con.close()

    adb("shell", "am", "force-stop", PKG)
    for suffix in ("", "-wal", "-shm"):
        adb("shell", "run-as", PKG, "rm", "-f", f"databases/focus.db{suffix}")
    adb("shell", "run-as", PKG, "mkdir", "-p", "databases")
    push(tmp, "databases/focus.db")
    prefs = ('<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>\n'
             '<int name="s_daily_goal_min" value="90" />\n<int name="s_weekly_goal_min" value="600" />\n</map>\n')
    adb("shell", "run-as", PKG, "mkdir", "-p", "shared_prefs")
    prefs_file = tmp.parent / "focus_settings.xml"
    prefs_file.write_text(prefs, encoding="utf-8")
    push(prefs_file, "shared_prefs/focus_settings.xml")
    print(f"seeded {rows} sessions on the emulator ({time.strftime('%H:%M')})")


if __name__ == "__main__":
    sys.exit(main())
