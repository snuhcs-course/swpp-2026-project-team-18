# Partly AI-generated (Claude)
"""블록 기록이 블록 신고 범위에서 크게 벗어나면 알람·학습에서 뺀다(`prep_observations`).

GPS 출발이 잡힌 아침은 출발 뒤에 누른 블록 기록을 이미 거른다. 출발이 아예 잡히지 않은
아침에 "마침" 을 한참 뒤에 누르면(샤워 10~20분인데 95분) 그 값이 그대로 학습됐다. 기준은
GPS 준비 시간과 같은 폭으로, 신고 최소의 0.5배 ~ 신고 최대의 1.5배다(사용자 10-29에 0.25~4배에서
좁혔다).
"""

from __future__ import annotations

from datetime import timedelta

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.planning import prep_observations, services
from apps.planning.models import AlarmPlan
from apps.prediction import learning
from apps.reports import services as reports
from apps.routines.models import BlockObservation, RoutineBlock

pytestmark = pytest.mark.django_db


@pytest.fixture
def user(django_user_model):
    u = django_user_model.objects.create_user(
        email="block-range@example.com", password="Lm6-cedar-harbor-18", nickname="범위"
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.onboarding_prep_min = 30
    p.save()
    return u


def block(user, name="샤워", lo=10, hi=20, **kw) -> RoutineBlock:
    return RoutineBlock.objects.create(
        user=user, name=name, default_min_minutes=lo, default_max_minutes=hi, **kw
    )


def record(user, blk, minutes: float, *, event=None, day=None, slack=None, parallel=False):
    return BlockObservation.objects.create(
        user=user, block=blk, event=event, observed_on=day or timezone.localdate(),
        duration_minutes=minutes, slack_minutes=slack, was_parallel=parallel,
        client_uuid=f"r-{blk.pk}-{minutes}-{day}-{slack}",
        client_recorded_at=timezone.now(),
    )


def kept(qs=None) -> list[float]:
    rows = prep_observations.usable_block_observations(qs or BlockObservation.objects.all())
    return sorted(rows.values_list("duration_minutes", flat=True))


class TestRange:
    def test_bounds_are_half_of_min_and_one_and_a_half_times_max(self, user):
        shower = block(user)  # 10~20분 → 5~30분
        for minutes in (4.9, 5, 15, 30, 30.1):
            record(user, shower, minutes)
        assert kept() == [5, 15, 30]

    def test_forgot_to_press_and_pressed_right_away_are_dropped(self, user):
        shower = block(user)
        record(user, shower, 95)  # 누르는 걸 잊었다가 나가기 직전에 눌렀다
        record(user, shower, 0.3)  # 안 한 블록을 바로 눌렀다
        record(user, shower, 14)
        assert kept() == [14]

    def test_zero_minimum_has_no_lower_bound(self, user):
        coffee = block(user, "커피", lo=0, hi=10)
        record(user, coffee, 0)
        record(user, coffee, 15)
        record(user, coffee, 16)
        assert kept() == [0, 15]

    def test_zero_maximum_still_keeps_a_few_minutes(self, user):
        """신고가 0~0분이어도 상한이 0 이 되지 않는다(최소 1분 기준, 1.5분까지)."""
        pill = block(user, "약", lo=0, hi=0)
        for minutes in (0, 1, 1.5, 2):
            record(user, pill, minutes)
        assert kept() == [0, 1, 1.5]

    def test_each_record_is_judged_by_its_own_block(self, user):
        shower = block(user)
        breakfast = block(user, "아침", lo=20, hi=40)
        record(user, shower, 45)  # 샤워 상한 30분 밖
        record(user, breakfast, 45)  # 아침 상한 60분 안
        assert kept() == [45]
        assert prep_observations.usable_block_observations(
            BlockObservation.objects.all()
        ).get().block == breakfast

    def test_widening_the_block_brings_records_back(self, user):
        """기준은 지금 신고 범위다. 범위를 고치면 그 기준으로 다시 본다."""
        breakfast = block(user, "아침", lo=5, hi=10)
        record(user, breakfast, 20)
        assert kept() == []
        RoutineBlock.objects.filter(pk=breakfast.pk).update(default_max_minutes=15)
        assert kept() == [20]


class TestWhereItApplies:
    def test_alarm_inputs_ignore_out_of_range_records(self, user):
        shower = block(user)
        for minutes in (12, 14, 95):
            record(user, shower, minutes)
        mean, _sd, count = services.prep_inputs(user.pk)[0][shower.pk]
        assert (mean, count) == (pytest.approx(13.0), 2)

    def test_learning_ignores_out_of_range_records(self, user):
        shower = block(user)
        today = timezone.localdate()
        record(user, shower, 12, day=today)
        record(user, shower, 14, day=today - timedelta(days=1))
        record(user, shower, 95, day=today - timedelta(days=2))
        assert learning.block_stats(user=user)["샤워"]["n"] == 2
        assert learning.prep_total_stats(user=user) == {
            "prep_mean": 13.0, "prep_sd": pytest.approx(1.41, abs=0.01), "n": 2,
        }

    def test_slack_coefficient_ignores_out_of_range_records(self, user):
        shower = block(user, lo=10, hi=30)
        for index in range(8):
            record(user, shower, 10 + index, slack=float(index), day=timezone.localdate() - timedelta(days=index))
        coef = learning.estimate_slack_coef(user=user)
        record(user, shower, 200, slack=50.0, day=timezone.localdate() - timedelta(days=9))
        assert learning.estimate_slack_coef(user=user) == coef

    def test_report_still_shows_what_the_user_pressed(self, user):
        """리포트는 누른 기록을 그대로 보여 준다. 알람과 학습만 거른다."""
        place = Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)
        monday, _ = reports.week_bounds(timezone.localdate() - timedelta(weeks=1))
        start = monday + timedelta(hours=9)
        event = Event.objects.create(user=user, place=place, title="수업", start_at=start)
        AlarmPlan.objects.create(
            user=user, event=event, status=AlarmPlan.Status.OK,
            alarm_at=start - timedelta(minutes=70), depart_by=start - timedelta(minutes=30),
            arrive_at=start - timedelta(minutes=10), prep_minutes=20, travel_minutes=20,
            buffer_minutes=10, tau_used=0.9,
        )
        record(user, block(user), 95, event=event)
        row = reports.weekly(user, anchor=monday.date())["outcomes"][0]
        assert row["actual"]["prep_minutes"] == 95.0


@pytest.mark.integration
def test_upload_is_not_rejected(user):
    """업로드 때 거절하지 않는다. 범위는 사용자가 나중에 고칠 수 있다."""
    shower = block(user)
    client = APIClient()
    client.force_authenticate(user)
    response = client.post("/api/routines/observations/batch", {"observations": [{
        "block": shower.pk, "observed_on": timezone.localdate().isoformat(),
        "duration_minutes": 300, "was_parallel": False, "client_uuid": "late-press",
        "client_recorded_at": timezone.now().isoformat(),
    }]}, format="json")
    assert response.status_code == 201, response.data
    assert BlockObservation.objects.get().duration_minutes == 300
    assert kept() == []
