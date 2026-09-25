"""이동 중에는 **현재 위치부터 목적지까지** 가장 빠른 길을 본다.

## 왜 출발지 기준이 무의미한가

`AlarmPlan.route_path` 와 `alt_route_*` 는 출발지부터 계산한 값이다. 이미 집을
나선 사람에게 집에서부터의 선은 판단 재료가 아니다 — 지금 있는 곳에서 목적지로
가는 선이 필요하다. 그래서 이동 중에는 앱이 1분마다 이 엔드포인트를 부르고,
응답의 `route.path` 를 지도에 그린다.

## 무엇을 고정하는가

1. **출발지를 현재 위치로 바꿔 넘긴다.** 이 테스트의 핵심이다. 계획에 저장된
   출발지를 쓰면 화면은 그대로인데 값만 바뀌어, 틀린 것을 알아채기 어렵다.
2. **대안 유무와 관계없이 현재 위치 기준 경로를 준다.** 대안이 없다고 비우면
   앱은 출발지부터 저장된 낡은 선을 계속 그린다.
3. **카카오 호출이 1회다.** 1분마다 부르므로 2회가 되면 하루 쿼터가 두 배로
   마른다.
4. **아무것도 저장하지 않는다.** `AlarmPlan` 이 그대로여야 한다. 1분마다 쓰면
   `computed_at`(auto_now)이 항상 "방금" 이 되어 "N분 전 계산" 이 거짓이 된다.
5. 인증·국내 좌표·남의 일정 차단.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from django.urls import reverse

from apps.events.models import Event, Place
from apps.planning import services

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db

HOME = (37.484267, 126.929745)      # 신림역
HERE = (37.497942, 127.027621)      # 강남역 — 이동 중에 잡힌 현재 위치
DEST = (37.459786, 126.951124)      # 서울대

CHOSEN_KEY = "transit:5516"
ROUTE = {
    "minutes": 40,
    "mode": "버스",
    "source": "kakao_transit",
    "summary": "40분 · 12.0km",
    "key": CHOSEN_KEY,
    "detail": "5516",
    "path": [[37.48, 126.93], [37.46, 126.95]],
    "path_distance_m": 12000,
}
CURRENT_PATH = [[37.4979, 127.0276], [37.48, 126.99], [37.4598, 126.9511]]
ALT_PATH = [[37.4979, 127.0276], [37.47, 126.99], [37.4598, 126.9511]]


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="live@example.com", password="test12345", nickname="이동"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = HOME[0], HOME[1], "신림역"
    p.onboarding_prep_min = 30
    p.save()
    return u


@pytest.fixture
def client_auth(user):
    from rest_framework.test import APIClient

    c = APIClient()
    c.force_authenticate(user=user)
    return c


@pytest.fixture
def place():
    return Place.objects.create(name="관악캠퍼스", lat=DEST[0], lng=DEST[1])


@pytest.fixture
def event(user, place, monkeypatch):
    """계획까지 계산된 일정. 계산 단계의 경로 조회는 고정값으로 막는다."""
    monkeypatch.setattr(
        services.clients, "resolve_route", lambda key, **kw: (dict(ROUTE, key=key), False)
    )
    monkeypatch.setattr(
        services.clients, "best_route", lambda **kw: (dict(ROUTE), False)
    )
    ev = Event.objects.create(
        user=user,
        place=place,
        title="수업",
        start_at=datetime(2026, 9, 21, 9, 0, tzinfo=KST),
        route_key=CHOSEN_KEY,
    )
    services.compute_and_store(ev)
    ev.refresh_from_db()
    return ev


@pytest.fixture
def spy(monkeypatch):
    """`resolve_route` 를 가로채 호출 인자와 횟수를 기록한다."""
    calls: list[dict] = []

    def fake(key, **kwargs):
        calls.append({"key": key, **kwargs})
        return (
            dict(
                ROUTE,
                key=key,
                path=CURRENT_PATH,
                alternative={
                    "key": "transit:2호선",
                    "label": "2호선",
                    "faster_minutes": 7,
                    "path": ALT_PATH,
                },
            ),
            False,
        )

    from apps.events import views

    monkeypatch.setattr(views.clients, "resolve_route", fake)
    return calls


def url() -> str:
    return reverse("events:route_live")


def body(event, lat=HERE[0], lng=HERE[1]) -> dict:
    return {"event_id": event.id, "lat": lat, "lng": lng}


# --- 현재 위치를 출발지로 쓴다 ----------------------------------------------


def test_현재_위치를_출발지로_넘긴다(client_auth, event, spy):
    res = client_auth.post(url(), body(event), format="json")

    assert res.status_code == 200
    assert len(spy) == 1, "1분마다 부르므로 호출이 늘면 쿼터가 배로 마른다"
    call = spy[0]
    # **계획의 출발지(집)가 아니라 지금 있는 곳이다.**
    assert call["start_lat"] == pytest.approx(HERE[0])
    assert call["start_lng"] == pytest.approx(HERE[1])
    assert call["start_lat"] != pytest.approx(HOME[0])
    assert call["end_lat"] == pytest.approx(DEST[0])
    assert call["end_lng"] == pytest.approx(DEST[1])


def test_계획이_쓴_경로를_기준으로_비교한다(client_auth, event, spy):
    client_auth.post(url(), body(event), format="json")
    assert spy[0]["key"] == CHOSEN_KEY


def test_고르지_않은_일정도_계획의_경로로_비교한다(client_auth, event, spy):
    # `Event.route_key` 가 비어도 `AlarmPlan.route_key` 에 서버가 쓴 것이 남아
    # 있으므로 비교 대상이 있다.
    Event.objects.filter(pk=event.pk).update(route_key="")
    client_auth.post(url(), body(event), format="json")

    assert len(spy) == 1
    assert spy[0]["key"] == CHOSEN_KEY


def test_더_빠른_대안이_있으면_그것을_현재_최단_경로로_내려준다(client_auth, event, spy):
    res = client_auth.post(url(), body(event), format="json")
    route = res.json()["route"]

    assert route == {
        "route_key": "transit:2호선",
        "label": "2호선",
        "minutes": 33,
        "path": ALT_PATH,
        "is_alternative": True,
        "faster_minutes": 7,
    }
    assert res.json()["degraded"] is False
    assert "alternative" not in res.json()


# --- 아무것도 저장하지 않는다 ------------------------------------------------


def test_계획을_건드리지_않는다(client_auth, event, spy):
    before = event.alarm_plan
    snapshot = {
        "alt_route_key": before.alt_route_key,
        "alt_route_path": list(before.alt_route_path or []),
        "route_key": before.route_key,
        "route_detail": before.route_detail,
        "route_path": list(before.route_path or []),
        "route_distance_m": before.route_distance_m,
        "travel_minutes": before.travel_minutes,
        "alarm_at": before.alarm_at,
        "computed_at": before.computed_at,
    }

    client_auth.post(url(), body(event), format="json")

    after = type(before).objects.get(pk=before.pk)
    for field, value in snapshot.items():
        assert getattr(after, field) == value, f"{field} 이 바뀌었다"


def test_계산_시각이_밀리지_않는다(client_auth, event, spy):
    """`computed_at` 은 `auto_now` 다.

    1분마다 저장하면 "N분 전 계산" 이 항상 "방금" 이 되어, 알람 숫자가 낡았는지
    알려 주는 유일한 장치가 거짓말을 한다.
    """
    before = event.alarm_plan.computed_at
    for _ in range(3):
        client_auth.post(url(), body(event), format="json")

    event.alarm_plan.refresh_from_db()
    assert event.alarm_plan.computed_at == before


# --- 대안이 없을 때 ---------------------------------------------------------


def test_같은_경로가_최단이어도_현재_위치_기준_선을_준다(client_auth, event, monkeypatch):
    from apps.events import views

    monkeypatch.setattr(
        views.clients,
        "resolve_route",
        lambda key, **kw: (dict(ROUTE, key=key, path=CURRENT_PATH), False),
    )
    res = client_auth.post(url(), body(event), format="json")

    assert res.status_code == 200
    assert res.json()["route"] == {
        "route_key": CHOSEN_KEY,
        "label": "5516",
        "minutes": 40,
        "path": CURRENT_PATH,
        "is_alternative": False,
        "faster_minutes": 0,
    }
    assert res.json()["degraded"] is False


@pytest.mark.parametrize(
    "route_key,label",
    [("walk", "도보"), ("bicycle", "자전거"), ("car", "자동차")],
)
def test_단일_경로_수단도_현재_위치_기준_선을_준다(
    client_auth, event, monkeypatch, route_key, label
):
    """대안 목록이 없는 수단도 응답을 비우지 않는다."""
    from apps.events import views

    type(event.alarm_plan).objects.filter(pk=event.alarm_plan.pk).update(
        route_key=route_key
    )
    calls = []

    def fake(key, **kwargs):
        calls.append({"key": key, **kwargs})
        return (
            dict(
                ROUTE,
                key=key,
                mode=label,
                detail="",
                path=CURRENT_PATH,
            ),
            False,
        )

    monkeypatch.setattr(views.clients, "resolve_route", fake)
    res = client_auth.post(url(), body(event), format="json")

    assert len(calls) == 1
    assert res.status_code == 200
    assert res.json()["route"] == {
        "route_key": route_key,
        "label": label,
        "minutes": 40,
        "path": CURRENT_PATH,
        "is_alternative": False,
        "faster_minutes": 0,
    }


def test_경로_조회가_실패하면_degraded_로_알린다(client_auth, event, monkeypatch):
    from apps.events import views

    monkeypatch.setattr(views.clients, "resolve_route", lambda key, **kw: (None, True))
    res = client_auth.post(url(), body(event), format="json")

    assert res.status_code == 200
    body_ = res.json()
    assert body_["route"] is None
    assert body_["degraded"] is True


def test_경로_key_가_없으면_호출하지_않는다(client_auth, user, place, spy):
    # 장소만 있고 계획이 없는 일정. 비교할 대상이 없으므로 카카오를 부르지 않는다.
    bare = Event.objects.create(
        user=user, place=place, title="계획없음",
        start_at=datetime(2026, 9, 22, 9, 0, tzinfo=KST),
    )
    Event.objects.filter(pk=bare.pk).update(route_key="")
    if hasattr(bare, "alarm_plan"):
        bare.alarm_plan.delete()

    res = client_auth.post(url(), body(bare), format="json")

    assert res.status_code == 200
    assert res.json()["route"] is None
    assert res.json()["degraded"] is False
    assert spy == [], "부를 근거가 없는데 쿼터를 쓰면 안 된다"


# --- 막는 것들 --------------------------------------------------------------


def test_인증이_없으면_401(event):
    from rest_framework.test import APIClient

    res = APIClient().post(url(), body(event), format="json")
    assert res.status_code in (401, 403)


def test_남의_일정은_404(event, django_user_model, spy):
    from rest_framework.test import APIClient

    other = django_user_model.objects.create_user(
        email="other@example.com", password="test12345", nickname="남"
    )
    c = APIClient()
    c.force_authenticate(user=other)

    res = c.post(url(), body(event), format="json")
    assert res.status_code == 404
    assert spy == []


@pytest.mark.parametrize(
    "lat,lng",
    [
        (48.8566, 2.3522),    # 파리
        (37.5, 200.0),        # 경도 범위 밖
        (91.0, 127.0),        # 위도 범위 밖
    ],
)
def test_국외_좌표는_400(client_auth, event, spy, lat, lng):
    # 전 세계 경로 프록시로 쓰이는 길을 막는다. 카카오는 국내만 다룬다.
    res = client_auth.post(url(), body(event, lat, lng), format="json")
    assert res.status_code == 400
    assert res.json()["error"]["code"] == "invalid_coordinate"
    assert spy == []


@pytest.mark.parametrize(
    "payload",
    [
        {},
        {"event_id": 1},
        {"lat": 37.5, "lng": 127.0},
        {"event_id": "x", "lat": 37.5, "lng": 127.0},
        {"event_id": 1, "lat": "여기", "lng": 127.0},
    ],
)
def test_모양이_틀리면_400(client_auth, spy, payload):
    res = client_auth.post(url(), payload, format="json")
    assert res.status_code == 400
    assert spy == []


def test_없는_일정은_404(client_auth, spy):
    res = client_auth.post(url(), {"event_id": 999999, "lat": HERE[0], "lng": HERE[1]}, format="json")
    assert res.status_code == 404
    assert spy == []
