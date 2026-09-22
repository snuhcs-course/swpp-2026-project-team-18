"""사용자에게 기본 루틴 블록 6종을 넣는다.

## 왜 마이그레이션이 아니라 커맨드인가

`EventTag` 는 마이그레이션으로 시드한다. 전역 표이고 사용자가 만들지 않기
때문이다. 루틴 블록은 다르다 — **사용자별**이라 계정마다 복제해야 하고,
사용자가 이름·범위·순서를 고친다. 마이그레이션에 넣으면 나중에 가입한
계정에는 들어가지 않는다.

회원가입 시그널에 넣는 방법도 있는데 그러지 않았다. 온보딩에서 사용자가
직접 고르게 하는 것이 제품 의도이고(front-spec S1), 시그널로 자동 생성하면
"내가 만든 게 아닌 블록" 이 생겨 편집 동기가 사라진다. 이 커맨드는 데모
계정과 개발용이다.

## 기본값의 근거

범위(min~max)가 분포의 사전값이 된다. `mean=(min+max)/2`,
`sd=(max-min)/4` 로 변환되므로(약 95% 구간) **범위를 너무 좁게 주면 안 된다.**
sd 가 0 에 가까워져 τ 가 의미를 잃는다.

`parallelizable` 은 세탁처럼 기계가 돌아가는 동안 다른 일을 할 수 있는
항목에만 켠다. 병렬 블록은 합이 아니라 max 로 들어간다.

    python manage.py seed_blocks --email demo@demo.com
    python manage.py seed_blocks --all-users
    python manage.py seed_blocks --email demo@demo.com --reset
"""

from __future__ import annotations

from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand, CommandError
from django.db import transaction

from apps.routines.models import RoutineBlock

# (이름, 최소분, 최대분, 병렬, 기본포함, 포기비용, 선행블록이름)
#
# 순서가 곧 `order` 다. 아침에 실제로 하는 순서로 둔다 — 화면이 이 순서로
# 그리고, 선행 블록 기본값도 이 순서를 따른다.
DEFAULT_BLOCKS = [
    ("기상·정리", 3, 7, False, True, RoutineBlock.DropCost.IMPOSSIBLE, None),
    ("샤워", 10, 18, False, True, RoutineBlock.DropCost.MEDIUM, None),
    ("머리 손질", 5, 12, False, True, RoutineBlock.DropCost.SMALL, "샤워"),
    ("옷 입기", 4, 9, False, True, RoutineBlock.DropCost.IMPOSSIBLE, None),
    ("아침 식사", 8, 20, False, True, RoutineBlock.DropCost.SMALL, None),
    ("가방·준비물", 2, 6, False, True, RoutineBlock.DropCost.LARGE, None),
]


class Command(BaseCommand):
    help = "사용자에게 기본 루틴 블록 6종을 넣는다(멱등)."

    def add_arguments(self, parser):
        parser.add_argument("--email", help="대상 계정 이메일")
        parser.add_argument(
            "--all-users",
            action="store_true",
            help="블록이 하나도 없는 모든 계정에 넣는다",
        )
        parser.add_argument(
            "--reset",
            action="store_true",
            help="기존 블록을 지우고 다시 만든다. **관측도 함께 사라진다**",
        )

    def handle(self, *args, **options):
        User = get_user_model()

        if options["email"]:
            try:
                users = [User.objects.get(email=options["email"].strip().lower())]
            except User.DoesNotExist as exc:
                raise CommandError(f"계정이 없다: {options['email']}") from exc
        elif options["all_users"]:
            users = list(User.objects.all())
        else:
            raise CommandError("--email 또는 --all-users 가 필요하다.")

        total_created = 0
        for user in users:
            created = self._seed_one(user, reset=options["reset"])
            total_created += created

        self.stdout.write(
            self.style.SUCCESS(
                f"대상 {len(users)}명 / 블록 {total_created}개 생성"
            )
        )

    @transaction.atomic
    def _seed_one(self, user, *, reset: bool) -> int:
        existing = RoutineBlock.objects.filter(user=user)

        if reset:
            # 관측이 CASCADE 로 함께 지워진다. 경고를 남긴다 — 학습 데이터를
            # 잃는 것은 되돌릴 수 없다.
            n_obs = sum(b.observations.count() for b in existing)
            if n_obs:
                self.stderr.write(
                    f"  [주의] {user.email}: 블록 관측 {n_obs}건이 함께 삭제된다."
                )
            existing.delete()
        elif existing.exists():
            self.stdout.write(f"  {user.email}: 이미 블록 {existing.count()}개 — 건너뜀")
            return 0

        # 1차: 선행 블록 없이 전부 만든다. 이름으로 참조하므로 먼저 존재해야 한다.
        by_name: dict[str, RoutineBlock] = {}
        for order, (name, lo, hi, parallel, default, cost, _pre) in enumerate(
            DEFAULT_BLOCKS, start=1
        ):
            block = RoutineBlock.objects.create(
                user=user,
                name=name,
                default_min_minutes=lo,
                default_max_minutes=hi,
                parallelizable=parallel,
                included_by_default=default,
                drop_cost=cost,
                order=order,
            )
            by_name[name] = block

        # 2차: 선행 블록을 연결한다.
        for name, _lo, _hi, _p, _d, _c, pre_name in DEFAULT_BLOCKS:
            if pre_name:
                block = by_name[name]
                block.precondition = by_name[pre_name]
                # clean() 으로 소유권·순환을 다시 확인한다. 시드가 잘못된
                # 데이터를 넣으면 알람 계산이 무한 루프에 빠진다.
                block.full_clean()
                block.save(update_fields=["precondition"])

        self.stdout.write(f"  {user.email}: 블록 {len(DEFAULT_BLOCKS)}개 생성")
        return len(DEFAULT_BLOCKS)
