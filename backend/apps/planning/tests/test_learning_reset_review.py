# Partly AI-generated (Claude)
"""기록 초기화 리뷰 지적(R-1 ~ R-9) 고정 테스트. 본 동작은 `test_learning_reset.py`.

- R-1 초기화 전 기록이 오프라인 큐에서 늦게 올라오면 받기만 하고 버린다
- R-2 진행 중인 아침(울림 · 출발)은 다시 섞지 않고, 이동 중 경로 변경은 남긴다
- R-3 초기화 뒤 첫 밤 학습이 낸 "바꿀까요" 제안은 거둔다
- R-4 50개 넘는 일정도 다시 섞고, 못 섞은 계획은 `skipped_plans` 로 센다
- R-5 곧 울릴 알람은 그대로, 다시 섞어도 곧 · 지난 시각으로 당기지 않는다
- R-6 주간 리포트 학습 카드는 초기화 전에 계산한 계획을 기록 출처로 세지 않는다
- R-8 밤 학습 중 초기화한 사용자의 개인 학습값을 다시 지운다
- R-9 직접 정한 알람 시각은 그대로, 남아 있던 제안은 거둔다
"""

from __future__ import annotations

from datetime import timedelta
from types import SimpleNamespace

import pytest
from django.core.management import call_command
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.observations.models import AlarmLog, TripObservation, TripReroute
from apps.planning import alarm_control, buffers, learning_reset, refresh, services
from apps.planning.buffers import DEFAULT_MINUTES
from apps.planning.models import AlarmPlan, BufferAdjustment, PlanRevision
from apps.planning.tests.test_learning_reset import ROUTE, make_user
from apps.prediction.models import ModelArtifact
from apps.reports import services as report_services
from apps.routines.models import BlockObservation, RoutineBlock
from apps.routing.models import UserTravelProfile

pytestmark = pytest.mark.django_db

RESET = "/api/auth/me/learning/reset"


@pytest.fixture
def alice(django_user_model):
    return make_user(django_user_model, "alice@example.com")


@pytest.fixture
def bob(django_user_model):
    return make_user(django_user_model, "bob@example.com")


@pytest.fixture
def place():
    return Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)


@pytest.fixture(autouse=True)
def route(monkeypatch):
    calls = []

    def best_route(**kw):
        calls.append("best")
        return dict(ROUTE), False

    def resolve_route(key, **kw):
        calls.append("resolve")
        return dict(ROUTE, key=key), False

    monkeypatch.setattr(services.clients, "best_route", best_route)
    monkeypatch.setattr(services.clients, "resolve_route", resolve_route)
    return calls


def client_for(user) -> APIClient:
    client = APIClient()
    client.force_authenticate(user)
    return client


def learned_buffer(user, minutes=15):
    """밤 학습이 지각 이력으로 버퍼를 늘린 상태. 초기화하면 기본값으로 돌아간다."""
    return BufferAdjustment.objects.create(
        user=user, previous_minutes=DEFAULT_MINUTES, minutes=minutes, reason="late_more",
        sample_count=6, late_count=3, expected_late=0.6, mean_slack_minutes=-2.0,
        tightest_slack_minutes=-9.0,
    )


def planned(user, place, start, title="수업") -> Event:
    event = Event.objects.create(user=user, place=place, title=title, start_at=start)
    services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
    return event


def planned_with_alarm_in(user, place, minutes) -> Event:
    """알람이 지금부터 약 `minutes` 분 뒤에 울리는 일정. 같은 조건의 일정으로 알람 앞당김을 잰다."""
    probe_start = (timezone.now() + timedelta(days=1)).replace(microsecond=0)
    probe = planned(user, place, probe_start, title="재기")
    lead = probe_start - AlarmPlan.objects.get(event=probe).alarm_at
    probe.delete()
    start = (timezone.now() + timedelta(minutes=minutes) + lead).replace(microsecond=0)
    event = planned(user, place, start)
    alarm_at = AlarmPlan.objects.get(event=event).alarm_at
    assert abs(alarm_at - (timezone.now() + timedelta(minutes=minutes))) < timedelta(minutes=1)
    return event


