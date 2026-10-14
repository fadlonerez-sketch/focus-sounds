"""Tiny adb helper for device-level checks (UI taps by text, state dumps, history DB pull).

Usage examples:
  python tools/device.py tap "Start"           # tap the first node whose text/desc matches
  python tools/device.py texts                 # list visible texts
  python tools/device.py state                 # service / media session / audio focus summary
  python tools/device.py history               # print the sessions table from the app's database
Env: ADB (path to adb), SERIAL (optional device serial).
"""
from __future__ import annotations

import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = os.environ.get("ADB", "adb")
SERIAL = os.environ.get("SERIAL")
PKG = "com.focussounds"


def adb(*args: str, check: bool = True) -> str:
    cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, check=check, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def adb_bytes(*args: str) -> bytes:
    cmd = [ADB] + (["-s", SERIAL] if SERIAL else []) + list(args)
    return subprocess.run(cmd, check=True, capture_output=True).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    xml = adb("shell", "cat", "/sdcard/ui.xml")
    root = ET.fromstring(xml[xml.index("<"):])
    for n in root.iter("node"):
        yield n


def center(bounds: str) -> tuple[int, int]:
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", bounds))
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(text: str) -> None:
    pat = re.compile(text, re.I)
    for n in nodes():
        label = n.get("text") or n.get("content-desc") or ""
        if label and pat.fullmatch(label):
            x, y = center(n.get("bounds"))
            adb("shell", "input", "tap", str(x), str(y))
            print(f"tapped '{label}' at {x},{y}")
            return
    raise SystemExit(f"no node matching '{text}'")


def texts() -> None:
    for n in nodes():
        label = n.get("text") or n.get("content-desc")
        if label:
            print(f"{label!r} {n.get('bounds')}")


def state() -> None:
    svc = adb("shell", "dumpsys", "activity", "services", PKG)
    t = re.search(r"isForeground=true .*?types=(0x[0-9a-f]+)", svc)
    print("service:", "running" if "PlaybackService" in svc else "not running",
          "| foreground:", "isForeground=true" in svc,
          "| fgs types:", (t.group(1) + (" (mediaPlayback)" if int(t.group(1), 16) & 0x2 else "")) if t else "-")
    ms = adb("shell", "dumpsys", "media_session")
    block = ms[ms.find(PKG):][:3000] if PKG in ms else ""
    st = re.search(r"state=PlaybackState \{state=(\w+)", block)
    print("media session:", st.group(1) if st else "none")
    audio = adb("shell", "dumpsys", "audio")
    stack = audio[audio.find("Audio Focus stack entries"):][:1500]
    owners = re.findall(r"pack: (\S+)", stack)
    print("audio focus stack (top first):", owners[:5])
    players = re.findall(r"AudioPlaybackConfiguration piid:\d+ deviceIds:\S* type:\S+ u/pid:\d+/(\d+) state:(\w+) attr:AudioAttributes: usage=(\w+)", audio)
    pid = adb("shell", "pidof", PKG, check=False).strip()
    print("app pid:", pid or "-", "| app players:", [(s, u) for p, s, u in players if p == pid])
    notif = adb("shell", "dumpsys", "notification", "--noredact")
    print("media notification posted:", f"pkg={PKG}" in notif and "MediaStyle" in notif or "android.mediaSession" in notif)


def history() -> None:
    tmp = Path(tempfile.mkdtemp())
    for suffix in ["", "-wal", "-shm"]:
        try:
            data = adb_bytes("exec-out", "run-as", PKG, "cat", f"databases/focus.db{suffix}")
            if data and not data.startswith(b"cat:"):
                (tmp / f"focus.db{suffix}").write_bytes(data)
        except subprocess.CalledProcessError:
            pass
    con = sqlite3.connect(tmp / "focus.db")
    cols = "activityName, soundId, startedAtMs, endedAtMs, plannedDurationMs, activeDurationMs, state, endReason, lastCheckpointMs"
    for row in con.execute(f"SELECT {cols} FROM sessions ORDER BY startedAtMs"):
        d = dict(zip([c.strip() for c in cols.split(",")], row))
        dur = (d["endedAtMs"] - d["startedAtMs"]) if d["endedAtMs"] else None
        print(f"{d['activityName']:<12} {d['soundId']:<11} planned={d['plannedDurationMs']} active={d['activeDurationMs']} "
              f"wall={dur} state={d['state']} reason={d['endReason']}")
    print("activities:", [r[0] for r in con.execute("SELECT name FROM activities ORDER BY id")])


if __name__ == "__main__":
    cmd = sys.argv[1]
    {"tap": lambda: tap(sys.argv[2]), "texts": texts, "state": state, "history": history}[cmd]()
