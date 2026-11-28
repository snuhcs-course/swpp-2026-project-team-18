"""S-4 version provenance uses existing headers, preserves retries, and hides auth bodies."""

from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.accounts import social
from apps.accounts.models import User
from apps.common import retention
from apps.common.models import ApiRequestLog
from apps.events import recurrence
from apps.events.models import Event
from apps.observations.models import AlarmLog, TripObservation
from apps.planning import services

pytestmark = pytest.mark.django_db
PASSWORD = "Jb6-marble-thicket-84"
KST = ZoneInfo("Asia/Seoul")
START = datetime(2027, 3, 2, 9, tzinfo=KST)


@pytest.fixture
def user():
    return User.objects.create_user(
        email="version-member@example.com", nickname="member", password=PASSWORD,
    )


@pytest.fixture
def client(user, monkeypatch):
    client = APIClient()
    client.force_authenticate(user)
    monkeypatch.setattr(services, "compute_and_store", lambda *args, **kwargs: None)
    return client


@pytest.fixture
def logging_on(settings, monkeypatch):
    settings.API_REQUEST_LOG_ENABLED = True
    monkeypatch.setattr(retention, "_last_purged_on", timezone.localdate())


def test_internal_records_have_unknown_version(user):
    event = Event.objects.create(user=user, title="internal", start_at=START)
    alarm = AlarmLog.objects.create(
        user=user, event=event, kind="fired", occurred_at=START, client_uuid="legacy-alarm",
    )
    trip = TripObservation.objects.create(
        user=user, event=event, kind="depart", detector="manual",
        observed_at=START, client_uuid="legacy-trip",
    )
    assert user.registration_app_version == ""
    assert event.creation_app_version == alarm.app_version == trip.app_version == ""


@pytest.mark.integration
def test_signup_stamps_version_and_links_metadata_without_auth_bodies(logging_on):
    response = APIClient().post(
        "/api/auth/register",
        {
            "email": "qa-ui-version@example.com", "nickname": "qa",
            "password": PASSWORD, "password_confirm": PASSWORD, "terms_agreed": True,
            "registration_app_version": "forged-body-value",
        },
        format="json", HTTP_X_APP_VERSION="0.22.0<script>",
    )
    assert response.status_code == 201
    created = User.objects.get(pk=response.data["user_id"])
    assert created.registration_app_version == "0.22.0script"
    row = ApiRequestLog.objects.get()
    assert row.user_id == created.pk
    assert row.app_version == created.registration_app_version
    assert (row.query, row.request_body, row.response_body) == ({}, {}, {})
    assert "registration_app_version" not in response.data["user"]


@pytest.mark.integration
def test_login_links_user_without_rewriting_signup_version(user, logging_on):
    client = APIClient()
    response = client.post(
        "/api/auth/token", {"email": user.email, "password": PASSWORD},
        format="json", HTTP_X_APP_VERSION="0.23.0",
    )
    assert response.status_code == 200
    row = ApiRequestLog.objects.get()
    assert (row.user_id, row.app_version) == (user.pk, "0.23.0")
    assert (row.query, row.request_body, row.response_body) == ({}, {}, {})
    user.refresh_from_db()
    assert user.registration_app_version == ""


@pytest.mark.integration
def test_failed_auth_does_not_trust_a_client_user_id(user, logging_on):
    response = APIClient().post(
        "/api/auth/token", {"email": user.email, "password": "wrong"},
        format="json", HTTP_API_REQUEST_LOG_USER_ID=str(user.pk),
    )
    assert response.status_code == 401
    row = ApiRequestLog.objects.get()
    assert row.user_id is None
    assert (row.query, row.request_body, row.response_body) == ({}, {}, {})


