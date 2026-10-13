# Partly AI-generated (Claude)
"""주간 줄글 요약 테스트. **키·네트워크 없이 돌고 진짜 LLM 을 부르지 않는다.**

성공 경로는 `fake_llm` 의 가짜 opener 로만 본다(conftest 가 진짜 opener 를 막는다).

지키는 것
    - 보내는 집계값에 제목·날짜·시각·id 가 없다          → N19
    - 템플릿은 규칙대로 문장을 만들고 검사를 통과한다     → 지어낸 숫자 없음
    - 검사가 지어낸 숫자·날짜·"학습"·URL 을 거절한다
    - 키가 없거나 실패·시간 초과·검사 실패면 템플릿이다  → 화면이 막히지 않음
    - 같은 수치면 다시 부르지 않고, 수치가 바뀌면 다시 만든다
    - 사용자당 하루 상한을 넘으면 부르지 않는다          → 학교 키 예산
"""

from __future__ import annotations

import io
import json
import statistics
import urllib.error
from datetime import timedelta

import pytest
from django.core.cache import cache
from django.core.management import call_command
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events.models import Event, Place
from apps.observations.models import TripObservation
from apps.planning.models import AlarmPlan
from apps.reports import llm, summary
from apps.reports.management.commands.probe_report_summary import SAMPLE_FACTS
from apps.reports.models import WeeklySummary
from apps.reports.services import half_up, week_bounds

SECRET_TITLE = "비밀 회의"
EVENT_ID = 987654


# ---------------------------------------------------------------------------
# 합성 리포트 — services.weekly 응답과 같은 모양
# ---------------------------------------------------------------------------


def monday_iso(weeks_ago: int = 3) -> str:
    start, _ = week_bounds(timezone.localdate() - timedelta(weeks=weeks_ago))
    return start.date().isoformat()


def no_deviation() -> dict:
    return {"prep_over": None, "depart_late": None, "travel_over": None}


def row(result: str, slack: float | None, *, weekday: int = 0, cause=None, deviation=None,
        alarm_on: bool = True) -> dict:
    return {
        "event": EVENT_ID,
        "title": SECRET_TITLE,
        "start_at": "2026-10-05T00:00:00Z",
        "date": "2026-10-05",
        "weekday": weekday,
        "result": result,
        "slack_minutes": slack,
        "on_time_probability": 90,
        "tau": 0.9,
        "tau_bucket_lower": 0.9,
        "prep_applies": True,
        "alarm_on": alarm_on,
        "planned": {
            "alarm_at": "2026-10-04T22:40:00Z",
            "depart_by": "2026-10-04T23:08:00Z",
            "arrive_at": "2026-10-04T23:50:00Z",
            "prep_minutes": 28,
            "travel_minutes": 42,
            "buffer_minutes": 10,
        },
        "actual": {
            "depart_at": "2026-10-04T23:15:00Z",
            "arrive_at": "2026-10-05T00:10:00Z",
            "prep_minutes": 34.0,
            "travel_minutes": 55.0,
        },
        "deviation": deviation or no_deviation(),
        "cause": cause,
        "unmeasured": [],
    }


def on_time(slack: float, weekday: int = 0, **kw) -> dict:
    return row("on_time", slack, weekday=weekday, **kw)


def late(slack: float, cause: str, weekday: int = 0, deviation=None, **kw) -> dict:
    return row("late", slack, weekday=weekday, cause=cause, deviation=deviation, **kw)


def unseen(weekday: int = 0, **kw) -> dict:
    return row("unobserved", None, weekday=weekday, **kw)


def stat(count: int = 0, mean: float | None = None, sd: float | None = None) -> dict:
    return {"count": count, "mean_minutes": mean, "sd_minutes": sd}


def make_report(rows, *, week_complete=True, target_rate=0.9, previous=None, deviation=None,
                weeks_ago: int = 3) -> dict:
    arrived = [r for r in rows if r["result"] != "unobserved"]
    slacks = [r["slack_minutes"] for r in arrived]
    punctual = [r for r in arrived if r["result"] == "on_time"]
    rate = round(len(punctual) / len(arrived), 4) if arrived else None
    target = target_rate if arrived else None
    met = None
    if rate is not None and target is not None and len(arrived) >= 3:
        met = half_up(rate * 100) >= half_up(target * 100)
    return {
        "week_start": monday_iso(weeks_ago),
        "event_count": len(rows),
        "arrived_count": len(arrived),
        "unobserved_count": len(rows) - len(arrived),
        "on_time_count": len(punctual),
        "late_count": len(arrived) - len(punctual),
        "on_time_rate": rate,
        "median_slack_minutes": round(statistics.median(slacks), 1) if slacks else None,
        "tightest_slack_minutes": min(slacks) if slacks else None,
        "week_complete": week_complete,
        "has_next_week": week_complete,
        "target_rate": target,
        "target_met": met,
        "previous_week": previous,
        "deviation": deviation or {
            "prep_over": stat(), "depart_late": stat(), "travel_over": stat(),
        },
        "learning": {"plan_count": len(rows), "prep_from_records": 0, "travel_from_records": 0},
        "outcomes": rows,
    }


