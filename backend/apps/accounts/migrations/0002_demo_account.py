"""데모 계정 시드.

back-spec.md 5.1 논의: "데모 편의를 위해 dev 환경에 시드 계정
(`demo@demo.com` / `demo1234`)을 마이그레이션으로 생성한다."

`DEBUG=True` 일 때만 만든다. 운영에서는 아무 일도 하지 않는다.

주의 — `demo1234` 는 Django 비밀번호 검증기를 통과하지 못할 수준이다.
마이그레이션은 검증기를 타지 않으므로 생성 자체는 된다. 데모 전용이며
회원가입 API 로는 이런 비밀번호를 만들 수 없다.
"""

from django.conf import settings
from django.contrib.auth.hashers import make_password
from django.db import migrations
from django.utils import timezone

DEMO_EMAIL = "demo@demo.com"
DEMO_PASSWORD = "demo1234"
DEMO_NICKNAME = "데모"


def create_demo(apps, schema_editor):
    if not settings.DEBUG:
        return

    User = apps.get_model("accounts", "User")
    Profile = apps.get_model("accounts", "Profile")

    if User.objects.filter(email=DEMO_EMAIL).exists():
        return

    user = User.objects.create(
        email=DEMO_EMAIL,
        nickname=DEMO_NICKNAME,
        password=make_password(DEMO_PASSWORD),
        is_active=True,
        is_staff=False,
        is_superuser=False,
        date_joined=timezone.now(),
    )
    # 회원가입 API 와 같은 상태를 만든다. 프로필 없는 사용자를 남기지 않는다.
    Profile.objects.create(user=user)


def remove_demo(apps, schema_editor):
    User = apps.get_model("accounts", "User")
    User.objects.filter(email=DEMO_EMAIL).delete()


class Migration(migrations.Migration):

    dependencies = [
        ("accounts", "0001_initial"),
    ]

    operations = [
        migrations.RunPython(create_demo, remove_demo),
    ]
