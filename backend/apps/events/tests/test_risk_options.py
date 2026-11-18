"""Risk choices use the planning model and stored raw route, without upstream calls."""

from datetime import datetime, timedelta, timezone
from unittest.mock import Mock

import pytest
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.planning import services
from apps.planning.models import PlanRevision
from apps.routing.models import RouteCorrection
from apps.routines.models import RoutineBlock

pytestmark = [pytest.mark.django_db, pytest.mark.integration]
KST = timezone(timedelta(hours=9))
ROUTE = {"minutes": 20, "mode": "transit", "key": "transit:test", "source": "kakao"}


@pytest.fixture
def context(django_user_model, monkeypatch):
    user = django_user_model.objects.create_user(email="risk@test.com", password="test12345")
    profile = user.profile
    profile.home_lat, profile.home_lng = 37.48, 126.92
    profile.save()
    place = Place.objects.create(name="학교", lat=37.46, lng=126.95)
    event = Event.objects.create(
        user=user, place=place, title="수업",
        start_at=datetime(2026, 10, 19, 9, tzinfo=KST),
    )
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(ROUTE), False))
    monkeypatch.setattr(services.clients, "resolve_route", lambda *a, **kw: (dict(ROUTE), False))
    client = APIClient()
    client.force_authenticate(user)
    return client, event


def get_options(client, event):
    response = client.get(f"/api/events/{event.pk}/risk-options")
    assert response.status_code == 200
    return response.json()


def add_variance(event, *, travel=True):
    RoutineBlock.objects.create(
        user=event.user, name="샤워", default_min_minutes=10, default_max_minutes=30,
    )
    if travel:
        RouteCorrection.objects.create(
            route_signature=ROUTE["key"], mode="transit", factor=1.5,
            sd_minutes=8, sample_count=20,
        )


def test_owned_and_authenticated(context, django_user_model):
    client, event = context
    client.force_authenticate(django_user_model.objects.create_user(email="other@test.com"))
    assert client.get(f"/api/events/{event.pk}/risk-options").status_code == 404
    client.force_authenticate(None)
    assert client.get(f"/api/events/{event.pk}/risk-options").status_code == 401


def test_missing_plan_and_raw_snapshot_are_explicit(context):
    client, event = context
    data = get_options(client, event)
    assert data["options"] == []
    # 앱이 그대로 보여 주는 문장이라 해요체다(BE-R1).
    assert data["unavailable_reason"] == "알람이 아직 계산되지 않아 고를 수 있는 알람 시각을 보여 드릴 수 없어요."
    services.compute_and_store(event)
    PlanRevision.objects.filter(event=event).update(route_minutes_raw=None)
    data = get_options(client, event)
    assert data["options"] == []
    # 시트에는 [다시 시도] 만 있다. 할 수 없는 "다시 계산" 을 시키지 않는다(BE-R1 T-4).
    assert data["unavailable_reason"] == "저장된 경로 정보가 없어 고를 수 있는 알람 시각을 아직 계산할 수 없어요."


def test_no_variability_is_empty_and_never_fetches_route(context, monkeypatch):
    client, event = context
    services.compute_and_store(event)
    upstream = Mock(side_effect=AssertionError("risk GET must not fetch routes"))
    monkeypatch.setattr(services.clients, "best_route", upstream)
    monkeypatch.setattr(services.clients, "resolve_route", upstream)
    data = get_options(client, event)
    assert data["options"] == []
    assert data["distribution"] is None
    assert data["unavailable_reason"] == (
        "걸리는 시간이 얼마나 달라지는지 아직 몰라 고를 수 있는 알람 시각을 계산할 수 없어요."
    )
    # 위험 단계를 "여유" 로 부르지 않는다 — 화면의 "여유 시간" 은 버퍼다(BE-R1 T-4).
    assert "여유" not in data["unavailable_reason"]
    upstream.assert_not_called()


