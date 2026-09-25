"""더 빠른 대안 경로를 계획에 옮기고 앱에 내린다.

`clients` 쪽 판단(같은 경로면·더 느리면 붙이지 않는다)은
`apps/routing/tests/test_route_alternative.py` 에서 확인한다. 여기서는 그
결과가 **계획에 어떻게 남는가**를 본다.

## 가장 중요한 두 가지

1. **사용자가 고른 경로를 건드리지 않는다.** `Event.route_key` 는 그대로고
   계산도 그 수단으로 한다. 대안은 따로 담긴 값일 뿐이다.
2. **낡은 대안이 남지 않는다.** 다음 갱신에서 대안이 없어졌으면 네 필드를
   비워야 한다. 안 비우면 지도가 이미 사라진 노선을 "지금 더 빠름" 으로
   알린다 — 지어낸 숫자보다 나쁜 것이 지어낸 대안이다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from apps.events.models import Event, Place
from apps.events.serializers import AlarmPlanSerializer
from apps.planning import services
from apps.planning.models import AlarmPlan

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db

CHOSEN_KEY = "transit:2호선"
ROUTE = {
    "minutes": 20,
    "mode": "지하철",
    "source": "kakao_transit",
    "summary": "20분 · 10.0km",
    "key": CHOSEN_KEY,
    "detail": "2호선",
    "path": [[37.48, 126.93], [37.49, 127.02]],
    "path_distance_m": 8000,
}
ALT = {
    "key": "transit:9호선>2호선",
    "label": "9호선 → 2호선",
    "faster_minutes": 4,
    "path": [[37.48, 126.93], [37.52, 126.98], [37.49, 127.02]],
}


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="alt@example.com", password="test12345", nickname="대안"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.onboarding_prep_min = 30
    p.save()
    return u


@pytest.fixture
def place():
    return Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)


def stub(monkeypatch, alternative: dict | None):
    """`resolve_route` 를 고정값으로. `alternative` 가 None 이면 붙이지 않는다."""

    def _resolve(key, **kwargs):
        route = dict(ROUTE, key=key)
        if alternative is not None:
            route["alternative"] = dict(alternative)
        return route, False

    monkeypatch.setattr(services.clients, "resolve_route", _resolve)
    monkeypatch.setattr(
        services.clients, "best_route", lambda **kw: (dict(ROUTE), False)
    )


def make_event(user, place, **kw):
    kw.setdefault("title", "수업")
    kw.setdefault("start_at", datetime(2026, 9, 21, 9, 0, tzinfo=KST))
    kw.setdefault("route_key", CHOSEN_KEY)
    return Event.objects.create(user=user, place=place, **kw)


# --- 계획에 남는다 ----------------------------------------------------------


def test_대안을_계획에_옮긴다(user, place, monkeypatch):
    stub(monkeypatch, ALT)
    plan = services.compute_and_store(make_event(user, place))

    assert plan.status == AlarmPlan.Status.OK
    assert plan.alt_route_key == "transit:9호선>2호선"
    assert plan.alt_route_label == "9호선 → 2호선"
    assert plan.alt_faster_minutes == 4
    assert plan.alt_route_path == ALT["path"]


def test_고른_경로는_그대로다(user, place, monkeypatch):
    stub(monkeypatch, ALT)
    event = make_event(user, place)
    plan = services.compute_and_store(event)

    event.refresh_from_db()
    # 사용자의 선택은 `Event` 에 있고 계산도 그것으로 했다. 대안이 있다고
    # 조용히 갈아치우지 않는다.
    assert event.route_key == CHOSEN_KEY
    assert plan.route_key == CHOSEN_KEY
    assert plan.route_path == ROUTE["path"]


def test_대안이_없으면_비운다(user, place, monkeypatch):
    stub(monkeypatch, None)
    plan = services.compute_and_store(make_event(user, place))

    assert plan.alt_route_key == ""
    assert plan.alt_route_label == ""
    assert plan.alt_faster_minutes is None
    assert plan.alt_route_path == []


def test_다음_갱신에서_대안이_사라지면_지운다(user, place, monkeypatch):
    event = make_event(user, place)

    stub(monkeypatch, ALT)
    first = services.compute_and_store(event)
    assert first.alt_route_key

    # 배차가 바뀌어 그 노선이 더 이상 빠르지 않다.
    stub(monkeypatch, None)
    second = services.compute_and_store(event)

    assert second.pk == first.pk, "같은 계획 행을 갱신한다"
    assert second.alt_route_key == ""
    assert second.alt_faster_minutes is None
    assert second.alt_route_path == []


def test_경로_조회가_실패하면_대안도_비운다(user, place, monkeypatch):
    event = make_event(user, place)
    stub(monkeypatch, ALT)
    services.compute_and_store(event)

    monkeypatch.setattr(
        services.clients, "resolve_route", lambda key, **kw: (None, True)
    )
    plan = services.compute_and_store(event)

    assert plan.status == AlarmPlan.Status.ROUTE_FAILED
    assert plan.alt_route_key == ""
    assert plan.alt_route_path == []


def test_차이가_0이하면_담지_않는다(user, place, monkeypatch):
    # `clients` 가 먼저 막지만 계획 쪽도 스스로 막는다. 0분 빠른 대안을
    # 화면에 올리면 "4분 빠름" 자리에 "0분 빠름" 이 뜬다.
    stub(monkeypatch, dict(ALT, faster_minutes=0))
    plan = services.compute_and_store(make_event(user, place))

    assert plan.alt_route_key == ""
    assert plan.alt_faster_minutes is None


# --- 앱에 내린다 ------------------------------------------------------------


def test_직렬화가_네_필드를_내린다(user, place, monkeypatch):
    stub(monkeypatch, ALT)
    plan = services.compute_and_store(make_event(user, place))

    data = AlarmPlanSerializer(plan).data

    assert data["alt_route_key"] == "transit:9호선>2호선"
    assert data["alt_route_label"] == "9호선 → 2호선"
    assert data["alt_faster_minutes"] == 4
    assert data["alt_route_path"] == ALT["path"]


def test_대안이_없을_때의_모양(user, place, monkeypatch):
    stub(monkeypatch, None)
    plan = services.compute_and_store(make_event(user, place))

    data = AlarmPlanSerializer(plan).data

    # 배열 자리에 null 을 내리면 앱의 Gson 이 응답 전체 파싱을 깨뜨린다.
    assert data["alt_route_path"] == []
    assert data["alt_faster_minutes"] is None
    assert data["alt_route_key"] == ""


def test_DB_에_문자열이_들어가도_배열로_내린다(user, place, monkeypatch):
    """`_as_list` 방어. `route_path` 와 같은 이유다.

    한 필드가 배열이어야 할 자리에 문자열이면 그 필드만 비는 것이 아니라
    **응답 전체**가 파싱 실패해 일정 목록이 통째로 날아간다.
    """
    stub(monkeypatch, ALT)
    plan = services.compute_and_store(make_event(user, place))

    AlarmPlan.objects.filter(pk=plan.pk).update(alt_route_path='[[37.5, 127.0]]')
    plan.refresh_from_db()

    data = AlarmPlanSerializer(plan).data
    assert data["alt_route_path"] == [[37.5, 127.0]]


def test_망가진_문자열이면_빈_배열로_내린다(user, place, monkeypatch):
    stub(monkeypatch, ALT)
    plan = services.compute_and_store(make_event(user, place))

    AlarmPlan.objects.filter(pk=plan.pk).update(alt_route_path='좌표아님')
    plan.refresh_from_db()

    assert AlarmPlanSerializer(plan).data["alt_route_path"] == []
