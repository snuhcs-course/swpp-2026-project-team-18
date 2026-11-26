# Partly AI-generated (Claude)
"""주간 요약 평가 세트(S-3) 실행. Testing, Iteration 4, 5.3절의 계획과 기준을 따른다.

세트는 `apps/reports/eval/weekly_summary_cases.json` 이다. 케이스마다 리포트 값(`build_facts` 와
`llm_allowed` 의 입력)과 평가 시각(`now`), 기대 동작(`llm_allowed`, `thin_data`)이 있다. 기준도 같은
파일의 `thresholds` 에 있다 — 세트와 기준이 한 커밋에 들어가야 "미리 정한 기준" 이 된다.

**DB 를 쓰지 않는다.** `summary_for` 는 저장·백오프·하루 상한을 거치지만 평가는 그 경로가 필요 없다.
여기서는 앱에 내보낼 때와 같은 함수(`build_facts`, `llm_allowed`, `render_template`, `llm.chat`,
`normalize`, `check_text`)를 순서대로 부른다. 그래서 사용자 행·`LlmCall` 행을 만들지 않는다.

케이스마다 하는 일:

1. 기대 동작 확인 — `llm_allowed`·`thin_data` 가 케이스에 적힌 값과 같은지(세트가 코드와 어긋나면 실패).
2. 템플릿 문장 만들기 — 템플릿도 `check_text` 를 통과해야 한다.
3. 허용된 주면 LLM 을 **한 번** 부른다(`offline` 이면 부르지 않는다). 검사를 통과하면 그 문장이,
   아니면 템플릿이 "보여 준 문장" 이다.
4. 허용되지 않은 주는 LLM 을 부르지 않고 템플릿을 보여 준다.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path

from . import llm, summary

CASES_PATH = Path(__file__).resolve().parent / "eval" / "weekly_summary_cases.json"


def load_set(path: Path | str = CASES_PATH) -> dict:
    with open(path, encoding="utf-8") as handle:
        return json.load(handle)


@dataclass
class CaseResult:
    id: str
    category: str
    human_rating: bool
    llm_allowed: bool
    thin_data: bool
    expect_ok: bool
    template: str
    template_reason: str | None
    called: bool = False
    failure: str | None = None
    llm_text: str | None = None
    llm_reason: str | None = None
    shown: str = ""
    shown_source: str = summary.SOURCE_TEMPLATE
    shown_reason: str | None = None
    facts: dict = field(default_factory=dict)


def run_case(case: dict, *, offline: bool, chat=None) -> CaseResult:
    """케이스 하나. `chat` 은 테스트가 가짜를 넣는 자리다(기본 `llm.chat`)."""
    chat = chat or llm.chat
    report = case["report"]
    now = datetime.fromisoformat(case["now"])
    facts = summary.build_facts(report)
    allowed = summary.llm_allowed(report, now=now)
    expect = case["expect"]
    template = summary.render_template(facts)
    result = CaseResult(
        id=case["id"],
        category=case["category"],
        human_rating=bool(case.get("human_rating")),
        llm_allowed=allowed,
        thin_data=bool(facts["thin_data"]),
        expect_ok=allowed == expect["llm_allowed"] and bool(facts["thin_data"]) == expect["thin_data"],
        template=template,
        template_reason=summary.check_text(template, facts),
        facts=facts,
    )
    result.shown = template
    if allowed and not offline:
        result.called = True
        text, failure = chat(summary.SYSTEM_PROMPT, json.dumps(facts, ensure_ascii=False))
        if failure is not None:
            result.failure = failure
        else:
            clean = summary.normalize(text)
            result.llm_text = clean
            result.llm_reason = summary.check_text(clean, facts)
            if result.llm_reason is None:
                result.shown = clean
                result.shown_source = summary.SOURCE_LLM
    result.shown_reason = summary.check_text(result.shown, facts)
    return result


def metrics(results: list[CaseResult], *, offline: bool) -> dict:
    """기준표의 자동 지표. 사람 평가(`human_mean`)는 여기서 내지 않는다."""
    allowed = [r for r in results if r.llm_allowed]
    called = [r for r in allowed if r.called]
    accepted = [r for r in called if r.llm_text is not None and r.llm_reason is None]
    not_allowed = [r for r in results if not r.llm_allowed]
    shown_valid = [r for r in results if r.shown_reason is None]
    invented = [r for r in results if (r.shown_reason or "").startswith("invented_number")]
    return {
        "cases": len(results),
        "expect_mismatches": [r.id for r in results if not r.expect_ok],
        "template_failures": [r.id for r in results if r.template_reason is not None],
        "allowed": len(allowed),
        "llm_called": len(called),
        "llm_failures": {r.id: r.failure for r in called if r.failure},
        "first_call_accept_rate": None if offline or not called else len(accepted) / len(called),
        "rejections": {r.id: r.llm_reason for r in called if r.llm_reason},
        "shown_valid_rate": len(shown_valid) / len(results) if results else None,
        "invented_numbers": len(invented),
        "not_allowed_template_rate": (
            sum(1 for r in not_allowed if not r.called and r.shown_source == summary.SOURCE_TEMPLATE)
            / len(not_allowed)
            if not_allowed
            else None
        ),
    }


def verdicts(values: dict, thresholds: dict) -> dict:
    """지표마다 (값, 기준, 통과 여부). 값이 None 이면 통과 여부도 None(재지 않음)."""
    out = {}
    for name, rule in thresholds.items():
        if name not in values:
            continue
        value = values[name]
        if value is None:
            passed = None
        elif "min" in rule:
            passed = value >= rule["min"]
        else:
            passed = value <= rule["max"]
        out[name] = {"value": value, "rule": rule, "passed": passed}
    return out


def result_rows(results: list[CaseResult]) -> list[dict]:
    return [asdict(r) for r in results]
