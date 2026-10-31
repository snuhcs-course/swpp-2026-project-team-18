"""관측을 분포 모수로 바꾼다. back-spec.md 6절.

## 재료가 무엇인가

명세 4.4 는 관측 모델 5개(PrepObservation / BlockObservation / TravelObservation /
ArrivalObservation / RiskChoiceLog)를 정의한다. 그 중 **실제로 데이터가 들어오는
표는 둘**이다.

    observations.TripObservation   앱이 GPS 로 판별한 출발·도착 (이미 동작)
    routines.BlockObservation      블록별 실제 소요 (이번에 추가)

나머지 셋은 앱이 아직 올리지 않는다. 그래서 빈 표를 새로 만들지 않고 이 둘에서
**파생**한다. 이동 시간은 출발·도착 쌍의 차이이고, 도착 여유는 도착 시각과
일정 시작의 차이다. 표를 늘리면 어느 쪽이 정본인지 모호해진다.

## 표준편차를 DB 가 아니라 파이썬에서 계산한다

SQLite 에는 `STDDEV` 집계가 없다. `django.db.models.StdDev` 는 Postgres 에서만
돈다. 로컬은 SQLite, 배포는 Postgres 라서 **같은 코드가 환경에 따라 다르게
동작하면 안 된다.** 표본 수가 수천 건 규모라 파이썬에서 계산해도 충분하다.

## 이상치를 버리는 기준

학습 데이터에 쓰레기가 섞이면 알람이 조용히 틀어진다. 버리는 근거를 명시한다.

| 조건 | 이유 |
| --- | --- |
| 도착이 출발보다 이르다 | 순서가 뒤집힌 관측. GPS 오판 |
| 정확도가 150m 를 넘는다 | 오차 150m 인 fix 로 "반경 진입" 을 말할 수 없다 |
| 실제/예측 비율이 0.25~4.0 밖 | 중간에 딴 데 들렀거나 판정 오류 |
| 결정 시점 경로 원값이 없다 | 현재 보정값을 다시 학습하면 중복 보정이 됨 |
| 실제 소요가 0분 | 출발·도착이 같은 fix 로 판정됐다 |
| 블록 기록이 같은 일정의 GPS 출발보다 늦다 | 나간 뒤에 눌러 이동 시간이 섞였다 |
| 블록 기록이 블록 신고 최소의 0.5배 미만·최대의 1.5배 초과 | 누르는 걸 잊었거나 안 한 블록을 눌렀다 |
| 일정을 "안 감" 으로 표시했다(`Event.skipped_at`) | 그날은 등굣길·등교 준비가 아니다 |
| 출발·도착을 직접 눌렀다(`manual`) | 누른 시각이 실제보다 늦기 쉽다. 리포트에만 쓴다 |
| 이동 중에 경로를 바꿨다(`TripReroute`) | 결정 시점 원값은 원래 경로의 것이다. 이동 학습만 뺀다 |

## 이동 보정을 세 단계로 푼다 (P2 A-1·A-2, 10-30)

한 비율(실제 ÷ 카카오 원값)에 걷는 속도, 시간대, 경로의 성질이 섞여 있다. 밤 학습은
이 순서로 하나씩 떼어 낸다. 쓸 때는 셋을 곱한다(`estimators.estimate_travel`).

1. **걷는 속도**(사용자별, `UserTravelProfile`). 도보로 간 일정만 쓴다. 기록 평균을
   저장하고, 쓸 때 1.0 에서 n / (n + 5) 만큼 옮겨 0.7~1.5 로 자른다. 그다음 모든 표본의
   예측을 "원값 + 걷는 분 × (걷는 속도 − 1)" 로 고친다. 대중교통은 도보 구간만 고친다.
2. **시간대**(전역·이동 종류별, 출근·낮·퇴근·밤 × 주중·주말). 구간 평균 비율 ÷ 그 종류 전체
   평균 비율을 저장한다. 종류는 경로 key 로 정한다(`travel_kind`, `travel_mode` 는 화면용 이름). 쓸 때 1.0 에서 n / (n + 5) 만큼 옮긴다. 구간은 **결정 시점 계획의
   출발 시각**(`depart_by`)으로 고른다. 알람 계산도 계획한 출발 시각으로 고른다.
3. **경로·전역 보정**. 1·2를 곱한 예측과 실제의 비율로 배운다. 그래야 걷는 속도와 아침
   지연이 경로 계수에 한 번 더 들어가지 않는다.
"""

