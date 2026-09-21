"""배포 서버와 내 DATABASE_URL 이 같은 DB 를 보는지 확인한다.

## 왜 필요한가

`check_deployed.py` 는 기능이 도는지만 본다. 자기 계정을 새로 만들어 검증하므로
**어느 DB 에 붙어 있어도 전부 통과한다.** 실제로 그런 일이 있었다 - 배포 서버가
Neon 이 아니라 Render 자체 Postgres 를 보고 있었는데 51개 항목이 모두 통과했다.
`/api/health` 도 200 이었다. 공용 DB 로 바꿨다고 믿고 넘어가기 쉬운 지점이다.

## 어떻게 확인하는가

시드 데이터를 비교하지 않는다. 그건 양쪽에 비슷한 데모가 있으면 헷갈린다.
대신 **한쪽으로 쓰고 다른 쪽에서 읽는다.**

    1. 배포 서버 API 로 임의 이메일 계정을 만든다
    2. 내 DATABASE_URL 로 DB 에 직접 붙어 그 이메일을 찾는다
    3. 보이면 같은 DB, 안 보이면 다른 DB

거짓 통과가 불가능하다. 우연히 같은 임의 이메일이 생길 일은 없다.

## 쓰는 법

    # .env 의 DATABASE_URL 을 쓴다
    python scripts/check_same_db.py

    # 값을 직접 주려면
    $env:DATABASE_URL="postgresql://..."; python scripts/check_same_db.py

Neon 을 쓸 때는 `-pooler` 없는 direct 주소를 넣는다.

종료 코드: 0 같은 DB / 2 다른 DB / 1 확인 실패
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND))

DEFAULT_BASE = "https://justintime-api.onrender.com"

# 콜드 스타트. 무료 인스턴스는 15분 유휴 후 잠들고 깨는 데 30초 이상 걸린다.
TIMEOUT = 120


def api(base: str, path: str, method: str = "GET", payload=None, token=None):
    url = f"{base}{path}"
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            body = r.read().decode("utf-8")
            return r.status, json.loads(body) if body else {}
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:300]}
    except Exception as e:  # noqa: BLE001
        return 0, {"_err": repr(e)[:300]}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=DEFAULT_BASE)
    parser.add_argument(
        "--keep",
        action="store_true",
        help="확인용으로 만든 계정을 지우지 않는다",
    )
    args = parser.parse_args()
    base = args.base

    print("=" * 74)
    print("배포 서버와 내 DATABASE_URL 이 같은 DB 인가")
    print("=" * 74)

    # --- 내 쪽 DB 준비 ----------------------------------------------------
    # dev 설정을 쓰되 DATABASE_URL 은 환경/.env 값을 그대로 따른다.
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")
    import django

    django.setup()

    from django.conf import settings
    from django.contrib.auth import get_user_model

    db = settings.DATABASES["default"]
    engine = db.get("ENGINE", "")
    host = db.get("HOST") or "(파일)"
    name = db.get("NAME")

    print(f"\n내 DB  : {engine.rsplit('.', 1)[-1]}  host={host}  name={name}")
    print(f"배포   : {base}")

    if "sqlite" in engine:
        print("\n내 DATABASE_URL 이 비어 있어 SQLite 를 보고 있다.")
        print("공용 DB 주소를 넣고 다시 실행할 것 (backend/.env 의 DATABASE_URL).")
        return 1

    User = get_user_model()
    try:
        before = User.objects.count()
    except Exception as e:  # noqa: BLE001
        print(f"\n내 DB 에 붙지 못했다: {e!r}")
        return 1
    print(f"내 DB 사용자 수: {before}명")

    # --- 1. 배포 서버를 깨운다 --------------------------------------------
    st, _ = api(base, "/api/health")
    if st != 200:
        print(f"\n배포 서버 health 가 {st} 다. 확인을 중단한다.")
        return 1
    print("\nhealth 200")

    # --- 2. 배포 서버로 쓴다 ----------------------------------------------
    marker = uuid.uuid4().hex[:12]
    email = f"dbprobe_{marker}@example.com"
    password = "dbprobe12345"
    st, body = api(
        base,
        "/api/auth/register",
        "POST",
        {
            "email": email,
            "nickname": "DB확인",
            "password": password,
            "password_confirm": password,
        },
    )
    if st != 201:
        print(f"\n배포 서버에 계정을 만들지 못했다: status={st} body={str(body)[:200]}")
        return 1
    print(f"배포 서버에 계정 생성: {email}")

    # --- 3. 내 DB 에서 읽는다 ---------------------------------------------
    # 연결을 새로 열어 캐시된 트랜잭션 스냅샷을 피한다.
    from django.db import connection

    connection.close()
    found = User.objects.filter(email=email).first()

    print("\n" + "=" * 74)
    if found:
        print(">>> 같은 DB 다.")
        print(f"    배포 서버가 만든 계정(id={found.id})이 내 DB 에서 보인다.")
        verdict = 0
        if not args.keep:
            # 공용 DB 를 확인용 계정으로 더럽히지 않는다.
            found.delete()
            print("    확인용 계정은 지웠다.")
        else:
            print("    --keep 이므로 남겨 둔다.")
    else:
        print(">>> **다른 DB 다.**")
        print(f"    배포 서버는 {email} 을 만들었는데 내 DB 에는 없다.")
        print("")
        print("    Render 대시보드에서 DATABASE_URL 을 확인할 것:")
        print("      Render > justintime-api > Environment > DATABASE_URL")
        print("    Neon 이면 `-pooler` 없는 direct 주소를 넣는다.")
        print("")
        print(f"    (배포 쪽 DB 에 {email} 이 남는다. 지울 방법이 없으니")
        print("     주소를 바꾼 뒤에는 무시해도 된다.)")
        verdict = 2
    print("=" * 74)
    return verdict


if __name__ == "__main__":
    raise SystemExit(main())
