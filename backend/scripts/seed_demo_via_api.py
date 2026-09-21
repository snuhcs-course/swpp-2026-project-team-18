"""배포 서버에 데모 계정을 HTTP 로 만든다.

`manage.py seed_demo` 는 서버 셸이 필요하다. 이 스크립트는 공개 API 만 쓰므로
로컬에서 배포 서버를 향해 실행할 수 있다.

    python scripts/seed_demo_via_api.py
    python scripts/seed_demo_via_api.py --base https://다른주소

멱등하다. 계정이 이미 있으면 로그인해서 집 위치만 맞춘다.

**비밀번호를 바꾸지 않는다.** 공용 서버에 두는 공개 계정이므로 팀 전체가
같은 값을 알고 있어야 한다. 이미 있는 계정의 비밀번호를 API 로 바꿀 수는
없으므로, 로그인이 안 되면 그 사실만 알리고 끝낸다.
"""

import argparse
import json
import sys
import urllib.error
import urllib.request

EMAIL = "demo@demo.com"
PASSWORD = "demo1234"
NICKNAME = "데모"

# manage.py seed_demo 와 같은 값을 쓴다. 두 경로가 다른 상태를 만들면
# "어느 쪽으로 만들었나" 에 따라 화면이 달라진다.
HOME = {"lat": 37.484267, "lng": 126.929745, "label": "신림역"}
PREP_MINUTES = 30


def call(base, path, method="GET", body=None, token=None, timeout=120):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(f"{base}{path}", data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode("utf-8")
            return r.status, (json.loads(raw) if raw.strip() else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:300]}
    except Exception as e:
        return 0, {"_error": f"{type(e).__name__}: {e}"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="https://justintime-api.onrender.com")
    base = parser.parse_args().base.rstrip("/")

    print(f"대상: {base}")

    st, body = call(base, "/api/health")
    if st != 200:
        print(f"서버에 닿지 못했다 (status={st}). 무료 플랜이면 잠들어 있을 수 있다.")
        return 1

    # 1. 이미 있는지 로그인으로 확인한다.
    st, body = call(base, "/api/auth/token", "POST", {"email": EMAIL, "password": PASSWORD})
    token = body.get("access") if st == 200 else None

    if token:
        print(f"이미 존재하고 로그인된다: {EMAIL}")
    else:
        st, body = call(
            base,
            "/api/auth/register",
            "POST",
            {
                "email": EMAIL,
                "nickname": NICKNAME,
                "password": PASSWORD,
                "password_confirm": PASSWORD,
            },
        )
        if st == 201:
            token = body.get("access")
            print(f"생성했다: {EMAIL} / {PASSWORD}")
        else:
            print(f"생성 실패 (status={st}): {str(body)[:300]}")
            print(
                "\n계정이 이미 있는데 비밀번호가 다른 경우일 수 있다. "
                "그때는 서버 셸에서 `python manage.py seed_demo` 를 돌려야 한다."
            )
            return 1

    if not token:
        print("토큰을 얻지 못했다.")
        return 1

    # 2. 집 위치. 없으면 일정을 넣어도 계획이 no_home 으로만 나온다.
    st, body = call(
        base,
        "/api/profile",
        "PATCH",
        {
            "home_lat": HOME["lat"],
            "home_lng": HOME["lng"],
            "home_label": HOME["label"],
            "onboarding_prep_min": PREP_MINUTES,
        },
        token=token,
    )
    if st == 200:
        print(f"집 위치 설정: {body.get('home_label')} / 준비 {body.get('onboarding_prep_min')}분")
    else:
        print(f"집 위치 설정 실패 (status={st}): {str(body)[:200]}")
        return 1

    st, body = call(base, "/api/events", token=token)
    n = len((body or {}).get("results") or []) if st == 200 else -1
    print(f"현재 일정 {n}건")

    print("\n완료. 앱에서 이 계정으로 로그인할 수 있다.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