from __future__ import annotations

import logging
import statistics
from collections import defaultdict
from dataclasses import dataclass, replace
from datetime import datetime, timezone

from django.db import transaction
from django.db.models import F
from django.utils import timezone as dj_tz

from apps.planning import history
from apps.planning.estimators import walk_adjusted_minutes
from apps.planning.models import AlarmPlan
from apps.planning.prep_observations import usable_block_observations
from apps.routing.models import (
    GLOBAL_SIGNATURE,
    MAX_FACTOR,
    MIN_FACTOR,
    WALK,
    RouteCorrection,
    UserTravelProfile,
    shrink_to_one,
    time_bucket,
    travel_kind,
    walk_factor,
)

from .models import ModelArtifact

logger = logging.getLogger(__name__)

# GPS fix 의 오차 상한(m). 이보다 큰 fix 로 판정한 관측은 학습에 쓰지 않는다.
MAX_ACCURACY_M = 150.0

# 이동 시간 하한(분). 0분은 출발·도착이 같은 fix 로 판정됐다는 뜻이다.
MIN_TRAVEL_MINUTES = 1.0

# 경로별 보정을 만들려면 최소 이만큼의 표본이 필요하다. 그 아래는 전역에
# 합쳐 둔다 — 표본 2건으로 경로 전용 계수를 만들면 우연이 계수가 된다.
MIN_ROUTE_SAMPLES = 3

# 블록 분포를 만들 최소 표본. 1건이면 표준편차가 0 이 되어 "변동성이 없다" 는
# 잘못된 결론이 된다.
MIN_BLOCK_SAMPLES = 2

# 개인 아티팩트를 만들 최소 관측 수. back-spec 6.1 S2 가 15건으로 정했다.
MIN_PERSONAL_OBSERVATIONS = 15


@dataclass(frozen=True)
class TravelSample:
    """이동 한 번의 예측 대비 실제."""

    signature: str
    mode: str
    # 결정 시점 계획의 출발 시각(시). 계획에 없으면 실제 출발 시각이다.
    hour: int
    weekday_type: str
    predicted_minutes: float
    actual_minutes: float
    user_id: int | None = None
    # 예측 중 걷는 분. 도보 경로는 예측 전체, 대중교통은 도보 구간 합이다. 모르면 0.
    walk_minutes: float = 0.0
    # 이동 종류(walk·transit 등, `routing.models.travel_kind`). `mode` 는 화면용 이름이다.
    kind: str = ""

    @property
    def ratio(self) -> float:
        return self.actual_minutes / self.predicted_minutes

    @property
    def time_bucket(self) -> int:
        return time_bucket(self.hour)


def _weekday_type(when: datetime) -> str:
    return (
        RouteCorrection.WeekdayType.WEEKEND
        if when.weekday() >= 5
        else RouteCorrection.WeekdayType.WEEKDAY
    )