def test_model_options_order_and_patch_parity(context, monkeypatch):
    client, event = context
    add_variance(event)
    plan = services.compute_and_store(event)
    revisions_before = PlanRevision.objects.filter(event=event).count()
    with monkeypatch.context() as no_network:
        upstream = Mock(side_effect=AssertionError("no upstream"))
        no_network.setattr(services.clients, "best_route", upstream)
        no_network.setattr(services.clients, "resolve_route", upstream)
        data = get_options(client, event)
        upstream.assert_not_called()
    options = data["options"]
    assert [(o["key"], o["tau"]) for o in options] == [
        ("safe", .97), ("normal", .90), ("gamble", .65),
    ]
    assert options[0]["alarm_at"] < options[1]["alarm_at"] < options[2]["alarm_at"]
    assert options[0]["probability"] >= options[1]["probability"] >= options[2]["probability"]
    assert data["selected_key"] == "normal"
    normal = options[1]
    assert datetime.fromisoformat(normal["alarm_at"]) == plan.alarm_at
    assert normal["probability"] == plan.on_time_probability
    assert PlanRevision.objects.filter(event=event).count() == revisions_before
    dist = data["distribution"]
    assert dist["source"] == "model" and dist["window_days"] is None
    assert dist["p50"] < dist["p90"]
    assert sum(b["mass"] for b in dist["bins"]) == pytest.approx(1, abs=.002)
    assert all("count" not in b for b in dist["bins"])
    saved = client.patch(f"/api/events/{event.pk}", {"tau_override": .97}, format="json")
    assert saved.status_code == 200
    assert datetime.fromisoformat(saved.json()["alarm_plan"]["alarm_at"]) == datetime.fromisoformat(options[0]["alarm_at"])
    assert get_options(client, event)["selected_key"] == "safe"


# Partly AI-generated (Claude)
def test_options_use_the_users_learned_buffer(context):
    """지각 이력으로 조정한 버퍼(`planning.buffers`)를 실제 알람과 똑같이 쓴다."""
    from apps.planning.models import BufferAdjustment

    client, event = context
    add_variance(event)
    services.compute_and_store(event)
    before = {o["key"]: datetime.fromisoformat(o["alarm_at"]) for o in get_options(client, event)["options"]}
    BufferAdjustment.objects.create(
        user=event.user, previous_minutes=5, minutes=9, reason="late_more", sample_count=6,
        late_count=3, expected_late=.3, mean_slack_minutes=4.3, tightest_slack_minutes=-6,
    )
    plan = services.compute_and_store(event)

    after = {o["key"]: datetime.fromisoformat(o["alarm_at"]) for o in get_options(client, event)["options"]}

    assert {key: before[key] - after[key] for key in before} == dict.fromkeys(before, timedelta(minutes=4))
    assert after["normal"] == plan.alarm_at


def test_custom_tau_is_unselected_and_metadata_tracks_actual_default(context):
    client, event = context
    add_variance(event)
    from apps.events.models import EventTag
    tag = EventTag.objects.create(key="risk-exam", label="시험", default_tau=.99)
    event.tag = tag
    event.save()
    services.compute_and_store(event)
    data = get_options(client, event)
    assert data["selected_key"] is None
    assert data["auto_adjusted"] is True
    assert "시험" in data["auto_reason"] and "0.99" in data["auto_reason"]
    event.tau_override = .99
    event.save()
    data = get_options(client, event)
    assert data["selected_key"] is None
    assert data["auto_adjusted"] is False and data["auto_reason"] is None


def wheel_estimate(distribution, available):
    """앱 `AlarmTimeChoice.estimate` 와 같은 식: 구간 안은 균등으로 본 누적 확률(%)."""
    bins = [b for b in distribution["bins"] if b["mass"] > 0 and b["max"] > b["min"]]
    total = sum(b["mass"] for b in bins)
    covered = sum(
        b["mass"] * min(1, max(0, (available - b["min"]) / (b["max"] - b["min"]))) for b in bins
    )
    return round(covered / total * 100)