def example_report(**kw) -> dict:
    """설계 문서의 예시 주. 일정 7 · 도착 기록 6 · 정시 5 · 화요일 10분 지각, 지난주 3/5."""
    rows = [
        on_time(12.0, 0),
        on_time(15.0, 0),
        late(-10.0, "travel_over", 1,
             deviation={"prep_over": 6.0, "depart_late": 7.0, "travel_over": 13.0}),
        on_time(3.0, 2),
        on_time(9.0, 3),
        on_time(9.0, 3),
        unseen(4),
    ]
    previous = {
        "week_start": monday_iso(4), "event_count": 5, "arrived_count": 5,
        "on_time_count": 3, "on_time_rate": 0.6, "on_time_rate_change_pp": 23,
    }
    deviation = {
        "prep_over": stat(2, 4.0, 1.0),
        "depart_late": stat(5, 3.0, 2.0),
        "travel_over": stat(5, -1.0, 7.0),
    }
    return make_report(rows, previous=previous, deviation=deviation, **kw)


EXAMPLE_TEXT = (
    "이 주 일정 7건 중 도착을 기록한 6번에서 5번 정시 도착해 정시율 83%로 목표 90%에 "
    "못 미쳤음. 지각 1번은 이동이 계획보다 오래 걸린 것 때문이었고 화요일에 10분 늦었음. "
    "지난주 60%보다 23%p 올랐음. 이동이 계획보다 길어지는 날이 있었으니 다른 경로를 골라 "
    "보거나 몇 분 일찍 나서 볼 것."
)


def text_for(report: dict) -> str:
    return summary.render_template(summary.build_facts(report))


# ---------------------------------------------------------------------------
# 집계값
# ---------------------------------------------------------------------------


class TestFacts:

    def test_keys_are_fixed(self):
        assert tuple(summary.build_facts(example_report())) == summary.FACT_KEYS

    def test_example_week(self):
        """설계 예시 그대로. 확인 명령의 표본(SAMPLE_FACTS)과도 같다."""
        assert summary.build_facts(example_report()) == SAMPLE_FACTS

    def test_no_titles_dates_times_or_ids(self):
        """N19. 보내는 값에 제목·날짜·시각·일정 id 가 없다."""
        dumped = json.dumps(summary.build_facts(example_report()), ensure_ascii=False)
        for secret in (SECRET_TITLE, "2026-10", "T00:", "22:40", "23:15", '"event"',
                       str(EVENT_ID), "start_at", "title"):
            assert secret not in dumped, secret

    def test_rounding_matches_the_app(self):
        """반올림은 절댓값 0.5 올림이다. 5/8 → 63%, 목표 0.925 → 93%, −22.5 → −23."""
        rows = [on_time(5.0, d) for d in range(5)] + [late(-3.0, "unknown", d) for d in range(3)]
        report = make_report(rows, target_rate=0.925, previous={
            "on_time_rate": 0.85, "on_time_rate_change_pp": -23,
        })
        facts = summary.build_facts(report)
        assert facts["on_time_pct"] == 63
        assert facts["target_pct"] == 93
        assert facts["prev_on_time_pct"] == 85
        assert facts["on_time_change_pp"] == -23

    def test_lateness_under_a_minute_counts_as_one(self):
        facts = summary.build_facts(make_report([late(-0.4, "unknown"), on_time(3.0, 1)]))
        assert facts["late_max_min"] == 1
        assert facts["tightest_slack_min"] == -1

    def test_top_cause_ties_prefer_travel(self):
        rows = [
            late(-5.0, "depart_late", 0, deviation={**no_deviation(), "depart_late": 4.0}),
            late(-6.0, "travel_over", 1, deviation={**no_deviation(), "travel_over": 9.0}),
        ]
        facts = summary.build_facts(make_report(rows))
        assert facts["late_causes"] == {"늦은 출발": 1, "이동 초과": 1}
        assert facts["top_cause"] == "이동 초과"
        assert facts["top_cause_count"] == 1
        assert facts["top_cause_mean_min"] == 9

    def test_unmeasured_causes_do_not_win(self):
        rows = [late(-5.0, "unknown", 0), late(-6.0, "unknown", 1),
                late(-2.0, "prep_over", 2, deviation={**no_deviation(), "prep_over": 3.0})]
        facts = summary.build_facts(make_report(rows))
        assert facts["top_cause"] == "준비 초과"
        assert facts["late_causes"] == {"원인 측정 못 함": 2, "준비 초과": 1}

    def test_alarm_counts(self):
        rows = [on_time(5.0), unseen(1), unseen(2, alarm_on=False)]
        facts = summary.build_facts(make_report(rows))
        assert facts["alarm_events"] == 2
        assert facts["unobserved_with_alarm"] == 1


# ---------------------------------------------------------------------------
# 템플릿
# ---------------------------------------------------------------------------


