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
            rec_prob=90, start=START, reason=("travel", "이동 시간이 12분 늘었어요"), before_start=None):
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
        **({"before_start_at": before_start} if before_start is not None else {}),
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


def test_confirm_twice_keeps_first_moment():
    c = Control()
    c.confirm(REC, EARLY_NOW)
    c.confirm(REC, EARLY_NOW + m(30))
    assert c.confirmed_at == EARLY_NOW
    c.confirm(REC - m(5), EARLY_NOW + m(40))
    assert c.confirmed_at == EARLY_NOW + m(40)


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
    # 마감은 두 시각 중 더 이른 쪽의 10분 전이다.
    assert c.answer_by(new, START) == min(REC, new) - m(10)


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
    """앞당기는 제안의 마감은 **제안 시각**의 10분 전이다. 7:40 지킴 · 7:28 제안 → 7:18 마감."""
    c = confirmed_at(REC)
    new = REC - m(12)
    compute(c, before=REC, after=new)
    deadline = new - m(10)
    assert c.answer_by(new, START) == deadline
    assert not c.settle(new, START, deadline - timedelta(seconds=1))
    assert c.settle(new, START, deadline)
    assert not c.has_proposal
    assert c.current(new, START) == new
    # 사람이 받아들인 것이 아니므로 확인 전이다.
    assert not c.confirmed(new)


def test_unanswered_earlier_proposal_read_late_keeps_the_deadline_decision():
    """마감 뒤 늦게 읽어도(서버에는 스케줄러가 없다) 마감에 정했을 결론이다 — 앱이 기기에서 정한 것과 같다."""
    c = confirmed_at(REC)
    new = REC - m(25)  # 7:15
    compute(c, before=REC, after=new)
    # 7:30 에야 읽었다. 마감(7:05)에 7:15 로 정했어야 했다.
    assert c.settle(new, START, REC - m(10))
    assert c.current(new, START) == new


@pytest.mark.parametrize("move", [-11, -15, -25, -40, -70])
@pytest.mark.parametrize("created", [-120, -60, -45, -36, -30, -26, -25, -20, -15, -11, -10, -5, -1, 0, 5])
def test_unanswered_advance_never_switches_to_a_time_already_past(move, created):
    """10분 넘게 앞당기는 제안에 답이 없으면, 언제 만들어졌든 바뀌는 순간에 이미 지난 시각으로 바꾸지 않는다.

    `created` 는 제안을 만든 다시 계산의 시각(지키는 시각 7:40 기준 분). 그 뒤 1분마다 서버가 읽는다.
    """
    c = confirmed_at(REC)
    new = REC + m(move)
    t = REC + m(created)
    compute(c, before=REC, after=new, now=t)
    moment = t
    while c.has_proposal and moment <= REC + m(10):
        moment += m(1)
        c.settle(new, START, moment)
    assert not c.has_proposal
    effective = c.current(new, START)
    assert effective in (new, REC)
    if effective == new:
        # 바꿨다면, 바꾼 순간(마감 또는 마감 뒤에 만든 제안이면 만든 순간)보다 1분 넘게 뒤다.
        decided = max(new - m(10), t)
        assert effective > decided + ac.SETTLE_MIN_AHEAD
    else:
        # 지켰다면, 제안 시각은 그 순간에 이미 지났거나 1분도 안 남았던 것이다.
        assert new <= t + ac.SETTLE_MIN_AHEAD


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
    """마감 뒤에 생긴 제안은 바로 처리한다. 제안 시각이 아직 1분 넘게 남았으면 그 시각으로 바꾼다."""
    c = confirmed_at(REC)
    new = REC - m(12)  # 7:28
    compute(c, before=REC, after=new, now=new - m(5))  # 7:23 (마감 7:18 뒤)
    assert not c.has_proposal
    assert c.current(new, START) == new


def test_proposal_for_a_time_already_past_keeps_the_current_time():
    """7:40 지킴, 7:32 에 온 다시 계산이 7:28 을 제안 → 7:28 은 이미 지났다. 7:40 을 지킨다(H3-1)."""
    c = confirmed_at(REC)
    new = REC - m(12)
    compute(c, before=REC, after=new, now=REC - m(8))
    assert not c.has_proposal
    assert c.current(new, START) == REC
    assert c.rejected_alarm_at == new
    # 1분도 안 남은 시각도 마찬가지다.
    c = confirmed_at(REC)
    compute(c, before=REC, after=new, now=new - timedelta(seconds=30))
    assert c.current(new, START) == REC


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
    # 추천 시각(더 이르다)의 10분 전.
    assert c.answer_by(REC, START) == REC - m(10)


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


def test_expired_proposal_and_start_edit_in_the_same_recompute():
    """마감이 지난 "늦추기" 제안 + 일정 시작 수정(9:00 → 14:00). 옛 7:40 을 새 일정에 묶지 않는다(H3-2)."""
    c = confirmed_at(REC)
    later = REC + m(12)
    compute(c, before=REC, after=later)
    assert c.has_proposal
    moved = START.replace(hour=14)
    new = REC.replace(hour=12)
    compute(c, before=later, after=new, start=moved, trigger=UPDATE, now=REC - m(5),
            before_start=START)
    assert c.user_alarm_at is None
    assert not c.has_proposal
    assert c.current(new, moved) == new


