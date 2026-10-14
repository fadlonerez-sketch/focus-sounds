"""Shared helpers for building the bundled sound assets (numpy + ffmpeg only).

Everything operates on float64 arrays shaped (frames, channels) at SAMPLE_RATE.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import wave
from pathlib import Path

import numpy as np

SAMPLE_RATE = 48_000
CHANNELS = 2
# Every asset is normalized to the same loudness so switching sounds does not jump in volume.
TARGET_LUFS = -20.0
PEAK_CEILING_DBFS = -1.0


def ffmpeg_exe(name: str = "ffmpeg") -> str:
    """Finds ffmpeg/ffprobe via $FFMPEG_DIR or PATH."""
    d = os.environ.get("FFMPEG_DIR")
    if d:
        p = Path(d) / (name + (".exe" if os.name == "nt" else ""))
        if p.exists():
            return str(p)
    found = shutil.which(name)
    if not found:
        raise SystemExit(f"{name} not found: put it on PATH or set FFMPEG_DIR")
    return found


def decode(path: Path, start_s: float | None = None, duration_s: float | None = None) -> np.ndarray:
    """Decodes any audio file to float64 stereo at SAMPLE_RATE (high-quality soxr resampling)."""
    cmd = [ffmpeg_exe(), "-v", "error"]
    if start_s is not None:
        cmd += ["-ss", f"{start_s:.6f}"]
    if duration_s is not None:
        cmd += ["-t", f"{duration_s:.6f}"]
    cmd += ["-i", str(path), "-ac", str(CHANNELS), "-ar", str(SAMPLE_RATE),
            "-af", "aresample=resampler=soxr:precision=28", "-f", "f32le", "-"]
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype="<f4").astype(np.float64).reshape(-1, CHANNELS)


def _biquad_response(b, a, freqs, sr):
    z = np.exp(-2j * np.pi * freqs / sr)
    return (b[0] + b[1] * z + b[2] * z * z) / (a[0] + a[1] * z + a[2] * z * z)


def k_weighting_power(freqs: np.ndarray, sr: int = SAMPLE_RATE) -> np.ndarray:
    """|H(f)|^2 of the ITU-R BS.1770 K-weighting filter (48 kHz coefficients)."""
    assert sr == 48_000
    shelf = _biquad_response([1.53512485958697, -2.69169618940638, 1.19839281085285],
                             [1.0, -1.69065929318241, 0.73248077421585], freqs, sr)
    hp = _biquad_response([1.0, -2.0, 1.0], [1.0, -1.99004745483398, 0.99007225036621], freqs, sr)
    return np.abs(shelf * hp) ** 2


def loudness_lufs(x: np.ndarray, sr: int = SAMPLE_RATE) -> float:
    """Ungated BS.1770 loudness, computed in the frequency domain (fine for steady ambience)."""
    n = x.shape[0]
    spec = np.fft.rfft(x, axis=0)
    w = k_weighting_power(np.fft.rfftfreq(n, 1 / sr), sr)[:, None]
    # Parseval for rfft: double every bin except DC (and Nyquist for even n).
    scale = np.full(spec.shape[0], 2.0)
    scale[0] = 1.0
    if n % 2 == 0:
        scale[-1] = 1.0
    ms_per_channel = (scale[:, None] * w * np.abs(spec) ** 2).sum(axis=0) / (n * n)
    return -0.691 + 10 * np.log10(ms_per_channel.sum())


def short_term_db(x: np.ndarray, sr: int = SAMPLE_RATE, window_s: float = 0.4, hop_s: float = 0.1):
    """Unweighted RMS level in dBFS over sliding windows, for spotting events."""
    win, hop = int(window_s * sr), int(hop_s * sr)
    mono_sq = (x ** 2).mean(axis=1)
    c = np.concatenate([[0.0], np.cumsum(mono_sq)])
    starts = np.arange(0, len(mono_sq) - win, hop)
    ms = (c[starts + win] - c[starts]) / win
    return starts / sr, 10 * np.log10(ms + 1e-20)


def transient_score(x: np.ndarray, sr: int = SAMPLE_RATE, frame_s: float = 0.01, context_s: float = 2.0):
    """Per-frame energy relative to the median of its surroundings, in dB (clicks, knocks, calls)."""
    f = int(frame_s * sr)
    n = len(x) // f
    e = (x[: n * f] ** 2).mean(axis=1).reshape(n, f).mean(axis=1) + 1e-20
    edb = 10 * np.log10(e)
    k = int(context_s / frame_s)
    pad = np.pad(edb, (k // 2, k // 2), mode="edge")
    # Rolling median via strided view; fine for minutes of audio.
    view = np.lib.stride_tricks.sliding_window_view(pad, k + 1)[: len(edb)]
    return np.arange(n) * frame_s, edb - np.median(view, axis=1)


def shape_spectrum_periodic(x: np.ndarray, gain_of_freq) -> np.ndarray:
    """Applies a zero-phase EQ through the FFT. Circular, so a periodic loop stays seamless."""
    n = x.shape[0]
    spec = np.fft.rfft(x, axis=0)
    spec *= gain_of_freq(np.fft.rfftfreq(n, 1 / SAMPLE_RATE))[:, None]
    return np.fft.irfft(spec, n=n, axis=0)


def make_seamless_loop(segment: np.ndarray, loop_frames: int, crossfade_frames: int) -> np.ndarray:
    """Builds a loop of loop_frames from a segment of loop_frames + crossfade_frames.

    The last crossfade_frames of the loop are an equal-power blend of the segment's tail into the
    audio that immediately precedes the loop start, so loop[-1] -> loop[0] is continuous.
    """
    assert segment.shape[0] >= loop_frames + crossfade_frames
    x = crossfade_frames
    body = segment[x: x + loop_frames].copy()
    t = (np.arange(x) + 0.5) / x
    fade_in = np.sin(0.5 * np.pi * t)[:, None]
    fade_out = np.cos(0.5 * np.pi * t)[:, None]
    body[loop_frames - x:] = body[loop_frames - x:] * fade_out + segment[:x] * fade_in
    return body


def circular_peak_limit(x: np.ndarray, ceiling_dbfs: float, window_ms: float = 5.0) -> tuple[np.ndarray, str]:
    """Look-ahead peak limiter whose gain envelope wraps around, so a periodic loop stays periodic.

    The per-sample required gain is spread to a sliding minimum over the window and then smoothed by
    a moving average of the same length, which keeps the gain at or below the required value at
    every peak while ramping over about window_ms on each side (no hard clipping).
    """
    ceiling = 10 ** (ceiling_dbfs / 20)
    peak = np.abs(x).max(axis=1)
    need = np.minimum(1.0, ceiling / np.maximum(peak, 1e-12))
    if need.min() >= 1.0:
        return x, "no limiting"
    w = max(3, int(window_ms / 1000 * SAMPLE_RATE)) | 1
    h = w // 2
    padded = np.concatenate([need[-h:], need, need[:h]])
    spread = np.lib.stride_tricks.sliding_window_view(padded, w).min(axis=1)
    padded = np.concatenate([spread[-h:], spread, spread[:h]])
    c = np.concatenate([[0.0], np.cumsum(padded)])
    gain = (c[w:] - c[:-w]) / w
    y = x * gain[:, None]
    touched = (gain < 0.999).mean() * 100
    return y, f"limited up to {-20 * np.log10(gain.min()):.1f} dB on {touched:.2f}% of samples"


def normalize(x: np.ndarray, target_lufs: float = TARGET_LUFS) -> tuple[np.ndarray, float, str]:
    gain_db = target_lufs - loudness_lufs(x)
    y = x * 10 ** (gain_db / 20)
    y, note = circular_peak_limit(y, PEAK_CEILING_DBFS - 0.1)
    if note != "no limiting":
        # Limiting removes a little energy; restore the target loudness precisely.
        trim = 10 ** ((target_lufs - loudness_lufs(y)) / 20)
        y, note = circular_peak_limit(y * trim, PEAK_CEILING_DBFS - 0.1)
    peak_db = 20 * np.log10(np.abs(y).max())
    if peak_db > PEAK_CEILING_DBFS:
        raise SystemExit(f"peak {peak_db:.2f} dBFS still exceeds ceiling after limiting")
    return y, gain_db, note


def to_int16_dithered(x: np.ndarray, seed: int) -> np.ndarray:
    """TPDF dither to 16-bit. Pointwise, so it never breaks loop continuity."""
    rng = np.random.default_rng(seed)
    lsb = 1 / 32768
    d = (rng.random(x.shape) - rng.random(x.shape)) * lsb
    return np.clip(np.round((x + d) * 32767), -32768, 32767).astype("<i2")


def write_flac(pcm16: np.ndarray, out: Path, tmp_dir: Path) -> None:
    tmp_dir.mkdir(parents=True, exist_ok=True)
    wav_path = tmp_dir / (out.stem + ".wav")
    with wave.open(str(wav_path), "wb") as w:
        w.setnchannels(pcm16.shape[1])
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes(pcm16.tobytes())
    out.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([ffmpeg_exe(), "-v", "error", "-y", "-i", str(wav_path), "-map_metadata", "-1",
                    "-c:a", "flac", "-compression_level", "12", str(out)], check=True)
    # Verify the FLAC decodes bit-exactly back to what we wrote (same sample count and samples).
    raw = subprocess.run([ffmpeg_exe(), "-v", "error", "-i", str(out), "-f", "s16le", "-"],
                         check=True, capture_output=True).stdout
    back = np.frombuffer(raw, dtype="<i2").reshape(-1, pcm16.shape[1])
    if back.shape != pcm16.shape or not np.array_equal(back, pcm16):
        raise SystemExit(f"{out.name}: FLAC round trip is not bit-exact")
    wav_path.unlink()


def seam_report(pcm16: np.ndarray) -> str:
    """Compares the loop wrap step (last -> first sample) with the file's normal sample steps."""
    x = pcm16.astype(np.float64)
    steps = np.abs(np.diff(x, axis=0))
    wrap = np.abs(x[0] - x[-1])
    pct = [(steps[:, c] < wrap[c]).mean() * 100 for c in range(x.shape[1])]
    return "wrap step percentile per channel: " + ", ".join(f"{p:.1f}%" for p in pct)
