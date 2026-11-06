"""Iteration 3 end-to-end demo: the four scenes of the demo videos, run through the real API.

The scenario is the one in the videos: a class tomorrow at 15:00 at the SNU College of
Engineering, leaving from Sillim Station.

  1. A faster route before the alarm: the event is saved with the slowest transit route; the
     server offers a faster one (alt_route_*); switching to it moves the alarm later.
  2. Edit and route candidates: the transit candidates (up to seven, with badges) are listed and
     the event is edited.
  3. Rerouting on the way: the start is moved close to now so the current route is late; the
     live route from Sillim Station returns `switch_suggestion`, and the reroute is sent.
  4. Arrival by hand: a manual arrival ("도착했어요") is uploaded and the event shows it.

By default the script starts a throwaway local server (SQLite in .artifacts/demo-iter3/) with the
current backend code. That needs `backend/.venv` and the Kakao keys in `backend/.env`. With
`--server https://justintime-api.onrender.com` it runs against the shared server instead and
creates a new demo account there (no keys needed; sign-up allows 10 per hour per address).

With `--keep`, the local server stays up after the run on port 8000, so the Android app can log in
with the printed account and show the same event: set `devServerHost=10.0.2.2` in
`APP/local.properties` for an emulator. Press Ctrl+C to stop it.

usage (from the repository root):
  backend/.venv/bin/python qa/demo/iter3_e2e.py
  backend/.venv/bin/python qa/demo/iter3_e2e.py --keep
  backend/.venv/bin/python qa/demo/iter3_e2e.py --server https://justintime-api.onrender.com

Exit code 0 when every scene passed. Results: .artifacts/demo-iter3/<time>/result.json
(Partly AI-generated (Claude))
"""
from __future__ import annotations

import argparse
import json
import os
import secrets
import socket
import subprocess
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
KST = timezone(timedelta(hours=9))
SILLIM = {"lat": 37.484267, "lng": 126.929745, "label": "신림역"}
# Fallback when place search is unavailable: SNU College of Engineering (Building 301 area).
SNU_ENGINEERING = {"name": "서울대학교 공과대학", "lat": 37.4500, "lng": 126.9527,
                   "address": "서울 관악구 관악로 1"}


class Failed(Exception):
    pass