def collect_travel_samples(user=None) -> tuple[list[TravelSample], dict[str, int]]:
    """`TripObservation` 출발·도착 쌍에서 이동 표본을 만든다.

    버린 건수를 이유별로 함께 돌려준다. "표본이 왜 이렇게 적지" 를 추적할 수
    있어야 한다 — 조용히 버리면 학습이 안 되는 이유를 알 수 없다.
    """
    from apps.observations.models import TripObservation, TripReroute

    qs = TripObservation.objects.annotate(event_skipped_at=F("event__skipped_at")).order_by(
        "event_id", "observed_at", "id"
    )
    if user is not None:
        qs = qs.filter(user=user)

    by_event: dict[int, dict[str, TripObservation]] = defaultdict(dict)
    dropped: dict[str, int] = defaultdict(int)

    for obs in qs:
        # 안 간 일정의 출발·도착은 등굣길이 아니다(나갔다 돌아왔거나 다른 데 갔다).
        if obs.event_skipped_at is not None:
            dropped["안 감"] += 1
            continue
        # 직접 누른 판정은 학습에 쓰지 않는다. 누른 시각이 실제보다 늦기 쉬워
        # 이동 시간이 길게 학습된다. 리포트 채점에만 보조로 쓴다(`observations.selection`).
        if obs.detector != TripObservation.Detector.GPS:
            dropped["수동 판정"] += 1
            continue
        if obs.accuracy_m is not None and obs.accuracy_m > MAX_ACCURACY_M:
            dropped["정확도 초과"] += 1
            continue
        # 같은 종류가 여러 건이면 **가장 이른 것**을 쓴다. 출발은 처음
        # 집을 벗어난 시점이고, 도착은 처음 목적지에 든 시점이다.
        slot = by_event[obs.event_id]
        if obs.kind not in slot:
            slot[obs.kind] = obs

    revisions = history.decision_revisions(by_event)
    # 이동 중에 경로를 바꾼 일정. 결정 시점 원값은 원래 경로의 것이라 실측과 맞지 않는다.
    rerouted = set(
        TripReroute.objects.filter(event_id__in=list(by_event)).values_list("event_id", flat=True)
    )
    obs_users = {
        event_id: next(iter(pair.values())).user_id for event_id, pair in by_event.items() if pair
    }
    samples: list[TravelSample] = []
    for event_id, pair in by_event.items():
        if event_id in rerouted:
            dropped["이동 중 경로 변경"] += 1
            continue
        depart = pair.get(TripObservation.Kind.DEPART)
        arrive = pair.get(TripObservation.Kind.ARRIVE)
        if depart is None or arrive is None:
            dropped["쌍 미완성"] += 1
            continue

        actual = (arrive.observed_at - depart.observed_at).total_seconds() / 60.0
        if actual < MIN_TRAVEL_MINUTES:
            dropped["소요 0분 또는 역순"] += 1
            continue

        revision = revisions.get(event_id)
        predicted = revision.route_minutes_raw if revision is not None else None
        if revision is None or revision.status != AlarmPlan.Status.OK or not predicted:
            dropped["경로 원값 없음"] += 1
            continue

        ratio = actual / predicted
        if not (MIN_FACTOR <= ratio <= MAX_FACTOR):
            dropped["비율 범위 밖"] += 1
            continue

        mode = (revision.travel_mode or "")[:20]
        kind = travel_kind(revision.route_key, mode)
        # 시간대는 **계획한** 출발 시각으로 고른다. 알람 계산도 계획한 출발 시각으로 찾는다
        # (`planning.services.estimate_components`). 실제 출발로 고르면 늦게 나간 아침이
        # 다음 구간에 들어가 계산과 칸이 어긋난다.
        planned = dj_tz.localtime(revision.depart_by or depart.observed_at)
        if kind == WALK:
            walk = float(predicted)
        else:
            walk = float(min(revision.walk_minutes_raw or 0, predicted))
        samples.append(
            TravelSample(
                signature=(revision.route_key or "")[:120],
                mode=mode,
                hour=planned.hour,
                weekday_type=_weekday_type(planned),
                predicted_minutes=float(predicted),
                actual_minutes=float(actual),
                user_id=obs_users.get(event_id),
                walk_minutes=walk,
                kind=kind,
            )
        )

    return samples, dict(dropped)


def _summarize(samples: list[TravelSample]) -> tuple[float, float, int]:
    """표본 묶음에서 `(factor, 잔차 표준편차, 표본수)`.

    factor 는 비율의 평균이다. **잔차는 보정 후에 남는 오차**다 —
    `actual − predicted × factor`. 보정 전 오차를 쓰면 체계적 편향(factor)과
    변동성(sd)을 구분하지 못한다.
    """
    ratios = [s.ratio for s in samples]
    factor = statistics.fmean(ratios)
    residuals = [s.actual_minutes - s.predicted_minutes * factor for s in samples]
    sd = statistics.stdev(residuals) if len(residuals) >= 2 else 0.0
    return factor, sd, len(samples)


def walk_ratios(samples: list[TravelSample]) -> dict[int, tuple[float, int]]:
    """사용자별 걷는 속도 기록 `(평균 비율, 표본 수)`. 도보로 간 일정만 쓴다."""
    by_user: dict[int, list[float]] = defaultdict(list)
    for s in samples:
        if s.kind == WALK and s.user_id is not None:
            by_user[s.user_id].append(s.ratio)
    return {uid: (statistics.fmean(ratios), len(ratios)) for uid, ratios in by_user.items()}


def walk_adjusted(samples: list[TravelSample], factors: dict[int, float]) -> list[TravelSample]:
    """각 표본의 예측에 그 사용자의 걷는 속도를 반영한다(걷는 분에만)."""
    return [
        replace(
            s,
            predicted_minutes=walk_adjusted_minutes(
                s.predicted_minutes, s.walk_minutes, factors.get(s.user_id, 1.0)
            ),
        )
        for s in samples
    ]


