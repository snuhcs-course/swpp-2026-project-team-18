"""인증 API 를 실제 HTTP 로 검증한다 (BE-P0 인증).

서버가 떠 있어야 한다:
    .\\.venv\\Scripts\\python.exe manage.py runserver 0.0.0.0:8000

실행:
    .\\.venv\\Scripts\\python.exe scripts\\check_auth_api.py

매 실행마다 새 이메일을 쓰므로 반복 실행해도 충돌하지 않는다.
"""


from __future__ import annotations

# --- 공용 DB 보호 -----------------------------------------------------------
# 이 스크립트는 검증용 계정과 일정을 만든다. 공용 DB 에서 돌리면 팀 전체가
# 보는 목록이 테스트 데이터로 채워진다. 실제로 그런 사고가 있었다 -
# 근거와 재현 조건은 scripts/_local_guard.py 상단에 적어 두었다.
#
# **부수효과가 생기기 전에** 돌아야 하므로 맨 위에 둔다.
import sys as _sys
from pathlib import Path as _Path

_sys.path.insert(0, str(_Path(__file__).resolve().parent))
from _local_guard import require_local_database  # noqa: E402

require_local_database()
# ---------------------------------------------------------------------------


import json
import urllib.error
import urllib.request
import uuid

BASE = "http://127.0.0.1:8000"
results: list[tuple[str, bool, str]] = []


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:300]}
    except Exception as e:
        return -1, {"_exc": f"{type(e).__name__}: {e}"}


def check(name, cond, note):
    results.append((name, bool(cond), note))
    print(f"{'[ OK ]' if cond else '[FAIL]'} {name}\n       {note}\n")


def err(payload):
    """공통 에러 포맷에서 code/message 를 뽑는다."""
    e = (payload or {}).get("error") or {}
    return e.get("code"), e.get("message")


suffix = uuid.uuid4().hex[:8]
EMAIL = f"tester_{suffix}@snu.ac.kr"
PASSWORD = "swpp2026Alarm!"
NICK = "테스터"

print("=" * 74)
print(f"인증 API 검증  base={BASE}  email={EMAIL}")
print("=" * 74 + "\n")

# 0) 서버 살아있는지
st, body = call("GET", "/api/health")
check("health", st == 200 and body.get("ok") is True, f"status={st} body={body}")
if st != 200:
    print("서버가 떠 있지 않다. runserver 를 먼저 실행한다.")
    raise SystemExit(1)

# 1) 회원가입
st, body = call("POST", "/api/auth/register", {
    "email": EMAIL, "nickname": NICK,
    "password": PASSWORD, "password_confirm": PASSWORD,
})
ok = st == 201 and body.get("user_id") and body.get("access") and body.get("refresh")
check("회원가입 201", ok,
      f"status={st} user_id={body.get('user_id')} "
      f"user={body.get('user')} access={'있음' if body.get('access') else '없음'}")
access = body.get("access")
refresh = body.get("refresh")

# 2) 같은 이메일 재가입 차단
st, body = call("POST", "/api/auth/register", {
    "email": EMAIL, "nickname": NICK,
    "password": PASSWORD, "password_confirm": PASSWORD,
})
code, msg = err(body)
check("중복 이메일 400", st == 400 and msg, f"status={st} code={code} message={msg!r}")

# 3) 대소문자만 다른 이메일도 차단
st, body = call("POST", "/api/auth/register", {
    "email": EMAIL.upper(), "nickname": NICK,
    "password": PASSWORD, "password_confirm": PASSWORD,
})
code, msg = err(body)
check("대소문자 다른 중복 400", st == 400, f"status={st} code={code} message={msg!r}")

# 4) 비밀번호 확인 불일치
st, body = call("POST", "/api/auth/register", {
    "email": f"x_{suffix}@snu.ac.kr", "nickname": NICK,
    "password": PASSWORD, "password_confirm": PASSWORD + "x",
})
code, msg = err(body)
check("비밀번호 확인 불일치 400", st == 400 and "일치" in (msg or ""),
      f"status={st} message={msg!r}")

# 5) 약한 비밀번호 거부 (Django 검증기)
st, body = call("POST", "/api/auth/register", {
    "email": f"y_{suffix}@snu.ac.kr", "nickname": NICK,
    "password": "1234", "password_confirm": "1234",
})
code, msg = err(body)
check("약한 비밀번호 400", st == 400, f"status={st} message={msg!r}")

