# Partly AI-generated (Claude)
"""저장한 집(R3-2)과 저장한 집 출발을 집 출발로 세기(R3-3). 계약은 docs/iter3/saved-places-api.md."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from rest_framework.test import APIClient

from apps.accounts.models import SavedPlace
from apps.events.models import Event, Place
from apps.planning import prep_observations, services
from apps.planning.tests.test_gps_prep import ROUTE, dismiss, depart, morning

pytestmark = pytest.mark.django_db

KST = timezone(timedelta(hours=9))
HOME = (37.4842, 126.9297)
PARENTS = (37.5665, 126.9780)   # 본가(시청)
STUDIO = (37.5446, 127.0559)    # 자취방(성수)
DEST = (37.4601, 126.9520)


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="homes@example.com", password="Rv5-cobalt-forest-21", nickname="집들"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = HOME[0], HOME[1], "신림"
    p.onboarding_prep_min = 30
    p.save()
    return u


@pytest.fixture
def client(user):
    c = APIClient()
    c.force_authenticate(user)
    return c


@pytest.fixture(autouse=True)
def stub_route(monkeypatch):
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(ROUTE), False))
    monkeypatch.setattr(
        services.clients, "resolve_route", lambda key, **kw: (dict(ROUTE, key=key), False)
    )


def item(label, at, uuid=""):
    return {"client_uuid": uuid, "label": label, "name": label, "address": "", "lat": at[0],
            "lng": at[1]}


@pytest.mark.integration
class TestCrud:
    def test_list_shows_default_from_profile(self, client):
        data = client.get("/api/saved-places/").json()
        assert data["results"] == []
        assert data["default"]["label"] == "신림"
        assert data["default"]["id"] is None
        assert (data["default"]["lat"], data["default"]["lng"]) == HOME
        # 끝의 `/` 는 없어도 된다.
        assert client.get("/api/saved-places").status_code == 200

    def test_create_and_same_spot_updates_label(self, client):
        first = client.post("/api/saved-places/", item("본가", PARENTS), format="json")
        assert first.status_code == 201
        near = (PARENTS[0] + 0.0002, PARENTS[1])  # 약 22m
        again = client.post("/api/saved-places/", item("엄마집", near), format="json")
        assert again.status_code == 200
        assert again.json()["id"] == first.json()["id"]
        assert again.json()["label"] == "엄마집"
        assert SavedPlace.objects.count() == 1

    def test_near_default_is_refused(self, client):
        res = client.post("/api/saved-places/", item("집", HOME), format="json")
        assert res.status_code == 409
        assert res.json()["error"]["code"] == "same_as_default"

    def test_limit_is_five(self, client):
        for n in range(SavedPlace.MAX_PER_USER):
            spot = (37.50 + n * 0.01, 127.00)
            assert client.post("/api/saved-places/", item(f"집{n}", spot), format="json").status_code == 201
        res = client.post("/api/saved-places/", item("여섯", (37.60, 127.10)), format="json")
        assert res.status_code == 409
        assert res.json()["error"]["code"] == "saved_place_limit"

    def test_patch_delete_and_other_users_rows(self, client, django_user_model):
        pk = client.post("/api/saved-places/", item("본가", PARENTS), format="json").json()["id"]
        res = client.patch(f"/api/saved-places/{pk}/", {"label": "부모님 집"}, format="json")
        assert res.status_code == 200 and res.json()["label"] == "부모님 집"

        other = APIClient()
        other.force_authenticate(django_user_model.objects.create_user(
            email="other@example.com", password="x", nickname="남"))
        assert other.patch(f"/api/saved-places/{pk}/", {"label": "x"}, format="json").status_code == 404
        assert other.delete(f"/api/saved-places/{pk}/").status_code == 404

        assert client.delete(f"/api/saved-places/{pk}/").status_code == 204
        assert SavedPlace.objects.count() == 0

    def test_patch_onto_another_saved_place_is_refused(self, client):
        client.post("/api/saved-places/", item("본가", PARENTS), format="json")
        pk = client.post("/api/saved-places/", item("자취방", STUDIO), format="json").json()["id"]
        res = client.patch(f"/api/saved-places/{pk}/", {"lat": PARENTS[0], "lng": PARENTS[1]}, format="json")
        assert res.json()["error"]["code"] == "saved_place_duplicate"

    def test_requires_login(self):
        assert APIClient().get("/api/saved-places/").status_code == 401


@pytest.mark.integration
class TestBulk:
    def test_upload_is_idempotent_and_respects_rules(self, client):
        body = {"items": [
            item("본가", PARENTS, "u-1"),
            item("자취방", STUDIO, "u-2"),
            item("집", HOME, "u-3"),  # 기본 집 근처
        ]}
        first = client.post("/api/saved-places/bulk/", body, format="json")
        assert first.status_code == 200
        assert [r["label"] for r in first.json()["results"]] == ["본가", "자취방"]
        assert first.json()["skipped"] == [{"client_uuid": "u-3", "reason": "same_as_default"}]

        again = client.post("/api/saved-places/bulk/", body, format="json")
        assert len(again.json()["results"]) == 2
        assert SavedPlace.objects.count() == 2

    def test_overflow_is_skipped_not_failed(self, client):
        items = [item(f"집{n}", (37.50 + n * 0.01, 127.0), f"u-{n}") for n in range(7)]
        res = client.post("/api/saved-places/bulk/", {"items": items}, format="json").json()
        assert len(res["results"]) == 5
        assert [s["reason"] for s in res["skipped"]] == ["limit", "limit"]


@pytest.mark.integration
class TestMakeDefault:
    def test_swaps_with_profile_home_and_moves_future_events(self, client, user):
        pk = client.post("/api/saved-places/", item("본가", PARENTS, "u-1"), format="json").json()["id"]
        place = Place.objects.create(name="학교", lat=DEST[0], lng=DEST[1])
        future = Event.objects.create(
            user=user, place=place, title="수업", start_at=datetime(2099, 1, 1, 9, tzinfo=KST),
            origin_place_id=pk, origin_lat=PARENTS[0], origin_lng=PARENTS[1], origin_label="본가",
        )
        past = Event.objects.create(
            user=user, place=place, title="수업", start_at=datetime(2026, 9, 1, 9, tzinfo=KST),
            origin_place_id=pk, origin_lat=PARENTS[0], origin_lng=PARENTS[1], origin_label="본가",
        )

        res = client.post(f"/api/saved-places/{pk}/make-default/", format="json")

        assert res.status_code == 200
        data = res.json()
        assert data["default"]["label"] == "본가"
        assert (data["default"]["lat"], data["default"]["lng"]) == PARENTS
        assert [(r["id"], r["label"], r["client_uuid"]) for r in data["results"]] == [(pk, "신림", "")]
        future.refresh_from_db()
        past.refresh_from_db()
        assert future.origin_lat is None and future.origin_place_id is None
        assert future.departs_from_home
        assert past.origin_place_id == pk and past.origin_lat == PARENTS[0]


@pytest.mark.integration
class TestEventsFromSavedHome:
    def test_origin_place_id_fills_coordinates_and_keeps_prep(self, client, user):
        pk = client.post("/api/saved-places/", item("본가", PARENTS), format="json").json()["id"]
        res = client.post("/api/events", {
            "title": "수업", "start_at": "2099-01-01T09:00:00+09:00",
            "place": {"name": "학교", "lat": DEST[0], "lng": DEST[1]},
            "origin_place_id": pk,
        }, format="json")
        assert res.status_code == 201, res.json()
        body = res.json()
        assert body["origin_place_id"] == pk
        assert (body["origin_lat"], body["origin_lng"], body["origin_label"]) == (*PARENTS, "본가")
        assert body["prep_applies"] is True
        assert body["alarm_plan"]["prep_minutes"] == 30

        # 좌표만 바꾸면 저장한 집을 뗀다(다른 곳 출발).
        moved = client.patch(f"/api/events/{body['id']}", {
            "origin_lat": STUDIO[0], "origin_lng": STUDIO[1], "origin_label": "카페",
        }, format="json").json()
        assert moved["origin_place_id"] is None
        assert moved["prep_applies"] is False

    def test_someone_elses_place_is_refused(self, client, django_user_model):
        stranger = django_user_model.objects.create_user(email="s@example.com", password="x", nickname="s")
        theirs = SavedPlace.objects.create(user=stranger, label="남의 집", lat=PARENTS[0], lng=PARENTS[1])
        res = client.post("/api/events", {
            "title": "수업", "start_at": "2099-01-01T09:00:00+09:00",
            "place": {"name": "학교", "lat": DEST[0], "lng": DEST[1]},
            "origin_place_id": theirs.pk,
        }, format="json")
        assert res.status_code == 400
        assert "origin_place_id" in res.json()["error"]["details"]

    def test_deleting_the_place_keeps_the_origin(self, client, user):
        pk = client.post("/api/saved-places/", item("본가", PARENTS), format="json").json()["id"]
        place = Place.objects.create(name="학교", lat=DEST[0], lng=DEST[1])
        event = Event.objects.create(
            user=user, place=place, title="수업", start_at=datetime(2099, 1, 1, 9, tzinfo=KST),
            origin_place_id=pk, origin_lat=PARENTS[0], origin_lng=PARENTS[1],
        )
        client.delete(f"/api/saved-places/{pk}/")
        event.refresh_from_db()
        assert event.origin_place_id is None
        assert (event.origin_lat, event.origin_lng) == PARENTS

class TestPrepLearning:
    def test_gps_prep_learns_from_saved_home_mornings(self, user):
        saved = SavedPlace.objects.create(user=user, label="본가", lat=PARENTS[0], lng=PARENTS[1])
        place = Place.objects.create(name="학교", lat=DEST[0], lng=DEST[1])

        def gps_morning(day, minutes, **kw):
            event = Event.objects.create(
                user=user, place=place, title="수업", start_at=morning(day) + timedelta(hours=2), **kw,
            )
            dismiss(user, event, morning(day), f"dis-{day}")
            depart(user, event, morning(day) + timedelta(minutes=minutes), f"dep-{day}")
            return event

        gps_morning(1, 40)  # 기본 집
        gps_morning(2, 35, origin_place=saved, origin_lat=PARENTS[0], origin_lng=PARENTS[1])
        gps_morning(3, 20, origin_lat=STUDIO[0], origin_lng=STUDIO[1])  # 카페: 빠진다

        assert sorted(prep_observations.gps_prep_samples(user.pk)) == [35.0, 40.0]