def time_ratios(samples: list[TravelSample]) -> dict[tuple[str, int, str], tuple[float, int]]:
    """(이동 종류, 시간대 구간, 요일) → `(구간 평균 비율 ÷ 종류 전체 평균 비율, 표본 수)`.

    종류로 나눈다(walk·transit …). 수단 이름("버스", "버스+지하철")으로 나누면 같은 대중교통
    아침이 여러 칸으로 흩어진다.
    """
    by_kind: dict[str, list[float]] = defaultdict(list)
    cells: dict[tuple[str, int, str], list[float]] = defaultdict(list)
    for s in samples:
        if not s.kind:
            continue
        by_kind[s.kind].append(s.ratio)
        cells[(s.kind, s.time_bucket, s.weekday_type)].append(s.ratio)
    out = {}
    for (kind, bucket, weekday), ratios in cells.items():
        overall = statistics.fmean(by_kind[kind])
        relative = statistics.fmean(ratios) / overall if overall > 0 else 1.0
        out[(kind, bucket, weekday)] = (min(max(relative, MIN_FACTOR), MAX_FACTOR), len(ratios))
    return out


def detimed(samples: list[TravelSample], cells: dict[tuple[str, int, str], tuple[float, int]]):
    """각 표본의 예측에 그 시간대 계수(1.0 에서 옮긴 값)를 곱한다."""
    out = []
    for s in samples:
        relative, n = cells.get((s.kind, s.time_bucket, s.weekday_type), (1.0, 0))
        out.append(replace(s, predicted_minutes=s.predicted_minutes * shrink_to_one(relative, n)))
    return out


@transaction.atomic
def update_route_corrections(user=None) -> dict:
    """`TripObservation` → `UserTravelProfile` · `RouteCorrection`.

    머리말 "이동 보정을 세 단계로 푼다" 순서다. 경로·전역 보정은 세 수준으로 만든다.
    구체적인 것이 없으면 추정기가 상위로 떨어진다.

        (경로, 수단, 전체, 요일)     경로 전용, 표본 3건부터
        ("",  수단, 전체, 전체)     전역
        ("",  "",  전체, 전체)     수단까지 무시한 최후의 전역
        ("",  수단, 구간, 요일)     시간대 상대 계수(곱하는 값)

    `user` 를 주면 그 사용자 관측만 쓴다. 보정 자체는 사용자별로 나누지
    않는다 — 2호선이 아침에 얼마나 지연되는지는 누가 타도 같다. 걷는 속도만 사용자별이다.
    """
    samples, dropped = collect_travel_samples(user=user)

    # 1. 걷는 속도
    walks = walk_ratios(samples)
    profile_ids = []
    for uid, (ratio, n) in walks.items():
        row, _ = UserTravelProfile.objects.update_or_create(
            user_id=uid,
            defaults={
                "walk_ratio": round(min(max(ratio, MIN_FACTOR), MAX_FACTOR), 4),
                "walk_samples": n,
            },
        )
        profile_ids.append(row.pk)
    factors = {uid: walk_factor(ratio, n) for uid, (ratio, n) in walks.items()}
    if user is None:
        # 다른 사용자는 지금 기록이 없다(일정을 "안 감" 으로 표시하는 등). 1.0 으로 돌아간다.
        UserTravelProfile.objects.exclude(pk__in=profile_ids).delete()
    elif user.pk not in factors:
        UserTravelProfile.objects.filter(user=user).delete()
    adjusted = walk_adjusted(samples, factors)

    # 2. 시간대
    cells = time_ratios(adjusted)
    written_ids = []
    for (kind, bucket, weekday), (relative, n) in cells.items():
        row, _ = RouteCorrection.objects.update_or_create(
            route_signature=GLOBAL_SIGNATURE,
            mode=kind,
            hour_bucket=bucket,
            weekday_type=weekday,
            defaults={"factor": round(relative, 4), "sd_minutes": 0.0, "sample_count": n},
        )
        written_ids.append(row.pk)

    # 3. 경로·전역
    buckets: dict[tuple[str, str, int, str], list[TravelSample]] = defaultdict(list)
    for s in detimed(adjusted, cells):
        # 경로·수단·요일
        buckets[(s.signature, s.mode, -1, s.weekday_type)].append(s)
        # 전역
        buckets[(GLOBAL_SIGNATURE, s.mode, -1, RouteCorrection.WeekdayType.ANY)].append(s)
        # 수단까지 무시한 최후의 전역. 새 수단을 처음 쓸 때 쓸 값이다.
        buckets[(GLOBAL_SIGNATURE, "", -1, RouteCorrection.WeekdayType.ANY)].append(s)

    for (signature, mode, hour, weekday), rows in buckets.items():
        # 경로 전용 보정은 표본이 모여야 만든다. 전역은 항상 만든다.
        if signature != GLOBAL_SIGNATURE and len(rows) < MIN_ROUTE_SAMPLES:
            continue

        factor, sd, n = _summarize(rows)
        # DB CheckConstraint 범위로 자른다. 밖으로 나가면 저장이 터진다.
        factor = min(max(factor, MIN_FACTOR), MAX_FACTOR)

        row, _ = RouteCorrection.objects.update_or_create(
            route_signature=signature,
            mode=mode,
            hour_bucket=hour,
            weekday_type=weekday,
            defaults={
                "factor": round(factor, 4),
                "sd_minutes": round(max(sd, 0.0), 3),
                "sample_count": n,
            },
        )
        written_ids.append(row.pk)

    removed = 0
    if user is None:
        # 전체 재학습에서 사라진 버킷을 남기면 과거 보정이 새 전역값보다 우선한다.
        # 예전 시(時) 단위 경로 행도 여기서 지워진다.
        # n=0 수동 보정과 일부 사용자만 학습한 경우의 공유 보정은 지우지 않는다.
        removed, _ = RouteCorrection.objects.filter(sample_count__gt=0).exclude(pk__in=written_ids).delete()
    return {"written": len(written_ids), "removed": removed,
            "samples": len(samples), "dropped": dropped,
            "walk_profiles": len(profile_ids), "time_rows": len(cells)}