# 6) 로그인
st, body = call("POST", "/api/auth/token", {"email": EMAIL, "password": PASSWORD})
ok = st == 200 and body.get("access") and body.get("user", {}).get("nickname") == NICK
check("로그인 200", ok,
      f"status={st} user={body.get('user')} access={'있음' if body.get('access') else '없음'}")
if st == 200:
    access = body["access"]
    refresh = body["refresh"]

# 7) 틀린 비밀번호
st, body = call("POST", "/api/auth/token", {"email": EMAIL, "password": "WrongPass123!"})
code, msg = err(body)
check("틀린 비밀번호 401", st == 401, f"status={st} code={code} message={msg!r}")

# 8) 없는 계정 — 존재 여부가 드러나지 않아야 한다
st2, body2 = call("POST", "/api/auth/token",
                  {"email": f"nobody_{suffix}@snu.ac.kr", "password": PASSWORD})
_, msg2 = err(body2)
check("없는 계정 401 (메시지 동일)", st2 == 401 and msg2 == msg,
      f"status={st2} message={msg2!r}  틀린비번과 동일={msg2 == msg}")

# 9) 토큰으로 me 조회
st, body = call("GET", "/api/auth/me", token=access)
check("me 200", st == 200 and body.get("email") == EMAIL, f"status={st} body={body}")

# 10) 토큰 없이 me 조회
st, body = call("GET", "/api/auth/me")
code, msg = err(body)
check("토큰 없이 me 401", st == 401, f"status={st} code={code} message={msg!r}")

# 11) 잘못된 토큰
st, body = call("GET", "/api/auth/me", token="not.a.real.token")
code, msg = err(body)
check("잘못된 토큰 401", st == 401, f"status={st} code={code} message={msg!r}")

# 12) refresh 로 access 재발급
st, body = call("POST", "/api/auth/token/refresh", {"refresh": refresh})
new_access = body.get("access")
check("refresh 200", st == 200 and new_access, f"status={st} access={'있음' if new_access else '없음'}")

# 13) 재발급한 access 가 실제로 통하는지
if new_access:
    st, body = call("GET", "/api/auth/me", token=new_access)
    check("재발급 access 로 me 200", st == 200 and body.get("email") == EMAIL,
          f"status={st} email={body.get('email')}")

# 14) 데모 계정 (마이그레이션 시드)
st, body = call("POST", "/api/auth/token", {"email": "demo@demo.com", "password": "demo1234"})
check("데모 계정 로그인 200", st == 200 and body.get("access"),
      f"status={st} user={body.get('user')}")

# 15) 에러 포맷이 공통 규칙을 따르는지
st, body = call("POST", "/api/auth/register", {"email": "not-an-email"})
shape = isinstance(body.get("error"), dict) and {"code", "message", "details"} <= set(body["error"])
check("에러 포맷 {error:{code,message,details}}", st == 400 and shape,
      f"status={st} keys={sorted((body.get('error') or {}).keys())} "
      f"details={json.dumps((body.get('error') or {}).get('details'), ensure_ascii=False)[:160]}")

# 16) 프로필이 함께 생성됐는지 (ORM 으로 직접 확인)
try:
    import os
    import sys
    from pathlib import Path
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")
    import django
    django.setup()

# 공용/원격 DB 에서 돌리지 못하게 막는다. 이 스크립트는 검증용 계정과


    from apps.accounts.models import Profile, User  # noqa: E402
    u = User.objects.get(email=EMAIL)
    p = Profile.objects.filter(user=u).first()
    check("회원가입 시 프로필 생성", p is not None,
          f"default_tau={getattr(p, 'default_tau', None)} timezone={getattr(p, 'timezone', None)}")
    check("비밀번호가 해시로 저장됨",
          u.password.startswith("pbkdf2_") and PASSWORD not in u.password,
          f"algo={u.password.split('$')[0]} 원문포함={PASSWORD in u.password}")
except Exception as e:
    check("ORM 확인", False, f"{type(e).__name__}: {e}")

print("=" * 74)
print("요약")
print("=" * 74)
w = max(len(n) for n, _, _ in results)
for name, ok, _ in results:
    print(f"  {name:{w}}  {'OK' if ok else 'FAIL'}")
n_ok = sum(1 for _, ok, _ in results if ok)
print(f"\n  {n_ok}/{len(results)} 통과")
raise SystemExit(0 if n_ok == len(results) else 1)
