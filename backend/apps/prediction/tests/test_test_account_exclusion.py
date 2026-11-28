"""N26 regression: reserved QA accounts never reach production learning."""

from datetime import date, datetime, timedelta
from io import StringIO

import pytest
from django.core.management import call_command
from django.utils import timezone

from apps.common.cohort import (
    TEST_ACCOUNT_EMAIL_PREFIXES,
    eligible_user_ids,
    exclude_test_accounts,
    production_users,
)
from apps.observations.models import AlarmLog, TripObservation
from apps.planning import buffers, prep_observations
from apps.prediction import learning
from apps.prediction.models import ModelArtifact
from apps.prediction.tests.test_learning import KST, add_block_obs, make_block, make_trip

pytestmark = pytest.mark.django_db


def make_user(django_user_model, email, **extra):
    return django_user_model.objects.create_user(
        email=email, password="test-password", nickname="Reserved QA Name", **extra
    )


@pytest.mark.parametrize("prefix", TEST_ACCOUNT_EMAIL_PREFIXES)
def test_reserved_prefixes_are_excluded_case_insensitively(django_user_model, prefix):
    account = make_user(django_user_model, f"{prefix.upper()}sample@example.com")
    assert not production_users().filter(pk=account.pk).exists()
    assert account.pk not in eligible_user_ids()


@pytest.mark.parametrize("email", [
    "qauser@example.com", "alice.depcheck_unit@example.com", "qa@example.com",
    "demo@demo.com", "synthetic@example.com", "normal@qa-ui-example.com",
])
def test_real_users_are_not_excluded_by_loose_matching(django_user_model, email):
    account = make_user(django_user_model, email, is_staff=True)
    assert production_users().filter(pk=account.pk).exists()


@pytest.fixture
def accounts(django_user_model):
    return (
        make_user(django_user_model, "participant@example.com"),
        make_user(django_user_model, "qa-ui-automated@example.com"),
    )


@pytest.fixture
def place():
    from apps.events.models import Place
    return Place.objects.create(name="Fixture Place", lat=37.46, lng=126.95)


def seed_blocks(account, count, *, minutes):
    block = make_block(account, "morning", lo=5, hi=40)
    for index in range(count):
        add_block_obs(
            account, block, date(2026, 11, 1) + timedelta(days=index), minutes,
            slack=float(index), uid=f"n26-{account.pk}-{index}",
        )


def test_all_block_learning_views_exclude_qa_even_when_scoped(accounts):
    real, qa = accounts
    seed_blocks(real, 2, minutes=10)
    seed_blocks(qa, 16, minutes=30)
    assert learning.block_stats()["morning"] == {"mean": 10.0, "sd": 0.0, "n": 2}
    assert learning.prep_total_stats() == {"prep_mean": 10.0, "prep_sd": 0.0, "n": 2}
    assert learning.observation_count() == 2
    assert learning.estimate_slack_coef() is None
    assert learning.block_stats(user=qa) == {}
    assert learning.prep_total_stats(user=qa)["n"] == 0
    assert learning.observation_count(user=qa) == 0
    assert learning.estimate_slack_coef(user=qa) is None
    assert learning.build_artifact()["blocks"]["morning"]["n"] == 2


def test_trip_learning_and_record_helper_exclude_qa(accounts, place):
    real, qa = accounts
    for account in accounts:
        make_trip(
            account, place, predicted=20, actual_minutes=24,
            depart_at=datetime(2026, 11, 20, 8, tzinfo=KST),
        )
    rows = exclude_test_accounts(TripObservation.objects.all())
    assert set(rows.values_list("user_id", flat=True)) == {real.pk}
    samples, _ = learning.collect_travel_samples()
    assert [sample.user_id for sample in samples] == [real.pk]
    assert learning.collect_travel_samples(user=qa)[0] == []


def test_runtime_gps_preparation_learning_excludes_qa(accounts, place):
    for account in accounts:
        trip = make_trip(
            account, place, predicted=20, actual_minutes=24,
            depart_at=datetime(2026, 11, 20, 8, tzinfo=KST),
        )
        AlarmLog.objects.create(
            user=account, event=trip, kind="dismissed",
            occurred_at=datetime(2026, 11, 20, 7, 30, tzinfo=KST),
            client_uuid=f"dismiss-{account.pk}",
        )
    assert prep_observations.gps_prep_samples(accounts[0].pk) == [30.0]
    assert prep_observations.gps_prep_samples(accounts[1].pk) == []
    # The explicit diagnostic selector can still inspect QA records locally.
    assert len(prep_observations.gps_prep_by_event(accounts[1].pk)) == 1


