"""프로필 API. back-spec.md 5.2.

집 위치가 알람 계산의 출발지다. 이게 없으면 모든 알람 계획이 `NO_HOME` 이
된다. 그래서 집 위치를 바꾸면 **기존 일정 계획을 다시 계산**한다.
"""

from __future__ import annotations

from rest_framework.generics import RetrieveUpdateAPIView
from rest_framework.response import Response

from apps.planning import services as planning_services

from .models import Profile
from .serializers import ProfileSerializer


class ProfileView(RetrieveUpdateAPIView):
    """GET /api/profile — 조회
    PATCH /api/profile — 부분 수정
    """

    serializer_class = ProfileSerializer

    def get_object(self) -> Profile:
        # accounts 시그널이 사용자 생성 시 프로필을 만든다. 없을 수 없다.
        return self.request.user.profile

    def update(self, request, *args, **kwargs):
        profile = self.get_object()
        before = (profile.home_lat, profile.home_lng, profile.onboarding_prep_min)

        serializer = self.get_serializer(profile, data=request.data, partial=True)
        serializer.is_valid(raise_exception=True)
        profile = serializer.save()

        after = (profile.home_lat, profile.home_lng, profile.onboarding_prep_min)

        recomputed = 0
        if before != after:
            # 집 위치나 준비시간이 바뀌면 알람 시각이 전부 달라진다.
            # NO_HOME 이던 계획들이 이때 계산 가능해진다.
            recomputed = planning_services.recompute_for_user(request.user)

        data = dict(serializer.data)
        data["recomputed_plans"] = recomputed
        return Response(data)
