# JustInTime

**An alarm you set by arrival confidence, not by clock time.**

A normal alarm makes you pick "7:40". But what you actually want to decide is not a
time — it is *how sure you want to be that you won't be late*. JustInTime works
backwards from when your event starts, subtracting `prep time + travel time + safety
buffer`, and **shows you the reasoning behind the number**.

![Screens](docs/screens-overview.png)

> All 13 Figma screens. Columns are stages; screens below a frame are its children.

---

## Features

| | Feature | Status |
| --- | --- | --- |
| ✅ | **Backward alarm calculation** — event start − buffer − travel − prep | Done |
| ✅ | **Real travel times from Kakao** — actual route lookup from home to destination | Done |
| ✅ | **Route selection** — pick from the candidates Kakao returns, e.g. 23 min with one transfer vs. 27 min with none | Done |
| ✅ | **Visible reasoning** — prep / travel / buffer broken out, with each value labeled as measured or fixed | Done |
| ✅ | **Per-category safety margin (τ)** — exams, presentations and trains wake you earlier | Done |
| 🚧 | **On-time probability** — computed from a distribution once observations exist. Currently shown as "learning" | Planned |
| 🚧 | **Exact alarm delivery** — full-screen intent over the lock screen, Doze handling | Planned |
| 🚧 | **Replanning** — alternatives when you're already running late | Planned |
| 🚧 | **Group rooms** — see everyone's expected arrival together | Planned |

### Why the probability is blank

Showing an on-time probability requires a **distribution of travel times**. Kakao's
routing response has no variance information — just a single `totalTime` point
estimate — and there are no user observations yet.

So the probability field is left empty and the UI says "learning" instead. Filling in
an arbitrary 90% would make the screen look finished while being untrue. Once
observations accumulate, the value is computed from quantiles.

---

## Tech Stack

| Layer | Choice |
| --- | --- |
| App | Native Android · Kotlin · **Jetpack Compose** · Material3 |
| State | `ViewModel` + `StateFlow` + Coroutines |
| Networking | Retrofit + Gson + OkHttp |
| Server | **Django + Django REST Framework** |
| Database | SQLite (swappable to Postgres) |
| Auth | SimpleJWT (email + password) |
| External APIs | Kakao Map (routing, place search), OpenAI, KMA weather, FCM |
| Design | Figma |

We did not use a cross-platform framework (Flutter, React Native). The core of this
app is **exact alarm delivery** — `AlarmManager.setAlarmClock`, Doze exemptions,
full-screen intents, re-registration after reboot. Those need direct platform access,
and putting a plugin layer in between makes them harder to debug.

---

## Getting Started

The app does not work on its own. **Start the backend first.**

### Prerequisites

- Android Studio (2025.1 or newer) with Android SDK 37
- An emulator or device running **Android 14 (API 34) or newer**
- Python 3.12
- JDK — the JBR bundled with Android Studio is fine
- A Kakao REST API key ([how to get one](#api-keys))

### Installation

**1. Backend**

```powershell
cd backend

# first time only
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements\dev.txt
Copy-Item .env.example .env      # then fill in the keys
.\.venv\Scripts\python.exe manage.py migrate

# run — must bind 0.0.0.0 so the emulator can reach it
.\.venv\Scripts\python.exe manage.py runserver 0.0.0.0:8000
```

Binding to `127.0.0.1:8000` works from the host but **the emulator cannot connect**.

Demo account: `demo@demo.com` / `demo1234` (created only when `DEBUG=True`).
Admin site: `http://127.0.0.1:8000/admin/`.

**2. App**

```powershell
cd APP
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
```

From the emulator the host machine is `10.0.2.2` (see `BuildConfig.BASE_URL`).
For a physical device, change `BASE_URL` in `app/build.gradle.kts` to your machine's
LAN IP.

**3. Emulator setup**

Skip these and you will mistake them for app bugs.

```powershell
# Time zone. The default is GMT, which puts every displayed time 9 hours off.
adb shell cmd alarm set-timezone Asia/Seoul

# Soft keyboard. In hardware-keyboard mode no on-screen keyboard appears and the
# host keyboard sends raw ASCII, so Hangul never gets composed.
adb shell settings put secure show_ime_with_hard_keyboard 1
```

For Korean input, add the language under **Settings → Languages → Add a language →
한국어(대한민국)**. Gboard ships with English only, so Hangul cannot be typed until
you do.

### Using the app

```
Sign in → set home location → add an event → choose a route → see the alarm
```

The home location is the origin for every travel-time lookup. Without it the alarm
cannot be computed; the banner on the home screen takes you straight to the setting.

---

## Project Structure

```
├── APP/                        Android (Kotlin + Compose)
│   └── app/src/main/java/com/swpp/wakeup/
│       ├── data/               API clients, local storage, repositories
│       ├── domain/model/       view-facing models
│       └── ui/                 auth · home · events · alarm · common · nav · theme
├── backend/                    Django + DRF
│   ├── apps/
│   │   ├── accounts/           User · Profile
│   │   ├── events/             Place · EventTag · Event
│   │   ├── planning/           AlarmPlan (alarm calculation)
│   │   ├── routing/            Kakao clients
│   │   └── common/             shared error format
│   └── scripts/                API verification scripts
└── docs/                       screenshots
```

Design documents (specifications, checklists) live in the **Wiki**.

---

## API

```
POST  /api/auth/register              sign up
POST  /api/auth/token                 sign in (access + refresh)
POST  /api/auth/token/refresh         refresh
GET   /api/auth/me                    validate the stored token

GET   PATCH  /api/profile             home location, prep time, default τ

GET   POST   /api/events              list, create
GET   PATCH  DELETE  /api/events/{id}
POST  /api/events/{id}/recompute      recalculate the alarm only
GET   /api/events/tags                the six event categories

GET   /api/places/search?q=           Kakao place search (proxied)
GET   /api/routes/candidates          route candidates
```

**The app never calls Kakao directly.** An API key shipped in an APK can be extracted,
so the server proxies those calls.

### Verification

Every script makes real HTTP calls. Run them with the server up.

```powershell
cd backend
.\.venv\Scripts\python.exe scripts\check_auth_api.py      # auth, 18 cases
.\.venv\Scripts\python.exe scripts\check_events_api.py    # events, 20 cases
.\.venv\Scripts\python.exe scripts\check_route_api.py     # routes, 30 cases
.\.venv\Scripts\python.exe scripts\db_status.py           # database summary
```

---

## API Keys

Put them in `backend/.env`, copied from `.env.example`.
**`.env` is never committed** (see `.gitignore`).

| Variable | Where to get it | Notes |
| --- | --- | --- |
| `KAKAO_REST_API_KEY` | [developers.kakao.com](https://developers.kakao.com) | One key covers transit, walk, bicycle, car and place search |
| `OPENAI_API_KEY` | [platform.openai.com](https://platform.openai.com) | Natural-language event parsing (planned) |
| `KMA_API_KEY` | [KMA API Hub](https://apihub.kma.go.kr) | Short-term forecast; requires an access request |
| `FCM_CREDENTIALS_PATH` | Firebase console | A **path** to the JSON. Keep the file outside the repository |

Kakao Map needs no review process — just switch it on under
`My Application > Product Settings > Kakao Map`. **The free quota applies to only one
app per developer account, so the team should create a single app and share the key.**

---

## Team

SNU SWPP 2026 Fall · Team 18
