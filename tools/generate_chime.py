"""Generates the soft completion chime (app/src/main/assets/sounds/chime.flac). Deterministic.

Usage:  python tools/generate_chime.py
Needs:  numpy, ffmpeg (on PATH or $FFMPEG_DIR)

Two gentle bell tones (E5 then B5, a perfect fifth) built from a few inharmonic partials with
exponential decays, a short attack to avoid clicks, and a fade to true silence at the end.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audiolib as al  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "assets" / "sounds" / "chime.flac"
SECONDS = 2.6
PEAK_DBFS = -9.0

# (frequency ratio, relative amplitude, decay time constant in seconds)
PARTIALS = [(1.0, 1.0, 0.9), (2.0, 0.28, 0.45), (2.76, 0.10, 0.3), (5.4, 0.035, 0.15)]


def bell(f0: float, t: np.ndarray, start: float) -> np.ndarray:
    tt = np.clip(t - start, 0, None)
    on = (t >= start).astype(float)
    attack = np.clip(tt / 0.008, 0, 1)
    tone = sum(a * np.exp(-tt / tau) * np.sin(2 * np.pi * f0 * r * tt) for r, a, tau in PARTIALS)
    return on * attack * tone


def main() -> None:
    sr = al.SAMPLE_RATE
    t = np.arange(int(SECONDS * sr)) / sr
    x = bell(659.25, t, 0.0) + 0.8 * bell(987.77, t, 0.22)
    fade = np.clip((SECONDS - t) / 0.4, 0, 1) ** 2
    x *= fade
    x *= 10 ** (PEAK_DBFS / 20) / np.abs(x).max()
    mono = x[:, None]
    pcm = al.to_int16_dithered(mono, seed=4242)
    al.write_flac(pcm, OUT, ROOT / "tools" / "work")
    print(f"chime: {OUT.stat().st_size / 1e3:.1f} kB, {SECONDS}s mono @ {sr} Hz, peak {PEAK_DBFS} dBFS")


if __name__ == "__main__":
    main()
