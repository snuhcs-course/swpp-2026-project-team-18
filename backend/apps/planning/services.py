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
from rest_framework.exceptions import ValidationError

from apps.events.geo import validate_route_distance
from apps.events.models import Event
from apps.routing import clients

from . import buffers, estimators, history, prep_observations
from .models import AlarmPlan, PlanRevision

logger = logging.getLogger(__name__)

# 문 앞에서 실제 출발까지의 여유. back-spec 4.5 door_buffer_minutes.
# 조정 기록이 없는 사용자의 값이다. 지각 이력으로 사용자마다 바뀐다(`buffers`).
DEFAULT_BUFFER_MINUTES = buffers.DEFAULT_MINUTES

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

    # 출발 뒤에 찍힌 기록은 이동 시간이 섞여 있다(`prep_observations`).
    qs = prep_observations.exclude_after_departure(
        BlockObservation.objects.filter(user_id=user_id)
    ).values("block_id")
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
def compute_and_store(event: Event, *, trigger: str = "") -> AlarmPlan:
    """일정 하나의 알람 계획을 계산해 저장한다.

    이미 계획이 있으면 갱신한다. 일정 1건 = 계획 1건이다.
    카카오 경로 API 를 부르므로 목록 조회마다 호출하지 않는다. 일정 생성·수정
    시점과 명시적 재계산 요청에서만 부른다.

    `trigger` 는 부른 곳(`PlanRevision.Trigger`)이다. 계획 이력에 "왜 다시 계산했나" 로
    남는다. 비워도 된다(관리 명령·테스트). (Partly AI-generated (Claude))
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
        "buffer_source": "",
        "tau_used": None,
        "on_time_probability": None,
        "confidence_basis": "",
        "prep_source": "",
        "prep_breakdown": [],
        "total_quantile_minutes": None,
        "travel_mode": "",
        "travel_source": "",
        "travel_sample_count": 0,
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

    # 1) 집 목적지는 계산할 때마다 현재 프로필에서 해석한다.
    destination = event.resolve_destination(profile)
    if destination is None:
        defaults["status"] = (
            AlarmPlan.Status.NO_HOME if event.destination_is_home else AlarmPlan.Status.NO_PLACE
        )
        return _upsert(event, defaults, trigger=trigger)

    # 2) 출발지를 모르면 이동 시간을 구할 수 없다.
    #
    # 일정에 지정된 출발지가 우선이고 없으면 프로필의 집이다. 둘 다 없을 때만
    # NO_HOME 이다 — 집을 설정하지 않았어도 일정마다 출발지를 골랐으면 알람을
    # 계산할 수 있다.
    origin = event.resolve_origin(profile)
    if origin is None:
        defaults["status"] = AlarmPlan.Status.NO_HOME
        return _upsert(event, defaults, trigger=trigger)
    origin_lat, origin_lng, _origin_label = origin
    dest_lat, dest_lng, _dest_label = destination

    # 집 변경으로 기존 일정의 양 끝이 가까워질 수 있다. 낡은 계획을 지우고
    # 외부 경로 호출 전에 중단한다. API 저장 검증과 같은 거리 규칙을 쓴다.
    try:
        validate_route_distance(origin_lat, origin_lng, dest_lat, dest_lng)
    except ValidationError:
        defaults["status"] = AlarmPlan.Status.ROUTE_FAILED
        return _upsert(event, defaults, trigger=trigger)

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
            end_lat=dest_lat,
            end_lng=dest_lng,
        )
    else:
        route, degraded = clients.best_route(
            start_lat=origin_lat,
            start_lng=origin_lng,
            end_lat=dest_lat,
            end_lng=dest_lng,
        )
    if degraded or not route:
        kept = _keep_previous_plan(event, trigger)
        if kept is not None:
            return kept
        defaults["status"] = AlarmPlan.Status.ROUTE_FAILED
        return _upsert(event, defaults, trigger=trigger)

    # 4) 분포 추정.
    #
    # 준비: 루틴 블록이 있으면 블록 조합에서, 없으면 온보딩 값 하나에서.
    # 이동: 카카오 점추정치에 관측된 경로 보정을 적용.
    prep, travel = estimate_components(event, route)

    # 5) 시각으로 환산. 경로를 다시 조회하지 않는 재계산(`refresh`)과 같은 규칙이다.
    defaults.update(alarm_fields(event, prep, travel, route_source=route.get("source") or ""))
    defaults.update(
        {
            "travel_mode": (route.get("mode") or "")[:20],
            # 요약 문자열은 clients 가 만든다. 같은 규칙을 두 곳에 두지 않는다.
            "route_summary": (route.get("summary") or f"{defaults['travel_minutes']}분")[:200],
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
    return _upsert(
        event,
        defaults,
        trigger=trigger,
        # 카카오가 준 분 그대로(G2). 계획의 `travel_minutes` 는 보정·τ 분위수가 들어간 값이다.
        route_minutes_raw=_raw_minutes(route),
    )


def _keep_previous_plan(event: Event, trigger: str) -> AlarmPlan | None:
    """다시 계산의 경로 조회가 실패하면 직전 계획을 돌려준다. 없으면 None.

    다시 계산은 일정을 바꾸지 않는다. 출발지·목적지·경로가 그대로이니 직전
    계획은 여전히 맞는 계획이다. 실패한 조회는 카카오 장애나 쿼터 소진 같은
    일시적 사정일 뿐인데, 이걸 `ROUTE_FAILED` 로 덮으면 `alarm_at` 이 비고
    앱이 등록해 둔 알람을 취소한다. 기기의 경로 갱신 워커가 15분마다 이걸
    부르므로 아침에 한 번만 실패해도 알람이 사라진다.

    생성·수정·가져오기·블록·프로필 변경은 입력 자체가 바뀌었으므로 직전
    계획이 더는 맞지 않는다. 그때는 지금처럼 비운다.

    계획을 건드리지 않으므로 `computed_at` 도 그대로다 — 화면의 "N분 전 계산"
    이 실제로 계산한 시각을 계속 가리킨다. 이력도 남기지 않는다.
    """
    if trigger != PlanRevision.Trigger.RECOMPUTE:
        return None
    plan = AlarmPlan.objects.filter(event=event).first()
    if plan is None or plan.status != AlarmPlan.Status.OK or plan.alarm_at is None:
        return None
    logger.warning("일정 %s 다시 계산의 경로 조회 실패. 직전 계획을 유지한다", event.pk)
    return plan


# Partly AI-generated (Claude)
def alarm_fields(
    event: Event,
    prep: estimators.PrepEstimate | None,
    travel: estimators.TravelEstimate,
    *,
    route_source: str,
    buffer: buffers.Buffer | None = None,
) -> dict:
    """분포에서 알람 시각과 그 근거 필드를 만든다. 경로 필드(경로선·요약·대안)는 넣지 않는다.

    도착 예정은 일정 시작에서 버퍼를 뺀 시각이다. 출발 시각은 거기서 이동 시간을 뺀
    값이고, 알람은 거기서 준비 시간을 뺀 값이다. 버퍼는 사용자의 지금 버퍼다
    (`buffers.current`). 여러 일정을 한 번에 계산하면 미리 구해 넘긴다.
    """
    tau = event.effective_tau
    if buffer is None:
        buffer = buffers.current(event.user_id)
    math_ = estimators.compute_alarm_math(prep, travel, buffer.minutes, tau)

    arrive_at = event.start_at - timedelta(minutes=math_.buffer_minutes)
    depart_by = arrive_at - timedelta(minutes=math_.travel_minutes)
    alarm_at = depart_by - timedelta(minutes=math_.prep_minutes)
    return {
        "status": AlarmPlan.Status.OK,
        "alarm_at": alarm_at,
        "depart_by": depart_by,
        "arrive_at": arrive_at,
        "prep_minutes": math_.prep_minutes,
        "travel_minutes": math_.travel_minutes,
        "buffer_minutes": math_.buffer_minutes,
        "buffer_source": buffer.source,
        "tau_used": tau,
        "on_time_probability": math_.on_time_probability,
        "confidence_basis": math_.confidence_basis,
        "prep_source": math_.prep_source,
        "prep_breakdown": math_.prep_breakdown,
        "total_quantile_minutes": round(math_.total_minutes - math_.buffer_minutes, 2),
        "travel_sample_count": travel.sample_count,
        # 보정이 적용됐으면 그 사실을 남긴다. 나중에 실측과 비교할 때
        # "카카오 원값" 과 "보정값" 을 구분해야 한다.
        "travel_source": (
            f"{route_source}+{travel.source}" if travel.source != "kakao" else route_source
        )[:30],
    }


def prep_inputs(user_id: int) -> tuple[dict, list[float]]:
    """준비 시간 추정의 사용자별 재료(블록 관측 통계, GPS 준비 표본).

    일정마다 같은 값이다. 여러 일정을 한 번에 다시 계산할 때 한 번만 읽는다.
    """
    return _block_observation_stats(user_id), prep_observations.gps_prep_samples(user_id)


def estimate_components(
    event: Event, route: dict, *, inputs: tuple[dict, list[float]] | None = None,
) -> tuple[estimators.PrepEstimate | None, estimators.TravelEstimate]:
    """Shared preparation/travel model for stored plans and risk previews.

    ``route["minutes"]`` must be the upstream raw estimate, never the corrected
    or quantile-allocated ``AlarmPlan.travel_minutes``. ``inputs`` is
    ``prep_inputs(event.user_id)``; pass it when computing many events of one user.
    """
    profile = event.user.profile
    # **준비 단계가 없는 일정은 준비 시간을 넣지 않는다.**
    #
    # 준비 시간은 씻고 옷을 입고 나서는 시간이다. 이미 밖에 있는 사람이 다음
    # 일정으로 갈 때는 그 항목이 없다. 기본은 출발지를 따로 골랐으면 빼고, 사용자가
    # 일정마다 넣거나 뺄 수 있다(친구 집에서 자고 출발하는 아침). 규칙은
    # `Event.prep_applies` 다.
    #
    # `None` 을 넘긴다. 0분으로 만들어 넘기면 정시 도착 확률이 영영 안 나오고
    # 화면이 "준비 시간 0분" 을 그린다 — `compute_alarm_math` 의 설명 참고.
    if not event.prep_applies:
        prep = None
    else:
        observations, gps_samples = inputs or prep_inputs(event.user_id)
        prep = estimators.estimate_prep(
            event,
            profile,
            fallback_minutes=FALLBACK_PREP_MINUTES,
            observations=observations,
            gps_samples=gps_samples,
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

    return prep, travel


# Partly AI-generated (Claude)
def _raw_minutes(route: dict) -> int | None:
    """경로 응답의 분을 이력용 정수로. 읽을 수 없으면 None(이력만 비고 계산은 그대로다)."""
    try:
        minutes = int(round(float(route.get("minutes"))))
    except (TypeError, ValueError):
        return None
    return minutes if minutes >= 0 else None


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


def _upsert(
    event: Event,
    defaults: dict,
    *,
    trigger: str = "",
    route_minutes_raw: int | None = None,
) -> AlarmPlan:
    plan, _created = AlarmPlan.objects.update_or_create(event=event, defaults=defaults)
    # Partly AI-generated (Claude)
    # 같은 트랜잭션에서 이력을 남긴다(db-schema.md A2). 계획은 덮어쓰지만 이력은 쌓인다 —
    # 결정 시점의 계획을 나중에 고를 수 있게 한다. 이력 쓰기가 실패해도 계획은 남는다.
    history.record_revision(
        event, plan, trigger=trigger, route_minutes_raw=route_minutes_raw
    )
    return plan


def recompute_for_user(user, only_future: bool = True, *, trigger: str = "") -> int:
    """사용자의 모든 일정 계획을 다시 계산한다.

    집 위치를 새로 설정했을 때 쓴다. 그때까지 `NO_HOME` 이던 계획들이 한꺼번에
    계산 가능해진다.

    **쿼터 주의** — 일정 수만큼 카카오 경로 API 를 부른다. 무료 쿼터가 일
    1,000건이므로 일정이 많으면 한 번에 다 쓰지 않게 상한을 둔다.
    """
    from django.utils import timezone
    from apps.events.recurrence import in_window

    qs = Event.objects.filter(user=user).select_related("place", "tag", "user__profile", "recurrence")
    if only_future:
        qs = qs.filter(start_at__gte=timezone.now())

    count = 0
    for event in qs[:50]:
        if not in_window(event):
            continue
        compute_and_store(event, trigger=trigger)
        count += 1
    return count
