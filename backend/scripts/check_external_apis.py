"""외부 API 키가 실제로 호출되는지 점검한다.

표준 라이브러리만 쓴다(google-auth 만 선택적). 팀원 누구나 키를 받은 뒤
바로 돌려서 자기 키가 살아있는지 확인할 수 있게 만든 스크립트다.

실행:
    cd backend
    .\\.venv\\Scripts\\python.exe scripts\\check_external_apis.py

키 값은 절대 출력하지 않는다. 길이와 앞 4자만 보여준다.
"""

from __future__ import annotations

import json
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
KST = timezone(timedelta(hours=9))

# ---------------------------------------------------------------------------
# .env 로딩 (python-dotenv 가 이미 의존성에 있다)
# ---------------------------------------------------------------------------
sys.path.insert(0, str(BASE_DIR))
try:
    from dotenv import dotenv_values
except ImportError:
    print("python-dotenv 가 없다. venv 를 확인한다.")
    raise SystemExit(1)

ENV = dotenv_values(BASE_DIR / ".env")

results: list[tuple[str, str, str]] = []  # (이름, 상태, 비고)


def mask(value: str | None) -> str:
    if not value:
        return "(비어 있음)"
    return f"len={len(value)} head={value[:4]}..."


def record(name: str, ok: bool | None, note: str) -> None:
    status = {True: "OK", False: "FAIL", None: "SKIP"}[ok]
    results.append((name, status, note))
    icon = {"OK": "[ OK ]", "FAIL": "[FAIL]", "SKIP": "[SKIP]"}[status]
    print(f"{icon} {name}\n       {note}\n")


def http(
    url: str,
    headers: dict[str, str] | None = None,
    data: bytes | None = None,
    method: str = "GET",
    timeout: int = 15,
) -> tuple[int, str]:
    """(status, body) 를 돌려준다. 예외를 밖으로 던지지 않는다."""
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    ctx = ssl.create_default_context()
    try:
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # 네트워크·타임아웃·SSL
        return -1, f"{type(e).__name__}: {e}"


def head(body: str, n: int = 220) -> str:
    one = " ".join(body.split())
    return one[:n] + ("..." if len(one) > n else "")


print("=" * 74)
print("외부 API 점검")
print("=" * 74)
for key in (
    "KAKAO_REST_API_KEY",
    "KAKAO_MOBILITY_KEY",
    "KMA_API_KEY",
    "OPENAI_API_KEY",
    "OPENAI_MODEL",
    "FCM_CREDENTIALS_PATH",
):
    shown = ENV.get(key) if key in ("OPENAI_MODEL", "FCM_CREDENTIALS_PATH") else mask(ENV.get(key))
    print(f"  {key:22} {shown or '(비어 있음)'}")
print("=" * 74 + "\n")


# ---------------------------------------------------------------------------
# 1. 카카오 로컬 검색 (F3.4)
# ---------------------------------------------------------------------------
kakao = ENV.get("KAKAO_REST_API_KEY") or ""
if not kakao:
    record("카카오 로컬 검색", None, "KAKAO_REST_API_KEY 없음")
else:
    q = urllib.parse.urlencode({"query": "서울대학교", "size": 1})
    st, body = http(
        f"https://dapi.kakao.com/v2/local/search/keyword.json?{q}",
        {"Authorization": f"KakaoAK {kakao}"},
    )
    if st == 200:
        docs = json.loads(body).get("documents", [])
        place = docs[0] if docs else {}
        record(
            "카카오 로컬 검색",
            True,
            f"200. place_name={place.get('place_name')!r} x={place.get('x')} y={place.get('y')}",
        )
        SNU_X, SNU_Y = place.get("x"), place.get("y")
    else:
        record("카카오 로컬 검색", False, f"status={st} {head(body)}")
        SNU_X = SNU_Y = None

# 목적지(서울대) → 출발지(신림역) 좌표. 경로 API 테스트에 쓴다.
if kakao:
    q = urllib.parse.urlencode({"query": "신림역", "size": 1})
    st, body = http(
        f"https://dapi.kakao.com/v2/local/search/keyword.json?{q}",
        {"Authorization": f"KakaoAK {kakao}"},
    )
    if st == 200:
        docs = json.loads(body).get("documents", [])
        if docs:
            SL_X, SL_Y = docs[0]["x"], docs[0]["y"]
        else:
            SL_X = SL_Y = None
    else:
        SL_X = SL_Y = None
else:
    SL_X = SL_Y = None