def three_arrivals_with_late(cause: str, deviation: dict) -> dict:
    return make_report([
        on_time(6.0, 0),
        on_time(9.0, 1),
        late(-5.0, cause, 2, deviation={**no_deviation(), **deviation}),
    ])


TEMPLATE_CASES = {
    "example": (example_report(), EXAMPLE_TEXT),
    "empty": (
        make_report([]),
        "이 주에는 지난 일정이 없음. 일정을 추가해 두면 다음 주부터 알람과 함께 기록이 쌓임.",
    ),
    "no_arrivals": (
        make_report([unseen(0), unseen(2), unseen(4)]),
        "이 주 일정 3건 모두 도착 기록이 없어 정시 여부를 채점하지 못했음. "
        "위치 권한을 켜 두고 알람을 해제한 뒤 이동하면 출발·도착이 자동으로 기록됨.",
    ),
    "no_arrivals_without_alarms": (
        make_report([unseen(0, alarm_on=False), unseen(1, alarm_on=False)]),
        "이 주 일정 2건 모두 도착 기록이 없어 정시 여부를 채점하지 못했음. "
        "출발·도착은 알람을 해제한 뒤에만 기록되니 기록할 일정은 알람을 켜 둘 것.",
    ),
    "thin": (
        make_report([on_time(8.0, 0), late(-4.0, "unknown", 1)]),
        "이 주 도착을 기록한 2번 중 1번 정시 도착했지만 기록이 적어 비율로 판단하기는 이름. "
        "지각 1번은 기록이 부족해 원인을 나누지 못했음. "
        "원인을 나누려면 출발 시각이 필요하니 집을 나서기 전에 알람을 해제해 둘 것.",
    ),
    "no_late_tight": (
        make_report([on_time(3.2, 0), on_time(8.0, 1), on_time(12.0, 2)]),
        "이 주 도착을 기록한 3번 중 3번 정시 도착해 정시율 100%로 목표 90%를 달성했음. "
        "지각은 없었지만 가장 아슬아슬한 날은 3분 전에 도착했음. "
        "아슬아슬한 날이 있었으니 지금처럼 출발 시각을 지키는 것이 좋음.",
    ),
    "no_late_exact": (
        make_report([on_time(0.2, 0), on_time(8.0, 1), on_time(12.0, 2)]),
        "이 주 도착을 기록한 3번 중 3번 정시 도착해 정시율 100%로 목표 90%를 달성했음. "
        "지각은 없었지만 가장 아슬아슬한 날은 일정 시작에 딱 맞춰 도착했음. "
        "아슬아슬한 날이 있었으니 지금처럼 출발 시각을 지키는 것이 좋음.",
    ),
    "no_late_comfortable": (
        make_report([on_time(7.0, 0), on_time(8.0, 1), on_time(12.0, 2)]),
        "이 주 도착을 기록한 3번 중 3번 정시 도착해 정시율 100%로 목표 90%를 달성했음. "
        "지각은 없었음. 지금 설정을 그대로 유지해도 좋음.",
    ),
    "no_target": (
        make_report([on_time(7.0, 0), on_time(8.0, 1), on_time(12.0, 2)], target_rate=None),
        "이 주 도착을 기록한 3번 중 3번 정시 도착해 정시율 100%였음. "
        "지각은 없었음. 지금 설정을 그대로 유지해도 좋음.",
    ),
    "cause_travel": (
        three_arrivals_with_late("travel_over", {"travel_over": 8.0, "depart_late": 0.0}),
        "이 주 도착을 기록한 3번 중 2번 정시 도착해 정시율 67%로 목표 90%에 못 미쳤음. "
        "지각 1번은 이동이 계획보다 오래 걸린 것 때문이었고 수요일에 5분 늦었음. "
        "이동이 계획보다 길어지는 날이 있었으니 다른 경로를 골라 보거나 몇 분 일찍 나서 볼 것.",
    ),
    "cause_depart": (
        three_arrivals_with_late("depart_late", {"depart_late": 6.0}),
        "이 주 도착을 기록한 3번 중 2번 정시 도착해 정시율 67%로 목표 90%에 못 미쳤음. "
        "지각 1번은 집에서 늦게 나선 것 때문이었고 수요일에 5분 늦었음. "
        "다음 주에는 지금보다 6분 먼저 문을 나서 볼 것.",
    ),
    "cause_depart_small": (
        three_arrivals_with_late("depart_late", {"depart_late": 0.4}),
        "이 주 도착을 기록한 3번 중 2번 정시 도착해 정시율 67%로 목표 90%에 못 미쳤음. "
        "지각 1번은 집에서 늦게 나선 것 때문이었고 수요일에 5분 늦었음. "
        "다음 주에는 알람이 정한 출발 시각을 지켜 볼 것.",
    ),
    "cause_prep": (
        three_arrivals_with_late("prep_over", {"prep_over": 7.0}),
        "이 주 도착을 기록한 3번 중 2번 정시 도착해 정시율 67%로 목표 90%에 못 미쳤음. "
        "지각 1번은 준비가 길어진 것 때문이었고 수요일에 5분 늦었음. "
        "오래 걸린 준비 항목의 시간 범위를 루틴에서 늘려 두면 알람이 그만큼 앞당겨짐.",
    ),
    "cause_tight": (
        three_arrivals_with_late("plan_too_tight", {"prep_over": -1.0, "depart_late": 0.0,
                                                    "travel_over": -2.0}),
        "이 주 도착을 기록한 3번 중 2번 정시 도착해 정시율 67%로 목표 90%에 못 미쳤음. "
        "지각 1번은 계획의 여유가 부족했던 것 때문이었고 수요일에 5분 늦었음. "
        "계획대로 움직여도 늦은 날이 있었으니 평소 준비 시간을 넉넉히 잡아 알람을 앞당겨 볼 것.",
    ),
    "several_lates_mixed": (
        make_report([
            late(-5.0, "travel_over", 0, deviation={**no_deviation(), "travel_over": 6.0}),
            late(-12.0, "travel_over", 1, deviation={**no_deviation(), "travel_over": 14.0}),
            late(-3.0, "depart_late", 2, deviation={**no_deviation(), "depart_late": 3.0}),
            on_time(4.0, 3),
        ]),
        "이 주 도착을 기록한 4번 중 1번 정시 도착해 정시율 25%로 목표 90%에 못 미쳤음. "
        "지각 3번 중 2번은 이동이 계획보다 오래 걸린 것 때문이었고 가장 많이 늦은 날은 "
        "화요일 12분이었음. "
        "이동이 계획보다 길어지는 날이 있었으니 다른 경로를 골라 보거나 몇 분 일찍 나서 볼 것.",
    ),
    "several_lates_same": (
        make_report([
            late(-5.0, "travel_over", 0, deviation={**no_deviation(), "travel_over": 6.0}),
            late(-12.0, "travel_over", 1, deviation={**no_deviation(), "travel_over": 14.0}),
            on_time(4.0, 3),
        ]),
        "이 주 도착을 기록한 3번 중 1번 정시 도착해 정시율 33%로 목표 90%에 못 미쳤음. "
        "지각 2번은 모두 이동이 계획보다 오래 걸린 것 때문이었고 가장 많이 늦은 날은 "
        "화요일 12분이었음. "
        "이동이 계획보다 길어지는 날이 있었으니 다른 경로를 골라 보거나 몇 분 일찍 나서 볼 것.",
    ),
    "in_progress": (
        make_report([on_time(5.0, 0)], week_complete=False),
        "진행 중인 주라 지금까지 기록만 요약함. "
        "이 주 도착을 기록한 1번 중 1번 정시 도착했지만 기록이 적어 비율로 판단하기는 이름. "
        "지각은 없었음. 지금 설정을 그대로 유지해도 좋음.",
    ),
    "trend_down": (
        make_report(
            [on_time(5.0, 0), on_time(6.0, 1), late(-3.0, "unknown", 2), late(-4.0, "unknown", 3)],
            previous={"on_time_rate": 0.725, "on_time_rate_change_pp": -23},
        ),
        "이 주 도착을 기록한 4번 중 2번 정시 도착해 정시율 50%로 목표 90%에 못 미쳤음. "
        "지각 2번은 기록이 부족해 원인을 나누지 못했음. 지난주 73%보다 23%p 내렸음. "
        "원인을 나누려면 출발 시각이 필요하니 집을 나서기 전에 알람을 해제해 둘 것.",
    ),
    "trend_flat": (
        make_report(
            [on_time(7.0, 0), on_time(8.0, 1), on_time(12.0, 2)],
            previous={"on_time_rate": 1.0, "on_time_rate_change_pp": 0},
        ),
        "이 주 도착을 기록한 3번 중 3번 정시 도착해 정시율 100%로 목표 90%를 달성했음. "
        "지각은 없었음. 지난주와 정시율이 같았음. 지금 설정을 그대로 유지해도 좋음.",
    ),
    "travel_mean_wins_ties": (
        make_report(
            [on_time(7.0, 0), on_time(8.0, 1), on_time(12.0, 2)],
            deviation={"prep_over": stat(), "depart_late": stat(3, 4.0, 1.0),
                       "travel_over": stat(3, 4.0, 2.0)},
        ),
        "이 주 도착을 기록한 3번 중 3번 정시 도착해 정시율 100%로 목표 90%를 달성했음. "
        "지각은 없었음. 이동은 계획보다 평균 4분 더 걸렸음(3번 기록). "
        "지금 설정을 그대로 유지해도 좋음.",
    ),
    "depart_mean": (
        make_report(
            [on_time(7.0, 0), on_time(8.0, 1), on_time(12.0, 2)],
            deviation={"prep_over": stat(2, 3.0, 0.0), "depart_late": stat(3, 5.0, 1.0),
                       "travel_over": stat(3, 3.0, 2.0)},
        ),
        "이 주 도착을 기록한 3번 중 3번 정시 도착해 정시율 100%로 목표 90%를 달성했음. "
        "지각은 없었음. 출발은 계획보다 평균 5분 늦었음(3번 기록). "
        "지금 설정을 그대로 유지해도 좋음.",
    ),
    "travel_spread": (
        make_report(
            [on_time(7.0, 0), on_time(8.0, 1), on_time(12.0, 2)],
            deviation={"prep_over": stat(), "depart_late": stat(),
                       "travel_over": stat(3, 1.0, 6.0)},
        ),
        "이 주 도착을 기록한 3번 중 3번 정시 도착해 정시율 100%로 목표 90%를 달성했음. "
        "지각은 없었음. 이동 시간 편차가 6분으로 큰 편이었음. "
        "지금 설정을 그대로 유지해도 좋음.",
    ),
}


