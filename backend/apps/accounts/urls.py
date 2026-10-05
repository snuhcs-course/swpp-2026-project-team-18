"""인증 라우팅. config/urls.py 에서 `api/auth/` 아래로 include 된다."""

from django.urls import path
from rest_framework_simplejwt.views import TokenRefreshView

from .views import LoginView, MeView, PasswordCheckView, RegisterView

app_name = "accounts"

urlpatterns = [
    path("register", RegisterView.as_view(), name="register"),
    path("password/check", PasswordCheckView.as_view(), name="password_check"),
    path("token", LoginView.as_view(), name="token_obtain_pair"),
    path("token/refresh", TokenRefreshView.as_view(), name="token_refresh"),
    path("me", MeView.as_view(), name="me"),
]
