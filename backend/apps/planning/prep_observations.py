# Partly AI-generated (Claude)
"""준비 시간 학습 재료를 고른다. GPS 출발이 기준이다.

## 왜 GPS 출발인가

준비 시간의 학습 재료는 지금까지 사용자가 아침 기록 화면에서 누른 블록 기록
(`BlockObservation`)뿐이었다. 그 기록은 **사람이 눌러야 생긴다.** 안 누르고
나가면 기록이 없고, 나간 뒤에 누르면 이동 시간까지 준비 소요로 들어간다.

GPS 출발(`TripObservation`, kind=depart, detector=gps)은 사용자가 아무것도 하지
않아도 남는다. 알람을 끈 시각(`AlarmLog`, kind=dismissed)부터 집 반경을 벗어난
시각까지가 그날 실제로 쓴 준비 시간이다. 둘 다 이미 서버에 올라오므로 새 표를
만들지 않고 파생한다(이동 시간을 출발·도착 쌍에서 파생하는 것과 같다).

## 신뢰 순서

    GPS 출발 기준 총시간  >  블록 기록  >  온보딩 값

블록 기록은 버리지 않는다. 총시간 안에서 항목별 비율을 나누는 데 쓴다
(`estimators.estimate_prep`). 대신 **같은 일정의 GPS 출발보다 늦게 찍힌 블록
기록은 학습에서 뺀다.** 출발 관측이 블록 기록보다 늦게 올라올 수 있어서 업로드
때 거절하지 않고, 학습할 때 거른다.
"""

from __future__ import annotations

from django.db.models import Exists, OuterRef

# 사용자의 예상 준비 시간 대비 이 비율 밖이면 판정 오류로 본다. 이동 시간 학습의
# 실제/예측 비율 기준(`routing.models.MIN_FACTOR/MAX_FACTOR`)과 같다.
#
# 고정 분(5~240분)으로 거르면 사람마다 다른 준비 시간을 무시한다. 평소 15분인
# 사람의 200분과 평소 90분인 사람의 200분은 다른 뜻이다.
#
# **좁히면 안 되는 이유.** 기준이 되는 예상값이 틀렸을 수 있다(온보딩에 30분이라
# 답했는데 실제로는 70분). 창이 좁으면 실제 값이 전부 버려지고, 예상값은 GPS 로
# 고쳐질 기회를 영영 잃는다. 기준은 GPS 를 섞기 전의 추정이라 되먹임은 없다.
GPS_PREP_MIN_RATIO = 0.25
GPS_PREP_MAX_RATIO = 4.0

# 비율과 별개인 절대 한계. 출발이 끄기와 같은 순간이거나, 끄기가 전날 것이면
# 비율을 따질 것도 없이 짝이 잘못된 것이다. 6시간은 아침 기록 세션의 유효 시간
# (`MorningSession.STALE_AFTER_MILLIS`)과 같다.
GPS_PREP_MIN_MINUTES = 1.0
GPS_PREP_MAX_MINUTES = 360.0

# 오차 반경이 이보다 큰 출발 fix 는 쓰지 않는다. 이동 시간 학습
# (`prediction.learning.MAX_ACCURACY_M`)과 같은 기준이다.
GPS_PREP_MAX_ACCURACY_M = 150.0

# 최근 몇 번의 아침만 볼지. 습관이 바뀌면 오래된 아침이 알람을 끌고 가지 않게 한다.
GPS_PREP_MAX_SAMPLES = 30


def exclude_after_departure(qs):
    """블록 기록 쿼리셋에서 같은 일정의 GPS 출발 **뒤에** 찍힌 기록을 뺀다.

    나간 뒤에 "마침" 을 누르면 그 블록의 소요에 이동 시간이 섞인다. 일정이 없는
    기록(일정이 지워진 경우)은 비교할 출발이 없으므로 그대로 둔다.
    """
    from apps.observations.models import TripObservation

    departed_before = TripObservation.objects.filter(
        event_id=OuterRef("event_id"),
        kind=TripObservation.Kind.DEPART,
        detector=TripObservation.Detector.GPS,
        observed_at__lt=OuterRef("client_recorded_at"),
    )
    return qs.filter(~Exists(departed_before))


def within_expected(samples: list[float], expected_minutes: float) -> list[float]:
    """예상 준비 시간 대비 0.25~4배 안의 표본만 최근 것부터 최대 30개 남긴다."""
    if expected_minutes <= 0:
        return samples[:GPS_PREP_MAX_SAMPLES]
    lo = expected_minutes * GPS_PREP_MIN_RATIO
    hi = expected_minutes * GPS_PREP_MAX_RATIO
    return [m for m in samples if lo <= m <= hi][:GPS_PREP_MAX_SAMPLES]


def gps_prep_samples(user_id: int) -> list[float]:
    """GPS 출발 기준 실제 준비 시간(분) 목록. 최근 아침부터.

    사용자별 비율 검사(`within_expected`)는 여기서 하지 않는다. 기준인 예상 준비
    시간이 일정마다(고른 블록마다) 달라서 추정기가 한다.

    일정마다 한 개다. **그 일정의 첫 GPS 출발** − **그 전 마지막 알람 끄기** 다.
    미루기 뒤 다시 울려 끈 경우 마지막 끄기부터 준비를 시작한 것으로 본다.

    버리는 것:
      - 출발지를 따로 고른 일정. 집에서 준비하고 나간 아침이 아니다(친구 집에서
        자고 준비를 넣은 아침도. 남의 집 준비는 평소 준비와 다르다)
      - 사용자가 준비를 뺀 일정(`include_prep` false). 준비하지 않고 나간 아침이다
      - 오차 반경이 150m 를 넘는 출발 fix
      - 출발 전에 끈 기록이 없는 일정(알람 없이 나갔거나 기록이 안 올라옴)
      - 1분 미만·6시간 초과(짝이 잘못된 것)
    """
    from apps.observations.models import AlarmLog, TripObservation

    departs = (
        TripObservation.objects.filter(
            user_id=user_id,
            kind=TripObservation.Kind.DEPART,
            detector=TripObservation.Detector.GPS,
            accuracy_m__lte=GPS_PREP_MAX_ACCURACY_M,
            event__origin_lat__isnull=True,
        )
        .exclude(event__include_prep=False)
        .order_by("event_id", "observed_at")
        .values_list("event_id", "observed_at")
    )
    first_depart: dict[int, object] = {}
    for event_id, observed_at in departs:
        first_depart.setdefault(event_id, observed_at)
    if not first_depart:
        return []

    last_dismiss: dict[int, object] = {}
    dismissals = AlarmLog.objects.filter(
        user_id=user_id,
        kind=AlarmLog.Kind.DISMISSED,
        event_id__in=first_depart.keys(),
    ).values_list("event_id", "occurred_at")
    for event_id, occurred_at in dismissals:
        if occurred_at > first_depart[event_id]:
            continue
        if event_id not in last_dismiss or occurred_at > last_dismiss[event_id]:
            last_dismiss[event_id] = occurred_at

    samples: list[float] = []
    for event_id, depart_at in sorted(first_depart.items(), key=lambda kv: kv[1], reverse=True):
        dismissed_at = last_dismiss.get(event_id)
        if dismissed_at is None:
            continue
        minutes = (depart_at - dismissed_at).total_seconds() / 60.0
        if GPS_PREP_MIN_MINUTES <= minutes <= GPS_PREP_MAX_MINUTES:
            samples.append(round(minutes, 1))
    return samples