class TestTemplate:

    @pytest.mark.parametrize("name", sorted(TEMPLATE_CASES))
    def test_sentences(self, name):
        report, expected = TEMPLATE_CASES[name]
        assert text_for(report) == expected

    @pytest.mark.parametrize("name", sorted(TEMPLATE_CASES))
    def test_every_template_passes_the_check(self, name):
        """템플릿도 LLM 문장과 같은 검사를 통과해야 한다 — 지어낸 숫자가 없다."""
        report, _ = TEMPLATE_CASES[name]
        facts = summary.build_facts(report)
        text = summary.render_template(facts)
        assert summary.check_text(text, facts) is None, text
        assert "학습" not in text
        assert 2 <= text.count(".") <= 5, text

    def test_sample_facts_template_passes(self):
        assert summary.check_text(summary.render_template(SAMPLE_FACTS), SAMPLE_FACTS) is None


# ---------------------------------------------------------------------------
# 검사
# ---------------------------------------------------------------------------


class TestCheckText:

    def facts(self):
        return dict(SAMPLE_FACTS)

    def test_accepts_the_example(self):
        assert summary.check_text(EXAMPLE_TEXT, self.facts()) is None

    def test_rejects_an_invented_number(self):
        text = EXAMPLE_TEXT.replace("83%", "84%")
        assert summary.check_text(text, self.facts()) == "invented_number:84"

    def test_rejects_decimals_not_in_facts(self):
        text = EXAMPLE_TEXT.replace("83%", "83.3%")
        assert summary.check_text(text, self.facts()) == "invented_number:83.3"

    @pytest.mark.parametrize("extra", ["아직 학습 중임.", "학습하는 중임.", "학습이 진행 중임."])
    def test_rejects_learning_words(self, extra):
        assert summary.check_text(f"{EXAMPLE_TEXT} {extra}", self.facts()) == "learning_word"

    def test_rejects_urls(self):
        text = f"{EXAMPLE_TEXT} 자세한 내용은 https://example.com 참고."
        assert summary.check_text(text, self.facts()) == "url"

    @pytest.mark.parametrize("swap", [("지각 1번은", "지각 한 번은"), ("지각 1번은", "지각 두번은")])
    def test_rejects_number_words(self, swap):
        text = EXAMPLE_TEXT.replace(*swap)
        assert summary.check_text(text, self.facts()) == "number_word"

    def test_number_words_inside_a_word_are_fine(self):
        """"충분한 시간" 의 "한 시간" 은 수가 아니다."""
        text = f"{EXAMPLE_TEXT} 충분한 시간을 두고 나서면 됨."
        assert summary.check_text(text, self.facts()) is None

    # Partly AI-generated (Claude)
    def test_prompt_names_the_no_late_phrase_the_check_accepts(self):
        """"한 번도 늦지 않았음" 은 number_word 로 떨어진다(지각이 있던 주에 쓰면 거짓이라
        풀어 주지 않는다). 그래서 프롬프트가 지각 0 표현을 정해 준다 — 그 말은 통과한다."""
        facts = summary.build_facts(TEMPLATE_CASES["no_late_comfortable"][0])
        text = summary.render_template(facts)
        assert "지각은 없었음" in text
        assert "'지각은 없었음'" in summary.SYSTEM_PROMPT
        assert "'한 번도'" in summary.SYSTEM_PROMPT
        assert summary.check_text(text, facts) is None
        idiom = text.replace("지각은 없었음", "한 번도 늦지 않았음")
        assert summary.check_text(idiom, facts) == "number_word"

    @pytest.mark.parametrize("extra", ["10월 7일 기록임.", "9시 수업이었음.", "8:15 에 나섰음."])
    def test_rejects_dates_and_times(self, extra):
        """facts 에 10·7·9 가 있어도 날짜·시각을 만들면 안 된다."""
        assert summary.check_text(f"{EXAMPLE_TEXT} {extra}", self.facts()) == "date_or_time"

    def test_rejects_length(self):
        assert summary.check_text("짧은 문장임.", self.facts()) == "length"
        assert summary.check_text("가" * (summary.MAX_CHARS + 1), self.facts()) == "length"

    def test_rejects_text_without_hangul(self):
        assert summary.check_text("a" * 60, self.facts()) == "no_hangul"

    def test_normalize_strips_markdown_quotes_and_lines(self):
        raw = '  "**이 주** 요약\n\n# 둘째 줄 `코드`"  '
        assert summary.normalize(raw) == "이 주 요약 둘째 줄 코드"


