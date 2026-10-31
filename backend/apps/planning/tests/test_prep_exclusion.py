# Partly AI-generated (Claude)
"""사용자가 고른 아침을 준비 학습에서 빼기(`Event.prep_learning_excluded_at`).

표시한 아침은 GPS 준비 시간·블록 기록 모두 학습에서 빠지고, 표시를 떼면 다시 들어간다.
붙이고 떼는 것은 `manage.py exclude_prep_mornings` 다.
"""

from __future__ import annotations

from datetime import timedelta
from io import StringIO

import pytest
from django.core.management import CommandError, call_command
from django.utils import timezone

from apps.events.models import Event, Place
from apps.planning import prep_observations, services
from apps.planning.models import AlarmPlan, PlanRevision
from apps.prediction import learning
from apps.routines.models import BlockObservation, RoutineBlock

from .test_gps_prep import ROUTE, gps_morning, make_event, morning

pytestmark = pytest.mark.django_db

EMAIL = "exclude-prep@example.com"


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email=EMAIL, password="test12345", nickname="빼기"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.onboarding_prep_min = 55
    p.save()
    return u


@pytest.fixture
def place():
    return Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)


@pytest.fixture(autouse=True)
def stub_route(monkeypatch):
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(ROUTE), False))
    monkeypatch.setattr(
        services.clients, "resolve_route", lambda key, **kw: (dict(ROUTE, key=key), False)
    )


@pytest.fixture
def block(user):
    return RoutineBlock.objects.create(
        user=user, name="샤워", default_min_minutes=10, default_max_minutes=20, order=0
    )


def record(user, block, event, minutes: float, uid: str, *, parallel=False):
    at = morning(1) + timedelta(minutes=5)
    return BlockObservation.objects.create(
        user=user, block=block, event=event, observed_on=at.date(), duration_minutes=minutes,
        client_uuid=uid, client_recorded_at=at, was_parallel=parallel,
    )


def mark(event):
    Event.objects.filter(pk=event.pk).update(prep_learning_excluded_at=timezone.now())


def run(*args) -> str:
    out = StringIO()
    call_command("exclude_prep_mornings", "--email", EMAIL, *args, stdout=out)
    return out.getvalue()


def marked_ids(user) -> set[int]:
    return set(
        Event.objects.filter(user=user, prep_learning_excluded_at__isnull=False)
        .values_list("pk", flat=True)
    )


class TestLearningSkipsMarkedMornings:
    def test_gps_sample_of_a_marked_morning_is_dropped(self, user, place):
        usual = gps_morning(user, place, 1, 40)
        long = gps_morning(user, place, 2, 81)
        mark(long)

        assert prep_observations.gps_prep_samples(user.pk) == [40.0]
        assert prep_observations.gps_prep_by_event(user.pk) == [(usual.pk, 40.0)]
        # 빼기 명령은 이미 뺀 아침도 보여 줘야 한다.
        assert prep_observations.gps_prep_by_event(user.pk, include_marked=True) == [
            (long.pk, 81.0), (usual.pk, 40.0),
        ]

    def test_block_records_of_a_marked_morning_are_dropped(self, user, place, block):
        kept = record(user, block, make_event(user, place, 1), 15, "b1")
        marked = make_event(user, place, 2)
        record(user, block, marked, 18, "b2")
        mark(marked)

        rows = prep_observations.usable_block_observations(BlockObservation.objects.all())
        assert list(rows) == [kept]
        assert services._block_observation_stats(user.pk)[block.pk][2] == 1
        assert learning.block_stats(user=user)["샤워"]["n"] == 1

    def test_records_without_event_are_kept(self, user, block):
        record(user, block, None, 15, "b1")
        assert prep_observations.usable_block_observations(BlockObservation.objects.all()).count() == 1


