# Partly AI-generated (Claude)
"""알람 확인 · 직접 정한 시각 · "바꿀까요" 제안 (iter4 H2-3, BE-H3a·b·d).

계약은 docs/iter4/server-alarm-control.md. 이 모듈이 규칙을 한곳에 둔다.

## 세 가지 시각

| 이름 | 어디 | 뜻 |
| --- | --- | --- |
| 추천 시각 | `AlarmPlan.alarm_at` | 계산 결과. 재계산이 덮는다. 예전 앱이 읽는 값 그대로다 |
| 직접 정한 시각 | `user_alarm_at` | 사용자가 휠로 고른 시각. **재계산이 바꾸지 않는다**(확률만 새로 구한다) |
| 울릴 시각 | `Control.current()`, 응답 `effective_alarm_at` | 직접 정한 시각 → 답을 기다리며 지키는 시각(`held_alarm_at`) → 추천 시각 |

## 확인 상태

"확인함" 은 저장하지 않고 **비교로 정한다.** 확인한 알람(`confirmed_alarm_at`)이 지금 울릴
시각과 같으면 확인함, 다르면 확인 전이다. 그래서 재계산 경로가 몇 개든(일정 수정, 다시 계산,
밤 학습, 루틴 수정, 프로필) "시각이 바뀌면 확인 전" 이 저절로 맞는다. 새 계획은 확인한
알람이 없으므로 확인 전이다. 확인은 명시적인 조작(확인 · 직접 정하기 · 되돌리기 · 제안 수락/거절)
에서만 생긴다. τ 칸 PATCH(`preset_chosen`)는 확인하지 않는다 — 확인을 모르는 지금 앱에 아무도
보지 않는 제안이 생기지 않게 한다.

## 제안과 답이 없을 때

서버에는 스케줄러도 푸시도 없다. 마감이 있는 규칙은 **읽거나 다시 계산할 때 늦게(lazily)**
적용한다. 응답(`present`)은 마감이 지난 제안을 이미 처리한 모습으로 내보내고, DB 는 다음
재계산·조작 때 맞춘다. 앱도 같은 규칙을 기기에서 적용해야 한다 — 앱이 서버를 부르지 않는
동안에는 아무도 처리하지 않는다.

순수한 상태 전이는 [Control] 에 있다. DB·분포를 만지는 부분은 아래 함수들이다.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, fields
from datetime import datetime, timedelta
from typing import Callable

from django.db import transaction
from django.utils import timezone

from . import estimators, on_time
from .models import AlarmPlan, PlanRevision

logger = logging.getLogger(__name__)

# 추천 알람이 이만큼 **넘게** 움직이면 조용히 바꾸지 않고 묻는다(Figma `547:2766`). 이하는 바로 바꾼다.
PROPOSAL_THRESHOLD = timedelta(minutes=5)
# 두 시각 중 **더 이른 쪽** 이만큼 전까지 답이 없으면 더 이른(안전한) 쪽을 쓴다(Figma 캡션의 예시, 잠정).
ANSWER_LEAD = timedelta(minutes=10)
# 답이 없어 정할 때, 정하는 순간부터 이만큼도 남지 않은 시각으로는 바꾸지 않는다. 이미 지났거나
# 기기 알람을 다시 걸 틈이 없는 시각이다. 그때는 지금 시각을 지킨다(`Control.settle`).
SETTLE_MIN_AHEAD = timedelta(minutes=1)
# 직접 정한 시각은 그 시각의 정시 도착 확률이 이것 **미만**일 때만 묻는다(%).
USER_PROBABILITY_FLOOR = 70
# 확인한 알람과 지금 울릴 시각이 이만큼까지 다르면 확인함으로 본다. 0 = 1분이라도 다르면 확인 전.
CONFIRM_TOLERANCE = timedelta(0)
# 직접 정할 수 있는 범위. 일정 시작 5분 전까지, 24시간 전부터(앱 휠은 하루 안의 시각만 고른다).
USER_MIN_LEAD = timedelta(minutes=5)
USER_MAX_LEAD = timedelta(hours=24)

# 묻는 재계산. 사용자가 입력을 바꾼 재계산(일정 수정·생성·가져오기·프로필·블록·루틴)은
# 사용자가 바꾼 결과이므로 바로 적용한다. 앱의 경로 갱신(다시 계산)과 밤 학습만 묻는다.
PROPOSAL_TRIGGERS = frozenset({PlanRevision.Trigger.RECOMPUTE, PlanRevision.Trigger.LEARNING})

SOURCE_RECOMMENDED = "recommended"
SOURCE_USER = "user"
REASON_PICKED = "picked"
REASON_KEPT = "kept"
KIND_RECOMMENDED = "recommended_moved"
KIND_USER = "user_low_probability"

# 재계산 전 계획에서 읽어 두는 값. 제안 이유("이동 시간이 12분 늘었어요")를 만든다.
# `arrive_at` + `buffer_minutes` 는 그 계획을 만든 일정 시작이다(`_before_start`).
SNAPSHOT_FIELDS = (
    "status", "alarm_at", "arrive_at", "prep_minutes", "travel_minutes", "buffer_minutes", "tau_used",
)


def _precise_or_whole(precise: float | None, whole: int | None) -> float | None:
    """소수 확률. 소수 칸이 생기기 전(0.21.0)에 저장된 행이면 정수 값(`on_time.stored`)."""
    return on_time.stored(precise, whole)


def _minutes(delta: timedelta) -> int:
    return int(round(delta.total_seconds() / 60))


@dataclass
class Control:
    """`AlarmPlan` 의 사용자 상태 필드. 이름이 모델 필드와 같다. 메서드는 DB 를 만지지 않는다."""

    user_alarm_at: datetime | None = None
    user_alarm_reason: str = ""
    user_alarm_start_at: datetime | None = None
    user_alarm_probability: int | None = None
    user_alarm_probability_precise: float | None = None
    confirmed_alarm_at: datetime | None = None
    confirmed_at: datetime | None = None
    held_alarm_at: datetime | None = None
    proposed_alarm_at: datetime | None = None
    proposal_kind: str = ""
    proposal_reason: str = ""
    proposal_reason_text: str = ""
    proposal_keep_probability: int | None = None
    proposal_change_probability: int | None = None
    proposal_keep_probability_precise: float | None = None
    proposal_change_probability_precise: float | None = None
    proposed_at: datetime | None = None
    rejected_alarm_at: datetime | None = None

    @classmethod
    def of(cls, plan: AlarmPlan) -> "Control":
        return cls(**{f.name: getattr(plan, f.name) for f in fields(cls)})

    def changes(self, plan: AlarmPlan) -> dict:
        """계획과 다른 필드만. 같으면 저장하지 않는다."""
        return {
            f.name: getattr(self, f.name)
            for f in fields(self)
            if getattr(plan, f.name) != getattr(self, f.name)
        }

    # --- 읽기 ---------------------------------------------------------------

    def user_active(self, start_at) -> bool:
        """직접 정한 시각이 지금 일정에 유효한가. 정한 뒤 일정 시작이 바뀌었으면 무효다."""
        return self.user_alarm_at is not None and self.user_alarm_start_at == start_at

    def current(self, recommended, start_at):
        """지금 울릴 시각. 추천이 없으면(계산 불가) None."""
        if recommended is None:
            return None
        if self.user_active(start_at):
            return self.user_alarm_at
        return self.held_alarm_at or recommended

    @property
    def has_proposal(self) -> bool:
        return self.proposed_alarm_at is not None

    def answer_by(self, recommended, start_at):
        """제안에 답할 마감. 지금 울릴 시각과 제안 시각 중 **더 이른 쪽**의 10분 전.

        앞당기는 제안이면 제안 시각의 10분 전이다. 지금 시각(더 늦다)의 10분 전으로 두면
        답이 없을 때 이미 지난 제안 시각으로 바꾸게 된다(7:40 지킴, 7:15 제안 → 7:30 마감).
        """
        current = self.current(recommended, start_at)
        if not self.has_proposal or current is None:
            return None
        return min(current, self.proposed_alarm_at) - ANSWER_LEAD

    def confirmed(self, effective) -> bool:
        return (
            effective is not None
            and self.confirmed_alarm_at is not None
            and abs(effective - self.confirmed_alarm_at) <= CONFIRM_TOLERANCE
        )

    # --- 내부 조각 -------------------------------------------------------------

    def _clear_proposal(self) -> None:
        self.held_alarm_at = None
        self.proposed_alarm_at = None
        self.proposal_kind = ""
        self.proposal_reason = ""
        self.proposal_reason_text = ""
        self._set_proposal_probabilities(None, None)
        self.proposed_at = None

    def _clear_user(self) -> None:
        self.user_alarm_at = None
        self.user_alarm_reason = ""
        self.user_alarm_start_at = None
        self._set_user_probability(None)

    def clear_choice(self) -> None:
        """직접 정한 시각 · 기다리는 제안 · 거절 기록을 지운다. 확인 기록은 그대로 둔다.

        확인 상태는 비교라서 울릴 시각이 바뀌면 저절로 확인 전이 된다.
        """
        self._clear_proposal()
        self._clear_user()
        self.rejected_alarm_at = None

    # Partly AI-generated (Claude) — BE-P100. 확률은 소수 값(`on_time.precise_of`)으로 받아 정수 칸과
    # 소수 칸을 함께 쓴다. 정수는 늘 소수 값에서 나온다(`on_time.whole`).
    def _set_user_probability(self, precise: float | None) -> None:
        self.user_alarm_probability_precise = precise
        self.user_alarm_probability = on_time.whole(precise)

    def _set_proposal_probabilities(self, keep: float | None, change: float | None) -> None:
        self.proposal_keep_probability_precise = keep
        self.proposal_keep_probability = on_time.whole(keep)
        self.proposal_change_probability_precise = change
        self.proposal_change_probability = on_time.whole(change)

    def user_probability_precise(self) -> float | None:
        """직접 정한 시각의 소수 확률. 소수 칸이 생기기 전에 저장된 행이면 정수 값."""
        return _precise_or_whole(self.user_alarm_probability_precise, self.user_alarm_probability)

    def keep_probability_precise(self) -> float | None:
        return _precise_or_whole(
            self.proposal_keep_probability_precise, self.proposal_keep_probability
        )

    def change_probability_precise(self) -> float | None:
        return _precise_or_whole(
            self.proposal_change_probability_precise, self.proposal_change_probability
        )

    def _pin(self, at, start_at, probability, reason) -> None:
        self.user_alarm_at = at
        self.user_alarm_reason = reason
        self.user_alarm_start_at = start_at
        self._set_user_probability(probability)

    def confirm(self, effective, now) -> None:
        """그 시각을 확인한 것으로 둔다. 이미 같은 시각을 확인했으면 처음 확인한 순간을 둔다."""
        if self.confirmed(effective):
            return
        self.confirmed_alarm_at = effective
        self.confirmed_at = now

    def _propose(self, kind, *, proposed, keep, change, reason, now) -> None:
        # 같은 제안을 다시 내면(15분마다 오는 다시 계산) 처음 물은 시각을 둔다.
        if not (self.proposal_kind == kind and self.proposed_alarm_at == proposed):
            self.proposed_at = now
        self.proposed_alarm_at = proposed
        self.proposal_kind = kind
        self.proposal_reason, self.proposal_reason_text = reason
        self._set_proposal_probabilities(keep, change)

    def _apply_proposal(self) -> None:
        """제안한 시각으로 바꾼다. 직접 정한 시각에 대한 제안이면 추천 시각으로 돌아간다."""
        if self.proposal_kind == KIND_USER:
            self._clear_user()
        self.rejected_alarm_at = None
        self._clear_proposal()

    def _keep_current(self, start_at) -> None:
        """제안을 거절하고 지금 시각을 지킨다.

        추천 시각에 대한 제안이면 지키던 시각을 **직접 정한 시각(`kept`)** 으로 굳힌다. 이후
        재계산은 그 시각을 바꾸지 않고 확률이 70% 아래로 떨어질 때만 다시 묻는다 — 거절한
        뒤에도 15분마다 같은 질문을 받지 않게 하려는 것이다. [추천 시각으로 되돌리기] 로 푼다.
        """
        if self.proposal_kind == KIND_RECOMMENDED and self.held_alarm_at is not None:
            self._pin(self.held_alarm_at, start_at, self.keep_probability_precise(), REASON_KEPT)
        self.rejected_alarm_at = self.proposed_alarm_at
        self._clear_proposal()

    def _suppressed(self, proposed) -> bool:
        """이미 거절한 제안과 5분 안이면 다시 묻지 않는다."""
        return (
            self.rejected_alarm_at is not None
            and abs(proposed - self.rejected_alarm_at) <= PROPOSAL_THRESHOLD
        )

    # --- 전이 ---------------------------------------------------------------

    def settle(self, recommended, start_at, now) -> bool:
        """마감이 지난 제안을 처리한다. 두 시각 중 **더 이른(안전한) 쪽**을 쓴다. 처리했으면 True.

        정하는 순간은 마감(`answer_by`)이다. 마감이 지난 뒤에 생긴 제안(알람 직전의 다시 계산)이면
        제안이 생긴 순간(`proposed_at`)이다. 늦게(lazily) 읽어도 그 순간에 정했을 결론을 낸다 —
        앱이 마감에 기기에서 정한 것과 같다.

        - 제안 시각이 더 이르고 정하는 순간부터 `SETTLE_MIN_AHEAD`(1분) 넘게 남았으면 제안 시각으로
          바꾼다. 마감에 정하면 제안 시각은 늘 10분 뒤이므로 앞당기는 제안은 이쪽이다.
        - 아니면 지금 시각을 지킨다. 늦추는 제안(지금 시각이 더 이르다)이거나, 마감 뒤에 생긴
          제안이라 제안 시각이 이미 지났거나 1분도 남지 않은 경우다. **이미 지난 시각으로는
          바꾸지 않는다** — 그때 아직 오지 않은 시각은 지금 시각뿐이다.

        확인 상태는 건드리지 않는다. 앞당겨졌으면 확인한 시각과 달라져 확인 전이 된다.
        """
        deadline = self.answer_by(recommended, start_at)
        if deadline is None or now < deadline:
            return False
        decided_at = max(deadline, self.proposed_at or deadline)
        if (
            self.proposed_alarm_at <= self.current(recommended, start_at)
            and self.proposed_alarm_at > decided_at + SETTLE_MIN_AHEAD
        ):
            self._apply_proposal()
        else:
            self._keep_current(start_at)
        return True

    def accept(self, recommended, now) -> None:
        """[앞당기기]·[바꾸기]. 추천 시각으로 바꾸고 그 시각을 확인한 것으로 둔다."""
        self._apply_proposal()
        self._clear_user()
        self.confirm(recommended, now)

    def reject(self, recommended, start_at, now) -> None:
        """[그대로 두기]. 지금 시각을 지키고 확인한 것으로 둔다."""
        current = self.current(recommended, start_at)
        self._keep_current(start_at)
        self.confirm(current, now)

    def set_user(self, at, start_at, probability, now) -> None:
        """휠로 고른 시각을 저장한다. 사용자가 고른 시각이므로 확인한 것으로 둔다."""
        self._clear_proposal()
        self.rejected_alarm_at = None
        self._pin(at, start_at, probability, REASON_PICKED)
        self.confirm(at, now)

    def use_recommended(self, recommended, now) -> None:
        """[추천 시각으로 되돌리기] 또는 휠로 추천 시각과 같은 시각을 골랐다. 추천 시각을 확인한 것으로 둔다.

        빠르게 고르기 세 칸(τ, `preset_chosen`)은 이것이 아니다 — 확인하지 않는다.
        """
        self.clear_choice()
        self.confirm(recommended, now)

    def after_compute(
        self,
        *,
        before_recommended,
        recommended,
        recommended_probability: float | None,
        start_at,
        trigger: str,
        now,
        probability_at: Callable[[datetime], float | None],
        reason: Callable[[], tuple[str, str]],
        before_start_at=None,
    ) -> None:
        """재계산 뒤. 직접 정한 시각은 두고 확률만 새로 구한다. 추천이 5분 넘게 움직이면 묻는다.

        `before_recommended` 는 재계산 전 추천 시각(계산 불가였으면 None), `recommended` 는 새
        추천 시각(계산 불가면 None)이다. `recommended_probability` 와 `probability_at(시각)` 은 새
        분포에서 그 시각에 일어날 때의 정시 도착 확률(소수, `on_time.precise_of`), `reason()` 은 제안 이유 (코드, 문장)이다. `before_start_at` 은 재계산
        전 계획을 만든 일정 시작이다(모르면 None — `start_at` 과 같다고 본다).
        """
        # 1) 일정 시작이 바뀌면 직접 정한 시각 · 제안 · 거절 기록을 버린다(9:00 일정의 7:50 은
        #    14:00 일정에 맞지 않다). 마감 처리보다 **먼저** 한다. 마감 처리가 먼저면 "그대로 두기"
        #    가 옛 시각을 새 시작에 묶어 14:00 일정이 7:40 에 울린다.
        #    직접 정한 시각은 정할 때의 시작(`user_alarm_start_at`)으로, 제안은 재계산 전 계획의
        #    시작(`before_start_at`)으로 본다.
        start_changed = before_start_at is not None and before_start_at != start_at
        if self.user_alarm_at is not None and not self.user_active(start_at):
            self.clear_choice()
        elif start_changed:
            self._clear_proposal()
            self.rejected_alarm_at = None
        # 2) 재계산 전에 마감이 지난 제안부터 처리한다. 그 뒤의 "지금 시각" 이 맞아야 한다.
        elif before_recommended is not None:
            self.settle(before_recommended, start_at, now)

        # 3) 계산 불가. 직접 정한 시각은 남겨 두고(다시 계산되면 그대로 쓴다) 제안만 버린다.
        if recommended is None:
            self._clear_proposal()
            self._set_user_probability(None)
            return

        if self.user_active(start_at):
            # 4) 직접 정한 시각. 시각은 그대로, 확률만. 70% 아래로 떨어지면 추천 시각으로 바꿀지 묻는다.
            probability = probability_at(self.user_alarm_at)
            self._set_user_probability(probability)
            if (
                probability is not None
                and probability < USER_PROBABILITY_FLOOR
                and self.user_alarm_at - recommended > PROPOSAL_THRESHOLD
                and not self._suppressed(recommended)
            ):
                self.held_alarm_at = None
                self._propose(
                    KIND_USER,
                    proposed=recommended,
                    keep=probability,
                    change=recommended_probability,
                    reason=(
                        "user_low_probability",
                        f"직접 정한 시각에 일어나면 정시 도착 확률이 {on_time.label(probability)}%예요",
                    ),
                    now=now,
                )
            else:
                self._clear_proposal()
        else:
            # 5) 추천 시각. 확인한 알람이 5분 넘게 움직였으면 지금 시각을 지키고 묻는다.
            previous = self.held_alarm_at or before_recommended
            if (
                trigger in PROPOSAL_TRIGGERS
                and previous is not None
                and self.confirmed(previous)
                and abs(recommended - previous) > PROPOSAL_THRESHOLD
            ):
                self._propose(
                    KIND_RECOMMENDED,
                    proposed=recommended,
                    keep=probability_at(previous),
                    change=recommended_probability,
                    reason=reason(),
                    now=now,
                )
                self.held_alarm_at = previous
            else:
                self._clear_proposal()

        # 6) 이미 마감이 지난 제안(알람 10분 안에 온 다시 계산)은 바로 처리한다.
        self.settle(recommended, start_at, now)


# 사용자 상태 필드 이름. 재계산 훅이 잠근 행에서 다시 읽고(`_lock_control`), 첫 알람 순서가 읽는다.
CONTROL_FIELDS = tuple(f.name for f in fields(Control))


# ---------------------------------------------------------------------------
# 계획 · 분포
# ---------------------------------------------------------------------------


def _recommended(plan: AlarmPlan | None):
    if plan is None or plan.status != AlarmPlan.Status.OK:
        return None
    return plan.alarm_at


def effective_of(control: Control, recommended, start_at, now=None):
    """울릴 시각. 마감이 지난 제안은 처리한 모습으로 본다(`present` 와 같다). `control` 은 바꾸지 않는다."""
    settled = Control(**{name: getattr(control, name) for name in CONTROL_FIELDS})
    settled.settle(recommended, start_at, now or timezone.now())
    return settled.current(recommended, start_at)


def effective_alarm_at(plan: AlarmPlan, start_at, now=None):
    """계획의 울릴 시각(응답 `effective_alarm_at`). 계산하지 못한 계획이면 None.

    "이미 울렸나 · 곧 울리나" · 그날 첫 알람 순서는 추천 시각(`alarm_at`)이 아니라 이 값으로 본다.
    """
    return effective_of(Control.of(plan), _recommended(plan), start_at, now)


def probability_function(prep, travel, start_at) -> Callable[[datetime], float | None]:
    """새 분포에서 "그 시각에 일어나면" 정시 도착 확률(%, 소수 한 자리)을 내는 함수.

    계획 확률 · 빠르게 고르기 세 칸 · 휠 표와 같은 식이다(`on_time`). 정수 칸은 `on_time.whole`.
    """
    if travel is None:
        return lambda _at: None
    return on_time.function(prep, travel, start_at)


def current_components(event, plan: AlarmPlan):
    """지금 계획을 만든 분포 재료 (준비, 이동). 카카오를 부르지 않는다. 다시 쓸 수 없으면 None.

    위험별 선택지(`risk_options`)와 같은 방식이다 — 마지막 이력의 경로 원값으로 다시 섞는다.
    """
    from . import buffers, history, services

    if plan.status != AlarmPlan.Status.OK:
        return None
    revision = history.reusable_revision(event, plan)
    if revision is None:
        return None
    return services.estimate_components(
        event,
        {
            "minutes": revision.route_minutes_raw,
            "walk_minutes": revision.walk_minutes_raw,
            "key": plan.route_key,
            "mode": plan.travel_mode,
        },
        buffer_minutes=buffers.current(event.user_id).minutes,
    )


def probability_now(event, plan: AlarmPlan, alarm_at) -> float | None:
    """지금 분포로 그 시각의 확률(소수). 재료가 없으면 None."""
    components = current_components(event, plan)
    if components is None:
        return None
    prep, travel = components
    return probability_function(prep, travel, event.start_at)(alarm_at)


def snapshot(plan: AlarmPlan | None) -> dict | None:
    """재계산 전 계획의 값. 계획이 없으면 None."""
    if plan is None:
        return None
    return {name: getattr(plan, name) for name in SNAPSHOT_FIELDS}


def snapshot_for(event) -> dict | None:
    return (
        AlarmPlan.objects.filter(event_id=event.pk).values(*SNAPSHOT_FIELDS).first()
        if event.pk is not None
        else None
    )


def describe_change(before: dict | None, plan: AlarmPlan) -> tuple[str, str]:
    """추천 알람이 움직인 가장 큰 이유. (`travel`·`prep`·`buffer`·`model`, 문장)."""
    parts = []
    for code, name, label in (
        ("travel", "travel_minutes", "이동 시간"),
        ("prep", "prep_minutes", "준비 시간"),
        ("buffer", "buffer_minutes", "여유 시간"),
    ):
        old = (before or {}).get(name)
        new = getattr(plan, name)
        if old is not None and new is not None and old != new:
            parts.append((abs(new - old), code, label, new - old))
    if not parts:
        return "model", "알람 계산에 쓰는 기록이 바뀌었어요"
    _size, code, label, delta = max(parts)
    return code, f"{label}이 {abs(delta)}분 {'늘었어요' if delta > 0 else '줄었어요'}"


def save(plan: AlarmPlan, control: Control) -> bool:
    """바뀐 필드만 쓴다. `save()` 를 쓰지 않는다 — `computed_at`(auto_now)이 "N분 전 계산" 이다."""
    changes = control.changes(plan)
    if not changes:
        return False
    AlarmPlan.objects.filter(pk=plan.pk).update(**changes)
    for name, value in changes.items():
        setattr(plan, name, value)
    return True


def after_compute(event, plan: AlarmPlan, before: dict | None, *, trigger: str, components=None) -> None:
    """재계산이 계획을 저장한 뒤 부른다(`services._upsert`, `refresh._remix`).

    `components` 는 그 계산의 (준비, 이동) 분포 재료다. 없으면 확률을 구하지 않는다.
    **실패해도 알람 계획은 그대로 둔다.** 저장점 안에서 하고 오류는 로그로만 남긴다 — 알람이
    핵심이고 확인·제안은 그 위의 표시다. 이 단계가 실패하면 추천 시각에 울린다.

    사용자 상태는 `plan` 인스턴스가 아니라 **잠근 행에서 다시 읽는다**(`_lock_control`). 재계산
    경로는 계획을 잠그지 않고 일찍 읽어 둔다(`refresh_upcoming` 은 일정 50개를 한 번에). 그사이
    `PUT …/alarm/time` 이 들어왔으면 옛 상태로 전이해 사용자가 정한 시각을 덮게 된다.
    """
    try:
        with transaction.atomic():
            control = _lock_control(plan)
            if control is None:
                return
            prep, travel = components if components is not None else (None, None)
            before_recommended = (
                before["alarm_at"]
                if before is not None and before.get("status") == AlarmPlan.Status.OK
                else None
            )
            control.after_compute(
                before_recommended=before_recommended,
                recommended=_recommended(plan),
                recommended_probability=_precise_or_whole(
                    plan.on_time_probability_precise, plan.on_time_probability
                ),
                start_at=event.start_at,
                trigger=trigger or "",
                now=timezone.now(),
                probability_at=probability_function(prep, travel, event.start_at),
                reason=lambda: describe_change(before, plan),
                before_start_at=_before_start(before),
            )
            save(plan, control)
    except Exception:  # noqa: BLE001 - 알람 계획 저장을 되돌리지 않는다
        logger.exception("일정 %s 알람 확인·제안 상태를 갱신하지 못했다", event.pk)


def _lock_control(plan: AlarmPlan) -> Control | None:
    """계획 행을 잠그고 사용자 상태를 DB 에서 다시 읽는다. 인스턴스에도 옮겨 `save` 의 비교가 맞게 한다."""
    row = (
        AlarmPlan.objects.select_for_update().filter(pk=plan.pk).values(*CONTROL_FIELDS).first()
    )
    if row is None:
        return None
    for name, value in row.items():
        setattr(plan, name, value)
    return Control(**row)


def _before_start(before: dict | None):
    """재계산 전 계획을 만든 일정 시작(도착 예정 + 버퍼, `services.alarm_fields`). 모르면 None."""
    if not before or before.get("status") != AlarmPlan.Status.OK:
        return None
    arrive_at, buffer_minutes = before.get("arrive_at"), before.get("buffer_minutes")
    if arrive_at is None or buffer_minutes is None:
        return None
    return arrive_at + timedelta(minutes=buffer_minutes)


# ---------------------------------------------------------------------------
# 사용자 조작 (뷰가 부른다. 계획 행을 잠근 트랜잭션 안에서)
# ---------------------------------------------------------------------------


def locked_plan(event) -> AlarmPlan | None:
    plan = AlarmPlan.objects.select_for_update().filter(event_id=event.pk).first()
    if plan is not None:
        plan.event = event
    return plan


def clear_user_choice(event) -> bool:
    """이 일정의 직접 정한 시각 · 기다리는 제안 · 거절 기록을 지운다. 바뀐 것이 있으면 True.

    **확인하지 않는다**(확인 기록도 지우지 않는다 — 비교라서 시각이 바뀌면 저절로 확인 전이다).
    계획 상태와 관계없이 지운다 — 다시 계산하지 못한 계획(`route_failed` 등)에 남겨 두면 다음에
    계산될 때 옛 직접 정한 시각이 되살아난다. 계획이 없으면 아무것도 하지 않는다.
    반복 "이후 일정 모두" 처럼 여러 회차면 `clear_user_choices` 를 쓴다.
    """
    with transaction.atomic():
        plan = locked_plan(event)
        if plan is None:
            return False
        control = Control.of(plan)
        control.clear_choice()
        return save(plan, control)


def clear_user_choices(event_ids) -> int:
    """여러 일정에 `clear_user_choice` 를 한 번에(잠그지 않는 UPDATE 한 번). 대상 계획 수를 돌려준다."""
    cleared = Control()
    values = {
        name: getattr(cleared, name)
        for name in CONTROL_FIELDS
        if name not in ("confirmed_alarm_at", "confirmed_at")
    }
    return AlarmPlan.objects.filter(event_id__in=list(event_ids)).update(**values)


def preset_chosen(event) -> None:
    """빠르게 고르기 세 칸(τ, `PATCH tau_override`)을 골랐다. 직접 정한 시각과 제안을 지운다.

    **확인하지 않는다.** 지금 앱(front-iter4)은 τ 칸을 PATCH 로 고르고 기기 알람을 `alarm_at` 으로
    걸며 확인 API 를 부르지 않는다. 여기서 확인함으로 두면 그 뒤 경로 갱신이 아무도 보지 않는
    "바꿀까요" 를 만들고, 답이 없으면 서버가 지킨 시각(`kept`)이 기기 알람과 어긋난다. 확인은
    명시적인 조작(`POST …/alarm/confirm`, `PUT …/alarm/time`, 제안 수락·거절)에서만 생긴다.
    """
    clear_user_choice(event)


# ---------------------------------------------------------------------------
# 응답
# ---------------------------------------------------------------------------


def present(plan: AlarmPlan, *, now=None) -> dict:
    """`alarm_plan` 응답에 붙는 필드. 마감이 지난 제안은 처리한 모습으로 내보낸다(DB 는 그대로)."""
    from rest_framework.fields import DateTimeField

    stamp = DateTimeField().to_representation

    def iso(value):
        return None if value is None else stamp(value)

    now = now or timezone.now()
    start_at = plan.event.start_at
    recommended = _recommended(plan)
    control = Control.of(plan)
    control.settle(recommended, start_at, now)
    effective = control.current(recommended, start_at)
    user = control.user_active(start_at) and recommended is not None

    if effective is None:
        precise = None
    elif user:
        precise = control.user_probability_precise()
    elif control.held_alarm_at is not None:
        precise = control.keep_probability_precise()
    else:
        precise = _precise_or_whole(plan.on_time_probability_precise, plan.on_time_probability)
    # 정수는 소수에서 만든다. 0.21.0 에 저장된 100 도 99 로 내보낸다(BE-P100).
    probability = on_time.whole(precise)

    proposal = None
    if control.has_proposal and effective is not None:
        proposal = {
            "kind": control.proposal_kind,
            "current_alarm_at": iso(effective),
            "proposed_alarm_at": iso(control.proposed_alarm_at),
            # 음수면 앞당기기, 양수면 늦추기(분).
            "move_minutes": _minutes(control.proposed_alarm_at - effective),
            "reason": control.proposal_reason,
            "reason_text": control.proposal_reason_text,
            "keep_probability": on_time.whole(control.keep_probability_precise()),
            "change_probability": on_time.whole(control.change_probability_precise()),
            "keep_probability_precise": control.keep_probability_precise(),
            "change_probability_precise": control.change_probability_precise(),
            "created_at": iso(control.proposed_at),
            "answer_by": iso(control.answer_by(recommended, start_at)),
        }

    # Partly AI-generated (Claude) — BE-H3f. 울릴 시각에 일어나면 출발까지 준비할 수 있는 분과 계획한
    # 준비와의 차이. 출발 시각(`depart_by`)은 울릴 시각과 상관없이 같다 — 이동 · 여유는 일정 시작에서
    # 거꾸로 정해진다. 8:40 기상 · 8:53 출발 · 계획 준비 28분이면 13분, 차이 −15(15분 모자람)다.
    effective_prep = None
    prep_margin = None
    if effective is not None and plan.depart_by is not None:
        effective_prep = max(0, _minutes(plan.depart_by - effective))
        if plan.prep_minutes is not None:
            prep_margin = effective_prep - plan.prep_minutes

    confirmed = control.confirmed(effective)
    return {
        "alarm_source": SOURCE_USER if user else SOURCE_RECOMMENDED,
        "recommended_alarm_at": iso(recommended),
        "effective_alarm_at": iso(effective),
        "effective_on_time_probability": probability,
        "effective_on_time_probability_precise": precise,
        "user_alarm_at": iso(control.user_alarm_at) if user else None,
        "user_alarm_reason": control.user_alarm_reason if user else "",
        "user_alarm_probability": (
            on_time.whole(control.user_probability_precise()) if user else None
        ),
        "user_alarm_probability_precise": control.user_probability_precise() if user else None,
        "alarm_confirmed": confirmed,
        "alarm_confirmed_at": iso(control.confirmed_at) if confirmed else None,
        "alarm_proposal": proposal,
        "effective_prep_minutes": effective_prep,
        "prep_margin_minutes": prep_margin,
    }