# ---------------------------------------------------------------------------
# 요약 고르기 — DB 가 필요하다(WeeklySummary)
# ---------------------------------------------------------------------------


@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(
        email="sum@example.com", password="Qm4-silver-meadow-21", nickname="요약"
    )


def with_suffix(request) -> str:
    """보낸 집계값으로 만든 템플릿 + 숫자 없는 꼬리. 검사를 늘 통과한다."""
    facts = json.loads(json.loads(request.data.decode("utf-8"))["messages"][1]["content"])
    return summary.render_template(facts) + " 다음 주도 지금처럼 하면 됨."


class RecordingCache:
    """진짜 캐시에 넘기면서 `set` 의 TTL 을 적어 둔다. (Partly AI-generated (Claude))"""

    def __init__(self, real):
        self._real = real
        self.timeouts: dict[str, float | None] = {}

    def set(self, key, value, timeout=None):
        self.timeouts[key] = timeout
        return self._real.set(key, value, timeout)

    def __getattr__(self, name):
        return getattr(self._real, name)


@pytest.mark.django_db
class TestSummaryFor:

    def test_without_a_key_uses_the_template_and_never_calls(self, user):
        text, source = summary.summary_for(user, example_report())
        assert (text, source) == (EXAMPLE_TEXT, summary.SOURCE_TEMPLATE)
        assert not WeeklySummary.objects.exists()

    def test_llm_text_is_stored_and_reused(self, user, fake_llm):
        fake_llm.reply = with_suffix
        report = example_report()

        text, source = summary.summary_for(user, report)
        assert source == summary.SOURCE_LLM
        assert text == EXAMPLE_TEXT + " 다음 주도 지금처럼 하면 됨."
        row = WeeklySummary.objects.get(user=user)
        assert row.week_start.isoformat() == report["week_start"]
        assert row.text == text
        assert row.model == "gpt-test-mini"
        assert row.facts == summary.build_facts(report)
        assert row.facts_hash == summary.facts_digest(summary.build_facts(report))

        # 두 번째는 DB 에서 읽는다. 캐시를 비워도(재시작·배포) 다시 부르지 않는다.
        cache.clear()
        assert summary.summary_for(user, report) == (text, summary.SOURCE_LLM)
        assert fake_llm.calls == 1

    def test_changed_numbers_make_a_new_summary(self, user, fake_llm):
        fake_llm.reply = with_suffix
        summary.summary_for(user, example_report())

        changed = example_report()
        changed["outcomes"][6] = on_time(4.0, 4)  # 늦게 올라온 도착 기록
        changed.update(arrived_count=7, unobserved_count=0, on_time_count=6,
                       on_time_rate=0.8571)
        text, source = summary.summary_for(user, changed)

        assert source == summary.SOURCE_LLM
        assert fake_llm.calls == 2
        assert "86%" in text
        assert WeeklySummary.objects.get(user=user).text == text

    def test_request_shape_and_payload(self, user, fake_llm):
        fake_llm.reply = with_suffix
        report = example_report()
        summary.summary_for(user, report)

        request, timeout = fake_llm.requests[0]
        assert request.full_url == "https://api.openai.com/v1/chat/completions"
        assert request.get_method() == "POST"
        assert request.get_header("Authorization") == "Bearer sk-test-not-a-real-key"
        assert timeout == llm.TIMEOUT_SECONDS

        body = fake_llm.sent()
        assert set(body) == {"model", "messages"}  # temperature·max_tokens 없음
        assert body["model"] == "gpt-test-mini"
        assert [m["role"] for m in body["messages"]] == ["system", "user"]
        assert body["messages"][0]["content"] == summary.SYSTEM_PROMPT
        assert json.loads(body["messages"][1]["content"]) == summary.build_facts(report)

        raw = request.data.decode("utf-8")
        for secret in (SECRET_TITLE, "2026-10", '\\"event\\"', str(EVENT_ID), "22:40"):
            assert secret not in raw, secret

    @pytest.mark.parametrize(
        "change",
        [
            {"week_complete": False},          # 진행 중인 주
            {"arrived_count": 2},              # 기록 적음
            {"arrived_count": 0},              # 도착 기록 없음
            {"week_start": monday_iso(13)},    # 12주보다 오래됨
        ],
    )
    def test_weeks_that_stay_on_the_template(self, user, fake_llm, change):
        report = {**example_report(), **change}
        _, source = summary.summary_for(user, report)
        assert source == summary.SOURCE_TEMPLATE
        assert fake_llm.calls == 0

    def test_stored_text_is_kept_for_older_weeks(self, user, fake_llm):
        """수치가 같으면 오래된 주라도 저장된 문장을 그대로 쓴다(열 때마다 바뀌지 않게)."""
        report = example_report(weeks_ago=20)
        facts = summary.build_facts(report)
        WeeklySummary.objects.create(
            user=user, week_start=report["week_start"], facts=facts,
            facts_hash=summary.facts_digest(facts), text="저장된 문장", model="gpt-test-mini",
        )
        assert summary.summary_for(user, report) == ("저장된 문장", summary.SOURCE_LLM)
        assert fake_llm.calls == 0

    def test_timeout_falls_back_and_backs_off(self, user, fake_llm):
        fake_llm.error = TimeoutError("timed out")
        report = example_report()

        assert summary.summary_for(user, report) == (EXAMPLE_TEXT, summary.SOURCE_TEMPLATE)
        assert cache.get(summary.DOWN_KEY) == llm.TIMEOUT
        # 장애 동안 요청마다 8초씩 기다리지 않는다.
        assert summary.summary_for(user, report)[1] == summary.SOURCE_TEMPLATE
        assert fake_llm.calls == 1

    def test_unauthorized_falls_back(self, user, fake_llm):
        fake_llm.error = urllib.error.HTTPError(
            llm.DEFAULT_BASE_URL, 401, "Unauthorized", {},
            io.BytesIO(b'{"error":{"code":"invalid_api_key","message":"Incorrect key sk-tes***"}}'),
        )
        assert summary.summary_for(user, example_report())[1] == summary.SOURCE_TEMPLATE
        assert cache.get(summary.DOWN_KEY) == llm.HTTP_4XX
        assert not WeeklySummary.objects.exists()

    def test_invalid_output_falls_back_for_that_digest(self, user, fake_llm):
        fake_llm.reply = EXAMPLE_TEXT.replace("83%", "84%")
        report = example_report()

        assert summary.summary_for(user, report) == (EXAMPLE_TEXT, summary.SOURCE_TEMPLATE)
        assert summary.summary_for(user, report)[1] == summary.SOURCE_TEMPLATE
        assert fake_llm.calls == 1  # 같은 수치로 다시 부르지 않는다
        assert cache.get(summary.DOWN_KEY) is None  # 다른 주는 막지 않는다
        assert not WeeklySummary.objects.exists()

    # Partly AI-generated (Claude)
    def test_rejected_numbers_rest_until_they_change(self, user, fake_llm, monkeypatch):
        """검사에 떨어진 해시는 한 시간 뒤가 아니라 수치가 바뀔 때까지 쉰다.

        끝난 주의 수치는 잘 바뀌지 않는다. 짧게 쉬면 그 주를 열 때마다 같은 입력을 다시
        보내고, 그 요청은 LLM 을 기다린 뒤 결국 템플릿을 받는다.
        """
        recording = RecordingCache(cache)
        monkeypatch.setattr(summary, "cache", recording)
        fake_llm.reply = EXAMPLE_TEXT.replace("83%", "84%")
        report = example_report()

        summary.summary_for(user, report)
        digest = summary.facts_digest(summary.build_facts(report))
        rest = recording.timeouts[summary.INVALID_KEY.format(digest=digest)]
        assert rest >= summary.LLM_MAX_WEEKS_BACK * 7 * 24 * 60 * 60

        # 수치가 바뀌면 해시가 달라져 다시 부른다.
        fake_llm.reply = with_suffix
        changed = example_report()
        changed["outcomes"][6] = on_time(4.0, 4)
        changed.update(arrived_count=7, unobserved_count=0, on_time_count=6,
                       on_time_rate=0.8571)
        assert summary.summary_for(user, changed)[1] == summary.SOURCE_LLM
        assert fake_llm.calls == 2

    # Partly AI-generated (Claude)
    def test_daily_budget_per_user(self, user, fake_llm, monkeypatch, django_user_model):
        """수치를 A→B→A 로 바꿔(지난 일정의 알람 켬/끔) 저장된 해시를 매번 빗나가게 해도
        사용자당 하루 상한을 넘겨 부르지 않는다. 다른 사용자는 막지 않는다."""
        monkeypatch.setattr(summary, "LLM_USER_DAILY_LIMIT", 2)
        fake_llm.reply = with_suffix
        alarm_on = example_report()
        alarm_off = example_report()
        alarm_off["outcomes"][6] = unseen(4, alarm_on=False)
        assert summary.facts_digest(summary.build_facts(alarm_on)) != summary.facts_digest(
            summary.build_facts(alarm_off)
        )

        assert summary.summary_for(user, alarm_on)[1] == summary.SOURCE_LLM
        assert summary.summary_for(user, alarm_off)[1] == summary.SOURCE_LLM
        assert summary.summary_for(user, alarm_on) == (EXAMPLE_TEXT, summary.SOURCE_TEMPLATE)
        assert fake_llm.calls == 2

        # 상한은 실제로 부를 때만 센다. 지금 수치와 같은 저장된 문장은 그대로 쓴다.
        assert summary.summary_for(user, alarm_off)[1] == summary.SOURCE_LLM
        assert fake_llm.calls == 2

        other = django_user_model.objects.create_user(
            email="sum-other@example.com", password="Qm4-silver-meadow-21", nickname="다른"
        )
        assert summary.summary_for(other, alarm_on)[1] == summary.SOURCE_LLM
        assert fake_llm.calls == 3

    # Partly AI-generated (Claude)
    def test_budget_cache_failure_uses_the_template(self, user, fake_llm, monkeypatch):
        """호출 수를 셀 수 없으면 부르지 않는다(상한이 없는 상태로 열지 않는다)."""

        class BrokenCaches:
            def __getitem__(self, alias):
                raise OSError("cache unavailable")

        monkeypatch.setattr(summary, "caches", BrokenCaches())
        assert summary.summary_for(user, example_report()) == (
            EXAMPLE_TEXT, summary.SOURCE_TEMPLATE,
        )
        assert fake_llm.calls == 0

    def test_template_failure_uses_the_fallback(self, user, monkeypatch):
        def broken(facts):
            raise RuntimeError("boom")

        monkeypatch.setattr(summary, "render_template", broken)
        assert summary.summary_for(user, example_report()) == (
            summary.FALLBACK, summary.SOURCE_TEMPLATE,
        )

    def test_unexpected_failure_never_escapes(self, user, monkeypatch):
        def broken(report):
            raise KeyError("week_start")

        monkeypatch.setattr(summary, "build_facts", broken)
        assert summary.summary_for(user, example_report()) == (
            summary.FALLBACK, summary.SOURCE_TEMPLATE,
        )

    def test_fallback_passes_no_numbers(self):
        assert not any(ch.isdigit() for ch in summary.FALLBACK)


