"""공용 서버에 데모 계정을 만든다.

팀원이 각자 계정을 만들어도 되지만, 화면을 확인하거나 앱을 처음 띄울 때
공통으로 쓸 계정이 하나 있으면 편하다. 집 위치까지 넣어 두므로 로그인
직후부터 알람이 계산되는 상태가 된다 - 집이 없으면 일정을 넣어도 계획이
`no_home` 으로만 나와서 화면이 비어 보인다.

멱등하다. 여러 번 실행해도 계정이 중복되지 않고 비밀번호만 다시 맞춘다.

    python manage.py seed_demo
    python manage.py seed_demo --email qa@demo.com --password qa12345678

**운영 데이터가 아니다.** 공용 서버에 두는 공개 계정이므로 비밀번호를
바꾸지 말고, 실제 개인 정보를 넣지 않는다.
"""

from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand
from django.db import transaction

# 서울대 신림역 근처. 목적지(관악캠퍼스)와 대중교통 경로가 실제로 나오는
# 좌표여야 데모가 의미 있다.
DEMO_HOME = {"lat": 37.484267, "lng": 126.929745, "label": "신림역"}
DEMO_PREP_MINUTES = 30


class Command(BaseCommand):
    help = "공용 서버용 데모 계정을 만든다(멱등)."

    def add_arguments(self, parser):
        parser.add_argument("--email", default="demo@demo.com")
        parser.add_argument("--password", default="demo1234")
        parser.add_argument("--nickname", default="데모")

    @transaction.atomic
    def handle(self, *args, **options):
        User = get_user_model()
        email = options["email"].strip().lower()
        password = options["password"]

        user, created = User.objects.get_or_create(
            email=email,
            defaults={"nickname": options["nickname"]},
        )

        # 이미 있으면 비밀번호만 맞춘다. 누가 바꿨거나 기억이 엇갈릴 때
        # 이 명령 한 번으로 알려진 상태로 되돌리려는 것이다.
        user.set_password(password)
        user.save()

        # 프로필은 accounts 시그널이 만든다. 만들어졌다고 가정하지 않고 확인한다.
        profile = getattr(user, "profile", None)
        if profile is None:
            self.stderr.write(
                "프로필이 없다. accounts 시그널을 확인할 것 "
                "(python manage.py backfill_profiles)."
            )
        else:
            profile.home_lat = DEMO_HOME["lat"]
            profile.home_lng = DEMO_HOME["lng"]
            profile.home_label = DEMO_HOME["label"]
            profile.onboarding_prep_min = DEMO_PREP_MINUTES
            profile.save()

        state = "생성" if created else "갱신"
        self.stdout.write(
            self.style.SUCCESS(
                f"데모 계정 {state}: {email} / {password}  "
                f"집={DEMO_HOME['label']} 준비={DEMO_PREP_MINUTES}분"
            )
        )
