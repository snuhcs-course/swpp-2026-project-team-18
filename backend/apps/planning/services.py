"""알람 계산.

    알람 시각 = 일정 시작 − 안전 버퍼 − 이동 시간 − 준비 시간

**지금 계산할 수 있는 것과 못 하는 것**

| 항목 | 출처 | 상태 |
| --- | --- | --- |
| 이동 시간 | 카카오 경로 API 실측 조회 | 가능 |
| 준비 시간 | 프로필 `onboarding_prep_min` | 가능 (사용자가 답한 값) |
| 안전 버퍼 | 고정 10분 | 가능 |
| **정시 도착 확률** | 관측 분포 필요 | **불가 → null** |

확률을 못 만드는 이유는 두 가지다. 새 계정에 관측이 없고, 카카오 응답에도
변동성 정보가 없다(checklist "BE-P0-06 결론"). 그래서 `on_time_probability` 를
비워 두고 화면이 "학습 중" 으로 표시한다. 임의의 90% 를 넣으면 화면은
그럴싸해지지만 사용자를 속이는 것이다.

P3 에서 `TravelObservation` 이 쌓이면 분위수를 계산해 채운다.
"""

from __future__ import annotations

import logging
from datetime import timedelta

from django.db import transaction

from apps.events.models import Event
from apps.routing import clients

from .models import AlarmPlan

logger = logging.getLogger(__name__)

# 문 앞에서 실제 출발까지의 여유. back-spec 4.5 door_buffer_minutes.
DEFAULT_BUFFER_MINUTES = 10

# 프로필에 준비시간이 없을 때 쓰는 값. 온보딩(S1)에서 사용자가 답하면 대체된다.
FALLBACK_PREP_MINUTES = 30


@transaction.atomic
def compute_and_store(event: Event) -> AlarmPlan:
    """일정 하나의 알람 계획을 계산해 저장한다.

    이미 계획이 있으면 갱신한다. 일정 1건 = 계획 1건이다.
    카카오 경로 API 를 부르므로 목록 조회마다 호출하지 않는다. 일정 생성·수정
    시점과 명시적 재계산 요청에서만 부른다.
    """
    profile = event.user.profile

    defaults: dict = {
        "user": event.user,
        "alarm_at": None,
        "depart_by": None,
        "arrive_at": None,
        "prep_minutes": None,
        "travel_minutes": None,
        "buffer_minutes": None,
        "tau_used": None,
        "on_time_probability": None,
        "travel_mode": "",
        "travel_source": "",
        "route_summary": "",
        "route_key": "",
        "route_detail": "",
    }

    # 1) 장소가 없으면 이동 시간을 구할 수 없다.
    if event.place_id is None:
        defaults["status"] = AlarmPlan.Status.NO_PLACE
        return _upsert(event, defaults)

    # 2) 출발지를 모르면 이동 시간을 구할 수 없다.
    #
    # 일정에 지정된 출발지가 우선이고 없으면 프로필의 집이다. 둘 다 없을 때만
    # NO_HOME 이다 — 집을 설정하지 않았어도 일정마다 출발지를 골랐으면 알람을
    # 계산할 수 있다.
    origin = event.resolve_origin(profile)
    if origin is None:
        defaults["status"] = AlarmPlan.Status.NO_HOME
        return _upsert(event, defaults)
    origin_lat, origin_lng, _origin_label = origin

    # 3) 경로 조회.
    #
    # 사용자가 경로를 골랐으면 그 수단만 다시 조회한다(호출 1회). 저장해 둔
    # 소요시간을 재사용하지 않는다 — 배차가 바뀌면 낡은 값이고, 클라이언트가
    # 보낸 값은 조작할 수 있다.
    #
    # 고르지 않았으면 서버가 도보·대중교통 중 빠른 쪽을 택한다.
    # 출발지는 2번에서 정한 값을 쓴다. 여기서 프로필 집을 다시 읽으면, 사용자가
    # 경로 선택 화면에서 고른 출발지와 달라져 엉뚱한 소요시간이 나온다.
    if event.route_key:
        route, degraded = clients.resolve_route(
            event.route_key,
            start_lat=origin_lat,
            start_lng=origin_lng,
            end_lat=event.place.lat,
            end_lng=event.place.lng,
        )
    else:
        route, degraded = clients.best_route(
            start_lat=origin_lat,
            start_lng=origin_lng,
            end_lat=event.place.lat,
            end_lng=event.place.lng,
        )
    if degraded or not route:
        defaults["status"] = AlarmPlan.Status.ROUTE_FAILED
        return _upsert(event, defaults)

    # 4) 계산
    prep = profile.onboarding_prep_min or FALLBACK_PREP_MINUTES
    travel = route["minutes"]
    buffer_min = DEFAULT_BUFFER_MINUTES

    arrive_at = event.start_at - timedelta(minutes=buffer_min)
    depart_by = arrive_at - timedelta(minutes=travel)
    alarm_at = depart_by - timedelta(minutes=prep)

    defaults.update(
        {
            "status": AlarmPlan.Status.OK,
            "alarm_at": alarm_at,
            "depart_by": depart_by,
            "arrive_at": arrive_at,
            "prep_minutes": prep,
            "travel_minutes": travel,
            "buffer_minutes": buffer_min,
            "tau_used": event.effective_tau,
            # 관측이 없으므로 확률은 비운다. 위 표 참고.
            "on_time_probability": None,
            "travel_mode": route.get("mode", "")[:20],
            "travel_source": route.get("source", "")[:30],
            # 요약 문자열은 clients 가 만든다. 같은 규칙을 두 곳에 두지 않는다.
            "route_summary": (route.get("summary") or f"{travel}분")[:200],
            "route_key": (route.get("key") or "")[:120],
            "route_detail": (route.get("detail") or "")[:120],
        }
    )
    return _upsert(event, defaults)


def _upsert(event: Event, defaults: dict) -> AlarmPlan:
    plan, _created = AlarmPlan.objects.update_or_create(event=event, defaults=defaults)
    return plan


def recompute_for_user(user, only_future: bool = True) -> int:
    """사용자의 모든 일정 계획을 다시 계산한다.

    집 위치를 새로 설정했을 때 쓴다. 그때까지 `NO_HOME` 이던 계획들이 한꺼번에
    계산 가능해진다.

    **쿼터 주의** — 일정 수만큼 카카오 경로 API 를 부른다. 무료 쿼터가 일
    1,000건이므로 일정이 많으면 한 번에 다 쓰지 않게 상한을 둔다.
    """
    from django.utils import timezone

    qs = Event.objects.filter(user=user).select_related("place", "tag", "user__profile")
    if only_future:
        qs = qs.filter(start_at__gte=timezone.now())

    count = 0
    for event in qs[:50]:
        compute_and_store(event)
        count += 1
    return count
