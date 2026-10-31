# Partly AI-generated (Claude)
"""이동 중 "지금 경로로는 늦는다, 이 경로로 바꾸자" 판정(P2, 10-31).

이동 중 경로 갱신(`POST /api/routes/live`)이 1분마다 부른다. 지금 위치에서 다시 푼 고른
경로와, 같은 카카오 응답 안의 더 빠른 대중교통 후보를 비교한다. 카카오 호출은 늘지 않는다.

## 언제 바꾸자고 하나

**지금 경로로 가면 일정 시작에 늦을 때만이다**(사용자 10-31). 안 늦는데 몇 분 빠른 대안은
알리지 않는다 — 지도에 이미 "N분 빠름" 이 보이고, 이동 중 알림은 방해다.

1. 고른 경로의 도착 예상이 일정 시작보다 늦다(반올림해 1분 이상).
2. 대안이 더 일찍 도착한다.
3. 대안으로 안 늦으면 몇 분 차이든 알린다. 대안도 늦으면 `MIN_SAVING_WHEN_STILL_LATE` 분
   이상 덜 늦을 때만 알린다.

## 도착 예상은 학습 보정을 같은 식으로 적용한다

카카오 원값끼리 비교하지 않는다. 알람 계산과 같은 식(`estimators.estimate_travel`)으로
걷는 속도·시간대 계수·경로 보정을 곱한 **평균**을 쓴다. τ 분위수가 아니다 — "늦을 것 같다"
는 기대값의 말이다. 시간대 구간은 지금 시각으로 고른다(지금 출발하는 셈이다).

## 실시간 도착은 필요할 때만 붙인다

카카오 대중교통 시간에는 첫 승차 전 대기가 평균값으로 들어 있다(`clients._segments` 의
`wait`). 실제 다음 차가 언제 오는지에 따라 늦음이 갈린다. 그래서 대안이 있고 고른 경로의
여유가 `REALTIME_MARGIN_MINUTES` 분보다 적을 때만 두 경로의 첫 승차 실시간 도착을 불러
대기를 바꾼다. 1분마다 부르면 버스 API 쿼터가 마른다. 실시간을 못 받으면 카카오 대기 그대로다.
"""

from __future__ import annotations

import copy
import math
from dataclasses import dataclass
from datetime import datetime, timedelta

from django.utils import timezone

from apps.routing import realtime
from apps.routing.models import walk_factor_for

from . import estimators
from .services import walk_raw_minutes

# 대안도 늦을 때 이만큼은 덜 늦어야 바꾸자고 한다. 1분 차이로 노선을 바꾸게 하면 환승
# 실패 위험이 이득보다 크다.
MIN_SAVING_WHEN_STILL_LATE = 2

# 고른 경로의 여유가 이보다 적으면 실시간 도착을 불러 다시 본다.
REALTIME_MARGIN_MINUTES = 5

REASON_LATE_NOW = "late_now"


@dataclass(frozen=True)
class Arrival:
    route_key: str
    label: str
    arrive_at: datetime
    # 일정 시작보다 늦는 분(반올림). 안 늦으면 0.
    late_minutes: int
    # 첫 승차 대기를 실시간 도착으로 바꿨는지.
    realtime: bool


def _round(minutes: float) -> int:
    return int(math.floor(minutes + 0.5))


def late_minutes(arrive_at: datetime, start_at: datetime) -> int:
    return max(0, _round((arrive_at - start_at).total_seconds() / 60))


def realtime_minutes(route: dict) -> tuple[float, bool]:
    """카카오 원값에서 첫 승차 전 대기를 실시간 다음 차로 바꾼 분. `(분, 바꿨나)`.

    첫 승차 지점까지 걷는 동안 떠나는 차는 못 탄다. 걸어서 닿은 뒤 오는 첫 차를 기다린다.
    같은 구간의 대체 버스(`alt_vehicles`)도 탈 수 있는 차로 본다. 실시간이 없으면 그대로다.
    """
    raw = float(route["minutes"])
    walk_before = 0
    wait = 0
    for segment in route.get("segments") or []:
        kind = segment.get("kind")
        if kind in ("bus", "subway"):
            seconds = [a.get("seconds") for a in segment.get("arrivals") or []]
            for vehicle in segment.get("alt_vehicles") or []:
                seconds += [a.get("seconds") for a in vehicle.get("arrivals") or []]
            catchable = sorted(s for s in seconds if s is not None and s >= walk_before)
            if not catchable:
                return raw, False
            real_wait = catchable[0] - walk_before
            return max(1.0, raw + (real_wait - wait) / 60), True
        if kind == "walk":
            walk_before += int(segment.get("seconds") or 0)
        elif kind == "wait":
            wait += int(segment.get("seconds") or 0)
    return raw, False


