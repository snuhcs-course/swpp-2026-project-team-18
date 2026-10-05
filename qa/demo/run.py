#!/usr/bin/env python3
"""Run the single real-API / real-GPS Android demo scenario on an emulator."""
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
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.swpp.wakeup.qa"
REMOTE = f"/sdcard/Android/data/{PACKAGE}/files/demo-qa"
API_PORT, CONTROL_PORT = 8765, 8766


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
        self.output = ROOT / ".artifacts" / "demo-qa" / now().strftime("%Y%m%dT%H%M%SZ")
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
        for port in (API_PORT, CONTROL_PORT):
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
        self.result = {"scenario": self.scenario["name"], "status": "failed", "device": self.device}

    def shell(self, *args):
        return run(self.adb + ["shell", *args], capture_output=True, timeout=30).stdout.strip()

    def api(self, method, path, payload=None):
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        request = Request(f"http://127.0.0.1:{API_PORT}/api/{path}",
                          data=json.dumps(payload).encode() if payload is not None else None,
                          headers=headers, method=method)
        try:
            with urlopen(request, timeout=45) as response:
                return json.load(response)
        except HTTPError as error:
            # Password/token API 응답은 로그에 남기지 않는다.
            detail = "" if path.startswith("auth/") else error.read().decode()[:500]
            raise RuntimeError(f"API {method} {path}: HTTP {error.code} {detail}") from error

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

    def seed(self):
        self.email = f"qa-{secrets.token_hex(5)}@example.com"
        self.password = secrets.token_urlsafe(18)
        self.token = self.api("POST", "auth/register", {
            "email": self.email, "nickname": self.scenario["nickname"],
            "password": self.password, "password_confirm": self.password})["access"]
        home = self.scenario["home"]
        self.api("PATCH", "profile", {"home_lat": home["lat"], "home_lng": home["lng"],
                                     "home_label": home["label"], "onboarding_prep_min": 2,
                                     "default_tau": .9, "timezone": "Asia/Seoul"})
        self.blocks = []
        for index, name in enumerate(self.scenario["blocks"]):
            self.blocks.append(self.api("POST", "routines/blocks", {
                "name": name, "default_min_minutes": self.scenario["block_min_minutes"],
                "default_max_minutes": self.scenario["block_max_minutes"],
                "precondition": self.blocks[-1]["id"] if self.blocks else None,
                "included_by_default": True, "parallelizable": False, "order": index}))
        search = self.api("GET", "places/search?" + urlencode({
            "q": self.scenario["destination_query"], "lat": home["lat"], "lng": home["lng"]}))
        require(not search["degraded"] and search["results"], "실제 카카오 장소 검색 실패: backend/.env를 확인하세요.")
        place = search["results"][0]
        self.event = self.api("POST", "events", {"title": self.scenario["event_title"],
            "start_at": iso(now() + timedelta(hours=2)), "route_key": self.scenario["route_key"],
            "place": {key: place[key] for key in ("name", "lat", "lng", "address", "kakao_place_id") if key in place}})
        self.event_id = self.event["id"]
        plan = self.event["alarm_plan"]
        require(plan["status"] == "ok" and len(plan["route_path"]) >= 2,
                f"실제 경로 계산 실패 (status={plan['status']}). QA는 폴백을 통과 처리하지 않습니다.")
        require(plan["travel_time_source"] == "kakao_walk", f"실제 도보 경로가 아닙니다: {plan['travel_time_source']}")
        self.result.update(event_id=self.event_id, destination=self.event["place"], initial_plan=plan)
        print(f"      실제 장소·경로 확인: {self.event['place']['name']} / {plan['travel_minutes']}분", flush=True)

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
        self.result.update(observations=observations, blocks=blocks, report=report)
        evidence = {"status": "passed", "event_id": self.event_id,
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
             "-PjitE2e=true", "--console=plain"], cwd=ROOT / "APP", env=self.env,
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
        config = {"email": self.email, "password": self.password, "event_id": self.event_id,
                  "event_title": self.scenario["event_title"], "blocks": self.scenario["blocks"],
                  "block_seconds": self.scenario["block_seconds"],
                  "control_url": f"http://10.0.2.2:{CONTROL_PORT}", "control_token": self.control_token}
        # Android의 외부 저장소 소유권과 무관하게 앱 전용 내부 파일에 쓴다.
        run(self.adb + ["shell", "run-as", PACKAGE, "sh", "-c",
                       "'mkdir -p files; cat > files/demo-qa-config.json'"],
            input=json.dumps(config), capture_output=True, timeout=30)
        home = self.scenario["home"]
        self.gps(home["lat"], home["lng"])

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
        print("[3/5] 로그인 → 잠금화면 알람 → 루틴 → GPS 출발·도착 → 리포트", flush=True)
        log = open(self.output / "logcat.txt", "w")
        self.files.append(log)
        self.logcat = subprocess.Popen(self.adb + ["logcat", "-v", "threadtime", "-T", "1",
            "DemoFlowQA:I", "TripTrackingService:I", "AlarmReceiver:I", "TripObservationQueue:I",
            "BlockObservationQueue:I", "AndroidRuntime:E", "*:S"], stdout=log, stderr=log)
        self.start_video()
        test_log = open(self.output / "instrumentation.txt", "w")
        self.files.append(test_log)
        self.instrument = subprocess.Popen(self.adb + ["shell", "am", "instrument", "-w", "-r",
            "-e", "class", "com.swpp.wakeup.DemoFlowTest",
            f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"], stdout=test_log, stderr=test_log)
        started = time.monotonic()
        log_position = 0
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
        output = (self.output / "instrumentation.txt").read_text()
        require(self.instrument.returncode == 0 and "OK (1 test)" in output,
                "Android UI 테스트 실패: instrumentation.txt와 실패 스크린샷을 확인하세요.")
        require(self.closed, "백엔드 관측·리포트 검증이 실행되지 않았습니다.")
        self.result["status"] = "passed"
        self.result["duration_seconds"] = round(time.monotonic() - started, 1)

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
        print("[4/5] 결과 수집 · 임시 서버 종료", flush=True)
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
    demo = None
    try:
        demo = Demo(parser.parse_args())
        demo.start_backend()
        demo.seed()
        demo.start_control()
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
