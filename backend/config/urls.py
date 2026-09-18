"""
URL 라우팅.

back-spec.md 5절의 경로 규칙을 따른다. 앱이 추가되면 여기에 include 를 붙인다.
"""

from django.contrib import admin
from django.urls import include, path

from apps.accounts.views_profile import ProfileView
from apps.common.health import health

urlpatterns = [
    path("admin/", admin.site.urls),
    # 인증 없이 접근. 클라이언트 연결 진단용.
    path("api/health", health, name="health"),
    # back-spec.md 5.1 인증 — register / token / token/refresh / me
    path("api/auth/", include("apps.accounts.urls")),
    # back-spec.md 5.2 프로필
    path("api/profile", ProfileView.as_view(), name="profile"),
    # back-spec.md 5.3 일정·장소
    path("api/", include("apps.events.urls")),
    # 이동 관측 — 앱이 GPS 로 판별한 실제 출발·도착 시각
    path("api/", include("apps.observations.urls")),
]