# ---------------------------------------------------------------------------
# R-1 오프라인 큐
# ---------------------------------------------------------------------------


@pytest.mark.integration
class TestQueuedUploadsAfterReset:
    def test_reset_records_its_time_on_the_profile(self, alice):
        assert alice.profile.learning_reset_at is None
        body = client_for(alice).post(RESET).json()
        alice.profile.refresh_from_db()
        assert alice.profile.learning_reset_at is not None
        assert body["reset_at"]

    def test_trip_observations_before_the_reset_are_ignored(self, alice, place):
        event = Event.objects.create(
            user=alice, place=place, title="지난 수업", start_at=timezone.now() - timedelta(hours=3)
        )
        client = client_for(alice)
        before = (timezone.now() - timedelta(hours=4)).isoformat()
        client.post(RESET)
        after = timezone.now().isoformat()

        res = client.post(
            "/api/observations/batch",
            {"observations": [
                {"event": event.pk, "kind": "depart", "detector": "manual",
                 "observed_at": before, "client_uuid": "old-depart"},
                {"event": event.pk, "kind": "arrive", "detector": "manual",
                 "observed_at": after, "client_uuid": "new-arrive"},
            ]},
            format="json",
        )

        assert res.status_code == 201
        body = res.json()
        assert (body["accepted"], body["duplicated"], body["ignored"]) == (1, 0, 1)
        assert list(
            TripObservation.objects.filter(user=alice).values_list("client_uuid", flat=True)
        ) == ["new-arrive"]

    def test_only_old_items_still_answer_2xx_so_the_queue_drains(self, alice, place):
        event = Event.objects.create(
            user=alice, place=place, title="지난 수업", start_at=timezone.now() - timedelta(hours=3)
        )
        client = client_for(alice)
        client.post(RESET)
        res = client.post(
            "/api/observations/batch",
            {"observations": [
                {"event": event.pk, "kind": "depart", "detector": "manual",
                 "observed_at": (timezone.now() - timedelta(hours=4)).isoformat(),
                 "client_uuid": "old-depart"},
            ]},
            format="json",
        )
        assert res.status_code == 200
        assert (res.json()["accepted"], res.json()["ignored"]) == (0, 1)
        assert not TripObservation.objects.filter(user=alice).exists()

    def test_alarm_logs_before_the_reset_are_ignored(self, alice, place):
        event = Event.objects.create(
            user=alice, place=place, title="지난 수업", start_at=timezone.now() - timedelta(hours=3)
        )
        client = client_for(alice)
        old = (timezone.now() - timedelta(hours=4)).isoformat()
        client.post(RESET)
        res = client.post(
            "/api/alarm-logs/batch",
            {"logs": [
                {"event": event.pk, "kind": "fired", "occurred_at": old, "client_uuid": "old-fired"},
                {"event": event.pk, "kind": "dismissed",
                 "occurred_at": timezone.now().isoformat(), "client_uuid": "new-dismissed"},
            ]},
            format="json",
        )
        assert res.status_code == 201
        assert (res.json()["accepted"], res.json()["ignored"]) == (1, 1)
        assert list(AlarmLog.objects.filter(user=alice).values_list("client_uuid", flat=True)) == [
            "new-dismissed"
        ]

    def test_block_observations_before_the_reset_are_ignored(self, alice):
        block = RoutineBlock.objects.create(
            user=alice, name="샤워", default_min_minutes=10, default_max_minutes=15
        )
        client = client_for(alice)
        old = timezone.now() - timedelta(hours=4)
        client.post(RESET)
        res = client.post(
            "/api/routines/observations/batch",
            {"observations": [
                {"block": block.pk, "observed_on": old.date().isoformat(), "duration_minutes": 12,
                 "client_uuid": "old-block", "client_recorded_at": old.isoformat()},
                {"block": block.pk, "observed_on": timezone.now().date().isoformat(),
                 "duration_minutes": 11, "client_uuid": "new-block",
                 "client_recorded_at": timezone.now().isoformat()},
            ]},
            format="json",
        )
        assert res.status_code == 201
        assert (res.json()["accepted"], res.json()["duplicated"], res.json()["ignored"]) == (1, 0, 1)
        assert list(
            BlockObservation.objects.filter(user=alice).values_list("client_uuid", flat=True)
        ) == ["new-block"]

    def test_users_who_never_reset_are_not_filtered(self, alice, bob, place):
        event = Event.objects.create(
            user=bob, place=place, title="지난 수업", start_at=timezone.now() - timedelta(days=30)
        )
        client_for(alice).post(RESET)
        res = client_for(bob).post(
            "/api/observations/batch",
            {"observations": [
                {"event": event.pk, "kind": "depart", "detector": "manual",
                 "observed_at": (timezone.now() - timedelta(days=30)).isoformat(),
                 "client_uuid": "bob-old"},
            ]},
            format="json",
        )
        assert (res.json()["accepted"], res.json()["ignored"]) == (1, 0)


