# Partly AI-generated (Claude)
"""쌓인 기록 초기화(iter4 BE-RESET, `planning.learning_reset`).

두 사용자에게 지우는 모델마다 행을 만들고, 부른 사용자의 행만 사라지는지 본다. 남기기로 한
것(일정 · 루틴 · 집 · 계정 · 공용 보정 · 전역 아티팩트)은 그대로인지도 본다. 카카오 경로는
모킹하고 다시 섞기가 부르지 않는지 센다.
"""

from __future__ import annotations

from datetime import date, timedelta

import pytest
from django.core.cache import cache
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts.models import SavedPlace
from apps.events.models import Event, EventTag, Place
from apps.observations.models import AlarmLog, TripObservation, TripReroute
from apps.planning import learning_reset, refresh, services
from apps.planning.buffers import DEFAULT_MINUTES
from apps.planning.models import AlarmPlan, BufferAdjustment, PlanRevision
from apps.prediction.models import ModelArtifact
from apps.reports.models import LlmCall, WeeklySummary
from apps.routines.models import BlockObservation, EventBlockSelection, RoutineBlock
from apps.routing.models import GLOBAL_SIGNATURE, RouteCorrection, UserTravelProfile

pytestmark = pytest.mark.django_db

PATH = "/api/auth/me/learning/reset"
PASSWORD = "Rk5-amber-willow-63"

ROUTE = {
    "minutes": 20,
    "mode": "transit",
    "source": "kakao_transit",
    "summary": "2호선 → 5513 · 20분",
    "key": "transit:2호선>5513",
    "detail": "2호선 → 5513",
    "path": [[37.48, 126.93], [37.46, 126.95]],
}

# 지우는 모델(응답 키 → 모델). 하나라도 빠지면 아래 테스트가 그 모델을 만들지 않아 드러난다.
DELETED = {
    "trip_observations": TripObservation,
    "alarm_logs": AlarmLog,
    "trip_reroutes": TripReroute,
    "block_observations": BlockObservation,
    "buffer_adjustments": BufferAdjustment,
    "weekly_summaries": WeeklySummary,
    "model_artifacts": ModelArtifact,
    "travel_profiles": UserTravelProfile,
}


def make_user(django_user_model, email):
    user = django_user_model.objects.create_user(email=email, password=PASSWORD, nickname="초기화")
    profile = user.profile
    profile.home_lat, profile.home_lng, profile.home_label = 37.4842, 126.9297, "신림역"
    profile.onboarding_prep_min = 30
    profile.save()
    return user


@pytest.fixture
def alice(django_user_model):
    return make_user(django_user_model, "alice@example.com")


@pytest.fixture
def bob(django_user_model):
    return make_user(django_user_model, "bob@example.com")


@pytest.fixture
def place():
    return Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)


@pytest.fixture
def route(monkeypatch):
    """경로 조회를 고정값으로. `calls` 로 호출 횟수를 센다."""
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


def seed_records(user, place, tag="r") -> Event:
    """지우는 모델마다 한 행씩. 지난 일정 하나에 매단다."""
    now = timezone.now()
    past = Event.objects.create(
        user=user, place=place, title="지난 수업", start_at=now - timedelta(days=2)
    )
    block = RoutineBlock.objects.create(
        user=user, name="샤워", default_min_minutes=10, default_max_minutes=15
    )
    TripObservation.objects.create(
        user=user, event=past, kind="depart", observed_at=past.start_at - timedelta(minutes=40),
        detector="manual", client_uuid=f"{tag}-trip",
    )
    AlarmLog.objects.create(
        user=user, event=past, kind="fired", occurred_at=past.start_at - timedelta(minutes=80),
        client_uuid=f"{tag}-alarm",
    )
    TripReroute.objects.create(user=user, event=past, to_route_key="walk")
    BlockObservation.objects.create(
        user=user, block=block, event=past, observed_on=past.start_at.date(),
        duration_minutes=12, client_uuid=f"{tag}-block",
        client_recorded_at=past.start_at - timedelta(minutes=60),
    )
    BufferAdjustment.objects.create(
        user=user, previous_minutes=10, minutes=15, reason="late_more", sample_count=6,
        late_count=3, expected_late=0.6, mean_slack_minutes=-2.0, tightest_slack_minutes=-9.0,
    )
    WeeklySummary.objects.create(
        user=user, week_start=date(2026, 11, 2), facts_hash="h" * 64, text="지난주 요약"
    )
    ModelArtifact.objects.create(user=user, version=f"v-{tag}", payload={"blocks": {}})
    UserTravelProfile.objects.create(user=user, walk_ratio=1.2, walk_samples=8)
    return past


