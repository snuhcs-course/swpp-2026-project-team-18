"""알람 계산.

    알람 시각 = 일정 시작 − 안전 버퍼 − (준비 + 이동)의 τ 분위수

## 1단계에서 2단계로

전에는 점추정치를 그대로 뺐다.

    알람 = 시작 − 10분 − 이동(카카오) − 준비(온보딩 값)

지금은 준비·이동을 **분포**로 만들고 합성한 뒤 τ 분위수를 쓴다. τ 는
사용자가 고르는 확신도다(0.5~0.999). 태그가 기본값을 주고 일정별로
덮을 수 있다.

**합성 후에 분위수를 구한다.** 준비의 90% 분위수와 이동의 90% 분위수를
따로 구해 더하면 둘이 동시에 나쁜 경우를 가정하게 되어 결합 확신도가
99% 가 된다. 그만큼 알람이 이르고 사용자는 잠을 잃는다.

## 관측이 없으면 1단계와 똑같이 동작한다

분산의 출처가 없으면 `sd=0` 이 되어 τ 분위수가 평균과 같아진다. 즉

    블록 없음 + 경로 보정 없음  →  전과 동일한 알람, 확률은 null

이게 의도다. 변동성을 모르는데 τ 를 반영하면 없는 근거로 사용자의 잠을
빼앗는다. 분산은 사용자가 신고한 블록 범위나 관측된 경로 보정에서만 나온다.
근거는 `estimators.py` 상단에 정리했다.

| 항목 | 출처 | 변동성 |
| --- | --- | --- |
| 이동 시간 | 카카오 경로 API 실측 조회 | `RouteCorrection` 이 있을 때만 |
| 준비 시간 | 루틴 블록 범위 + 블록 관측 | 블록이 있을 때만 |
| 안전 버퍼 | 고정 10분 | 없음(확정값) |
| 정시 도착 확률 | 위 둘 모두 변동성이 있을 때 | — |
"""

from __future__ import annotations

import logging
from datetime import timedelta

from django.db import transaction
from django.db.models import Avg, Count, StdDev

from apps.events.models import Event
from apps.routing import clients

from . import estimators
from .models import AlarmPlan

logger = logging.getLogger(__name__)

# 문 앞에서 실제 출발까지의 여유. back-spec 4.5 door_buffer_minutes.
DEFAULT_BUFFER_MINUTES = 10

# 프로필에 준비시간이 없을 때 쓰는 값. 온보딩(S1)에서 사용자가 답하면 대체된다.
FALLBACK_PREP_MINUTES = 30


