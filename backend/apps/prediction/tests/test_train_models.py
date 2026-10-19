"""`train_models` 커맨드 테스트.

학습 함수 자체는 `test_learning.py` 가 본다. 여기서는 **매일 도는 운영 진입점**이
옵션대로 저장하고, 저장하지 말아야 할 때 저장하지 않는지를 본다. 이 커맨드가
틀리면 학습 함수가 맞아도 아티팩트가 안 바뀌거나, 빈 아티팩트가 활성으로 올라가
앱이 "학습된 값" 으로 오해한다.
"""

from __future__ import annotations

import json
from datetime import date, datetime, timedelta
from io import StringIO

import pytest
from django.core.management import call_command

from apps.prediction import learning
from apps.prediction.models import ModelArtifact
from apps.prediction.tests.test_learning import KST, add_block_obs, make_block, make_trip
from apps.routing.models import RouteCorrection

pytestmark = pytest.mark.django_db


@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(
        email="train@example.com", password="Nb3-copper-lantern-77", nickname="학습"
    )


@pytest.fixture
def place():
    from apps.events.models import Place

    return Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)


def run(*args) -> str:
    out = StringIO()
    call_command("train_models", *args, stdout=out)
    return out.getvalue()


def add_days(user, name, days, minutes=15.0):
    """`days` 일치 블록 관측. 하루에 한 건이다."""
    block = make_block(user, name)
    for i in range(days):
        add_block_obs(user, block, date(2026, 9, 1) + timedelta(days=i), minutes + i % 3,
                      uid=f"{user.pk}-{name}-{i}")
    return block


def add_trips(user, place, n, ratio=1.2):
    for i in range(n):
        make_trip(
            user, place, predicted=20, actual_minutes=20 * ratio,
            depart_at=datetime(2026, 9, 21, 8, 0, tzinfo=KST) + timedelta(days=i * 7),
        )


class TestNoData:
    def test_empty_database_saves_nothing(self):
        out = run()

        assert "표본 0건 → 보정 0행" in out
        assert "관측이 없어 저장하지 않았다" in out
        assert not ModelArtifact.objects.exists()
        assert not RouteCorrection.objects.exists()

    def test_says_how_to_build_personal_artifacts(self):
        assert "--users 로 만든다" in run()


class TestGlobalArtifact:
    def test_saves_an_active_global_artifact(self, user):
        add_days(user, "샤워", 3)

        out = run()

        artifact = ModelArtifact.objects.get(user=None, is_active=True)
        assert artifact.sample_count == 3
        assert artifact.payload["blocks"]["샤워"]["n"] == 3
        assert f"저장: {artifact.version}" in out
        assert "샤워" in out

    def test_rerun_keeps_one_active_global_artifact(self, user):
        add_days(user, "샤워", 3)
        run()
        run()

        assert ModelArtifact.objects.filter(user=None, is_active=True).count() == 1

    def test_show_payload_prints_the_artifact_as_json(self, user):
        add_days(user, "샤워", 2)

        out = run("--show-payload")

        start = out.index("{")
        end = out.rindex("}") + 1
        payload = json.loads(out[start:end])
        assert payload["scope"] == "global"
        assert payload["blocks"]["샤워"]["n"] == 2


class TestRouteCorrections:
    def test_trips_become_route_corrections(self, user, place):
        add_trips(user, place, learning.MIN_ROUTE_SAMPLES)

        out = run()

        assert f"표본 {learning.MIN_ROUTE_SAMPLES}건" in out
        row = RouteCorrection.objects.get(
            route_signature="transit:2호선", hour_bucket=-1, mode="transit",
            weekday_type="weekday",
        )
        assert row.factor == pytest.approx(1.2, abs=1e-3)

    def test_dropped_trips_are_reported_with_reason(self, user, place):
        """조용히 버리면 "표본이 왜 이렇게 적지" 를 추적할 수 없다."""
        make_trip(user, place, predicted=20, actual_minutes=25, skip_arrive=True,
                  depart_at=datetime(2026, 9, 21, 8, 0, tzinfo=KST))

        assert "버림: 쌍 미완성 1건" in run()

    def test_routes_only_skips_the_artifact(self, user, place):
        add_trips(user, place, learning.MIN_ROUTE_SAMPLES)
        add_days(user, "샤워", 3)

        out = run("--routes-only")

        assert RouteCorrection.objects.exists()
        assert not ModelArtifact.objects.exists()
        assert "경로 보정만 갱신했다" in out
        assert "[2]" not in out


class TestDryRun:
    def test_saves_nothing(self, user, place):
        add_trips(user, place, learning.MIN_ROUTE_SAMPLES)
        add_days(user, "샤워", learning.MIN_PERSONAL_OBSERVATIONS)

        out = run("--dry-run", "--users")

        assert not RouteCorrection.objects.exists()
        assert not ModelArtifact.objects.exists()
        assert f"이동 표본 {learning.MIN_ROUTE_SAMPLES}건" in out
        assert "dry-run 이라 저장하지 않았다" in out
        # 개인 아티팩트도 계산은 해서 보여 준다.
        assert user.email in out

    def test_reports_dropped_trips(self, user, place):
        make_trip(user, place, predicted=20, actual_minutes=25, accuracy=500.0,
                  depart_at=datetime(2026, 9, 21, 8, 0, tzinfo=KST))

        out = run("--dry-run")

        assert "버림: 정확도 초과 2건" in out


class TestPersonalArtifacts:
    def test_quiet_batch_output_contains_no_block_names_or_email(self, user):
        add_days(user, "PRIVATE_BLOCK_LABEL", learning.MIN_PERSONAL_OBSERVATIONS)

        out = run("--users", "--verbosity", "0")

        assert "PRIVATE_BLOCK_LABEL" not in out
        assert user.email not in out
        assert "개인 아티팩트 1개" in out

    def test_only_users_with_enough_observations_get_one(self, user, django_user_model):
        add_days(user, "샤워", learning.MIN_PERSONAL_OBSERVATIONS)
        few = django_user_model.objects.create_user(
            email="few@example.com", password="Nb3-copper-lantern-77", nickname="적음"
        )
        add_days(few, "샤워", learning.MIN_PERSONAL_OBSERVATIONS - 1)

        out = run("--users")

        personal = ModelArtifact.objects.get(user=user, is_active=True)
        assert personal.payload["scope"] == "user"
        assert not ModelArtifact.objects.filter(user=few).exists()
        assert "개인 아티팩트 1개" in out
        # 전역도 함께 만든다.
        assert ModelArtifact.objects.filter(user=None, is_active=True).exists()

    def test_says_so_when_nobody_qualifies(self, user):
        add_days(user, "샤워", 2)

        out = run("--users")

        assert f"관측 {learning.MIN_PERSONAL_OBSERVATIONS}건 이상인 사용자가 없다" in out
        assert not ModelArtifact.objects.filter(user__isnull=False).exists()