# ---------------------------------------------------------------------------
# 2. 카카오맵 경로 API (F4.1) — 공식 엔드포인트
#    https://developers.kakao.com/docs/latest/ko/kakaomap/rest-api
#    파라미터는 start_x/start_y/end_x/end_y 다. origin/destination 이 아니다.
# ---------------------------------------------------------------------------
if kakao and SNU_X and SL_X:
    route_qs = urllib.parse.urlencode({
        "start_x": SL_X, "start_y": SL_Y,
        "end_x": SNU_X, "end_y": SNU_Y,
        "input_coord": "WGS84", "output_coord": "WGS84",
    })
    auth = {"Authorization": f"KakaoAK {kakao}"}

    # 대중교통
    st, body = http(f"https://dapi.kakao.com/v2/routing/publictraffic?{route_qs}", auth)
    if st == 200:
        d = json.loads(body)
        p = d.get("properties") or {}
        first = (d.get("routes") or [{}])[0].get("properties") or {}
        record(
            "카카오 대중교통 경로",
            d.get("status") == "OK",
            f"200 status={d.get('status')} 경로 {p.get('total')}개 "
            f"(버스 {p.get('bus')}/지하철 {p.get('subway')}/혼합 {p.get('busAndSubway')}) "
            f"첫경로 {first.get('totalTime')}초 {first.get('totalDistance')}m "
            f"환승 {first.get('transfers')}회",
        )
    else:
        record("카카오 대중교통 경로", False, f"status={st} {head(body)}")

    # 도보
    st, body = http(f"https://dapi.kakao.com/v2/routing/walk?{route_qs}", auth)
    if st == 200:
        d = json.loads(body)
        rp = ((d.get("route") or {}).get("properties") or {})
        record(
            "카카오 도보 경로",
            d.get("status") == "OK",
            f"200 status={d.get('status')} {rp.get('totalTime')}초 {rp.get('totalDistance')}m",
        )
    else:
        record("카카오 도보 경로", False, f"status={st} {head(body)}")

    # 자동차 (모빌리티 호스트, origin/destination 형식)
    st, body = http(
        "https://apis-navi.kakaomobility.com/v1/directions"
        f"?origin={SL_X},{SL_Y}&destination={SNU_X},{SNU_Y}",
        auth,
    )
    if st == 200:
        s = ((json.loads(body).get("routes") or [{}])[0].get("summary") or {})
        record("카카오 자동차 길찾기", True, f"200 {s.get('distance')}m {s.get('duration')}초")
    else:
        record("카카오 자동차 길찾기", False, f"status={st} {head(body)}")
else:
    record("카카오 경로", None, "좌표를 못 구해 건너뜀")


# ---------------------------------------------------------------------------
# 3. 카카오모빌리티 자동차 길찾기 (F4.2) — 제휴 키
# ---------------------------------------------------------------------------
mob = ENV.get("KAKAO_MOBILITY_KEY") or ""
if not mob:
    record("카카오모빌리티 자동차", None, "KAKAO_MOBILITY_KEY 없음")
elif not (SNU_X and SL_X):
    record("카카오모빌리티 자동차", None, "좌표를 못 구해 건너뜀")
else:
    url = (
        "https://apis-navi.kakaomobility.com/v1/directions"
        f"?origin={SL_X},{SL_Y}&destination={SNU_X},{SNU_Y}"
    )
    st, body = http(url, {"Authorization": f"KakaoAK {mob}"})
    if st == 200:
        d = json.loads(body)
        summary = (d.get("routes") or [{}])[0].get("summary", {})
        record(
            "카카오모빌리티 자동차",
            True,
            f"200. distance={summary.get('distance')}m duration={summary.get('duration')}s",
        )
    else:
        record(
            "카카오모빌리티 자동차",
            False,
            f"status={st} {head(body)}  (제휴 미승인이면 401/403 이 정상)",
        )


# ---------------------------------------------------------------------------
# 4. 기상청 단기예보 — API허브(apihub.kma.go.kr). authKey 파라미터를 쓴다.
#    공공데이터포털(data.go.kr)은 serviceKey 이고 호스트도 다르다.
# ---------------------------------------------------------------------------
kma = ENV.get("KMA_API_KEY") or ""
if not kma:
    record("기상청 단기예보", None, "KMA_API_KEY 없음")
else:
    now = datetime.now(KST)
    base = now - timedelta(hours=4)  # 발표 지연을 넉넉히 피한다
    slots = [2, 5, 8, 11, 14, 17, 20, 23]
    hour = max([s for s in slots if s <= base.hour], default=23)
    if hour == 23 and base.hour < 2:
        base = base - timedelta(days=1)
    base_date = base.strftime("%Y%m%d")
    base_time = f"{hour:02d}00"

    common = {
        "pageNo": "1",
        "numOfRows": "8",
        "dataType": "JSON",
        "base_date": base_date,
        "base_time": base_time,
        "nx": "60",
        "ny": "127",
    }
    trials = [
        (
            "API허브(authKey)",
            "https://apihub.kma.go.kr/api/typ02/openApi/VilageFcstInfoService_2.0/getVilageFcst?"
            + urllib.parse.urlencode({**common, "authKey": kma}),
        ),
        (
            "공공데이터포털(serviceKey)",
            "https://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst?"
            + urllib.parse.urlencode({**common, "serviceKey": kma}),
        ),
    ]
    for label, url in trials:
        st, body = http(url)
        ok = False
        note = f"status={st}"
        if st == 200:
            try:
                d = json.loads(body)
                hdr = d["response"]["header"]
                code, msg = hdr.get("resultCode"), hdr.get("resultMsg")
                if code in ("00", "0"):
                    items = d["response"]["body"]["items"]["item"]
                    cats = sorted({i["category"] for i in items})
                    ok = True
                    note = (
                        f"200 resultCode={code} base={base_date} {base_time} "
                        f"categories={cats}"
                    )
                else:
                    note = f"200 이지만 resultCode={code} resultMsg={msg!r}"
            except Exception:
                note = f"200 이지만 JSON 아님. {head(body, 160)}"
        else:
            note += f" {head(body, 160)}"
        record(f"기상청 단기예보 [{label}]", ok, note)


