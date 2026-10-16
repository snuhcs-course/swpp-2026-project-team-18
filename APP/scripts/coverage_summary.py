"""JaCoCo XML 을 아키텍처 계층별 줄 커버리지 표(마크다운)로 묶는다.

    cd APP
    ./gradlew jacocoDebugUnitTestReport
    python3 scripts/coverage_summary.py               # 기본 리포트 경로를 읽는다
    python3 scripts/coverage_summary.py path/to.xml

## 왜 따로 묶는가

테스트 문서(Wiki)에 "MVVM 계층별 목표 vs 실제" 표를 낸다. JaCoCo HTML 은 패키지
단위라 ViewModel 과 화면이 같은 `ui/*` 패키지에 섞여 있고, 손으로 나누면 사람마다
숫자가 달라진다. 규칙을 코드로 두면 CI 요약과 Wiki 표가 같은 숫자가 된다.

소스 파일 단위로 센다. 클래스 단위로 더하면 람다·내부 클래스가 같은 줄을 나눠 가져
합이 어긋난다. 계층 구분은 Wiki 의 커버리지 목표 표와 같다. 패키지를 옮기거나 새로
만들면 여기도 고친다. 어느 계층에도 속하지 않는 파일은 "Other" 로 드러난다.
"""
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

DEFAULT_REPORT = "app/build/reports/jacoco/jacocoDebugUnitTestReport/jacocoDebugUnitTestReport.xml"
ROOT = "com/swpp/wakeup"

# 순서가 판정 순서다. ViewModel 을 View 보다 먼저 본다(둘 다 ui/ 아래에 있다).
LAYERS = (
    ("Model - Domain", lambda pkg, src: pkg.startswith("domain")),
    ("Model - Data", lambda pkg, src: pkg.startswith("data")),
    ("ViewModel", lambda pkg, src: pkg.startswith("ui") and src.endswith("ViewModel.kt")),
    ("View", lambda pkg, src: pkg.startswith("ui")),
    ("Platform", lambda pkg, src: pkg.split("/")[0] in ("alarm", "sensing", "background", "calendar")),
)


def _line_counter(element) -> tuple[int, int]:
    """(covered, total). LINE 카운터가 없으면(실행 줄이 없는 파일) 0."""
    for c in element.findall("counter"):
        if c.get("type") == "LINE":
            covered, missed = int(c.get("covered")), int(c.get("missed"))
            return covered, covered + missed
    return 0, 0


def _pct(covered: int, total: int) -> str:
    return f"{covered / total * 100:.1f}%" if total else "-"


def summarize(root) -> tuple[str, int]:
    per_layer = defaultdict(lambda: [0, 0, 0])  # layer -> [covered, total, files]
    per_package = defaultdict(lambda: [0, 0])
    for package in root.iter("package"):
        pkg = package.get("name").removeprefix(ROOT).lstrip("/")
        for source in package.findall("sourcefile"):
            covered, total = _line_counter(source)
            if total == 0:
                continue
            src = source.get("name")
            layer = next((name for name, match in LAYERS if match(pkg, src)), "Other")
            per_layer[layer][0] += covered
            per_layer[layer][1] += total
            per_layer[layer][2] += 1
            per_package[pkg][0] += covered
            per_package[pkg][1] += total

    lines = [
        "| Layer | Files | Lines | Covered | Line coverage |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for name in [n for n, _ in LAYERS] + ["Other"]:
        if name not in per_layer:
            continue
        covered, total, files = per_layer[name]
        lines.append(f"| {name} | {files} | {total} | {covered} | {_pct(covered, total)} |")
    covered, total = _line_counter(root)
    lines.append(f"| **Total** | | {total} | {covered} | **{_pct(covered, total)}** |")

    lines += ["", "| Package | Lines | Covered | Line coverage |", "| --- | ---: | ---: | ---: |"]
    for pkg in sorted(per_package):
        c, t = per_package[pkg]
        lines.append(f"| {pkg or '(root)'} | {t} | {c} | {_pct(c, t)} |")
    return "\n".join(lines), total


def main() -> None:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_REPORT)
    if not path.exists():
        sys.exit(f"{path} 가 없다. 먼저 ./gradlew jacocoDebugUnitTestReport 를 돌린다.")
    table, total = summarize(ET.parse(path).getroot())
    print(table)
    # 클래스 경로가 틀리면 JaCoCo 는 오류 없이 빈 리포트를 만든다. 0% 를 "측정함" 으로
    # 읽지 않게 여기서 실패한다.
    if total == 0:
        sys.exit("리포트에 실행 줄이 하나도 없다. build.gradle.kts 의 classDirectories 경로를 확인한다.")


if __name__ == "__main__":
    main()
