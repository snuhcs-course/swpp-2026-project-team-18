"""에뮬레이터 검증 결과를 보고하고, 검증용 가짜 데이터를 지운다.

`emu_verify_setup.py` 가 만든 계정(`emuchk_` 접두사)과 그 계정의 모든 일정·관측을
삭제한다. 검증 데이터가 DB 에 남으면 나중에 학습 재료로 섞여 들어간다 — 걸어서
만든 관측이 아니라 손으로 주입한 좌표이므로 반드시 지워야 한다.

사용법
    .\\.venv\\Scripts\\python.exe scripts\\emu_verify_cleanup.py --report
    .\\.venv\\Scripts\\python.exe scripts\\emu_verify_cleanup.py --apply
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BASE_DIR))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")

import django  # noqa: E402

django.setup()

from django.contrib.auth import get_user_model  # noqa: E402
from django.db.models import Q  # noqa: E402
from django.utils import timezone as dj_timezone  # noqa: E402

from apps.events.models import Event  # noqa: E402
from apps.observations.models import TripObservation  # noqa: E402
from apps.planning.models import AlarmPlan  # noqa: E402

# 검증이 만드는 계정 접두사.
#
# `emuchk_` 는 emu_verify_setup.py, `obs_` 는 check_observations_api.py 가 만든다.
# **둘 다 가짜 관측을 남긴다** — 손으로 주입한 좌표이므로 학습 재료에 섞이면
# 안 된다. 다른 검증 스크립트(`tester_`, `ev_`)는 관측을 만들지 않아 여기서
# 건드리지 않는다.
PREFIXES = ("emuchk_", "obs_")
STATE = Path(__file__).resolve().parent / ".emu_verify_state.json"

User = get_user_model()


def kst(dt):
    """DB 는 UTC 로 저장한다. 사람이 읽을 KST 로 바꾼다.

    이걸 빼먹으면 "10:57 에 관측했는데 01:57 로 찍힌다" 로 읽혀 9시간
    어긋난 버그처럼 보인다. 실제로 처음 출력이 그랬다.
    """
    return dj_timezone.localtime(dt) if dt else None


def targets():
    q = Q()
    for prefix in PREFIXES:
        q |= Q(email__startswith=prefix)
    return User.objects.filter(q)


def report() -> None:
    users = targets()
    print(f"검증 계정 {users.count()}개\n")

    for user in users:
        events = Event.objects.filter(user=user).select_related("place")
        obs = (
            TripObservation.objects.filter(user=user)
            .select_related("event", "event__alarm_plan")
            .order_by("observed_at")
        )
        print(f"  {user.email}")
        print(f"    일정 {events.count()}건 · 관측 {obs.count()}건")

        for event in events:
            plan = getattr(event, "alarm_plan", None)
            print(f"    · 일정 {event.id} {event.title}  시작 {kst(event.start_at):%H:%M}")
            if plan and plan.alarm_at:
                print(
                    f"      계획 알람 {kst(plan.alarm_at):%H:%M}"
                    f" · 출발 {kst(plan.depart_by):%H:%M}"
                    f" · 도착 {kst(plan.arrive_at):%H:%M} ({plan.status})"
                )

        if obs.exists():
            print("\n    관측 상세 (KST)")
            for o in obs:
                planned = kst(o.planned_at)
                delay = o.delay_minutes
                delay_text = (
                    "계획 없음"
                    if delay is None
                    else ("정시" if delay == 0 else f"{delay:+d}분")
                )
                planned_text = f"{planned:%H:%M:%S}" if planned else "없음"
                print(
                    f"      {o.get_kind_display()}  관측 {kst(o.observed_at):%H:%M:%S}"
                    f"  계획 {planned_text}  지연 {delay_text}"
                )
                print(
                    f"        기준점까지 {o.distance_m:.0f}m · 오차 {o.accuracy_m:.0f}m"
                    f" · 판별기 {o.get_detector_display()}"
                )
                print(f"        좌표 {o.lat:.6f}, {o.lng:.6f} · uuid {o.client_uuid[:8]}…")
        print()


def cleanup() -> None:
    users = targets()
    if not users.exists():
        print("지울 검증 계정이 없다.")
        return

    obs_count = TripObservation.objects.filter(user__in=users).count()
    event_count = Event.objects.filter(user__in=users).count()
    plan_count = AlarmPlan.objects.filter(user__in=users).count()
    emails = list(users.values_list("email", flat=True))

    # 계정을 지우면 CASCADE 로 일정·계획·관측이 함께 지워진다.
    deleted, _ = users.delete()

    print(f"삭제한 계정 {len(emails)}개: {', '.join(emails)}")
    print(f"  함께 삭제됨 — 일정 {event_count}건 · 알람 계획 {plan_count}건 · 관측 {obs_count}건")
    print(f"  DB 행 총 {deleted}개 삭제")

    if STATE.exists():
        STATE.unlink()
        print(f"  상태 파일 삭제: {STATE.name}")

    # 남은 것이 없는지 확인한다.
    left_users = targets().count()
    left_obs = TripObservation.objects.count()
    print(f"\n확인 — 남은 검증 계정 {left_users}개 · DB 전체 관측 {left_obs}건")
    if left_obs:
        print("  경고: 관측이 남아 있다. 어느 계정 것인지 확인해야 한다")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true", help="현황만 출력한다")
    ap.add_argument("--apply", action="store_true", help="실제로 삭제한다")
    args = ap.parse_args()

    if args.apply:
        cleanup()
    else:
        report()
        if not args.report:
            print("삭제하려면 --apply 를 붙인다.")
