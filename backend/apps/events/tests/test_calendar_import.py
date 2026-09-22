"""캘린더 가져오기 테스트.

## 무엇을 지키는가

동기화는 **반복 실행되는 것이 정상**이다. 그래서 두 가지가 깨지면 바로 사고가 된다.

1. **멱등성** — 같은 요청을 다시 보내면 행이 늘지 않아야 한다. 안 그러면 앱을
   열 때마다 목록이 사본으로 찬다.
2. **변경 없으면 재계산 안 함** — 재계산은 카카오 경로 API 를 한 번 부른다.
   무조건 돌면 일정 20건짜리 계정이 앱을 한 번 열 때마다 20콜을 쓴다. 무료
   쿼터는 하루 1,000건이다.

여기에 "사용자가 앱에서 고친 값을 덮어쓰지 않는다" 를 더한다. 캘린더에는
경로·출발지 정보가 없으므로 그 필드를 함께 밀면 사용자 설정이 동기화 한 번에
사라진다.
"""

from __future__ import annotations

from datetime import timedelta

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event

pytestmark = pytest.mark.django_db

ROUTE = {
    "minutes": 20,
    "mode": "transit",
    "source": "kakao",
    "summary": "2호선 → 5513 · 20분",
    "key": "transit:2호선>5513",
    "detail": "2호선 → 5513",
}

PLACE = {
    "name": "서울대학교 관악캠퍼스",
    "lat": 37.459786,
    "lng": 126.951124,
    "address": "서울 관악구 관악로 1",
    "kakao_place_id": "11137036",
}

OTHER_PLACE = {
    "name": "강남역",
    "lat": 37.497942,
    "lng": 127.027621,
    "address": "서울 강남구",
    "kakao_place_id": "21160735",
}


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="cal@example.com", password="Kv7-marble-thicket-31", nickname="캘린더"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.onboarding_prep_min = 30
    p.save()
    return u


@pytest.fixture
def other(django_user_model):
    return django_user_model.objects.create_user(
        email="cal2@example.com", password="Rt5-copper-meadow-92", nickname="남"
    )


@pytest.fixture
def client(user):
    c = APIClient()
    c.force_authenticate(user=user)
    return c


@pytest.fixture
def route_calls(monkeypatch):
    """경로 조회 횟수를 센다. 쿼터 소모를 눈으로 확인하는 장치다."""
    from apps.planning import services

    counter = {"best": 0, "resolve": 0}

    def best_route(**kw):
        counter["best"] += 1
        return dict(ROUTE), False

    def resolve_route(key, **kw):
        counter["resolve"] += 1
        return dict(ROUTE, key=key), False

    monkeypatch.setattr(services.clients, "best_route", best_route)
    monkeypatch.setattr(services.clients, "resolve_route", resolve_route)
    return counter

    
def future(hours: int = 48) -> str:
    return (timezone.now() + timedelta(hours=hours)).isoformat()


def row(external_id: str, **over) -> dict:
    base = {
        "external_id": external_id,
        "title": f"일정 {external_id}",
        "start_at": future(),
        "place": PLACE,
    }
    base.update(over)
    return base


class TestImportBasics:

    def test_creates_events(self, client, route_calls):
        res = client.post(
            "/api/events/import",
            {"events": [row("cal-1"), row("cal-2")]},
            format="json",
        )
        assert res.status_code == 200, res.data
        assert res.data["created"] == 2
        assert res.data["updated"] == 0
        assert res.data["unchanged"] == 0
        assert Event.objects.count() == 2

    def test_marks_source_and_external_id(self, client, route_calls):
        client.post("/api/events/import", {"events": [row("cal-1")]}, format="json")
        event = Event.objects.get()
        assert event.source == Event.Source.CALENDAR
        assert event.external_id == "cal-1"

    def test_response_exposes_external_id(self, client, route_calls):
        """클라이언트가 '이미 가져온 일정' 을 알아야 중복 선택을 막을 수 있다."""
        res = client.post("/api/events/import", {"events": [row("cal-1")]}, format="json")
        assert res.data["results"][0]["external_id"] == "cal-1"
        assert res.data["results"][0]["source"] == "calendar"

    def test_computes_plan_for_new_events(self, client, route_calls):
        res = client.post("/api/events/import", {"events": [row("cal-1")]}, format="json")
        assert res.data["results"][0]["alarm_plan"]["status"] == "ok"
        assert res.data["recomputed"] == 1

    def test_requires_authentication(self, route_calls):
        res = APIClient().post(
            "/api/events/import", {"events": [row("cal-1")]}, format="json"
        )
        assert res.status_code == 401


