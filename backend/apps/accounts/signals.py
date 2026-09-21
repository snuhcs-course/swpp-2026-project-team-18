"""사용자가 만들어지면 프로필도 반드시 함께 만든다.

**왜 시그널인가** — 회원가입 시리얼라이저에서만 `Profile` 을 만들면 그 경로를
타지 않는 생성에서 프로필이 빠진다. 실제로 `manage.py createsuperuser` 로 만든
관리자 계정에 프로필이 없는 상태가 생겼다. admin 화면에서 사용자를 추가하거나
`manage.py shell` 에서 만드는 경우도 같다.

"프로필 없는 사용자" 가 하나라도 존재하면 P1 알람 계산이 매번 그 경우를 방어해야
한다. 생성 경로가 여러 개인 불변식은 모델 계층에서 지키는 편이 낫다.

마이그레이션은 영향을 받지 않는다. `apps.get_model` 이 돌려주는 과거 모델은 실제
모델과 다른 클래스라 `sender` 가 일치하지 않아 이 리시버가 호출되지 않는다.
`0002_demo_account` 는 프로필을 직접 만든다.
"""

from django.conf import settings
from django.db.models.signals import post_save
from django.dispatch import receiver

from .models import Profile


@receiver(post_save, sender=settings.AUTH_USER_MODEL, dispatch_uid="accounts.create_profile")
def create_profile_for_new_user(sender, instance, created, raw=False, **kwargs):
    # fixture 적재 중에는 아무것도 만들지 않는다.
    #
    # `loaddata` 는 `raw=True` 로 저장한다. 이때 프로필을 만들면 fixture 안의
    # 프로필과 충돌한다 - 사용자당 프로필은 하나여야 하는데(unique), 시그널이
    # 먼저 만든 행이 그 자리를 차지해 적재가 IntegrityError 로 죽는다. 실제로
    # SQLite -> Postgres 이관에서 겪었다.
    #
    # fixture 는 프로필을 함께 담고 있으므로 만들 이유도 없다.
    if raw or not created:
        return
    # get_or_create 를 쓴다. 다른 경로가 이미 만들었더라도 터지지 않는다.
    Profile.objects.get_or_create(user=instance)
