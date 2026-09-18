"""카카오 경로 API 가 대안 경로를 몇 개, 얼마나 다르게 주는지 확인한다.

지금 `clients.best_route()` 는 `min(totalTime)` 하나만 쓰고 나머지 routes 를
버린다. 사용자가 고를 만한 후보가 실제로 존재하는지 봐야 선택 UI 를 만들 수 있다.

사용법:
    cd backend
    .\\.venv\\Scripts\\python.exe scripts\\probe_route_alternatives.py
"""

from __future__ import annotations

import json
import os
import ssl
import sys
import urllib.parse
import urllib.request
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BASE_DIR))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")

import django  # noqa: E402

django.setup()

from django.conf import settings  # noqa: E402

KEY = settings.KAKAO_REST_API_KEY

# 신림역 2호선 -> 서울대학교 관악캠퍼스 (E2E 에서 쓴 좌표)
HOME = (37.484267, 126.929745)
DEST = (37.459786, 126.951124)
# 좀 더 먼 경로도 본다. 신림역 -> 강남역
FAR = (37.497942, 127.027621)


def call(url: str, params: dict) -> dict | None:
    qs = urllib.parse.urlencode(params)
    req = urllib.request.Request(f"{url}?{qs}")
    req.add_header("Authorization", f"KakaoAK {KEY}")
    try:
        with urllib.request.urlopen(req, timeout=10, context=ssl.create_default_context()) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception as e:  # noqa: BLE001
        body = ""
        if hasattr(e, "read"):
            body = e.read().decode("utf-8", "replace")[:200]
        print(f"    실패 {type(e).__name__}: {e} {body}")
        return None


def base_params(start, end):
    return {
        "start_x": f"{start[1]}",
        "start_y": f"{start[0]}",
        "end_x": f"{end[1]}",
        "end_y": f"{end[0]}",
        "input_coord": "WGS84",
        "output_coord": "WGS84",
    }


def describe_transit(data):
    if not data:
        return
    print(f"    status={data.get('status')}  routes={len(data.get('routes') or [])}")
    for i, r in enumerate(data.get("routes") or []):
        p = r.get("properties") or {}
        modes = []
        detail = []
        for step in r.get("steps") or []:
            sp = step.get("properties") or {}
            t = sp.get("type")
            label = {"SUBWAY": "지하철", "BUS": "버스", "WALKING": "도보"}.get(t, t)
            if label and (not modes or modes[-1] != label):
                modes.append(label)
            veh = sp.get("vehicles") or []
            for v in veh[:1]:
                name = v.get("name") or v.get("busNo") or ""
                if name:
                    detail.append(f"{label}:{name}")
        mins = round((p.get("totalTime") or 0) / 60)
        print(
            f"      [{i}] {mins}분  {p.get('totalDistance')}m  환승{p.get('transfers')}  "
            f"{(p.get('fare') or {}).get('value')}원  {'+'.join(modes)}"
        )
        if detail:
            print(f"           {' > '.join(detail[:6])}")


print("=" * 78)
print("1) 대중교통 — 기본 파라미터  (신림역 -> 서울대 관악캠퍼스)")
print("=" * 78)
describe_transit(call("https://dapi.kakao.com/v2/routing/publictraffic", base_params(HOME, DEST)))

print()
print("=" * 78)
print("2) 대중교통 — 먼 거리  (신림역 -> 강남역)")
print("=" * 78)
describe_transit(call("https://dapi.kakao.com/v2/routing/publictraffic", base_params(HOME, FAR)))

print()
print("=" * 78)
print("3) 대안 개수를 늘리는 파라미터가 있는지 시도")
print("=" * 78)
for extra in [
    {"priority": "RECOMMEND"},
    {"priority": "TIME"},
    {"priority": "TRANSFER"},
    {"alternatives": "true"},
    {"route_count": "5"},
    {"size": "5"},
]:
    params = base_params(HOME, FAR)
    params.update(extra)
    data = call("https://dapi.kakao.com/v2/routing/publictraffic", params)
    n = len(data.get("routes") or []) if data else "ERR"
    st = data.get("status") if data else "-"
    print(f"    {extra}  ->  status={st}  routes={n}")

print()
print("=" * 78)
print("4) 도보 · 자전거 · 자동차 각각")
print("=" * 78)
for name, url in [
    ("도보", "https://dapi.kakao.com/v2/routing/walk"),
    ("자전거", "https://dapi.kakao.com/v2/routing/bicycle"),
]:
    data = call(url, base_params(HOME, DEST))
    if data and data.get("status") == "OK":
        p = ((data.get("route") or {}).get("properties")) or {}
        print(f"    {name}: {round((p.get('totalTime') or 0) / 60)}분  {p.get('totalDistance')}m")
    else:
        print(f"    {name}: status={data.get('status') if data else 'ERR'}")

car = call(
    "https://apis-navi.kakaomobility.com/v1/directions",
    {
        "origin": f"{HOME[1]},{HOME[0]}",
        "destination": f"{DEST[1]},{DEST[0]}",
        "priority": "RECOMMEND",
        "alternatives": "true",
    },
)
if car:
    routes = car.get("routes") or []
    print(f"    자동차: routes={len(routes)}")
    for i, r in enumerate(routes):
        s = r.get("summary") or {}
        print(
            f"      [{i}] {round((s.get('duration') or 0) / 60)}분  {s.get('distance')}m  "
            f"택시 {(s.get('fare') or {}).get('taxi')}원  통행료 {(s.get('fare') or {}).get('toll')}원"
        )
