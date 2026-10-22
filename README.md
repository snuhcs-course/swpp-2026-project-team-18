# JustInTime — Iteration 2 Demo

**An alarm you set by arrival confidence, not by clock time.**

You pick how sure you want to be of arriving on time — τ, e.g. 0.90 for a class, 0.99
for an exam — and the app works backwards from the event:

```
alarm = event start − 10-min buffer − τ-quantile of (prep + travel)
```

| | |
| --- | --- |
| Iteration | 2 · 2026-10-12 – 2026-10-23 |
| Snapshot | `main` at `e3698d7` (2026-10-21), the final Iteration 2 snapshot |
| Version | 0.11.0 — server `APP_VERSION` and app `versionName` |
| Server | `https://justintime-api.onrender.com` ([`/api/health`](https://justintime-api.onrender.com/api/health)) |
| Demo video | [`iteration2_demo.mp4`](iteration2_demo.mp4) ([what it shows](#demo-video)) |

Everything needed to run the demo is on this page. The full project README
(architecture, API, deployment, configuration) is on
[`main`](https://github.com/snuhcs-course/swpp-2026-project-team-18/blob/main/README.md);
the Iteration 1 demo is on
[`iteration-1-demo`](https://github.com/snuhcs-course/swpp-2026-project-team-18/tree/iteration-1-demo).

**Contents:** [Demo video](#demo-video) · [What the demo shows](#what-the-demo-shows) ·
[Major progress in Iteration 2](#major-progress-in-iteration-2) ·
[Tech stack and environment](#tech-stack-and-environment) · [How to run](#how-to-run) ·
[Automated run](#automated-run) · [Known limitations and todos](#known-limitations-and-todos) ·
[Verification](#verification)

---

## Demo video

**[▶ iteration2_demo.mp4](iteration2_demo.mp4)** — 2 min 42 s, no audio. Recorded on
2026-10-19 on an Android emulator (API 37, Windows 11 host) with the 0.11.0 app. It is the
screen recording of an [automated run](#automated-run) (`qa/demo/run.py --replay-routing`),
cut down to the key steps: the real UI is driven by UI Automator against a local 0.11.0
server that replays recorded Kakao routes, and GPS is simulated. To keep the waits short,
the run moves the event's start time before the alarm part, so the alarm rings at 5:56 PM
rather than at the 7:25 PM first computed.

| Time | What it shows |
| --- | --- |
| 0:00 | Sign up: password rules checked as you type and terms consent |
| 0:12 | Onboarding: home by road-name address search, then the usual prep time on the **clock dial** (5 min) |
| 0:27 | Settings: change the prep time on the dial (5 → 15 min), saved to the server |
| 0:36 | Add an event: title, date picker (past days can't be picked), time picker with AM/PM, the **weekly repeat** row, origin = home, destination through place search |
| 0:54 | Route choice: walking candidate with steps and kcal and the **server-written route guide**, then "이 경로로 계산" (calculate with this route) |
| 1:00 | Change the category from class (τ 0.90) to exam (τ 0.99) and add the event |
| 1:12 | The alarm and why: prep blocks + 11-min walk + 10-min buffer, each labeled, with the route map |
| 1:27 | Log out and log back in; the event and settings are still there |
| 1:36 | Home: the next alarm and the event's alarm switch. Adding a second event on the same day re-applies the **first-alarm-of-the-day rule**, so only the first one stays on |
| 1:54 | **Risk choice**: safe / normal / gamble with their alarm times (7:06 · 7:11 · 7:18 PM) and on-time probability (100 · 99 · 95%) from the modeled travel-time distribution; picking "normal" moves the alarm and shows 99% on-time |
| 2:12 | The **real alarm** rings full screen over the lock screen; "일어났음 · 이동 기록 시작" (I'm up · start tracking) |
| 2:15 | Morning record: one tap per prep block (세수, 가방 챙기기) |
| 2:27 | Trip tracking: departure detected, progress and the live route, then **arrival on the first fix within 100 m** of the destination |
| 2:39 | Back on Home with the trip recorded |

Not in the video, but in the app and in the [automated run](#automated-run): the map preview
of each route candidate, an event whose destination is home, a repeating event's
per-occurrence switches, and the weekly report with its written summary.

---

## What the demo shows

Iteration 1 built one flow end to end: sign up, add an event, let the server compute the
alarm, have it ring, and record the trip. **Iteration 2 makes that flow trustworthy and
starts learning from it**: better route choice, risk you can pick, repeating events,
reports that explain the week, and travel corrections trained from real trips every day.

### Features implemented (whole app, as of this snapshot)

| # | Feature | What to look for |
| --- | --- | --- |
| 1 | Sign up and onboard | Server-checked password, terms consent, home by road-name or lot-number address or place name, prep time on a clock dial (5–240 min, checked by the server too), then an optional screen to pick which device calendars to read |
| 2 | Add an event | Title, date, time, category (sets τ: class 0.90 · exam 0.99), destination by place search, a pick on the map or **your current home**, origin home by default, and a **weekly repeat** on chosen weekdays |
| 3 | Choose a route | Kakao candidates **grouped by car, transit, walk and bicycle**, the fastest selected by default, a server-written guide sentence, transit badges and alternative bus numbers, walking steps and kcal, and a **map preview for every mode** |
| 4 | See the alarm and why | The server computes it; prep, travel and buffer add up to start − alarm exactly, each labeled estimated, observed or fixed. When a learned travel correction is used it says how many trips it came from |
| 5 | Choose risk | Safe / normal / gamble options with modeled alarm times and on-time probability. The choice is saved and the device alarm moves with it. Without enough uncertainty data the screen says so instead of inventing numbers |
| 6 | Wake up reliably | Full screen over the lock screen, 5-min snooze, re-registered after reboot, app update and clock changes. By default only the **first alarm of each day** is on; adding an event re-applies that rule for the day. Each occurrence of a repeating event has its own switch |
| 7 | Log the morning and commute | Dismissing the alarm starts one-tap prep logging and GPS tracking. Arrival is the **first valid fix within 100 m** of the destination. The progress bar shows a live ETA, and while moving the fastest route from the current position is redrawn every minute |
| 8 | Learn | Departures, arrivals, prep blocks and alarm logs are uploaded, queued on disk while offline. Prep observations feed the next calculation directly; travel corrections are trained **every day by GitHub Actions** (23:17 KST) |
| 9 | Review | A weekly report with a short **written summary** (LLM, with a fixed-sentence fallback), a per-day detail report and calibration: did you arrive as often as τ promised? |

Also in this build: Seoul subway and bus real-time arrivals on the route, device calendar
import from the linked calendars, and an offline cache with background sync.

The app's text is Korean. The rehearsal sheet with pass criteria for each step is
[docs/demo-checklist.md](docs/demo-checklist.md) (Korean).

---

## Major progress in Iteration 2

Compared with [`iteration-1-demo`](https://github.com/snuhcs-course/swpp-2026-project-team-18/tree/iteration-1-demo)
(`e7c0b1e`, 2026-10-08) — 94 commits. Design notes for each item are in
[`docs/iter2/`](docs/iter2/summary.md) (Korean).

**Planning and route choice**

- Route candidates grouped by mode with the fastest as default and a guide sentence (BE-7, BE-12)
- Map preview for walk and bicycle, then for car and transit too; the static map's scale and
  coordinate system were corrected so the route line lands in the right place
- Transit badges and alternative buses, walking steps and kcal, lateness sensitivity from τ
- Risk choice ⑤ connected end to end: modeled options, saving, and the device alarm update (BE-16)
- Home as a destination, following later changes of the home address (BE-18)
- Weekly repeating events: seven days of occurrences, an alarm and an exception per occurrence (BE-20)
- Events whose origin and destination are too close are rejected
- If a route lookup fails during a recompute, the last good alarm is kept instead of being cleared

**Alarm and tracking**

- Adding an event clears that day's manual alarm switches and re-applies the first-alarm rule (BE-25)
- Arrival is recorded on the first valid fix within 100 m, without the 2-minute stay (BE-24),
  and the time of entering the radius is stored
- After leaving home, blurry fixes no longer keep the screen on "준비 중" (preparing); stale
  evidence shows "알람 꺼짐" (alarm off) or "출발 확인 안 됨" (departure not confirmed) instead

**Onboarding and accounts**

- Prep time on a clock dial instead of "+5 min" buttons; the server rejects values outside 5–240 min (R-6)
- Sign-up limited to 10 attempts per IP per hour, with a Korean 429 message (R-8)
- Calendar-link screen after sign-up and a calendar switch in Settings; calendars with sync
  turned off are hidden, with guidance for Google and Kakao calendars

**Learning and data**

- Travel correction training (`train_models`) runs daily on GitHub Actions, learning from the raw
  Kakao estimate stored with each plan (R-3); learned route buckets match across UTC and local time
- The number of trips behind a travel correction is stored and shown (R-1)
- The "learning in progress" messages are gone: values come from sign-up input until
  observations exist, and the screens say what each value is based on
- New records for later learning: plan revision history, alarm logs, arrival radius entry time,
  API request logs and LLM call logs with a daily cap, with retention cleanup

**Reports**

- Weekly report screen ⑧ redesigned, per-day detail report ⑨ added, and a written summary
  from an LLM that falls back to fixed sentences when the call fails

**Testing**

- Line coverage measured per architectural layer on every CI run, for the backend, the app's
  JVM tests and the app's device tests
- Repository unit tests and device integration tests (screens + Room + repository with fake
  APIs), with an API 34 emulator job in CI
- Backend tests split into unit and API integration by marker, with a guard that keeps the split honest
- The end-to-end runner now works on Windows and API 37 emulators

---

## Tech stack and environment

| Part | Stack |
| --- | --- |
| App | Kotlin, Jetpack Compose (Material 3), Retrofit + OkHttp, Room, WorkManager, Google Play services Location · minSdk 34, targetSdk 37 · AGP 9.3, Gradle 9.5 |
| Server | Python 3.12, Django 5.2, Django REST Framework, SimpleJWT, gunicorn · Docker on Render (free plan, Singapore) |
| Database | Neon Postgres |
| Scheduled jobs | GitHub Actions (`train-models.yml`, daily travel correction training) |
| External APIs | Kakao (routes, place search, static maps), Seoul real-time subway and bus arrivals, an OpenAI-compatible LLM for the report summary — all called by the server only |

Development and test environment:

- **Build and manual runs:** Windows 11 Pro, Android Studio with its bundled JBR 25,
  Android SDK Platform 37; emulator Medium Phone on API 37 (Google Play image)
- **Demo video:** the same Windows PC and API 37 emulator, app 0.11.0 against a local server with replayed routes (2026-10-19)
- **End-to-end run:** the same Windows PC and API 37 emulator (`qa/demo/run.py --replay-routing`, 2026-10-21, all 5 UI tests passed)
- **CI:** GitHub Actions on Ubuntu; app integration tests on an API 34 emulator

The design behind it is in the Wiki's
[Design Documentation](https://github.com/snuhcs-course/swpp-2026-project-team-18/wiki/team18%E2%80%90iter1%E2%80%90design)
(v2.0 for Iteration 2).

---

## How to run

No backend setup is needed: every build talks to the shared server above.

### Quick start

1. `git clone -b iteration-2-demo https://github.com/snuhcs-course/swpp-2026-project-team-18.git`
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
git clone -b iteration-2-demo https://github.com/snuhcs-course/swpp-2026-project-team-18.git
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
the alarm to open over the lock screen. The **calendar** permission is asked only if you
link a calendar after sign-up or in Settings; "나중에" (later) skips it. To grant them
from a terminal:

```bash
adb shell pm grant com.swpp.wakeup android.permission.POST_NOTIFICATIONS
adb shell pm grant com.swpp.wakeup android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.swpp.wakeup android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.swpp.wakeup android.permission.READ_CALENDAR
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

More on phones, including battery settings: [docs/device-setup.md](docs/device-setup.md).

### 7. Accounts and the server

Create your own account for the demo. `demo@demo.com` / `demo1234` is a shared account
for looking around, so please don't use it to create events. Sign-up allows 10 attempts
per network address per hour.

The server is on Render's free plan and sleeps after 15 minutes idle. The first request
then takes 30–60 seconds, and the login screen says it is waking the server
("서버를 깨우는 중…"). [`/api/health`](https://justintime-api.onrender.com/api/health)
should report `"version": "0.11.0"`.

### 8. Demo tips

- **Trying the Iteration 2 features**
  - *Weekly repeat*: on the add-event screen, pick weekdays under repeat. Home lists the
    next seven days; switch one occurrence off and the others stay on.
  - *Route map*: on the route screen, tap a candidate card to open its map preview, then
    "이 경로로 계산" (calculate with this route).
  - *Risk*: open an event and pick safe / normal / gamble. Until enough trips exist, the
    screen explains that the uncertainty is not known yet.
  - *Home as destination*: choose "집" (home) as the destination when adding an event.
  - *Report*: the report card on Home opens this week's report; tap a day for its detail.
- **Getting the alarm in a few minutes**: set the start to about *now + prep + travel +
  10-min buffer + 3–5 min*, then check the alarm time the server computed. If it is
  already past, add the event again with a later start; there is no edit screen yet.
- Only the first alarm of the day rings by default. Turn others on with the switch on Home.
  Adding an event resets that day's switches to this rule.
- Test with real time; don't change the device clock.
- **Moving the emulator**: open the emulator's **⋯ → Location** (if the menu is missing,
  run the emulator in its own window instead of inside Android Studio).
  - Before dismissing the alarm, send a **Single point** at your saved home.
  - For the trip, use **Routes** from home to the destination and **Play route** at 1×.
  - Or from a terminal: `adb emu geo fix <longitude> <latitude>` — longitude first,
    e.g. `adb emu geo fix 126.929745 37.484267` (Sillim Station).
- **Departure** is detected after 2 fixes in a row at least 150 m from home, or after
  80 m of movement spread over at least 60 s. Fixes come every 30 s before departure and
  every 10 s while moving.
- **Arrival** is the first valid fix within **100 m** of the destination **coordinates
  saved with the event**, not the map center or a search result.

### 9. Troubleshooting

Tap the avatar on Home to open **Settings**. It shows the number of registered alarms
and the next one, alarms that couldn't be computed, the location permission, the linked
calendars and trip records not uploaded yet. Debug builds also show the server address
and a server check.

| Problem | Check, in this order |
| --- | --- |
| Can't sign in | Server address (build output or Settings) → [`/api/health`](https://justintime-api.onrender.com/api/health) → the error message on screen. After idle, the first request takes up to a minute |
| Sign-up refused with "too many requests" | The 10-per-hour limit; wait, or use another network |
| Times are 9 hours off | The emulator time zone (step 4) |
| Alarm doesn't ring or doesn't open full screen | The alarm time is in the future and registered (Settings, or `adb shell dumpsys alarm` and look for `com.swpp.wakeup`) → time zone → notification and full-screen permissions → volume and power saving |
| No trip recorded | Tracking starts only when you dismiss the alarm → precise location permission → enough GPS movement (tips above) → "올리지 못한 이동 기록" (records not uploaded) in Settings |
| Arrival not detected | Distance to the saved destination coordinates is under 100 m → the tracking notification |
| A calendar doesn't appear | Sync for that calendar is on in the phone's calendar app. Kakao 톡캘린더 can't be imported: Kakao's API doesn't return the title and place of events others created |
| Can't type Korean | On-screen keyboard setting and a Korean keyboard (step 4) |
| Gradle Sync or build fails | SDK Platform 37 installed (SDK Manager) → Gradle JDK set to Android Studio's bundled JDK (Settings → Build, Execution, Deployment → Build Tools → Gradle) |
| The app talks to a local server | Delete `devServerHost=` from `APP/local.properties` and build again |

Logs:

```bash
adb logcat -s AlarmScheduler AlarmReceiver TripTrackingService TripObservationQueue
adb shell dumpsys alarm
```

Don't share logs that contain tokens, keys or personal locations.

---

## Automated run

`qa/demo/run.py` drives the real UI with UI Automator on an emulator. It builds and uses a
separate QA app (**JustInTime QA**, `com.swpp.wakeup.qa`), so your own install is left
alone. It runs five UI tests:

| Test | Covers |
| --- | --- |
| `PublicSetupTest` | Sign-up, terms, home, prep time on the dial, routines, an event, the alarm switch, re-login |
| `RouteDisplayTest` | Grouped candidates, badges and alternative buses, the map preview and "calculate with this route" |
| `BackendFeaturesTest` | Home as destination, risk options and saving, travel correction count, weekly repeat with per-occurrence switches and deletion |
| `DemoFlowTest` | The real alarm on the lock screen, prep blocks, simulated GPS departure and arrival within 100 m, upload, the weekly report |
| `SignupThrottleTest` | The sign-up limit and its Korean 429 message |

Before the first run:

- One emulator running, API 34+, time zone `Asia/Seoul` (step 4), window, transition and
  animator animation scales set to 0 in Developer options. With several, add
  `--device emulator-5554`
- `JAVA_HOME` and `ANDROID_HOME` set as in step 3 (or `sdk.dir` in `APP/local.properties`)
- Ports 8765 and 8766 free
- A Python 3.12 environment at `backend/.venv`:

```bash
python3.12 -m venv backend/.venv
backend/.venv/bin/python -m pip install -r backend/requirements/dev.txt
# Windows: py -3.12 -m venv backend\.venv
#          backend\.venv\Scripts\python.exe -m pip install -r backend\requirements\dev.txt
```

Then, from the repository root (on Windows use `backend\.venv\Scripts\python.exe`):

```bash
# Local throwaway server with recorded routes: all 5 tests, no API keys needed (about 9 min)
backend/.venv/bin/python qa/demo/run.py --replay-routing --no-video

# Shared server, new QA account each run, no API keys needed (5–7 min)
backend/.venv/bin/python qa/demo/run.py --public-e2e --no-video

# Shared server, setup screens only (1–2 min)
backend/.venv/bin/python qa/demo/run.py --public-ui --no-video
```

Leave out `--no-video` to record the screen. Don't touch the emulator while it runs, and
don't run two modes at once. The first build takes longer while dependencies download.

A run has passed when it ends with `PASSED` and exit code 0. Results are in
`.artifacts/demo-qa/<run time>/`: `result.json` and `server-evidence.json`, a screenshot
per step, `failure.png` on failure, and the logs. `credentials.json` holds the QA
account's password, so don't share it. Each public run leaves its QA account on the
shared server. More detail: [qa/demo/README.md](qa/demo/README.md) (Korean).

---

## Known limitations and todos

**Not finished in Iteration 2**

- Four car candidates with duplicates removed (BE-8), and routes that follow the target
  arrival time and service hours (BE-11)
- Editing a saved event — the server recomputes on `PATCH`, but the app has no edit screen
- Confirming the first *scheduled* (not manual) run of the daily training job
- Alarm, GPS and power-saving checks on a phone; these were checked on the emulator

**Partial**

- On-time probability value — the pipeline is complete; the value waits for enough
  observed travel variance. Kakao returns one travel time with no spread, so the app shows
  why the number is missing instead of inventing one
- Replanning while moving ⑦ — colors, ETA and the live route; no checkpoints or causes yet
- Reports ⑧⑨ — the Figma panels for sleep, snoozes and per-weekday adjustment are left out
  because nothing records that data yet
- Sign-in — email and password only; the social buttons show a notice
- Automatic calendar sync — import reads the linked calendars on demand

**Next (Iteration 3)** — sharper travel learning (traffic, the user's walking speed, a
faster route when running late), a manual "arrived" button and better arrival detection
from activity recognition, barometer and Wi-Fi, email features for accounts, an app
logo, replanning before departure, and encrypted token storage.

**Known bugs**

1. The arrival result is not restored after the app process restarts
2. When the tracking service restarts, the moving state and the live route are lost
3. Map panning can jump twice as far during consecutive gestures while a response is slow
4. A small pinch (12%) snaps a whole zoom level

---

## Verification

```bash
cd backend && python -m pytest                      # backend unit + API integration tests
cd backend && python scripts/run_local_suite.py     # 8 HTTP suites against a live local server
cd APP     && ./gradlew testDebugUnitTest lintDebug assembleDebug   # app unit tests + lint + build
cd APP     && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.swpp.wakeup.integration
                                                    # app integration tests on a running emulator
```

CI runs all four on every push to `main` and every pull request, and reports line
coverage per layer. Results, coverage against the targets and the test strategy are in
the Wiki's
[Testing Documentation](https://github.com/snuhcs-course/swpp-2026-project-team-18/wiki/team18%E2%80%90iter2%E2%80%90testing);
backend setup is in [backend/README.md](backend/README.md).

---

SNU SWPP 2026 Fall · Team 18 · Momentum
