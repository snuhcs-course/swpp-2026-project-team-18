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
| Snapshot | `main` at `a269b29` (2026-09-30). Refreshed from `main` until the deadline |
| Version | 0.9.0 — server and app |
| Server | `https://justintime-api.onrender.com` ([`/api/health`](https://justintime-api.onrender.com/api/health)) |
| Demo video | to be added |

This README covers the Iteration 1 demo. The full project README — architecture, API,
deployment, configuration — is on
[`main`](https://github.com/snuhcs-course/swpp-2026-project-team-18/blob/main/README.md).

---

## What the demo shows

Iteration 1 goal: **one flow that works end to end** — sign in, add an event, let the
server compute the alarm, have it ring, and record the trip back to the server.

| # | Step | What to look for |
| --- | --- | --- |
| 1 | Sign up, then set home and usual prep time | Asked once at first run; editable later in Settings |
| 2 | Add an event — title, time, category, destination, route | The category sets τ (class 0.90 · exam 0.99). Destination by Kakao search or a pick on the map; route from Kakao's transit, walk, bicycle and car candidates |
| 3 | Open the event | The server-computed alarm with its reasoning: prep + travel + 10-min buffer = start − alarm exactly, each item labeled measured or fixed, plus a route map |
| 4 | The alarm rings | Full screen over the lock screen, 5-min snooze. Alarms for the next 7 days are registered and survive reboot, app update and clock changes |
| 5 | Dismiss it | Prep logging starts — one tap per routine block (the demo account has six) — and GPS trip tracking |
| 6 | Leave, travel, arrive | Departure is detected 150 m from home or on sustained movement. While moving, the fastest route from the current position is redrawn every minute. Arrival = within 50 m of the destination for 2 min. Both are uploaded |
| 7 | Open the weekly report | On-time rate, slack and calibration — did you arrive as often as τ promised? |

Also in this build: Seoul subway and bus real-time arrivals on the route, device calendar
import, and an offline cache with background sync.

The on-time probability stays blank ("learning") on purpose. Kakao returns one travel
time with no variance, so the app waits for observed trips instead of showing an
invented percentage.

---

## How to run

**Requirements** — Android Studio with Android SDK 37, and an emulator or device on
**Android 14 (API 34) or newer**. No backend setup: every build talks to the shared server.

```bash
cd APP
./gradlew assembleDebug        # Windows: .\gradlew.bat assembleDebug
./gradlew installDebug
```

Sign in with `demo@demo.com` / `demo1234` (home: Sillim Station), or create an account.

The server is on Render's free plan and sleeps after 15 minutes idle. The first request
then takes 30–60 seconds; the app shows that it is waking the server.

**Emulator setup**

```bash
# Time zone — the default GMT puts every time 9 hours off
adb shell cmd alarm set-timezone Asia/Seoul

# On-screen keyboard, needed to type Korean place names
adb shell settings put secure show_ime_with_hard_keyboard 1
```

Then add Korean under **Settings → Languages**. For a physical device, see
[docs/device-setup.md](docs/device-setup.md).

**Demo tips**

- The alarm rings at the time shown on the event screen. Times already past are not
  registered, so for a live demo pick a start time that puts the alarm a few minutes ahead.
- On an emulator, move the device for step 6 with **Extended controls → Location** or
  `adb emu geo fix <lng> <lat>`.

---

## Known limitations and todos

**Still open for Iteration 1**

- Record the wake-up time when the alarm is dismissed (departure and arrival are recorded)
- Edit a saved event — the server recomputes on `PATCH`, but the app has no edit screen
- Physical-device run: locked screen, power saving, reboot, network loss. Checked on the
  emulator only so far
- Write-up of test results

**Partial**

- On-time probability value — waits for observed travel variance (above)
- Risk choice — an explanation screen only, no per-time options yet
- Route choice — one list; the per-mode tabs on the Figma board are not built
- Reports — part of the design is implemented
- Sign-in — email and password only

**Later iterations** — replanning before departure, group rooms, direct τ adjustment,
natural-language input, push notifications, encrypted token storage.

**Known bugs**

1. "Fastest route" means fastest within the chosen mode, not across modes
2. The arrived state does not stay on screen
3. When the tracking service restarts, the moving state and the live route are lost
4. Map panning can jump twice as far during consecutive gestures while a response is slow
5. A small pinch (12%) snaps a whole zoom level

---

## Verification

```bash
cd backend && python -m pytest                      # 717 unit tests
cd backend && python scripts/run_local_suite.py     # 8 HTTP suites against a live local server
cd APP     && ./gradlew testDebugUnitTest lintDebug assembleDebug   # 487 unit tests + lint + build
```

CI runs all three on every push to `main` and every pull request. Backend setup is in the
[main README](https://github.com/snuhcs-course/swpp-2026-project-team-18/blob/main/README.md#work-on-the-backend).

---

SNU SWPP 2026 Fall · Team 18 · Momentum