class TestExcludeCommand:
    def test_dry_run_lists_targets_and_changes_nothing(self, user, place):
        gps_morning(user, place, 1, 40)
        gps_morning(user, place, 2, 95)

        out = run("--above", "80")

        assert "80분을 넘는 아침 1개" in out
        assert "GPS 95분" in out
        assert "--apply" in out
        assert marked_ids(user) == set()

    def test_apply_marks_only_mornings_over_the_line(self, user, place):
        gps_morning(user, place, 1, 40)
        exactly = gps_morning(user, place, 2, 80)
        over = gps_morning(user, place, 3, 80.5)

        run("--above", "80", "--apply")

        assert marked_ids(user) == {over.pk}
        assert exactly.pk not in marked_ids(user)
        assert prep_observations.gps_prep_samples(user.pk) == [80.0, 40.0]

    def test_block_total_over_the_line_also_counts(self, user, place, block):
        """GPS 출발이 안 잡힌 아침은 블록 기록 합으로 잰다. 병렬 블록은 더하지 않는다."""
        long = make_event(user, place, 1)
        record(user, block, long, 50, "b1")
        record(user, block, long, 35, "b2")
        parallel = make_event(user, place, 2)
        record(user, block, parallel, 50, "b3")
        record(user, block, parallel, 35, "b4", parallel=True)

        out = run("--above", "80", "--apply")

        assert "블록 합 85분" in out
        assert marked_ids(user) == {long.pk}

    def test_running_again_finds_nothing_new(self, user, place):
        gps_morning(user, place, 1, 95)
        run("--above", "80", "--apply")

        out = run("--above", "80", "--apply")

        assert "[이미 뺌]" in out
        assert "새로 뺄 아침이 없다" in out

    def test_other_users_are_untouched(self, user, place, django_user_model):
        other = django_user_model.objects.create_user(
            email="other@example.com", password="test12345", nickname="남"
        )
        theirs = gps_morning(other, place, 1, 120)
        gps_morning(user, place, 2, 95)

        run("--above", "80", "--apply")

        assert not Event.objects.get(pk=theirs.pk).prep_learning_excluded_at

    def test_undo_puts_them_back(self, user, place):
        gps_morning(user, place, 1, 40)
        gps_morning(user, place, 2, 95)
        run("--above", "80", "--apply")

        out = run("--undo")
        assert "뺀 아침 1개" in out
        assert len(marked_ids(user)) == 1

        run("--undo", "--apply")
        assert marked_ids(user) == set()
        assert prep_observations.gps_prep_samples(user.pk) == [95.0, 40.0]

    def test_apply_refreshes_upcoming_alarms(self, user, place, monkeypatch):
        calls = []
        monkeypatch.setattr(
            "apps.planning.refresh.refresh_upcoming",
            lambda u, **kw: calls.append((u.pk, kw["trigger"])) or {},
        )
        gps_morning(user, place, 1, 95)

        run("--above", "80")
        assert calls == []

        run("--above", "80", "--apply")
        assert calls == [(user.pk, PlanRevision.Trigger.LEARNING)]

    def test_alarm_moves_once_the_long_morning_is_out(self, user, place):
        """설정 55분에 82분 아침이 섞이면(1.5배 안) 준비가 늘어난다. 빼면 그만큼 늦게 울린다."""
        for day, minutes in [(1, 50), (2, 55), (3, 82)]:
            gps_morning(user, place, day, minutes)
        upcoming = Event.objects.create(
            user=user, place=place, title="수업", start_at=timezone.now() + timedelta(days=2)
        )
        services.compute_and_store(upcoming, trigger=PlanRevision.Trigger.CREATE)
        before = AlarmPlan.objects.get(event=upcoming).alarm_at

        run("--above", "80", "--apply")

        after = AlarmPlan.objects.get(event=upcoming).alarm_at
        assert after > before

    def test_needs_a_positive_line(self, user):
        with pytest.raises(CommandError):
            run()
        with pytest.raises(CommandError):
            run("--above", "0")

    def test_unknown_email(self, db):
        with pytest.raises(CommandError):
            call_command("exclude_prep_mornings", "--email", "nobody@example.com", "--above", "80")