# ---------------------------------------------------------------------------
# 블록 학습
# ---------------------------------------------------------------------------


def block_stats(user=None) -> dict[str, dict]:
    """블록 **이름**별 `{mean, sd, n}`.

    id 가 아니라 이름으로 묶는다. 블록은 사용자별이라 id 가 기기 간에 의미가
    없고, 전역 아티팩트는 여러 사용자의 "샤워" 를 합쳐야 한다.
    """
    from apps.routines.models import BlockObservation

    qs = usable_block_observations(BlockObservation.objects.select_related("block"))
    if user is not None:
        qs = qs.filter(user=user)

    grouped: dict[str, list[float]] = defaultdict(list)
    for obs in qs.only("duration_minutes", "block__name"):
        grouped[obs.block.name].append(float(obs.duration_minutes))

    out: dict[str, dict] = {}
    for name, values in grouped.items():
        if len(values) < MIN_BLOCK_SAMPLES:
            # 표본 1건으로 sd 를 만들면 0 이 나온다. 그러면 "한 번 재봤으니
            # 변동성이 없다" 는 잘못된 결론이 된다. 평균만 내려보낸다.
            out[name] = {
                "mean": round(statistics.fmean(values), 2),
                "sd": None,
                "n": len(values),
            }
            continue
        out[name] = {
            "mean": round(statistics.fmean(values), 2),
            "sd": round(statistics.stdev(values), 2),
            "n": len(values),
        }
    return out


def estimate_slack_coef(user=None) -> float | None:
    """여유 1분당 준비 시간이 몇 분 늘어나는지. back-spec 6.1 S3 의 `slack_coef`.

    최소제곱 기울기다. 이 계수가 없으면 "여유를 준 날은 준비가 오래 걸린다" 는
    상관이 **개인 특성으로 흡수된다** — 실제로는 상황 효과인데 "이 사람은
    원래 느리다" 로 학습된다.

    표본이 적으면 None 이다. 기울기를 억지로 내면 노이즈가 계수가 된다.
    """
    from apps.routines.models import BlockObservation

    qs = usable_block_observations(BlockObservation.objects.filter(slack_minutes__isnull=False))
    if user is not None:
        qs = qs.filter(user=user)

    pairs = [
        (float(o.slack_minutes), float(o.duration_minutes))
        for o in qs.only("slack_minutes", "duration_minutes")
    ]
    if len(pairs) < 8:
        return None

    xs = [p[0] for p in pairs]
    ys = [p[1] for p in pairs]
    mean_x = statistics.fmean(xs)
    mean_y = statistics.fmean(ys)
    denom = sum((x - mean_x) ** 2 for x in xs)
    if denom == 0:
        # 여유가 전부 같으면 기울기를 정의할 수 없다.
        return None
    num = sum((x - mean_x) * (y - mean_y) for x, y in zip(xs, ys))
    return round(num / denom, 4)