def counts(user) -> dict[str, int]:
    return {key: model.objects.filter(user=user).count() for key, model in DELETED.items()}


class TestResetLearning:
    def test_deletes_only_the_callers_records(self, alice, bob, place):
        past = seed_records(alice, place, "a")
        seed_records(bob, place, "b")
        global_artifact = ModelArtifact.objects.create(user=None, version="global", payload={})
        correction = RouteCorrection.objects.create(
            route_signature=GLOBAL_SIGNATURE, factor=1.2, sd_minutes=3.0, sample_count=9
        )
        LlmCall.objects.create(
            user=alice, day=date(2026, 11, 9), week_start=date(2026, 11, 2), outcome="ok"
        )
        tag = EventTag.objects.create(key="u0123456789", label="동아리", user=alice)
        saved = SavedPlace.objects.create(user=alice, label="본가", lat=37.5, lng=127.0)
        block = RoutineBlock.objects.get(user=alice)
        EventBlockSelection.objects.create(event=past, block=block, checked=False)

        result = learning_reset.reset_learning(alice)

        assert result.deleted == {key: 1 for key in DELETED}
        assert counts(alice) == {key: 0 for key in DELETED}
        assert counts(bob) == {key: 1 for key in DELETED}
        # 남기는 것.
        assert ModelArtifact.objects.filter(pk=global_artifact.pk).exists()
        assert RouteCorrection.objects.filter(pk=correction.pk).exists()
        assert LlmCall.objects.filter(user=alice).count() == 1
        assert Event.objects.filter(pk=past.pk).exists()
        assert EventTag.objects.filter(pk=tag.pk).exists()
        assert SavedPlace.objects.filter(pk=saved.pk).exists()
        assert RoutineBlock.objects.filter(user=alice).count() == 1
        assert EventBlockSelection.objects.filter(event=past).count() == 1
        alice.profile.refresh_from_db()
        assert alice.profile.onboarding_prep_min == 30
        assert alice.profile.home_label == "신림역"

    def test_is_idempotent(self, alice, place):
        seed_records(alice, place)
        learning_reset.reset_learning(alice)
        again = learning_reset.reset_learning(alice)
        assert again.deleted == {key: 0 for key in DELETED}
        assert again.recomputed_plans == 0

    def test_refuses_a_missing_user(self):
        # `filter(user=None)` 이면 전역 아티팩트를 지운다. 그 길을 막는다.
        with pytest.raises(ValueError):
            learning_reset.reset_learning(None)

    def test_upcoming_alarm_goes_back_to_the_default_buffer(self, alice, place, route):
        BufferAdjustment.objects.create(
            user=alice, previous_minutes=10, minutes=15, reason="late_more", sample_count=6,
            late_count=3, expected_late=0.6, mean_slack_minutes=-2.0, tightest_slack_minutes=-9.0,
        )
        start = (timezone.now() + timedelta(days=1)).replace(second=0, microsecond=0)
        event = Event.objects.create(user=alice, place=place, title="수업", start_at=start)
        services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
        before = AlarmPlan.objects.get(event=event)
        assert before.buffer_minutes == 15
        lookups = len(route)

        result = learning_reset.reset_learning(alice)

        after = AlarmPlan.objects.get(event=event)
        assert after.buffer_minutes == DEFAULT_MINUTES
        assert after.alarm_at == before.alarm_at + timedelta(minutes=15 - DEFAULT_MINUTES)
        assert (result.recomputed_plans, result.changed_plans) == (1, 1)
        assert len(route) == lookups  # 카카오를 다시 부르지 않는다
        latest = event.plan_revisions.order_by("-computed_at", "-id").first()
        assert latest.trigger == PlanRevision.Trigger.PROFILE

    def test_confirmed_alarm_moves_without_a_proposal(self, alice, place, route):
        """초기화는 사용자가 한 일이라 확인한 알람이 5분 넘게 바뀌어도 묻지 않고 바로 바꾼다(H3d)."""
        BufferAdjustment.objects.create(
            user=alice, previous_minutes=10, minutes=15, reason="late_more", sample_count=6,
            late_count=3, expected_late=0.6, mean_slack_minutes=-2.0, tightest_slack_minutes=-9.0,
        )
        start = (timezone.now() + timedelta(days=1)).replace(second=0, microsecond=0)
        event = Event.objects.create(user=alice, place=place, title="수업", start_at=start)
        services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
        before = AlarmPlan.objects.get(event=event)
        before.confirmed_alarm_at = before.alarm_at
        before.save(update_fields=["confirmed_alarm_at"])

        learning_reset.reset_learning(alice)

        after = AlarmPlan.objects.get(event=event)
        assert after.alarm_at == before.alarm_at + timedelta(minutes=15 - DEFAULT_MINUTES)
        assert after.proposal_kind == ""
        assert after.proposed_alarm_at is None

    def test_alarm_that_already_rang_is_left_alone(self, alice, place, route):
        BufferAdjustment.objects.create(
            user=alice, previous_minutes=10, minutes=15, reason="late_more", sample_count=6,
            late_count=3, expected_late=0.6, mean_slack_minutes=-2.0, tightest_slack_minutes=-9.0,
        )
        # 20분 뒤 시작. 준비 30분 + 이동 20분이라 알람은 이미 울렸다.
        start = (timezone.now() + timedelta(minutes=20)).replace(microsecond=0)
        event = Event.objects.create(user=alice, place=place, title="곧 수업", start_at=start)
        services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
        before = AlarmPlan.objects.get(event=event)
        assert before.alarm_at <= timezone.now()

        result = learning_reset.reset_learning(alice)

        after = AlarmPlan.objects.get(event=event)
        assert after.alarm_at == before.alarm_at
        assert after.buffer_minutes == 15
        assert result.recomputed_plans == 0

    def test_failed_recompute_keeps_the_records(self, alice, place, monkeypatch):
        seed_records(alice, place)

        def boom(*args, **kwargs):
            raise RuntimeError("재계산 실패")

        monkeypatch.setattr(refresh, "refresh_upcoming", boom)
        with pytest.raises(RuntimeError):
            learning_reset.reset_learning(alice)
        # 한 트랜잭션이라 지운 것도 되돌아간다.
        assert counts(alice) == {key: 1 for key in DELETED}