class TestIdempotency:

    def test_second_import_does_not_duplicate(self, client, route_calls):
        payload = {"events": [row("cal-1"), row("cal-2")]}
        client.post("/api/events/import", payload, format="json")
        res = client.post("/api/events/import", payload, format="json")

        assert res.status_code == 200
        assert res.data["created"] == 0
        assert res.data["unchanged"] == 2
        assert Event.objects.count() == 2

    def test_unchanged_import_costs_no_route_call(self, client, route_calls):
        """**쿼터를 지키는 핵심 규칙이다.**

        앱이 열릴 때마다 동기화하는데 무조건 재계산하면 일정 20건짜리 계정이
        한 번 열 때마다 카카오 20콜을 쓴다. 무료 쿼터는 하루 1,000건이다.
        """
        payload = {"events": [row("cal-1"), row("cal-2"), row("cal-3")]}
        client.post("/api/events/import", payload, format="json")
        after_first = route_calls["best"] + route_calls["resolve"]
        assert after_first == 3, "새 일정 3건이면 3콜이어야 한다"

        res = client.post("/api/events/import", payload, format="json")
        assert res.data["recomputed"] == 0
        assert route_calls["best"] + route_calls["resolve"] == after_first, (
            "변경이 없는데 경로를 다시 조회했다"
        )

    def test_same_external_id_twice_in_one_request_is_rejected(self, client, route_calls):
        """한 요청 안의 중복은 순서에 따라 결과가 달라진다. 400 으로 막는다."""
        res = client.post(
            "/api/events/import",
            {"events": [row("cal-1"), row("cal-1", title="다른 제목")]},
            format="json",
        )
        assert res.status_code == 400
        assert Event.objects.count() == 0