# ---------------------------------------------------------------------------
# 5. OpenAI — 키 유효성 + Structured Outputs(strict) 지원 모델 후보
# ---------------------------------------------------------------------------
oai = ENV.get("OPENAI_API_KEY") or ""
if not oai:
    record("OpenAI", None, "OPENAI_API_KEY 없음")
else:
    st, body = http(
        "https://api.openai.com/v1/models",
        {"Authorization": f"Bearer {oai}"},
    )
    if st == 200:
        ids = sorted(m["id"] for m in json.loads(body).get("data", []))
        # 파싱 용도로 값싸고 strict 지원 가능성이 높은 후보만 추린다
        picks = [
            i
            for i in ids
            if any(t in i for t in ("mini", "nano", "flash", "small"))
            and not any(
                t in i
                for t in ("audio", "realtime", "image", "tts", "whisper", "embedding", "moderation", "transcribe", "search")
            )
        ]
        record(
            "OpenAI 키 유효성",
            True,
            f"200. 사용 가능 모델 {len(ids)}개. 경량 후보: {picks[:12]}",
        )
    else:
        record("OpenAI 키 유효성", False, f"status={st} {head(body)}")


# ---------------------------------------------------------------------------
# 6. Firebase 서비스 계정 — 파일 존재/형식 확인. 토큰 발급은 google-auth 필요.
# ---------------------------------------------------------------------------
fcm_path = (ENV.get("FCM_CREDENTIALS_PATH") or "").strip()
if not fcm_path:
    record("Firebase 서비스 계정", None, "FCM_CREDENTIALS_PATH 없음")
elif "PRIVATE KEY" in fcm_path or "MII" in fcm_path:
    record(
        "Firebase 서비스 계정",
        False,
        "FCM_CREDENTIALS_PATH 에 키 내용이 직접 들어 있다. 파일 '경로'여야 한다.",
    )
else:
    p = Path(fcm_path)
    if not p.exists():
        record("Firebase 서비스 계정", False, f"파일이 없다: {fcm_path}")
    else:
        try:
            d = json.loads(p.read_text(encoding="utf-8"))
            need = {"type", "project_id", "private_key", "client_email", "token_uri"}
            missing = need - set(d)
            if missing:
                record("Firebase 서비스 계정", False, f"필드 누락: {sorted(missing)}")
            else:
                inside_repo = str(p.resolve()).startswith(str(BASE_DIR.parent.resolve()))
                warn = "  ** 저장소 안에 있다. 밖으로 옮겨야 한다 **" if inside_repo else ""
                record(
                    "Firebase 서비스 계정 파일",
                    True,
                    f"형식 정상. project_id={d['project_id']} "
                    f"client_email={d['client_email']}{warn}",
                )
                # 토큰 발급까지 확인 (google-auth 가 있으면)
                try:
                    from google.oauth2 import service_account  # type: ignore
                    import google.auth.transport.requests  # type: ignore

                    creds = service_account.Credentials.from_service_account_file(
                        str(p),
                        scopes=["https://www.googleapis.com/auth/firebase.messaging"],
                    )
                    creds.refresh(google.auth.transport.requests.Request())
                    record(
                        "Firebase 액세스 토큰",
                        bool(creds.token),
                        "토큰 발급 성공. FCM 발송 준비됨"
                        if creds.token
                        else "토큰이 비어 있다",
                    )
                except ImportError:
                    record(
                        "Firebase 액세스 토큰",
                        None,
                        "google-auth 미설치로 건너뜀. P4 에서 firebase-admin 추가 시 확인",
                    )
                except Exception as e:
                    record(
                        "Firebase 액세스 토큰",
                        False,
                        f"{type(e).__name__}: {e}",
                    )
        except Exception as e:
            record("Firebase 서비스 계정", False, f"JSON 파싱 실패: {e}")


# ---------------------------------------------------------------------------
# 요약
# ---------------------------------------------------------------------------
print("=" * 74)
print("요약")
print("=" * 74)
w = max(len(n) for n, _, _ in results)
for name, status, _ in results:
    print(f"  {name:{w}}  {status}")
ok_n = sum(1 for _, s, _ in results if s == "OK")
fail_n = sum(1 for _, s, _ in results if s == "FAIL")
skip_n = sum(1 for _, s, _ in results if s == "SKIP")
print(f"\n  OK {ok_n} / FAIL {fail_n} / SKIP {skip_n}")
