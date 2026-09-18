"""카카오맵 경로 API 를 공식 엔드포인트로 검증한다 (F4.1, BE-P0-06).

공식 문서: https://developers.kakao.com/docs/latest/ko/kakaomap/rest-api

    대중교통  GET https://dapi.kakao.com/v2/routing/publictraffic
    도보      GET https://dapi.kakao.com/v2/routing/walk
    자전거    GET https://dapi.kakao.com/v2/routing/bicycle
    파라미터  start_x, start_y, end_x, end_y (origin/destination 이 아니다)
    헤더      Authorization: KakaoAK ${REST_API_KEY}

키를 인자로 받는다. 파일에 적어두지 않는다.

사용법:
    .\\.venv\\Scripts\\python.exe scripts\\probe_kakao_routing.py <키1> [<키2> ...]

인자가 없으면 .env 의 KAKAO_REST_API_KEY 를 쓴다.
"""

from __future__ import annotations

import json
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

# 신림역 -> 서울대 관악캠퍼스 (로컬 검색으로 확인한 좌표)
START = ("126.92973494922707", "37.48453")
END = ("126.9511239870991", "37.45978574975834")


def http(url: str, key: str):
    req = urllib.request.Request(url, method="GET")
    req.add_header("Authorization", f"KakaoAK {key}")
    try:
        with urllib.request.urlopen(
            req, timeout=15, context=ssl.create_default_context()
        ) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        return -1, f"{type(e).__name__}: {e}"


def short(s: str, n: int = 200) -> str:
    return " ".join(s.split())[:n]


def routing_url(path: str) -> str:
    qs = urllib.parse.urlencode(
        {
            "start_x": START[0],
            "start_y": START[1],
            "end_x": END[0],
            "end_y": END[1],
            "s_name": "신림역",
            "e_name": "서울대학교",
            "input_coord": "WGS84",
            "output_coord": "WGS84",
        }
    )
    return f"https://dapi.kakao.com/v2/routing/{path}?{qs}"


def describe_transit(body: str) -> str:
    d = json.loads(body)
    status = d.get("status")
    if status != "OK":
        return f"status={status}"
    props = d.get("properties") or {}
    routes = d.get("routes") or []
    parts = [
        f"status=OK",
        f"경로수={props.get('total')}",
        f"(버스 {props.get('bus')} / 지하철 {props.get('subway')} / 혼합 {props.get('busAndSubway')})",
    ]
    if routes:
        rp = routes[0].get("properties") or {}
        fare = rp.get("fare") or {}
        parts.append(
            f"첫경로: {rp.get('type')} {rp.get('totalTime')}초 "
            f"{rp.get('totalDistance')}m 환승{rp.get('transfers')}회 "
            f"{fare.get('value')}원 단계{len(routes[0].get('steps') or [])}개"
        )
        # 변동성 정보가 응답에 있는지 확인한다 (BE-P0-06 의 핵심 질문)
        keys = set()
        for step in (routes[0].get("steps") or []):
            keys |= set((step.get("properties") or {}).keys())
        parts.append(f"step 속성={sorted(keys)}")
    return "  ".join(parts)


def describe_walk(body: str) -> str:
    d = json.loads(body)
    top = sorted(d.keys())
    out = [f"최상위키={top}"]
    routes = d.get("routes") or []
    if routes:
        rp = routes[0].get("properties") or routes[0].get("summary") or {}
        out.append(f"첫경로속성={sorted(rp.keys())}")
        out.append(f"거리={rp.get('totalDistance') or rp.get('distance')}")
        out.append(f"시간={rp.get('totalTime') or rp.get('duration')}")
    elif "status" in d:
        out.append(f"status={d['status']}")
    return "  ".join(out)


def probe(label: str, key: str) -> dict:
    print("=" * 74)
    print(f"{label}  (len={len(key)}, head={key[:4]}…)")
    print("=" * 74)
    result = {}

    # 0) 키 자체가 유효한지 — 로컬 검색으로 확인
    q = urllib.parse.urlencode({"query": "서울대학교", "size": 1})
    st, body = http(f"https://dapi.kakao.com/v2/local/search/keyword.json?{q}", key)
    ok = st == 200
    result["local"] = st
    name = None
    if ok:
        docs = json.loads(body).get("documents") or []
        name = docs[0].get("place_name") if docs else None
    print(f"  [{'OK  ' if ok else 'FAIL'}] 로컬 검색       status={st} {name or short(body, 120)}")

    # 1) 대중교통
    st, body = http(routing_url("publictraffic"), key)
    result["publictraffic"] = st
    if st == 200:
        try:
            print(f"  [OK  ] 대중교통 경로   status=200  {describe_transit(body)}")
        except Exception as e:
            print(f"  [OK  ] 대중교통 경로   status=200  (파싱 실패 {e}) {short(body)}")
    else:
        print(f"  [FAIL] 대중교통 경로   status={st} {short(body)}")

    # 2) 도보
    st, body = http(routing_url("walk"), key)
    result["walk"] = st
    if st == 200:
        try:
            print(f"  [OK  ] 도보 경로       status=200  {describe_walk(body)}")
        except Exception as e:
            print(f"  [OK  ] 도보 경로       status=200  (파싱 실패 {e}) {short(body)}")
    else:
        print(f"  [FAIL] 도보 경로       status={st} {short(body)}")

    # 3) 자전거 (문서상 4종에 포함. 경로 패턴 추정)
    st, body = http(routing_url("bicycle"), key)
    result["bicycle"] = st
    print(
        f"  [{'OK  ' if st == 200 else '----'}] 자전거 경로     status={st} "
        f"{'' if st == 200 else short(body, 100)}"
    )

    # 4) 자동차 (모빌리티 호스트)
    car = (
        "https://apis-navi.kakaomobility.com/v1/directions"
        f"?origin={START[0]},{START[1]}&destination={END[0]},{END[1]}"
    )
    st, body = http(car, key)
    result["car"] = st
    if st == 200:
        summary = ((json.loads(body).get("routes") or [{}])[0].get("summary") or {})
        print(
            f"  [OK  ] 자동차 길찾기   status=200  "
            f"{summary.get('distance')}m {summary.get('duration')}초"
        )
    else:
        print(f"  [FAIL] 자동차 길찾기   status={st} {short(body, 120)}")

    print()
    return result


def main() -> int:
    keys = sys.argv[1:]
    if not keys:
        from dotenv import dotenv_values

        env = dotenv_values(BASE_DIR / ".env")
        k = env.get("KAKAO_REST_API_KEY")
        if not k:
            print("키를 인자로 넘기거나 .env 의 KAKAO_REST_API_KEY 를 채운다.")
            return 1
        keys = [k]

    results = {}
    for i, key in enumerate(keys, 1):
        results[f"키{i}({key[:4]}…)"] = probe(f"키{i}", key)

    print("=" * 74)
    print("요약  (200 이면 사용 가능)")
    print("=" * 74)
    cols = ["local", "publictraffic", "walk", "bicycle", "car"]
    names = {
        "local": "로컬검색",
        "publictraffic": "대중교통",
        "walk": "도보",
        "bicycle": "자전거",
        "car": "자동차",
    }
    header = "  " + "키".ljust(14) + "".join(names[c].ljust(11) for c in cols)
    print(header)
    for label, r in results.items():
        row = "  " + label.ljust(14)
        for c in cols:
            row += str(r.get(c)).ljust(11)
        print(row)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
