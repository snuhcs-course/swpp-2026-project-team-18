"""학습은 사용자가 따른 계획의 경로 원값을 사용한다."""

from datetime import datetime, timedelta

import pytest

from apps.observations.models import AlarmLog
from apps.planning import history
from apps.prediction import learning
from apps.prediction.tests.test_learning import KST, make_trip, place, user
from apps.routing.models import RouteCorrection

pytestmark = pytest.mark.django_db
DEPARTED = datetime(2026, 9, 21, 8, 0, tzinfo=KST)


@pytest.fixture
def trip(user, place):
    event = make_trip(user, place, predicted=20, actual_minutes=24, depart_at=DEPARTED)
    event.plan_revisions.all().delete()
    return event


def snapshot(event, raw, at, corrected=30, key="transit:2호선"):
    plan = event.alarm_plan
    plan.travel_minutes, plan.route_key = corrected, key
    plan.save(update_fields=["travel_minutes", "route_key"])
    plan.computed_at = at
    return history.record_revision(event, plan, route_minutes_raw=raw)


def test_training_uses_raw_minutes_instead_of_corrected_plan(trip):
    snapshot(trip, 20, DEPARTED - timedelta(minutes=30))

    samples, _ = learning.collect_travel_samples()

    assert len(samples) == 1
    assert samples[0].predicted_minutes == 20
    assert samples[0].ratio == pytest.approx(1.2)


@pytest.mark.parametrize("fired, expected", [(False, 40), (True, 20)])
def test_training_keeps_the_decision_before_alarm_or_departure(trip, user, fired, expected):
    snapshot(trip, 20, DEPARTED - timedelta(minutes=30))
    if fired:
        AlarmLog.objects.create(user=user, event=trip, kind="fired",
                                occurred_at=DEPARTED - timedelta(minutes=10), client_uuid="fired")
    snapshot(trip, 40, DEPARTED - timedelta(minutes=5), corrected=45, key="transit:old")
    snapshot(trip, 80, DEPARTED + timedelta(hours=1), corrected=99, key="transit:changed")

    samples, _ = learning.collect_travel_samples()

    assert len(samples) == 1
    assert samples[0].predicted_minutes == expected
    assert samples[0].signature == ("transit:2호선" if fired else "transit:old")


@pytest.mark.parametrize("has_revision", [False, True])
def test_training_skips_missing_raw_history_without_fallback(trip, has_revision):
    if has_revision:
        snapshot(trip, None, DEPARTED - timedelta(minutes=30))

    samples, dropped = learning.collect_travel_samples()

    assert samples == []
    assert dropped["경로 원값 없음"] == 1


@pytest.mark.parametrize("valid_samples", [0, 2])
def test_full_training_removes_obsolete_learned_buckets_but_keeps_manual(user, place, valid_samples):
    stale = RouteCorrection.objects.create(route_signature="transit:2호선", mode="transit",
        hour_bucket=8, weekday_type="weekday", factor=2, sample_count=3)
    manual = RouteCorrection.objects.create(route_signature="manual", factor=1.1, sample_count=0)
    for day in range(valid_samples):
        make_trip(user, place, predicted=20, actual_minutes=24,
                  depart_at=DEPARTED + timedelta(days=day))

    result = learning.update_route_corrections()

    assert not RouteCorrection.objects.filter(pk=stale.pk).exists()
    assert RouteCorrection.objects.filter(pk=manual.pk).exists()
    assert result["removed"] == 1


def test_user_scoped_training_does_not_remove_existing_shared_corrections(user):
    shared = RouteCorrection.objects.create(route_signature="shared", factor=1.2, sample_count=10)

    learning.update_route_corrections(user=user)

    assert RouteCorrection.objects.filter(pk=shared.pk).exists()
