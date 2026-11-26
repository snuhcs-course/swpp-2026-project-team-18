# Partly AI-generated (Claude)
"""주간 요약 평가 세트(S-3)와 `eval_weekly_summary` 명령. **LLM 을 부르지 않는다**(가짜 chat 만 쓴다).

세트가 Testing, Iteration 4, 5.3절의 구성을 지키는지, 케이스의 기대 동작이 지금 코드와 맞는지,
지표 계산이 기준표대로인지를 본다.
"""

from __future__ import annotations

import csv
import json
from collections import Counter

import pytest
from django.core.management import call_command

from apps.reports import evaluation, summary

DOC = evaluation.load_set()
CASES = DOC["cases"]


def test_set_size_and_composition():
    counts = Counter(c["category"] for c in CASES)
    assert len(CASES) >= 35
    assert counts["ordinary"] >= 10
    for category in ("many_late", "buffer_shift", "missing_records", "edge", "thin", "not_allowed"):
        assert counts[category] >= 5, category
    assert len({c["id"] for c in CASES}) == len(CASES)


def test_thresholds_match_the_declared_plan():
    t = DOC["thresholds"]
    assert t["first_call_accept_rate"]["min"] == 0.80
    assert t["shown_valid_rate"]["min"] == 1.00
    assert t["invented_numbers"]["max"] == 0
    assert t["not_allowed_template_rate"]["min"] == 1.00
    assert t["human_mean"]["min"] == 4.0


def test_ten_human_cases_all_allowed():
    human = [c for c in CASES if c["human_rating"]]
    assert len(human) == 10
    assert all(c["expect"]["llm_allowed"] for c in human)


def test_reports_carry_no_personal_fields():
    banned = {"title", "event", "start_at", "date", "planned", "actual", "place", "email", "nickname"}
    for case in CASES:
        for row in case["report"]["outcomes"]:
            assert not banned & set(row), case["id"]


@pytest.mark.parametrize("case", CASES, ids=[c["id"] for c in CASES])
def test_case_expectation_matches_code_and_template_passes(case):
    result = evaluation.run_case(case, offline=True)
    assert result.expect_ok, (result.llm_allowed, result.thin_data, case["expect"])
    assert result.template_reason is None, result.template
    assert not result.called


def test_offline_metrics():
    results = [evaluation.run_case(c, offline=True) for c in CASES]
    values = evaluation.metrics(results, offline=True)
    assert values["expect_mismatches"] == []
    assert values["template_failures"] == []
    assert values["first_call_accept_rate"] is None
    assert values["shown_valid_rate"] == 1.0
    assert values["invented_numbers"] == 0
    assert values["not_allowed_template_rate"] == 1.0


def test_accepted_rejected_and_failed_answers_are_counted():
    good = "이 주 도착을 기록한 6번 중 5번 정시 도착했어요. 다음 주에도 지금처럼 나서 보세요."
    invented = "이 주 도착을 기록한 6번 중 5번 정시 도착했어요. 평균 47분 여유가 있었어요."
    allowed = [c for c in CASES if c["expect"]["llm_allowed"] and not c["expect"]["thin_data"]][:3]
    answers = iter([(good, None), (invented, None), (None, "timeout")])

    def chat(system, user):
        assert system == summary.SYSTEM_PROMPT
        assert set(json.loads(user)) == set(summary.FACT_KEYS)
        return next(answers)

    # 케이스 숫자에 맞춰 good 이 통과하는 케이스(ord-01: 6번 중 5번)를 첫 자리에 둔다.
    first = next(c for c in CASES if c["id"] == "ord-01")
    picked = [first] + [c for c in allowed if c["id"] != "ord-01"][:2]
    results = [evaluation.run_case(c, offline=False, chat=chat) for c in picked]
    values = evaluation.metrics(results, offline=False)

    assert results[0].shown_source == summary.SOURCE_LLM
    assert results[1].llm_reason.startswith("invented_number:")
    assert results[1].shown_source == summary.SOURCE_TEMPLATE
    assert results[2].failure == "timeout"
    assert values["first_call_accept_rate"] == pytest.approx(1 / 3)
    # 보여 준 문장은 통과한 LLM 문장이거나 템플릿이라 모두 검사를 통과한다.
    assert values["shown_valid_rate"] == 1.0
    assert values["invented_numbers"] == 0


def test_not_allowed_weeks_never_call_the_llm():
    def chat(system, user):  # pragma: no cover - 불리면 실패
        raise AssertionError("허용되지 않은 주에 LLM 을 불렀다")

    for case in (c for c in CASES if not c["expect"]["llm_allowed"]):
        result = evaluation.run_case(case, offline=False, chat=chat)
        assert result.shown == result.template


def test_verdicts():
    rules = DOC["thresholds"]
    judged = evaluation.verdicts(
        {"first_call_accept_rate": 0.79, "invented_numbers": 0, "shown_valid_rate": None}, rules
    )
    assert judged["first_call_accept_rate"]["passed"] is False
    assert judged["invented_numbers"]["passed"] is True
    assert judged["shown_valid_rate"]["passed"] is None


def test_command_offline_writes_results_and_rating_sheet(tmp_path):
    call_command("eval_weekly_summary", "--offline", "--out", str(tmp_path))
    payload = json.loads((tmp_path / "results.json").read_text(encoding="utf-8"))
    assert payload["prompt_version"] == summary.PROMPT_VERSION
    assert len(payload["results"]) == len(CASES)
    with open(tmp_path / "human_rating.csv", encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    assert len(rows) == 10
    assert all(row["rater_1"] == "" and row["rater_2"] == "" for row in rows)


def test_command_reads_filled_ratings(tmp_path):
    call_command("eval_weekly_summary", "--offline", "--out", str(tmp_path))
    sheet = tmp_path / "human_rating.csv"
    with open(sheet, encoding="utf-8-sig", newline="") as handle:
        rows = list(csv.DictReader(handle))
    for row in rows:
        row["rater_1"], row["rater_2"] = "4", "5"
    with open(sheet, "w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    call_command("eval_weekly_summary", "--offline", "--ratings", str(sheet))


def test_command_refuses_live_run_without_key(settings):
    settings.OPENAI_API_KEY = ""
    with pytest.raises(SystemExit):
        call_command("eval_weekly_summary")
