# Partly AI-generated (Claude)
"""저장한 집(R3-2)과 저장한 집 출발을 집 출발로 세기(R3-3). 계약은 docs/iter3/saved-places-api.md."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from rest_framework.test import APIClient

from apps.accounts import saved_places
from apps.accounts.models import Profile, SavedPlace
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


# Partly AI-generated (Claude)
class TestLabelKey:
    """BE-H9 이름 비교 규칙. docs/iter4/server-saved-place-labels-and-report-tone.md"""

    @pytest.mark.parametrize("a, b", [
        ("본가", " 본가 "), ("우리  집", "우리 집"), ("Home", "home"), ("ＨＯＭＥ", "home"),
        ("자취방\t", "자취방"),
        # H9-4: 서식 문자(Cf)는 비교에서 뺀다.
        ("집​", "집"), ("본‍가", "본가"), ("﻿본가", "본가"), ("본가⁠", "본가"),
    ])
    def test_same(self, a, b):
        assert saved_places.label_key(a) == saved_places.label_key(b)

    @pytest.mark.parametrize("a, b", [("본가2", "본가 2"), ("본가", "본가집"), ("집", "우리 집")])
    def test_different(self, a, b):
        assert saved_places.label_key(a) != saved_places.label_key(b)

    def test_unique_label_stays_within_the_column(self):
        rows = [SavedPlace(pk=1, label="가" * 40)]
        label = saved_places.unique_label("가" * 40, rows)
        assert label == "가" * 38 + " 2" and len(label) == saved_places.LABEL_MAX


@pytest.mark.integration
class TestLabelDuplicate:
    """BE-H9. "집"이나 다른 저장한 집과 같은 이름은 409 `saved_place_label_duplicate`."""

    def assert_label_error(self, res, reserved=False):
        assert res.status_code == 409, res.json()
        error = res.json()["error"]
        assert error["code"] == "saved_place_label_duplicate"
        expected = (saved_places.LABEL_RESERVED_MESSAGE if reserved
                    else saved_places.LABEL_DUPLICATE_MESSAGE)
        assert error["details"] == {"label": [expected]}
        assert error["message"] == expected

    @pytest.mark.parametrize("label", ["집", " 집 "])
    def test_create_reserved_name(self, client, label):
        res = client.post("/api/saved-places/", item(label, PARENTS), format="json")
        self.assert_label_error(res, reserved=True)
        assert SavedPlace.objects.count() == 0

    @pytest.mark.parametrize("label", ["본가", "  본가", "본가  "])
    def test_create_same_name_elsewhere(self, client, label):
        client.post("/api/saved-places/", item("본가", PARENTS), format="json")
        res = client.post("/api/saved-places/", item(label, STUDIO), format="json")
        self.assert_label_error(res)
        assert SavedPlace.objects.count() == 1

    def test_inner_spaces_and_case_are_ignored(self, client):
        client.post("/api/saved-places/", item("Mom  House", PARENTS), format="json")
        res = client.post("/api/saved-places/", item("mom house", STUDIO), format="json")
        self.assert_label_error(res)

    def test_same_spot_with_its_own_name_is_fine(self, client):
        """같은 자리(50m)는 그 행을 고치는 것이라 자기 이름과는 겹치지 않는다."""
        first = client.post("/api/saved-places/", item("본가", PARENTS, "u-1"), format="json").json()
        near = (PARENTS[0] + 0.0002, PARENTS[1])
        again = client.post("/api/saved-places/", item("본가", near), format="json")
        assert again.status_code == 200 and again.json()["id"] == first["id"]
        # 같은 client_uuid 로 다시 보내도 통과한다(재시도).
        retry = client.post("/api/saved-places/", item("본가", PARENTS, "u-1"), format="json")
        assert retry.status_code == 200

    def test_same_spot_taking_another_rows_name_is_refused(self, client):
        client.post("/api/saved-places/", item("본가", PARENTS), format="json")
        client.post("/api/saved-places/", item("자취방", STUDIO), format="json")
        near = (STUDIO[0] + 0.0002, STUDIO[1])
        res = client.post("/api/saved-places/", item("본가", near), format="json")
        self.assert_label_error(res)
        assert sorted(SavedPlace.objects.values_list("label", flat=True)) == ["본가", "자취방"]

    def test_patch_rules(self, client):
        client.post("/api/saved-places/", item("본가", PARENTS), format="json")
        pk = client.post("/api/saved-places/", item("자취방", STUDIO), format="json").json()["id"]
        self.assert_label_error(
            client.patch(f"/api/saved-places/{pk}/", {"label": "본가 "}, format="json"))
        self.assert_label_error(
            client.patch(f"/api/saved-places/{pk}/", {"label": "집"}, format="json"), reserved=True)
        # 자기 이름(공백·대소문자만 다름)과 다른 칸만 고치는 것은 된다.
        assert client.patch(f"/api/saved-places/{pk}/", {"label": " 자취방"}, format="json").status_code == 200
        assert client.patch(f"/api/saved-places/{pk}/", {"address": "성수동"}, format="json").status_code == 200
        assert client.patch(f"/api/saved-places/{pk}/", {"label": "원룸"}, format="json").json()["label"] == "원룸"

    def test_patch_keeps_an_old_duplicate_name(self, client, user):
        """BE-H9 전에 생긴 같은 이름 행도 이름을 바꾸지 않는 수정은 된다."""
        a = SavedPlace.objects.create(user=user, label="본가", lat=PARENTS[0], lng=PARENTS[1])
        SavedPlace.objects.create(user=user, label="본가", lat=STUDIO[0], lng=STUDIO[1])
        res = client.patch(f"/api/saved-places/{a.pk}/", {"label": "본가", "name": "엄마"}, format="json")
        assert res.status_code == 200

    def test_other_users_names_do_not_count(self, client, django_user_model):
        stranger = django_user_model.objects.create_user(email="s2@example.com", password="x", nickname="s")
        SavedPlace.objects.create(user=stranger, label="본가", lat=PARENTS[0], lng=PARENTS[1])
        assert client.post("/api/saved-places/", item("본가", PARENTS), format="json").status_code == 201

    def test_bulk_renames_instead_of_failing(self, client):
        """첫 업로드는 막지 않는다. 겹치면 " 2" 를 붙이고 `renamed` 에 알린다."""
        far = (37.40, 127.10)
        body = {"items": [
            item("본가", PARENTS, "u-1"),
            item(" 본가", STUDIO, "u-2"),   # 같은 요청 안의 겹침
            item("집", far, "u-3"),         # 기본 집 이름(자리는 기본 집과 멂)
        ]}
        res = client.post("/api/saved-places/bulk/", body, format="json")
        assert res.status_code == 200
        data = res.json()
        assert [r["label"] for r in data["results"]] == ["본가", "본가 2", "집 2"]
        assert data["skipped"] == []
        assert data["renamed"] == [
            {"client_uuid": "u-2", "label": "본가 2", "requested_label": "본가"},
            {"client_uuid": "u-3", "label": "집 2", "requested_label": "집"},
        ]
        # 같은 기기 목록을 다시 보내도 늘지 않고, 이미 바꾼 이름도 그대로다.
        again = client.post("/api/saved-places/bulk/", body, format="json").json()
        assert [r["label"] for r in again["results"]] == ["본가", "본가 2", "집 2"]
        assert SavedPlace.objects.count() == 3

    def test_bulk_against_existing_rows(self, client):
        client.post("/api/saved-places/", item("본가", PARENTS), format="json")
        client.post("/api/saved-places/", item("본가 2", STUDIO), format="json")
        res = client.post("/api/saved-places/bulk/", {"items": [item("본가", (37.40, 127.10), "u-9")]},
                          format="json").json()
        assert res["renamed"] == [{"client_uuid": "u-9", "label": "본가 3", "requested_label": "본가"}]

    def test_bulk_without_conflicts_has_empty_renamed(self, client):
        res = client.post("/api/saved-places/bulk/", {"items": [item("본가", PARENTS, "u-1")]},
                          format="json").json()
        assert res["renamed"] == []


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

    # --- H9-1: 내려온 예전 기본 집도 이름 규칙을 지킨다. (Partly AI-generated (Claude)) ---

    def _make_default(self, client, user, home_label):
        Profile.objects.filter(user=user).update(home_label=home_label)
        pk = client.post("/api/saved-places/", item("본가", PARENTS, "u-1"), format="json").json()["id"]
        res = client.post(f"/api/saved-places/{pk}/make-default/", format="json")
        assert res.status_code == 200
        return res.json(), pk

    @pytest.mark.parametrize("home_label", ["", "  ", "집", " 집 ", "집​"])
    def test_blank_or_reserved_home_label_becomes_previous_home(self, client, user, home_label):
        data, pk = self._make_default(client, user, home_label)
        row = next(r for r in data["results"] if r["id"] == pk)
        assert row["label"] == saved_places.PREVIOUS_HOME_LABEL == "예전 집"
        assert (row["lat"], row["lng"]) == HOME

    def test_home_label_taken_by_another_row_gets_a_number(self, client, user):
        client.post("/api/saved-places/", item("신림", STUDIO), format="json")
        data, pk = self._make_default(client, user, "신림")
        labels = {r["id"]: r["label"] for r in data["results"]}
        assert labels[pk] == "신림 2"
        assert sorted(labels.values()) == ["신림", "신림 2"]

    def test_previous_home_name_taken_gets_a_number(self, client, user):
        client.post("/api/saved-places/", item("예전 집", STUDIO), format="json")
        data, pk = self._make_default(client, user, "")
        assert {r["id"]: r["label"] for r in data["results"]}[pk] == "예전 집 2"

    def test_reads_the_current_home_not_the_cached_profile(self, client, user):
        """다른 요청이 방금 바꾼 기본 집을 내린다(프로필을 잠그고 다시 읽는다, H9-2)."""
        assert user.profile.home_label == "신림"  # 캐시
        pk = client.post("/api/saved-places/", item("본가", PARENTS, "u-1"), format="json").json()["id"]
        Profile.objects.filter(user=user).update(home_lat=STUDIO[0], home_lng=STUDIO[1], home_label="성수")
        data = client.post(f"/api/saved-places/{pk}/make-default/", format="json").json()
        assert [(r["label"], r["lat"], r["lng"]) for r in data["results"]] == [("성수", *STUDIO)]


@pytest.mark.integration
class TestReviewFixes:
    """BE-H9 리뷰 수정(H9-2~H9-5)과 검사 순서. (Partly AI-generated (Claude))"""

    def five(self, client):
        for n in range(SavedPlace.MAX_PER_USER):
            res = client.post("/api/saved-places/", item(f"집{n}", (37.50 + n * 0.01, 127.00), f"u-{n}"),
                              format="json")
            assert res.status_code == 201

    def test_duplicate_label_on_a_new_sixth_place_is_limit(self, client):
        self.five(client)
        res = client.post("/api/saved-places/", item("집0", (37.60, 127.10)), format="json")
        assert res.status_code == 409
        assert res.json()["error"]["code"] == "saved_place_limit"
        assert res.json()["error"]["message"] == "집은 기본 집 말고 5개까지 저장할 수 있어요."

    def test_client_uuid_update_at_the_limit_with_a_duplicate_name_is_a_label_error(self, client):
        self.five(client)
        res = client.post("/api/saved-places/", item("집1", (37.50, 127.00), "u-0"), format="json")
        assert res.status_code == 409
        assert res.json()["error"]["code"] == "saved_place_label_duplicate"
        assert res.json()["error"]["details"] == {"label": [saved_places.LABEL_DUPLICATE_MESSAGE]}

    @pytest.mark.parametrize("label", ["집​", "‍집", "﻿집"])
    def test_zero_width_reserved_name(self, client, label):
        res = client.post("/api/saved-places/", item(label, PARENTS), format="json")
        assert res.status_code == 409
        assert res.json()["error"]["details"] == {"label": [saved_places.LABEL_RESERVED_MESSAGE]}

    def test_zero_width_duplicate_name(self, client):
        client.post("/api/saved-places/", item("본가", PARENTS), format="json")
        res = client.post("/api/saved-places/", item("본​가", STUDIO), format="json")
        assert res.status_code == 409
        assert res.json()["error"]["code"] == "saved_place_label_duplicate"

    @pytest.mark.parametrize("label", ["", "   ", "​", " ​‍ "])
    def test_blank_or_invisible_label_is_400(self, client, label):
        res = client.post("/api/saved-places/", item(label, PARENTS), format="json")
        assert res.status_code == 400
        assert res.json()["error"]["details"]["label"] == ["이름을 입력해 주세요."]
        assert res.json()["error"]["message"] == "이름을 입력해 주세요."

    def test_bulk_does_not_take_a_later_items_name(self, client):
        """H9-3. `["집", "집 2"]` 는 "집 3"·"집 2" 다("집 2"·"집 2 2" 가 아니다). 다시 보내도 같다."""
        body = {"items": [item("집", PARENTS, "u-1"), item("집 2", STUDIO, "u-2")]}
        first = client.post("/api/saved-places/bulk/", body, format="json").json()
        assert [r["label"] for r in first["results"]] == ["집 3", "집 2"]
        assert first["renamed"] == [{"client_uuid": "u-1", "label": "집 3", "requested_label": "집"}]
        again = client.post("/api/saved-places/bulk/", body, format="json").json()
        assert [r["label"] for r in again["results"]] == ["집 3", "집 2"]
        assert SavedPlace.objects.count() == 2

    def test_bulk_40_char_resend_is_stable(self, client):
        long = "가" * saved_places.LABEL_MAX
        body = {"items": [item(long, PARENTS, "u-1"), item(long, STUDIO, "u-2"),
                          item(long, (37.40, 127.10), "u-3")]}
        first = [r["label"] for r in client.post("/api/saved-places/bulk/", body, format="json").json()["results"]]
        assert first == [long, "가" * 38 + " 2", "가" * 38 + " 3"]
        assert all(len(label) <= saved_places.LABEL_MAX for label in first)
        for _ in range(2):
            again = client.post("/api/saved-places/bulk/", body, format="json").json()
            assert [r["label"] for r in again["results"]] == first
            assert again["renamed"] == [
                {"client_uuid": "u-2", "label": "가" * 38 + " 2", "requested_label": long},
                {"client_uuid": "u-3", "label": "가" * 38 + " 3", "requested_label": long},
            ]

    def test_bulk_keeps_a_numbered_name_when_a_lower_number_frees_up(self, client, user):
        SavedPlace.objects.create(user=user, client_uuid="u-1", label="본가", lat=PARENTS[0], lng=PARENTS[1])
        SavedPlace.objects.create(user=user, client_uuid="u-3", label="본가 3", lat=STUDIO[0], lng=STUDIO[1])
        body = {"items": [item("본가", PARENTS, "u-1"), item("본가", STUDIO, "u-3")]}
        res = client.post("/api/saved-places/bulk/", body, format="json").json()
        assert [r["label"] for r in res["results"]] == ["본가", "본가 3"]

    def test_haeyo_messages(self, client):
        res = client.post("/api/saved-places/", item("본가", HOME), format="json")
        assert res.json()["error"]["message"] == "기본 집에서 50m 안이에요. 기본 집으로 이미 저장돼 있어요."
        client.post("/api/saved-places/", item("본가", PARENTS), format="json")
        pk = client.post("/api/saved-places/", item("자취방", STUDIO), format="json").json()["id"]
        res = client.patch(f"/api/saved-places/{pk}/", {"lat": PARENTS[0], "lng": PARENTS[1]}, format="json")
        assert res.json()["error"]["message"] == "저장한 다른 집에서 50m 안이에요."
        res = client.post("/api/saved-places/", item("도쿄", (35.68, 139.77)), format="json")
        assert res.json()["error"]["details"]["lat"] == ["국내 좌표만 집으로 저장할 수 있어요."]
        res = client.post("/api/saved-places/bulk/", {"items": [item(f"집{n}", PARENTS, f"u-{n}") for n in range(21)]},
                          format="json")
        assert res.status_code == 400
        assert res.json()["error"]["details"]["items"] == ["한 번에 20개까지 올릴 수 있어요."]

    def test_every_write_locks_the_profile(self, client, monkeypatch):
        """H9-2. 프로필 행을 사용자별 뮤텍스로 잠근다(SQLite 는 `FOR UPDATE` 가 없어 부르는지만 본다)."""
        calls = []
        original = saved_places.lock_profile

        def spy(user):
            calls.append(user.pk)
            return original(user)

        monkeypatch.setattr(saved_places, "lock_profile", spy)
        pk = client.post("/api/saved-places/", item("본가", PARENTS), format="json").json()["id"]
        client.patch(f"/api/saved-places/{pk}/", {"label": "부모님"}, format="json")
        client.post("/api/saved-places/bulk/", {"items": [item("자취방", STUDIO, "u-2")]}, format="json")
        client.post(f"/api/saved-places/{pk}/make-default/", format="json")
        assert len(calls) == 4

    def test_post_and_patch_check_the_current_home_not_the_cached_profile(self, client, user):
        """다른 요청이 방금 옮긴 기본 집 근처는 POST·PATCH 모두 `same_as_default` 다."""
        assert user.profile.home_lat == HOME[0]  # 캐시
        pk = client.post("/api/saved-places/", item("자취방", STUDIO), format="json").json()["id"]
        Profile.objects.filter(user=user).update(home_lat=PARENTS[0], home_lng=PARENTS[1])
        res = client.post("/api/saved-places/", item("본가", PARENTS), format="json")
        assert res.json()["error"]["code"] == "same_as_default"
        res = client.patch(f"/api/saved-places/{pk}/", {"lat": PARENTS[0], "lng": PARENTS[1]}, format="json")
        assert res.json()["error"]["code"] == "same_as_default"


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
