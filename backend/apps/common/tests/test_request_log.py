# Partly AI-generated (Claude)
"""API 요청 기록(db-schema.md A4)과 보존(§9-2).

지키는 것 세 가지.

1. **비밀과 좌표는 남지 않는다.** 비밀번호·토큰·키는 값을 지우고, 이동 중 위치·경로
   좌표는 표시만 남긴다(N19). 인증 경로는 본문을 아예 읽지 않는다.
2. **본문은 허용 목록만.** 목록에 없는 경로·메서드는 메타만 남는다.
3. **기록이 응답을 바꾸지 않는다.** DB 오류가 나도 응답은 그대로 나간다.

테스트 설정은 기록을 꺼 둔다(`config/settings/test.py`). 여기서만 켠다.
"""

from __future__ import annotations

import io
import json
from datetime import datetime, timedelta, timezone

import pytest
from django.core.management import call_command
from django.db import DatabaseError
from django.utils import timezone as dj_timezone
from rest_framework.test import APIClient

from apps.common import request_log, retention
from apps.common.models import ApiRequestLog
from apps.events.models import Event, Place
from apps.planning import services
from apps.planning.models import AlarmPlan
from apps.reports.models import LlmCall

KST = timezone(timedelta(hours=9))
PASSWORD = "Qx8-velvet-harbor-47"
ROUTE = {
    "minutes": 20, "mode": "transit", "source": "kakao", "summary": "2호선 · 20분",
    "key": "transit:2호선", "detail": "2호선", "path": [[37.48, 126.93], [37.46, 126.95]],
}


class TestSanitize:
    def test_secrets_lose_their_values(self):
        clean = request_log.sanitize(
            {
                "password": "p", "password_confirm": "p", "new_password": "p",
                "token": "t", "access": "a", "refresh": "r", "Authorization": "Bearer x",
                "api_key": "k", "kakao_api_key": "k", "client_secret": "s",
            }
        )
        assert set(clean.values()) == {request_log.REDACTED}

    def test_choice_keys_are_not_secrets(self):
        """`route_key`·`tag_key` 는 사용자가 고른 경로·종류다. 남기려는 값이다."""
        clean = request_log.sanitize({"route_key": "transit:2호선", "tag_key": "class"})
        assert clean == {"route_key": "transit:2호선", "tag_key": "class"}

    def test_coordinates_become_markers(self):
        clean = request_log.sanitize(
            {
                "lat": 37.5, "lng": 127.0, "origin_lat": 37.4, "home_lng": 126.9,
                "x": "127.0", "y": "37.5", "start_x": "127.0", "end_y": "37.4",
                "route_path": [[37.4, 126.9], [37.5, 127.0]], "alt_route_path": [],
                "path": {"points": [[127.0, 37.5]]}, "origin_label": "집",
                "distance_m": 160.0, "accuracy_m": 12.0,
            }
        )
        assert clean["lat"] == clean["lng"] == clean["origin_lat"] == request_log.COORDS
        assert clean["home_lng"] == clean["x"] == clean["start_x"] == request_log.COORDS
        assert clean["route_path"] == "[coords:2]"
        assert clean["alt_route_path"] == "[coords:0]"
        assert clean["path"] == request_log.COORDS
        # 파생값과 이름표는 그대로다.
        assert (clean["origin_label"], clean["distance_m"], clean["accuracy_m"]) == ("집", 160.0, 12.0)
        assert "37.5" not in json.dumps(clean)

    def test_nested_values_are_cleaned(self):
        clean = request_log.sanitize(
            {"observations": [{"lat": 37.5, "lng": 127.0, "kind": "arrive"}],
             "place": {"name": "302동", "lat": 37.44, "lng": 126.95}}
        )
        assert clean["observations"][0] == {"lat": "[coords]", "lng": "[coords]", "kind": "arrive"}
        assert clean["place"]["name"] == "302동"
        assert clean["place"]["lat"] == "[coords]"

    def test_null_coordinate_stays_null(self):
        """"출발지 없음(집)" 도 뜻이 있다."""
        assert request_log.sanitize({"origin_lat": None}) == {"origin_lat": None}

    def test_non_json_values_are_safe(self):
        clean = request_log.sanitize({"nan": float("nan"), "when": datetime(2026, 10, 5)})
        json.dumps(clean, allow_nan=False)

    def test_deep_nesting_stops(self):
        value: dict = {}
        cur = value
        for _ in range(request_log.MAX_DEPTH + 5):
            cur["a"] = {}
            cur = cur["a"]
        assert request_log.TOO_DEEP in json.dumps(request_log.sanitize(value))


