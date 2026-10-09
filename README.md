# JustInTime — Iteration 1 Demo

**An alarm you set by arrival confidence, not by clock time.**

You pick how sure you want to be of arriving on time — τ, e.g. 0.90 for a class, 0.99
for an exam — and the app works backwards from the event:

```
alarm = event start − 10-min buffer − τ-quantile of (prep + travel)
```

| | |
| --- | --- |
| Iteration | 1 · due 2026-10-09 |
| Snapshot | `main` at `e7c0b1e` (2026-10-08), the final Iteration 1 snapshot |
| Version | 0.10.0 — server and app |
| Server | `https://justintime-api.onrender.com` ([`/api/health`](https://justintime-api.onrender.com/api/health)) |
| Demo video | [`iteration1_demo.mp4`](iteration1_demo.mp4) · 1 min 50 s ([what it shows](#demo-video)) |

Everything needed to run the demo is on this page. The full project README
(architecture, API, deployment, configuration) is on
[`main`](https://github.com/snuhcs-course/swpp-2026-project-team-18/blob/main/README.md).

**Contents:** [Demo video](#demo-video) · [What the demo shows](#what-the-demo-shows) ·
[Tech stack and test environment](#tech-stack-and-test-environment) · [How to run](#how-to-run) ·
[Automated run](#automated-run) · [Known limitations and todos](#known-limitations-and-todos) ·
[Verification](#verification)

---

## Demo video

**[▶ iteration1_demo.mp4](iteration1_demo.mp4)** — 1 min 50 s, no audio. Recorded on
2026-10-09 on a Galaxy S24 (Android 16) with the 0.10.0 app against the shared server.

| Time | What it shows |
| --- | --- |
| 0:00 | Log in with email and password |
| 0:12 | First-run onboarding: home found through place search, then the usual prep time (15 min) |
| 0:24 | Home, still empty for this account |
| 0:28 | Add an event: title, date picker (past days can't be picked), time picker with AM/PM, origin = home, destination through place search |
| 1:08 | Route choice by mode, all from Kakao: car (16 min, fastest) and transit options with segment bars, stop times and badges (cheapest, no transfer, subway); no walking route for this trip |
| 1:24 | Pick the 26-min bus with no transfer, keep the category "class" (τ = 0.90) and add the event |
| 1:32 | The alarm computed by the server: 14:39 = 15:30 start − 10-min buffer − 26-min bus − 15-min prep, each item labeled fixed or measured. The on-time probability says "learning" and why. The route map shows a faster alternative (2 min) |
| 1:48 | Home: the next alarm, and the event with its alarm switch on |

The video covers steps 1–4 below; it logs in to an existing account instead of signing up.
Steps 5–8 — the alarm ringing, the morning routine, trip tracking and the weekly report — need
the real alarm time and a trip, so they are not in the video; the
[automated run](#automated-run) goes through them on an emulator.

---

## What the demo shows

Iteration 1 goal: **one flow that works end to end** — sign up, add an event, let the
server compute the alarm, have it ring, and record the trip back to the server.

| # | Step | What to look for |
| --- | --- | --- |
| 1 | Sign up, then set home and usual prep time | The server checks the password rules as you type (8+ characters, letters and digits) and requires terms consent. Home accepts a road-name or lot-number address as well as a place name |
| 2 | Add an event — title, date, time, origin, destination, route, category | Date and time come from pickers. Routes are split into car, transit, walk and bicycle tabs, all from Kakao. The category sets τ (class 0.90 · exam 0.99) |
| 3 | Back on Home | Each event has an alarm switch saved on the server. By default only the **first alarm of each day** is on; switching it off hands the role to the next event |
| 4 | Open the event | The server-computed alarm with its reasoning: prep + travel + 10-min buffer = start − alarm exactly, each item labeled measured or fixed, plus a route map |
| 5 | The alarm rings | Full screen over the lock screen, 5-min snooze. Enabled alarms for the next 7 days are registered and survive reboot, app update and clock changes |
| 6 | Dismiss it | Prep logging starts — one tap per routine block (make a couple in the routine editor first) — and GPS trip tracking |
| 7 | Leave, travel, arrive | Departure is detected 150 m from home or on sustained movement. While moving, the fastest route for the chosen mode is redrawn from the current position every minute. Arrival = within 50 m of the destination for 2 min; the result stays on screen after tracking ends. Both are uploaded |
| 8 | Open the weekly report | On-time rate, slack and calibration — did you arrive as often as τ promised? |

Also in this build: Seoul subway and bus real-time arrivals on the route, device calendar
import, and an offline cache with background sync.

The on-time probability stays blank ("learning") on purpose. Kakao returns one travel
time with no variance, so the app waits for observed trips instead of showing an
invented percentage.

The app's text is Korean. The rehearsal sheet with pass criteria for each step (D01–D12)
is [docs/demo-checklist.md](docs/demo-checklist.md) (Korean).

---

## Tech stack and test environment

| Part | Stack |
| --- | --- |
| App | Kotlin, Jetpack Compose (Material 3), Retrofit + OkHttp, Room, WorkManager, Google Play services Location · minSdk 34, targetSdk 37 |
| Server | Python 3.12, Django 5.2, Django REST Framework, SimpleJWT, gunicorn · Docker on Render (free plan, Singapore) |
| Database | Neon Postgres |
| External APIs | Kakao (routes, place search, static maps) and Seoul real-time subway and bus arrivals, called by the server only |

Tested on:

- **Demo video:** Galaxy S24 (SM-S921N), Android 16, app 0.10.0, shared server 0.10.0
- **Build and manual runs:** Windows 11 Pro, Android Studio with its bundled JBR 25.0.2,
  Android SDK Platform 37; emulator on API 36 (Google Play image)
- **Automated run:** macOS with an API 34 emulator

The design behind it is in the Wiki's
[Design Documentation](https://github.com/snuhcs-course/swpp-2026-project-team-18/wiki/team18%E2%80%90iter1%E2%80%90design).

---

## How to run

No backend setup is needed: every build talks to the shared server above.

### Quick start

1. `git clone -b iteration-1-demo https://github.com/snuhcs-course/swpp-2026-project-team-18.git`
2. In Android Studio, open the **`APP`** folder and wait for Gradle Sync.
3. Start an emulator (Google Play image) or connect a phone, on Android 14 (API 34) or newer.
4. Press **Run ▶**, allow notifications and precise location, then sign up and add an event.

The first request after 15 idle minutes wakes the server and takes 30–60 seconds. Steps 1–9
below cover each part in detail, including a phone, permissions and troubleshooting.

### 1. Requirements

- **Android Studio**, a recent version. Its bundled JDK (JBR 25) is what Gradle uses; if
  Gradle can't find a JDK 25 it downloads one on the first build
- **Android SDK Platform 37**. Android Studio offers to install it during the first sync;
  otherwise use **Tools → SDK Manager**
- An **emulator or phone with Android 14 (API 34) or newer**. Emulators need a
  **Google Play** or **Google APIs** system image, because location uses Google Play
  services. On Apple Silicon, use an ARM64 image
- Internet access

### 2. Get the code

```bash
git clone -b iteration-1-demo https://github.com/snuhcs-course/swpp-2026-project-team-18.git
cd swpp-2026-project-team-18
```

### 3. Build and install

**Android Studio (recommended)**

1. **File → Open** and choose the **`APP` folder** inside the repository, not the
   repository root. Wait for Gradle Sync to finish.
2. **Device Manager** → create a phone with an API 34+ Google Play image, and start it.
3. Select the device and press **Run ▶**. JustInTime opens on the login screen.
4. The Build output should show
   `[JustInTime] API=https://justintime-api.onrender.com/`. If it shows another address,
   delete the `devServerHost=` line from `APP/local.properties` and run again.

**Terminal** — start an emulator or connect a phone first, then from the repository root:

```bash
# macOS
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
cd APP
./gradlew installDebug
adb shell am start -n com.swpp.wakeup/.ui.auth.LoginActivity
```

```powershell
# Windows PowerShell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:ANDROID_HOME\platform-tools;$env:Path"
cd APP
.\gradlew.bat installDebug
adb shell am start -n com.swpp.wakeup/.ui.auth.LoginActivity
```

Change the paths if Android Studio or the SDK is installed elsewhere. `installDebug`
installs on every connected device; with more than one, add `-s <device-id>` to `adb`
(`adb devices -l` lists the IDs). The APK is written to
`APP/app/build/outputs/apk/debug/app-debug.apk`.

### 4. Set up the emulator once

```bash
# Time zone. The default (GMT) puts every time 9 hours off
adb shell cmd alarm set-timezone Asia/Seoul

# Show the on-screen keyboard, needed to type Korean place names
adb shell settings put secure show_ime_with_hard_keyboard 1
```

Then add a Korean keyboard in the emulator (Gboard → Languages → Add keyboard → 한국어).

### 5. Permissions

When the app asks, allow **notifications** and **precise location**. "While using the
app" is enough: tracking runs in a foreground service that starts when you dismiss the
alarm. On Android 14 and newer, **full-screen notifications** must also be allowed for
the alarm to open over the lock screen. To grant all of them from a terminal:

```bash
adb shell pm grant com.swpp.wakeup android.permission.POST_NOTIFICATIONS
adb shell pm grant com.swpp.wakeup android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.swpp.wakeup android.permission.ACCESS_COARSE_LOCATION
adb shell appops set com.swpp.wakeup USE_FULL_SCREEN_INTENT allow
```

Check that the device volume is up.

### 6. On a phone instead of an emulator

- **USB**: Settings → About phone → tap *Build number* 7 times, then turn on
  *USB debugging* in Developer options. Connect the cable and accept the prompt.
- **Wireless** (Android 11+): put the phone and the computer on the same network. In
  Developer options → *Wireless debugging* → *Pair device with pairing code*, run
  `adb pair <IP>:<pairing-port>` and enter the code, then
  `adb connect <IP>:<port>` with the address shown on the Wireless debugging screen.
  The two ports are different. Networks that block device-to-device traffic, such as
  campus Wi-Fi, stop this; use USB or a VPN such as Tailscale instead.
- `adb devices -l` should list the phone as `device`. Then use step 3, or install the
  built APK with `adb -s <device-id> install -r APP/app/build/outputs/apk/debug/app-debug.apk`.

Physical-device behavior (power saving, real GPS error) is not verified yet; see the
limitations below.

### 7. Accounts and the server

Create your own account for the demo. `demo@demo.com` / `demo1234` is a shared account
for looking around, so please don't use it to create events.

The server is on Render's free plan and sleeps after 15 minutes idle. The first request
then takes 30–60 seconds, and the login screen says it is waking the server
("서버를 깨우는 중…").

### 8. Demo tips

- **Getting the alarm in a few minutes**: set the start to about *now + prep + travel +
  10-min buffer + 3–5 min*, then check the alarm time the server computed. If it is
  already past, add the event again with a later start; there is no edit screen yet.
- Only the first alarm of the day rings by default. Turn others on with the switch on Home.
- Test with real time; don't change the device clock.
- **Moving the emulator**: open the emulator's **⋯ → Location** (if the menu is missing,
  run the emulator in its own window instead of inside Android Studio).
  - Before dismissing the alarm, send a **Single point** at your saved home.
  - For the trip, use **Routes** from home to the destination and **Play route** at 1×.
  - Or from a terminal: `adb emu geo fix <longitude> <latitude>` — longitude first,
    e.g. `adb emu geo fix 126.929745 37.484267` (Sillim Station).
- **Departure** is detected after 2 fixes in a row at least 150 m from home, or after
  80 m of movement spread over at least 60 s. Fixes come every 30 s before departure and
  every 10 s while moving; fixes less accurate than 50 m are ignored.
- **Arrival** needs 2 minutes within 50 m of the destination **coordinates saved with
  the event**, not the map center or a search result. The tracking notification shows
  "도착 확인 중" (confirming arrival) and the time left.

### 9. Troubleshooting

Tap the avatar on Home to open **Settings**. It shows the number of registered alarms
and the next one, alarms that couldn't be computed, the location permission, and trip
records not uploaded yet. Debug builds also show the server address and a server check.

| Problem | Check, in this order |
| --- | --- |
| Can't sign in | Server address (build output or Settings) → [`/api/health`](https://justintime-api.onrender.com/api/health) → the error message on screen. After idle, the first request takes up to a minute |
| Times are 9 hours off | The emulator time zone (step 4) |
| Alarm doesn't ring or doesn't open full screen | The alarm time is in the future and registered (Settings, or `adb shell dumpsys alarm` and look for `com.swpp.wakeup`) → time zone → notification and full-screen permissions → volume and power saving |
| No trip recorded | Tracking starts only when you dismiss the alarm → precise location permission → enough GPS movement (tips above) → "올리지 못한 이동 기록" (records not uploaded) in Settings |
| Arrival not detected | Distance to the saved destination coordinates is under 50 m → 2-minute stay → the tracking notification |
| Can't type Korean | On-screen keyboard setting and a Korean keyboard (step 4) |
| Gradle Sync or build fails | SDK Platform 37 installed (SDK Manager) → Gradle JDK set to Android Studio's bundled JDK (Settings → Build, Execution, Deployment → Build Tools → Gradle) |
| The app talks to a local server | Delete `devServerHost=` from `APP/local.properties` and build again |
| State lost after the app restarts | A known bug (below) |

Logs:

```bash
adb logcat -s AlarmScheduler AlarmReceiver TripTrackingService TripObservationQueue
adb shell dumpsys alarm
```

Don't share logs that contain tokens, keys or personal locations.

---

## Automated run

`qa/demo/run.py` drives the real UI with UI Automator on an emulator: sign-up, home and
routines, an event and its alarm switch, the real alarm, the prep blocks, a simulated
GPS trip, the observation upload and the weekly report. It builds and uses a separate QA
app (**JustInTime QA**, `com.swpp.wakeup.qa`), so your own install is left alone.

**macOS or Linux only** — on Windows it stops with `WinError 193`.

Before the first run:

- One emulator running, API 34+, time zone `Asia/Seoul` (step 4). With several, add
  `--device emulator-5554`
- `JAVA_HOME` and `ANDROID_HOME` set as in step 3 (or `sdk.dir` in `APP/local.properties`)
- Ports 8765 and 8766 free
- A Python 3.12 environment at `backend/.venv`:

```bash
python3.12 -m venv backend/.venv
backend/.venv/bin/python -m pip install -r backend/requirements/dev.txt
```

Then, from the repository root:

```bash
# Shared server, new QA account each run, no API keys needed (5–7 min)
backend/.venv/bin/python qa/demo/run.py --public-e2e --no-video

# Shared server, setup screens only (1–2 min)
backend/.venv/bin/python qa/demo/run.py --public-ui --no-video

# Throwaway local server: copy backend/.env.example to backend/.env and set KAKAO_REST_API_KEY
backend/.venv/bin/python qa/demo/run.py --no-video
```

Leave out `--no-video` to record the screen. Don't touch the emulator while it runs, and
don't run two modes at once. The first build takes longer while dependencies download.

A run has passed when it ends with `PASSED` and exit code 0. Results are in
`.artifacts/demo-qa/<run time>/` (public modes: `public-e2e-<run time>/` or
`public-ui-<run time>/`): `result.json` and `server-evidence.json`, a screenshot per
step, `failure.png` on failure, and the logs. `credentials.json` holds the QA account's
password, so don't share it. Each public run leaves its QA account on the shared server.

---

## Known limitations and todos

**Not finished in Iteration 1**

- Record the wake-up time when the alarm is dismissed (departure and arrival are recorded)
- Edit a saved event — the server recomputes on `PATCH`, but the app has no edit screen
- Alarm checks on a phone: locked screen, power saving, reboot, network loss. The app runs
  on a Galaxy S24 (demo video), but these were checked on the emulator only

**Partial**

- On-time probability value — waits for observed travel variance (above)
- Risk choice ⑤ — the three option cards are built, but the server API that fills them
  is not, so the screen shows a "learning" notice
- Alarm screen ④ — when an alarm can't be computed (no place, or the route lookup
  failed), there is no retry button; add the event again
- Route choice ⑬ — mode tabs work. Car shows one route for now; badges, tolls and route
  guides wait for the server. Walk and bicycle get one route each, without slope data
- Repeating events and custom categories — marked "coming soon" until the server supports them
- Reports ⑧⑨ — part of the design is implemented
- Sign-in — email and password only; the social buttons show a notice

**Later iterations** — replanning before departure, group rooms, direct τ adjustment,
natural-language input, push notifications, encrypted token storage.

**Known bugs**

1. The arrival result is not restored after the app process restarts
2. When the tracking service restarts, the moving state and the live route are lost
3. Map panning can jump twice as far during consecutive gestures while a response is slow
4. A small pinch (12%) snaps a whole zoom level

---

## Verification

```bash
cd backend && .venv/bin/python -m pytest                   # 795 unit tests
cd backend && .venv/bin/python scripts/run_local_suite.py  # 8 HTTP suites against a live local server
cd APP     && ./gradlew testDebugUnitTest lintDebug assembleDebug   # 528 unit tests + lint + build
```

CI runs all three on every push to `main` and every pull request. Results, the test
strategy and how each acceptance test is checked are in the Wiki's
[Testing Documentation](https://github.com/snuhcs-course/swpp-2026-project-team-18/wiki/team18%E2%80%90iter1%E2%80%90testing);
backend setup is in [backend/README.md](backend/README.md).

---

SNU SWPP 2026 Fall · Team 18 · Momentum