# ---------------------------------------------------------------------------
# R-2 진행 중인 아침
# ---------------------------------------------------------------------------


class TestMorningInProgress:
    def test_fired_and_departed_event_is_not_recomputed(self, alice, place):
        learned_buffer(alice)
        # 추천 알람은 아직 앞이지만 사용자는 이미 일어나 출발했다(예: 직접 정한 더 이른 시각).
        event = planned(alice, place, (timezone.now() + timedelta(hours=2)).replace(microsecond=0))
        plan = AlarmPlan.objects.get(event=event)
        assert plan.alarm_at > timezone.now() + timedelta(minutes=30)
        AlarmLog.objects.create(
            user=alice, event=event, kind="fired", occurred_at=timezone.now(), client_uuid="f"
        )
        TripObservation.objects.create(
            user=alice, event=event, kind="depart", observed_at=timezone.now(),
            detector="manual", client_uuid="d",
        )
        reroute = TripReroute.objects.create(user=alice, event=event, to_route_key="walk")

        result = learning_reset.reset_learning(alice)

        after = AlarmPlan.objects.get(event=event)
        assert after.alarm_at == plan.alarm_at
        assert after.buffer_minutes == 15
        assert (result.recomputed_plans, result.skipped_plans) == (0, 1)
        # 이동 중 경로 변경은 남는다 — `routes/live` 가 바꾼 경로를 기준으로 삼는다.
        assert TripReroute.objects.filter(pk=reroute.pk).exists()
        assert result.deleted["trip_reroutes"] == 0
        assert result.deleted["alarm_logs"] == 1

    def test_reroutes_of_old_trips_are_still_deleted(self, alice, place):
        old = Event.objects.create(
            user=alice, place=place, title="지난주", start_at=timezone.now() - timedelta(days=3)
        )
        TripReroute.objects.create(user=alice, event=old, to_route_key="walk")
        result = learning_reset.reset_learning(alice)
        assert result.deleted["trip_reroutes"] == 1
        assert not TripReroute.objects.filter(user=alice).exists()


# ---------------------------------------------------------------------------
# R-3 · R-8 밤 학습
# ---------------------------------------------------------------------------


def confirmed_plan(user, place) -> Event:
    event = planned(user, place, (timezone.now() + timedelta(days=1)).replace(microsecond=0))
    plan = AlarmPlan.objects.get(event=event)
    AlarmPlan.objects.filter(pk=plan.pk).update(
        confirmed_alarm_at=plan.alarm_at, confirmed_at=timezone.now()
    )
    return event