@pytest.mark.integration
class TestResetEndpoint:
    def _client(self, user) -> APIClient:
        client = APIClient()
        client.force_authenticate(user)
        return client

    def test_requires_login(self):
        assert APIClient().post(PATH).status_code == 401

    def test_returns_counts_and_leaves_others(self, alice, bob, place):
        seed_records(alice, place, "a")
        seed_records(bob, place, "b")

        res = self._client(alice).post(PATH)

        assert res.status_code == 200
        body = res.json()
        assert body["deleted"] == {key: 1 for key in DELETED}
        assert all(isinstance(v, int) for v in body["deleted"].values())
        assert body["recomputed_plans"] == 0
        assert body["changed_plans"] == 0
        assert body["reset_at"]
        assert counts(alice) == {key: 0 for key in DELETED}
        assert counts(bob) == {key: 1 for key in DELETED}

        again = self._client(alice).post(PATH)
        assert again.status_code == 200
        assert again.json()["deleted"] == {key: 0 for key in DELETED}

    def test_get_is_not_allowed(self, alice):
        # 앱은 405 를 "서버가 아직 지원하지 않는다" 로 읽는다. 잘못 부른 GET 만 405 다.
        assert self._client(alice).get(PATH).status_code == 405

    def test_throttle_five_per_hour(self, alice, monkeypatch):
        import importlib

        from rest_framework.throttling import ScopedRateThrottle

        base = importlib.import_module("config.settings.base")
        monkeypatch.setattr(
            ScopedRateThrottle, "THROTTLE_RATES", base.REST_FRAMEWORK["DEFAULT_THROTTLE_RATES"]
        )
        cache.clear()
        try:
            client = self._client(alice)
            statuses = [client.post(PATH).status_code for _ in range(6)]
        finally:
            cache.clear()
        assert statuses == [200] * 5 + [429]
