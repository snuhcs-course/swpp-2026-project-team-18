# Partly AI-generated (Claude)
"""도착 관측의 반경 진입 시각 `entered_at`(db-schema.md A1).

`observed_at` 은 반경 진입 + 2분 체류를 채운 판정 fix 의 시각이라 진입보다 최소 2분
늦다. 진입 시각을 함께 받아 두면 나중에 보정할 수 있다. **이 값은 받아 두기만 한다** —
`observed_at` 의 뜻과 학습·리포트의 사용은 그대로다(§5-3).
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.observations.models import TripObservation
from apps.observations.serializers import MAX_DWELL_SECONDS

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db

BATCH_URL = "/api/observations/batch"
LIST_URL = "/api/observations"
OBSERVED = datetime(2026, 10, 5, 8, 55, tzinfo=KST)


@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(
        email="enter@example.com", password="Jb6-marble-thicket-84", nickname="진입"
    )


@pytest.fixture
def client(user):
    c = APIClient()
    c.force_authenticate(user=user)
    return c


@pytest.fixture
def event(user):
    place = Place.objects.create(name="302동", lat=37.4486, lng=126.9526)
    return Event.objects.create(
        user=user, place=place, title="수업", start_at=datetime(2026, 10, 5, 9, 0, tzinfo=KST)
    )


def arrival(event, uid, *, kind="arrive", entered=None, **kw):
    body = {
        "event": event.pk,
        "kind": kind,
        "detector": "gps",
        "observed_at": OBSERVED.isoformat(),
        "lat": 37.4486,
        "lng": 126.9526,
        "accuracy_m": 12.0,
        "distance_m": 18.0,
        "client_uuid": uid,
    }
    if kind == "arrive":
        body["dwell_seconds"] = 150
    if entered is not None:
        body["entered_at"] = entered.isoformat()
    body.update(kw)
    return {"observations": [body]}


class TestEnteredAt:
    def test_arrival_stores_the_entry_time(self, client, event):
        entered = OBSERVED - timedelta(seconds=150)
        res = client.post(BATCH_URL, arrival(event, "a1", entered=entered), format="json")

        assert res.status_code == 201
        obs = TripObservation.objects.get(client_uuid="a1")
        assert obs.entered_at == entered
        # 판정 시각은 앱이 보낸 그대로다. 진입 시각으로 바꾸지 않는다(§5-3).
        assert obs.observed_at == OBSERVED
        assert res.data["results"][0]["entered_at"] is not None

    def test_is_optional_for_older_apps(self, client, event):
        """이 필드가 생기기 전 큐에 쌓인 도착도 그대로 올라온다."""
        assert client.post(BATCH_URL, arrival(event, "a2"), format="json").status_code == 201
        assert TripObservation.objects.get(client_uuid="a2").entered_at is None

    def test_depart_cannot_carry_it(self, client, event):
        """출발은 "반경을 벗어남" 이다. 진입 시각이 붙어 오면 앱의 배선 오류다."""
        res = client.post(
            BATCH_URL,
            arrival(event, "d1", kind="depart", entered=OBSERVED - timedelta(minutes=1)),
            format="json",
        )
        assert res.status_code == 400
        assert not TripObservation.objects.filter(client_uuid="d1").exists()

    def test_entry_after_the_decision_is_rejected(self, client, event):
        res = client.post(
            BATCH_URL, arrival(event, "a3", entered=OBSERVED + timedelta(seconds=1)), format="json"
        )
        assert res.status_code == 400

    def test_entry_equal_to_the_decision_is_accepted(self, client, event):
        """BE-24 즉시 도착은 진입=판정이며 체류 시간 0도 보존한다."""
        body = arrival(event, "a4", entered=OBSERVED)
        body["observations"][0]["dwell_seconds"] = 0
        res = client.post(BATCH_URL, body, format="json")
        assert res.status_code == 201
        observation = TripObservation.objects.get(client_uuid="a4")
        assert observation.entered_at == observation.observed_at
        assert observation.dwell_seconds == 0
        assert observation.dwell_confirmed is False  # 과거 체류 참고값이며 도착 거절이 아니다.

    def test_gap_is_capped_like_dwell(self, client, event):
        ok = OBSERVED - timedelta(seconds=MAX_DWELL_SECONDS)
        too_old = OBSERVED - timedelta(seconds=MAX_DWELL_SECONDS + 1)

        # 앱은 체류를 같은 차이의 초 내림으로 보낸다. 1초 안쪽 넘침은 체류처럼 통과한다.
        just_over = OBSERVED - timedelta(seconds=MAX_DWELL_SECONDS, milliseconds=500)

        assert client.post(BATCH_URL, arrival(event, "a5", entered=ok), format="json").status_code == 201
        assert client.post(BATCH_URL, arrival(event, "a9", entered=just_over), format="json").status_code == 201
        assert client.post(BATCH_URL, arrival(event, "a6", entered=too_old), format="json").status_code == 400

    def test_resend_keeps_the_first_value(self, client, event):
        """재전송은 "저장됐는지 모르겠다" 는 뜻이다. 첫 값을 덮어쓰지 않는다."""
        first = OBSERVED - timedelta(seconds=130)
        client.post(BATCH_URL, arrival(event, "a7", entered=first), format="json")
        res = client.post(
            BATCH_URL, arrival(event, "a7", entered=OBSERVED - timedelta(seconds=300)), format="json"
        )

        assert res.status_code == 200
        assert res.data["duplicated"] == 1
        assert TripObservation.objects.get(client_uuid="a7").entered_at == first

    def test_list_exposes_it(self, client, event):
        entered = OBSERVED - timedelta(seconds=140)
        client.post(BATCH_URL, arrival(event, "a8", entered=entered), format="json")
        client.post(BATCH_URL, arrival(event, "d2", kind="depart"), format="json")

        rows = {row["kind"]: row for row in client.get(LIST_URL).data["results"]}
        assert rows["arrive"]["entered_at"] is not None
        assert rows["depart"]["entered_at"] is None
