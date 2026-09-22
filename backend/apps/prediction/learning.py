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
| 예측값이 없다 | 비교 대상이 없어 보정을 만들 수 없다 |
| 실제 소요가 0분 | 출발·도착이 같은 fix 로 판정됐다 |
"""

from __future__ import annotations

import logging
import statistics
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone

from django.db import transaction
from django.utils import timezone as dj_tz

from apps.routing.models import (
    GLOBAL_SIGNATURE,
    MAX_FACTOR,
    MIN_FACTOR,
    RouteCorrection,
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
    hour: int
    weekday_type: str
    predicted_minutes: float
    actual_minutes: float

    @property
    def ratio(self) -> float:
        return self.actual_minutes / self.predicted_minutes


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
    from apps.observations.models import TripObservation

    qs = TripObservation.objects.select_related(
        "event", "event__alarm_plan"
    ).order_by("event_id", "observed_at")
    if user is not None:
        qs = qs.filter(user=user)

    by_event: dict[int, dict[str, TripObservation]] = defaultdict(dict)
    dropped: dict[str, int] = defaultdict(int)

    for obs in qs:
        if obs.accuracy_m is not None and obs.accuracy_m > MAX_ACCURACY_M:
            dropped["정확도 초과"] += 1
            continue
        # 같은 종류가 여러 건이면 **가장 이른 것**을 쓴다. 출발은 처음
        # 집을 벗어난 시점이고, 도착은 처음 목적지에 든 시점이다.
        slot = by_event[obs.event_id]
        if obs.kind not in slot:
            slot[obs.kind] = obs

    samples: list[TravelSample] = []
    for event_id, pair in by_event.items():
        depart = pair.get(TripObservation.Kind.DEPART)
        arrive = pair.get(TripObservation.Kind.ARRIVE)
        if depart is None or arrive is None:
            dropped["쌍 미완성"] += 1
            continue

        actual = (arrive.observed_at - depart.observed_at).total_seconds() / 60.0
        if actual < MIN_TRAVEL_MINUTES:
            dropped["소요 0분 또는 역순"] += 1
            continue

        plan = getattr(depart.event, "alarm_plan", None)
        predicted = getattr(plan, "travel_minutes", None) if plan else None
        if not predicted:
            dropped["예측값 없음"] += 1
            continue

        ratio = actual / predicted
        if not (MIN_FACTOR <= ratio <= MAX_FACTOR):
            dropped["비율 범위 밖"] += 1
            continue

        samples.append(
            TravelSample(
                signature=(plan.route_key or "")[:120],
                mode=(plan.travel_mode or "")[:20],
                hour=dj_tz.localtime(depart.observed_at).hour,
                weekday_type=_weekday_type(dj_tz.localtime(depart.observed_at)),
                predicted_minutes=float(predicted),
                actual_minutes=float(actual),
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


@transaction.atomic
def update_route_corrections(user=None) -> dict:
    """`TripObservation` → `RouteCorrection`.

    세 수준으로 만든다. 구체적인 것이 없으면 추정기가 상위로 떨어진다.

        (경로, 수단, 시간대, 요일)   가장 구체적
        (경로, 수단, 전체, 요일)
        ("",  수단, 전체, 전체)     전역

    `user` 를 주면 그 사용자 관측만 쓴다. 보정 자체는 사용자별로 나누지
    않는다 — 2호선이 아침에 얼마나 지연되는지는 누가 타도 같다.
    """
    samples, dropped = collect_travel_samples(user=user)
    if not samples:
        return {"written": 0, "samples": 0, "dropped": dropped}

    buckets: dict[tuple[str, str, int, str], list[TravelSample]] = defaultdict(list)
    for s in samples:
        # 경로·수단·시간대·요일
        buckets[(s.signature, s.mode, s.hour, s.weekday_type)].append(s)
        # 경로·수단·요일 (시간대 무시)
        buckets[(s.signature, s.mode, -1, s.weekday_type)].append(s)
        # 전역
        buckets[(GLOBAL_SIGNATURE, s.mode, -1, RouteCorrection.WeekdayType.ANY)].append(s)
        # 수단까지 무시한 최후의 전역. 새 수단을 처음 쓸 때 쓸 값이다.
        buckets[(GLOBAL_SIGNATURE, "", -1, RouteCorrection.WeekdayType.ANY)].append(s)

    written = 0
    for (signature, mode, hour, weekday), rows in buckets.items():
        # 경로 전용 보정은 표본이 모여야 만든다. 전역은 항상 만든다.
        if signature != GLOBAL_SIGNATURE and len(rows) < MIN_ROUTE_SAMPLES:
            continue

        factor, sd, n = _summarize(rows)
        # DB CheckConstraint 범위로 자른다. 밖으로 나가면 저장이 터진다.
        factor = min(max(factor, MIN_FACTOR), MAX_FACTOR)

        RouteCorrection.objects.update_or_create(
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
        written += 1

    return {"written": written, "samples": len(samples), "dropped": dropped}


# ---------------------------------------------------------------------------
# 블록 학습
# ---------------------------------------------------------------------------


def block_stats(user=None) -> dict[str, dict]:
    """블록 **이름**별 `{mean, sd, n}`.

    id 가 아니라 이름으로 묶는다. 블록은 사용자별이라 id 가 기기 간에 의미가
    없고, 전역 아티팩트는 여러 사용자의 "샤워" 를 합쳐야 한다.
    """
    from apps.routines.models import BlockObservation

    qs = BlockObservation.objects.select_related("block")
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

    qs = BlockObservation.objects.filter(slack_minutes__isnull=False)
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

    qs = BlockObservation.objects.filter(was_parallel=False)
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
