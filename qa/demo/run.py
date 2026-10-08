#!/usr/bin/env python3
"""Run the demo E2E locally or against the public server with UI setup."""
from __future__ import annotations

import argparse
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.swpp.wakeup.qa"
REMOTE = f"/sdcard/Android/data/{PACKAGE}/files/demo-qa"
API_PORT, CONTROL_PORT = 8765, 8766
PUBLIC_API = "https://justintime-api.onrender.com"


def now():
    return datetime.now(timezone.utc)


def iso(value):
    return value.isoformat()


def read_rows(value):
    return value.get("results", []) if isinstance(value, dict) else value


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def run(command, **kwargs):
    return subprocess.run(command, check=True, text=True, **kwargs)


class Demo:
    def __init__(self, args):
        self.args = args
        self.scenario = json.loads(args.scenario.read_text(encoding="utf-8"))
        require(20 <= self.scenario["alarm_wait_seconds"] <= 120, "알람 대기는 20~120초여야 합니다.")
        require(self.scenario["travel_seconds"] >= 45, "출발 판정을 위해 이동은 45초 이상이어야 합니다.")
        self.public_server = args.public_ui or args.public_e2e
        self.api_base = PUBLIC_API if self.public_server else f"http://127.0.0.1:{API_PORT}"
        prefix = "public-e2e-" if args.public_e2e else "public-ui-" if args.public_ui else ""
        self.output = ROOT / ".artifacts" / "demo-qa" / (prefix + now().strftime("%Y%m%dT%H%M%SZ"))
        self.output.mkdir(parents=True)
        (self.output / "scenario.json").write_text(json.dumps(self.scenario, ensure_ascii=False, indent=2))
        self.env = os.environ.copy()
        # dotenv의 공용 Neon 주소보다 먼저 설정한다. 다른 DB를 선택할 옵션은 없다.
        self.env.update(DATABASE_URL=f"sqlite:///{self.output / 'qa.sqlite3'}",
                        DJANGO_SETTINGS_MODULE="config.settings.dev",
                        DJANGO_SECRET_KEY=secrets.token_urlsafe(40), PYTHONUNBUFFERED="1",
                        PYTHONIOENCODING="utf-8", FCM_CREDENTIALS_PATH="")
        java = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
        if "JAVA_HOME" not in self.env and java.exists():
            self.env["JAVA_HOME"] = str(java)
        sdk_path = self.env.get("ANDROID_HOME") or self.env.get("ANDROID_SDK_ROOT")
        properties = ROOT / "APP/local.properties"
        if not sdk_path and properties.exists():
            for line in properties.read_text().splitlines():
                if line.strip().startswith("sdk.dir="):
                    sdk_path = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "\\")
        sdk = Path(sdk_path or Path.home() / "Library/Android/sdk")
        adb = shutil.which("adb") or str(sdk / "platform-tools/adb")
        require(Path(adb).exists(), "adb를 찾을 수 없습니다. ANDROID_HOME을 설정하세요.")
        devices = run([adb, "devices"], capture_output=True).stdout.splitlines()[1:]
        devices = [line.split()[0] for line in devices if line.strip().endswith("device")
                   and line.startswith("emulator-")]
        self.device = args.device or (devices[0] if len(devices) == 1 else None)
        require(self.device in devices, "API 34+ 에뮬레이터 하나를 켜거나 --device emulator-5554를 지정하세요.")
        self.adb = [adb, "-s", self.device]
        require(int(self.shell("getprop", "ro.build.version.sdk")) >= 34, "API 34 이상이 필요합니다.")
        require(self.shell("getprop", "persist.sys.timezone") == "Asia/Seoul",
                "에뮬레이터 시간대를 Asia/Seoul로 설정하세요 (docs/device-setup.md).")
        self.python = ROOT / "backend/.venv/bin/python"
        if os.name == "nt":
            self.python = ROOT / "backend/.venv/Scripts/python.exe"
        require(self.python.exists(), "backend 가상환경을 먼저 준비하세요 (backend/README.md).")
        ports = (CONTROL_PORT,) if args.public_e2e else () if args.public_ui else (API_PORT, CONTROL_PORT)
        for port in ports:
            with socket.socket() as sock:
                require(sock.connect_ex(("127.0.0.1", port)) != 0, f"{port} 포트가 사용 중입니다.")
        self.backend = self.instrument = self.logcat = self.recorder = self.control = None
        self.installed = False
        self.files = []
        self.token = None
        self.control_token = secrets.token_urlsafe(24)
        self.gps_stop = threading.Event()
        self.gps_thread = None
        self.gps_error = None
        self.videos = []
        self.video_remote = f"/data/local/tmp/jit-demo-{self.output.name}"
        self.video_stop = threading.Event()
        self.armed = False
        self.closed = False
        mode = "public_e2e" if args.public_e2e else "public_ui" if args.public_ui else "local_e2e"
        self.result = {"scenario": self.scenario["name"], "mode": mode,
                       "api": self.api_base, "status": "failed", "device": self.device}

    def shell(self, *args):
        return run(self.adb + ["shell", *args], capture_output=True, timeout=30).stdout.strip()

    def api(self, method, path, payload=None):
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = Request(f"{self.api_base}/api/{path}",
                          data=json.dumps(payload).encode() if payload is not None else None,
                          headers=headers, method=method)
        try:
            with urlopen(request, timeout=45) as response:
                return json.load(response)
        except HTTPError as error:
            # Password/token API 응답은 로그에 남기지 않는다.
            detail = "" if path.startswith("auth/") else error.read().decode()[:500]
            raise RuntimeError(f"API {method} {path}: HTTP {error.code} {detail}") from error

    def prepare_ui(self):
        print("[1/5] UI 확인 준비 (새 QA 계정·데이터를 화면에서 생성)", flush=True)
        deadline = time.monotonic() + 120
        while True:
            try:
                self.result["health"] = self.api("GET", "health")
                break
            except (OSError, RuntimeError):
                require(time.monotonic() < deadline, "공용 서버 깨우기 실패")
                time.sleep(1)
        self.email = f"qa-ui-{secrets.token_hex(5)}@example.com"
        self.password = "Qa1-" + secrets.token_urlsafe(18)
        self.start_at = (now() + timedelta(hours=2)).astimezone(timezone(timedelta(hours=9))).replace(second=0, microsecond=0)
        # 재확인할 때 쓸 자격증명만 별도 비공개 파일에 보관한다. 증거·로그에는 넣지 않는다.
        path = self.output / "credentials.json"
        with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as file:
            json.dump({"email": self.email, "password": self.password}, file)
        self.result.update(email=self.email, expected_start_at=iso(self.start_at))

    def verify_ui(self):
        # 가입·프로필·블록·일정 쓰기는 모두 앱 UI가 수행했다. 여기서는 저장 결과만 조회한다.
        self.token = self.api("POST", "auth/token", {"email": self.email, "password": self.password})["access"]
        profile = self.api("GET", "profile")
        blocks = read_rows(self.api("GET", "routines/blocks"))
        events = read_rows(self.api("GET", "events"))
        require(profile["has_home"] and profile["home_label"] == self.scenario["home_query"], "UI 집 설정이 저장되지 않았습니다.")
        require(profile["onboarding_prep_min"] == self.scenario["onboarding_prep_minutes"], "UI 준비 시간이 저장되지 않았습니다.")
        require(len(blocks) == len(self.scenario["blocks"]) and {b["name"] for b in blocks} == set(self.scenario["blocks"]),
                "UI 루틴이 정확히 저장되지 않았습니다.")
        require(all(b["default_min_minutes"] == self.scenario["block_min_minutes"] and
                    b["default_max_minutes"] == self.scenario["block_max_minutes"] and b["included_by_default"] for b in blocks),
                "UI 루틴 범위·기본 포함 값이 다릅니다.")
        require(len(events) == 1 and events[0]["title"] == self.scenario["event_title"], "UI 일정이 정확히 한 건 저장되지 않았습니다.")
        event = events[0]
        require(datetime.fromisoformat(event["start_at"]) == self.start_at, "UI 날짜·시각이 저장 값과 다릅니다.")
        require(event["place"]["name"] == self.scenario["destination_query"] and event["route_key"] == self.scenario["route_key"],
                "UI 목적지·경로 선택이 저장 값과 다릅니다.")
        require(event["origin_lat"] is None and event["origin_lng"] is None,
                "UI 집 선택이 별도 출발지로 저장되어 준비 시간을 제외합니다.")
        require(event["alarm_enabled"] is True and event["alarm_on"] is True and event["is_first_alarm"] is True,
                "UI 알람 스위치 ON이 서버에 저장되지 않았습니다.")
        plan = event["alarm_plan"]
        require(plan["prep_minutes"] > 0 and plan["prep_source"] != "not_from_home" and
                len(plan["prep_breakdown"]) == len(blocks), "UI에서 만든 준비 루틴이 알람 계산에 반영되지 않았습니다.")
        require(plan["status"] == "ok" and len(plan["route_path"]) >= 2 and plan["travel_time_source"] == "kakao_walk",
                "실제 카카오 도보 경로로 알람을 계산하지 못했습니다.")
        require(datetime.fromisoformat(plan["alarm_at"]) > now(), "UI 일정의 알람이 미래 시각이 아닙니다.")
        event_blocks = self.api("GET", f"events/{event['id']}/blocks")
        require(len(event_blocks["blocks"]) == len(blocks) and all(b["checked"] for b in event_blocks["blocks"]),
                "UI에서 만든 루틴이 새 일정에 기본 포함되지 않았습니다.")
        evidence = {"status": "passed", "api": self.api_base, "profile": profile,
                    "blocks": blocks, "event": event, "event_blocks": event_blocks}
        filename = "server-evidence.json" if self.args.public_ui else "setup-server-evidence.json"
        (self.output / filename).write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
        # 이후 쓰기는 방금 UI로 만든 계정의 유일한 일정에만 수행한다.
        self.event, self.event_id, self.blocks = event, event["id"], blocks
        self.home = {"lat": profile["home_lat"], "lng": profile["home_lng"]}
        self.result.update(event_id=event["id"], alarm_at=plan["alarm_at"], server_verified=True)
        print(f"      서버 저장 확인: 루틴 {len(blocks)}개 · 일정 {event['id']} · {plan['travel_time_source']}", flush=True)

    def start_backend(self):
        print("[1/5] 일회용 DB · 로컬 백엔드 준비", flush=True)
        log = open(self.output / "backend.log", "w")
        self.files.append(log)
        run([str(self.python), "manage.py", "migrate", "--noinput"], cwd=ROOT / "backend",
            env=self.env, stdout=log, stderr=subprocess.STDOUT, timeout=120)
        self.backend = subprocess.Popen([str(self.python), "manage.py", "runserver",
                                         f"127.0.0.1:{API_PORT}", "--noreload", "--nothreading"],
                                        cwd=ROOT / "backend", env=self.env, stdout=log, stderr=log)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            require(self.backend.poll() is None, "백엔드 시작 실패: backend.log를 확인하세요.")
            try:
                self.api("GET", "health")
                return
            except (OSError, RuntimeError):
                time.sleep(.5)
        raise RuntimeError("백엔드 연결 시간 초과")

    def arm(self):
        require(not self.armed, "알람은 한 번만 예약할 수 있습니다.")
        plan = self.event["alarm_plan"]
        lead = datetime.fromisoformat(self.event["start_at"]) - datetime.fromisoformat(plan["alarm_at"])
        for _ in range(3):
            target = now() + timedelta(seconds=self.scenario["alarm_wait_seconds"])
            self.event = self.api("PATCH", f"events/{self.event_id}", {"start_at": iso(target + lead)})
            actual = datetime.fromisoformat(self.event["alarm_plan"]["alarm_at"])
            if 20 <= (actual - now()).total_seconds() <= 120:
                break
            lead = datetime.fromisoformat(self.event["start_at"]) - actual
        require(20 <= (actual - now()).total_seconds() <= 120, "짧은 알람 예약에 실패했습니다.")
        self.armed = True
        self.result["armed_plan"] = self.event["alarm_plan"]
        print(f"      알람 예약: 약 {(actual - now()).total_seconds():.0f}초 뒤", flush=True)
        return {"alarm_at_ms": int(actual.timestamp() * 1000)}

    def gps(self, lat, lng):
        run(self.adb + ["emu", "geo", "fix", str(lng), str(lat)], capture_output=True, timeout=10)

    def travel(self):
        require(self.gps_thread is None, "GPS 이동은 한 번만 실행합니다.")
        def drive():
            try:
                path = self.event["alarm_plan"]["route_path"]
                # API의 실제 길을 따라 이동. 마지막은 도로의 스냅 지점 대신 장소 중심이다.
                points = [(float(p[0]), float(p[1])) for p in path]
                distances = [0.0]
                for a, b in zip(points, points[1:]):
                    distances.append(distances[-1] + math.hypot((b[0]-a[0])*111000,
                                      (b[1]-a[1])*111000*math.cos(math.radians(a[0]))))
                duration = self.scenario["travel_seconds"]
                started = time.monotonic()
                while not self.gps_stop.is_set():
                    fraction = min((time.monotonic() - started) / duration, 1)
                    if fraction == 1:
                        place = self.event["place"]
                        self.gps(place["lat"], place["lng"])
                    else:
                        distance = fraction * distances[-1]
                        i = next((i for i in range(1, len(points)) if distances[i] >= distance), len(points)-1)
                        span = distances[i] - distances[i-1]
                        t = (distance - distances[i-1]) / span if span else 0
                        a, b = points[i-1], points[i]
                        self.gps(a[0]+(b[0]-a[0])*t, a[1]+(b[1]-a[1])*t)
                    if self.gps_stop.wait(3):
                        return
            except Exception as error:
                self.gps_error = str(error)
        self.gps_thread = threading.Thread(target=drive, daemon=True)
        self.gps_thread.start()
        return {"started": True}

    def verify_and_close(self):
        require(not self.closed, "리포트 마감은 한 번만 실행합니다.")
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            observations = read_rows(self.api("GET", f"observations?event={self.event_id}"))
            blocks = read_rows(self.api("GET", "routines/blocks"))
            if len(observations) == 2 and all(b["observation_count"] == 1 for b in blocks):
                break
            time.sleep(1)
        require(len(observations) == 2 and {o["kind"] for o in observations} == {"depart", "arrive"},
                "출발·도착 관측이 정확히 한 건씩 저장되지 않았습니다.")
        arrival = next(o for o in observations if o["kind"] == "arrive")
        require(arrival["dwell_confirmed"] and arrival["dwell_seconds"] >= 120,
                "실제 2분 체류로 확정된 도착이 아닙니다.")
        require(len(blocks) == len(self.blocks) and all(b["observation_count"] == 1 for b in blocks),
                "실제 루틴 완료 기록이 저장되지 않았습니다.")
        # 미래 일정은 리포트에 안 잡힌다. 실제 관측을 보존한 채 같은 일정의 시각만 마감한다.
        self.api("PATCH", f"events/{self.event_id}", {"start_at": iso(now() - timedelta(seconds=1))})
        report = self.api("GET", "reports/weekly?week=" + now().astimezone(timezone(timedelta(hours=9))).date().isoformat())
        require(report["event_count"] == 1 and report["arrived_count"] == 1 and report["on_time_count"] == 1,
                "실제 관측이 주간 리포트에 반영되지 않았습니다.")
        self.closed = True
        self.result.update(observations=observations, blocks=blocks, report=report, report_verified=True)
        evidence = {"status": "passed", "api": self.api_base, "event_id": self.event_id,
                    "observations": observations, "blocks": blocks, "report": report}
        (self.output / "server-evidence.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2))
        return {"verified": True, "arrived_count": report["arrived_count"]}

    def start_control(self):
        demo = self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                if self.headers.get("X-QA-Token") != demo.control_token:
                    self.send_error(403)
                    return
                actions = {"/arm": demo.arm, "/travel": demo.travel, "/verify": demo.verify_and_close}
                try:
                    require(self.path in actions, "알 수 없는 QA 단계")
                    payload = actions[self.path]()
                    self.send_response(200)
                except Exception as error:
                    payload = {"error": str(error)}
                    self.send_response(500)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps(payload).encode())
        self.control = ThreadingHTTPServer(("127.0.0.1", CONTROL_PORT), Handler)
        threading.Thread(target=self.control.serve_forever, daemon=True).start()

    def build_install(self):
        print("[2/5] 독립 QA 앱 · UI 테스트 빌드/설치", flush=True)
        log = open(self.output / "build.log", "w")
        self.files.append(log)
        run([str(ROOT / "APP/gradlew"), ":app:assembleE2e", ":app:assembleE2eAndroidTest",
             "-PjitE2e=true", f"-PjitPublicUi={str(self.public_server).lower()}", "--console=plain"], cwd=ROOT / "APP", env=self.env,
             stdout=log, stderr=subprocess.STDOUT, timeout=600)
        for apk in ("e2e/app-e2e.apk", "androidTest/e2e/app-e2e-androidTest.apk"):
            run(self.adb + ["install", "-r", str(ROOT / "APP/app/build/outputs/apk" / apk)],
                capture_output=True, timeout=120)
        self.installed = True
        self.shell("pm", "clear", PACKAGE)  # 이 테스트 전용 패키지만 초기화한다.
        for permission in ("POST_NOTIFICATIONS", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION"):
            self.shell("pm", "grant", PACKAGE, f"android.permission.{permission}")
        self.shell("appops", "set", PACKAGE, "USE_FULL_SCREEN_INTENT", "allow")
        self.shell("cmd", "location", "set-location-enabled", "true")
        self.shell("input", "keyevent", "KEYCODE_WAKEUP")
        self.shell("wm", "dismiss-keyguard")
        self.write_config()
        home = self.scenario["home"]
        self.gps(home["lat"], home["lng"])

    def write_config(self):
        config = {"email": self.email, "password": self.password,
                  "event_title": self.scenario["event_title"], "blocks": self.scenario["blocks"],
                  "block_seconds": self.scenario["block_seconds"],
                  "control_url": f"http://10.0.2.2:{CONTROL_PORT}", "control_token": self.control_token,
                  "public_e2e": self.args.public_e2e}
        config.update(nickname=self.scenario["nickname"], home_query=self.scenario["home_query"],
                      prep_minutes=self.scenario["onboarding_prep_minutes"],
                      block_min_minutes=self.scenario["block_min_minutes"],
                      block_max_minutes=self.scenario["block_max_minutes"],
                      destination_query=self.scenario["destination_query"], route_key=self.scenario["route_key"],
                      event_date=self.start_at.date().isoformat(), event_time=self.start_at.strftime("%H:%M"))
        if hasattr(self, "event_id"):
            config["event_id"] = self.event_id
        # Android의 외부 저장소 소유권과 무관하게 앱 전용 내부 파일에 쓴다.
        run(self.adb + ["shell", "run-as", PACKAGE, "sh", "-c",
                       "'mkdir -p files; cat > files/demo-qa-config.json'"],
            input=json.dumps(config), capture_output=True, timeout=30)

    def start_video(self):
        if self.args.no_video:
            return
        self.shell("mkdir", "-p", self.video_remote)
        def record():
            index = 1
            while not self.video_stop.is_set():
                name = f"screen-{index:02}.mp4"
                remote = self.video_remote + "/" + name
                self.videos.append(name)
                # PID 파일은 이 실행의 screenrecord만 중지하는 데 사용한다.
                command = f"screenrecord --bit-rate 2000000 --time-limit 180 {remote} & echo $! > {self.video_remote}/video.pid; wait"
                self.recorder = subprocess.Popen(self.adb + ["shell", command], stdout=subprocess.DEVNULL,
                                                  stderr=subprocess.DEVNULL)
                self.recorder.wait()
                index += 1
        self.video_thread = threading.Thread(target=record, daemon=True)
        self.video_thread.start()

    def execute(self):
        print("[3/5] " + ("가입·설정 → 실제 알람 → 루틴 → GPS 도착 → 공용 서버 리포트" if self.args.public_e2e else
                          "가입 → 집·준비 시간 → 루틴 → 일정·경로 → 재로그인" if self.args.public_ui else
                          "가입·설정 → 알람 켬/끔 → 실제 알람 → GPS 도착 → 리포트"), flush=True)
        log = open(self.output / "logcat.txt", "w")
        self.files.append(log)
        self.logcat = subprocess.Popen(self.adb + ["logcat", "-v", "threadtime", "-T", "1",
            "DemoFlowQA:I", "TripTrackingService:I", "AlarmReceiver:I", "TripObservationQueue:I",
            "BlockObservationQueue:I", "AndroidRuntime:E", "*:S"], stdout=log, stderr=log)
        self.start_video()
        started = time.monotonic()
        filename = "instrumentation.txt" if self.args.public_ui else "setup-instrumentation.txt"
        self.result["setup_seconds"] = self.execute_test("PublicSetupTest", filename)
        self.verify_ui()
        if not self.args.public_ui:
            # 준비 단계의 결과를 보존하고 같은 APK·계정·일정으로 완주한다.
            run(self.adb + ["pull", REMOTE + "/steps.json", str(self.output / "setup-steps.json")],
                capture_output=True, timeout=30)
            self.start_control()
            self.write_config()
            self.gps(self.home["lat"], self.home["lng"])
            self.result["flow_seconds"] = self.execute_test("DemoFlowTest", "instrumentation.txt")
            require(self.closed, "백엔드 관측·리포트 검증이 실행되지 않았습니다.")
        self.result["status"] = "passed"
        self.result["duration_seconds"] = round(time.monotonic() - started, 1)

    def execute_test(self, test_class, filename):
        test_log = open(self.output / filename, "w")
        self.files.append(test_log)
        log_position = (self.output / "logcat.txt").stat().st_size
        self.instrument = subprocess.Popen(self.adb + ["shell", "am", "instrument", "-w", "-r",
            "-e", "class", f"com.swpp.wakeup.{test_class}",
            f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"], stdout=test_log, stderr=test_log)
        started = time.monotonic()
        while self.instrument.poll() is None:
            require(time.monotonic() - started < 720, "E2E 실행이 12분을 넘었습니다.")
            require(self.gps_error is None, f"GPS 주입 실패: {self.gps_error}")
            with open(self.output / "logcat.txt") as progress:
                progress.seek(log_position)
                for line in progress.read().splitlines():
                    if "DemoFlowQA:" in line:
                        print("      " + line.split("DemoFlowQA:", 1)[1].strip(), flush=True)
                log_position = progress.tell()
            time.sleep(1)
        test_log.flush()
        output = (self.output / filename).read_text()
        error = f"Android UI 테스트 실패: {filename}와 실패 스크린샷을 확인하세요."
        for line in output.splitlines():
            if line.startswith("INSTRUMENTATION_STATUS: stack="):
                error += "\n원인: " + line.split("stack=", 1)[1]
                break
        require(self.instrument.returncode == 0 and "OK (1 test)" in output,
                error)
        return round(time.monotonic() - started, 1)

    def stop_video(self):
        if self.args.no_video or not hasattr(self, "video_thread"):
            return
        self.video_stop.set()
        try:
            pid = self.shell("cat", self.video_remote + "/video.pid")
            if pid.isdigit():
                self.shell("kill", "-2", pid)
        except subprocess.CalledProcessError:
            pass
        self.video_thread.join(timeout=15)

    def cleanup(self):
        print("[4/5] 결과 수집 · QA 앱 종료" if self.public_server else "[4/5] 결과 수집 · 임시 서버 종료", flush=True)
        self.gps_stop.set()
        if self.gps_thread:
            self.gps_thread.join(timeout=12)
        warnings = []
        def attempt(action):
            try:
                action()
            except (OSError, subprocess.SubprocessError) as error:
                warnings.append(type(error).__name__)
        if self.instrument and self.instrument.poll() is None:
            attempt(lambda: self.shell("am", "force-stop", PACKAGE))
            self.instrument.terminate()
        attempt(self.stop_video)
        if self.installed:
            attempt(lambda: self.shell("am", "force-stop", PACKAGE))
        try:
            self.shell("run-as", PACKAGE, "rm", "-f", "files/demo-qa-config.json")
            run(self.adb + ["pull", REMOTE, str(self.output / "device")], capture_output=True, timeout=60)
        except (OSError, subprocess.SubprocessError):
            pass
        (self.output / "device").mkdir(exist_ok=True)
        setup_steps = self.output / "setup-steps.json"
        if setup_steps.exists():
            setup_steps.replace(self.output / "device/setup-steps.json")
        for name in self.videos:
            attempt(lambda name=name: run(self.adb + ["pull", self.video_remote + "/" + name,
                    str(self.output / "device" / name)], capture_output=True, timeout=60))
        if self.videos:
            attempt(lambda: self.shell("rm", "-rf", self.video_remote))
        for process in (self.logcat, self.backend):
            if process and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
        if self.control:
            self.control.shutdown()
            self.control.server_close()
        for file in self.files:
            file.close()
        if warnings:
            self.result["cleanup_warnings"] = warnings
        (self.output / "result.json").write_text(json.dumps(self.result, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", help="예: emulator-5554")
    parser.add_argument("--scenario", type=Path, default=Path(__file__).with_name("scenario.json"))
    parser.add_argument("--no-video", action="store_true", help="스크린샷·로그만 남김")
    public = parser.add_mutually_exclusive_group()
    public.add_argument("--public-ui", action="store_true", help="공용 서버에 새 QA 계정·루틴·일정을 실제 UI로 생성·검증")
    public.add_argument("--public-e2e", action="store_true", help="공용 UI 준비 후 같은 계정으로 실제 알람·GPS 도착·리포트까지 완주")
    demo = None
    try:
        demo = Demo(parser.parse_args())
        if not demo.public_server:
            demo.start_backend()
        demo.prepare_ui()
        demo.build_install()
        demo.execute()
    except (Exception, KeyboardInterrupt) as error:
        if demo:
            demo.result["error"] = str(error)
        print(f"FAIL: {error}", file=sys.stderr, flush=True)
    finally:
        if demo:
            demo.cleanup()
            print(f"[5/5] {demo.result['status'].upper()} — {demo.output}", flush=True)
    return 0 if demo and demo.result["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
