# Partly AI-generated (Claude)
"""쌓인 기록 초기화(iter4 BE-RESET). 계약은 `docs/iter4/server-learning-reset.md`.

내 정보 › 계정 › [기록 초기화]. 쓰면서 쌓인 기록과 **그 기록에서 맞춰진 값**을 지우고,
앞으로의 알람을 기록이 없을 때의 기준으로 다시 섞는다. 사용자가 직접 넣은 것(일정 · 집 ·
준비 시간 · 루틴 블록 · 계정)은 그대로다.

## 지우는 것 (요청한 사용자 행만)

| 키 | 모델 | 왜 |
| --- | --- | --- |
| `trip_observations` | `observations.TripObservation` | 출발 · 도착 기록. 이동 보정 · GPS 준비 시간 · 버퍼의 재료 |
| `alarm_logs` | `observations.AlarmLog` | 아침(알람) 기록. 결정 시점 판단 · 리포트의 재료 |
| `trip_reroutes` | `observations.TripReroute` | 이동 중 · 출발 전 경로 변경 기록. 이동 학습의 재료 |
| `block_observations` | `routines.BlockObservation` | 블록별 실제 걸린 시간. 준비 시간 분포의 재료 |
| `buffer_adjustments` | `planning.BufferAdjustment` | 지각 이력으로 맞춘 버퍼. 마지막 행이 지금 버퍼다 |
| `weekly_summaries` | `reports.WeeklySummary` | 기록으로 쓴 주간 요약 문장 |
| `model_artifacts` | `prediction.ModelArtifact` 중 **이 사용자의 개인 행** | 개인 블록 분포. 전역 행(`user` null)은 건드리지 않는다 |
| `travel_profiles` | `routing.UserTravelProfile` | 도보 기록으로 배운 걷는 속도 |

## 일부러 남기는 것

- `planning.AlarmPlan` · `PlanRevision`: 계획과 그 이력이다. 앞으로의 일정은 지운 뒤 바로 다시
  섞는데, 그때 이력의 카카오 원값을 쓴다(`refresh`). 지난 일정의 이력은 관측이 없으면 학습에
  쓰이지 않는다.
- `routing.RouteCorrection`: 모든 사용자의 기록으로 만든 공용 보정이라 이 사용자 몫만 뗄 수 없다.
  밤 전체 학습(`train_models`)이 남은 기록으로 다시 만들 때 이 사용자 기록이 빠진다.
- `reports.LlmCall`: 하루 LLM 호출 상한을 세는 표다. 지우면 초기화로 상한을 되살릴 수 있다.
- `common.ApiRequestLog`: 운영 기록. 보존 기간이 지나면 따로 지워진다.
- 일정의 `skipped_at`(안 감) · `prep_learning_excluded_at`(학습 제외 표시) · `route_key`(고른 경로),
  `EventBlockSelection`(블록 체크): 사용자가 고르거나 팀이 붙인 표시다. 기록이 없으면 효과도 없다.

## 순서와 트랜잭션

지우기와 다시 섞기를 한 트랜잭션에서 한다. 다시 섞다가 실패하면 지운 것도 되돌린다 — 기록은
지워졌는데 알람은 옛 학습값인 상태를 남기지 않는다. 다시 섞기는 카카오를 부르지 않는다.
울릴 시각이 이미 지난 알람(`alarm_at` 이 지금 이전)은 그대로다(`refresh._refresh`) — 울림 기록을
지운 뒤라도 기기에 걸린 알람과 어긋나지 않는다.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime

from django.db import transaction
from django.utils import timezone

from . import refresh
from .models import BufferAdjustment, PlanRevision

logger = logging.getLogger(__name__)


def _targets():
    """(응답 키, 모델). 지우는 순서이기도 하다. 서로 외래 키가 없어 순서는 상관없다."""
    from apps.observations.models import AlarmLog, TripObservation, TripReroute
    from apps.prediction.models import ModelArtifact
    from apps.reports.models import WeeklySummary
    from apps.routines.models import BlockObservation
    from apps.routing.models import UserTravelProfile

    return (
        ("trip_observations", TripObservation),
        ("alarm_logs", AlarmLog),
        ("trip_reroutes", TripReroute),
        ("block_observations", BlockObservation),
        ("buffer_adjustments", BufferAdjustment),
        ("weekly_summaries", WeeklySummary),
        ("model_artifacts", ModelArtifact),
        ("travel_profiles", UserTravelProfile),
    )


@dataclass(frozen=True)
class ResetResult:
    deleted: dict[str, int]
    # 지운 기준으로 다시 섞은 앞으로의 계획 수(바뀐 것 + 같은 것).
    recomputed_plans: int
    # 그중 알람 값이 실제로 바뀐 계획 수.
    changed_plans: int
    reset_at: datetime


def reset_learning(user) -> ResetResult:
    """`user` 의 쌓인 기록과 학습값을 지우고 앞으로의 알람을 다시 섞는다. 멱등하다."""
    if user is None or user.pk is None:
        # `filter(user=None)` 은 전역 아티팩트를 고른다. 절대 그 경로로 가지 않게 막는다.
        raise ValueError("기록 초기화에는 저장된 사용자가 필요하다")

    reset_at = timezone.now()
    with transaction.atomic():
        deleted: dict[str, int] = {}
        for key, model in _targets():
            _total, per_model = model.objects.filter(user_id=user.pk).delete()
            deleted[key] = per_model.get(model._meta.label, 0)
        outcomes = refresh.refresh_upcoming(user, trigger=PlanRevision.Trigger.LEARNING)

    changed = outcomes.get(refresh.CHANGED, 0)
    result = ResetResult(
        deleted=deleted,
        recomputed_plans=changed + outcomes.get(refresh.UNCHANGED, 0),
        changed_plans=changed,
        reset_at=reset_at,
    )
    logger.info(
        "기록 초기화 user=%s deleted=%s recomputed=%s changed=%s",
        user.pk, deleted, result.recomputed_plans, changed,
    )
    return result
