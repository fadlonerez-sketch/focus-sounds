"""Generates the bundled Brown Noise and White Noise loops. Deterministic (fixed seeds).

Usage:  python tools/generate_noise.py
Needs:  numpy, ffmpeg (on PATH or $FFMPEG_DIR)

The noise is synthesized in the frequency domain: Gaussian random spectra shaped by the target
magnitude response, then one inverse FFT per channel. An inverse-FFT signal is exactly periodic
over its length, so the loop boundary is as seamless as any other point in the file, with no
crossfade needed. Channels use independent seeds (decorrelated stereo).
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audiolib as al  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "app" / "src" / "main" / "assets" / "sounds"
WORK = ROOT / "tools" / "work"

LOOP_SECONDS = 60
HIGHPASS_HZ = 20.0  # 2nd-order roll-off below the audible band; avoids wasting headroom on DC/sub-bass


def highpass_mag(f: np.ndarray) -> np.ndarray:
    r = (f / HIGHPASS_HZ) ** 2
    return r / np.sqrt(1 + r * r)


def synthesize(magnitude, n: int, seeds: list[int]) -> np.ndarray:
    freqs = np.fft.rfftfreq(n, 1 / al.SAMPLE_RATE)
    mag = magnitude(freqs)
    mag[0] = 0.0  # no DC
    chans = []
    for s in seeds:
        rng = np.random.default_rng(s)
        spec = (rng.standard_normal(len(freqs)) + 1j * rng.standard_normal(len(freqs))) * mag
        chans.append(np.fft.irfft(spec, n=n))
    x = np.stack(chans, axis=1)
    return x / x.std()


def brown_mag(f):
    with np.errstate(divide="ignore"):
        m = np.where(f > 0, 1.0 / f, 0.0)  # power ~ 1/f^2  (-6 dB/octave)
    return m * highpass_mag(f)


def white_mag(f):
    return np.ones_like(f)  # flat power spectrum


def spectral_slope_db_per_octave(x: np.ndarray, lo=100.0, hi=4000.0) -> float:
    n = x.shape[0]
    p = (np.abs(np.fft.rfft(x, axis=0)) ** 2).mean(axis=1)
    f = np.fft.rfftfreq(n, 1 / al.SAMPLE_RATE)
    m = (f >= lo) & (f <= hi)
    # Average into 1/3-octave bands so the fit is not dominated by the many high bins.
    edges = lo * 2 ** (np.arange(0, np.log2(hi / lo) * 3 + 1) / 3)
    centers, levels = [], []
    for a, b in zip(edges[:-1], edges[1:]):
        sel = m & (f >= a) & (f < b)
        if sel.any():
            centers.append(np.sqrt(a * b))
            levels.append(10 * np.log10(p[sel].mean()))
    slope_per_decade = np.polyfit(np.log10(centers), levels, 1)[0]
    return slope_per_decade * np.log10(2)


def main() -> None:
    n = LOOP_SECONDS * al.SAMPLE_RATE
    specs = {
        "brown_noise": (brown_mag, [1001, 1002], -6.02),
        "white_noise": (white_mag, [2001, 2002], 0.0),
    }
    for name, (mag, seeds, expected_slope) in specs.items():
        x = synthesize(mag, n, seeds)
        x, gain, _ = al.normalize(x)
        pcm = al.to_int16_dithered(x, seed=seeds[0] + 7)
        out = OUT_DIR / f"{name}.flac"
        al.write_flac(pcm, out, WORK)
        slope = spectral_slope_db_per_octave(pcm.astype(np.float64))
        corr = np.corrcoef(pcm[:, 0], pcm[:, 1])[0, 1]
        print(f"{name}: {out.stat().st_size / 1e6:.2f} MB, {LOOP_SECONDS}s @ {al.SAMPLE_RATE} Hz, "
              f"loudness {al.loudness_lufs(pcm / 32768):.2f} LUFS, peak "
              f"{20 * np.log10(np.abs(pcm).max() / 32768):.2f} dBFS, slope {slope:+.2f} dB/oct "
              f"(expected {expected_slope:+.2f}), L/R corr {corr:+.3f}; {al.seam_report(pcm)}")
        if abs(slope - expected_slope) > 0.3:
            raise SystemExit(f"{name}: spectral slope out of tolerance")


if __name__ == "__main__":
    main()