@pytest.mark.django_db
class TestApiWithFakeLlm:
    """뷰 → 서비스 → 요약까지 실제 데이터로 한 번. LLM 자리만 가짜다."""

    def test_completed_week_with_three_arrivals_gets_an_llm_summary(self, user, fake_llm):
        fake_llm.reply = with_suffix
        place = Place.objects.create(name="강의동", lat=37.46, lng=126.95, address="관악로 1")
        start, _ = week_bounds(timezone.localdate() - timedelta(weeks=2))
        for day in range(3):
            begin = start + timedelta(days=day, hours=9)
            event = Event.objects.create(user=user, title=SECRET_TITLE, start_at=begin, place=place)
            AlarmPlan.objects.create(
                user=user, event=event, status=AlarmPlan.Status.OK,
                alarm_at=begin - timedelta(minutes=80), depart_by=begin - timedelta(minutes=52),
                arrive_at=begin - timedelta(minutes=10), prep_minutes=28, travel_minutes=42,
                buffer_minutes=10, tau_used=0.9,
            )
            TripObservation.objects.create(
                user=user, event=event, kind=TripObservation.Kind.ARRIVE,
                detector=TripObservation.Detector.GPS, observed_at=begin - timedelta(minutes=6),
                lat=37.46, lng=126.95, accuracy_m=15.0, distance_m=20.0,
                client_uuid=f"sum-arrive-{day}",
            )

        client = APIClient()
        client.force_authenticate(user=user)
        res = client.get(f"/api/reports/weekly?week={start.date().isoformat()}")

        assert res.status_code == 200
        assert res.data["summary_source"] == summary.SOURCE_LLM
        assert res.data["summary"].endswith("다음 주도 지금처럼 하면 됨.")
        sent = fake_llm.sent()["messages"][1]["content"]
        assert SECRET_TITLE not in sent and "관악로" not in sent and "37.46" not in sent

    def test_in_progress_week_never_calls(self, user, fake_llm):
        client = APIClient()
        client.force_authenticate(user=user)
        res = client.get(f"/api/reports/weekly?week={timezone.localdate().isoformat()}")
        assert res.data["summary_source"] == summary.SOURCE_TEMPLATE
        assert res.data["summary"].startswith("진행 중인 주라")
        assert fake_llm.calls == 0