class TestChangeDetection:
    """변경 판정.

    시각을 **고정해서** 보낸다. `future()` 를 두 번 부르면 마이크로초가 달라져
    무엇을 검사하는 테스트인지 흐려진다.
    """

    def test_time_change_recomputes(self, client, route_calls):
        client.post(
            "/api/events/import", {"events": [row("cal-1", start_at=future(48))]}, format="json"
        )
        before = route_calls["best"] + route_calls["resolve"]

        res = client.post(
            "/api/events/import",
            {"events": [row("cal-1", start_at=future(hours=72))]},
            format="json",
        )
        assert res.data["updated"] == 1
        assert res.data["recomputed"] == 1
        assert route_calls["best"] + route_calls["resolve"] == before + 1

    def test_place_change_recomputes(self, client, route_calls):
        at = future(48)
        client.post("/api/events/import", {"events": [row("cal-1", start_at=at)]}, format="json")
        before = route_calls["best"] + route_calls["resolve"]

        res = client.post(
            "/api/events/import",
            {"events": [row("cal-1", start_at=at, place=OTHER_PLACE)]},
            format="json",
        )
        assert res.data["updated"] == 1
        assert route_calls["best"] + route_calls["resolve"] == before + 1

    def test_title_only_change_updates_without_recompute(self, client, route_calls):
        """제목은 계획을 바꾸지 않는다. 갱신은 하고 경로는 부르지 않는다."""
        at = future(48)
        client.post("/api/events/import", {"events": [row("cal-1", start_at=at)]}, format="json")
        before = route_calls["best"] + route_calls["resolve"]

        res = client.post(
            "/api/events/import",
            {"events": [row("cal-1", start_at=at, title="제목만 바뀜")]},
            format="json",
        )
        assert res.status_code == 200
        assert res.data["recomputed"] == 0
        assert route_calls["best"] + route_calls["resolve"] == before
        assert Event.objects.get().title == "제목만 바뀜"

    def test_sub_minute_jitter_does_not_recompute(self, client, route_calls):
        """**초 단위 지터가 전건 재계산을 유발하면 쿼터가 마른다.**

        계획은 분 단위로 계산되므로 초 이하 차이는 알람을 바꾸지 못한다. 시각을
        미세하게 다르게 보내는 클라이언트가 있어도 반복 동기화가 공짜여야 한다.
        """
        base = timezone.now().replace(second=0, microsecond=0) + timedelta(hours=48)
        client.post(
            "/api/events/import",
            {"events": [row("cal-1", start_at=base.isoformat())]},
            format="json",
        )
        before = route_calls["best"] + route_calls["resolve"]

        jittered = base + timedelta(seconds=37)
        res = client.post(
            "/api/events/import",
            {"events": [row("cal-1", start_at=jittered.isoformat())]},
            format="json",
        )
        assert res.data["recomputed"] == 0
        assert res.data["unchanged"] == 1
        assert route_calls["best"] + route_calls["resolve"] == before

    def test_minute_change_does_recompute(self, client, route_calls):
        """분이 바뀌면 계획도 바뀐다. 위의 관용이 너무 넓어지지 않았는지 확인한다."""
        base = timezone.now().replace(second=0, microsecond=0) + timedelta(hours=48)
        client.post(
            "/api/events/import",
            {"events": [row("cal-1", start_at=base.isoformat())]},
            format="json",
        )
        before = route_calls["best"] + route_calls["resolve"]

        res = client.post(
            "/api/events/import",
            {"events": [row("cal-1", start_at=(base + timedelta(minutes=1)).isoformat())]},
            format="json",
        )
        assert res.data["recomputed"] == 1
        assert route_calls["best"] + route_calls["resolve"] == before + 1


class TestPreservesUserEdits:

    def test_import_does_not_clear_route_choice(self, client, route_calls):
        """**캘린더에는 경로 정보가 없다.**

        매번 빈 값으로 밀면 사용자가 고른 경로와 출발지가 동기화 한 번에
        사라진다. 그러면 알람이 다른 경로 기준으로 계산되는데 화면은 고른
        경로라고 말한다.
        """
        client.post("/api/events/import", {"events": [row("cal-1")]}, format="json")
        event = Event.objects.get()
        event.route_key = "transit:2호선>5513"
        event.origin_lat, event.origin_lng = 37.5, 127.0
        event.origin_label = "학교"
        event.tau_override = 0.95
        event.save()

        client.post(
            "/api/events/import",
            {"events": [row("cal-1", start_at=future(hours=96))]},
            format="json",
        )

        event.refresh_from_db()
        assert event.route_key == "transit:2호선>5513"
        assert event.origin_lat == 37.5
        assert event.origin_label == "학교"
        assert event.tau_override == 0.95


class TestOwnership:

    def test_external_id_is_scoped_per_user(self, client, other, route_calls):
        """두 사용자가 같은 캘린더 id 를 가질 수 있다(같은 공유 캘린더)."""
        Event.objects.create(
            user=other,
            source=Event.Source.CALENDAR,
            external_id="cal-1",
            title="남의 일정",
            start_at=timezone.now() + timedelta(days=3),
        )

        res = client.post("/api/events/import", {"events": [row("cal-1")]}, format="json")
        assert res.status_code == 200
        assert res.data["created"] == 1
        assert Event.objects.filter(external_id="cal-1").count() == 2

    def test_import_never_touches_other_users_event(self, client, other, route_calls):
        theirs = Event.objects.create(
            user=other,
            source=Event.Source.CALENDAR,
            external_id="cal-1",
            title="남의 일정",
            start_at=timezone.now() + timedelta(days=3),
        )
        client.post(
            "/api/events/import",
            {"events": [row("cal-1", title="내 일정")]},
            format="json",
        )
        theirs.refresh_from_db()
        assert theirs.title == "남의 일정"


