"""Local-only reproduction of stored-alarm versus fresh recommended-clock drift.

The real Django views/planner run against a fresh, forced workspace SQLite DB.
Only external routing/map responses use the existing demo replay. Seeding uses
the real request pipeline in-process; serving exposes those same views to the QA
Android app at http://10.0.2.2:8765/. No public-server mode exists.
"""

from __future__ import annotations

import argparse
from datetime import datetime, time, timedelta
import json
import os
from pathlib import Path
import re
import runpy
import secrets
import sys
from uuid import uuid4
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
RUNS = ROOT / ".artifacts" / "alarm-time"
KST = ZoneInfo("Asia/Seoul")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def private_json(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write("\n")


def run_directory(run_id):
    require(bool(re.fullmatch(r"[A-Za-z0-9_-]{1,80}", run_id)), "Invalid local QA run id")
    require(RUNS.resolve().is_relative_to(ROOT), "QA artifacts must stay inside the workspace")
    path = (RUNS / run_id).resolve()
    require(path.is_relative_to(RUNS.resolve()), "QA run must stay inside its artifact directory")
    return path


def configure(path, secret):
    database = (path / "qa.sqlite3").resolve()
    require(database.is_relative_to(RUNS.resolve()), "Refusing a non-QA database")
    os.environ.update({
        "DATABASE_URL": "sqlite:///" + database.as_posix(),
        "DJANGO_SETTINGS_MODULE": "config.settings.dev",
        "DJANGO_SECRET_KEY": secret,
        "PYTHONUNBUFFERED": "1", "PYTHONUTF8": "1", "PYTHONIOENCODING": "utf-8",
        "KAKAO_REST_API_KEY": "", "KAKAO_MOBILITY_KEY": "", "KMA_API_KEY": "",
        "OPENAI_API_KEY": "", "FCM_CREDENTIALS_PATH": "", "REDIS_URL": "",
        "SEOUL_SUBWAY_API_KEY": "", "SEOUL_BUS_API_KEY_ENCODING": "",
        "SEOUL_BUS_API_KEY_DECODING": "",
    })
    replay = runpy.run_path(str(ROOT / "qa" / "demo" / "replay_backend.py"))
    from django.conf import settings

    config = settings.DATABASES["default"]
    require(config["ENGINE"] == "django.db.backends.sqlite3", "Refusing a non-SQLite database")
    require(Path(config["NAME"]).resolve() == database, "Django selected an unexpected database")
    return replay


def request(client, method, path, payload=None):
    kwargs = {"HTTP_HOST": "localhost", "HTTP_X_APP_VERSION": "0.18.0-qa"}
    if method in {"post", "put", "patch"}:
        kwargs["format"] = "json"
    response = getattr(client, method)(path, payload or {}, **kwargs)
    require(200 <= response.status_code < 300, f"Local QA API {method.upper()} {path}: HTTP {response.status_code}")
    return response.json()


def snapshot(client, event_id):
    event = request(client, "get", f"/api/events/{event_id}")
    options = request(client, "get", f"/api/events/{event_id}/risk-options")
    normal = next((row for row in options["options"] if row["key"] == "normal"), None)
    require(normal is not None, "The real model did not provide its normal option")
    plan = event["alarm_plan"]
    alarm = plan.get("effective_alarm_at") or plan["alarm_at"]
    normal_at = normal["alarm_at"]
    difference = (datetime.fromisoformat(normal_at) - datetime.fromisoformat(alarm)).total_seconds() / 60
    return {
        "event_id": event_id, "alarm_at": alarm, "normal_alarm_at": normal_at,
        "difference_minutes": difference, "tau_used": plan["tau_used"],
        "recommended_key": options["recommended_key"], "selected_key": options["selected_key"],
        "computed_at": plan.get("computed_at"),
    }


def seed(path):
    require(not path.exists(), "Use a new QA run id; existing runs are never overwritten")
    path.mkdir(parents=True)
    secret = secrets.token_urlsafe(40)
    private_json(path / "server.json", {"django_secret_key": secret})
    configure(path, secret)
    from django.contrib.auth import get_user_model
    from django.core.management import call_command
    from django.utils import timezone
    from rest_framework.test import APIClient
    from apps.common.cohort import production_users
    from apps.routing.models import RouteCorrection
    from apps.routines.models import RoutineBlock

    call_command("migrate", interactive=False, verbosity=0)
    fixture = json.loads((ROOT / "qa" / "demo" / "replay" / "route.json").read_text(encoding="utf-8"))
    home, destination = fixture["home"], fixture["transit_destination"]
    # Deliberately eligible for learning inside this isolated database only. A
    # qa-ui-/depcheck_ production fixture would correctly be filtered by N26 and
    # could not exercise the real learning update that caused this bug.
    email = f"alarm-time-fixture-{secrets.token_hex(5)}@example.test"
    password = "Local1-" + secrets.token_urlsafe(18)
    user = get_user_model().objects.create_user(email=email, password=password, nickname="알람 시각 QA")
    profile = user.profile
    profile.home_lat, profile.home_lng = home["lat"], home["lng"]
    profile.home_label, profile.onboarding_prep_min, profile.default_tau = home["name"], 30, .9
    profile.intro_seen_at = profile.coachmark_seen_at = timezone.now()
    profile.save()
    require(production_users().filter(pk=user.pk).exists(), "Isolated learning fixture unexpectedly excluded")
    block = RoutineBlock.objects.create(user=user, name="QA 준비", default_min_minutes=20, default_max_minutes=40)
    RouteCorrection.objects.create(route_signature="", mode="transit", factor=1, sd_minutes=8, sample_count=20)
    client = APIClient()
    client.force_authenticate(user)
    tomorrow = datetime.now(KST).date() + timedelta(days=1)
    start = datetime.combine(tomorrow, time(9), KST)
    candidates = request(client, "get", "/api/routes/candidates", {
        "dest_lat": destination["lat"], "dest_lng": destination["lng"],
        "arrive_by": start.isoformat(),
    })
    route = next(row for row in candidates["results"] if row["kind"] == "transit")
    created = request(client, "post", "/api/events", {
        "title": "알람 시각 일치 확인", "start_at": start.isoformat(), "route_key": route["key"],
        "place": {"name": destination["name"], "lat": destination["lat"], "lng": destination["lng"]},
    })
    event_id = created["id"]
    request(client, "put", f"/api/events/{event_id}/blocks", {"selections": [{"block": block.pk, "checked": True}]})
    baseline = snapshot(client, event_id)
    require(baseline["difference_minutes"] == 0, "Initial stored alarm and normal preview disagree")

    # The ordinary offline-upload path updates usable preparation observations.
    # Do not edit the saved plan, buffer, or correction after computing baseline.
    recorded = timezone.now().astimezone(KST) - timedelta(days=1)
    uploaded = request(client, "post", "/api/routines/observations/batch", {
        "observations": [{
            "block": block.pk, "event": None,
            "observed_on": (recorded - timedelta(days=index)).date().isoformat(),
            "duration_minutes": 60, "client_uuid": str(uuid4()),
            "client_recorded_at": (recorded - timedelta(days=index)).isoformat(),
        } for index in range(8)],
    })
    after = snapshot(client, event_id)
    config = {
        "email": email, "password": password, "event_id": event_id,
        "event_date": tomorrow.isoformat(), "event_time": "09:00",
        "api_url": "http://10.0.2.2:8765/", "nickname": "알람 시각 QA",
    }
    private_json(path / "config.json", config)
    summary = {
        "isolated_synthetic_fixture": True, "external_response_mode": "routing_and_map_replay",
        "baseline": baseline, "after_observation_upload": after,
        "accepted_observations": uploaded.get("accepted"),
        "stored_alarm_unchanged": baseline["alarm_at"] == after["alarm_at"],
        "stored_plan_computed_at_unchanged": baseline["computed_at"] == after["computed_at"],
    }
    (path / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "event_id": event_id, "baseline_difference_minutes": baseline["difference_minutes"],
        "after_upload_difference_minutes": after["difference_minutes"],
        "accepted_observations": uploaded.get("accepted"),
    }))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--seed", action="store_true")
    mode.add_argument("--serve", action="store_true")
    parser.add_argument("--run-id", required=True, help="Local artifact name, e.g. recommendation-before")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    require(1 <= args.port <= 65535, "Invalid local server port")
    path = run_directory(args.run_id)
    if args.seed:
        seed(path)
    else:
        require((path / "qa.sqlite3").is_file(), "Seed this local QA run first")
        secret = json.loads((path / "server.json").read_text(encoding="utf-8"))["django_secret_key"]
        replay = configure(path, secret)
        replay["serve"](args.port)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, StopIteration):
        # Credentials and request bodies stay in ignored files, even on failure.
        print("Local alarm-time QA failed; inspect the local artifacts and source.", file=sys.stderr)
        raise SystemExit(1) from None
