"""도보 경로 응답의 실제 구조를 확인한다. BE-P1-05 구현 준비용.

사용법: .\\.venv\\Scripts\\python.exe scripts\\inspect_kakao_walk.py <키>
"""

from __future__ import annotations

import json
import ssl
import sys
import urllib.parse
import urllib.request

KEY = sys.argv[1]
START = ("126.92973494922707", "37.48453")
END = ("126.9511239870991", "37.45978574975834")


def get(url):
    req = urllib.request.Request(url)
    req.add_header("Authorization", f"KakaoAK {KEY}")
    with urllib.request.urlopen(url=req, timeout=15, context=ssl.create_default_context()) as r:
        return json.loads(r.read().decode("utf-8"))


def shape(obj, depth=0, max_depth=4):
    """좌표 배열처럼 큰 값은 접어서 구조만 보여준다."""
    pad = "  " * depth
    if depth > max_depth:
        return f"{pad}…"
    if isinstance(obj, dict):
        lines = []
        for k, v in obj.items():
            if isinstance(v, (dict, list)):
                lines.append(f"{pad}{k}:")
                lines.append(shape(v, depth + 1, max_depth))
            else:
                lines.append(f"{pad}{k} = {v!r}")
        return "\n".join(lines)
    if isinstance(obj, list):
        if not obj:
            return f"{pad}[] (빈 배열)"
        if all(isinstance(x, (int, float)) for x in obj):
            return f"{pad}[숫자 {len(obj)}개] 예: {obj[:2]}"
        if all(isinstance(x, list) for x in obj):
            return f"{pad}[좌표쌍 {len(obj)}개] 예: {obj[0] if obj else None}"
        out = [f"{pad}[{len(obj)}개] 첫 항목:"]
        out.append(shape(obj[0], depth + 1, max_depth))
        return "\n".join(out)
    return f"{pad}{obj!r}"


qs = urllib.parse.urlencode({
    "start_x": START[0], "start_y": START[1],
    "end_x": END[0], "end_y": END[1],
    "s_name": "신림역", "e_name": "서울대학교",
})

print("=" * 74)
print("도보 경로  GET /v2/routing/walk")
print("=" * 74)
walk = get(f"https://dapi.kakao.com/v2/routing/walk?{qs}")
print(shape(walk))

print()
print("=" * 74)
print("대중교통 첫 경로 전체 구조  GET /v2/routing/publictraffic")
print("=" * 74)
transit = get(f"https://dapi.kakao.com/v2/routing/publictraffic?{qs}")
print("status =", transit.get("status"))
print("properties:")
print(shape(transit.get("properties"), 1))
routes = transit.get("routes") or []
print(f"routes = {len(routes)}개")

# 환승이 있는 경로를 골라 단계 구조를 본다
pick = None
for r in routes:
    if (r.get("properties") or {}).get("transfers", 0) >= 1:
        pick = r
        break
pick = pick or (routes[0] if routes else None)
if pick:
    print("\n대표 경로 properties:")
    print(shape(pick.get("properties"), 1))
    print(f"\nsteps = {len(pick.get('steps') or [])}개")
    for i, step in enumerate(pick.get("steps") or [], 1):
        sp = step.get("properties") or {}
        print(f"  step{i}: type={sp.get('type')} time={sp.get('time')}s "
              f"distance={sp.get('distance')}m "
              f"stops={len(sp.get('stops') or [])} "
              f"vehicles={[v.get('name') for v in (sp.get('vehicles') or [])]}")
        print(f"         guidance={sp.get('guidance')!r}")
        print(f"         속성키={sorted(sp.keys())}")
