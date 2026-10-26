# Partly AI-generated (Claude)
"""직접 누른 도착 판정(`detector=manual`). GPS 가 먼저고 수동은 보조다.

- 업로드: 수동은 위치가 없어도, 오차가 커도 받는다. GPS 규칙은 그대로다
- 리포트 채점: GPS 도착이 있으면 GPS, 없을 때만 믿을 만한 수동을 쓴다
- 이동 시간 학습: 수동은 쓰지 않는다
"""

from __future__ import annotations

from datetime import timedelta

import pytest
from django.db import IntegrityError, transaction
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.observations import selection
from apps.observations.models import TripObservation
from apps.prediction import learning
from apps.reports import services as reports

BATCH_URL = "/api/observations/batch"


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="manual@example.com", password="Wq3-golden-canyon-62", nickname="수동"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.save()
    return u


@pytest.fixture
def start():
    """이미 지난 일정 시작. 리포트는 지난 일정만 채점한다."""
    return (timezone.now() - timedelta(days=1)).replace(microsecond=0)


@pytest.fixture
def event(user, start):
    place = Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)
    return Event.objects.create(user=user, place=place, title="수업", start_at=start)


def make_obs(user, event, at, uid, *, kind="arrive", detector="gps", **kw):
    location = {"lat": 37.46, "lng": 126.95, "accuracy_m": 10.0, "distance_m": 30.0}
    if detector == "manual":
        location = {}
    location.update(kw)
    return TripObservation.objects.create(
        user=user, event=event, kind=kind, detector=detector, observed_at=at,
        client_uuid=uid, **location,
    )


def arrival_of(user, event):
    [outcome] = [
        o for o in reports.collect_outcomes(
            user, since=event.start_at - timedelta(hours=1), until=event.start_at
        )
        if o.event.pk == event.pk
    ]
    return outcome.arrive_at


@pytest.mark.integration
class TestUpload:
    @pytest.fixture
    def client(self, user):
        c = APIClient()
        c.force_authenticate(user=user)
        return c

    def body(self, event, uid, **kw):
        item = {
            "event": event.pk, "kind": "arrive", "detector": "manual",
            "observed_at": (event.start_at - timedelta(minutes=5)).isoformat(),
            "client_uuid": uid,
        }
        item.update(kw)
        return {"observations": [item]}

    def test_manual_without_location_is_accepted(self, client, event):
        res = client.post(BATCH_URL, self.body(event, "m1"), format="json")
        assert res.status_code == 201, res.data
        obs = TripObservation.objects.get(client_uuid="m1")
        assert obs.detector == "manual"
        assert obs.lat is None and obs.accuracy_m is None
        assert res.data["results"][0]["lat"] is None

    def test_manual_with_blurry_fix_is_accepted(self, client, event):
        """실내라 오차가 커도 받는다. 거절하면 앱 큐가 배치를 통째로 버린다."""
        res = client.post(
            BATCH_URL,
            self.body(event, "m1", lat=37.46, lng=126.95, accuracy_m=400.0, distance_m=80.0),
            format="json",
        )
        assert res.status_code == 201, res.data

    def test_manual_needs_both_coordinates(self, client, event):
        res = client.post(BATCH_URL, self.body(event, "m1", lat=37.46), format="json")
        assert res.status_code == 400

    def test_gps_still_needs_location(self, client, event):
        res = client.post(BATCH_URL, self.body(event, "g1", detector="gps"), format="json")
        assert res.status_code == 400
        assert TripObservation.objects.count() == 0

    def test_gps_still_rejects_blurry_fix(self, client, event):
        res = client.post(
            BATCH_URL,
            self.body(
                event, "g1", detector="gps",
                lat=37.46, lng=126.95, accuracy_m=80.0, distance_m=30.0,
            ),
            format="json",
        )
        assert res.status_code == 400

    def test_missing_detector_means_gps(self, client, event):
        body = self.body(event, "g1")
        del body["observations"][0]["detector"]
        res = client.post(BATCH_URL, body, format="json")
        assert res.status_code == 400


@pytest.mark.django_db
class TestDatabaseRules:
    def test_gps_row_without_location_is_rejected(self, user, event):
        with pytest.raises(IntegrityError), transaction.atomic():
            TripObservation.objects.create(
                user=user, event=event, kind="arrive", detector="gps",
                observed_at=event.start_at, client_uuid="g1",
            )


@pytest.mark.django_db
class TestRepresentative:
    def test_gps_wins_even_if_manual_is_earlier(self, user, event, start):
        make_obs(user, event, start - timedelta(minutes=20), "m1", detector="manual")
        gps = make_obs(user, event, start - timedelta(minutes=10), "g1")
        assert arrival_of(user, event) == gps.observed_at

    def test_manual_is_used_when_gps_missed(self, user, event, start):
        manual = make_obs(user, event, start - timedelta(minutes=5), "m1", detector="manual")
        assert arrival_of(user, event) == manual.observed_at

    def test_late_manual_press_is_ignored(self, user, event, start):
        """추적이 끝난 뒤 생각나서 누른 것은 도착 시각이 아니다."""
        make_obs(user, event, start + timedelta(hours=2), "m1", detector="manual")
        assert arrival_of(user, event) is None

    def test_manual_up_to_an_hour_late_counts_as_late_arrival(self, user, event, start):
        manual = make_obs(user, event, start + timedelta(minutes=30), "m1", detector="manual")
        assert arrival_of(user, event) == manual.observed_at

    def test_future_clock_is_ignored(self, user, event):
        obs = make_obs(user, event, timezone.now() + timedelta(hours=1), "m1", detector="manual")
        event.start_at = timezone.now() + timedelta(hours=1)
        assert not selection.usable_manual(obs, event)

    def test_kinds_are_picked_separately(self, user, event, start):
        depart = make_obs(user, event, start - timedelta(minutes=40), "g0", kind="depart")
        manual = make_obs(user, event, start - timedelta(minutes=5), "m1", detector="manual")
        observations = list(event.trip_observations.all())
        assert selection.representative(observations, "depart", event) == depart
        assert selection.representative(observations, "arrive", event) == manual


@pytest.mark.django_db
class TestLearning:
    def test_manual_arrival_is_not_a_travel_sample(self, user, event, start):
        make_obs(user, event, start - timedelta(minutes=40), "g0", kind="depart")
        make_obs(user, event, start - timedelta(minutes=5), "m1", detector="manual")

        samples, dropped = learning.collect_travel_samples(user=user)

        assert samples == []
        assert dropped["수동 판정"] == 1
        assert dropped["쌍 미완성"] == 1

    def test_gps_pair_is_still_learned_next_to_a_manual_press(self, user, event, start):
        make_obs(user, event, start - timedelta(minutes=40), "g0", kind="depart")
        make_obs(user, event, start - timedelta(minutes=20), "m1", detector="manual")
        make_obs(user, event, start - timedelta(minutes=10), "g1")

        _samples, dropped = learning.collect_travel_samples(user=user)

        assert dropped["수동 판정"] == 1
        assert dropped.get("쌍 미완성", 0) == 0
