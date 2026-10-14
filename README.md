# Focus Sounds (Android)

A native Android app that plays one calm background sound while you study, code, read, write,
work on a project or meditate. It records every focus session on the device and turns that
history into a detailed progress dashboard.

Offline only: no account, backend, API key, analytics, cloud sync, or network permission.

## Features

**Focus**
- Pick an activity, one of five sounds (Brown Noise, White Noise, Ocean, Stream, Forest), and a timer: none, 25, 45 or 60 min, or custom (1–600 min).
- An optional 3, 5 or 10 s start countdown. It is cancelled if the app leaves the foreground.
- Active-session view:
  - a large timer with a progress ring (or an orbiting arc when there is no timer)
  - an estimated end time and a status line explaining every pause cause
  - pause/resume, stop, and volume controls
- Compact session feedback after each session (completed, stopped, interrupted or cancelled), with optional goal milestones.
- Background and locked-screen playback through a Media3 `MediaSessionService` foreground service, with notification, lock-screen and headset controls (play/pause and a Stop button).

**Progress dashboard**
- Ranges: Today, 7D, 30D, 90D, 12M, All, or a custom date range. Every card follows the selected range.
- Overview:
  - Hero card: total focus time, comparison with the previous equivalent period, sessions, average session length, and per-day average.
  - Goal rings for today and this week, plus a daily-goal strip.
  - Current and longest streak, focus days, best day, and longest session.
- Highlights: deterministic insights computed from your own data (comparison, top activity, top sound, goal record, streak, longest completed session, record day).
- Charts (all drawn with Compose Canvas):
  1. Focus trend: a smooth (monotone) area/line chart with tap-and-drag inspection, at hourly, daily, weekly, monthly or yearly resolution.
  2. Daily, weekly or monthly bars with a goal line; bars that reached the goal are solid.
  3. Activity donut with tap selection, an accessible legend, and an expandable "Other" group.
  4. Month calendar heatmap: five intensity levels and goal-met dots. It shows the difference between zero, not-tracked and future days, and has month navigation.
  5. Sound usage bars (active playback time, session counts, shares).
  6. Session-length histogram, start-hour distribution, and session-outcome breakdown (under "More breakdowns").
- An in-app "How Progress is calculated" section explains every rule.

**History, Activities and Settings**
- **History:** a full session library with no cap. Every session is listed newest first, grouped by day under pinned date headers, with a Today / Last 7 days / All time summary, filters by date, activity, sound and outcome, and a Back to top button for long histories. Each finished session has a visible delete button, and tapping a row opens its details with a Delete session action. Deleting asks for confirmation. A running session has no delete action, and the database query refuses it too.
- **Activities:** built-in versus custom, rename and delete with a confirmation that explains history is kept, and per-activity totals.
- **Settings:**
  - *Appearance:* always dark, with an abstract night-landscape backdrop on Focus (sky light only on Progress) drawn in Compose from the accent. Its colors are capped so secondary text keeps 4.5:1 contrast anywhere on it (text drawn directly on the landscape uses that color or brighter); it has no looping animation and only redraws when the accent or screen changes. Accent color via an HSV picker, HEX entry with validation, 10 presets, live preview, Apply/Cancel, and Reset to default. Animation intensity (Full / Reduced / Off) and haptics.
  - *Goals:* daily and weekly goals, and goal milestones.
  - *Focus:* remember last selection or use defaults; start countdown; completion chime and vibration.
  - *Data & privacy:* CSV export through the system file picker, and clear history (confirmed, never touches a running session).
  - *About:* version and sound credits.

## Progress rules

- **Focus time** is the persisted active (actually playing) time. Paused time, calls and interruptions are never counted.
- **Day attribution:** a session counts on the local day it **started**, even if it ran past midnight. The database does not store when inside a session the pauses happened, so splitting a session across days would invent precision.
- **Which sessions count:**
  - Interrupted sessions count with the time saved up to their last checkpoint.
  - Cancelled sessions (no playback) are not counted.
  - The running session is included live, never twice.
  - A running session's length is excluded from the session-length histogram until it ends.
- **Streaks:** a focus day has at least 1 minute of focus. The current streak is the run of consecutive focus days ending today, or ending yesterday while today has no focus yet.
- **Comparisons:**
  - The previous period has the same length (the previous 12 calendar months for 12M).
  - When the current period ends now, the previous one is cut at the same time of day.
  - A percentage only appears when the previous period was fully tracked and had at least 5 minutes of focus.
- **Untracked and future days:** days before the first recorded session are "not tracked", never zero. Future days are never counted.
- **Goal-day counts:** these exclude untracked days, and they exclude today until today's goal is met.
- **Weeks** start on the device locale's first day of the week.

