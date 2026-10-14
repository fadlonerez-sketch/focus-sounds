"""Builds the Ocean, Stream and Forest loops from verified CC0 Freesound recordings.

Usage:  python tools/build_recordings.py
Needs:  numpy, ffmpeg (on PATH or $FFMPEG_DIR), network access only if tools/sources/ is empty

Each source is downloaded (Freesound HQ preview, 192 kbps Ogg Vorbis; originals require a
Freesound login), verified against its SHA-256, and processed:
  1. Take the measured steady section (chosen with tools/analyze_recording.py; see SOURCES notes).
  2. Pick the exact loop start near it so both crossfade regions fall on quiet, level-matched
     moments (e.g. between waves).
  3. Equal-power crossfade the loop tail into the audio preceding the loop start (seamless).
  4. Gentle circular high-pass to remove sub-audible rumble (keeps the loop periodic).
  5. Normalize to the shared loudness target, TPDF-dither to 16-bit, encode FLAC (bit-exact check).
License details for every asset live in docs/SOUND_LICENSES.md.
"""
from __future__ import annotations

import hashlib
import sys
import urllib.request
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audiolib as al  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
SOURCES_DIR = ROOT / "tools" / "sources"
OUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "sounds"
WORK = ROOT / "tools" / "work"

LOOP_SECONDS = 90
CROSSFADE_SECONDS = 4.0
SEARCH_SECONDS = 10.0  # how far the loop start may move from the nominal start
HIGHPASS_HZ = 25.0

SOURCES = {
    "ocean": dict(
        file="ocean_176617.ogg",
        url="https://cdn.freesound.org/previews/176/176617_3278970-hq.ogg",
        sha256="ecb25abb9e5b6f71b6d73bb6864c52acd81124f60b245474163d659a5fa385c4",
        # Steadiest 95 s windows measured at 125 s and 22.5 s; no transients anywhere.
        nominal_start=125.0,
        seed=3001,
    ),
    "stream": dict(
        file="stream_220528.ogg",
        url="https://cdn.freesound.org/previews/220/220528_3965914-hq.ogg",
        sha256="56a9edd45ee08e42fd950888f780b507d8a7686feab0874c434260b9cd1ee5d5",
        # 10-105 s: 1.6 dB level spread, zero transient events.
        nominal_start=12.0,
        seed=3002,
    ),
    "forest": dict(
        file="forest_383506.ogg",
        url="https://cdn.freesound.org/previews/383/383506_1648170-hq.ogg",
        sha256="338f5fa8774a500cfa01e8d38c21cb1e963fa81e4c311641c3dd27e2fad189f4",
        # Inside the creator's logged "clean background" span 16:14-23:06 (974-1386 s),
        # away from the logged planes and woodpeckers; measured 1.5 dB spread, zero events.
        nominal_start=994.0,
        seed=3003,
    ),
}


def fetch(src: dict) -> Path:
    path = SOURCES_DIR / src["file"]
    if not path.exists():
        SOURCES_DIR.mkdir(parents=True, exist_ok=True)
        print(f"downloading {src['url']}")
        urllib.request.urlretrieve(src["url"], path)
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != src["sha256"]:
        raise SystemExit(f"{path.name}: SHA-256 mismatch ({digest}); refusing to use it")
    return path


def choose_start(x: np.ndarray, nominal_start: float) -> float:
    """Loop start whose two crossfade regions are quiet and level-matched."""
    t, lvl = al.short_term_db(x, window_s=1.0, hop_s=0.05)
    best = None
    for s in np.arange(max(0.0, nominal_start - SEARCH_SECONDS), nominal_start + SEARCH_SECONDS, 0.05):
        pre = (t >= s) & (t < s + CROSSFADE_SECONDS)
        tail = (t >= s + LOOP_SECONDS) & (t < s + LOOP_SECONDS + CROSSFADE_SECONDS)
        if not pre.any() or not tail.any() or s + LOOP_SECONDS + CROSSFADE_SECONDS > len(x) / al.SAMPLE_RATE:
            continue
        a, b = lvl[pre].mean(), lvl[tail].mean()
        score = (a + b) / 2 + 2 * abs(a - b) + (lvl[pre].std() + lvl[tail].std())
        if best is None or score < best[0]:
            best = (score, s)
    return float(best[1])


def seam_check(loop: np.ndarray) -> str:
    """Plays the loop twice and compares the level around the seam with the rest of the file."""
    twice = np.concatenate([loop, loop])
    t, lvl = al.short_term_db(twice)
    seam = len(loop) / al.SAMPLE_RATE
    near = (t > seam - 2) & (t < seam + 2)
    tt, tr = al.transient_score(twice)
    seam_tr = tr[(tt > seam - 0.5) & (tt < seam + 0.5)].max()
    return (f"seam level {lvl[near].mean():.1f} dB vs median {np.median(lvl):.1f} dB "
            f"(range {lvl[near].min():.1f}..{lvl[near].max():.1f}); seam transient {seam_tr:.1f} dB")


def hp(f: np.ndarray) -> np.ndarray:
    r = (f / HIGHPASS_HZ) ** 2
    return r / np.sqrt(1 + r * r)


def main() -> None:
    xf = int(CROSSFADE_SECONDS * al.SAMPLE_RATE)
    n = LOOP_SECONDS * al.SAMPLE_RATE
    for name, src in SOURCES.items():
        path = fetch(src)
        lo = max(0.0, src["nominal_start"] - SEARCH_SECONDS - 1)
        span = LOOP_SECONDS + CROSSFADE_SECONDS + 2 * SEARCH_SECONDS + 2
        region = al.decode(path, start_s=lo, duration_s=span)
        start = choose_start(region, src["nominal_start"] - lo)
        i = int(round(start * al.SAMPLE_RATE))
        segment = region[i: i + n + xf]
        loop = al.make_seamless_loop(segment, n, xf)
        loop = al.shape_spectrum_periodic(loop, hp)
        loop, gain, limit_note = al.normalize(loop)
        pcm = al.to_int16_dithered(loop, seed=src["seed"])
        out = OUT_DIR / f"{name}.flac"
        al.write_flac(pcm, out, WORK)
        print(f"{name}: source {lo + start:.2f}s..{lo + start + LOOP_SECONDS + CROSSFADE_SECONDS:.2f}s "
              f"(crossfade {CROSSFADE_SECONDS}s), gain {gain:+.1f} dB, {out.stat().st_size / 1e6:.2f} MB, "
              f"peak {20 * np.log10(np.abs(pcm).max() / 32768):.1f} dBFS ({limit_note}); {seam_check(pcm / 32768)}")


if __name__ == "__main__":
    main()
