"""에뮬레이터 검증용 계정·일정을 만든다. **검증이 끝나면 지운다.**

`emu_verify_cleanup.py` 가 이 스크립트가 만든 것을 전부 지운다. 계정 이메일에
`emuchk_` 접두사를 붙이는 이유가 그것이다 — 지울 대상을 이름으로 특정할 수 있다.

사용법
    .\\.venv\\Scripts\\python.exe scripts\\emu_verify_setup.py            # 생성
    .\\.venv\\Scripts\\python.exe scripts\\emu_verify_setup.py --alarm-in 3
        이미 만든 일정의 시작 시각을 옮겨 알람이 3분 뒤에 울리게 한다
"""

from __future__ import annotations

import argparse
import json
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

BASE = "http://127.0.0.1:8000"
KST = timezone(timedelta(hours=9))

# 검증 상태를 파일에 남긴다. 단계가 여러 번의 명령으로 나뉘므로 토큰과 id 를
# 이어서 써야 한다.
STATE = Path(__file__).resolve().parent / ".emu_verify_state.json"

PASSWORD = "swpp2026Alarm!"
HOME = {"name": "Sillim Station", "lat": 37.4842, "lng": 126.9295}
DEST = {
    "name": "Seoul National University",
    "lat": 37.4598,
    "lng": 126.9511,
    "address": "서울 관악구 관악로 1",
}


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read().decode("utf-8", "replace")
            return r.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"_raw": raw[:300]}


def load_state() -> dict:
    return json.loads(STATE.read_text(encoding="utf-8")) if STATE.exists() else {}


def save_state(state: dict) -> None:
    STATE.write_text(json.dumps(state, indent=2), encoding="utf-8")


def create() -> dict:
    email = f"emuchk_{uuid.uuid4().hex[:6]}@snu.ac.kr"
    st, body = call(
        "POST",
        "/api/auth/register",
        {
            "email": email,
            "nickname": "검증",
            "password": PASSWORD,
            "password_confirm": PASSWORD,
        },
    )
    if st != 201:
        raise SystemExit(f"회원가입 실패 {st} {body}")
    token = body["access"]
    print(f"계정   {email} / {PASSWORD}")

    st, body = call(
        "PATCH",
        "/api/profile",
        {
            "home_lat": HOME["lat"],
            "home_lng": HOME["lng"],
            "home_label": HOME["name"],
            "onboarding_prep_min": 28,
        },
        token=token,
    )
    if st != 200:
        raise SystemExit(f"집 위치 설정 실패 {st} {body}")
    print(f"집     {HOME['name']} ({HOME['lat']}, {HOME['lng']})")

    # 넉넉한 시각으로 먼저 만든다. --alarm-in 으로 좁힌다.
    start = datetime.now(KST) + timedelta(minutes=120)
    st, body = call(
        "POST",
        "/api/events",
        {
            "title": "GPS check",
            "start_at": start.isoformat(),
            "place": DEST,
            "tag_key": "class",
        },
        token=token,
    )
    if st != 201:
        raise SystemExit(f"일정 생성 실패 {st} {body}")

    plan = body.get("alarm_plan") or {}
    print(f"일정   id={body['id']}  plan={plan.get('status')}")
    print(
        f"       준비 {plan.get('prep_minutes')} + 이동 {plan.get('travel_minutes')} "
        f"+ 버퍼 {plan.get('buffer_minutes')} = {plan.get('total_minutes')}분"
    )
    print(f"알람   {plan.get('alarm_at')}")

    state = {
        "email": email,
        "password": PASSWORD,
        "token": token,
        "event_id": body["id"],
        "home": HOME,
        "dest": DEST,
        "lead_minutes": plan.get("total_minutes"),
    }
    save_state(state)
    return state


def shift(minutes: int) -> None:
    """알람이 지금부터 [minutes] 분 뒤에 울리도록 일정 시작 시각을 옮긴다."""
    state = load_state()
    if not state:
        raise SystemExit("상태 파일이 없다. 먼저 생성해야 한다")

    # 토큰이 30분이면 만료됐을 수 있다. 다시 받는다.
    st, body = call(
        "POST", "/api/auth/token", {"email": state["email"], "password": state["password"]}
    )
    if st != 200:
        raise SystemExit(f"토큰 재발급 실패 {st} {body}")
    token = body["access"]
    state["token"] = token

    lead = state.get("lead_minutes") or 61
    target_alarm = datetime.now(KST) + timedelta(minutes=minutes)
    start = target_alarm + timedelta(minutes=lead)

    st, body = call(
        "PATCH",
        f"/api/events/{state['event_id']}",
        {"start_at": start.isoformat()},
        token=token,
    )
    if st != 200:
        raise SystemExit(f"일정 수정 실패 {st} {body}")

    plan = body.get("alarm_plan") or {}
    state["lead_minutes"] = plan.get("total_minutes")
    save_state(state)

    alarm_at = plan.get("alarm_at")
    local = (
        datetime.fromisoformat(alarm_at).astimezone(KST).strftime("%H:%M:%S")
        if alarm_at
        else "-"
    )
    print(f"일정 시작  {start.strftime('%H:%M:%S')} KST")
    print(f"알람 시각  {local} KST  ({minutes}분 뒤)")
    print(f"소요 합계  {plan.get('total_minutes')}분  상태={plan.get('status')}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument(
        "--alarm-in",
        type=int,
        help="알람이 몇 분 뒤에 울리게 할지. 생략하면 계정·일정을 새로 만든다",
    )
    args = ap.parse_args()

    if args.alarm_in is None:
        create()
    else:
        shift(args.alarm_in)