class TestBounded:
    def test_small_values_are_kept(self):
        assert request_log.bounded({"a": 1}, 100) == {"a": 1}

    def test_large_values_are_cut(self):
        big = {"title": "가" * 5000}
        cut = request_log.bounded(big, request_log.REQUEST_LIMIT_BYTES)
        assert cut["_truncated"] is True
        assert cut["_bytes"] > request_log.REQUEST_LIMIT_BYTES
        assert len(cut["preview"].encode("utf-8")) <= request_log.REQUEST_LIMIT_BYTES


class TestWhatIsLogged:
    @pytest.mark.parametrize(
        "path, expected",
        [
            ("/api/events", True),
            ("/api/health", False),
            ("/api/places/staticmap", False),
            ("/admin/", False),
            ("/static/app.css", False),
        ],
    )
    def test_should_log(self, path, expected):
        assert request_log.should_log(path) is expected

    def test_live_route_responses_are_not_kept(self):
        """경로 갱신 응답은 1분마다 쌓이면 이동 경과가 된다(N19)."""
        assert ("POST", "api/routes/live") not in request_log.RESPONSE_BODY_ROUTES

    def test_app_version_is_trimmed(self):
        class Req:
            headers = {"X-App-Version": "0.12.0 (12)\n<script>" + "9" * 40}

        version = request_log.app_version(Req())
        assert version.startswith("0.12.0 (12)")
        assert "<" not in version and "\n" not in version
        assert len(version) <= 32


# ---------------------------------------------------------------------------
# 미들웨어 — DB 가 필요하다
# ---------------------------------------------------------------------------


@pytest.fixture
def logging_on(settings, monkeypatch):
    settings.API_REQUEST_LOG_ENABLED = True
    # 하루 한 번 지우기가 이 테스트들 사이에서 돌지 않게 오늘은 이미 지운 것으로 둔다.
    monkeypatch.setattr(retention, "_last_purged_on", dj_timezone.localdate())


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="reqlog@example.com", password=PASSWORD, nickname="기록"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.onboarding_prep_min = 30
    p.save()
    return u


@pytest.fixture
def client(user):
    c = APIClient()
    c.force_authenticate(user=user)
    return c


@pytest.fixture
def stub_route(monkeypatch):
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(ROUTE), False))
    monkeypatch.setattr(
        services.clients, "resolve_route", lambda key, **kw: (dict(ROUTE, key=key), False)
    )


def only_row() -> ApiRequestLog:
    rows = list(ApiRequestLog.objects.all())
    assert len(rows) == 1, [(r.method, r.route) for r in rows]
    return rows[0]

FUTURE = "2027-03-02T09:00:00+09:00"
EVENT_BODY = {
    "title": "수업", "start_at": FUTURE,
    "place": {"name": "302동", "lat": 37.4486, "lng": 126.9526},
    "origin_lat": 37.4701, "origin_lng": 126.9402, "origin_label": "카페",
}
# 요청·응답 어디에도 나오면 안 되는 좌표 문자열(목적지·출발지·집·경로 점).
COORD_TEXTS = ("37.4486", "126.9526", "37.4701", "126.9402", "37.4842", "126.9297", "37.48", "126.93")


def assert_no_coordinates(row: ApiRequestLog) -> None:
    dumped = json.dumps([row.query, row.request_body, row.response_body], ensure_ascii=False)
    leaked = [text for text in COORD_TEXTS if text in dumped]
    assert not leaked, leaked