def _arrival(event, route: dict, *, now: datetime, walk_factor: float, minutes: float,
             used_realtime: bool) -> Arrival:
    """`route`(key·mode·segments 를 가진 경로 후보)로 지금 출발하면 언제 닿나."""
    estimate = estimators.estimate_travel(
        minutes=minutes,
        route_key=route.get("key") or "",
        mode=route.get("mode") or "",
        depart_at=now,
        walk_minutes=float(walk_raw_minutes(route) or 0.0),
        walk_factor=walk_factor,
    )
    arrive_at = (now + timedelta(minutes=estimate.distribution.mean)).replace(microsecond=0)
    return Arrival(
        route_key=route.get("key") or "",
        label=route.get("label") or route.get("detail") or "",
        arrive_at=arrive_at,
        late_minutes=late_minutes(arrive_at, event.start_at),
        realtime=used_realtime,
    )


def assess(event, route: dict, *, now: datetime | None = None) -> dict:
    """`/api/routes/live` 응답에 붙일 `arrival`·`switch_suggestion`.

    `route` 는 지금 위치에서 다시 푼 고른 경로(`clients.resolve_route` 결과)다. 대중교통이면
    같은 응답의 더 빠른 후보가 `alternative` 로 붙어 있다.
    """
    now = now or timezone.now()
    walk_factor = walk_factor_for(event.user_id)
    chosen = dict(route)
    alt = route.get("alternative") or {}
    alternative = (
        {
            "key": alt["key"],
            "label": alt.get("label") or "",
            "mode": alt.get("mode") or "",
            "minutes": alt["minutes"],
            "segments": alt.get("segments") or [],
        }
        if alt.get("key") and alt.get("minutes") is not None
        else None
    )

    def arrive(item: dict, minutes: float, used: bool) -> Arrival:
        return _arrival(event, item, now=now, walk_factor=walk_factor, minutes=minutes,
                        used_realtime=used)

    current = arrive(chosen, float(chosen["minutes"]), False)
    other = arrive(alternative, float(alternative["minutes"]), False) if alternative else None

    slack = (event.start_at - current.arrive_at).total_seconds() / 60
    if other is not None and slack < REALTIME_MARGIN_MINUTES:
        # 실시간은 구간에 제자리로 붙는다. 사본에 붙여 경로 응답 자체는 그대로 둔다.
        chosen = dict(chosen, segments=copy.deepcopy(chosen.get("segments") or []))
        alternative = dict(alternative, segments=copy.deepcopy(alternative["segments"]))
        realtime.enrich_candidates([chosen, alternative])
        current = arrive(chosen, *realtime_minutes(chosen))
        other = arrive(alternative, *realtime_minutes(alternative))

    return {
        "arrival": _arrival_json(event, current),
        "switch_suggestion": _suggestion(current, other),
    }


def should_switch(current: Arrival, other: Arrival | None) -> bool:
    if other is None or current.late_minutes < 1:
        return False
    if other.arrive_at >= current.arrive_at:
        return False
    if other.late_minutes < 1:
        return True
    saving = (current.arrive_at - other.arrive_at).total_seconds() / 60
    return saving >= MIN_SAVING_WHEN_STILL_LATE


def _suggestion(current: Arrival, other: Arrival | None) -> dict | None:
    if not should_switch(current, other):
        return None
    return {
        "reason": REASON_LATE_NOW,
        "route_key": other.route_key,
        "label": other.label,
        "arrive_at": timezone.localtime(other.arrive_at).isoformat(),
        "late_minutes": other.late_minutes,
        "saves_minutes": max(1, _round((current.arrive_at - other.arrive_at).total_seconds() / 60)),
        "realtime": other.realtime,
    }


def _arrival_json(event, current: Arrival) -> dict:
    return {
        "route_key": current.route_key,
        "start_at": timezone.localtime(event.start_at).isoformat(),
        "arrive_at": timezone.localtime(current.arrive_at).isoformat(),
        "late_minutes": current.late_minutes,
        "realtime": current.realtime,
    }
