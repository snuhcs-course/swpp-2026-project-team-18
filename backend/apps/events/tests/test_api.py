"""일정 API 테스트.

`scripts/check_events_api.py` 가 살아 있는 서버를 확인한다면, 이쪽은 **서버 없이**
계약을 고정한다. 특히 시각 변환과 경로 선택 존중 여부처럼 조용히 틀어지기 쉬운
부분을 잡는다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from rest_framework.test import APIClient

from apps.events.models import Event, EventTag, Place
from apps.planning.models import AlarmPlan

KST = timezone(timedelta(hours=9))
UTC = timezone.utc
pytestmark = pytest.mark.django_db

ROUTE = {
    "minutes": 20, "mode": "transit", "source": "kakao",
    "summary": "2호선 → 5513 · 20분", "key": "transit:2호선>5513", "detail": "2호선 → 5513",
}


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="ev@example.com", password="Dq2-hollow-timber-58", nickname="일정"
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
    from apps.planning import services

    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(ROUTE), False))
    monkeypatch.setattr(
        services.clients, "resolve_route", lambda key, **kw: (dict(ROUTE, key=key), False)
    )


PLACE = {
    "name": "서울대학교 관악캠퍼스",
    "lat": 37.459786,
    "lng": 126.951124,
    "address": "서울 관악구 관악로 1",
    "kakao_place_id": "11137036",
}


class TestCreate:
    def test_creates_with_plan(self, client, stub_route):
        res = client.post(
            "/api/events",
            {"title": "수업", "start_at": "2026-10-05T09:00:00+09:00",
             "place": PLACE, "tag_key": "class"},
            format="json",
        )
        assert res.status_code == 201
        assert res.data["alarm_plan"]["status"] == "ok"
        assert res.data["title"] == "수업"

    def test_stores_utc_and_returns_the_same_instant(self, client, stub_route):
        """**타임존이 조용히 틀어지면 알람이 9시간 어긋난다.**

        KST 09:00 은 UTC 00:00 이다. DB 에는 UTC 로 저장하고 응답은 같은
        순간을 가리켜야 한다(표현은 달라도 된다).
        """
        res = client.post(
            "/api/events",
            {"title": "타임존", "start_at": "2026-10-05T09:00:00+09:00", "place": PLACE},
            format="json",
        )
        assert res.status_code == 201
        event = Event.objects.get(pk=res.data["id"])
        assert event.start_at.astimezone(UTC) == datetime(2026, 10, 5, 0, 0, tzinfo=UTC)
        assert event.start_at.astimezone(KST).hour == 9

    def test_reuses_place_by_kakao_id(self, client, stub_route):
        """같은 장소를 두 사람이 저장해도 행이 하나여야 한다."""
        for i in range(2):
            client.post(
                "/api/events",
                {"title": f"일정{i}", "start_at": f"2026-10-0{5 + i}T09:00:00+09:00",
                 "place": PLACE},
                format="json",
            )
        assert Place.objects.filter(kakao_place_id="11137036").count() == 1

    def test_place_is_optional(self, client, stub_route):
        """장소를 몰라도 일정은 적어둘 수 있어야 한다."""
        res = client.post(
            "/api/events",
            {"title": "장소 미정", "start_at": "2026-10-05T09:00:00+09:00"},
            format="json",
        )
        assert res.status_code == 201
        assert res.data["alarm_plan"]["status"] == "no_place"

    def test_unknown_tag_is_rejected(self, client, stub_route):
        res = client.post(
            "/api/events",
            {"title": "x", "start_at": "2026-10-05T09:00:00+09:00",
             "tag_key": "없는태그"},
            format="json",
        )
        assert res.status_code == 400

    def test_half_origin_is_rejected(self, client, stub_route):
        """위도만 보내면 계산기가 조용히 집으로 돌아간다. DB 제약 전에 막는다."""
        res = client.post(
            "/api/events",
            {"title": "반쪽", "start_at": "2026-10-05T09:00:00+09:00",
             "place": PLACE, "origin_lat": 37.5},
            format="json",
        )
        assert res.status_code == 400

    def test_foreign_origin_is_rejected(self, client, stub_route):
        """국내 좌표만 받는다. 스펙 5.3.1 의 대체 방어다.

        **이 검사가 빠져 있었다.** `/api/routes/candidates` 는 막고 있었지만
        일정 생성에는 없어서 파리 좌표가 201 로 저장됐다. 그러면 알람 계산이
        국외 좌표로 카카오를 불러 쿼터를 쓰고 실패한다. 규칙을
        `apps.events.geo.in_service_area` 한 곳으로 모아 양쪽이 쓰게 했다.
        """
        res = client.post(
            "/api/events",
            {"title": "파리", "start_at": "2026-10-05T09:00:00+09:00",
             "place": PLACE, "origin_lat": 48.8584, "origin_lng": 2.2945},
            format="json",
        )
        assert res.status_code == 400

    def test_foreign_origin_is_rejected_on_patch_too(self, client, stub_route):
        """생성만 막고 수정을 빼먹으면 두 번째 요청으로 우회된다."""
        res = client.post(
            "/api/events",
            {"title": "정상", "start_at": "2026-10-05T09:00:00+09:00", "place": PLACE},
            format="json",
        )
        event_id = res.data["id"]
        res = client.patch(
            f"/api/events/{event_id}",
            {"origin_lat": 48.8584, "origin_lng": 2.2945},
            format="json",
        )
        assert res.status_code == 400
        assert Event.objects.get(pk=event_id).origin_lat is None

    @pytest.mark.parametrize(
        "lat,lng,label",
        [
            (33.06, 126.27, "마라도 (국내 최남단)"),
            (37.24, 131.87, "독도 (국내 최동단)"),
            (38.61, 128.35, "고성 (국내 최북단 근처)"),
        ],
    )
    def test_domestic_edges_are_allowed(self, client, stub_route, lat, lng, label):
        """범위를 너무 좁게 잡으면 실제 국내 좌표가 막힌다."""
        res = client.post(
            "/api/events",
            {"title": label, "start_at": "2026-10-05T09:00:00+09:00",
             "place": PLACE, "origin_lat": lat, "origin_lng": lng},
            format="json",
        )
        assert res.status_code == 201, f"{label} 이 막혔다"


class TestRouteChoiceHonored:
    """사용자가 고른 경로가 그대로 쓰였는지 화면이 알아야 한다."""

    def test_none_when_not_chosen(self, client, stub_route):
        res = client.post(
            "/api/events",
            {"title": "미선택", "start_at": "2026-10-05T09:00:00+09:00", "place": PLACE},
            format="json",
        )
        assert res.data["alarm_plan"]["route_choice_honored"] is None

    def test_true_when_choice_survives(self, client, stub_route):
        res = client.post(
            "/api/events",
            {"title": "선택", "start_at": "2026-10-05T09:00:00+09:00",
             "place": PLACE, "route_key": ROUTE["key"]},
            format="json",
        )
        assert res.data["alarm_plan"]["route_choice_honored"] is True

    def test_false_when_server_substituted(self, client, monkeypatch):
        """배차가 바뀌어 고른 경로가 사라지면 서버가 대체한다.

        그때 `true` 로 보고하면 사용자를 속인다.
        """
        from apps.planning import services

        substitute = dict(ROUTE, key="walk")
        monkeypatch.setattr(
            services.clients, "resolve_route", lambda key, **kw: (substitute, False)
        )
        monkeypatch.setattr(
            services.clients, "best_route", lambda **kw: (substitute, False)
        )
        res = client.post(
            "/api/events",
            {"title": "대체됨", "start_at": "2026-10-05T09:00:00+09:00",
             "place": PLACE, "route_key": "transit:사라진노선"},
            format="json",
        )
        assert res.data["alarm_plan"]["route_choice_honored"] is False


class TestUpdateAndRecompute:
    @pytest.fixture
    def event_id(self, client, stub_route):
        res = client.post(
            "/api/events",
            {"title": "원본", "start_at": "2026-10-05T09:00:00+09:00", "place": PLACE},
            format="json",
        )
        return res.data["id"]

    def test_changing_time_moves_the_alarm(self, client, stub_route, event_id):
        before = AlarmPlan.objects.get(event_id=event_id).alarm_at
        res = client.patch(
            f"/api/events/{event_id}",
            {"start_at": "2026-10-05T14:00:00+09:00"},
            format="json",
        )
        assert res.status_code == 200
        after = AlarmPlan.objects.get(event_id=event_id).alarm_at
        assert after - before == timedelta(hours=5)

    def test_removing_place_clears_the_plan(self, client, stub_route, event_id):
        event = Event.objects.get(pk=event_id)
        event.place = None
        event.save()
        res = client.post(f"/api/events/{event_id}/recompute")
        assert res.status_code == 200
        plan = AlarmPlan.objects.get(event_id=event_id)
        assert plan.status == AlarmPlan.Status.NO_PLACE
        assert plan.alarm_at is None

    def test_delete_removes_the_plan_too(self, client, stub_route, event_id):
        assert client.delete(f"/api/events/{event_id}").status_code == 204
        assert not AlarmPlan.objects.filter(event_id=event_id).exists()


class TestListFiltering:
    def test_from_and_to_narrow_the_range(self, client, stub_route):
        for day in (3, 5, 7):
            client.post(
                "/api/events",
                {"title": f"{day}일", "start_at": f"2026-10-0{day}T09:00:00+09:00",
                 "place": PLACE},
                format="json",
            )
        res = client.get("/api/events?from=2026-10-04T00:00:00%2B09:00"
                         "&to=2026-10-06T00:00:00%2B09:00")
        assert res.status_code == 200
        titles = [e["title"] for e in res.data["results"]]
        assert titles == ["5일"]

    def test_garbage_filter_is_ignored_not_fatal(self, client, stub_route):
        res = client.get("/api/events?from=쓰레기&to=also-garbage")
        assert res.status_code == 200


class TestTags:
    def test_seeded_tags_are_returned(self, client):
        res = client.get("/api/events/tags")
        assert res.status_code == 200
        keys = {t["key"] for t in res.data}
        assert keys == {"class", "exam", "presentation", "train", "parttime", "meetup"}

    def test_tau_reflects_penalty_shape(self, client):
        """`step`(기차·시험)은 높은 τ, `linear`(수업)은 낮은 τ."""
        res = client.get("/api/events/tags")
        by_key = {t["key"]: t for t in res.data}
        assert by_key["train"]["default_tau"] > by_key["meetup"]["default_tau"]
        assert by_key["exam"]["penalty_shape"] == "step"
        assert by_key["class"]["penalty_shape"] == "linear"


class TestEffectiveTau:
    def test_priority_is_override_then_tag_then_profile(self, user, stub_route):
        place = Place.objects.create(name="목적지", lat=37.46, lng=126.95)
        tag = EventTag.objects.get(key="meetup")  # 0.85
        profile = user.profile
        profile.default_tau = 0.70
        profile.save()

        bare = Event.objects.create(
            user=user, place=place, title="태그 없음",
            start_at=datetime(2026, 10, 5, 9, 0, tzinfo=KST),
        )
        tagged = Event.objects.create(
            user=user, place=place, title="태그", tag=tag,
            start_at=datetime(2026, 10, 6, 9, 0, tzinfo=KST),
        )
        overridden = Event.objects.create(
            user=user, place=place, title="재지정", tag=tag, tau_override=0.95,
            start_at=datetime(2026, 10, 7, 9, 0, tzinfo=KST),
        )
        assert bare.effective_tau == pytest.approx(0.70)
        assert tagged.effective_tau == pytest.approx(0.85)
        assert overridden.effective_tau == pytest.approx(0.95)