def _block_observation_stats(user_id: int) -> dict[int, tuple[float, float, int]]:
    """블록별 관측 통계를 한 번에 가져온다.

    `{block_id: (평균, 표준편차, 표본수)}`. 블록마다 쿼리를 날리면 N+1 이다.

    SQLite 에는 `STDDEV` 가 없어서 `StdDev` 집계가 실패할 수 있다. 그 경우
    평균과 표본수만 쓰고 표준편차는 None 으로 둔다 — `block_distribution` 이
    신고 범위의 폭으로 대체한다.
    """
    from apps.routines.models import BlockObservation

    qs = BlockObservation.objects.filter(user_id=user_id).values("block_id")
    try:
        rows = list(
            qs.annotate(
                avg=Avg("duration_minutes"),
                sd=StdDev("duration_minutes"),
                n=Count("id"),
            )
        )
    except Exception:  # noqa: BLE001 - 백엔드가 STDDEV 를 지원하지 않는 경우
        logger.info("StdDev 집계를 쓸 수 없다. 평균과 표본수만 사용한다.")
        rows = [
            {**r, "sd": None}
            for r in qs.annotate(avg=Avg("duration_minutes"), n=Count("id"))
        ]

    return {
        r["block_id"]: (r["avg"], r["sd"], r["n"])
        for r in rows
        if r["avg"] is not None
    }


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
        "confidence_basis": "",
        "prep_source": "",
        "prep_breakdown": [],
        "total_quantile_minutes": None,
        "travel_mode": "",
        "travel_source": "",
        "route_summary": "",
        "route_key": "",
        "route_detail": "",
        # 경로를 못 구한 상태에서도 명시적으로 비운다. 이전 계산의 폴리라인이
        # 남아 있으면 지도가 **지금 계획과 다른 경로**를 그린다.
        "route_path": [],
        "route_distance_m": None,
        # 더 빠른 대안도 같은 이유로 비운다. 낡은 대안이 남으면 지도가 이미
        # 사라진 노선을 "지금 더 빠름" 으로 알린다.
        "alt_route_key": "",
        "alt_route_label": "",
        "alt_faster_minutes": None,
        "alt_route_path": [],
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

    # 4) 분포 추정.
    #
    # 준비: 루틴 블록이 있으면 블록 조합에서, 없으면 온보딩 값 하나에서.
    # 이동: 카카오 점추정치에 관측된 경로 보정을 적용.
    tau = event.effective_tau
    buffer_min = DEFAULT_BUFFER_MINUTES

    # **집에서 출발하지 않는 일정은 준비 시간이 해당되지 않는다.**
    #
    # 준비 시간은 집에서 씻고 옷을 입고 나서는 시간이다. 이미 밖에 있는 사람이
    # 다음 일정으로 갈 때는 그 항목이 없다. `origin_lat` 이 있다는 것 자체가
    # "이 일정만의 출발지를 따로 골랐다" 는 뜻이므로(`Event.resolve_origin`)
    # 그것을 신호로 쓴다. 좌표를 집과 비교하지 않는 이유는, 사용자가 집 근처
    # 카페를 골랐어도 그건 집이 아니고 준비 단계도 아니기 때문이다.
    #
    # `None` 을 넘긴다. 0분으로 만들어 넘기면 정시 도착 확률이 영영 안 나오고
    # 화면이 "준비 시간 0분" 을 그린다 — `compute_alarm_math` 의 설명 참고.
    prep = (
        None
        if event.origin_lat is not None
        else estimators.estimate_prep(
            event,
            profile,
            fallback_minutes=FALLBACK_PREP_MINUTES,
            observations=_block_observation_stats(event.user_id),
        )
    )
    travel = estimators.estimate_travel(
        minutes=route["minutes"],
        route_key=route.get("key", "") or "",
        mode=route.get("mode", "") or "",
        # 보정은 출발 시각의 시간대로 나뉘어 있다. 출발 시각은 알람 계산
        # 결과라 아직 모르므로 일정 시작 시각으로 근사한다 — 같은 아침이라
        # 시간대 버킷이 거의 같다.
        depart_at=event.start_at,
    )

    math_ = estimators.compute_alarm_math(prep, travel, buffer_min, tau)

    # 5) 시각으로 환산.
    #
    # 도착 예정은 일정 시작에서 버퍼를 뺀 시각이다. 출발 시각은 거기서
    # 이동 시간을 뺀 값이고, 알람은 거기서 준비 시간을 뺀 값이다.
    arrive_at = event.start_at - timedelta(minutes=math_.buffer_minutes)
    depart_by = arrive_at - timedelta(minutes=math_.travel_minutes)
    alarm_at = depart_by - timedelta(minutes=math_.prep_minutes)

    defaults.update(
        {
            "status": AlarmPlan.Status.OK,
            "alarm_at": alarm_at,
            "depart_by": depart_by,
            "arrive_at": arrive_at,
            "prep_minutes": math_.prep_minutes,
            "travel_minutes": math_.travel_minutes,
            "buffer_minutes": math_.buffer_minutes,
            "tau_used": tau,
            "on_time_probability": math_.on_time_probability,
            "confidence_basis": math_.confidence_basis,
            "prep_source": math_.prep_source,
            "prep_breakdown": math_.prep_breakdown,
            "total_quantile_minutes": round(
                math_.total_minutes - math_.buffer_minutes, 2
            ),
            "travel_mode": (route.get("mode") or "")[:20],
            # 보정이 적용됐으면 그 사실을 남긴다. 나중에 실측과 비교할 때
            # "카카오 원값" 과 "보정값" 을 구분해야 한다.
            "travel_source": (
                f"{route.get('source', '')}+{travel.source}"
                if travel.source != "kakao"
                else (route.get("source") or "")
            )[:30],
            # 요약 문자열은 clients 가 만든다. 같은 규칙을 두 곳에 두지 않는다.
            "route_summary": (route.get("summary") or f"{math_.travel_minutes}분")[:200],
            "route_key": (route.get("key") or "")[:120],
            "route_detail": (route.get("detail") or "")[:120],
            # 지도에 그릴 경로선과 진행률의 분모. 좌표를 못 받았으면 빈 배열이고
            # 앱은 지도 자리에 안내만 띄운다 — 알람 계산은 그대로 성립한다.
            "route_path": route.get("path") or [],
            "route_distance_m": route.get("path_distance_m"),
            # 지금 더 빠른 대안. 고른 경로는 위에서 그대로 저장했고, 이건 지도에
            # 겹쳐 보여 주기만 한다. `resolve_route` 가 대중교통을 고른 경우에만
            # 채워 주고, 같은 경로거나 더 느리면 아예 붙이지 않는다.
            **_alternative_fields(route),
        }
    )
    return _upsert(event, defaults)


def _alternative_fields(route: dict) -> dict:
    """경로 응답의 `alternative` 를 계획 필드로 옮긴다.

    `resolve_route` 가 **고른 경로와 다르고 실제로 더 빠를 때만** 붙이므로
    여기서 다시 판단하지 않는다. 없으면 빈 값을 돌려주는 것이 중요하다 —
    `defaults` 의 초기값을 덮어써야 이전 계산의 대안이 남지 않는다.
    """
    alt = route.get("alternative") or {}
    faster = alt.get("faster_minutes")
    if not alt.get("key") or not faster or faster <= 0:
        return {
            "alt_route_key": "",
            "alt_route_label": "",
            "alt_faster_minutes": None,
            "alt_route_path": [],
        }
    return {
        "alt_route_key": alt["key"][:120],
        "alt_route_label": (alt.get("label") or "")[:120],
        "alt_faster_minutes": faster,
        "alt_route_path": alt.get("path") or [],
    }


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
