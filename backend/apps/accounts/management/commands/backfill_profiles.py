"""프로필이 없는 사용자에게 기본 프로필을 만들어 준다.

시그널을 붙인 뒤에도 그 전에 만들어진 계정은 프로필이 없다. 이 커맨드로 메꾼다.

    manage.py backfill_profiles            # 대상만 보여준다
    manage.py backfill_profiles --apply    # 실제로 만든다
"""

from django.core.management.base import BaseCommand
from django.db import transaction

from apps.accounts.models import Profile, User


class Command(BaseCommand):
    help = "프로필이 없는 사용자에게 기본 프로필을 생성한다."

    def add_arguments(self, parser):
        parser.add_argument(
            "--apply",
            action="store_true",
            help="실제로 생성한다. 없으면 대상만 출력한다.",
        )

    def handle(self, *args, **options):
        targets = list(User.objects.filter(profile__isnull=True).order_by("id"))

        if not targets:
            self.stdout.write(self.style.SUCCESS("프로필 없는 사용자가 없다. 할 일 없음."))
            return

        self.stdout.write(f"프로필 없는 사용자 {len(targets)}명")
        for u in targets:
            self.stdout.write(f"  id={u.id} {u.email}")

        if not options["apply"]:
            self.stdout.write(
                self.style.WARNING("\n--apply 를 붙이면 실제로 생성한다. 지금은 아무것도 바꾸지 않았다.")
            )
            return

        with transaction.atomic():
            created = [Profile.objects.create(user=u) for u in targets]

        self.stdout.write(self.style.SUCCESS(f"\n프로필 {len(created)}개 생성했다."))
