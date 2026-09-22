"""루틴 블록 API 테스트.

가장 중요한 것은 **격리**다. 블록은 사용자별이고 `precondition` 이 자기참조
FK 라서, 남의 블록 id 를 보내면 그 사람의 루틴 이름과 소요 시간이 새어 나간다.
목록 필터만으로는 막히지 않는다 — 참조 필드의 queryset 도 좁혀야 한다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.routines.models import BlockObservation, EventBlockSelection, RoutineBlock

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db

BLOCKS_URL = "/api/routines/blocks"
OBS_URL = "/api/routines/observations/batch"


ROUTE = {
    "minutes": 20,
    "mode": "transit",
    "source": "kakao",
    "summary": "2호선 · 20분",
    "key": "transit:2호선",
    "detail": "2호선",
}


@pytest.fixture
def stub_route(monkeypatch):
    """카카오 경로 조회를 고정값으로 바꾼다. 테스트가 외부 API 를 부르면 안 된다."""
    from apps.planning import services

    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(ROUTE), False))
    monkeypatch.setattr(
        services.clients, "resolve_route", lambda key, **kw: (dict(ROUTE, key=key), False)
    )


def make_user(django_user_model, email):
    u = django_user_model.objects.create_user(
        email=email, password="Kv8-silent-harbor-31", nickname=email.split("@")[0]
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.onboarding_prep_min = 30
    p.save()
    return u


def client_for(user):
    c = APIClient()
    c.force_authenticate(user=user)
    return c


def error_fields(response) -> set[str]:
    """에러 응답에서 필드 이름 집합을 꺼낸다.

    이 프로젝트는 back-spec 5절의 공통 포맷을 쓴다.

        {"error": {"code": ..., "message": ..., "details": {"필드": [...]}}}

    그래서 `res.data` 최상위에 필드 이름이 없다. 테스트가 그걸 모르면
    "검증이 안 된다" 고 잘못 읽는다.
    """
    data = response.data or {}
    details = (data.get("error") or {}).get("details")
    if isinstance(details, dict):
        return set(details.keys())
    # 포맷이 바뀌었거나 래핑되지 않은 경우를 위한 보조 경로
    return {k for k in data.keys() if k != "error"}


@pytest.fixture
def alice(django_user_model):
    return make_user(django_user_model, "alice@example.com")


@pytest.fixture
def bob(django_user_model):
    return make_user(django_user_model, "bob@example.com")


def make_block(user, name="샤워", lo=12, hi=18, **kw):
    return RoutineBlock.objects.create(
        user=user, name=name, default_min_minutes=lo, default_max_minutes=hi, **kw
    )


class TestAuthRequired:
    def test_anonymous_cannot_list(self):
        assert APIClient().get(BLOCKS_URL).status_code == 401

    def test_anonymous_cannot_create(self):
        res = APIClient().post(BLOCKS_URL, {"name": "x"}, format="json")
        assert res.status_code == 401

    def test_anonymous_cannot_upload_observations(self):
        assert APIClient().post(OBS_URL, {}, format="json").status_code == 401


class TestBlockCrud:
    def test_create_assigns_the_requesting_user(self, alice):
        res = client_for(alice).post(
            BLOCKS_URL,
            {"name": "샤워", "default_min_minutes": 12, "default_max_minutes": 18},
            format="json",
        )
        assert res.status_code == 201
        block = RoutineBlock.objects.get(pk=res.data["id"])
        assert block.user_id == alice.pk

    def test_cannot_create_for_another_user(self, alice, bob):
        """`user` 를 본문에 넣어도 무시돼야 한다. 받으면 남의 계정에 블록을 만든다."""
        res = client_for(alice).post(
            BLOCKS_URL,
            {
                "name": "침입",
                "default_min_minutes": 1,
                "default_max_minutes": 2,
                "user": bob.pk,
            },
            format="json",
        )
        assert res.status_code == 201
        assert RoutineBlock.objects.get(pk=res.data["id"]).user_id == alice.pk
        assert not RoutineBlock.objects.filter(user=bob).exists()

    def test_list_shows_only_my_blocks(self, alice, bob):
        make_block(alice, "내 것")
        make_block(bob, "남의 것")
        res = client_for(alice).get(BLOCKS_URL)
        assert res.status_code == 200
        names = [row["name"] for row in res.data]
        assert names == ["내 것"]

    def test_cannot_read_another_users_block_by_id(self, alice, bob):
        b = make_block(bob, "비밀")
        res = client_for(alice).get(f"{BLOCKS_URL}/{b.pk}")
        assert res.status_code == 404

    def test_cannot_patch_another_users_block(self, alice, bob):
        b = make_block(bob, "비밀")
        res = client_for(alice).patch(
            f"{BLOCKS_URL}/{b.pk}", {"name": "바꿈"}, format="json"
        )
        assert res.status_code == 404
        b.refresh_from_db()
        assert b.name == "비밀"

    def test_cannot_delete_another_users_block(self, alice, bob):
        b = make_block(bob, "비밀")
        assert client_for(alice).delete(f"{BLOCKS_URL}/{b.pk}").status_code == 404
        assert RoutineBlock.objects.filter(pk=b.pk).exists()

    def test_list_is_not_paginated(self, alice):
        """화면이 전체를 한 번에 그린다. 페이지로 잘리면 준비 시간 합계를 못 만든다."""
        for i in range(25):
            make_block(alice, f"블록{i}", order=i)
        res = client_for(alice).get(BLOCKS_URL)
        assert isinstance(res.data, list)
        assert len(res.data) == 25

    def test_delete_clears_dependents_precondition(self, alice):
        shower = make_block(alice, "샤워")
        hair = make_block(alice, "머리", precondition=shower)
        assert client_for(alice).delete(f"{BLOCKS_URL}/{shower.pk}").status_code == 204
        hair.refresh_from_db()
        assert hair.precondition_id is None


class TestPreconditionOwnership:
    """**이 클래스가 이 파일의 핵심이다.**

    `precondition` 은 자기참조 FK 다. DRF 의 PrimaryKeyRelatedField 는 기본
    queryset 전체에서 찾으므로 그냥 두면 남의 블록 id 가 통과한다.
    """

    def test_cannot_reference_another_users_block(self, alice, bob):
        victim = make_block(bob, "남의 샤워")
        res = client_for(alice).post(
            BLOCKS_URL,
            {
                "name": "내 머리",
                "default_min_minutes": 5,
                "default_max_minutes": 10,
                "precondition": victim.pk,
            },
            format="json",
        )
        assert res.status_code == 400
        assert "precondition" in error_fields(res)

    def test_can_reference_my_own_block(self, alice):
        shower = make_block(alice, "샤워")
        res = client_for(alice).post(
            BLOCKS_URL,
            {
                "name": "머리",
                "default_min_minutes": 5,
                "default_max_minutes": 10,
                "precondition": shower.pk,
            },
            format="json",
        )
        assert res.status_code == 201
        assert res.data["precondition"] == shower.pk

    def test_cannot_set_itself_as_precondition(self, alice):
        b = make_block(alice, "샤워")
        res = client_for(alice).patch(
            f"{BLOCKS_URL}/{b.pk}", {"precondition": b.pk}, format="json"
        )
        assert res.status_code == 400

    def test_cannot_create_a_cycle(self, alice):
        """A → B → A 는 임계경로 계산을 무한 루프에 빠뜨린다."""
        a = make_block(alice, "A")
        b = make_block(alice, "B", precondition=a)
        res = client_for(alice).patch(
            f"{BLOCKS_URL}/{a.pk}", {"precondition": b.pk}, format="json"
        )
        assert res.status_code == 400


class TestBlockValidation:
    def test_rejects_inverted_range(self, alice):
        res = client_for(alice).post(
            BLOCKS_URL,
            {"name": "이상", "default_min_minutes": 20, "default_max_minutes": 10},
            format="json",
        )
        assert res.status_code == 400
        assert "default_max_minutes" in error_fields(res)

    def test_rejects_absurdly_long_block(self, alice):
        res = client_for(alice).post(
            BLOCKS_URL,
            {"name": "무한", "default_min_minutes": 0, "default_max_minutes": 9999},
            format="json",
        )
        assert res.status_code == 400

    def test_rejects_duplicate_name_for_same_user(self, alice):
        """DRF 는 `Meta.constraints` 에서 검증기를 자동 생성하지 않는다.

        `unique_together` 만 인식하므로 `UniqueConstraint` 를 쓰면 시리얼라이저가
        통과시키고 IntegrityError 가 올라가 **사용자 입력에 500 이 난다.**
        """
        make_block(alice, "샤워")
        res = client_for(alice).post(
            BLOCKS_URL,
            {"name": "샤워", "default_min_minutes": 1, "default_max_minutes": 2},
            format="json",
        )
        assert res.status_code == 400
        assert "name" in error_fields(res)
        assert RoutineBlock.objects.filter(user=alice).count() == 1

    def test_can_rename_a_block_to_its_own_name(self, alice):
        """자기 자신은 중복 검사에서 빼야 한다. 빼지 않으면 수정이 막힌다."""
        b = make_block(alice, "샤워")
        res = client_for(alice).patch(
            f"{BLOCKS_URL}/{b.pk}",
            {"name": "샤워", "default_min_minutes": 11, "default_max_minutes": 19},
            format="json",
        )
        assert res.status_code == 200
        b.refresh_from_db()
        assert b.default_min_minutes == 11

    def test_same_name_is_fine_across_users(self, alice, bob):
        make_block(alice, "샤워")
        res = client_for(bob).post(
            BLOCKS_URL,
            {"name": "샤워", "default_min_minutes": 1, "default_max_minutes": 2},
            format="json",
        )
        assert res.status_code == 201


class TestEventBlockSelection:
    @pytest.fixture
    def event(self, alice):
        place = Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)
        return Event.objects.create(
            user=alice,
            title="수업",
            start_at=datetime(2026, 9, 21, 9, 0, tzinfo=KST),
            place=place,
        )

    def url(self, event):
        return f"/api/events/{event.pk}/blocks"

    def test_get_returns_default_checked_state(self, alice, event):
        make_block(alice, "포함", order=1)
        make_block(alice, "제외", order=2, included_by_default=False)
        res = client_for(alice).get(self.url(event))
        assert res.status_code == 200
        rows = {r["name"]: r for r in res.data["blocks"]}
        assert rows["포함"]["checked"] is True
        assert rows["제외"]["checked"] is False
        # 아직 사용자가 이 일정에서 바꾼 적이 없다.
        assert rows["포함"]["explicit"] is False

    def test_put_saves_selection_and_marks_explicit(self, alice, event, stub_route):
        b = make_block(alice, "샤워")
        res = client_for(alice).put(
            self.url(event),
            {"selections": [{"block": b.pk, "checked": False}]},
            format="json",
        )
        assert res.status_code == 200
        assert EventBlockSelection.objects.get(event=event, block=b).checked is False

        res = client_for(alice).get(self.url(event))
        row = res.data["blocks"][0]
        assert row["checked"] is False
        assert row["explicit"] is True

    def test_put_recomputes_the_alarm(self, alice, event, stub_route):
        """체크를 바꾸면 준비 시간이 바뀌므로 알람도 바뀌어야 한다."""
        big = make_block(alice, "긴 블록", 55, 65)
        res = client_for(alice).put(
            self.url(event),
            {"selections": [{"block": big.pk, "checked": True}]},
            format="json",
        )
        assert res.status_code == 200
        plan = res.data["alarm_plan"]
        assert plan["status"] == "ok"
        # 온보딩 30분이 아니라 블록 60분 근처를 써야 한다.
        assert plan["prep_minutes"] >= 55

    def test_put_only_updates_sent_blocks(self, alice, event, stub_route):
        a = make_block(alice, "A", order=1)
        b = make_block(alice, "B", order=2)
        EventBlockSelection.objects.create(event=event, block=b, checked=False)

        client_for(alice).put(
            self.url(event),
            {"selections": [{"block": a.pk, "checked": False}]},
            format="json",
        )
        # B 의 기존 선택이 지워지지 않아야 한다.
        assert EventBlockSelection.objects.get(event=event, block=b).checked is False
        assert EventBlockSelection.objects.get(event=event, block=a).checked is False

    def test_cannot_touch_another_users_event(self, alice, bob, event):
        res = client_for(bob).get(self.url(event))
        assert res.status_code == 404

    def test_cannot_attach_another_users_block(self, alice, bob, event, stub_route):
        """**소유자가 다른 블록을 붙이면 준비 시간에 남의 데이터가 섞인다.**"""
        victim = make_block(bob, "남의 블록")
        res = client_for(alice).put(
            self.url(event),
            {"selections": [{"block": victim.pk, "checked": True}]},
            format="json",
        )
        assert res.status_code == 400
        assert not EventBlockSelection.objects.filter(block=victim).exists()

    def test_rejects_duplicate_block_in_one_request(self, alice, event, stub_route):
        b = make_block(alice, "샤워")
        res = client_for(alice).put(
            self.url(event),
            {
                "selections": [
                    {"block": b.pk, "checked": True},
                    {"block": b.pk, "checked": False},
                ]
            },
            format="json",
        )
        assert res.status_code == 400

    def test_rejects_empty_selection(self, alice, event, stub_route):
        res = client_for(alice).put(self.url(event), {"selections": []}, format="json")
        assert res.status_code == 400


class TestBlockObservationBatch:
    def payload(self, block, uuid_, minutes=14.0):
        return {
            "observations": [
                {
                    "block": block.pk,
                    "observed_on": "2026-09-20",
                    "duration_minutes": minutes,
                    "slack_minutes": 5.0,
                    "was_parallel": False,
                    "client_uuid": uuid_,
                    "client_recorded_at": "2026-09-20T07:10:00+09:00",
                }
            ]
        }

    def test_uploads_and_assigns_the_user(self, alice):
        b = make_block(alice)
        res = client_for(alice).post(OBS_URL, self.payload(b, "u1"), format="json")
        assert res.status_code == 201
        assert res.data["accepted"] == 1
        obs = BlockObservation.objects.get(client_uuid="u1")
        assert obs.user_id == alice.pk

    def test_resending_the_same_uuid_is_ignored(self, alice):
        """오프라인 큐가 재전송한다. 행이 늘면 같은 아침이 두 번 학습된다."""
        b = make_block(alice)
        client_for(alice).post(OBS_URL, self.payload(b, "u1"), format="json")
        res = client_for(alice).post(OBS_URL, self.payload(b, "u1"), format="json")
        assert res.status_code == 200
        assert res.data["accepted"] == 0
        assert res.data["duplicated"] == 1
        assert BlockObservation.objects.filter(client_uuid="u1").count() == 1

    def test_cannot_upload_for_another_users_block(self, alice, bob):
        victim = make_block(bob, "남의 블록")
        res = client_for(alice).post(OBS_URL, self.payload(victim, "u1"), format="json")
        assert res.status_code == 400
        assert not BlockObservation.objects.exists()

    def test_rejects_negative_duration(self, alice):
        b = make_block(alice)
        res = client_for(alice).post(
            OBS_URL, self.payload(b, "u1", minutes=-5.0), format="json"
        )
        assert res.status_code == 400

    def test_rejects_absurd_duration(self, alice):
        b = make_block(alice)
        res = client_for(alice).post(
            OBS_URL, self.payload(b, "u1", minutes=99999.0), format="json"
        )
        assert res.status_code == 400

    def test_rejects_empty_batch(self, alice):
        res = client_for(alice).post(OBS_URL, {"observations": []}, format="json")
        assert res.status_code == 400

    def test_rejects_oversized_batch(self, alice):
        b = make_block(alice)
        rows = [
            {
                "block": b.pk,
                "observed_on": "2026-09-20",
                "duration_minutes": 10.0,
                "client_uuid": f"u{i}",
                "client_recorded_at": "2026-09-20T07:10:00+09:00",
            }
            for i in range(201)
        ]
        res = client_for(alice).post(OBS_URL, {"observations": rows}, format="json")
        assert res.status_code == 400


class TestLearningExposure:
    def test_observation_stats_are_returned(self, alice):
        """front-spec S4: 관측이 쌓인 블록은 실측 평균을 보여준다."""
        b = make_block(alice, "샤워", 12, 18)
        for i in range(6):
            BlockObservation.objects.create(
                user=alice, block=b, observed_on="2026-09-20",
                duration_minutes=20.0, client_uuid=f"o{i}",
                client_recorded_at=datetime(2026, 9, 20, 7, 0, tzinfo=KST),
            )
        res = client_for(alice).get(BLOCKS_URL)
        row = res.data[0]
        assert row["observation_count"] == 6
        assert row["observed_mean_minutes"] == pytest.approx(20.0)

    def test_no_observations_reports_none(self, alice):
        make_block(alice, "샤워")
        row = client_for(alice).get(BLOCKS_URL).data[0]
        assert row["observation_count"] == 0
        assert row["observed_mean_minutes"] is None