## Project layout

```
core/   Pure Kotlin (no Android): SessionEngine, ProgressAnalytics, ColorMath (contrast-safe
        accent derivation), Durations/Csv formatting. Unit-tested on the JVM; reusable for a
        future desktop/Windows target.
app/    Android: Room data layer, settings repository, PlaybackService (Media3 + ExoPlayer),
        completion chime/vibration, Compose UI (theme, icons, components, charts, screens).
tools/  Reproducible audio assets (noise, recordings, chime), adb helper, emulator-only demo seeding.
docs/   SOUND_LICENSES.md, the license manifest for every bundled sound.
```

Key files:
- `core/.../analytics/ProgressAnalytics.kt`: all statistics, charts data, streaks, goals, insights.
- `core/.../ColorMath.kt`: HEX parsing, HSV, WCAG contrast. `AccentPalette` keeps any chosen accent legible: fills ≥ 3:1, accent text ≥ 4.5:1, text on accent ≥ 4.5:1.
- `app/.../ui/theme/Theme.kt`: the always-dark token system that every screen uses; the accent also feeds Material components.
- `app/.../ui/charts/`: the chart library (trend, bars, donut, heatmap, usage bars, stacked bar, goal strip).
- `app/.../data/Settings.kt`: observable preferences, persisted in SharedPreferences (the existing store; no new storage framework).

## Data and persistence

- The Room schema is unchanged (version 1), so no migration was needed. Goals and preferences live in SharedPreferences.
- Instrumented tests run with `FocusTestRunner`, which switches the app to separate `_test` database and preference files, so tests can never touch real history.
- `tools/seed_emulator_demo.py` fills an **emulator** with realistic demo sessions for screenshots. It refuses to run on a real device. The app itself never contains demo data.

## Build and test

Toolchain: JDK 21 (Temurin), Android SDK platform 37 / build-tools 36, Gradle 9.8.1 (wrapper),
AGP 9.4.1, Kotlin 2.4.21, Media3 1.11.1, Room 2.8.5, Compose BOM 2026.09.00.
minSdk 29 (Android 10), targetSdk 36.

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-21.0.12.1+1"
.\gradlew :app:assembleDebug              # APK: app\build\outputs\apk\debug\app-debug.apk
.\gradlew :core:test                      # 53 JVM unit tests
.\gradlew :app:connectedDebugAndroidTest  # 28 instrumented tests (needs an adb device)
```

Note: the playback-timing tests (`EngineOnDeviceTest`, `SoundAssetsTest`) assume a responsive
device. On a heavily loaded emulator they can miss their timing windows.

## Audio behavior

- **Audio focus.** The player requests `AUDIOFOCUS_GAIN` (usage MEDIA) before playing. Android then tells other media apps to pause.
- **Interruptions:**
  - Another app taking focus pauses the session, with no auto-resume.
  - Calls and alarms pause it, and it resumes afterwards.
  - Unplugging headphones pauses it, with no auto-resume.
  - Paused time is never counted.
- **Stop.** Releases focus, removes the notification, and stops the service.
- **Completion chime.** Plays with notification-event attributes, after focus is released, so it follows notification volume and Do Not Disturb.
- **Permissions.** No `RECORD_AUDIO`. The manifest strips `INTERNET`, `ACCESS_NETWORK_STATE` and `RECORD_AUDIO`. `VIBRATE` is used only for the optional completion vibration.
- **What Android cannot guarantee:**
  - Audio focus is cooperative; apps that ignore it can still play.
  - Aggressive OEM battery savers may still stop background apps. The session is then recorded as interrupted at its last checkpoint.

## Session recording rules

- One row per session, finished exactly once (`UPDATE … WHERE state='active'`).
- Active time excludes all paused or not-playing time. Timed sessions are capped at the planned duration.
- End reasons: `completed`, `stopped`, `interrupted`, `cancelled` (stopped before 1 s of playback).
- Checkpoints are written every 15 s while playing and at each pause.
- After process death, the session is closed at its last checkpoint (at most 15 s lost, never invented). The service is not restarted (`START_NOT_STICKY`).
- Renames show the current name in history; deleted activities keep their recorded name.
- History is excluded from cloud backup.

## Sound assets

See `docs/SOUND_LICENSES.md`.
- `python tools/generate_noise.py` regenerates the noise loops.
- `python tools/build_recordings.py` rebuilds Ocean, Stream and Forest from the hash-verified CC0 sources.
- `python tools/generate_chime.py` regenerates the completion chime.