class TestValidation:

    def test_rejects_past_events(self, client, route_calls):
        """과거 시각으로 계획을 만들면 알람이 즉시 울릴 시각이 되거나 버려진다."""
        res = client.post(
            "/api/events/import",
            {"events": [row("cal-1", start_at=(timezone.now() - timedelta(days=1)).isoformat())]},
            format="json",
        )
        assert res.status_code == 400
        assert Event.objects.count() == 0

    def test_rejects_empty_batch(self, client, route_calls):
        res = client.post("/api/events/import", {"events": []}, format="json")
        assert res.status_code == 400

    def test_rejects_oversized_batch(self, client, route_calls):
        from apps.events.serializers import CalendarImportSerializer

        rows = [row(f"cal-{i}") for i in range(CalendarImportSerializer.MAX_ITEMS + 1)]
        res = client.post("/api/events/import", {"events": rows}, format="json")
        assert res.status_code == 400
        assert Event.objects.count() == 0

    def test_rejects_blank_external_id(self, client, route_calls):
        res = client.post(
            "/api/events/import",
            {"events": [row("   ")]},
            format="json",
        )
        assert res.status_code == 400

    def test_rejects_missing_external_id(self, client, route_calls):
        payload = row("cal-1")
        payload.pop("external_id")
        res = client.post("/api/events/import", {"events": [payload]}, format="json")
        assert res.status_code == 400

    def test_rejects_foreign_place(self, client, route_calls):
        """국외 목적지는 카카오가 답하지 않는다. Place 는 전역 공유 표라 더 위험하다."""
        paris = {"name": "에펠탑", "lat": 48.8584, "lng": 2.2945}
        res = client.post(
            "/api/events/import",
            {"events": [row("cal-1", place=paris)]},
            format="json",
        )
        assert res.status_code == 400
        assert Event.objects.count() == 0

    def test_rejects_unknown_tag(self, client, route_calls):
        res = client.post(
            "/api/events/import",
            {"events": [row("cal-1", tag_key="없는태그")]},
            format="json",
        )
        assert res.status_code == 400

    def test_batch_is_all_or_nothing(self, client, route_calls):
        """한 건이 잘못되면 **아무것도** 들어가지 않는다.

        절반만 반영되면 사용자가 무엇이 들어갔는지 알 수 없고, 다시 시도할 때
        어디서부터인지도 모른다.
        """
        res = client.post(
            "/api/events/import",
            {
                "events": [
                    row("cal-ok"),
                    row("cal-bad", start_at=(timezone.now() - timedelta(days=1)).isoformat()),
                ]
            },
            format="json",
        )
        assert res.status_code == 400
        assert Event.objects.count() == 0


class TestWithoutPlace:

    def test_event_without_place_is_accepted(self, client, route_calls):
        """장소 없는 캘린더 일정도 있다. 일정은 적히고 계획은 no_place 가 된다."""
        res = client.post(
            "/api/events/import",
            {"events": [row("cal-1", place=None)]},
            format="json",
        )
        assert res.status_code == 200
        assert res.data["created"] == 1
        assert res.data["results"][0]["alarm_plan"]["status"] == "no_place"

    def test_no_place_costs_no_route_call(self, client, route_calls):
        client.post(
            "/api/events/import", {"events": [row("cal-1", place=None)]}, format="json"
        )
        assert route_calls["best"] + route_calls["resolve"] == 0