# Partly AI-generated (Claude) — PM 피드백(iter4): 빠르게 고르기에 도착 확률이 안 뜬다.
def test_partial_variance_still_shows_option_probabilities(context):
    client, event = context
    add_variance(event, travel=False)  # 준비만 흔들린다(이동 기록 없는 새 사용자)
    plan = services.compute_and_store(event)
    assert plan.on_time_probability is None  # 계획 확률은 그대로 비운다
    data = get_options(client, event)
    options = data["options"]
    assert len(options) == 3
    probs = [o["probability"] for o in options]
    assert all(isinstance(p, int) and 0 <= p <= 100 for p in probs)
    assert probs[0] >= probs[1] >= probs[2]
    # 휠로 같은 시각을 고르면 앱이 분포로 읽는 값과 거의 같아야 한다(구간 균등 가정만큼 차이).
    for option in options:
        available = (event.start_at - datetime.fromisoformat(option["alarm_at"])).total_seconds() / 60
        assert abs(option["probability"] - wheel_estimate(data["distribution"], available)) <= 5


def test_full_variance_option_probability_is_the_plan_math(context):
    client, event = context
    add_variance(event)
    plan = services.compute_and_store(event)
    normal = get_options(client, event)["options"][1]
    assert plan.on_time_probability is not None
    assert normal["probability"] == plan.on_time_probability


def test_unmatched_latest_revision_does_not_reuse_an_old_raw_route(context):
    client, event = context
    add_variance(event)
    services.compute_and_store(event)
    PlanRevision.objects.filter(event=event).update(event_start_at=event.start_at - timedelta(hours=1))
    data = get_options(client, event)
    assert data["options"] == [] and data["unavailable_reason"]


def test_explicit_origin_uses_travel_model_without_home_preparation(context):
    client, event = context
    event.origin_lat, event.origin_lng = 37.49, 126.93
    event.save()
    add_variance(event)
    plan = services.compute_and_store(event)
    data = get_options(client, event)
    normal = next(option for option in data["options"] if option["key"] == "normal")
    assert plan.prep_minutes == 0
    assert datetime.fromisoformat(normal["alarm_at"]) == plan.alarm_at
    assert normal["probability"] == plan.on_time_probability


def test_selecting_same_automatic_route_keeps_risk_snapshot_available(context):
    client, event = context
    add_variance(event)
    plan = services.compute_and_store(event)
    assert len(get_options(client, event)["options"]) == 3

    selected = client.patch(
        f"/api/events/{event.pk}", {"route_key": ROUTE["key"]}, format="json",
    )
    assert selected.status_code == 200
    assert datetime.fromisoformat(selected.json()["alarm_plan"]["alarm_at"]) == plan.alarm_at
    assert len(get_options(client, event)["options"]) == 3
    latest = event.plan_revisions.first()
    assert latest.requested_route_key == ROUTE["key"]
    assert event.plan_revisions.count() == 2

    # Repeated identical recomputations must neither lose that snapshot nor add rows.
    assert client.post(f"/api/events/{event.pk}/recompute").status_code == 200
    assert len(get_options(client, event)["options"]) == 3
    assert event.plan_revisions.count() == 2


def test_raw_route_change_refreshes_preview_even_if_rounded_decision_is_identical(context, monkeypatch):
    client, event = context
    add_variance(event)
    # At this factor, raw 20 -> 21 changes the modeled mean by .25 minute while
    # the current tau's allocated preparation/travel minutes round identically.
    RouteCorrection.objects.filter(route_signature=ROUTE["key"]).update(factor=.25)
    route = dict(ROUTE)
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(route), False))
    old_plan = services.compute_and_store(event)
    decision_fields = [field for field in PlanRevision.DECISION_FIELDS if field != "event_start_at"]
    old_decision = [getattr(old_plan, field) for field in decision_fields]
    old_distribution = get_options(client, event)["distribution"]

    route["minutes"] = 21
    new_plan = services.compute_and_store(event)
    assert [getattr(new_plan, field) for field in decision_fields] == old_decision
    new_distribution = get_options(client, event)["distribution"]
    assert new_distribution != old_distribution
    assert event.plan_revisions.first().route_minutes_raw == 21
    assert event.plan_revisions.count() == 2

