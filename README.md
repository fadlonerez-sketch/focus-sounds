# Focus Sounds (Android)

A native Android app that plays one calm background sound while you study, code, read, write,
work on a project or meditate, and keeps a local history of focus sessions.
Offline only: no account, backend, API key, analytics, or network permission.

## Features (MVP)

- Five sounds: Brown Noise, White Noise, Ocean, Stream, Forest. All are bundled as seamless loops.
- Activities: six built-ins plus custom ones you can add, rename and delete. Deleting an activity keeps its history.
- Manual sessions (no timer) or timed sessions (25 / 45 / 60 min or a custom 1–600 min).
- Play / pause / resume / stop, an app volume slider, and a live remaining-time display (elapsed time without a timer).
- Background playback through a Media3 `MediaSessionService` foreground service. Playback keeps going with the screen locked or the app in the background.
- Media notification and lock-screen controls (play/pause plus a Stop button). Bluetooth and headset media buttons also work.
- History stored in Room/SQLite: activity, sound, start and end time, planned duration, active duration, end reason.

## Project layout

```
core/   Pure Kotlin (no Android). Sound catalog, SessionEngine (timer, pause accounting,
        exactly-once recording, checkpoints, crash recovery) and its ports. Reusable for a
        future desktop/Windows target.
app/    Android: Room data layer, PlaybackService (Media3 + ExoPlayer), Compose UI.
tools/  Reproducible audio asset build (numpy + ffmpeg) and the adb test helper.
docs/   SOUND_LICENSES.md, the license manifest for every bundled sound.
```

Key files:
- `core/.../SessionEngine.kt`: session state machine. Active time comes only from the player's real "is playing" signal.
- `app/.../playback/PlaybackService.kt`: the single ExoPlayer, the MediaSession, the notification Stop button, and the audio attributes and focus settings.
- `app/.../playback/ExoPlaybackPort.kt`: maps ExoPlayer state to the engine, including the pause causes (user, focus loss, call, headphones).
- `app/.../data/`: Room entities, DAOs and repositories.

## Build

Toolchain used: JDK 21 (Temurin), Android SDK platform 37 / build-tools 36, Gradle 9.8.1 (wrapper),
AGP 9.4.1, Kotlin 2.4.21, Media3 1.11.1, Room 2.8.5, Compose BOM 2026.09.00.
minSdk 29 (Android 10), targetSdk 36.

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-21.0.12.1+1"
.\gradlew :app:assembleDebug            # APK: app\build\outputs\apk\debug\app-debug.apk
.\gradlew :core:test                    # engine unit tests (JVM)
.\gradlew :app:connectedDebugAndroidTest  # device/emulator tests (needs adb device)
```

Android Studio can open the folder directly; it uses the SDK at `%LOCALAPPDATA%\Android\Sdk`.

## Audio behavior

- **Audio focus.** The player requests `AUDIOFOCUS_GAIN` (usage MEDIA) before playing. Android then tells other media apps (Spotify, YouTube…) to pause, so the focus sound is the only intended audio.
- **Another app starts playing (permanent focus loss).** The session pauses with the note "another app started playing audio". It does not auto-resume. Paused time is not counted.
- **Calls and alarms (transient loss).** The session pauses and resumes automatically when the interruption ends, which is standard Android media behavior. The gap is not counted.
- **Navigation prompts (ducking).** Volume lowers briefly.
- **Headphones or Bluetooth disconnect.** The session pauses and does not resume on reconnect.
- **Stop.** Audio focus is released, the notification is removed, and the service stops.
- **No microphone.** The app never requests `RECORD_AUDIO`. The manifest explicitly strips `INTERNET`, `ACCESS_NETWORK_STATE` and `RECORD_AUDIO`.

What Android does not let an app guarantee:
- Audio focus is cooperative. Apps that ignore focus, and some games, system sounds, or apps that request focus *after* ours, can still play. Android then takes focus from Focus Sounds and it pauses, as described above.
- Android 12+ fades out the previous focus holder; the exact fade is up to the OS.
- Some OEM battery savers (for example aggressive "app killers") may still stop background apps. If that happens, the session is recorded as *interrupted* at its last saved checkpoint.

## Session recording rules

- One row per session, inserted at start and finished exactly once (`UPDATE … WHERE state='active'`).
- Active duration counts only time actually playing. Pauses, calls, focus loss and buffering are excluded. Timed sessions are capped at the planned duration.
- End reasons:
  - `completed`: the timer ran out.
  - `stopped`: the user stopped the session from the app, notification, or media controls.
  - `interrupted`: playback error, service teardown, or process death.
  - `cancelled`: stopped before 1 s of playback.
- Progress is checkpointed every 15 s while playing, and at every pause.
- If the process is killed, the next app start closes the session as `interrupted`, with the last checkpoint as both duration and end time. Elapsed time is never invented; at most 15 s may be lost.
- After a kill, the service is not restarted (`START_NOT_STICKY`) and no stale notification is left behind.
- History shows an activity's current name, which follows renames. For deleted activities it shows the name recorded at the time, marked "(deleted activity)".
- History is not included in cloud backup (`allowBackup=false`).

## Sound assets

See `docs/SOUND_LICENSES.md`.
- `python tools/generate_noise.py` regenerates the noise loops.
- `python tools/build_recordings.py` rebuilds Ocean, Stream and Forest from the hash-verified CC0 sources.
