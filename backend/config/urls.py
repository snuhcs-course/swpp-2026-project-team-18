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
    # back-spec.md 4.3 아침 루틴 블록 + 블록 관측
    # events/{id}/blocks 도 여기에 있다. 블록 개념의 소유자가 routines 이고,
    # events 가 routines 를 import 하면 의존 방향이 뒤집힌다.
    path("api/", include("apps.routines.urls")),
    # back-spec.md 5.8 주간 리포트·캘리브레이션.
    # 모델이 없다 — 요청 시점에 기존 관측에서 계산한다(apps/reports/services.py).
    path("api/", include("apps.reports.urls")),
]