def test_start_change_drops_a_pending_recommended_proposal():
    c = confirmed_at(REC)
    compute(c, before=REC, after=REC - m(12))
    assert c.held_alarm_at == REC
    moved = START + timedelta(hours=1)
    new = REC + timedelta(hours=1) - m(12)
    compute(c, before=REC - m(12), after=new, start=moved, trigger=RECOMPUTE, before_start=START)
    assert not c.has_proposal and c.held_alarm_at is None
    assert c.current(new, moved) == new


def test_clear_choice_keeps_confirmation_record():
    c = confirmed_at(REC)
    c.set_user(REC + m(10), START, 81, EARLY_NOW)
    c.clear_choice()
    assert c.user_alarm_at is None and not c.has_proposal and c.rejected_alarm_at is None
    # 확인 기록은 남는다. 비교라서 지금 시각(추천)과 다르면 확인 전이다.
    assert c.confirmed_alarm_at == REC + m(10)
    assert not c.confirmed(c.current(REC, START))


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


# ---------------------------------------------------------------------------
# 리뷰 수정 (H3-2 · H3-3 · H3-5 · H3-7c)
# ---------------------------------------------------------------------------


def _expire_later_proposal(event):
    """확인한 알람(지금+5분)을 지키며 늦추는 제안(지금+20분)을 기다리는 상태. 마감(지금−5분)이 지났다."""
    from django.utils import timezone as dj_tz

    from apps.planning.models import AlarmPlan

    now = dj_tz.now().replace(second=0, microsecond=0)
    held = now + m(5)
    AlarmPlan.objects.filter(event=event).update(
        confirmed_alarm_at=held, confirmed_at=now - m(60), held_alarm_at=held,
        proposed_alarm_at=now + m(20), proposal_kind=ac.KIND_RECOMMENDED, proposal_reason="travel",
        proposal_reason_text="이동 시간이 15분 줄었어요", proposal_keep_probability=80,
        proposal_change_probability=90, proposed_at=now - m(60),
    )
    return held


@pytest.mark.django_db
def test_start_edit_after_expired_proposal_does_not_pin_old_time(setup):
    """마감이 지난 제안이 있는 채로 일정 시작을 고쳐 경로부터 다시 계산해도 옛 시각을 새 시작에 묶지 않는다(H3-2)."""
    from apps.planning import services

    event, _route = setup
    services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
    held = _expire_later_proposal(event)
    event.start_at = event.start_at + timedelta(hours=5)
    event.save(update_fields=["start_at"])
    services.compute_and_store(event, trigger=UPDATE)
    plan = _reload(event)
    assert plan.user_alarm_at is None and plan.proposed_alarm_at is None
    shown = ac.present(plan)
    assert shown["alarm_source"] == "recommended"
    assert shown["effective_alarm_at"] == shown["recommended_alarm_at"]
    assert datetime.fromisoformat(shown["effective_alarm_at"]) != held


@pytest.mark.django_db
def test_start_edit_through_refresh_edited_drops_pending_proposal(setup):
    """원값으로 다시 섞는 수정 경로(`refresh_edited`)도 시작이 바뀌면 지키던 시각을 버린다(H3-2)."""
    from apps.planning import refresh, services

    event, _route = setup
    services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
    _expire_later_proposal(event)
    start_before = event.start_at
    event.start_at = start_before + timedelta(hours=5)
    event.save(update_fields=["start_at"])
    assert refresh.refresh_edited(event, start_before=start_before) is not None
    plan = _reload(event)
    assert plan.user_alarm_at is None and plan.held_alarm_at is None
    assert ac.effective_alarm_at(plan, event.start_at) == plan.alarm_at


@pytest.mark.django_db
def test_after_compute_rereads_control_under_lock(setup):
    """재계산이 일찍 읽어 둔(낡은) 계획 인스턴스로 전이해도 그사이 저장된 직접 정한 시각을 덮지 않는다(H3-3)."""
    from django.utils import timezone as dj_tz

    from apps.planning import services

    event, _route = setup
    plan = services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
    _expire_later_proposal(event)
    stale = _reload(event)  # 재계산 경로가 먼저 읽은 상태: 마감이 지난 제안을 기다린다
    assert stale.proposed_alarm_at is not None

    # 그사이 PUT /alarm/time.
    picked = plan.alarm_at + m(10)
    fresh = _reload(event)
    control = Control.of(fresh)
    control.set_user(picked, event.start_at, 88, dj_tz.now())
    ac.save(fresh, control)

    ac.after_compute(event, stale, ac.snapshot(stale), trigger=RECOMPUTE,
                     components=ac.current_components(event, stale))
    stored = _reload(event)
    assert stored.user_alarm_at == picked
    assert stored.user_alarm_reason == ac.REASON_PICKED
    assert stored.proposed_alarm_at is None