@pytest.mark.django_db
@pytest.mark.usefixtures("logging_on")
class TestMiddleware:
    def test_event_create_keeps_clean_bodies(self, client, user, stub_route):
        res = client.post("/api/events", EVENT_BODY, format="json", HTTP_X_APP_VERSION="0.12.0")
        assert res.status_code == 201

        row = only_row()
        assert (row.user_id, row.method, row.route, row.status_code) == (user.pk, "POST", "api/events", 201)
        assert row.app_version == "0.12.0"
        assert row.request_body["title"] == "수업"
        assert row.request_body["origin_lat"] == request_log.COORDS
        assert row.request_body["place"] == {"name": "302동", "lat": "[coords]", "lng": "[coords]"}
        # 그 결과 계획이 남는다(경로 좌표는 점 개수만).
        assert row.response_body["id"] == res.data["id"]
        assert row.duration_ms >= 0
        assert row.error_code == ""
        assert_no_coordinates(row)

    def test_route_is_the_pattern_and_keeps_the_id(self, client, user, stub_route):
        event = Event.objects.create(user=user, title="수업", start_at=datetime(2027, 3, 2, 9, tzinfo=KST))
        assert client.post(f"/api/events/{event.pk}/recompute").status_code == 200
        row = only_row()
        assert row.route == "api/events/<int:pk>/recompute"
        assert row.object_id == event.pk
        assert row.response_body["id"] == event.pk

    def test_auth_routes_keep_meta_only(self, user):
        res = APIClient().post(
            "/api/auth/token", {"email": user.email, "password": PASSWORD}, format="json"
        )
        assert res.status_code == 200

        row = only_row()
        assert row.route == "api/auth/token"
        assert (row.query, row.request_body, row.response_body) == ({}, {}, {})
        dumped = json.dumps([row.query, row.request_body, row.response_body, row.error_code])
        assert PASSWORD not in dumped
        assert res.data["access"] not in dumped

    def test_anonymous_request(self):
        assert APIClient().get("/api/events").status_code == 401
        row = only_row()
        assert row.user_id is None
        assert row.status_code == 401
        assert row.error_code == "not_authenticated"

    @pytest.mark.parametrize("path", ["/api/health", "/admin/login/"])
    def test_excluded_paths(self, client, path):
        client.get(path)
        assert not ApiRequestLog.objects.exists()

    def test_live_location_is_not_kept(self, client, user, stub_route):
        place = Place.objects.create(name="302동", lat=37.4486, lng=126.9526)
        event = Event.objects.create(
            user=user, place=place, title="수업", start_at=datetime(2027, 3, 2, 9, tzinfo=KST)
        )
        # 이동 중 경로는 계획이 쓴 경로 key 만 본다. 상태는 상관없다(OK 는 시각 값이 필요하다).
        AlarmPlan.objects.create(
            user=user, event=event, status=AlarmPlan.Status.ROUTE_FAILED, route_key="transit:2호선"
        )
        res = client.post(
            "/api/routes/live", {"event_id": event.pk, "lat": 37.4701, "lng": 126.9402}, format="json"
        )
        assert res.status_code == 200

        row = only_row()
        assert row.request_body == {"event_id": event.pk, "lat": "[coords]", "lng": "[coords]"}
        assert row.response_body == {}  # 경로 갱신 응답은 남기지 않는다
        assert_no_coordinates(row)

    def test_queries_are_cleaned(self, client, stub_route):
        client.get("/api/routes/candidates?origin_lat=37.4701&origin_lng=126.9402&dest_lat=37.4486")
        row = only_row()
        assert row.query["origin_lat"] == row.query["dest_lat"] == request_log.COORDS
        assert_no_coordinates(row)

    def test_unlisted_routes_keep_meta_only(self, client):
        assert client.get("/api/reports/weekly").status_code == 200
        row = only_row()
        assert row.route == "api/reports/weekly"
        assert row.response_body == {}

    def test_large_bodies_are_cut(self, client, user):
        event = Event.objects.create(user=user, title="수업", start_at=datetime(2027, 3, 2, 9, tzinfo=KST))
        observations = [
            {"event": event.pk, "kind": "depart", "detector": "gps",
             "observed_at": "2027-03-02T08:20:00+09:00", "lat": 37.48, "lng": 126.93,
             "accuracy_m": 10.0, "distance_m": 160.0, "client_uuid": f"cut-{i}"}
            for i in range(80)
        ]
        client.post("/api/observations/batch", {"observations": observations}, format="json")

        row = only_row()
        assert row.request_body["_truncated"] is True
        assert len(row.request_body["preview"].encode("utf-8")) <= request_log.REQUEST_LIMIT_BYTES
        assert_no_coordinates(row)

    def test_log_failure_keeps_the_response(self, client, stub_route, monkeypatch):
        def broken(self, *args, **kwargs):
            raise DatabaseError("no such table")

        monkeypatch.setattr(ApiRequestLog, "save", broken)
        res = client.post("/api/events", EVENT_BODY, format="json")

        assert res.status_code == 201
        assert Event.objects.filter(pk=res.data["id"]).exists()
        assert not ApiRequestLog.objects.exists()

    def test_switch_off(self, client, settings):
        settings.API_REQUEST_LOG_ENABLED = False
        client.get("/api/events")
        assert not ApiRequestLog.objects.exists()

    def test_deleting_the_user_deletes_their_rows(self, client, user):
        client.get("/api/events")
        assert ApiRequestLog.objects.filter(user=user).exists()
        user.delete()
        assert not ApiRequestLog.objects.exists()

