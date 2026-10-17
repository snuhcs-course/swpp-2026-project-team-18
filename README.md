# JustInTime

[![CI](https://github.com/snuhcs-course/swpp-2026-project-team-18/actions/workflows/ci.yml/badge.svg)](https://github.com/snuhcs-course/swpp-2026-project-team-18/actions/workflows/ci.yml)

**An alarm you set by arrival confidence, not by clock time.**

A normal alarm makes you pick "7:40". But what you actually want to decide is not a
time — it is *how sure you want to be that you won't be late*. JustInTime works
backwards from when your event starts:

```
alarm = event start − safety buffer (10 min) − τ-quantile of (prep + travel)
```

τ is the confidence you ask for — 0.90 for a class, 0.99 for an exam or a train. The
app **shows the reasoning behind the number**: what Kakao estimated (a route of
20 min, 4.6 km, one transfer), what is fixed (the buffer), and what comes from
observations.

| | |
| --- | --- |
| Version | **0.11.0** — source `APP_VERSION` and app `versionName`; deployment status is tracked separately |
| Shared server | `https://justintime-api.onrender.com` ([`/api/health`](https://justintime-api.onrender.com/api/health)) |
| Stack | Android · Kotlin · Jetpack Compose — Django · DRF · Neon Postgres — Render |

![Figma board](docs/figma-board.png)

> The Figma board as of 2026-09-30: 17 screens plus state variants (route map,
> progress colors, route modes, input states). Columns are stages; screens below a
> frame are its children. The board is ahead of the app in places — the lists below
> describe the app.

---

## Features

What the MVP supports:

1. **Sign up and onboard** — server-checked password, terms consent, home address, prep time (5–240 minutes); sign-up allows 10 attempts per IP per hour
2. **Add an event** — title, time, category (which sets τ), destination (Kakao place
   search, a pick on the map, or your current home), origin (home by default), and a route chosen from
   grouped Kakao candidates: transit, walk, bicycle or car, with server-written guidance
3. **See the alarm and why** — the server computes it; the decision screen breaks out
   prep, travel and buffer, each labeled as estimated, observed or fixed, with a progress bar and
   a route map. A faster alternative from the origin is drawn on the map
4. **Wake up reliably** — `setAlarmClock` (survives Doze, shows the system next-alarm
   icon), full screen over the lock screen, re-registered after reboot, app update and
   clock changes, 5-minute snooze. Home switches save to the server; only enabled alarms
   for the next 7 days are registered
5. **Log the morning** — dismissing the alarm starts one-tap-per-block prep logging and
   GPS trip tracking
6. **Commute** — departure and arrival are detected on the device (arrival = the first
   valid location within 100 m of the destination). While you are moving, the app fetches the
   fastest route from your current position every minute and draws it in purple; the
   progress bar shows a live ETA and colors the lateness outlook
7. **Choose risk** — server-modeled safe/normal/gamble choices update the alarm and device schedule. Missing uncertainty has an explicit empty state
8. **Learn** — departures, arrivals and prep blocks are uploaded, queued on disk while
   offline. Prep observations feed the next calculation directly
9. **Review** — a weekly report with a short written summary, a per-day detail (plan
   vs actual, cause of lateness) and calibration: did you arrive as often as τ promised?

Also: Seoul subway and bus real-time arrivals on the route (express, last train,
congestion), device calendar import, an offline cache and background sync (plans every
6 hours, routes every 15 minutes for events within 3 hours).

### Why the probability is blank

An on-time probability needs a **distribution** of travel times. Kakao returns a single
`totalTime` with no variance, so the spread comes from observed trips — route
corrections learned from departures and arrivals across users. Until that evidence exists,
the home screen hides the probability and the detail screen explains why it cannot yet be calculated. Filling in an arbitrary 90% would
make the screen look finished while being untrue.

---

## Known limitations and todos

**Partial**

- On-time probability *value* — the pipeline is complete; the value waits for travel
  variance evidence (above)
- Replanning while moving ⑦ — colors, ETA and the live route; no checkpoints or causes
- Route choice — grouped candidates, fastest default and guidance work. Four car variants (BE-8), arrival-time/operating-hour routing (BE-11) and optional bicycle-road ratio (BE-14) remain
- Weekly and detailed reports ⑧⑨ — built; the Figma panels for sleep, alarm snoozes and
  per-weekday auto-adjustment are left out because nothing records that data yet
- Sign-in — email and password only; the social buttons show a notice
- Route learning — corrections update only when someone runs
  `python manage.py train_models`; there is no scheduler on Render's free plan

**Not started**

- Replanning before departure ⑥ and a "switch to this route" action
- Group rooms ⑩⑪
- Editing a saved event — schedule changes via `PATCH /api/events/{id}` recompute, but the
  app has no edit screen
- Arbitrary τ entry in the app (risk presets already work), natural-language input, weather adjustment, push (FCM), bedtime suggestions
- Recurring events (BE-20), user-defined tags (BE-21) and sleep-time calculation (BE-22)
- Encrypted token storage — tokens sit in plain SharedPreferences; required before a
  real release
- Battery-optimization guidance

**Runtime limitations** (route scope and previously identified recovery/map issues)

1. Live/alternative routes compare within the selected mode. The candidate picker separately uses `fastest_key` across its available candidates
2. Arrival restoration after app process restart is not implemented
3. When the tracking service restarts, the moving state and the live route are lost
4. Map panning can jump twice as far during consecutive gestures while a response is slow
5. A small pinch (12%) snaps a whole zoom level

---

## Getting started

### Run the app

No backend setup is needed to try the app. The default build talks to the shared server
(`jitApiBaseUrl` in [`APP/gradle.properties`](APP/gradle.properties)). A local
`devServerHost` override takes precedence; remove that line and rebuild to use the shared server.
See the [app run guide](APP/README.md) and [MVP demo checklist](docs/demo-checklist.md).

- Android Studio with Android SDK 37 (the project uses AGP 9.3 and Gradle 9.5; Gradle
  downloads the JDKs it needs)
- An emulator or device running **Android 14 (API 34) or newer**

```bash
cd APP
./gradlew assembleDebug        # Windows: .\gradlew.bat assembleDebug
./gradlew installDebug
```

The build log prints `[JustInTime] API=...` — the server that build talks to. If Gradle
cannot find a JDK, point `JAVA_HOME` at the JBR bundled with Android Studio.

Sign in with the demo account `demo@demo.com` / `demo1234`, or create one.

The shared server runs on Render's free plan and sleeps after 15 minutes without
traffic. The first request then takes 30–60 seconds; the app wakes the server with a
health check and says so on screen.

**Emulator setup** — skip these and you will mistake them for app bugs.

```bash
# Time zone. The default is GMT, which puts every displayed time 9 hours off.
adb shell cmd alarm set-timezone Asia/Seoul

# Soft keyboard. In hardware-keyboard mode no on-screen keyboard appears and the
# host keyboard sends raw ASCII, so Hangul never gets composed.
adb shell settings put secure show_ime_with_hard_keyboard 1
```

For Korean input, add **Settings → Languages → Add a language → 한국어(대한민국)**.

**Physical devices** — see [docs/device-setup.md](docs/device-setup.md) for USB and
wireless debugging, permissions and battery settings. Test devices against the shared
server; the guide also covers optional local-server development.
[`APP/scripts/`](APP/scripts/README.md) has PowerShell helpers for installing and
checking a device.

### Work on the backend

```bash
cd backend
python -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -r requirements/dev.txt
test -f .env || cp .env.example .env  # Create the template only when .env does not exist
python -m pytest                   # in-memory SQLite, no keys needed
```

For platform instructions and `.env` setup, see the [backend run guide](backend/README.md).
Unit tests need nothing else. Running the server needs `DATABASE_URL` and the API keys
in `.env` ([Configuration](#configuration)); the values come from the team and are never
committed. The dev settings refuse to start without `DATABASE_URL`.

> **`DATABASE_URL` is the team's shared Neon database** — the one the deployed server
> uses. `runserver`, `migrate` and scripts run locally write team data. Run
> `python scripts/backup_db.py` before anything destructive.

To try server changes in the app before deploying, run the server locally and point an
emulator build at it with a git-ignored `APP/local.properties` line. Remove the line and
rebuild to go back to the shared server.

```bash
python manage.py runserver 0.0.0.0:8000
```

```properties
devServerHost=10.0.2.2
```

---

## Architecture

App — one direction only, `ui → ViewModel → repository → remote/local`:

```
ui/                        Compose screens
  ↑ StateFlow   ↓ actions
HomeViewModel · AuthViewModel
  ↓
data/repository            → data/remote (Retrofit) · data/local (Room cache, SharedPreferences)
domain/model               pure calculations and view models, covered by JVM tests
alarm/ sensing/ background/   receivers, the trip-tracking foreground service, WorkManager workers
```

Server — the app talks to it only over HTTPS with JWT:

```
accounts · events · routines · observations · reports    views and serializers
  ↓
planning     alarm calculation (compute_and_store)
  ↓
routing      Kakao routes, places, static maps · Seoul subway and bus real-time
prediction   learning from observations (manage.py train_models)
  ↓
Neon Postgres
```

Creating or changing an event runs the calculation on the server: resolve the route
with Kakao, estimate the prep and travel distributions, take the τ-quantile and store
the result as an `AlarmPlan` returned with the event. The app caches responses in Room,
so screens look the same online and offline.

**The app never calls Kakao or the real-time APIs directly.** An API key shipped in an
APK can be extracted, so the server proxies those calls, throttles them and keeps a daily
budget for static maps.

### Project structure

```
├── APP/                          Android app (Gradle root)
│   ├── app/src/main/java/com/swpp/wakeup/
│   │   ├── alarm/                scheduling, full-screen alarm, re-registration
│   │   ├── background/           workers — observation upload, plan sync, route refresh
│   │   ├── calendar/             device calendar reader
│   │   ├── data/                 remote (Retrofit) · local (Room, prefs) · repository
│   │   ├── domain/model/         pure calculations and view models
│   │   ├── sensing/              trip tracking, departure/arrival detection, upload queues
│   │   └── ui/                   alarm · auth · calendar · common · events · home · morning
│   │                             · nav · onboarding · places · report · routines · settings · theme
│   ├── gradle.properties         jitApiBaseUrl — the shared server
│   └── scripts/                  PowerShell helpers for physical devices
├── backend/                      Django + DRF
│   ├── apps/
│   │   ├── accounts/             User · Profile (home, prep time, default τ)
│   │   ├── events/               Place · EventTag · Event · calendar import · place and route views
│   │   ├── planning/             AlarmPlan · distributions · estimators · compute_and_store
│   │   ├── routing/              Kakao client · real-time arrivals · RouteCorrection
│   │   ├── routines/             prep blocks, per-event selection, block observations
│   │   ├── observations/         detected departures and arrivals
│   │   ├── prediction/           learning from observations
│   │   ├── reports/              weekly report, calibration
│   │   └── common/               error format, health
│   ├── config/settings/          base · dev · prod · test · local_suite
│   ├── scripts/                  HTTP checks and DB tools
│   └── Dockerfile                migrate, then gunicorn
├── docs/                         setup guides, design notes, the board image
├── .github/workflows/ci.yml      CI
└── render.yaml                   Render Blueprint
```

---

## Verification

Latest local integration evidence (2026-10-17, `back-iter2` feature baseline `d434097`): **1,182 backend tests, 611 Android tests, 8 HTTP suites with zero failures (34 external-key skips), lint zero errors, emulator E2E 386 seconds**. This is recorded/fixed upstream routing replay, not a claim about the current public deployment. See the [current checklist](docs/demo-checklist.md), [TODO](task.md) and [direct tests](docs/iter2/account-ranges-and-travel-counts.md).


Four suites run locally and in CI on every push to `main` and every pull request
([`ci.yml`](.github/workflows/ci.yml)). CI uses no secrets. Local feature checks can use `:app:testE2eUnitTest` and `:app:lintE2e` with `-PjitE2e=true`; see [QA setup](qa/demo/README.md).

```bash
cd backend && python -m pytest                      # backend unit tests
cd backend && python scripts/run_local_suite.py     # 8 HTTP suites against a live local server
cd APP     && ./gradlew testDebugUnitTest lintDebug assembleDebug   # Android unit tests + lint + build
cd APP     && ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=com.swpp.wakeup.integration
                                                    # Android integration tests on a running emulator (API 34+)
```

The integration tests (`app/src/androidTest/.../integration`) run screens, the Room
cache and the repository together on a real Android runtime, with fake APIs and no
server. They log the debug app out on the emulator they run on. The package filter
leaves out the end-to-end tests in the same folder, which need the QA build and the
replay server and run through `qa/demo/run.py`.

CI also measures line coverage and prints a per-layer table in the job summary; the
HTML reports are uploaded as the `backend-coverage` and `app-coverage` artifacts.
To get the same numbers locally:

```bash
cd backend && python -m pytest --cov --cov-report=html --cov-report=json && python scripts/coverage_summary.py
cd APP     && ./gradlew jacocoDebugUnitTestReport && python3 scripts/coverage_summary.py
```

Excluded from coverage: tests, migrations and seed commands on the server
([`.coveragerc`](backend/.coveragerc)); generated code (R, BuildConfig, Room `*_Impl`,
`ComposableSingletons`), `ui/theme` and the app entry points in the app
(`coverageExcludes` in [`app/build.gradle.kts`](APP/app/build.gradle.kts)).

The middle one is the unusual part. `scripts/check_*.py` drive a **running** Django
server over HTTP, which catches what the Django test client does not: a URL that was
never wired up, a serializer field the app reads under a different name, a missing
permission class. `run_local_suite.py` starts the server, stops it afterwards, and
refuses to run if port 8000 is already taken — a stale server answering health checks
once made a whole suite pass against old code.

It also pins the database to a **throwaway SQLite file in the temp directory**, never
the team's Neon database; the scripts create close to a hundred accounts per run. Its dedicated `local_suite` settings disable only the sign-up throttle; separate tests and the emulator use the real 10/hour limit.
Without `KAKAO_REST_API_KEY` the checks that need route lookups are **skipped rather
than failed**, so CI never burns the daily free quota. Lint blocks errors only.

For the deployed server, `python scripts/check_deployed.py` runs HTTP checks (it covers
the 0.3.0 features and whether `routes/live` is deployed; it is being extended), and
`/api/health` reports the running version.

---

## API

Everything is under `/api/`. Requests carry `Authorization: Bearer <access>` except
health, password check, sign-up, token and refresh.

| Area | Endpoints |
| --- | --- |
| Health | `GET health` — version and real-time availability |
| Auth | `POST auth/password/check` · `POST auth/register` · `POST auth/token` · `POST auth/token/refresh` · `GET auth/me` |
| Profile | `GET PATCH PUT profile` — home, prep time, default τ. A new home or prep time recomputes up to 50 future events |
| Events | `GET POST events` · `GET PATCH DELETE events/{id}` · `GET events/{id}/risk-options` · `POST events/{id}/recompute` · `POST events/import` · `GET events/tags` · `GET PUT events/{id}/blocks` |
| Places | `GET places/search` · `GET places/staticmap` · `GET places/reverse` |
| Routes | `GET routes/candidates` · `POST routes/live` (from the current position, while moving) |
| Observations | `POST observations/batch` · `GET observations` · `POST alarm-logs/batch` |
| Routines | `GET POST routines/blocks` · `GET PATCH DELETE routines/blocks/{id}` · `POST routines/observations/batch` |
| Reports | `GET reports/weekly` · `GET reports/calibration` |

- Errors look like `{"error": {"code", "message", "details"}}`. Another user's id
  returns **404, not 403**, so existence does not leak
- Sign-up requires `terms_agreed: true` and is limited to 10 attempts/IP/hour (429 with `Retry-After`). Profile prep time accepts 5–240 minutes or null.
- `destination_is_home:true` resolves the current profile home without a Place row. Risk distributions use modeled `bins.mass`, not observation counts; `travel_sample_count` is persisted correction provenance.
- Alarm enablement and password validation
  contracts are documented in [backend setup](backend/README.md#프론트-연결-계약-be-16).
- Batch uploads are idempotent on `(user, client_uuid)`. Detection happens mid-commute
  where there is often no network, so the app queues records and resends them; a retry
  must not create a second row
- `places/search` reports `reachable_count` rather than Kakao's `total_count`: Kakao
  answers "카페" with 142,759 matches but serves only 45, and the larger number next to a
  list that ends at 45 reads as a bug
- Place search prioritizes complete road/lot addresses, then falls back to place names.
  The response shape is unchanged; address results have a null `kakao_place_id`.
- `places/staticmap` returns a PNG cached for an hour, within a daily budget (900 by
  default) of the 1,000 free Kakao requests. Failures are not cached

---

## Deployment

| | |
| --- | --- |
| Service | Render `justintime-api` — Docker, free plan, Singapore, deploys `main` |
| Start | `backend/Dockerfile` runs `migrate`, then gunicorn with 2 workers |
| Database | Neon Postgres, **direct** URL (the `-pooler` endpoint can break migrations) |
| Expected source health | `GET /api/health` → `{"ok": true, "version": "0.11.0", "realtime": {"subway": true, "bus": true}}`, independent of the database |
| Idle | sleeps after 15 minutes; waking takes 30–60 seconds |

Bump the server `APP_VERSION` and the app `versionName` / `versionCode` together in the
change you deploy — the app adds a "server is behind" hint to its errors when the server
reports an older version. After deploying, check that `/api/health` shows the new
version: a 200 alone does not mean the new code is live. `render.yaml` deliberately has
no `databases` block — with one, Render attached its own Postgres and the demo login
broke while health stayed 200.

---

## Configuration

Local values go in `backend/.env`, copied from `.env.example`. **`.env` is never
committed.** The deployed values live in the Render dashboard, and CI needs none.

| Variable | Used for |
| --- | --- |
| `DATABASE_URL` | Neon Postgres, direct URL. Required |
| `DJANGO_SECRET_KEY` | Django secret key |
| `KAKAO_REST_API_KEY` | [Kakao](https://developers.kakao.com) routes (transit, walk, bicycle, car), place search, reverse geocoding, static maps |
| `SEOUL_SUBWAY_API_KEY` | Subway real-time arrivals ([data.seoul.go.kr](https://data.seoul.go.kr)) |
| `SEOUL_BUS_API_KEY_ENCODING` / `_DECODING` | Bus real-time arrivals ([data.go.kr](https://www.data.go.kr)). Set both forms of the key |
| `STATIC_MAP_DAILY_UPSTREAM_LIMIT` | Daily budget for Kakao static-map requests, default 900 |
| `OPENAI_API_KEY` / `OPENAI_MODEL` / `OPENAI_BASE_URL` | Weekly report summary (optional; without them a fixed template is used). Any OpenAI-compatible chat-completions endpoint; an empty base URL means OpenAI. Check with `python manage.py probe_report_summary` |

Without the real-time keys the arrival lines simply do not appear; routing still works.
`KMA_API_KEY`, `FCM_CREDENTIALS_PATH`, `KAKAO_MOBILITY_KEY` and `REDIS_URL` are reserved
for planned features; nothing uses them yet.

Kakao Map needs no review — switch it on under `My Application > Product Settings >
Kakao Map`. **The free quota applies to one app per developer account, so the team
shares a single app and key.**

---

## Documentation

- [APP/README.md](APP/README.md) — running the app in Android Studio, macOS and Windows (Korean)
- [backend/README.md](backend/README.md) — local backend setup and isolated verification (Korean)
- [docs/demo-checklist.md](docs/demo-checklist.md) — MVP rehearsal, pass criteria and verified scope (Korean)
- [docs/team-setup.md](docs/team-setup.md) — shared server and database, deployment,
  verification scripts (Korean)
- [docs/device-setup.md](docs/device-setup.md) — running on a physical device
- [docs/progress-tracking.md](docs/progress-tracking.md) — progress bar, lateness
  outlook, ETA and route refresh design (Korean)
- [APP/scripts/README.md](APP/scripts/README.md) — device scripts

Specifications, checklists and course documents are kept out of the repository and
shared through the course **Wiki**.

---

## Team

SNU SWPP 2026 Fall · Team 18 · Momentum

Each iteration has three roles — **UI** (Figma, screens, research), **BE** (backend,
unit tests, QA) and **PM** (schedule, decisions, integration, deliverables). The PM
rotates every iteration.