@pytest.mark.integration
def test_social_signup_preserves_first_version_and_links_login(monkeypatch, logging_on):
    identity = social.Identity(
        provider=social.PROVIDERS["kakao"], subject="version-subject",
        email="version-social@example.com", nickname="social",
    )
    monkeypatch.setattr(social, "verify", lambda *args: identity)
    client = APIClient()
    first = client.post(
        "/api/auth/social/kakao", {"id_token": "private-token", "terms_agreed": True},
        format="json", HTTP_X_APP_VERSION="0.22.0",
    )
    assert first.status_code == 201
    second = client.post(
        "/api/auth/social/kakao", {"id_token": "private-token"},
        format="json", HTTP_X_APP_VERSION="0.23.0",
    )
    assert second.status_code == 200
    created = User.objects.get(pk=first.data["user_id"])
    assert created.registration_app_version == "0.22.0"
    rows = list(ApiRequestLog.objects.order_by("id"))
    assert [row.user_id for row in rows] == [created.pk, created.pk]
    assert [row.app_version for row in rows] == ["0.22.0", "0.23.0"]
    assert all((row.query, row.request_body, row.response_body) == ({}, {}, {}) for row in rows)


@pytest.mark.integration
def test_event_create_retry_and_update_keep_first_version(client):
    body = {
        "title": "version event", "start_at": START.isoformat(),
        "client_request_id": "version-idempotency", "creation_app_version": "forged",
    }
    first = client.post("/api/events", body, format="json", HTTP_X_APP_VERSION="0.22.0")
    assert first.status_code == 201
    second = client.post("/api/events", body, format="json", HTTP_X_APP_VERSION="0.23.0")
    assert second.status_code == 200
    changed = client.patch(
        f"/api/events/{first.data['id']}", {"title": "updated"},
        format="json", HTTP_X_APP_VERSION="0.24.0",
    )
    assert changed.status_code == 200
    event = Event.objects.get(pk=first.data["id"])
    assert event.creation_app_version == "0.22.0"
    assert "creation_app_version" not in first.data


@pytest.mark.integration
def test_calendar_import_stamps_only_new_rows(client):
    body = {"events": [{"external_id": "version-calendar", "title": "first", "start_at": START.isoformat()}]}
    first = client.post("/api/events/import", body, format="json", HTTP_X_APP_VERSION="0.22.0")
    assert first.status_code == 200
    assert first.data["created"] == 1
    body["events"][0]["title"] = "updated"
    second = client.post("/api/events/import", body, format="json", HTTP_X_APP_VERSION="0.23.0")
    assert second.status_code == 200
    assert Event.objects.get(external_id="version-calendar").creation_app_version == "0.22.0"
    assert "creation_app_version" not in second.data["results"][0]


@pytest.mark.integration
@pytest.mark.parametrize("record", ["alarm", "trip"])
def test_batch_upload_version_is_header_metadata_and_immutable(client, user, record):
    event = Event.objects.create(user=user, title="batch", start_at=START)
    if record == "alarm":
        model, path, key = AlarmLog, "/api/alarm-logs/batch", "logs"
        item = {"kind": "fired", "occurred_at": START.isoformat()}
    else:
        model, path, key = TripObservation, "/api/observations/batch", "observations"
        item = {"kind": "depart", "detector": "manual", "observed_at": START.isoformat()}
    item.update(event=event.pk, client_uuid="version-batch", app_version="forged")
    first = client.post(path, {key: [item]}, format="json", HTTP_X_APP_VERSION="0.22.0")
    assert first.status_code == 201
    second = client.post(path, {key: [item]}, format="json", HTTP_X_APP_VERSION="0.23.0")
    assert second.status_code == 200
    assert second.data["duplicated"] == 1
    assert model.objects.get(client_uuid="version-batch").app_version == "0.22.0"
    if record == "trip":
        assert "app_version" not in first.data["results"][0]


def test_server_expanded_occurrences_do_not_inherit_app_version(user, monkeypatch):
    monkeypatch.setattr(recurrence, "window", lambda zone: (START, START + timedelta(days=7)))
    monkeypatch.setattr(services, "compute_and_store", lambda *args, **kwargs: None)
    event = Event.objects.create(
        user=user, title="recurring", start_at=START, creation_app_version="0.22.0",
    )
    recurrence.attach(event, list(range(7)))
    recurrence.expand(event.recurrence_id)
    assert Event.objects.exclude(pk=event.pk).count() == 6
    assert set(Event.objects.exclude(pk=event.pk).values_list("creation_app_version", flat=True)) == {""}
