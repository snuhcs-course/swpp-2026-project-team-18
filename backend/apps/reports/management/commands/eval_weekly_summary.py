# Partly AI-generated (Claude)
"""주간 요약 평가 세트(S-3)를 돌리고 지표를 기준과 함께 찍는다.

    python manage.py eval_weekly_summary              # LLM 을 부른다(OPENAI_API_KEY·OPENAI_MODEL 필요)
    python manage.py eval_weekly_summary --offline    # LLM 없이 세트·템플릿만 확인한다
    python manage.py eval_weekly_summary --out eval-out

기준과 세트는 `apps/reports/eval/weekly_summary_cases.json` 하나에 있다. 기준표와 케이스 구성은
위키 Testing, Iteration 4, 5.3절(2026-11-16 선언)을 따른다.

출력:
- 실행 설정: 모델(`OPENAI_MODEL`), `PROMPT_VERSION`, 커밋, 세트 버전과 케이스 수. 키는 찍지 않는다.
- 지표마다 값 · 기준 · 통과 여부.
- `--out` 폴더에 `results.json`(케이스별 결과)과 `human_rating.csv`(사람 평가용 10건, 점수 칸은 비어 있음).

종료 코드: 자동 지표가 모두 통과하면 0, 하나라도 떨어지거나 세트가 코드와 어긋나면 1.
사람 평가 평균(`human_mean`)은 CSV 를 채운 뒤 `--ratings` 로 넘기면 같이 판정한다.
"""

from __future__ import annotations

import csv
import json
import os
import subprocess
from pathlib import Path

from django.conf import settings
from django.core.management.base import BaseCommand

from apps.reports import evaluation, llm, summary


def _commit() -> str:
    for key in ("RENDER_GIT_COMMIT", "GIT_COMMIT"):
        if os.getenv(key):
            return os.environ[key][:12]
    try:
        return subprocess.run(
            ["git", "rev-parse", "--short=12", "HEAD"], capture_output=True, text=True, check=True,
            cwd=Path(__file__).resolve().parent,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def _human_mean(path: str) -> float | None:
    """채운 `human_rating.csv`. 두 평가자 칸(rater_1, rater_2)의 1~5 점수 평균."""
    scores = []
    with open(path, encoding="utf-8-sig", newline="") as handle:
        for row in csv.DictReader(handle):
            for key in ("rater_1", "rater_2"):
                value = (row.get(key) or "").strip()
                if value:
                    score = float(value)
                    if not 1 <= score <= 5:
                        raise ValueError(f"{row.get('id')}: 점수는 1~5 ({value})")
                    scores.append(score)
    return sum(scores) / len(scores) if scores else None


class Command(BaseCommand):
    help = "주간 요약 평가 세트를 돌려 지표를 기준과 함께 찍는다(S-3)."

    def add_arguments(self, parser):
        parser.add_argument("--offline", action="store_true", help="LLM 을 부르지 않는다")
        parser.add_argument("--cases", default=str(evaluation.CASES_PATH), help="세트 파일")
        parser.add_argument("--out", default="", help="results.json · human_rating.csv 를 쓸 폴더")
        parser.add_argument("--ratings", default="", help="채운 human_rating.csv(사람 평가 평균 판정)")

    def handle(self, *args, **options):
        out = self.stdout
        offline = options["offline"]
        if not offline and not llm.configured():
            out.write("설정 없음: OPENAI_API_KEY·OPENAI_MODEL 이 비어 있다. --offline 으로만 돌 수 있다.")
            raise SystemExit(1)

        doc = evaluation.load_set(options["cases"])
        cases = doc["cases"]
        model = "(offline)" if offline else (settings.OPENAI_MODEL or "").strip()
        out.write(f"세트        v{doc.get('version')} · 케이스 {len(cases)}개 · 사람 평가 {sum(1 for c in cases if c.get('human_rating'))}개")
        out.write(f"모델        {model}")
        out.write(f"프롬프트    PROMPT_VERSION {summary.PROMPT_VERSION}")
        out.write(f"커밋        {_commit()}")
        out.write("디코딩      temperature·max_tokens 를 보내지 않음(llm.py) · 8초/단계 · 15초/호출")
        out.write("")

        results = [evaluation.run_case(case, offline=offline) for case in cases]
        values = evaluation.metrics(results, offline=offline)
        if options["ratings"]:
            values["human_mean"] = _human_mean(options["ratings"])
        judged = evaluation.verdicts(values, doc["thresholds"])

        ok = not values["expect_mismatches"] and not values["template_failures"]
        if values["expect_mismatches"]:
            out.write(f"세트와 코드가 어긋남: {', '.join(values['expect_mismatches'])}")
        if values["template_failures"]:
            out.write(f"템플릿이 검사에 떨어짐: {', '.join(values['template_failures'])}")
        out.write(f"허용된 주 {values['allowed']} · LLM 호출 {values['llm_called']} · 호출 실패 {len(values['llm_failures'])}")
        for case_id, reason in values["rejections"].items():
            out.write(f"  거부 {case_id}: {reason}")
        for case_id, failure in values["llm_failures"].items():
            out.write(f"  실패 {case_id}: {failure}")
        out.write("")
        for name, item in judged.items():
            rule = item["rule"]
            bound = f">= {rule['min']}" if "min" in rule else f"<= {rule['max']}"
            value = item["value"]
            shown = "재지 않음" if value is None else (f"{value:.3f}" if isinstance(value, float) else str(value))
            mark = {True: "통과", False: "실패", None: "-"}[item["passed"]]
            out.write(f"{name:<28} {shown:>10}  기준 {bound:<7} {mark}")
            if item["passed"] is False:
                ok = False
        if "human_mean" not in values:
            out.write(f"{'human_mean':<28} {'재지 않음':>10}  기준 >= {doc['thresholds']['human_mean']['min']}  - (--ratings 로 판정)")

        if options["out"]:
            folder = Path(options["out"])
            folder.mkdir(parents=True, exist_ok=True)
            payload = {
                "model": model,
                "prompt_version": summary.PROMPT_VERSION,
                "commit": _commit(),
                "set_version": doc.get("version"),
                "offline": offline,
                "metrics": values,
                "verdicts": judged,
                "results": evaluation.result_rows(results),
            }
            (folder / "results.json").write_text(
                json.dumps(payload, ensure_ascii=False, indent=1, default=str), encoding="utf-8"
            )
            with open(folder / "human_rating.csv", "w", encoding="utf-8-sig", newline="") as handle:
                writer = csv.writer(handle)
                writer.writerow(["id", "category", "source", "facts", "summary", "rater_1", "rater_2", "comment"])
                for r in results:
                    if r.human_rating:
                        writer.writerow([
                            r.id, r.category, r.shown_source,
                            json.dumps(r.facts, ensure_ascii=False), r.shown, "", "", "",
                        ])
            out.write("")
            out.write(f"결과: {folder / 'results.json'} · 사람 평가지: {folder / 'human_rating.csv'}")

        if not ok:
            raise SystemExit(1)
