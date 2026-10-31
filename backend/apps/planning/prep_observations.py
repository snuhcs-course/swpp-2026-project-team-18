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

## 학습에서 빼는 블록 기록 (`usable_block_observations`)

| 조건 | 이유 |
| --- | --- |
| 일정을 "안 감" 으로 표시했다 | 그날 아침은 등교 준비가 아니다 |
| 같은 일정의 GPS 출발보다 늦게 찍혔다 | 나간 뒤에 눌러 이동 시간이 섞였다 |
| 블록 신고 범위 최소의 0.5배 미만·최대의 1.5배 초과 | 누르는 걸 잊었다가 한참 뒤에 눌렀거나 안 한 블록을 바로 눌렀다 |
| 사용자가 그 아침을 준비 학습에서 뺐다 | 아래 "사용자가 고른 아침" |

세 번째는 GPS 출발이 아예 잡히지 않은 아침을 위한 것이다. 출발 관측이 없으면
두 번째 조건이 아무것도 거르지 못한다. 기준은 GPS 준비 시간과 같은 폭(0.5~1.5배)이고,
신고 범위의 양 끝에 건다.

## 설정에서 크게 벗어난 아침은 뺀다 (사용자 10-29)

처음에는 폭을 0.25~4배로 넓게 잡았다. 신고가 틀렸어도(30분이라 했는데 실제 70분) 실제
값으로 고쳐지게 하려던 것이다. 그런데 준비 55분으로 정한 사용자의 준비 시간이 지나치게
늘었다. 4배면 220분까지 학습에 들어가서, 알람을 끄고 다시 잠든 아침이나 출발이 늦게
잡힌 아침이 그대로 섞인다. 사용자가 "55분 세팅한 거에서 지나치게 벗어난 건 학습에서
제외" 하자고 해서 **정한 값의 0.5~1.5배**만 쓴다(55분이면 27.5~82.5분).

대가: 정한 값이 실제와 크게 다르면 학습으로 고쳐지지 않는다. 그때는 설정을 고쳐야 한다.

## 사용자가 고른 아침도 뺀다 (사용자 10-30)

비율 기준은 정한 값에서 나오므로 사람이 보기에 이상한 아침이 남을 수 있다(55분이면
82.5분까지 들어간다). 사용자가 "80분 넘어가는 거 수기로 제외" 를 요청했다. 지우지 않고
일정에 표시만 붙인다(`Event.prep_learning_excluded_at`). 표시한 아침은 GPS 준비 시간과
블록 기록 모두에서 빠지고, 표시를 떼면 다시 들어간다. 붙이고 떼는 것은
`manage.py exclude_prep_mornings` 다. 리포트·알람·이동 시간 학습은 그대로다.

