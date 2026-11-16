"""프로필 API. back-spec.md 5.2.

집 위치가 알람 계산의 출발지다. 이게 없으면 모든 알람 계획이 `NO_HOME` 이
된다. 그래서 집 위치를 바꾸면 **기존 일정 계획을 다시 계산**한다.
"""

from __future__ import annotations

from django.db import transaction
from rest_framework.generics import RetrieveUpdateAPIView
from rest_framework.response import Response

from apps.planning import services as planning_services
from apps.planning.models import PlanRevision

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
        # 프로필 행을 잠그고 다시 읽은 다음 고친다(BE-H4 리뷰 H4-1). 같은 사용자의 다른 요청(다른
        # gunicorn 워커의 PATCH, 저장한 집 `make-default`)과 읽기-쓰기가 겹치지 않는다. 저장은
        # 보낸 칸만 한다(`ProfileSerializer.update`). 다시 계산은 잠금을 푼 뒤에 한다 — 카카오를
        # 부르는 동안 잠그지 않게. (Partly AI-generated (Claude))
        with transaction.atomic():
            profile = Profile.objects.select_for_update().get(user=request.user)
            before = (profile.home_lat, profile.home_lng, profile.onboarding_prep_min)

            serializer = self.get_serializer(profile, data=request.data, partial=True)
            serializer.is_valid(raise_exception=True)
            profile = serializer.save()
        # `request.user.profile` 캐시도 새 값으로 맞춘다(다시 계산이 이것을 읽는다).
        request.user.profile = profile

        after = (profile.home_lat, profile.home_lng, profile.onboarding_prep_min)
        # 안내를 본 시각(`intro_seen_at`·`coachmark_seen_at`, BE-H4)·τ·시간대만 바꾼 PATCH 는 다시 계산하지
        # 않는다(`recomputed_plans: 0`). 앱이 안내를 닫을 때마다 부른다. (Partly AI-generated (Claude))

        recomputed = 0
        if before != after:
            # 집 위치나 준비시간이 바뀌면 알람 시각이 전부 달라진다.
            # NO_HOME 이던 계획들이 이때 계산 가능해진다.
            recomputed = planning_services.recompute_for_user(
                request.user, trigger=PlanRevision.Trigger.PROFILE
            )  # Partly AI-generated (Claude) — 계획 이력에 "프로필 변경" 으로 남긴다

        data = dict(serializer.data)
        data["recomputed_plans"] = recomputed
        return Response(data)
