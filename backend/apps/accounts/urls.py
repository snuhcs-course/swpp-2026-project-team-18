"""인증 라우팅. config/urls.py 에서 `api/auth/` 아래로 include 된다."""

from django.urls import path
from rest_framework_simplejwt.views import TokenRefreshView

from .views import (
    LoginView,
    LogoutAllView,
    LogoutView,
    MeView,
    PasswordCheckView,
    RegisterView,
)
from .views_password import (
    EmailVerifyConfirmView,
    EmailVerifyView,
    PasswordChangeView,
    PasswordResetConfirmView,
    PasswordResetView,
)
from .views_social import SocialLinkView, SocialLoginView

app_name = "accounts"

urlpatterns = [
    path("register", RegisterView.as_view(), name="register"),
    path("password/check", PasswordCheckView.as_view(), name="password_check"),
    # 비밀번호 찾기(공개)·변경(로그인), 이메일 인증. 계약은 docs/iter3/password-api.md.
    path("password/reset", PasswordResetView.as_view(), name="password_reset"),
    path(
        "password/reset/confirm",
        PasswordResetConfirmView.as_view(),
        name="password_reset_confirm",
    ),
    path("me/password", PasswordChangeView.as_view(), name="password_change"),
    path("me/email/verify", EmailVerifyView.as_view(), name="email_verify"),
    path(
        "me/email/verify/confirm", EmailVerifyConfirmView.as_view(), name="email_verify_confirm"
    ),
    path("token", LoginView.as_view(), name="token_obtain_pair"),
    path("token/refresh", TokenRefreshView.as_view(), name="token_refresh"),
    path("logout", LogoutView.as_view(), name="logout"),
    path("me", MeView.as_view(), name="me"),
    path("me/logout-all", LogoutAllView.as_view(), name="logout_all"),
    path("social/<str:provider>", SocialLoginView.as_view(), name="social_login"),
    # 연결은 로그인한 사용자의 것이라 `me` 아래에 둔다. 공개 경로(`social/`)와 접두가 겹치지 않는다.
    path("me/social/<str:provider>", SocialLinkView.as_view(), name="social_link"),
]
