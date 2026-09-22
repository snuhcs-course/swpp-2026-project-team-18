"""이동 관측 API 테스트.

멱등성이 핵심이다. 앱은 오프라인이면 로컬 큐에 쌓아 두고 나중에 다시 보낸다.
같은 건이 두 번 저장되면 **같은 아침이 두 번 학습된다** — 분포가 그 방향으로
기울고, 그게 알람에 반영된다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.observations.models import TripObservation
from apps.observations.serializers import MAX_ACCURACY_M
from apps.planning.models import AlarmPlan

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db

BATCH_URL = "/api/observations/batch"
LIST_URL = "/api/observations"


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="obs@example.com", password="Jb6-marble-thicket-84", nickname="관측"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.onboarding_prep_min = 30
    p.save()
    return u


@pytest.fixture
def client(user):
    c = APIClient()
    c.force_authenticate(user=user)
    return c


@pytest.fixture
def event(user):
    place = Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)
    return Event.objects.create(
        user=user, place=place, title="수업",
        start_at=datetime(2026, 10, 5, 9, 0, tzinfo=KST),
    )


def obs_body(event, uid, *, kind="depart", accuracy=15.0, **kw):
    base = {
        "event": event.pk,
        "kind": kind,
        "detector": "gps",
        "observed_at": "2026-10-05T08:00:00+09:00",
        "lat": 37.4842,
        "lng": 126.9297,
        "accuracy_m": accuracy,
        "distance_m": 60.0,
        "client_uuid": uid,
    }
    base.update(kw)
    return {"observations": [base]}


class TestIdempotency:
    def test_first_upload_is_201(self, client, event):
        res = client.post(BATCH_URL, obs_body(event, "u1"), format="json")
        assert res.status_code == 201
        assert res.data["accepted"] == 1
        assert res.data["duplicated"] == 0

    def test_resend_is_200_and_ignored(self, client, event):
        client.post(BATCH_URL, obs_body(event, "u1"), format="json")
        res = client.post(BATCH_URL, obs_body(event, "u1"), format="json")
        assert res.status_code == 200
        assert res.data["accepted"] == 0
        assert res.data["duplicated"] == 1
        assert TripObservation.objects.filter(client_uuid="u1").count() == 1

    def test_resend_does_not_overwrite(self, client, event):
        """첫 판정이 가장 신뢰할 만하다. 재전송은 "저장됐는지 모르겠다" 는 뜻이다."""
        client.post(BATCH_URL, obs_body(event, "u1", accuracy=12.0), format="json")
        client.post(BATCH_URL, obs_body(event, "u1", accuracy=48.0), format="json")
        assert TripObservation.objects.get(client_uuid="u1").accuracy_m == 12.0

    def test_same_uuid_across_users_is_allowed(self, client, event, django_user_model):
        """멱등 키는 사용자별이다. 두 사람의 앱이 같은 uuid 를 만들 수 있다."""
        other = django_user_model.objects.create_user(
            email="obs2@example.com", password="Wq3-golden-canyon-62", nickname="타인"
        )
        place = Place.objects.create(name="다른 목적지", lat=37.5, lng=127.0)
        other_event = Event.objects.create(
            user=other, place=place, title="타인 일정",
            start_at=datetime(2026, 10, 5, 9, 0, tzinfo=KST),
        )
        client.post(BATCH_URL, obs_body(event, "same"), format="json")

        c2 = APIClient()
        c2.force_authenticate(user=other)
        res = c2.post(BATCH_URL, obs_body(other_event, "same"), format="json")
        assert res.status_code == 201
        assert TripObservation.objects.filter(client_uuid="same").count() == 2

    def test_mixed_batch_counts_both(self, client, event):
        client.post(BATCH_URL, obs_body(event, "old"), format="json")
        body = {
            "observations": [
                obs_body(event, "old")["observations"][0],
                obs_body(event, "new", kind="arrive")["observations"][0],
            ]
        }
        res = client.post(BATCH_URL, body, format="json")
        assert res.data["accepted"] == 1
        assert res.data["duplicated"] == 1


class TestValidation:
    def test_rejects_low_accuracy_fix(self, client, event):
        """오차가 큰 fix 로 반경 판정을 말할 수 없다. 서버도 막는다."""
        res = client.post(
            BATCH_URL, obs_body(event, "u1", accuracy=MAX_ACCURACY_M + 1), format="json"
        )
        assert res.status_code == 400
        assert not TripObservation.objects.exists()

    def test_accepts_boundary_accuracy(self, client, event):
        res = client.post(
            BATCH_URL, obs_body(event, "u1", accuracy=MAX_ACCURACY_M), format="json"
        )
        assert res.status_code == 201

    def test_rejects_negative_accuracy(self, client, event):
        res = client.post(BATCH_URL, obs_body(event, "u1", accuracy=-1.0), format="json")
        assert res.status_code == 400

    def test_rejects_out_of_range_coordinates(self, client, event):
        res = client.post(BATCH_URL, obs_body(event, "u1", lat=999.0), format="json")
        assert res.status_code == 400

    def test_rejects_blank_client_uuid(self, client, event):
        res = client.post(BATCH_URL, obs_body(event, "   "), format="json")
        assert res.status_code == 400

    def test_rejects_unknown_kind(self, client, event):
        res = client.post(BATCH_URL, obs_body(event, "u1", kind="점심"), format="json")
        assert res.status_code == 400

    def test_rejects_empty_batch(self, client):
        res = client.post(BATCH_URL, {"observations": []}, format="json")
        assert res.status_code == 400

    def test_rejects_oversized_batch(self, client, event):
        from apps.observations.serializers import TripObservationBatchSerializer

        rows = [
            obs_body(event, f"u{i}")["observations"][0]
            for i in range(TripObservationBatchSerializer.MAX_ITEMS + 1)
        ]
        res = client.post(BATCH_URL, {"observations": rows}, format="json")
        assert res.status_code == 400

    def test_rejects_nonexistent_event(self, client):
        res = client.post(
            BATCH_URL,
            {
                "observations": [{
                    "event": 999999, "kind": "depart", "detector": "gps",
                    "observed_at": "2026-10-05T08:00:00+09:00",
                    "lat": 37.48, "lng": 126.93,
                    "accuracy_m": 10.0, "distance_m": 60.0, "client_uuid": "u1",
                }]
            },
            format="json",
        )
        # 존재하지 않는 일정도 404 다 — "없다" 와 "남의 것" 을 구분하면
        # 그 차이가 곧 정보다.
        assert res.status_code in (400, 404)


class TestDelayIsDerived:
    """계획 대비 지연은 필드가 아니라 파생값이다.

    복사해 두면 알람이 재계산될 때 낡은 값이 남는다.
    """

    def test_delay_is_null_without_a_plan(self, client, event):
        res = client.post(BATCH_URL, obs_body(event, "u1"), format="json")
        assert res.data["results"][0]["delay_minutes"] is None

    def test_delay_uses_the_current_plan(self, client, user, event):
        AlarmPlan.objects.create(
            user=user, event=event, status=AlarmPlan.Status.OK,
            alarm_at=datetime(2026, 10, 5, 7, 30, tzinfo=KST),
            depart_by=datetime(2026, 10, 5, 8, 0, tzinfo=KST),
            arrive_at=datetime(2026, 10, 5, 8, 50, tzinfo=KST),
            prep_minutes=30, travel_minutes=20, buffer_minutes=10,
        )
        # 계획상 08:00 출발인데 08:07 에 나갔다 → 7분 지연
        client.post(
            BATCH_URL,
            obs_body(event, "u1", observed_at="2026-10-05T08:07:00+09:00"),
            format="json",
        )
        obs = TripObservation.objects.get(client_uuid="u1")
        assert obs.delay_minutes == 7

    def test_delay_is_signed(self, client, user, event):
        """**부호를 살린다.** 절댓값만 쌓으면 "항상 늦는 사람" 과 "들쭉날쭉한
        사람" 을 구분할 수 없고, 그 차이가 곧 분산이다.
        """
        AlarmPlan.objects.create(
            user=user, event=event, status=AlarmPlan.Status.OK,
            alarm_at=datetime(2026, 10, 5, 7, 30, tzinfo=KST),
            depart_by=datetime(2026, 10, 5, 8, 0, tzinfo=KST),
            arrive_at=datetime(2026, 10, 5, 8, 50, tzinfo=KST),
            prep_minutes=30, travel_minutes=20, buffer_minutes=10,
        )
        client.post(
            BATCH_URL,
            obs_body(event, "early", observed_at="2026-10-05T07:52:00+09:00"),
            format="json",
        )
        assert TripObservation.objects.get(client_uuid="early").delay_minutes == -8

    def test_recomputing_the_plan_changes_the_derived_delay(self, client, user, event):
        plan = AlarmPlan.objects.create(
            user=user, event=event, status=AlarmPlan.Status.OK,
            alarm_at=datetime(2026, 10, 5, 7, 30, tzinfo=KST),
            depart_by=datetime(2026, 10, 5, 8, 0, tzinfo=KST),
            arrive_at=datetime(2026, 10, 5, 8, 50, tzinfo=KST),
            prep_minutes=30, travel_minutes=20, buffer_minutes=10,
        )
        client.post(
            BATCH_URL,
            obs_body(event, "u1", observed_at="2026-10-05T08:07:00+09:00"),
            format="json",
        )
        obs = TripObservation.objects.get(client_uuid="u1")
        assert obs.delay_minutes == 7

        plan.depart_by = datetime(2026, 10, 5, 8, 10, tzinfo=KST)
        plan.save()
        obs.refresh_from_db()
        # 같은 관측인데 계획이 바뀌어 지연이 -3 이 된다. 복사해 뒀으면 7 이 남는다.
        assert obs.delay_minutes == -3


class TestListFiltering:
    @pytest.fixture
    def seeded(self, client, event):
        client.post(BATCH_URL, obs_body(event, "d1", kind="depart"), format="json")
        client.post(
            BATCH_URL,
            obs_body(event, "a1", kind="arrive",
                     observed_at="2026-10-05T08:40:00+09:00"),
            format="json",
        )
        return event

    def test_kind_filter(self, client, seeded):
        res = client.get(f"{LIST_URL}?kind=arrive")
        assert res.status_code == 200
        assert [o["kind"] for o in res.data["results"]] == ["arrive"]

    def test_event_filter(self, client, seeded):
        res = client.get(f"{LIST_URL}?event={seeded.pk}")
        assert len(res.data["results"]) == 2

    def test_unknown_event_filter_returns_empty(self, client, seeded):
        res = client.get(f"{LIST_URL}?event=999999")
        assert res.data["results"] == []

    def test_garbage_filters_are_ignored(self, client, seeded):
        """잘못된 필터에 500 이 나면 안 된다."""
        for q in ("?kind=쓰레기", "?event=abc", "?from=쓰레기", "?to=!!!"):
            res = client.get(f"{LIST_URL}{q}")
            assert res.status_code == 200, f"{q} → {res.status_code}"

    def test_time_range_filter(self, client, seeded):
        res = client.get(
            f"{LIST_URL}?from=2026-10-05T08:30:00%2B09:00"
        )
        assert [o["client_uuid"] for o in res.data["results"]] == ["a1"]