@pytest.mark.django_db
def test_concurrent_put_during_refresh_is_not_overwritten(setup, monkeypatch):
    """밤 재계산(`refresh_upcoming`)이 계획을 다시 섞는 도중 PUT 이 들어와도 사용자가 고른 시각이 남는다(H3-3)."""
    from django.utils import timezone as dj_tz

    from apps.planning import history, refresh, services
    from apps.routing.models import RouteCorrection

    event, _route = setup
    plan = services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
    _expire_later_proposal(event)
    picked = plan.alarm_at + m(10)
    original = history.record_revision

    def put_meanwhile(*args, **kwargs):
        result = original(*args, **kwargs)
        fresh = _reload(event)
        control = Control.of(fresh)
        control.set_user(picked, event.start_at, 88, dj_tz.now())
        ac.save(fresh, control)
        return result

    monkeypatch.setattr(history, "record_revision", put_meanwhile)
    RouteCorrection.objects.update(factor=1.5)
    outcomes = refresh.refresh_upcoming(event.user, trigger=LEARNING)
    assert outcomes[refresh.CHANGED] == 1
    stored = _reload(event)
    assert stored.user_alarm_at == picked
    assert stored.user_alarm_reason == ac.REASON_PICKED


@pytest.mark.django_db
def test_refresh_skip_soon_uses_effective_time(setup):
    """직접 정한 시각이 보호 구간 안이면, 추천 시각이 멀어도 "곧 울림" 이다(H3-5)."""
    from django.utils import timezone as dj_tz

    from apps.planning import refresh, services
    from apps.planning.models import AlarmPlan
    from apps.routing.models import RouteCorrection

    event, _route = setup
    services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
    soon = dj_tz.now().replace(second=0, microsecond=0) + m(30)
    AlarmPlan.objects.filter(event=event).update(
        user_alarm_at=soon, user_alarm_start_at=event.start_at, user_alarm_reason=ac.REASON_PICKED,
    )
    RouteCorrection.objects.update(factor=1.5)
    outcomes = refresh.refresh_upcoming(event.user, trigger=LEARNING, protect=timedelta(hours=3))
    assert outcomes == {refresh.SKIP_SOON: 1}


@pytest.mark.django_db
def test_first_alarm_follows_effective_time(setup):
    """그날 첫 알람은 울릴 시각 순서다. 늦은 일정의 알람을 직접 앞당기면 그 일정이 첫 알람이다(H3-5)."""
    from apps.events.alarms import alarm_states
    from apps.events.models import Event
    from apps.planning import services
    from apps.planning.models import AlarmPlan

    event, _route = setup
    later = Event.objects.create(
        user=event.user, place=event.place, title="실험", start_at=event.start_at + timedelta(hours=1),
    )
    first = services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
    services.compute_and_store(later, trigger=PlanRevision.Trigger.CREATE)
    assert alarm_states(event.user)[event.pk] == (True, True)
    assert alarm_states(event.user)[later.pk] == (False, False)

    AlarmPlan.objects.filter(event=later).update(
        user_alarm_at=first.alarm_at - m(20), user_alarm_start_at=later.start_at,
        user_alarm_reason=ac.REASON_PICKED,
    )
    states = alarm_states(event.user)
    assert states[later.pk] == (True, True)
    assert states[event.pk] == (False, False)


@pytest.mark.django_db
def test_clear_user_choice_helpers(setup):
    """반복 "이후 일정 모두" 등에서 부르는 공개 도우미. 확인하지 않고, 계획 상태와 관계없이 지운다(H3-7c)."""
    from django.utils import timezone as dj_tz

    from apps.events.models import Event
    from apps.planning import services
    from apps.planning.models import AlarmPlan

    event, _route = setup
    other = Event.objects.create(
        user=event.user, place=event.place, title="실험", start_at=event.start_at + timedelta(days=1),
    )
    for item in (event, other):
        plan = services.compute_and_store(item, trigger=PlanRevision.Trigger.CREATE)
        control = Control.of(plan)
        control.set_user(plan.alarm_at + m(10), item.start_at, 80, dj_tz.now())
        ac.save(plan, control)
    _expire_later_proposal(other)
    AlarmPlan.objects.filter(event=event).update(status=AlarmPlan.Status.ROUTE_FAILED)

    assert ac.clear_user_choice(event) is True
    assert ac.clear_user_choice(event) is False  # 두 번째는 바뀐 것이 없다
    stored = _reload(event)
    assert stored.user_alarm_at is None and stored.user_alarm_reason == ""
    assert stored.confirmed_alarm_at is not None  # 확인 기록은 그대로(확인하지 않는다)

    assert ac.clear_user_choices([other.pk]) == 1
    stored = _reload(other)
    assert stored.user_alarm_at is None and stored.proposed_alarm_at is None
    assert stored.held_alarm_at is None and stored.proposal_kind == ""
    assert stored.confirmed_alarm_at is not None
    assert ac.present(stored)["alarm_source"] == "recommended"
