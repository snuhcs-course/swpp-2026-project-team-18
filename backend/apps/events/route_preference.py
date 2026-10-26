# Partly AI-generated (Claude)
"""경로 선택 화면의 기본 선택. 사용자가 전에 고른 경로에서 정한다.

## 왜 필요한가

경로 선택 화면은 지금까지 가장 빠른 후보(`fastest_key`)의 수단 탭을 열었다. 차가 없는
사람도 자동차가 가장 빠르면 자동차 탭이 먼저 열려서, 매번 대중교통 탭으로 옮겨 골라야
했다.

## 규칙

1. **같은 목적지**(100m 안)에 간 일정이 있으면 그중 가장 최근 선택을 쓴다. 그 경로가
   이번 후보에 그대로 있으면 그 경로, 없으면(대중교통 노선 조합이 바뀌었거나 출발지가
   다르면) 같은 수단의 가장 빠른 후보다.
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

from django.db.models import Min

from apps.common.geo import distance_meters
from apps.routing.clients import CANDIDATE_LIMITS

from .models import Event, EventRecurrence

# 같은 목적지로 보는 거리. 앱 도착 판정 반경(`TripGeofence`)과 같다.
SAME_DESTINATION_M = 100

# 수단 횟수를 셀 최근 선택 수.
RECENT_CHOICES = 10

# 한 번에 읽는 일반 일정 수. 같은 목적지를 찾을 때 이만큼 거슬러 올라간다.
SCAN_LIMIT = 200

SAME_DESTINATION = "same_destination"
RECENT_MODE = "recent_mode"


@dataclass(frozen=True)
class Choice:
    key: str
    mode: str
    at: datetime
    to_home: bool
    lat: float | None
    lng: float | None


def preferred_route(user, dest_lat: float, dest_lng: float, candidates: list[dict]) -> dict:
    """후보 응답에 붙일 `preferred_key`·`preferred_mode`·`preferred_basis`. 없으면 모두 None."""
    none = {"preferred_key": None, "preferred_mode": None, "preferred_basis": None}
    by_mode: dict[str, list[dict]] = {}
    for candidate in candidates:
        by_mode.setdefault(candidate["kind"], []).append(candidate)
    if not by_mode:
        return none
    choices = recent_choices(user)
    if not choices:
        return none

    profile = user.profile
    same = next(
        (c for c in choices if _goes_to(c, profile, dest_lat, dest_lng)), None
    )
    if same is not None:
        if any(candidate["key"] == same.key for candidate in candidates):
            return _result(same.key, same.mode, SAME_DESTINATION)
        fastest = _fastest(by_mode.get(same.mode))
        if fastest is not None:
            return _result(fastest["key"], same.mode, SAME_DESTINATION)

    recent = choices[:RECENT_CHOICES]
    counts = Counter(c.mode for c in recent)
    top = max(counts.values())
    mode = next(c.mode for c in recent if counts[c.mode] == top)
    fastest = _fastest(by_mode.get(mode))
    if fastest is None:
        return none
    return _result(fastest["key"], mode, RECENT_MODE)


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


def _result(key: str, mode: str, basis: str) -> dict:
    return {"preferred_key": key, "preferred_mode": mode, "preferred_basis": basis}
