"""가입된 사용자와 프로필을 확인한다. E2E 검증용."""

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings.dev")

import django  # noqa: E402

django.setup()

from apps.accounts.models import Profile, User  # noqa: E402

print(f"총 사용자: {User.objects.count()}")
for u in User.objects.order_by("id"):
    p = Profile.objects.filter(user=u).first()
    algo = u.password.split("$")[0]
    has_profile = "있음" if p else "없음"
    tau = getattr(p, "default_tau", None)
    tz = getattr(p, "timezone", None)
    print(
        f"  id={u.id} email={u.email} nickname={u.nickname} "
        f"algo={algo} profile={has_profile} tau={tau} tz={tz}"
    )

orphans = User.objects.filter(profile__isnull=True).count()
print(f"프로필 없는 사용자: {orphans}")
