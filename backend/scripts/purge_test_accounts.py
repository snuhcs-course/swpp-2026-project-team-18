"""검증 스크립트가 남긴 테스트 계정을 공용 DB 에서 지운다.

## 왜 필요한가

`check_deployed.py` 와 `check_same_db.py` 는 실제 서버에 계정을 만들어 검증한다.
사용자 삭제 API 가 없어 그 계정이 DB 에 남는다. 공용 DB 라 그대로 두면 팀
전체가 보는 목록이 테스트 쓰레기로 채워진다.

계정을 지우면 딸린 일정·알람계획·관측도 함께 사라진다(FK cascade). 지우기 전에
무엇이 사라지는지 먼저 센다.

## 쓰는 법

    # 무엇이 지워질지만 본다 (기본)
    $env:DATABASE_URL="<공용 DB 주소>"
    python scripts/purge_test_accounts.py

    # 실제로 지운다
    python scripts/purge_test_accounts.py --yes

    # 접두를 직접 지정
    python scripts/purge_test_accounts.py --prefix qa_ --prefix tmp_ --yes

**기본이 dry-run 이다.** 공용 DB 를 지우는 일이라 실수로 도는 것을 막는다.
실제 계정을 접두로 잡지 않도록 지울 목록을 반드시 눈으로 확인할 것.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND))

# 검증 스크립트가 쓰는 접두. 실제 사용자 이메일과 겹칠 일이 없는 값들이다.
DEFAULT_PREFIXES = ("depcheck_", "dbprobe_")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--prefix",
        action="append",
        default=None,
        help=f"지울 이메일 접두. 여러 번 줄 수 있다. 기본 {DEFAULT_PREFIXES}",
    )
    parser.add_argument(
        "--yes",
        action="store_true",
        help="실제로 지운다. 없으면 무엇이 지워질지만 보여준다",
    )
    args = parser.parse_args()
    prefixes = tuple(args.prefix) if args.prefix else DEFAULT_PREFIXES

    os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")
    import django

    django.setup()

    from django.conf import settings
    from django.contrib.auth import get_user_model
    from django.db.models import Q

    db = settings.DATABASES["default"]
    host = db.get("HOST") or "(파일)"
    print("=" * 70)
    print("테스트 계정 정리")
    print("=" * 70)
    print(f"DB     : {db.get('ENGINE', '').rsplit('.', 1)[-1]}  host={host}  name={db.get('NAME')}")
    print(f"접두   : {', '.join(prefixes)}")
    print(f"모드   : {'삭제' if args.yes else 'dry-run (아무것도 지우지 않는다)'}")

    User = get_user_model()

    q = Q()
    for p in prefixes:
        q |= Q(email__startswith=p)
    targets = list(User.objects.filter(q).order_by("id"))

    if not targets:
        print("\n지울 계정이 없다.")
        print("=" * 70)
        return 0

    # 무엇이 함께 사라지는지 먼저 센다. cascade 를 눈으로 보지 않고 지우면
    # 나중에 "일정이 왜 줄었지" 를 추적하기 어렵다.
    from apps.events.models import Event
    from apps.observations.models import TripObservation
    from apps.planning.models import AlarmPlan

    ids = [u.id for u in targets]
    n_events = Event.objects.filter(user_id__in=ids).count()
    n_plans = AlarmPlan.objects.filter(event__user_id__in=ids).count()
    n_obs = TripObservation.objects.filter(event__user_id__in=ids).count()

    print(f"\n대상 계정 {len(targets)}개")
    for u in targets:
        own = Event.objects.filter(user_id=u.id).count()
        print(f"  id={u.id:<5} {u.email:<42} 일정 {own}건")

    print("\n함께 사라지는 것")
    print(f"  일정       {n_events}건")
    print(f"  알람계획   {n_plans}건")
    print(f"  관측       {n_obs}건")

    # 남는 것도 같이 보여준다. 실제 계정을 잡았는지 여기서 알아챈다.
    keep = User.objects.exclude(q).order_by("id")
    print(f"\n남는 계정 {keep.count()}개")
    for u in keep:
        print(f"  id={u.id:<5} {u.email}")

    if not args.yes:
        print("\n" + "=" * 70)
        print("dry-run 이라 지우지 않았다. 목록이 맞으면 --yes 를 붙여 다시 실행할 것.")
        print("=" * 70)
        return 0

    deleted, per_model = User.objects.filter(q).delete()
    print("\n" + "=" * 70)
    print(f"삭제 완료: 총 {deleted}행")
    for model, n in sorted(per_model.items()):
        print(f"  {model:<40} {n}")
    print(f"\n남은 계정 {User.objects.count()}개 / 일정 {Event.objects.count()}건")
    print("=" * 70)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
