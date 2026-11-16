# JustInTime — Iteration 4 Demo

**An alarm you set by arrival confidence, not by clock time.**

You pick how sure you want to be of arriving on time — τ, e.g. 0.90 for a class, 0.99
for an exam — and the app works backwards from the event:

```
alarm = event start − safety buffer − τ-quantile of (prep + travel)
```

Iteration 4 is **hardening and the user acceptance test**. The heuristic evaluation of 11-04 said
the app was hard to read, talked like a developer, gave no help and gave no control over the
alarm. This iteration rebuilt the app's structure around four tabs, added a first-run guide and
help, replaced the clock dials, opened the alarm time to the user, and built the server side for
user-set alarms, confirmation and change proposals, login lock, recurring-event scope, learning
reset and in-app inquiries.

| | |
| --- | --- |
| Iteration | 4 · 2026-11-07 – 2026-11-20 (Hardening and UAT) |
| Snapshot | `front-iter4` at [`a52d9b2`](https://github.com/snuhcs-course/swpp-2026-project-team-18/commit/a52d9b2) (2026-11-16): the app of `main` [`d296ce4`](https://github.com/snuhcs-course/swpp-2026-project-team-18/commit/d296ce4) plus the server of `back-iter4` [`86a1293`](https://github.com/snuhcs-course/swpp-2026-project-team-18/commit/86a1293). This branch adds only this README |
| Version | Server **0.21.0** (`APP_VERSION`) — **deployed on 2026-11-16**: `back-iter4` was merged into `main` ([`a175b4d`](https://github.com/snuhcs-course/swpp-2026-project-team-18/commit/a175b4d), the same code as this snapshot) and the shared server answers 0.21.0 · app `versionName` 0.14.0 (not bumped this iteration) |
| Server | `https://justintime-api.onrender.com` ([`/api/health`](https://justintime-api.onrender.com/api/health)) |
| Demo video | Not recorded yet — the planned scenes are listed [below](#demo-video) |
| UAT | Sheet submitted 11-13; sessions in class on 11-16 and 11-18. Results go to the Wiki's [Testing Documentation, Iteration 4](https://github.com/snuhcs-course/swpp-2026-project-team-18/wiki/team18%E2%80%90iter4%E2%80%90testing) |

Everything needed to run the demo is on this page. The full project README (architecture, API,
deployment, configuration) is on
[`main`](https://github.com/snuhcs-course/swpp-2026-project-team-18/blob/main/README.md); the
earlier demos are on
[`iteration-1-demo`](https://github.com/snuhcs-course/swpp-2026-project-team-18/tree/iteration-1-demo),
[`iteration-2-demo`](https://github.com/snuhcs-course/swpp-2026-project-team-18/tree/iteration-2-demo)
and [`iteration-3-demo`](https://github.com/snuhcs-course/swpp-2026-project-team-18/tree/iteration-3-demo).

**Contents:** [Demo video](#demo-video) · [What the demo shows](#what-the-demo-shows) ·
[Major progress in Iteration 4](#major-progress-in-iteration-4) ·
[Heuristic evaluation: issue → change → result](#heuristic-evaluation-issue--change--result) ·
[Tech stack and environment](#tech-stack-and-environment) · [How to run](#how-to-run) ·
[Known limitations and todos](#known-limitations-and-todos) · [Verification](#verification)

---

## Demo video

**Not recorded yet.** The video is recorded on a physical phone before the iteration closes and
goes into `demo-video/` on this branch, like
[`iteration-3-demo`](https://github.com/snuhcs-course/swpp-2026-project-team-18/tree/iteration-3-demo#demo-video).
Planned scenes, in the order the heuristic evaluation ranked the problems:

| Scene | What it should show | Runs on |
| --- | --- | --- |
| 1 · First run | Sign-up with the nickname rule shown under the field, the prep-time screen with the wheel, the three intro pages, then the coachmark over the new Home | Shared server |
| 2 · Home and tabs | The next-alarm card (time, on-time probability chip, "1시간 28분 뒤 · 9:00 일정"), today and tomorrow listed in full, then the 이동 (trip), 리포트 (report) and 내 정보 (me) tabs | Shared server |
| 3 · Change the alarm | [시각 바꾸기] (change time) on the next-alarm card opens the sheet: the three presets (여유 있게 · 보통 · 빠듯하게), the wheel previewing the on-time probability, then saving a preset and the snackbar | Shared server |
| 4 · Help | ? → help with search, the "정시 도착 확률이 뭐예요?" (what is the on-time probability) sheet with ten circles, and the contact sheet | Shared server |
| 5 · Recurring event | Editing one occurrence of a weekly event, saving with "이후 일정 모두" (this and following) and changing the weekdays | Shared server |
| 6 · Weekly report | The report tab opening on this week: "이번 주 N번 중 M번 제시간에 도착했어요" (on time M of N this week) with weekday dots, then expected vs actual on-time and prep bars | Shared server, an account with past trips |

---

## What the demo shows

Iteration 1 built one flow end to end; Iteration 2 made it trustworthy; Iteration 3 let the user
correct the plan and made the learning personal. **Iteration 4 makes the app usable by people who
did not build it**: one main fact per screen, plain Korean, a guide on first run and help on
every tab, a time picker that doesn't fight the user, and a way to change the alarm.

### Features implemented (whole app, as of this snapshot)

**New in Iteration 4** is in bold. "Needs 0.21.0" marks what works only with server 0.21.0. The
shared server runs 0.21.0 since 2026-11-16; against an older server (for example a local
checkout from before that day) the app falls back as each row says.

| # | Feature | What to look for |
| --- | --- | --- |
| 1 | Navigate | **Four bottom tabs — 홈 (home) · 이동 (trip) · 리포트 (report) · 내 정보 (me) — each keeping its own back stack.** The alarm screen and sign-in stay outside the tabs |
| 2 | Sign up and sign in | Email with a server-checked password, Kakao and Google; forgot and change password; optional email verification. **Nickname rule under the field ("다른 사람에게 보이는 이름이에요 · 20자까지", live character count, the server's reason under the field and in a red card).** Login lock after 5 wrong passwords: server only (needs 0.21.0), the app still shows the server's message only |
| 3 | First run | Home address, **prep time on a redesigned screen (−/+ 5 min, quick chips, a wheel; no prefilled value)**, optional calendar link, **then three intro pages and a one-time coachmark on Home**. Whether the guide was seen is **stored on the account** (falls back to the phone on a 0.20.0 server) |
| 4 | Home | **Redrawn: today's date, one large next-alarm card (alarm time, on-time probability chip or a grey "정시 도착 확률 계산 전" (not computed yet) chip, time to go, place and title), then today and tomorrow in full; each row is start · title · place · alarm time with its switch; a floating [+ 일정 추가] (add event).** The weekly report, calendar and routine cards moved to the report and me tabs |
| 5 | Add and edit an event | Title, date, **time on a scrolling wheel (1-minute steps) instead of the clock dial**, event type (**rename and delete your own types**), destination, origin, weekly repeat, route. **Recurring events: [저장] asks "이 일정만 / 이후 일정 모두" (this one / this and following) and weekdays can change for "following" — needs 0.21.0; on 0.20.0 only that occurrence changes and its weekdays can't be edited, as before** |
| 6 | Change the alarm | **A bottom sheet from Home's [시각 바꾸기] and the decision screen: three presets 여유 있게 · 보통 · 빠듯하게 (safe .97 · normal .90 · tight .65) and a wheel in 5-minute steps that previews the on-time probability from the prep + travel distribution. Saving a time other than the presets, confirming the alarm and the "바꿀까요" (change it?) proposals are on the server (needs 0.21.0) but not connected in the app yet.** The old risk-choice screen ⑤ was removed |
| 7 | See the alarm and why | Prep, travel and safety buffer add up to start − alarm, each labeled; the route map and alternatives. **An i button opens "정시 도착 확률이 뭐예요?" (what is the on-time probability) — ten circles, "10번 중 N번" rounded down** |
| 8 | Trip tab | **Before leaving: the next trip (leave time, time left, arrival) with the on-time chip and a vertical "가는 길" (the way) timeline of the planned legs; empty states for no trip today, no events, and no route chosen.** While moving it links to the trip progress card of Iteration 3 (reroute when late, "도착했어요") |
| 9 | Wake up reliably | Full screen over the lock screen, 5-min snooze, re-registered after reboot and app update; only the first alarm of each day on by default |
| 10 | Learn | Nightly training at 23:17 KST: safety buffer, prep from GPS departures, walking speed and time-of-day factors, route correction; the next 7 days recomputed. **"기록 초기화" (reset my records) in me › account clears the learned data and keeps events, homes and routines — needs 0.21.0 (on 0.20.0 the app says the server doesn't support it yet)** |
| 11 | Report tab | **Opens on this week: "이번 주 N번 중 M번 제시간에 도착했어요" (on time M of N this week) with weekday dots, late days → the day detail ⑨, expected vs actual on-time probability with a verdict, expected vs actual prep time; ‹ › move between weeks.** **A Sunday 12:00 notification "이번 주 리포트가 나왔어요" (this week's report is ready) opens it; switch in me › 알림·권한** |
| 12 | Me tab | **Profile, then 내 아침 (my morning: homes, prep time, routine) · 일정 (calendar) · 도움 (help) · 계정 (account) rows with their current values; 알림·권한 (notifications and permissions) shows notifications, full-screen alarm and precise location, with what is off in orange and a shortcut to the setting** |
| 13 | Help | **Help screen with eight questions, search, and [처음 안내 다시 보기] (replay the intro); a ? on Home, trip and report; [문의하기] (contact us) sends a message with a reply email — needs 0.21.0** |

The app's text is Korean. Server texts (weekly summary, buffer reasons, plan status, errors) are
rewritten in the polite 해요체 on 0.21.0; the app shows them as they come.

---

## Major progress in Iteration 4

Compared with [`iteration-3-demo`](https://github.com/snuhcs-course/swpp-2026-project-team-18/tree/iteration-3-demo)
(`5dc51ef`, 2026-11-05): 48 commits without merges. Jinho Kim is the PM, Sooyeon Kim did the app
and Junha Park the server (roles rotate each iteration). The 15 commits of 11-06 – 11-07 are the
last Iteration 3 app fixes (merged into `main` on 11-07 as `86ba415`). The frontend's requests to
the backend are [`docs/iter4/*-api.md`](docs/iter4/); the backend's contracts are
`docs/iter4/server-*.md`.

**Usability (app, on `main`)**

- Four-tab navigation with one back stack per tab; Home, trip, report and me redrawn from the
  Figma section "피드백 적용" (HE redesign, 35 frames)
- First-run intro (three pages), Home coachmark, help with search, on-time probability sheet,
  contact sheet; the guide state kept on the account
- A scrolling time wheel and a duration wheel replace the Material clock dials; the prep-time
  screen redrawn
- The alarm-time sheet replaces the risk-choice screen; probability colors follow one rule
  everywhere (below 70% red before the target is checked)
- Nickname errors under the field; own event types renamed and deleted; recurring-event scope
  sheet; learning reset dialog; Sunday report notification

**Server (`back-iter4`, 0.21.0, merged into `main` and deployed on 11-16)**

| Request | What the server does | Contract |
| --- | --- | --- |
| BE-H3a/b/d/e | A user-set alarm time that survives recomputes (`PUT`/`DELETE events/{id}/alarm/time`), confirmation (`POST …/alarm/confirm`), "바꿀까요" change proposals with accept/reject and an `answer_by` deadline, the recommended preset in risk-options; the alarm to ring is `effective_alarm_at` | [`server-alarm-control.md`](docs/iter4/server-alarm-control.md) |
| BE-A2 | Edit or delete a recurring event for "this" or "this and following", with weekday changes | [`server-recurrence-scope.md`](docs/iter4/server-recurrence-scope.md) |
| BE-A3 | 5 wrong passwords lock email sign-in for 15 minutes (423 `account_locked`, `remaining_attempts`) | [`server-login-lock.md`](docs/iter4/server-login-lock.md) |
| BE-H4 | Intro and coachmark "seen" times on the profile | [`server-first-run-guide.md`](docs/iter4/server-first-run-guide.md) |
| BE-H4b | In-app inquiry: stored, mailed to the team after commit, 5 per hour | [`server-inquiry.md`](docs/iter4/server-inquiry.md) |
| BE-RESET | Reset the learned data in one transaction and recompute | [`server-learning-reset.md`](docs/iter4/server-learning-reset.md) |
| BE-H9, BE-R1 | Saved-place names may not be "집" or repeat; every server text in 해요체, LLM summaries that aren't are rejected | [`server-saved-place-labels-and-report-tone.md`](docs/iter4/server-saved-place-labels-and-report-tone.md) |
| (team) | Recent and most-used routes of the last 90 days, for a future quick-add (`GET routes/history`) | [`server-route-history.md`](docs/iter4/server-route-history.md) |

Each BE request above went through a review round on 11-15 before it was merged into `back-iter4`
(the "review fixes" commits for A2, A3, H3, H4, H4b, H9, R1 and RESET).

**Testing** (run locally on this snapshot on 2026-11-16, see [Verification](#verification))

- Backend tests 1,795 → **2,298** (96.0% line coverage), app JVM tests 950 → **1,221**, device
  tests 34 → **56** (55 pass, 1 fails), contract checks 241 passed · 34 skipped (no Kakao key) ·
  0 failed
- CI [run 94](https://github.com/snuhcs-course/swpp-2026-project-team-18/actions/runs/43795831229)
  on `main` `d296ce4` and [run 95](https://github.com/snuhcs-course/swpp-2026-project-team-18/actions/runs/43819062902)
  on `main` `a175b4d` (after the server merge): backend, contract and app unit jobs passed; **the
  device integration job failed** in both (see [Known limitations](#known-limitations-and-todos))

**PM work**

- The heuristic evaluation sheets organized by theme with the code location of each finding, and
  the Figma section "피드백 적용" with one redesigned frame per finding and 13 open decisions
- The frontend's request list to the backend and the backend's answer (what the app still has to
  connect), the UAT sheet (five stories, submitted 11-13), the Nov 9 meeting log

---

## Heuristic evaluation: issue → change → result

The full table, with the unresolved items and why, is in section 7 of the Wiki's
[Testing Documentation, Iteration 4](https://github.com/snuhcs-course/swpp-2026-project-team-18/wiki/team18%E2%80%90iter4%E2%80%90testing).
Severity is the highest rating any evaluator gave (0–4).

| Issue (severity) | Change | Result in this snapshot |
| --- | --- | --- |
| Too much on Home, nothing stands out (3) | Four tabs; Home shows one next-alarm card and a plain list | ✅ in the app |
| Developer wording on screen (3) | Server texts in 해요체 (0.21.0); app texts rewritten on the new screens | 🚧 server done; "~함 / ~음" texts remain in the app, also on parts of the new Home, the decision screen, calendar import and event add |
| The computed alarm can't be changed or approved (3) | Alarm-time sheet with presets and a wheel; server user time, confirmation and proposals | 🚧 presets save; custom time, confirmation and proposals wait for the app connection |
| No help or tutorial (2) | Intro, coachmark, help, i sheets, contact | ✅ in the app |
| The clock dial is hard to use (2) | Time and duration wheels | ✅ |
| Buttons look like text (2) | Four button levels in Figma | ❌ not in the app yet |
| Back only by the on-screen button (2) | Predictive back; fix the sign-up back gesture | ❌ not in the app yet |
| Adding an event in a hurry takes too long (2) | An add sheet with three required fields; the route history API | ❌ app not started; server API done |
| Choosing "집" by mistake; duplicate home names (1) | Undo after swapping to home; the server refuses duplicate names | 🚧 server done (0.21.0); app not started |
| Route colors unclear (1) | Line names in the bar, a legend and a color sheet | ❌ not in the app yet |
| Nickname error without a reason (1) | Rule and reason under the field | ✅ |

---

## Tech stack and environment

Unchanged from Iteration 3 — no new libraries in the app or the server.

| Part | Stack |
| --- | --- |
| App | Kotlin, Jetpack Compose (Material 3), Retrofit + OkHttp, Room, WorkManager, Google Play services Location, Kakao SDK and Credential Manager for sign-in, Android Keystore for tokens · minSdk 34, targetSdk 37 · AGP 9.3, Gradle 9.5 |
| Server | Python 3.12, Django 5.2, Django REST Framework, SimpleJWT, gunicorn · Docker on Render (free plan, Singapore). New on 0.21.0: the `support` app (inquiries) and the `SUPPORT_INBOX_EMAILS` setting in `render.yaml` |
| Database | Neon Postgres |
| Scheduled jobs | GitHub Actions (`train-models.yml`: buffer, prep and travel training, then the 7-day recompute, daily at 23:17 KST) |
| External APIs | Kakao (routes, place search, static maps, sign-in), Google sign-in, Seoul real-time subway and bus arrivals, an OpenAI-compatible LLM for the report summary, SMTP (Brevo) for password codes and inquiries — all called by the server only |

Development and test environment this iteration:

- **App:** Android Studio with its bundled JBR, Android SDK Platform 37
- **Server:** Python 3.12; the server features were reviewed and fixed on `back-iter4` before the
  merge into `front-iter4`
- **Local verification of this snapshot:** Windows 11, Python 3.12, Android Studio's JBR, an
  Android 36 emulator (`SWPP_APP`)
- **CI:** GitHub Actions on Ubuntu; app integration tests on an API 34 emulator

The design is in the Wiki's
[Design Documentation](https://github.com/snuhcs-course/swpp-2026-project-team-18/wiki/team18%E2%80%90iter1%E2%80%90design)
(v4.0 for Iteration 4) and the requirements in the
[Requirements Specification](https://github.com/snuhcs-course/swpp-2026-project-team-18/wiki/team18%E2%80%90iter1%E2%80%90reqspec).

---

## How to run

No backend setup is needed: every build talks to the shared server, which runs 0.21.0 since
2026-11-16. A local server ([step 8](#8-trying-the-iteration-4-server-features-locally)) is only
for trying the server features on throwaway data.

### Quick start

1. `git clone -b iteration-4-demo https://github.com/snuhcs-course/swpp-2026-project-team-18.git`
2. In Android Studio, open the **`APP`** folder and wait for Gradle Sync.
3. Start an emulator (Google Play image) or connect a phone, on Android 14 (API 34) or newer.
4. Press **Run ▶**, allow notifications and precise location, then **sign up with an email**.
   The intro and the Home coachmark appear once after sign-up.

The first request after 15 idle minutes wakes the server and takes 30–60 seconds. Kakao and
Google sign-in work only with an APK signed by a key registered in their consoles, so use email
sign-up on your own build.

### 1. Requirements

- **Android Studio**, a recent version. Its bundled JDK (JBR) is what Gradle uses
- **Android SDK Platform 37** (Android Studio offers it during the first sync; otherwise
  **Tools → SDK Manager**)
- An **emulator or phone with Android 14 (API 34) or newer**. Emulators need a **Google Play**
  or **Google APIs** system image. On Apple Silicon, use an ARM64 image
- Internet access

### 2. Get the code

```bash
git clone -b iteration-4-demo https://github.com/snuhcs-course/swpp-2026-project-team-18.git
cd swpp-2026-project-team-18
```

### 3. Build and install

**Android Studio (recommended)**

1. **File → Open** and choose the **`APP` folder** inside the repository, not the repository
   root. Wait for Gradle Sync to finish.
2. **Device Manager** → create a phone with an API 34+ Google Play image, and start it.
3. Select the device and press **Run ▶**. JustInTime opens on the login screen.
4. The Build output should show `[JustInTime] API=https://justintime-api.onrender.com/`. If it
   shows another address, delete the `devServerHost=` line from `APP/local.properties`.

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

With more than one device, add `-s <device-id>` to `adb` (`adb devices -l` lists them). The APK
is written to `APP/app/build/outputs/apk/debug/app-debug.apk`. If an older JustInTime signed on
another computer is installed, uninstall it first; events and settings come back after sign-in.

### 4. Set up the emulator once

```bash
# Time zone. The default (GMT) puts every time 9 hours off
adb shell cmd alarm set-timezone Asia/Seoul

# Show the on-screen keyboard, needed to type Korean place names
adb shell settings put secure show_ime_with_hard_keyboard 1
```

Then add a Korean keyboard in the emulator (Gboard → Languages → Add keyboard → 한국어).

### 5. Permissions

Allow **notifications** and **precise location** when asked ("while using the app" is enough).
On Android 14 and newer, also allow **full-screen notifications** for the alarm to open over the
lock screen; me › 알림·권한 shows all three and turns the missing one orange. From a terminal:

```bash
adb shell pm grant com.swpp.wakeup android.permission.POST_NOTIFICATIONS
adb shell pm grant com.swpp.wakeup android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.swpp.wakeup android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.swpp.wakeup android.permission.READ_CALENDAR
adb shell appops set com.swpp.wakeup USE_FULL_SCREEN_INTENT allow
```

### 6. On a phone instead of an emulator

- **USB**: Settings → About phone → tap *Build number* 7 times, turn on *USB debugging*, connect
  and accept the prompt.
- **Wireless** (Android 11+): same network, Developer options → *Wireless debugging* → *Pair
  device with pairing code*, `adb pair <IP>:<pairing-port>`, then `adb connect <IP>:<port>`.
  Campus Wi-Fi often blocks this; use USB instead.

More on phones, including battery settings: [docs/device-setup.md](docs/device-setup.md).

### 7. Trying the Iteration 4 features (shared server)

- **Tabs**: the bar at the bottom; each tab keeps its own screen when you switch.
- **First run again**: me › 도움 › "처음 안내 다시 보기" replays the intro and the coachmark.
- **Change the alarm**: [시각 바꾸기] on Home's next-alarm card, or "지각 위험 바꾸기" on the
  decision screen. Pick a preset and save; turn the wheel to see the probability change. A wheel
  time that matches no preset can't be saved yet (needs the app connection to 0.21.0).
- **On-time probability**: tap the probability chip on Home or the i on the decision screen. A
  new account shows "정시 도착 확률 계산 전" until prep and travel records have a spread.
- **Time wheel**: add an event and tap the start time; prep time in me › 준비 시간.
- **Help and contact**: ? on Home, trip or report. [문의하기] sends the text with a reply email
  (at most 5 an hour). A server older than 0.21.0 answers "아직 앱에서 문의를 받을 수 없어요"
  (inquiries are not accepted yet) and the app keeps the text.
- **Recurring event**: open one occurrence of a weekly event and save; the sheet asks "이 일정만 /
  이후 일정 모두" (this one / this and following), and weekdays can change for "following".
- **Report**: the report tab opens on this week; ‹ goes back a week.
- **Sunday notification**: me › 알림·권한 › "주간 리포트 알림"; it fires on Sundays at 12:00
  (device time) and is skipped after 18:00.
- **Getting the alarm in a few minutes**: set the start to about *now + prep + travel + buffer
  (5 min for a new account) + 3–5 min*. Only the first alarm of the day rings by default.
- **Moving the emulator**: ⋯ → Location; a Single point at home before dismissing the alarm, then
  a Route to the destination at 1×, or `adb emu geo fix <longitude> <latitude>`.

### 8. Trying the Iteration 4 server features locally

Optional: the shared server already runs 0.21.0. Run the server locally only to try
recurring-event scope, inquiries, learning reset or the 해요체 texts on data you can throw away.
Use a **throwaway SQLite database** — `backend/.env` normally points at the team's shared Neon
database, and running migrations against it from a local checkout can break the shared server.

```bash
cd backend
python3.12 -m venv .venv
.venv/bin/python -m pip install -r requirements/dev.txt     # Windows: .venv\Scripts\python.exe
export DATABASE_URL="sqlite:///$(pwd)/demo-iter4.sqlite3"     # PowerShell: $env:DATABASE_URL = "sqlite:///$((Get-Location) -replace '\\','/')/demo-iter4.sqlite3"
export KAKAO_REST_API_KEY="<from the team>"                  # needed for route lookups
.venv/bin/python manage.py migrate
.venv/bin/python manage.py runserver 0.0.0.0:8000
curl http://127.0.0.1:8000/api/health                        # "version": "0.21.0"
```

Then put `devServerHost=10.0.2.2` in `APP/local.properties` (emulator; a phone on the same network
uses the computer's LAN address), build again, and sign up on the local server. Remove the line
afterwards to go back to the shared server. Without a Kakao key the server starts, but adding an
event can't look up a route.

### 9. Troubleshooting

| Problem | Check, in this order |
| --- | --- |
| Can't sign in | Server address (build output) → [`/api/health`](https://justintime-api.onrender.com/api/health) → the message on screen. After idle, the first request takes up to a minute |
| Kakao or Google sign-in fails | The APK's signing key is not registered; use email sign-up |
| "아직 서버에서 지원하지 않아요" (not supported by the server yet) | The server is older than 0.21.0: check [`/api/health`](https://justintime-api.onrender.com/api/health), or `devServerHost` in `APP/local.properties` points at an older local server |
| The "이후 일정 모두" option doesn't appear | The server reports a version below 0.21.0 (`/api/health`) |
| Times are 9 hours off | The emulator time zone (step 4) |
| Alarm doesn't ring or doesn't open full screen | The alarm is in the future and registered (me › 알림·권한, or `adb shell dumpsys alarm` and look for `com.swpp.wakeup`) → time zone → notification and full-screen permissions → volume |
| No trip recorded | Tracking starts only when you dismiss the alarm → precise location → enough GPS movement (step 7) |
| Can't type Korean | On-screen keyboard setting and a Korean keyboard (step 4) |
| Gradle Sync or build fails | SDK Platform 37 installed → Gradle JDK set to Android Studio's JBR |

```bash
adb logcat -s AlarmScheduler AlarmReceiver TripTrackingService TripObservationQueue
```

Logs contain locations; don't share them.

---

## Known limitations and todos

**Deployed during the UAT week.** `back-iter4` (server 0.21.0) was merged into `main` (`a175b4d`)
on 11-16 at 15:46 KST, the day of the first UAT session, and Render served 0.21.0 when checked that
evening. The
plan had been 11-19, after the second session, so the two sessions may have run against
different server versions; the Wiki's Testing page records the version for each session.

**App connections still open** (from the backend's answer of 11-15):

- Alarm control (largest): register the device alarm at `effective_alarm_at`, save any wheel time
  (`PUT …/alarm/time`), [추천 시각으로 되돌리기] (back to the recommended time), the
  "확인 전 / 확인함" (not confirmed / confirmed) state and [이 시각으로 알람 맞추기] (set this
  alarm), the "바꿀까요" card and heads-up notification with the `answer_by` deadline, and the
  "추천" (recommended) preset from `recommended_key`
- Login lock: a countdown with [로그인] disabled, and "N번 더 틀리면 15분 동안 잠겨요"
- Saved-place names: the 409 duplicate and empty-name errors under the name field, checked before
  saving with the server's rule
- Inquiry: refuse a reply email longer than 254 characters
- Learning reset: drop queued uploads older than `reset_at`
- Route history: the "최근 이동 경로" (recent routes) and "가장 자주 이용한 경로" (most-used
  routes) screens are not designed yet

**Heuristic evaluation items not started** — button levels (H2-6), predictive back and the
sign-up back gesture (H2-7), the add-alarm sheet (H2-8), the event screen redraw with undo after
choosing home (H2-9), the route bar legend and color sheet (H2-10), and the remaining 해요체
wording in older screens (H2-1). The map zoom (A4, a pinch can still jump up to three levels) and
the grey travelled path on the trip map (A5) are also open.

**CI.** The device integration job of CI runs 94 (`main` `d296ce4`) and 95 (`main` `a175b4d`)
failed; the backend, contract and app unit jobs passed. Run locally on this snapshot, 55 of the 56 device tests pass and one
fails: `SignupScreenTest` "서버가 닉네임을 거절하면 … 고치면 칸의 이유가 사라진다" (line 202) — after the
nickname is edited, the server's reason "쓸 수 없는 닉네임이에요" (this nickname can't be used) is
still under the field. CI's log needs a sign-in to read, so that this is the same failure is
likely but not confirmed. Whether the screen or the test is wrong is not decided yet.

**Not checked yet** — the new screens on a physical phone end to end, the Sunday notification on
a real Sunday, and the `qa/demo/run.py` UI runner of Iteration 2 against the new tab layout. The
UAT results of 11-16 and 11-18 will be added to the Wiki.

**Next (Iteration 5)** — finish the alarm-control connection and the open HE items, fix what the
UAT finds, split `HomeViewModel` (ViewModel coverage is still 7.1%), design-pattern
refactoring, then alpha and beta testing.

---

## Verification

```bash
cd backend && python -m pytest --cov                # backend unit + API integration tests
cd backend && python scripts/run_local_suite.py     # 8 HTTP suites against a throwaway local server
cd APP     && ./gradlew testDebugUnitTest jacocoDebugUnitTestReport lintDebug assembleDebug
cd APP     && ./gradlew jacocoDebugAndroidTestReport -Pandroid.testInstrumentationRunnerArguments.package=com.swpp.wakeup.integration
                                                    # app integration tests on a running emulator
```

Run on this snapshot (`a52d9b2`) on 2026-11-16 (Windows 11, Python 3.12, Android Studio's JBR):

| Suite | Result |
| --- | --- |
| Backend pytest | 2,298 passed (unit 1,302 · integration 996) in 80 s; line coverage 96.0% (computation 95.6%, API 96.2%) |
| Contract suites (8), without a Kakao key | 241 passed · 34 skipped · 0 failed |
| App JVM tests | 1,221 passed, 0 failed; line coverage domain 97.2%, data 70.0%, ViewModel 7.1%, platform 14.1% |
| App device tests (`integration` package, Android 36 emulator) | 55 passed · **1 failed** (`SignupScreenTest`, see [Known limitations](#known-limitations-and-todos)); no device coverage report, because Gradle stops at the failure |

CI runs the same suites on every push to `main` and every pull request. Coverage by layer and the
device test results are in the Wiki's
[Testing Documentation, Iteration 4](https://github.com/snuhcs-course/swpp-2026-project-team-18/wiki/team18%E2%80%90iter4%E2%80%90testing).

---

SNU SWPP 2026 Fall · Team 18 · Momentum
