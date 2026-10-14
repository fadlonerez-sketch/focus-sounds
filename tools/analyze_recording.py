"""Finds the calmest, steadiest section of a field recording for use as a loop.

Usage:  python tools/analyze_recording.py <audio file> [window_seconds]

Reports a level profile, sharp transient events (clicks, knocks, bird calls, handling noise)
and ranks candidate windows by steadiness, so loop sections are chosen from measurements.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audiolib as al  # noqa: E402

TRANSIENT_DB = 9.0  # 10 ms frame this far above its 2 s median counts as an event


def bandwidth_hz(x: np.ndarray) -> float:
    n = min(len(x), al.SAMPLE_RATE * 30)
    p = (np.abs(np.fft.rfft(x[:n], axis=0)) ** 2).mean(axis=1)
    f = np.fft.rfftfreq(n, 1 / al.SAMPLE_RATE)
    ref = p[(f > 500) & (f < 2000)].mean()
    smooth = np.convolve(p, np.ones(200) / 200, mode="same")
    above = f[smooth > ref * 1e-5]  # -50 dB relative to the 0.5-2 kHz band
    return float(above.max()) if len(above) else 0.0


def main() -> None:
    path = Path(sys.argv[1])
    window_s = float(sys.argv[2]) if len(sys.argv) > 2 else 95.0
    x = al.decode(path)
    dur = len(x) / al.SAMPLE_RATE
    t, lvl = al.short_term_db(x)
    tt, tr = al.transient_score(x)
    events = tt[tr > TRANSIENT_DB]
    corr = np.corrcoef(x[:, 0], x[:, 1])[0, 1]
    print(f"{path.name}: {dur:.1f}s, {al.loudness_lufs(x):.1f} LUFS, L/R corr {corr:+.2f}, "
          f"bandwidth ~{bandwidth_hz(x) / 1000:.1f} kHz, transient frames>{TRANSIENT_DB:.0f}dB: {len(events)}")

    print("  level per 10 s (dBFS):", " ".join(f"{lvl[(t >= s) & (t < s + 10)].mean():.0f}"
                                               for s in np.arange(0, dur - 10, 10)))
    if len(events):
        # Collapse consecutive frames into events.
        groups = np.split(events, np.where(np.diff(events) > 0.25)[0] + 1)
        print("  events at (s):", ", ".join(f"{g[0]:.1f}" for g in groups[:60]),
              "..." if len(groups) > 60 else "")

    if dur < window_s:
        print("  (shorter than window)")
        return
    results = []
    for start in np.arange(0, dur - window_s + 0.01, 2.5):
        sel = (t >= start) & (t < start + window_s)
        tsel = (tt >= start) & (tt < start + window_s)
        lv = lvl[sel]
        n_events = int((tr[tsel] > TRANSIENT_DB).sum())
        worst = float(tr[tsel].max())
        # Steadiness: spread of short-term level and slow drift; events penalized heavily.
        spread = float(np.percentile(lv, 95) - np.percentile(lv, 5))
        drift = float(abs(lv[: len(lv) // 4].mean() - lv[-len(lv) // 4:].mean()))
        results.append((spread + drift + 0.5 * n_events + max(0.0, worst - 6), start, spread, drift, n_events, worst))
    results.sort()
    print(f"  best {window_s:.0f}s windows (score, start, p95-p5 spread dB, drift dB, events, worst dB):")
    shown = []
    for r in results:
        if all(abs(r[1] - s) > window_s / 2 for s in shown):
            shown.append(r[1])
            print(f"    {r[0]:6.2f}  start {r[1]:7.1f}s  spread {r[2]:5.2f}  drift {r[3]:4.2f}  events {r[4]:3d}  worst {r[5]:5.1f}")
        if len(shown) == 4:
            break


if __name__ == "__main__":
    main()