@pytest.mark.django_db
class TestRetention:
    """보존 기간(§9-2). 요청 기록 30일, LLM 호출 90일. 학습 재료는 지우지 않는다."""

    def aged(self, model, days, **fields):
        row = model.objects.create(**fields)
        model.objects.filter(pk=row.pk).update(created_at=dj_timezone.now() - timedelta(days=days))
        return row

    def request_row(self, days):
        return self.aged(
            ApiRequestLog, days, method="GET", route="api/events", status_code=200, duration_ms=3
        )

    def llm_row(self, user, days):
        today = dj_timezone.localdate()
        return self.aged(LlmCall, days, user=user, day=today, week_start=today, outcome="ok")

    def test_purge_drops_only_old_rows(self, user):
        old_req, new_req = self.request_row(31), self.request_row(29)
        old_llm, new_llm = self.llm_row(user, 91), self.llm_row(user, 89)

        assert retention.purge() == {"api_request_log": 1, "llm_call": 1}
        assert list(ApiRequestLog.objects.values_list("pk", flat=True)) == [new_req.pk]
        assert list(LlmCall.objects.values_list("pk", flat=True)) == [new_llm.pk]
        assert old_req.pk != new_req.pk and old_llm.pk != new_llm.pk

    def test_learning_records_are_kept(self, user):
        from apps.observations.models import AlarmLog

        log = self.aged(
            AlarmLog, 400, user=user, kind="fired", occurred_at=dj_timezone.now(), client_uuid="keep"
        )
        retention.purge()
        assert AlarmLog.objects.filter(pk=log.pk).exists()

    def test_zero_days_keeps_everything(self, user):
        self.request_row(400)
        self.llm_row(user, 400)
        assert retention.purge(request_days=0, llm_days=0) == {"api_request_log": 0, "llm_call": 0}
        assert ApiRequestLog.objects.count() == LlmCall.objects.count() == 1

    def test_settings_choose_the_window(self, settings):
        settings.API_REQUEST_LOG_RETENTION_DAYS = 14
        self.request_row(20)
        assert retention.purge()["api_request_log"] == 1

    def test_runs_once_a_day_per_process(self, monkeypatch):
        monkeypatch.setattr(retention, "_last_purged_on", None)
        today = dj_timezone.localdate()
        assert retention.maybe_purge(today=today) is not None
        assert retention.maybe_purge(today=today) is None
        assert retention.maybe_purge(today=today + timedelta(days=1)) is not None

    def test_first_logged_request_of_the_day_purges(self, client, settings, monkeypatch):
        settings.API_REQUEST_LOG_ENABLED = True
        monkeypatch.setattr(retention, "_last_purged_on", None)
        old = self.request_row(40)

        client.get("/api/events")

        assert not ApiRequestLog.objects.filter(pk=old.pk).exists()
        assert ApiRequestLog.objects.count() == 1  # 방금 요청한 행

    def test_command(self):
        self.request_row(20)
        out = io.StringIO()
        call_command("purge_logs", "--request-days", "14", stdout=out)
        assert "API 요청 기록 1건" in out.getvalue()
        assert not ApiRequestLog.objects.exists()