리포트(`reports.services`)는 이 조건으로 거르지 않는다. 사용자가 누른 기록은 그대로
보여 주고, 알람과 학습만 거른다. "안 감" 일정은 리포트에서도 빠진다.
"""

from __future__ import annotations

from django.db.models import Exists, ExpressionWrapper, F, FloatField, OuterRef, Value
from django.db.models.functions import Greatest

# 사용자가 정한 준비 시간(블록 신고 범위·온보딩 값) 대비 이 비율 밖이면 학습에서 뺀다.
#
# 고정 분(5~240분)으로 거르면 사람마다 다른 준비 시간을 무시한다. 평소 15분인
# 사람의 200분과 평소 90분인 사람의 200분은 다른 뜻이다.
#
# 0.25~4배였다가 0.5~1.5배로 좁혔다(사용자 10-29, 머리말 "설정에서 크게 벗어난 아침").
# 늘어나는 쪽이 더 좁다. 늦게 나간 아침은 준비가 길어서라기보다 알람을 끄고 다시 잠들었거나
# 출발이 늦게 잡힌 경우가 많다. 기준은 블록 기록도 섞기 전의 정한 값이라 되먹임은 없다.
GPS_PREP_MIN_RATIO = 0.5
GPS_PREP_MAX_RATIO = 1.5

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

# 블록 기록이 블록 신고 범위(최소~최대)의 이 비율 밖이면 학습에서 뺀다. 위 GPS 기준과
# 같은 폭이다. 하한은 신고 최소의 0.5배, 상한은 신고 최대의 1.5배다.
BLOCK_MIN_RATIO = 0.5
BLOCK_MAX_RATIO = 1.5
# 신고 최대가 0분인 블록도 상한이 0 이 되지 않게 이 값 아래로 내리지 않는다.
BLOCK_RATIO_FLOOR_MINUTES = 1


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


def exclude_skipped(qs):
    """관측 쿼리셋에서 "안 감" 으로 표시한 일정의 것을 뺀다. 일정이 없는 기록은 둔다."""
    return qs.exclude(event__skipped_at__isnull=False)


def exclude_marked(qs):
    """관측 쿼리셋에서 사용자가 준비 학습에서 뺀 아침의 것을 뺀다. 일정이 없는 기록은 둔다."""
    return qs.exclude(event__prep_learning_excluded_at__isnull=False)


def within_block_range(qs):
    """블록 기록 쿼리셋에서 블록 신고 범위의 0.5~1.5배 밖인 기록을 뺀다."""
    low = ExpressionWrapper(
        F("block__default_min_minutes") * BLOCK_MIN_RATIO, output_field=FloatField()
    )
    high = ExpressionWrapper(
        Greatest(F("block__default_max_minutes"), Value(BLOCK_RATIO_FLOOR_MINUTES))
        * BLOCK_MAX_RATIO,
        output_field=FloatField(),
    )
    return qs.filter(duration_minutes__gte=low, duration_minutes__lte=high)


def usable_block_observations(qs):
    """알람·학습에 쓸 블록 기록. 머리말 표의 조건을 모두 건다."""
    return within_block_range(exclude_after_departure(exclude_marked(exclude_skipped(qs))))


def within_expected(samples: list[float], expected_minutes: float) -> list[float]:
    """정한 준비 시간 대비 0.5~1.5배 안의 표본만 최근 것부터 최대 30개 남긴다."""
    if expected_minutes <= 0:
        return samples[:GPS_PREP_MAX_SAMPLES]
    lo = expected_minutes * GPS_PREP_MIN_RATIO
    hi = expected_minutes * GPS_PREP_MAX_RATIO
    return [m for m in samples if lo <= m <= hi][:GPS_PREP_MAX_SAMPLES]


def gps_prep_samples(user_id: int) -> list[float]:
    """GPS 출발 기준 실제 준비 시간(분) 목록. 최근 아침부터.

    사용자별 비율 검사(`within_expected`)는 여기서 하지 않는다. 기준인 예상 준비
    시간이 일정마다(고른 블록마다) 달라서 추정기가 한다.
    """
    return [minutes for _, minutes in gps_prep_by_event(user_id)]


def gps_prep_by_event(user_id: int, *, include_marked: bool = False) -> list[tuple[int, float]]:
    """`(일정 id, GPS 준비 시간(분))` 목록. 최근 아침부터.

    `include_marked` 면 사용자가 학습에서 뺀 아침도 넣는다. 빼기 명령
    (`exclude_prep_mornings`)이 이미 뺀 아침을 함께 보여 줄 때 쓴다.

    일정마다 한 개다. **그 일정의 첫 GPS 출발** − **그 전 마지막 알람 끄기** 다.
    미루기 뒤 다시 울려 끈 경우 마지막 끄기부터 준비를 시작한 것으로 본다.

    버리는 것:
      - 출발지를 따로 고른 일정. 집에서 준비하고 나간 아침이 아니다(친구 집에서
        자고 준비를 넣은 아침도. 남의 집 준비는 평소 준비와 다르다)
      - 사용자가 준비를 뺀 일정(`include_prep` false). 준비하지 않고 나간 아침이다
      - "안 감" 으로 표시한 일정. 나갔다 돌아왔거나 다른 데 간 아침이다
      - 사용자가 준비 학습에서 뺀 아침(`include_marked` 가 아닐 때)
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
        .exclude(event__skipped_at__isnull=False)
    )
    if not include_marked:
        departs = exclude_marked(departs)
    departs = departs.order_by("event_id", "observed_at").values_list("event_id", "observed_at")
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

    samples: list[tuple[int, float]] = []
    for event_id, depart_at in sorted(first_depart.items(), key=lambda kv: kv[1], reverse=True):
        dismissed_at = last_dismiss.get(event_id)
        if dismissed_at is None:
            continue
        minutes = (depart_at - dismissed_at).total_seconds() / 60.0
        if GPS_PREP_MIN_MINUTES <= minutes <= GPS_PREP_MAX_MINUTES:
            samples.append((event_id, round(minutes, 1)))
    return samples
