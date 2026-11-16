# Partly AI-generated (Claude)
"""최근 이동 경로 · 가장 자주 이용한 경로(iter4, 팀원 요청). 계약은 `docs/iter4/server-route-history.md`.

## 무엇을 세는가

**다녀온 이동** 하나 = 시작 시각이 지났고 "안 감"(`skipped_at`)이 아닌 일정 하나다. 반복 일정은 회차마다
한 번이다(매일 가는 수업은 실제로 매일 간다). 도착 기록(GPS)까지는 보지 않는다 — 도착을 못 잡는 실내가
많아 기록이 없다고 안 간 것은 아니다.

탄 경로는 이동 중에 바꿨으면 바꾼 경로, 아니면 계획이 쓴 경로, 아니면 고른 경로다
(`observations.current_route_key` 와 같은 규칙). 경로를 알 수 없는 일정(계산 실패 등)은 세지 않는다 — 다시 쓸 수 없다.

## 같은 경로로 묶는 기준

출발지 + 도착지 + 경로 key 가 같으면 하나다.

- 출발지: 집(출발지를 고르지 않음) · 저장한 집(`origin_place`) · 그 밖의 좌표(약 100m 격자).
- 도착지: 집(`destination_is_home`) · 카카오 장소(`kakao_place_id`) · 그 밖의 좌표(약 100m 격자).

집은 지금 프로필의 집 좌표로 보여 준다(일정 계산과 같다, `Event.resolve_origin`).
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta

from django.utils import timezone
from rest_framework.fields import DateTimeField

from apps.observations.models import TripReroute

from .models import Event

RECENT = "recent"
FREQUENT = "frequent"
SORTS = (RECENT, FREQUENT)

# 볼 기간과 한 번에 읽을 일정 수. 매일 가는 반복 일정 몇 개가 있어도 90일이면 수백 개다.
HISTORY_DAYS = 90
SCAN_LIMIT = 1000

DEFAULT_LIMIT = 5
MAX_LIMIT = 20

# 좌표 묶음 격자(소수 셋째 자리 ≈ 위도 110m · 경도 90m). 같은 카페를 지도에서 조금 다르게 찍어도 묶인다.
GRID_DIGITS = 3


@dataclass
class Group:
    # 가장 최근에 다녀온 일정과 그때 탄 경로. 보여 줄 값은 이 일정에서 온다.
    event: Event
    route_key: str
    count: int = 0


def history(user, *, sort: str = RECENT, limit: int = DEFAULT_LIMIT, now: datetime | None = None) -> list[dict]:
    """사용자의 다녀온 경로 목록. `sort` 가 `recent` 면 마지막으로 다녀온 순, `frequent` 면 횟수 순."""
    now = now or timezone.now()
    profile = user.profile
    events = list(
        Event.objects.filter(
            user=user,
            start_at__lte=now,
            start_at__gte=now - timedelta(days=HISTORY_DAYS),
            skipped_at__isnull=True,
        )
        .select_related("place", "origin_place", "alarm_plan")
        .order_by("-start_at", "-id")[:SCAN_LIMIT]
    )
    rerouted = _last_reroutes([e.pk for e in events])

    groups: dict[tuple, Group] = {}
    for event in events:
        plan = getattr(event, "alarm_plan", None)
        route_key = rerouted.get(event.pk) or current_route_key_without_query(event, plan)
        if not route_key:
            continue
        origin = _origin_key(event)
        destination = _destination_key(event)
        if destination is None:
            continue
        key = (origin, destination, route_key)
        group = groups.get(key)
        if group is None:
            # 일정은 최근 것부터 오므로 처음 본 것이 가장 최근 이동이다.
            group = groups[key] = Group(event=event, route_key=route_key)
        group.count += 1

    ordered = list(groups.values())
    if sort == FREQUENT:
        ordered.sort(key=lambda g: (-g.count, -g.event.start_at.timestamp(), -g.event.pk))
    # recent 는 이미 마지막 이동 순이다(dict 는 넣은 순서를 지킨다).
    return [_present(g, profile) for g in ordered[:limit]]


def current_route_key_without_query(event, plan) -> str:
    """`current_route_key` 와 같되 경로 변경 기록은 미리 한 번에 읽었으므로 다시 묻지 않는다."""
    return (getattr(plan, "route_key", "") or event.route_key or "").strip()


def _last_reroutes(event_ids: list[int]) -> dict[int, str]:
    """일정마다 마지막으로 바꾼 경로(`TripReroute`). 쿼리 한 번."""
    last: dict[int, str] = {}
    rows = (
        TripReroute.objects.filter(event_id__in=event_ids)
        .order_by("event_id", "-rerouted_at", "-id")
        .values_list("event_id", "to_route_key")
    )
    for event_id, key in rows:
        if event_id not in last and key:
            last[event_id] = key
    return last


def _grid(lat: float, lng: float) -> tuple[float, float]:
    return round(lat, GRID_DIGITS), round(lng, GRID_DIGITS)


def _origin_key(event: Event) -> tuple:
    if event.origin_place_id is not None:
        return ("saved_place", event.origin_place_id)
    if event.origin_lat is None or event.origin_lng is None:
        return ("home",)
    return ("point", *_grid(event.origin_lat, event.origin_lng))


def _destination_key(event: Event) -> tuple | None:
    if event.destination_is_home:
        return ("home",)
    place = event.place
    if place is None:
        return None
    if place.kakao_place_id:
        return ("kakao", place.kakao_place_id)
    return ("point", *_grid(place.lat, place.lng))


def _present(group: Group, profile) -> dict:
    event = group.event
    plan = getattr(event, "alarm_plan", None)
    return {
        "origin": _origin_json(event, profile),
        "destination": _destination_json(event, profile),
        "route_key": group.route_key,
        # 표시용 값은 마지막으로 다녀온 일정의 계획에서 온다(경로를 바꿨으면 바꾸기 전 계획 값이다).
        "travel_mode": (getattr(plan, "travel_mode", "") or "") or None,
        "route_summary": (getattr(plan, "route_summary", "") or "") or None,
        "travel_minutes": getattr(plan, "travel_minutes", None),
        "rerouted": group.route_key != current_route_key_without_query(event, plan),
        "trip_count": group.count,
        # 다른 응답과 같은 형식(서버 시간대, `+09:00`).
        "last_trip_at": DateTimeField().to_representation(event.start_at),
        "last_event_id": event.pk,
    }


def _origin_json(event: Event, profile) -> dict:
    if event.origin_place_id is not None:
        saved = event.origin_place
        return {
            "kind": "saved_place",
            "saved_place_id": event.origin_place_id,
            "label": saved.label if saved is not None else (event.origin_label or ""),
            "lat": event.origin_lat,
            "lng": event.origin_lng,
        }
    if event.origin_lat is None or event.origin_lng is None:
        return {
            "kind": "home",
            "saved_place_id": None,
            "label": profile.home_label or "집",
            "lat": profile.home_lat,
            "lng": profile.home_lng,
        }
    return {
        "kind": "point",
        "saved_place_id": None,
        "label": event.origin_label or "출발지",
        "lat": event.origin_lat,
        "lng": event.origin_lng,
    }


def _destination_json(event: Event, profile) -> dict:
    if event.destination_is_home:
        return {
            "kind": "home",
            "name": profile.home_label or "집",
            "address": "",
            "lat": profile.home_lat,
            "lng": profile.home_lng,
            "kakao_place_id": None,
        }
    place = event.place
    return {
        "kind": "place",
        "name": place.name,
        "address": place.address or "",
        "lat": place.lat,
        "lng": place.lng,
        "kakao_place_id": place.kakao_place_id or None,
    }
