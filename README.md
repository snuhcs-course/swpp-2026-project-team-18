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
| Snapshot | `main` at `0682182` (2026-10-07). Refreshed from `main` until the deadline |
| Version | 0.10.0 — server and app |
| Server | `https://justintime-api.onrender.com` ([`/api/health`](https://justintime-api.onrender.com/api/health)) |
| Demo video | to be added |

This README covers the Iteration 1 demo. The full project README — architecture, API,
deployment, configuration — is on
[`main`](https://github.com/snuhcs-course/swpp-2026-project-team-18/blob/main/README.md).

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
| 7 | Leave, travel, arrive | Departure is detected 150 m from home or on sustained movement. While moving, the fastest route from the current position is redrawn every minute. Arrival = within 50 m of the destination for 2 min; the result stays on screen after tracking ends. Both are uploaded |
| 8 | Open the weekly report | On-time rate, slack and calibration — did you arrive as often as τ promised? |

Also in this build: Seoul subway and bus real-time arrivals on the route, device calendar
import, and an offline cache with background sync.

The on-time probability stays blank ("learning") on purpose. Kakao returns one travel
time with no variance, so the app waits for observed trips instead of showing an
invented percentage.

The rehearsal sheet with pass criteria for each step (D01–D12) is
[docs/demo-checklist.md](docs/demo-checklist.md) (Korean).

---

## How to run

**Requirements** — Android Studio with Android SDK 37, and an emulator or device on
**Android 14 (API 34) or newer**. No backend setup: every build talks to the shared server.

```bash
cd APP
./gradlew installDebug        # Windows: .\gradlew.bat installDebug
```

Create your own account for the demo. `demo@demo.com` / `demo1234` is a shared account
for looking around. Android Studio, macOS and Windows steps are in
[APP/README.md](APP/README.md) (Korean).

The server is on Render's free plan and sleeps after 15 minutes idle. The first request
then takes 30–60 seconds; the app shows that it is waking the server.

**Emulator setup**

```bash
# Time zone — the default GMT puts every time 9 hours off
adb shell cmd alarm set-timezone Asia/Seoul

# On-screen keyboard, needed to type Korean place names
adb shell settings put secure show_ime_with_hard_keyboard 1
```

Then add Korean under **Settings → Languages**. Permissions, mock GPS and physical
devices are covered in [docs/device-setup.md](docs/device-setup.md).

**Demo tips**

- To get the alarm a few minutes ahead, set the start to about *now + prep + travel +
  10-min buffer + 3–5 min*, then check the alarm time the server computed. If it is
  already past, create the event again with a later start — there is no edit screen yet.
- Only the first alarm of the day rings by default. Turn others on with the switch on Home.
- Test with real time; don't change the device clock.
- Move the emulator with **Extended controls → Location** or `adb emu geo fix <lng> <lat>`.
  Arrival needs the destination coordinates and a 2-minute stay.

**Automated run** — `qa/demo/run.py` drives the real UI on an emulator from sign-up to
the alarm, GPS arrival and the weekly report (macOS/Linux):

```bash
backend/.venv/bin/python qa/demo/run.py               # throwaway local server
backend/.venv/bin/python qa/demo/run.py --public-e2e  # shared server, new QA account
```

Setup and results: [qa/demo/README.md](qa/demo/README.md) (Korean).

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
- Risk choice ⑤ — the three option cards are built, but the server API that fills them
  is not, so the screen shows a "learning" notice
- Route choice ⑬ — mode tabs work. Car shows one route for now; badges, tolls and route
  guides wait for the server. Walk and bicycle get one route each, without slope data
- Repeating events and custom categories — marked "coming soon" until the server supports them
- Reports ⑧⑨ — part of the design is implemented
- Sign-in — email and password only; the social buttons show a notice

**Later iterations** — replanning before departure, group rooms, direct τ adjustment,
natural-language input, push notifications, encrypted token storage.

**Known bugs**

1. "Fastest route" means fastest within the chosen mode, not across modes
2. The arrival result is not restored after the app process restarts
3. When the tracking service restarts, the moving state and the live route are lost
4. Map panning can jump twice as far during consecutive gestures while a response is slow
5. A small pinch (12%) snaps a whole zoom level

---

## Verification

```bash
cd backend && python -m pytest                      # 795 unit tests
cd backend && python scripts/run_local_suite.py     # 8 HTTP suites against a live local server
cd APP     && ./gradlew testDebugUnitTest lintDebug assembleDebug   # 528 unit tests + lint + build
```

CI runs all three on every push to `main` and every pull request. The latest recorded
results are in [docs/demo-checklist.md](docs/demo-checklist.md); backend setup is in
[backend/README.md](backend/README.md).

---

SNU SWPP 2026 Fall · Team 18 · Momentum