def test_personal_and_global_nightly_training_exclude_qa(accounts):
    real, qa = accounts
    seed_blocks(real, 15, minutes=10)
    seed_blocks(qa, 20, minutes=30)
    output = StringIO()
    call_command("train_models", "--users", stdout=output)
    assert not ModelArtifact.objects.filter(user=qa).exists()
    assert ModelArtifact.objects.get(user=real, is_active=True).sample_count == 15
    assert ModelArtifact.objects.get(user=None, is_active=True).sample_count == 15
    assert qa.email not in output.getvalue()


def test_nightly_buffer_learning_skips_qa(accounts, place, monkeypatch):
    real, _ = accounts
    for account in accounts:
        make_trip(
            account, place, predicted=20, actual_minutes=24,
            depart_at=timezone.now() - timedelta(days=1),
        )
    evaluated = []

    def evaluate(account):
        evaluated.append(account.pk)
        return buffers.Decision(5, 5, buffers.KEEP_FEW, 0)

    monkeypatch.setattr(buffers, "evaluate", evaluate)
    assert sum(buffers.adjust_all(dry_run=True).values()) == 1
    assert evaluated == [real.pk]


def test_cleanup_script_shares_the_same_reserved_prefixes():
    from scripts.purge_test_accounts import DEFAULT_PREFIXES
    assert DEFAULT_PREFIXES == TEST_ACCOUNT_EMAIL_PREFIXES


def test_full_retrain_deactivates_legacy_qa_and_empty_global_artifacts(accounts):
    from apps.routing.models import RouteCorrection, UserTravelProfile

    real, qa = accounts
    seed_blocks(qa, 20, minutes=30)
    old_global = ModelArtifact.objects.create(version="legacy-global", sample_count=20)
    old_qa = ModelArtifact.objects.create(user=qa, version="legacy-qa", sample_count=20)
    old_real = ModelArtifact.objects.create(user=real, version="legacy-real", sample_count=20)
    learned = RouteCorrection.objects.create(
        route_signature="legacy-qa-route", factor=1.4, sample_count=20
    )
    manual = RouteCorrection.objects.create(
        route_signature="manual-route", factor=1.1, sample_count=0
    )
    UserTravelProfile.objects.create(user=qa, walk_ratio=1.4, walk_samples=20)

    call_command("train_models", "--users", stdout=StringIO())

    old_global.refresh_from_db()
    old_qa.refresh_from_db()
    old_real.refresh_from_db()
    assert not old_global.is_active
    assert not old_qa.is_active
    assert old_real.is_active  # A real user's historical personal data is preserved.
    assert ModelArtifact.objects.count() == 3
    assert not RouteCorrection.objects.filter(pk=learned.pk).exists()
    assert RouteCorrection.objects.filter(pk=manual.pk).exists()
    assert not UserTravelProfile.objects.filter(user=qa).exists()


def test_dry_run_leaves_legacy_artifacts_and_corrections_untouched(accounts):
    from apps.routing.models import RouteCorrection, UserTravelProfile

    _, qa = accounts
    global_artifact = ModelArtifact.objects.create(version="legacy-global", sample_count=20)
    qa_artifact = ModelArtifact.objects.create(user=qa, version="legacy-qa", sample_count=20)
    correction = RouteCorrection.objects.create(
        route_signature="legacy", factor=1.4, sample_count=20
    )
    profile = UserTravelProfile.objects.create(user=qa, walk_ratio=1.4, walk_samples=20)
    call_command("train_models", "--users", "--dry-run", stdout=StringIO())
    assert ModelArtifact.objects.filter(pk=global_artifact.pk, is_active=True).exists()
    assert ModelArtifact.objects.filter(pk=qa_artifact.pk, is_active=True).exists()
    assert RouteCorrection.objects.filter(pk=correction.pk).exists()
    assert UserTravelProfile.objects.filter(pk=profile.pk).exists()


def test_legacy_qa_personal_runtime_corrections_are_ignored(accounts):
    from apps.planning.models import BufferAdjustment
    from apps.routing.models import UserTravelProfile, walk_factor_for

    real, qa = accounts
    for account in accounts:
        UserTravelProfile.objects.create(user=account, walk_ratio=1.4, walk_samples=20)
        BufferAdjustment.objects.create(
            user=account, previous_minutes=5, minutes=10, reason="late_more",
            sample_count=5, late_count=3, expected_late=0.5,
            mean_slack_minutes=-5, tightest_slack_minutes=-10,
        )
    assert walk_factor_for(qa.pk) == 1.0
    assert buffers.current(qa.pk).minutes == buffers.DEFAULT_MINUTES
    assert walk_factor_for(real.pk) > 1.0
    assert buffers.current(real.pk).minutes == 10
    assert UserTravelProfile.objects.count() == 2  # No runtime data deletion.
    assert BufferAdjustment.objects.count() == 2
