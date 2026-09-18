"""경로 후보 추출이 의미 있는 선택지를 만드는지 확인한다.

카카오는 대중교통 15개를 항상 주지만 대부분 같은 버스의 다른 환승 조합이다.
`clients.route_candidates()` 가 서로 다른 축(빠름·환승없음·지하철·저렴)의
대표만 남기는지, 도보·자전거·자동차가 붙는지 본다.

사용법:
    cd backend
    .\\.venv\\Scripts\\python.exe scripts\\check_route_candidates.py
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BASE_DIR))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")

import django  # noqa: E402

django.setup()

from apps.routing import clients  # noqa: E402

CASES = [
    ("신림역 → 서울대 관악캠퍼스", (37.484267, 126.929745), (37.459786, 126.951124)),
    ("신림역 → 강남역", (37.484267, 126.929745), (37.497942, 127.027621)),
    ("신림역 → 신림역교차로 (도보권)", (37.484267, 126.929745), (37.484141, 126.929718)),
]

failures = 0

for label, start, end in CASES:
    print("=" * 78)
    print(label)
    print("=" * 78)
    items, degraded = clients.route_candidates(start[0], start[1], end[0], end[1])
    print(f"  degraded={degraded}  후보 {len(items)}개")
    if not items:
        print("  실패: 후보가 없다")
        failures += 1
        continue

    keys = [i["key"] for i in items]
    if len(keys) != len(set(keys)):
        print(f"  실패: key 중복 {keys}")
        failures += 1

    for i in items:
        fare = f"{i['fare']:,}원" if i["fare"] else ("무료" if i["fare"] == 0 else "요금 미정")
        reason = f"  [{i['reason']}]" if i.get("reason") else ""
        print(f"    {i['minutes']:>3}분  {i['mode']:<14} 환승{i['transfers']}  {fare:<10} {i['detail']}{reason}")
        print(f"         key={i['key']}")

    # 오름차순 정렬 확인
    mins = [i["minutes"] for i in items]
    if mins != sorted(mins):
        print(f"  실패: 정렬이 깨졌다 {mins}")
        failures += 1

    # 선택한 경로를 다시 조회해 같은 값이 나오는지 (resolve_route)
    target = items[-1] if len(items) > 1 else items[0]
    resolved, rdeg = clients.resolve_route(target["key"], start[0], start[1], end[0], end[1])
    if resolved is None:
        print(f"  실패: resolve_route({target['key']}) 가 None")
        failures += 1
    else:
        same = resolved["key"] == target["key"]
        print(
            f"  resolve_route({target['key']}) -> {resolved['minutes']}분 "
            f"{'OK' if same else 'key 불일치: ' + resolved['key']}"
        )
        if not same and not target["key"].startswith("transit:"):
            failures += 1
    print()

print("=" * 78)
print("실패 없음" if failures == 0 else f"실패 {failures}건")
print("=" * 78)
sys.exit(1 if failures else 0)
