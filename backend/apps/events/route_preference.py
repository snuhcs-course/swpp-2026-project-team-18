# Partly AI-generated (Claude)
"""경로 선택 화면의 기본 선택. 사용자가 전에 고른 경로에서 정한다.

## 왜 필요한가

경로 선택 화면은 지금까지 가장 빠른 후보(`fastest_key`)의 수단 탭을 열었다. 차가 없는
사람도 자동차가 가장 빠르면 자동차 탭이 먼저 열려서, 매번 대중교통 탭으로 옮겨 골라야
했다.

## 규칙

1. **같은 출발지 → 같은 목적지**(100m 안)로 **지난 90일 안에 다녀온 이동**이 있으면 그중 가장
   최근 이동에서 **실제로 탄 경로**를 쓴다(BE-PREF, 11-20). 그 경로가 이번 후보에 그대로 있으면
   그 경로(`preferred_match: same_route`), 없으면(대중교통 노선 조합이 바뀌었으면) 같은 수단의 가장
   빠른 후보다(`same_mode_fallback`). "다녀온 이동" 과 "실제로 탄 경로" 는 `routes/history` 와 같다
   (`route_history`): 시작 시각이 지났고 "안 감" 이 아닌 일정, 이동 중 바꿨으면 바꾼 경로.
   출발지는 집 · 저장한 집 · 그 밖의 좌표를 약 100m 격자로 비교한다. 이번 요청은 좌표만 오므로
   (`origin_lat`/`origin_lng`, 없으면 집) 그 좌표의 격자와 이동의 출발 좌표 격자가 같으면 같은
   출발지다.
2. 없으면 **최근 선택 10개에서 가장 많이 고른 수단**의 가장 빠른 후보다. 동률이면 더
   최근에 고른 수단이다. 가장 최근 하나만 보면 근처 카페에 한 번 걸어간 것 때문에 먼
   곳도 도보 탭이 열린다.
3. 고른 수단이 이번 후보에 없으면 기본 선택을 주지 않는다. 앱은 `fastest_key` 를 쓴다.

"선택" 은 `route_key` 가 있는 일정이다(경로 선택 화면에서 고른 것). 반복 일정은 회차가
매일 새로 만들어지므로 규칙 하나를 한 번으로 센다. 그 시각은 첫 회차를 만든 시각이다.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass
from datetime import datetime

from datetime import timedelta

from django.db.models import Min, Q
from django.utils import timezone

from apps.common.geo import distance_meters
from apps.routing.clients import CANDIDATE_LIMITS

from . import route_history
from .models import Event, EventRecurrence

# 같은 목적지로 보는 거리. 앱 도착 판정 반경(`TripGeofence`)과 같다.
SAME_DESTINATION_M = 100

# 수단 횟수를 셀 최근 선택 수.
RECENT_CHOICES = 10

# 한 번에 읽는 일반 일정 수. 같은 목적지를 찾을 때 이만큼 거슬러 올라간다.
SCAN_LIMIT = 200

SAME_DESTINATION = "same_destination"
RECENT_MODE = "recent_mode"

# `preferred_match`. 같은 목적지 규칙에서 기록의 경로가 이번 후보에 그대로 있었는지.
SAME_ROUTE = "same_route"
SAME_MODE_FALLBACK = "same_mode_fallback"

# 목적지 100m 를 DB 에서 먼저 거르는 상자(도). 위도 0.0015° ≈ 167m, 경도 0.002° ≈ 177m(위도 37°).
_DEST_BOX_LAT = 0.0015
_DEST_BOX_LNG = 0.002


@dataclass(frozen=True)
class Choice:
    key: str
    mode: str
    at: datetime
    to_home: bool
    lat: float | None
    lng: float | None


def preferred_route(
    user, dest_lat: float, dest_lng: float, candidates: list[dict], *,
    origin_lat: float | None = None, origin_lng: float | None = None, now=None,
) -> dict:
    """후보 응답에 붙일 `preferred_key`·`preferred_mode`·`preferred_basis`·`preferred_match`.

    `origin_lat`/`origin_lng` 는 이번 요청의 출발지다. 없으면(앱이 출발지를 보내지 않음) 집이다.
    기본 선택이 없으면 모두 None.
    """
    none = {
        "preferred_key": None, "preferred_mode": None, "preferred_basis": None,
        "preferred_match": None,
    }
    by_mode: dict[str, list[dict]] = {}
    for candidate in candidates:
        by_mode.setdefault(candidate["kind"], []).append(candidate)
    if not by_mode:
        return none

    trip_key = last_trip_route(
        user, dest_lat, dest_lng, origin_lat=origin_lat, origin_lng=origin_lng, now=now,
    )
    mode = _mode_of(trip_key)
    if mode is not None:
        # 버스 번호만 다른 경로는 한 후보로 합쳐 온다. 탄 key 가 대표가 아니어도 그 후보다.
        match = next(
            (c for c in candidates if trip_key in (c.get("member_keys") or [c["key"]])), None
        )
        if match is not None:
            return _result(match["key"], mode, SAME_DESTINATION, SAME_ROUTE)
        fastest = _fastest(by_mode.get(mode))
        if fastest is not None:
            return _result(fastest["key"], mode, SAME_DESTINATION, SAME_MODE_FALLBACK)

    choices = recent_choices(user)
    if not choices:
        return none
    recent = choices[:RECENT_CHOICES]
    counts = Counter(c.mode for c in recent)
    top = max(counts.values())
    mode = next(c.mode for c in recent if counts[c.mode] == top)
    fastest = _fastest(by_mode.get(mode))
    if fastest is None:
        return none
    return _result(fastest["key"], mode, RECENT_MODE, None)


def last_trip_route(
    user, dest_lat: float, dest_lng: float, *,
    origin_lat: float | None = None, origin_lng: float | None = None, now=None,
) -> str:
    """같은 출발지에서 같은 목적지로 지난 90일 안에 다녀온 가장 최근 이동의 탄 경로. 없으면 ""."""
    now = now or timezone.now()
    profile = user.profile
    if origin_lat is None or origin_lng is None:
        origin_lat, origin_lng = profile.home_lat, profile.home_lng
    if origin_lat is None or origin_lng is None:
        return ""
    wanted_origin = route_history._grid(origin_lat, origin_lng)

    near_destination = Q(
        place__lat__range=(dest_lat - _DEST_BOX_LAT, dest_lat + _DEST_BOX_LAT),
        place__lng__range=(dest_lng - _DEST_BOX_LNG, dest_lng + _DEST_BOX_LNG),
        destination_is_home=False,
    )
    if profile.home_lat is not None and profile.home_lng is not None and distance_meters(
        profile.home_lat, profile.home_lng, dest_lat, dest_lng
    ) <= SAME_DESTINATION_M:
        near_destination |= Q(destination_is_home=True)
    events = list(
        Event.objects.filter(
            near_destination,
            user=user,
            start_at__lte=now,
            start_at__gte=now - timedelta(days=route_history.HISTORY_DAYS),
            skipped_at__isnull=True,
        )
        .select_related("place", "alarm_plan")
        .order_by("-start_at", "-id")[:SCAN_LIMIT]
    )
    trips = [
        e for e in events
        if (e.destination_is_home or _within(e.place.lat, e.place.lng, dest_lat, dest_lng))
        and _origin_grid(e, profile) == wanted_origin
    ]
    if not trips:
        return ""
    rerouted = route_history._last_reroutes([e.pk for e in trips])
    for event in trips:  # 최근 것부터
        plan = getattr(event, "alarm_plan", None)
        key = rerouted.get(event.pk) or route_history.current_route_key_without_query(event, plan)
        if _mode_of(key) is not None:
            return key
    return ""


def _origin_grid(event: Event, profile) -> tuple | None:
    """이동의 출발 좌표 격자. 저장한 집이면 그 집의 좌표(`origin_*` 에 복사돼 있다), 집이면 지금 집 좌표."""
    if event.origin_lat is not None and event.origin_lng is not None:
        return route_history._grid(event.origin_lat, event.origin_lng)
    if profile.home_lat is None or profile.home_lng is None:
        return None
    return route_history._grid(profile.home_lat, profile.home_lng)


def _within(lat, lng, dest_lat, dest_lng) -> bool:
    return lat is not None and lng is not None and (
        distance_meters(lat, lng, dest_lat, dest_lng) <= SAME_DESTINATION_M
    )


def _mode_of(key: str) -> str | None:
    mode = (key or "").split(":", 1)[0]
    return mode if key and mode in CANDIDATE_LIMITS else None


def recent_choices(user) -> list[Choice]:
    """사용자의 경로 선택. 최근 것부터."""
    choices = []
    one_off = (
        Event.objects.filter(user=user, recurrence__isnull=True)
        .exclude(route_key="")
        .order_by("-created_at", "-id")
        .values_list("route_key", "created_at", "destination_is_home", "place__lat", "place__lng")
    )
    for key, at, to_home, lat, lng in one_off[:SCAN_LIMIT]:
        choices.append(_choice(key, at, to_home, lat, lng))

    rules = (
        EventRecurrence.objects.filter(user=user)
        .annotate(first_created=Min("occurrences__created_at"))
        .values_list("template", "first_created", "place__lat", "place__lng")
    )
    for template, at, lat, lng in rules:
        key = (template or {}).get("route_key") or ""
        if key and at is not None:
            choices.append(
                _choice(key, at, bool(template.get("destination_is_home")), lat, lng)
            )

    choices = [c for c in choices if c is not None]
    choices.sort(key=lambda c: c.at, reverse=True)
    return choices


def _choice(key, at, to_home, lat, lng) -> Choice | None:
    mode = key.split(":", 1)[0]
    if mode not in CANDIDATE_LIMITS:
        return None
    return Choice(key=key, mode=mode, at=at, to_home=to_home, lat=lat, lng=lng)


def _goes_to(choice: Choice, profile, dest_lat: float, dest_lng: float) -> bool:
    # 집 목적지는 지금 프로필의 집 좌표로 비교한다(`Event.resolve_destination` 과 같다).
    lat, lng = (profile.home_lat, profile.home_lng) if choice.to_home else (choice.lat, choice.lng)
    if lat is None or lng is None:
        return False
    return distance_meters(lat, lng, dest_lat, dest_lng) <= SAME_DESTINATION_M


def _fastest(candidates: list[dict] | None) -> dict | None:
    # 전체 최단(`fastest_key`)과 같은 분·환승 순위다.
    if not candidates:
        return None
    return min(candidates, key=lambda c: (c["minutes"], c.get("transfers", 0)))


def _result(key: str, mode: str, basis: str, match: str | None) -> dict:
    return {
        "preferred_key": key, "preferred_mode": mode, "preferred_basis": basis,
        "preferred_match": match,
    }
