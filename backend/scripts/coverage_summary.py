"""coverage.json 을 컴포넌트별 줄 커버리지 표(마크다운)로 묶는다.

    cd backend
    python -m pytest --cov --cov-report=json
    python scripts/coverage_summary.py                # coverage.json 을 읽는다
    python scripts/coverage_summary.py path/to.json

## 왜 따로 묶는가

테스트 문서(Wiki)에 "아키텍처 계층별 목표 vs 실제" 표를 낸다. coverage 의 기본
리포트는 파일 단위라 그 표를 손으로 더해야 하고, 그러면 사람마다 숫자가 달라진다.
묶는 규칙을 코드로 두면 CI 요약과 Wiki 표가 같은 숫자가 된다.

컴포넌트 구분은 Wiki 의 커버리지 목표 표와 같다. 앱을 옮기거나 새로 만들면 여기도
고친다. 어느 컴포넌트에도 속하지 않는 앱은 "Other" 로 따로 보여서 빠뜨린 것이
드러난다.
"""
import json
import sys
from collections import defaultdict
from pathlib import Path

# 결과를 좌우하는 계산(학습·분포·경로)과, 권한·직렬화·검증을 하는 API 층.
COMPONENTS = {
    "Backend - Computation": ("planning", "prediction", "routing"),
    "Backend - API": ("accounts", "events", "routines", "observations", "reports", "common"),
}


def _app_of(path: str) -> str | None:
    """`apps/<앱>/...` 의 앱 이름. `apps/__init__.py` 처럼 앱 밖 파일은 None."""
    parts = Path(path).parts
    if "apps" not in parts or len(parts) < parts.index("apps") + 3:
        return None
    return parts[parts.index("apps") + 1]


def _pct(covered: int, total: int) -> str:
    return f"{covered / total * 100:.1f}%" if total else "-"


def summarize(report: dict) -> str:
    per_app = defaultdict(lambda: [0, 0])  # app -> [covered, statements]
    for path, data in report["files"].items():
        app = _app_of(path)
        if app is None:
            continue
        s = data["summary"]
        per_app[app][0] += s["covered_lines"]
        per_app[app][1] += s["num_statements"]

    known = {app for apps in COMPONENTS.values() for app in apps}
    groups = dict(COMPONENTS)
    others = tuple(sorted(set(per_app) - known))
    if others:
        groups["Other"] = others

    lines = [
        "| Component | Apps | Statements | Covered | Line coverage |",
        "| --- | --- | ---: | ---: | ---: |",
    ]
    for name, apps in groups.items():
        covered = sum(per_app[a][0] for a in apps)
        total = sum(per_app[a][1] for a in apps)
        lines.append(f"| {name} | {', '.join(apps)} | {total} | {covered} | {_pct(covered, total)} |")
    t = report["totals"]
    lines.append(
        f"| **Total** | | {t['num_statements']} | {t['covered_lines']} | "
        f"**{_pct(t['covered_lines'], t['num_statements'])}** |"
    )

    lines += ["", "| App | Statements | Covered | Line coverage |", "| --- | ---: | ---: | ---: |"]
    for app in sorted(per_app):
        covered, total = per_app[app]
        lines.append(f"| {app} | {total} | {covered} | {_pct(covered, total)} |")
    return "\n".join(lines)


def main() -> None:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "coverage.json")
    if not path.exists():
        sys.exit(f"{path} 가 없다. 먼저 python -m pytest --cov --cov-report=json 을 돌린다.")
    print(summarize(json.loads(path.read_text(encoding="utf-8"))))


if __name__ == "__main__":
    main()