class TestFirstNightAfterReset:
    def _nightly_moves_alarms(self, *users):
        """밤 학습이 알람을 10분 앞당기는 값을 만든다(여기서는 버퍼). 그리고 재계산한다."""
        for user in users:
            learned_buffer(user, minutes=DEFAULT_MINUTES + 10)
        started = timezone.now()
        refresh.refresh_after_learning()
        return started

    def test_proposal_from_the_first_night_is_dropped(self, alice, bob, place):
        mine = confirmed_plan(alice, place)
        theirs = confirmed_plan(bob, place)
        learning_reset.reset_learning(alice)
        # 초기화는 버퍼가 기본값이라 알람을 바꾸지 않는다. 확인은 그대로다.
        plan = AlarmPlan.objects.get(event=mine)
        before = plan.alarm_at

        started = self._nightly_moves_alarms(alice, bob)
        assert AlarmPlan.objects.get(event=mine).proposal_kind == alarm_control.KIND_RECOMMENDED

        assert learning_reset.drop_nightly_proposals(started) == 1

        plan = AlarmPlan.objects.get(event=mine)
        assert plan.proposal_kind == ""
        assert plan.held_alarm_at is None
        assert plan.alarm_at == before - timedelta(minutes=10)
        # 초기화하지 않은 사용자는 그대로 묻는다.
        assert AlarmPlan.objects.get(event=theirs).proposal_kind == alarm_control.KIND_RECOMMENDED

    def test_later_nights_ask_again(self, alice, place):
        mine = confirmed_plan(alice, place)
        learning_reset.reset_learning(alice)
        alice.profile.refresh_from_db()
        type(alice.profile).objects.filter(pk=alice.profile.pk).update(
            learning_reset_at=timezone.now() - timedelta(days=2)
        )
        started = self._nightly_moves_alarms(alice)
        assert learning_reset.drop_nightly_proposals(started) == 0
        assert AlarmPlan.objects.get(event=mine).proposal_kind == alarm_control.KIND_RECOMMENDED


class TestResetDuringNightlyTraining:
    def test_learned_rows_written_after_the_reset_are_removed(self, alice, bob, place):
        event = planned(alice, place, (timezone.now() + timedelta(days=1)).replace(microsecond=0))
        started = timezone.now()
        # 학습이 기록을 읽은 뒤 사용자가 초기화했다.
        learning_reset.reset_learning(alice)
        # 그 뒤 학습이 읽어 둔 옛 기록으로 개인 학습값을 쓰고 알람을 다시 섞었다.
        learned_buffer(alice)
        UserTravelProfile.objects.create(user=alice, walk_ratio=1.3, walk_samples=9)
        ModelArtifact.objects.create(user=alice, version="night", payload={"blocks": {}})
        learned_buffer(bob)
        refresh.refresh_upcoming(alice, trigger=PlanRevision.Trigger.LEARNING)
        assert AlarmPlan.objects.get(event=event).buffer_minutes == 15

        assert learning_reset.redo_after_nightly(started) == 1

        assert not BufferAdjustment.objects.filter(user=alice).exists()
        assert not UserTravelProfile.objects.filter(user=alice).exists()
        assert not ModelArtifact.objects.filter(user=alice).exists()
        assert AlarmPlan.objects.get(event=event).buffer_minutes == DEFAULT_MINUTES
        # 학습 시작 전에 초기화했거나 초기화하지 않은 사용자는 건드리지 않는다.
        assert BufferAdjustment.objects.filter(user=bob).count() == 1

    def test_reset_before_the_run_is_left_to_the_run(self, alice):
        learning_reset.reset_learning(alice)
        started = timezone.now() + timedelta(seconds=1)
        learned_buffer(alice)
        assert learning_reset.redo_after_nightly(started) == 0
        assert BufferAdjustment.objects.filter(user=alice).count() == 1

    def test_train_models_settles_resets_with_its_start_time(self, monkeypatch):
        seen = {}
        monkeypatch.setattr(
            learning_reset, "redo_after_nightly", lambda started: seen.setdefault("redo", started) and 0
        )
        monkeypatch.setattr(
            learning_reset, "drop_nightly_proposals", lambda started: seen.setdefault("drop", started) and 0
        )
        before = timezone.now()
        call_command("train_models", "--users", verbosity=0)
        assert before <= seen["redo"] == seen["drop"] <= timezone.now()

    def test_dry_run_does_not_settle(self, monkeypatch):
        called = []
        monkeypatch.setattr(learning_reset, "redo_after_nightly", lambda s: called.append(s) or 0)
        call_command("train_models", "--dry-run", verbosity=0)
        assert called == []