def prep_total_stats(user=None) -> dict:
    """하루 준비 시간 총합의 `{prep_mean, prep_sd, n}`.

    같은 사용자·같은 날짜의 블록 관측을 더해 하루치로 만든다. 블록이 없는
    사용자에게 내려줄 사전값이다.

    **병렬 블록은 더하지 않는다.** 세탁기를 돌리며 샤워한 시간을 합치면
    실제보다 길어진다. `was_parallel` 이 참인 관측은 제외하고, 그 결과는
    "직렬 블록의 합" 이 된다 — 병렬 효과는 추정기의 `max_of` 가 담당한다.
    """
    from apps.routines.models import BlockObservation

    qs = usable_block_observations(BlockObservation.objects.filter(was_parallel=False))
    if user is not None:
        qs = qs.filter(user=user)

    per_day: dict[tuple[int, object], float] = defaultdict(float)
    for obs in qs.only("user_id", "observed_on", "duration_minutes"):
        per_day[(obs.user_id, obs.observed_on)] += float(obs.duration_minutes)

    totals = list(per_day.values())
    if not totals:
        return {"prep_mean": None, "prep_sd": None, "n": 0}
    return {
        "prep_mean": round(statistics.fmean(totals), 2),
        "prep_sd": round(statistics.stdev(totals), 2) if len(totals) >= 2 else None,
        "n": len(totals),
    }


def route_correction_payload() -> dict:
    """아티팩트에 담을 경로 보정. front-spec 11 의 `route_correction` 절."""
    out = {}
    for row in RouteCorrection.objects.filter(
        hour_bucket=-1, weekday_type=RouteCorrection.WeekdayType.ANY
    ):
        key = row.route_signature or "*"
        out[key] = {
            "factor": row.factor,
            "sd": row.sd_minutes,
            "n": row.sample_count,
        }
    return out


def build_artifact(user=None) -> dict:
    """front-spec.md 11 의 아티팩트 스키마를 채운다.

    `tag_adjust` 와 `weather_adjust` 는 비워 둔다. 태그별 보정은 도착 여유를
    태그로 나눠 회귀해야 하는데 표본이 태그당 몇 건 수준이고, 날씨는 아직
    수집하지 않는다. **빈 값을 내려보내는 것이 0.0 을 넣는 것보다 정직하다** —
    클라이언트가 "보정 없음" 과 "보정이 0 으로 학습됨" 을 구분할 수 있다.
    """
    blocks = block_stats(user=user)
    totals = prep_total_stats(user=user)

    return {
        "version": dj_tz.now().astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "scope": "user" if user is not None else "global",
        "global": {
            "prep_mean": totals["prep_mean"],
            "prep_sd": totals["prep_sd"],
            "prep_days": totals["n"],
        },
        "blocks": blocks,
        "tag_adjust": {},
        "slack_coef": estimate_slack_coef(user=user),
        "route_correction": route_correction_payload(),
        "weather_adjust": {},
    }


def observation_count(user=None) -> int:
    """아티팩트를 만들 가치가 있는지 판단할 관측 수."""
    from apps.routines.models import BlockObservation

    qs = BlockObservation.objects.all()
    if user is not None:
        qs = qs.filter(user=user)
    return qs.count()


@transaction.atomic
def save_artifact(payload: dict, user=None) -> ModelArtifact:
    """아티팩트를 저장하고 이전 활성본을 내린다.

    옛 행을 지우지 않는다. "언제 왜 알람이 바뀌었나" 를 추적해야 하고,
    학습이 이상해졌을 때 되돌릴 기준도 필요하다.
    """
    ModelArtifact.objects.filter(user=user, is_active=True).update(is_active=False)

    version = payload.get("version") or dj_tz.now().isoformat()
    sample_count = sum(b.get("n", 0) for b in (payload.get("blocks") or {}).values())

    # 같은 버전이 이미 있으면(같은 초에 두 번 돌린 경우) 덮어쓴다.
    artifact, _created = ModelArtifact.objects.update_or_create(
        user=user,
        version=version,
        defaults={
            "payload": payload,
            "sample_count": sample_count,
            "is_active": True,
        },
    )
    return artifact