class Demo:
    def __init__(self, args):
        self.args = args
        self.local = args.server is None
        self.base = (args.server or f"http://127.0.0.1:{args.port}").rstrip("/")
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        self.out = ROOT / ".artifacts" / "demo-iter3" / stamp
        self.out.mkdir(parents=True, exist_ok=True)
        self.token = None
        self.server = None
        self.scenes: list[dict] = []
        self.account = {"email": f"demo-iter3-{secrets.token_hex(4)}@example.com",
                        "password": f"Demo-{secrets.token_urlsafe(9)}-7a", "nickname": "데모"}

    # -- plumbing ---------------------------------------------------------------------------
    def api(self, method, path, payload=None, query=None, ok=(200, 201, 204)):
        url = f"{self.base}/api/{path}" + (f"?{urlencode(query)}" if query else "")
        headers = {"Content-Type": "application/json"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        data = json.dumps(payload).encode() if payload is not None else None
        request = Request(url, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=90) as response:
                body = response.read()
                status = response.status
        except HTTPError as error:
            body, status = error.read(), error.code
        parsed = json.loads(body) if body else {}
        if status not in ok:
            # Never print auth bodies (passwords, tokens); only the error field names.
            detail = (sorted(parsed) if isinstance(parsed, dict) else "") if path.startswith("auth/") \
                else json.dumps(parsed, ensure_ascii=False)[:400]
            raise Failed(f"{method} /api/{path} → HTTP {status} {detail}")
        return parsed

    def start_server(self):
        python = ROOT / "backend" / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
        if not python.exists():
            raise Failed("backend/.venv is missing; see backend/README.md")
        with socket.socket() as sock:
            if sock.connect_ex(("127.0.0.1", self.args.port)) == 0:
                raise Failed(f"port {self.args.port} is in use")
        env = os.environ.copy()
        # Set before Django reads backend/.env, so the shared database is never used.
        env.update(DATABASE_URL=f"sqlite:///{self.out / 'demo.sqlite3'}",
                   DJANGO_SETTINGS_MODULE="config.settings.dev",
                   DJANGO_SECRET_KEY=secrets.token_urlsafe(40), PYTHONUNBUFFERED="1",
                   PYTHONIOENCODING="utf-8", FCM_CREDENTIALS_PATH="")
        log = open(self.out / "backend.log", "w")
        subprocess.run([str(python), "manage.py", "migrate", "--noinput"], cwd=ROOT / "backend",
                       env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=300)
        host = "0.0.0.0" if self.args.keep else "127.0.0.1"
        self.server = subprocess.Popen(
            [str(python), "manage.py", "runserver", f"{host}:{self.args.port}", "--noreload"],
            cwd=ROOT / "backend", env=env, stdout=log, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if self.server.poll() is not None:
                raise Failed(f"server did not start; see {self.out / 'backend.log'}")
            try:
                self.api("GET", "health")
                return
            except (OSError, Failed):
                time.sleep(0.5)
        raise Failed("server did not answer within 60 s")

    def scene(self, number, title, fn):
        print(f"\n[{number}/4] {title}", flush=True)
        started = time.monotonic()
        entry = {"scene": number, "title": title}
        try:
            entry["evidence"] = fn()
            entry["status"] = "passed"
            print("  → passed", flush=True)
        except Failed as error:
            entry["status"] = "failed"
            entry["error"] = str(error)
            print(f"  → FAILED: {error}", flush=True)
        entry["seconds"] = round(time.monotonic() - started, 1)
        self.scenes.append(entry)

    @staticmethod
    def say(text):
        print(f"  {text}", flush=True)

    # -- setup ------------------------------------------------------------------------------
    def setup(self):
        health = self.api("GET", "health")
        self.say(f"server {self.base} · version {health.get('version')}")
        reg = self.api("POST", "auth/register", {**self.account, "password_confirm": self.account["password"],
                                                "terms_agreed": True})
        self.token = reg.get("access") or self.api(
            "POST", "auth/token", {"email": self.account["email"], "password": self.account["password"]})["access"]
        self.api("PATCH", "profile", {"home_lat": SILLIM["lat"], "home_lng": SILLIM["lng"],
                                       "home_label": SILLIM["label"], "onboarding_prep_min": 30})
        self.say(f"account {self.account['email']} · home {SILLIM['label']} · prep 30 min")
        self.place = SNU_ENGINEERING
        try:
            found = self.api("GET", "places/search", query={"query": "서울대학교 공과대학"})
            items = found.get("results") or found.get("places") or found.get("documents") or []
            if items:
                p = items[0]
                self.place = {"name": p.get("name") or p.get("place_name"), "lat": float(p.get("lat") or p.get("y")),
                              "lng": float(p.get("lng") or p.get("x")),
                              "address": p.get("address") or p.get("road_address") or "",
                              "kakao_place_id": str(p.get("kakao_place_id") or p.get("id") or "") or None}
        except Failed as error:
            self.say(f"place search unavailable, using fixed coordinates ({error})")
        self.say(f"destination {self.place['name']} ({self.place['lat']:.4f}, {self.place['lng']:.4f})")
        tomorrow = (datetime.now(KST) + timedelta(days=1)).date()
        self.start = datetime(tomorrow.year, tomorrow.month, tomorrow.day, 15, 0, tzinfo=KST)

    # -- scenes -----------------------------------------------------------------------------
    def candidates(self):
        res = self.api("GET", "routes/candidates",
                       query={"dest_lat": self.place["lat"], "dest_lng": self.place["lng"]})
        transit = None
        for group in res.get("groups") or res.get("modes") or []:
            if isinstance(group, dict) and (group.get("mode") == "transit" or group.get("key") == "transit"):
                transit = group.get("candidates") or group.get("routes") or group.get("items")
        if transit is None:
            transit = res.get("transit") or [c for c in res.get("results", [])
                                             if str(c.get("key", c.get("route_key", ""))).startswith("transit")]
        if not transit:
            raise Failed(f"no transit candidates (keys in response: {sorted(res)})")
        self.transit = transit
        self.fastest_key = res.get("fastest_key")
        rows = []
        for c in transit:
            key = c.get("key") or c.get("route_key")
            minutes = c.get("minutes") or c.get("total_minutes") or c.get("duration_min")
            badges = [b.get("label", b) if isinstance(b, dict) else b for b in c.get("badges", [])]
            label = (key or "").split(":", 1)[-1].replace(">", " → ") or c.get("label") or c.get("summary")
            alt = c.get("alternatives") or c.get("variants") or []
            rows.append({"key": key, "label": label, "minutes": minutes, "badges": badges,
                         "merged_variants": len(alt)})
            self.say(f"{minutes} min · {label}" + (f" · {', '.join(map(str, badges))}" if badges else "")
                     + (f" · (대체 {len(alt)}개)" if alt else ""))
        return rows

    def scene1(self):
        rows = self.candidates()
        slow = max((r for r in rows if r["minutes"]), key=lambda r: r["minutes"])
        event = self.api("POST", "events", {
            "client_request_id": uuid.uuid4().hex, "title": "수업 (Iteration 3 데모)",
            "start_at": self.start.isoformat(), "place": self.place, "route_key": slow["key"],
            "tag_key": "class"}, ok=(200, 201))
        self.event = event
        self.slow_key = slow["key"]
        plan = event.get("alarm_plan") or {}
        before = plan.get("alarm_at")
        self.say(f"saved with the slow route {plan.get('route_summary') or slow['label']} "
                 f"({slow['minutes']} min) → alarm {fmt(before)}")
        alt_key, alt_min = plan.get("alt_route_key"), plan.get("alt_faster_minutes")
        if not alt_key or not alt_min:
            raise Failed("the server offered no faster route for the slow pick")
        self.say(f"offer: \"{plan.get('alt_route_label')}(으)로 가면 {alt_min}분 빨라요\"")
        event = self.api("PATCH", f"events/{event['id']}", {"route_key": alt_key})
        after = (event.get("alarm_plan") or {}).get("alarm_at")
        self.say(f"switched to {alt_key} → alarm {fmt(before)} → {fmt(after)}")
        if not (before and after and after > before):
            raise Failed("the alarm did not move later after switching")
        self.event = event
        return {"slow": slow, "offer": {"route_key": alt_key, "faster_minutes": alt_min},
                "alarm_before": before, "alarm_after": after}

    def scene2(self):
        rows = self.candidates()
        if len(rows) < 2:
            raise Failed("fewer than two transit candidates")
        event = self.api("PATCH", f"events/{self.event['id']}", {"title": "수업 (수정됨)"})
        if event.get("title") != "수업 (수정됨)":
            raise Failed("edit not saved")
        self.say(f"edited: title → {event['title']}, route kept {event.get('route_key')}")
        return {"transit_candidates": rows, "edited_title": event["title"]}

    def scene3(self):
        eid = self.event["id"]
        route_min = max(int((self.event.get("alarm_plan") or {}).get("travel_minutes") or 25), 10)
        start = (datetime.now(KST) + timedelta(minutes=max(route_min - 10, 3))).replace(second=0, microsecond=0)
        # Ride the slow route again, as in the video, and move the start so it is late.
        self.api("PATCH", f"events/{eid}", {"start_at": start.isoformat(), "route_key": self.slow_key})
        self.say(f"trip on {self.slow_key}; start moved to {start:%H:%M} so the current route is late")
        now = datetime.now(KST).isoformat()
        self.api("POST", "observations/batch", {"observations": [{
            "event": eid, "kind": "depart", "detector": "gps", "observed_at": now,
            "lat": SILLIM["lat"], "lng": SILLIM["lng"], "accuracy_m": 10, "distance_m": 300,
            "client_uuid": uuid.uuid4().hex}]}, ok=(200, 201))
        live = self.api("POST", "routes/live", {"event_id": eid, "lat": SILLIM["lat"], "lng": SILLIM["lng"]})
        arrival, suggestion = live.get("arrival") or {}, live.get("switch_suggestion")
        self.say(f"live route {(live.get('route') or {}).get('route_key')} · late {arrival.get('late_minutes')} min")
        if not arrival.get("late_minutes"):
            raise Failed("the live route was not judged late")
        if not suggestion:
            self.say("no earlier alternative right now (depends on Kakao and real-time arrivals); "
                     "the late judgement itself passed")
            return {"arrival": arrival, "switch_suggestion": None}
        self.say(f"\"지금 경로로는 {arrival['late_minutes']}분 늦어요 · {suggestion.get('label')}(으)로 가면 "
                 f"{suggestion.get('saves_minutes')}분 덜 늦어요\"")
        rr = self.api("POST", f"events/{eid}/reroute",
                      {"route_key": suggestion["route_key"], "reason": suggestion.get("reason")}, ok=(200, 201))
        self.say(f"rerouted for this trip → {suggestion['route_key']}")
        return {"arrival": arrival, "switch_suggestion": suggestion, "reroute": rr}

    def scene4(self):
        eid = self.event["id"]
        self.api("POST", "observations/batch", {"observations": [{
            "event": eid, "kind": "arrive", "detector": "manual",
            "observed_at": datetime.now(KST).isoformat(), "client_uuid": uuid.uuid4().hex}]}, ok=(200, 201))
        event = self.api("GET", f"events/{eid}")
        listed = self.api("GET", "observations", query={"event": eid, "kind": "arrive"})
        items = listed.get("results", listed) if isinstance(listed, dict) else listed
        manual = [o for o in items if o.get("detector") == "manual"]
        if not manual:
            raise Failed("the server has no manual arrival for the event")
        arrived = datetime.fromisoformat(manual[0]["observed_at"].replace("Z", "+00:00"))
        start = datetime.fromisoformat(event["start_at"].replace("Z", "+00:00"))
        late = max(0, round((arrived - start).total_seconds() / 60))
        self.say(f"\"도착했어요\" stored (detector=manual) · start {fmt(event['start_at'])} → "
                 + ("도착 완료 · 정시" if late == 0 else f"도착 완료 · {late}분 늦음"))
        return {"arrival": manual[0], "late_minutes": late}

    # -- run --------------------------------------------------------------------------------
    def run(self):
        code = 1
        try:
            if self.local:
                print(f"Starting a throwaway local server (logs: {self.out / 'backend.log'})", flush=True)
                self.start_server()
            self.setup()
            self.scene(1, "Faster route before the alarm", self.scene1)
            if hasattr(self, "event"):
                self.scene(2, "Edit the event and the route candidates", self.scene2)
                self.scene(3, "Reroute when late on the way", self.scene3)
                self.scene(4, "Arrival by hand", self.scene4)
            code = 0 if len(self.scenes) == 4 and all(s["status"] == "passed" for s in self.scenes) else 1
        except Failed as error:
            print(f"\nSetup failed: {error}", flush=True)
        finally:
            result = {"server": self.base, "local": self.local, "account": self.account["email"],
                      "scenes": self.scenes, "status": "PASSED" if code == 0 else "FAILED"}
            (self.out / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
            print(f"\n{result['status']} · {sum(s['status'] == 'passed' for s in self.scenes)}/4 scenes · "
                  f"{self.out / 'result.json'}", flush=True)
            if self.server and self.args.keep and code == 0:
                print(f"\nServer kept on port {self.args.port}. Log in from the app with\n"
                      f"  email    {self.account['email']}\n  password {self.account['password']}\n"
                      "Emulator: devServerHost=10.0.2.2 in APP/local.properties, then Run. Ctrl+C stops.",
                      flush=True)
                try:
                    self.server.wait()
                except KeyboardInterrupt:
                    pass
            if self.server:
                self.server.terminate()
        return code


def fmt(value):
    if not value:
        return "—"
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(KST).strftime("%m-%d %H:%M")
    except ValueError:
        return value


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server", help="run against this server instead of a local one")
    parser.add_argument("--port", type=int, default=8000, help="local server port (the app expects 8000)")
    parser.add_argument("--keep", action="store_true", help="keep the local server for the app after the run")
    args = parser.parse_args()
    if args.keep and args.server:
        parser.error("--keep only applies to the local server")
    sys.exit(Demo(args).run())


if __name__ == "__main__":
    main()
