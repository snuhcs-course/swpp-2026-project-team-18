# Partly AI-generated (Claude)
"""쌓인 기록 초기화(iter4 BE-RESET). 계약은 `docs/iter4/server-learning-reset.md`.

`POST /api/auth/me/learning/reset` — Bearer 필요, 본문 없음. 200
`{"deleted": {...}, "recomputed_plans": n, "changed_plans": n, "reset_at": "..."}`.
무엇을 지우고 남기는지는 `apps.planning.learning_reset` 머리말에 있다.
"""

from __future__ import annotations

from rest_framework import serializers
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from apps.planning import learning_reset


class LearningResetView(APIView):
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "learning_reset"

    def post(self, request, *args, **kwargs):
        result = learning_reset.reset_learning(request.user)
        return Response(
            {
                "deleted": result.deleted,
                "recomputed_plans": result.recomputed_plans,
                "changed_plans": result.changed_plans,
                # 다른 응답의 시각과 같은 모양(DRF DateTimeField)이다.
                "reset_at": serializers.DateTimeField().to_representation(result.reset_at),
            }
        )