# ---------------------------------------------------------------------------
# 확인 명령
# ---------------------------------------------------------------------------


class TestProbeCommand:

    def test_sample_facts_have_the_fixed_keys(self):
        assert tuple(SAMPLE_FACTS) == summary.FACT_KEYS

    def test_without_settings_it_says_so_and_never_calls(self):
        out = io.StringIO()
        with pytest.raises(SystemExit) as exit_info:
            call_command("probe_report_summary", stdout=out)
        assert exit_info.value.code == 1
        assert "설정 없음" in out.getvalue()

    def test_with_settings_it_checks_one_reply(self, fake_llm):
        fake_llm.reply = summary.render_template(SAMPLE_FACTS)
        out = io.StringIO()
        call_command("probe_report_summary", stdout=out)
        printed = out.getvalue()
        assert "통과" in printed
        assert "api.openai.com" in printed
        assert "sk-test-not-a-real-key" not in printed
        assert fake_llm.calls == 1

    def test_failed_check_exits_with_one(self, fake_llm):
        fake_llm.reply = summary.render_template(SAMPLE_FACTS).replace("83%", "84%")
        out = io.StringIO()
        with pytest.raises(SystemExit) as exit_info:
            call_command("probe_report_summary", stdout=out)
        assert exit_info.value.code == 1
        assert "invented_number:84" in out.getvalue()
