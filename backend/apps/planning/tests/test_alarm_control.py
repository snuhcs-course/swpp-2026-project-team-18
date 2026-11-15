# Partly AI-generated (Claude)
"""알람 확인 · 직접 정한 시각 · "바꿀까요" 제안의 상태 전이(iter4 H2-3, `planning.alarm_control`).

앞부분은 DB 없는 순수 전이 테스트, 뒷부분은 실제 재계산 경로(`compute_and_store`,
`refresh.refresh_upcoming`)에 걸린 것을 본다.
"""

from datetime import datetime, timedelta, timezone

import pytest

from apps.planning import alarm_control as ac
from apps.planning.alarm_control import Control
from apps.planning.models import PlanRevision

KST = timezone(timedelta(hours=9))
START = datetime(2026, 11, 20, 9, 0, tzinfo=KST)
REC = datetime(2026, 11, 20, 7, 40, tzinfo=KST)
EARLY_NOW = datetime(2026, 11, 19, 22, 0, tzinfo=KST)
RECOMPUTE = PlanRevision.Trigger.RECOMPUTE
LEARNING = PlanRevision.Trigger.LEARNING
UPDATE = PlanRevision.Trigger.UPDATE


def m(minutes):
    return timedelta(minutes=minutes)


def compute(control, *, before, after, trigger=RECOMPUTE, now=EARLY_NOW, prob=None,
            rec_prob=90, start=START, reason=("travel", "이동 시간이 12분 늘었어요")):
    """재계산 한 번. `prob` 는 시각 → 확률 함수(기본: 추천 시각과의 거리로 단순하게)."""
    if prob is None:
        def prob(at):
            # 이를수록 높다. 추천 시각에서 90%, 1분 늦을 때마다 2%p 씩 떨어진다.
            return max(0, min(100, 90 - int((at - after).total_seconds() // 60) * 2))
    control.after_compute(
        before_recommended=before,
        recommended=after,
        recommended_probability=rec_prob,
        start_at=start,
        trigger=trigger,
        now=now,
        probability_at=prob,
        reason=lambda: reason,
    )
    return control


def confirmed_at(at):
    return Control(confirmed_alarm_at=at, confirmed_at=EARLY_NOW)


# ---------------------------------------------------------------------------
# 확인 상태 (BE-H3a)
# ---------------------------------------------------------------------------


def test_new_plan_is_unconfirmed():
    c = Control()
    assert not c.confirmed(REC)
    assert c.current(REC, START) == REC


def test_confirm_then_same_time_stays_confirmed():
    c = Control()
    c.confirm(REC, EARLY_NOW)
    compute(c, before=REC, after=REC)
    assert c.confirmed(c.current(REC, START))


@pytest.mark.parametrize("move", [-3, 3, -5, 5])
def test_small_move_is_applied_and_turns_unconfirmed(move):
    c = confirmed_at(REC)
    new = REC + m(move)
    compute(c, before=REC, after=new)
    assert not c.has_proposal
    assert c.current(new, START) == new
    assert not c.confirmed(new)


def test_unconfirmed_large_move_is_applied_without_asking():
    c = Control()
    new = REC - m(12)
    compute(c, before=REC, after=new)
    assert not c.has_proposal
    assert c.current(new, START) == new


@pytest.mark.parametrize("trigger", [UPDATE, PlanRevision.Trigger.CREATE, PlanRevision.Trigger.PROFILE,
                                     PlanRevision.Trigger.BLOCKS, PlanRevision.Trigger.ROUTINE, ""])
def test_user_input_recompute_applies_large_move(trigger):
    c = confirmed_at(REC)
    new = REC - m(12)
    compute(c, before=REC, after=new, trigger=trigger)
    assert not c.has_proposal
    assert c.current(new, START) == new
    assert not c.confirmed(new)


# ---------------------------------------------------------------------------
# 5분 넘게 움직이면 묻기 (BE-H3d)
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("trigger", [RECOMPUTE, LEARNING])
@pytest.mark.parametrize("move", [-12, 12, -6])
def test_confirmed_large_move_is_held_and_proposed(trigger, move):
    c = confirmed_at(REC)
    new = REC + m(move)
    compute(c, before=REC, after=new, trigger=trigger, rec_prob=91)
    assert c.has_proposal
    assert c.proposal_kind == ac.KIND_RECOMMENDED
    assert c.held_alarm_at == REC
    assert c.proposed_alarm_at == new
    # 답할 때까지 원래 시각에 울리고, 확인 상태도 그대로다.
    assert c.current(new, START) == REC
    assert c.confirmed(REC)
    assert c.proposal_change_probability == 91
    assert c.proposal_keep_probability is not None
    assert c.proposal_reason == "travel"
    assert c.proposed_at == EARLY_NOW
    assert c.answer_by(new, START) == REC - m(10)


def test_further_recompute_replaces_the_proposal():
    c = confirmed_at(REC)
    first = REC - m(12)
    compute(c, before=REC, after=first)
    later = EARLY_NOW + m(15)
    # 같은 제안이면 처음 물은 시각을 둔다.
    compute(c, before=first, after=first, now=later)
    assert c.proposed_at == EARLY_NOW
    second = REC - m(20)
    compute(c, before=first, after=second, now=later)
    assert c.proposed_alarm_at == second
    assert c.held_alarm_at == REC
    assert c.proposed_at == later


def test_recompute_back_near_held_time_drops_the_proposal():
    c = confirmed_at(REC)
    compute(c, before=REC, after=REC - m(12))
    back = REC - m(2)
    compute(c, before=REC - m(12), after=back)
    assert not c.has_proposal
    assert c.held_alarm_at is None
    assert c.current(back, START) == back


def test_user_edit_while_proposal_pending_applies_directly():
    c = confirmed_at(REC)
    compute(c, before=REC, after=REC - m(12))
    edited = REC - m(30)
    compute(c, before=REC - m(12), after=edited, trigger=UPDATE)
    assert not c.has_proposal
    assert c.current(edited, START) == edited


def test_accept_moves_to_recommended_and_confirms():
    c = confirmed_at(REC)
    new = REC - m(12)
    compute(c, before=REC, after=new)
    now = EARLY_NOW + m(5)
    c.accept(new, now)
    assert not c.has_proposal
    assert c.current(new, START) == new
    assert c.confirmed(new)
    assert c.confirmed_at == now


def test_reject_keeps_time_as_kept_user_alarm():
    c = confirmed_at(REC)
    new = REC - m(12)
    compute(c, before=REC, after=new)
    keep = c.proposal_keep_probability
    c.reject(new, START, EARLY_NOW)
    assert not c.has_proposal
    assert c.current(new, START) == REC
    assert c.user_alarm_at == REC
    assert c.user_alarm_reason == ac.REASON_KEPT
    assert c.user_alarm_probability == keep
    assert c.rejected_alarm_at == new
    assert c.confirmed(REC)


def test_rejected_proposal_is_not_asked_again():
    c = confirmed_at(REC)
    new = REC - m(12)
    compute(c, before=REC, after=new)
    c.reject(new, START, EARLY_NOW)
    # 직접 정한(kept) 시각의 확률이 70% 아래여도 같은 제안이면 다시 묻지 않는다.
    compute(c, before=new, after=new, prob=lambda at: 50)
    assert not c.has_proposal
    assert c.user_alarm_probability == 50
    # 추천이 5분 넘게 더 앞당겨지면 다시 묻는다.
    further = new - m(8)
    compute(c, before=new, after=further, prob=lambda at: 40)
    assert c.has_proposal
    assert c.proposal_kind == ac.KIND_USER
    assert c.proposed_alarm_at == further


def test_unanswered_earlier_proposal_applies_at_deadline():
    c = confirmed_at(REC)
    new = REC - m(12)
    compute(c, before=REC, after=new)
    deadline = REC - m(10)
    assert not c.settle(new, START, deadline - timedelta(seconds=1))
    assert c.settle(new, START, deadline)
    assert not c.has_proposal
    assert c.current(new, START) == new
    # 사람이 받아들인 것이 아니므로 확인 전이다.
    assert not c.confirmed(new)


def test_unanswered_later_proposal_keeps_earlier_time():
    c = confirmed_at(REC)
    new = REC + m(12)
    compute(c, before=REC, after=new)
    assert c.settle(new, START, REC - m(10))
    assert c.current(new, START) == REC
    assert c.user_alarm_reason == ac.REASON_KEPT
    assert c.rejected_alarm_at == new
    assert c.confirmed(REC)


def test_proposal_inside_answer_window_is_settled_immediately():
    c = confirmed_at(REC)
    new = REC - m(12)
    compute(c, before=REC, after=new, now=REC - m(8))
    assert not c.has_proposal
    assert c.current(new, START) == new


def test_expired_proposal_is_settled_before_the_next_recompute():
    c = confirmed_at(REC)
    first = REC + m(12)
    compute(c, before=REC, after=first)
    # 마감(7:30)이 지난 뒤 다시 계산: 먼저 이른 쪽(7:40)을 지킨 뒤 새 추천을 본다.
    compute(c, before=first, after=first, now=REC - m(5))
    assert c.current(first, START) == REC
    assert not c.has_proposal


# ---------------------------------------------------------------------------
# 직접 정한 시각 (BE-H3b)
# ---------------------------------------------------------------------------


def test_set_user_is_confirmed_and_clears_proposal():
    c = confirmed_at(REC)
    compute(c, before=REC, after=REC - m(12))
    picked = REC + m(10)
    c.set_user(picked, START, 81, EARLY_NOW)
    assert not c.has_proposal
    assert c.current(REC - m(12), START) == picked
    assert c.user_alarm_reason == ac.REASON_PICKED
    assert c.user_alarm_probability == 81
    assert c.confirmed(picked)


@pytest.mark.parametrize("trigger", [RECOMPUTE, LEARNING, UPDATE])
def test_recompute_keeps_user_time_and_updates_probability(trigger):
    c = Control()
    picked = REC + m(10)
    c.set_user(picked, START, 81, EARLY_NOW)
    new = REC - m(3)
    compute(c, before=REC, after=new, trigger=trigger, prob=lambda at: 77)
    assert c.user_alarm_at == picked
    assert c.user_alarm_probability == 77
    assert c.current(new, START) == picked
    assert c.confirmed(picked)
    assert not c.has_proposal


def test_user_time_below_floor_proposes_recommended():
    c = Control()
    picked = REC + m(10)
    c.set_user(picked, START, 81, EARLY_NOW)
    compute(c, before=REC, after=REC, prob=lambda at: 62, rec_prob=90)
    assert c.has_proposal
    assert c.proposal_kind == ac.KIND_USER
    assert c.proposed_alarm_at == REC
    assert c.proposal_keep_probability == 62
    assert c.proposal_change_probability == 90
    assert "62%" in c.proposal_reason_text
    assert c.held_alarm_at is None
    assert c.current(REC, START) == picked
    assert c.answer_by(REC, START) == picked - m(10)


@pytest.mark.parametrize("prob,gap", [(70, 10), (None, 10), (40, 5), (40, -10)])
def test_user_time_is_not_questioned(prob, gap):
    """70% 이상, 확률 없음, 추천과 5분 이내, 추천이 더 늦음 — 묻지 않는다."""
    c = Control()
    picked = REC + m(gap)
    c.set_user(picked, START, 81, EARLY_NOW)
    compute(c, before=REC, after=REC, prob=lambda at: prob)
    assert not c.has_proposal


def test_user_proposal_disappears_when_probability_recovers():
    c = Control()
    picked = REC + m(10)
    c.set_user(picked, START, 81, EARLY_NOW)
    compute(c, before=REC, after=REC, prob=lambda at: 60)
    assert c.has_proposal
    compute(c, before=REC, after=REC, prob=lambda at: 75)
    assert not c.has_proposal


def test_user_proposal_accept_and_reject():
    accepted = Control()
    picked = REC + m(10)
    accepted.set_user(picked, START, 81, EARLY_NOW)
    compute(accepted, before=REC, after=REC, prob=lambda at: 60)
    accepted.accept(REC, EARLY_NOW)
    assert accepted.user_alarm_at is None
    assert accepted.current(REC, START) == REC
    assert accepted.confirmed(REC)

    rejected = Control()
    rejected.set_user(picked, START, 81, EARLY_NOW)
    compute(rejected, before=REC, after=REC, prob=lambda at: 60)
    rejected.reject(REC, START, EARLY_NOW)
    assert rejected.user_alarm_at == picked
    assert rejected.user_alarm_reason == ac.REASON_PICKED
    assert rejected.rejected_alarm_at == REC
    assert rejected.confirmed(picked)
    # 같은 추천이면 다시 묻지 않는다.
    compute(rejected, before=REC, after=REC, prob=lambda at: 60)
    assert not rejected.has_proposal


def test_unanswered_user_proposal_uses_earlier_recommended():
    c = Control()
    picked = REC + m(10)
    c.set_user(picked, START, 81, EARLY_NOW)
    compute(c, before=REC, after=REC, prob=lambda at: 60)
    assert c.settle(REC, START, picked - m(10))
    assert c.user_alarm_at is None
    assert c.current(REC, START) == REC
    assert not c.confirmed(REC)


def test_start_change_drops_user_time():
    c = Control()
    c.set_user(REC + m(10), START, 81, EARLY_NOW)
    moved = START + timedelta(hours=1)
    assert not c.user_active(moved)
    assert c.current(REC, moved) == REC
    compute(c, before=REC, after=REC + timedelta(hours=1), start=moved, trigger=UPDATE)
    assert c.user_alarm_at is None


def test_failed_plan_keeps_user_time_but_drops_proposal():
    c = Control()
    picked = REC + m(10)
    c.set_user(picked, START, 81, EARLY_NOW)
    compute(c, before=REC, after=REC, prob=lambda at: 60)
    assert c.has_proposal
    compute(c, before=REC, after=None)
    assert not c.has_proposal
    assert c.user_alarm_at == picked
    assert c.user_alarm_probability is None
    assert c.current(None, START) is None
    # 다시 계산되면 직접 정한 시각을 그대로 쓴다.
    compute(c, before=None, after=REC, prob=lambda at: 80)
    assert c.current(REC, START) == picked


def test_use_recommended_clears_user_time_and_confirms():
    c = Control()
    c.set_user(REC + m(10), START, 81, EARLY_NOW)
    c.use_recommended(REC, EARLY_NOW)
    assert c.user_alarm_at is None
    assert c.rejected_alarm_at is None
    assert c.current(REC, START) == REC
    assert c.confirmed(REC)


def test_describe_change_picks_largest_component():
    before = {"travel_minutes": 30, "prep_minutes": 40, "buffer_minutes": 10}

    class Plan:
        travel_minutes, prep_minutes, buffer_minutes = 42, 38, 10

    assert ac.describe_change(before, Plan) == ("travel", "이동 시간이 12분 늘었어요")
    Plan.travel_minutes = 30
    assert ac.describe_change(before, Plan) == ("prep", "준비 시간이 2분 줄었어요")
    Plan.prep_minutes = 40
    assert ac.describe_change(before, Plan)[0] == "model"
    assert ac.describe_change(None, Plan)[0] == "model"


# ---------------------------------------------------------------------------
# 실제 재계산 경로
# ---------------------------------------------------------------------------


@pytest.fixture
def setup(django_user_model, monkeypatch):
    from apps.events.models import Event, Place
    from apps.planning import services
    from apps.routing.models import RouteCorrection
    from apps.routines.models import RoutineBlock

    user = django_user_model.objects.create_user(email="alarm-control@test.com", password="test12345")
    profile = user.profile
    profile.home_lat, profile.home_lng = 37.48, 126.92
    profile.save()
    place = Place.objects.create(name="학교", lat=37.46, lng=126.95)
    start = (datetime.now(KST) + timedelta(days=2)).replace(hour=9, minute=0, second=0, microsecond=0)
    event = Event.objects.create(user=user, place=place, title="수업", start_at=start)
    RoutineBlock.objects.create(user=user, name="샤워", default_min_minutes=10, default_max_minutes=30)
    RouteCorrection.objects.create(
        route_signature="transit:test", mode="transit", factor=1.2, sd_minutes=6, sample_count=20,
    )
    route = {"minutes": 20, "mode": "transit", "key": "transit:test", "source": "kakao"}
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(route), False))
    monkeypatch.setattr(services.clients, "resolve_route", lambda *a, **kw: (dict(route), False))
    return event, route


def _reload(event):
    from apps.planning.models import AlarmPlan

    plan = AlarmPlan.objects.get(event=event)
    plan.event = event
    return plan


@pytest.mark.django_db
def test_compute_and_store_holds_confirmed_alarm_and_proposes(setup):
    from apps.planning import services

    event, route = setup
    plan = services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
    shown = ac.present(plan)
    assert shown["alarm_confirmed"] is False
    assert shown["alarm_source"] == "recommended"
    assert shown["alarm_proposal"] is None
    first = plan.alarm_at

    control = Control.of(plan)
    control.confirm(first, datetime.now(KST))
    ac.save(plan, control)

    route["minutes"] = 40
    plan = services.compute_and_store(event, trigger=RECOMPUTE)
    assert plan.alarm_at < first - m(5)  # 추천은 계산 결과대로 움직인다
    plan = _reload(event)
    shown = ac.present(plan)
    assert shown["effective_alarm_at"] == shown["alarm_proposal"]["current_alarm_at"]
    assert datetime.fromisoformat(shown["effective_alarm_at"]) == first
    proposal = shown["alarm_proposal"]
    assert proposal["kind"] == "recommended_moved"
    assert proposal["reason"] == "travel"
    assert proposal["move_minutes"] < -5
    assert proposal["keep_probability"] < proposal["change_probability"]
    assert shown["alarm_confirmed"] is True
    assert shown["recommended_alarm_at"] == proposal["proposed_alarm_at"]


@pytest.mark.django_db
def test_user_probability_matches_risk_option_math(setup):
    """직접 정한 시각을 추천 시각과 같게 두면 확률이 계획 확률과 거의 같다(같은 분포)."""
    from apps.planning import services

    event, _route = setup
    plan = services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
    assert plan.on_time_probability is not None
    at_recommended = ac.probability_now(event, plan, plan.alarm_at)
    assert abs(at_recommended - plan.on_time_probability) <= 1
    later = ac.probability_now(event, plan, plan.alarm_at + m(15))
    earlier = ac.probability_now(event, plan, plan.alarm_at - m(15))
    assert later < at_recommended < earlier


@pytest.mark.django_db
def test_learning_refresh_keeps_user_time(setup):
    from apps.planning import refresh, services
    from apps.routing.models import RouteCorrection

    event, _route = setup
    plan = services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
    picked = plan.alarm_at + m(20)
    control = Control.of(plan)
    control.set_user(picked, event.start_at, ac.probability_now(event, plan, picked), datetime.now(KST))
    ac.save(plan, control)
    before_probability = plan.user_alarm_probability

    RouteCorrection.objects.update(factor=1.8)
    outcomes = refresh.refresh_upcoming(event.user, trigger=LEARNING)
    assert outcomes[refresh.CHANGED] == 1
    plan = _reload(event)
    assert plan.user_alarm_at == picked
    assert plan.user_alarm_probability < before_probability
    shown = ac.present(plan)
    assert shown["alarm_source"] == "user"
    assert datetime.fromisoformat(shown["effective_alarm_at"]) == picked
    assert shown["effective_on_time_probability"] == plan.user_alarm_probability
    if plan.user_alarm_probability < ac.USER_PROBABILITY_FLOOR:
        assert shown["alarm_proposal"]["kind"] == "user_low_probability"


@pytest.mark.django_db
def test_hook_failure_does_not_break_plan(setup, monkeypatch):
    from apps.planning import services

    event, _route = setup

    def boom(self, **kwargs):
        raise RuntimeError("boom")

    monkeypatch.setattr(Control, "after_compute", boom)
    plan = services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
    assert plan.status == "ok" and plan.alarm_at is not None