# ---------------------------------------------------------------------------
# R-4 다시 섞지 못한 계획
# ---------------------------------------------------------------------------


class TestPlansTheResetCannotRemix:
    def test_more_than_fifty_events_are_all_remixed(self, alice, place):
        learned_buffer(alice)
        base = (timezone.now() + timedelta(days=1)).replace(microsecond=0)
        events = [
            planned(alice, place, base + timedelta(minutes=10 * i), title=f"수업 {i}")
            for i in range(refresh.MAX_EVENTS_PER_USER + 5)
        ]

        result = learning_reset.reset_learning(alice)

        assert result.recomputed_plans == len(events)
        assert result.skipped_plans == 0
        assert set(
            AlarmPlan.objects.filter(event__in=events).values_list("buffer_minutes", flat=True)
        ) == {DEFAULT_MINUTES}

    def test_plan_without_a_reusable_raw_route_is_counted(self, alice, place):
        learned_buffer(alice)
        event = planned(alice, place, (timezone.now() + timedelta(days=1)).replace(microsecond=0))
        event.plan_revisions.all().delete()

        result = learning_reset.reset_learning(alice)

        assert (result.recomputed_plans, result.skipped_plans) == (0, 1)
        # 카카오를 부르지 않으므로 옛 값이 남는다. 앱 경로 갱신 · 밤 학습이 바꾼다.
        assert AlarmPlan.objects.get(event=event).buffer_minutes == 15

    @pytest.mark.integration
    def test_endpoint_reports_skipped_plans(self, alice, place):
        event = planned(alice, place, (timezone.now() + timedelta(days=1)).replace(microsecond=0))
        event.plan_revisions.all().delete()
        body = client_for(alice).post(RESET).json()
        assert body["skipped_plans"] == 1
        assert body["recomputed_plans"] == 0


# ---------------------------------------------------------------------------
# R-5 곧 울릴 알람
# ---------------------------------------------------------------------------


class TestAlarmAboutToRing:
    def test_alarm_two_minutes_away_is_left_alone(self, alice, place):
        learned_buffer(alice)
        event = planned_with_alarm_in(alice, place, 2)
        before = AlarmPlan.objects.get(event=event)

        result = learning_reset.reset_learning(alice)

        after = AlarmPlan.objects.get(event=event)
        assert after.alarm_at == before.alarm_at
        assert after.buffer_minutes == 15
        assert (result.recomputed_plans, result.skipped_plans) == (0, 1)

    def test_remix_never_pulls_an_alarm_into_the_next_minutes(self, alice, place, monkeypatch):
        event = planned_with_alarm_in(alice, place, 15)
        before = AlarmPlan.objects.get(event=event)
        # 초기화 뒤 기준이 알람을 10분 앞당긴다고 하자(예: 기록이 신고 범위보다 짧았던 준비 시간).
        real = buffers.current
        monkeypatch.setattr(
            buffers, "current",
            lambda user_id: buffers.Buffer(minutes=real(user_id).minutes + 10, source="default", since=None),
        )

        result = learning_reset.reset_learning(alice)

        after = AlarmPlan.objects.get(event=event)
        assert after.alarm_at == before.alarm_at
        assert (result.recomputed_plans, result.skipped_plans) == (0, 1)

    def test_an_alarm_further_away_still_moves(self, alice, place, monkeypatch):
        event = planned_with_alarm_in(alice, place, 40)
        before = AlarmPlan.objects.get(event=event)
        real = buffers.current
        monkeypatch.setattr(
            buffers, "current",
            lambda user_id: buffers.Buffer(minutes=real(user_id).minutes + 10, source="default", since=None),
        )
        result = learning_reset.reset_learning(alice)
        assert AlarmPlan.objects.get(event=event).alarm_at == before.alarm_at - timedelta(minutes=10)
        assert result.changed_plans == 1


# ---------------------------------------------------------------------------
# R-6 주간 리포트 학습 카드
# ---------------------------------------------------------------------------


