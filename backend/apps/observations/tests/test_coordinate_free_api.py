"""P3: derived GPS evidence reaches the server; measured coordinates never do."""

from datetime import timedelta

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.observations.models import TripObservation
from apps.observations.serializers import MAX_ACCURACY_M, MAX_DWELL_SECONDS

pytestmark = [pytest.mark.django_db, pytest.mark.integration]
BATCH_URL = "/api/observations/batch"
LIST_URL = "/api/observations"


@pytest.fixture
def account(django_user_model):
    return django_user_model.objects.create_user(
        email="coordinate-free@example.com", password="Sample-privacy-42", nickname="관측 테스트"
    )


@pytest.fixture
def client(account):
    client = APIClient()
    client.force_authenticate(user=account)
    return client


@pytest.fixture
def event(account):
    destination = Place.objects.create(name="검색해서 고른 장소", lat=37.5, lng=127.0)
    return Event.objects.create(
        user=account, place=destination, title="좌표 없는 이동",
        start_at=timezone.now().replace(microsecond=0),
    )


def item(event, uid="without-coordinates", *, kind="arrive", **overrides):
    observed = event.start_at - timedelta(minutes=10 if kind == "arrive" else 40)
    row = {
        "event": event.pk, "kind": kind, "detector": "gps",
        "observed_at": observed.isoformat(), "accuracy_m": 12.0,
        "distance_m": 18.0 if kind == "arrive" else 65.0, "client_uuid": uid,
    }
    if kind == "arrive":
        row.update(entered_at=observed.isoformat(), dwell_seconds=0)
    row.update(overrides)
    return row


def upload(client, row):
    return client.post(BATCH_URL, {"observations": [row]}, format="json")


@pytest.mark.parametrize("kind", ["depart", "arrive"])
@pytest.mark.parametrize("explicit_detector", [True, False])
def test_coordinate_free_gps_preserves_detector_and_evidence(client, event, kind, explicit_detector):
    row = item(event, kind=kind)
    if not explicit_detector:
        row.pop("detector")
    response = upload(client, row)
    assert response.status_code == 201, response.data
    saved = TripObservation.objects.get(client_uuid=row["client_uuid"])
    assert saved.detector == "gps"
    assert saved.lat is None and saved.lng is None
    assert saved.accuracy_m == row["accuracy_m"]
    assert saved.distance_m == row["distance_m"]
    assert saved.kind == kind
    if kind == "arrive":
        assert saved.entered_at == saved.observed_at
        assert saved.dwell_seconds == 0
    output = response.data["results"][0]
    assert output["detector"] == "gps"
    assert "lat" not in output and "lng" not in output


@pytest.mark.parametrize("detector", ["gps", "manual"])
def test_legacy_sensor_coordinates_are_accepted_but_never_saved_or_returned(client, event, detector):
    row = item(event, detector=detector, lat=37.4812345, lng=126.9512345)
    response = upload(client, row)
    assert response.status_code == 201, response.data
    saved = TripObservation.objects.get(client_uuid=row["client_uuid"])
    assert (saved.lat, saved.lng) == (None, None)
    assert saved.detector == detector
    assert response.data["accepted"] == 1
    assert "lat" not in response.data["results"][0]
    assert "lng" not in response.data["results"][0]
    rows = client.get(LIST_URL).data["results"]
    assert len(rows) == 1
    assert "lat" not in rows[0] and "lng" not in rows[0]


def test_old_coordinate_columns_are_hidden_even_before_historical_erasure(client, account, event):
    TripObservation.objects.create(
        user=account, event=event, kind="depart", detector="gps",
        observed_at=event.start_at - timedelta(minutes=40), client_uuid="old-row",
        lat=37.4812345, lng=126.9512345, accuracy_m=15, distance_m=70,
    )
    response = client.get(LIST_URL)
    assert response.status_code == 200
    assert response.data["count"] == 1
    assert "lat" not in response.data["results"][0]
    assert "lng" not in response.data["results"][0]


def test_legacy_replay_stays_idempotent_without_reintroducing_coordinates(client, event):
    row = item(event, lat=37.48, lng=126.95)
    assert upload(client, row).status_code == 201
    row.update(lat=37.49, lng=126.96, accuracy_m=20)
    response = upload(client, row)
    assert response.status_code == 200
    assert response.data["duplicated"] == 1
    saved = TripObservation.objects.get(client_uuid=row["client_uuid"])
    assert saved.accuracy_m == 12
    assert saved.lat is None and saved.lng is None


@pytest.mark.parametrize("field", ["accuracy_m", "distance_m"])
@pytest.mark.parametrize("missing", [True, False])
def test_coordinate_free_gps_still_requires_both_derived_values(client, event, field, missing):
    row = item(event)
    if missing:
        row.pop(field)
    else:
        row[field] = None
    response = upload(client, row)
    assert response.status_code == 400
    assert not TripObservation.objects.exists()


@pytest.mark.parametrize("field,value", [
    ("accuracy_m", -1), ("accuracy_m", MAX_ACCURACY_M + 1), ("distance_m", -1),
    ("accuracy_m", "NaN"), ("distance_m", "NaN"),
    ("accuracy_m", "Infinity"), ("distance_m", "Infinity"),
    ("distance_m", "-Infinity"),
])
def test_invalid_evidence_is_rejected_without_coordinates(client, event, field, value):
    response = upload(client, item(event, **{field: value}))
    assert response.status_code == 400, response.data
    assert not TripObservation.objects.exists()


def test_boundary_accuracy_and_legacy_coordinate_pair_validation_are_retained(client, event):
    response = upload(client, item(event, accuracy_m=MAX_ACCURACY_M))
    assert response.status_code == 201
    assert upload(client, item(event, "partial", lat=37.48)).status_code == 400
    assert upload(client, item(event, "invalid-lat", lat=91, lng=126)).status_code == 400


@pytest.mark.parametrize("case", ["depart-entered", "depart-dwell", "entered-future", "entered-old", "dwell-negative", "dwell-old"])
def test_entry_and_dwell_validation_remain_after_coordinate_removal(client, event, case):
    row = item(event, kind="depart" if case.startswith("depart") else "arrive")
    if case == "depart-entered":
        row["entered_at"] = row["observed_at"]
    elif case == "depart-dwell":
        row["dwell_seconds"] = 0
    elif case == "entered-future":
        row["entered_at"] = (event.start_at - timedelta(minutes=10) + timedelta(seconds=1)).isoformat()
    elif case == "entered-old":
        row["entered_at"] = (event.start_at - timedelta(minutes=10, seconds=MAX_DWELL_SECONDS + 1)).isoformat()
    elif case == "dwell-negative":
        row["dwell_seconds"] = -1
    else:
        row["dwell_seconds"] = MAX_DWELL_SECONDS + 1
    assert upload(client, row).status_code == 400
    assert not TripObservation.objects.exists()


def test_coordinate_free_upload_does_not_modify_selected_destination_or_home(client, account, event):
    profile = account.profile
    profile.home_lat, profile.home_lng, profile.home_label = 37.48, 126.93, "검색한 집"
    profile.save()
    assert upload(client, item(event)).status_code == 201
    profile.refresh_from_db()
    event.place.refresh_from_db()
    assert (profile.home_lat, profile.home_lng, profile.home_label) == (37.48, 126.93, "검색한 집")
    assert (event.place.lat, event.place.lng) == (37.5, 127.0)