class TestWeeklyLearningCard:
    def _outcome(self, computed_at):
        plan = SimpleNamespace(
            status=AlarmPlan.Status.OK,
            prep_source="observed",
            travel_source=f"kakao_transit+{report_services.TRAVEL_SOURCE_OBSERVED_MARK}",
            computed_at=computed_at,
        )
        return SimpleNamespace(event=SimpleNamespace(alarm_plan=plan))

    def test_plans_computed_before_the_reset_do_not_count_as_records(self):
        now = timezone.now()
        reset_at = now - timedelta(days=1)
        outcomes = [self._outcome(now - timedelta(days=2)), self._outcome(now)]
        assert report_services._learning(outcomes, reset_at) == {
            "plan_count": 2, "prep_from_records": 1, "travel_from_records": 1,
        }
        assert report_services._learning(outcomes) == {
            "plan_count": 2, "prep_from_records": 2, "travel_from_records": 2,
        }


# ---------------------------------------------------------------------------
# R-9 직접 정한 시각 · 남은 제안
# ---------------------------------------------------------------------------


class TestUserChoicesAndProposals:
    def test_user_set_alarm_is_kept(self, alice, place):
        learned_buffer(alice)
        event = planned(alice, place, (timezone.now() + timedelta(days=1)).replace(microsecond=0))
        plan = AlarmPlan.objects.get(event=event)
        mine = plan.alarm_at - timedelta(minutes=20)
        AlarmPlan.objects.filter(pk=plan.pk).update(
            user_alarm_at=mine, user_alarm_reason=alarm_control.REASON_PICKED,
            user_alarm_start_at=event.start_at, confirmed_alarm_at=mine,
            confirmed_at=timezone.now(),
        )

        learning_reset.reset_learning(alice)

        after = AlarmPlan.objects.select_related("event").get(event=event)
        assert after.buffer_minutes == DEFAULT_MINUTES  # 추천 시각은 새 기준으로
        assert after.user_alarm_at == mine
        shown = alarm_control.present(after)
        assert shown["alarm_source"] == alarm_control.SOURCE_USER
        assert shown["effective_alarm_at"] == shown["user_alarm_at"]
        assert shown["alarm_confirmed"] is True

    def test_pending_proposal_is_cleared_even_when_the_alarm_is_unchanged(self, alice, place):
        event = planned(alice, place, (timezone.now() + timedelta(days=1)).replace(microsecond=0))
        plan = AlarmPlan.objects.get(event=event)
        held = plan.alarm_at - timedelta(minutes=20)
        # 지난 밤 학습이 "20분 늦출까요" 를 물었고 아직 답하지 않았다. 지금 추천은 기본 기준과 같다.
        AlarmPlan.objects.filter(pk=plan.pk).update(
            confirmed_alarm_at=held, confirmed_at=timezone.now(), held_alarm_at=held,
            proposed_alarm_at=plan.alarm_at, proposal_kind=alarm_control.KIND_RECOMMENDED,
            proposal_reason="buffer", proposal_reason_text="여유 시간이 줄었어요",
            proposed_at=timezone.now(),
        )

        result = learning_reset.reset_learning(alice)

        after = AlarmPlan.objects.select_related("event").get(event=event)
        assert result.changed_plans == 0
        assert after.proposal_kind == ""
        assert after.held_alarm_at is None
        assert alarm_control.present(after)["effective_alarm_at"] is not None
        assert alarm_control.present(after)["alarm_proposal"] is None

    def test_pending_proposal_is_cleared_when_the_alarm_moves(self, alice, place):
        learned_buffer(alice)
        event = confirmed_plan(alice, place)
        plan = AlarmPlan.objects.get(event=event)
        AlarmPlan.objects.filter(pk=plan.pk).update(
            held_alarm_at=plan.alarm_at + timedelta(minutes=20),
            proposed_alarm_at=plan.alarm_at, proposal_kind=alarm_control.KIND_RECOMMENDED,
            proposed_at=timezone.now(),
        )
        learning_reset.reset_learning(alice)
        after = AlarmPlan.objects.get(event=event)
        assert after.proposal_kind == ""
        assert after.held_alarm_at is None
