# Partly AI-generated (Claude)
"""앱 안 문의하기(iter4 BE-H4b). 계약은 `docs/iter4/server-inquiry.md`.

`POST /api/support/inquiries` — Bearer 필요. 201 `{"id": n}`. 400 `invalid`(칸별 `details`),
401 로그인 필요, 429 사용자당 시간당 5건 초과. 저장한 뒤 팀 메일로 알린다(`notify`). 메일이
실패해도 201 이다.
"""

from __future__ import annotations

from rest_framework import status
from rest_framework.generics import GenericAPIView
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle

from . import notify
from .serializers import InquirySerializer


class InquiryCreateView(GenericAPIView):
    serializer_class = InquirySerializer
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "support_inquiry"

    def post(self, request, *args, **kwargs):
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        inquiry = serializer.save(user=request.user)
        notify.notify_team(inquiry)
        return Response({"id": inquiry.pk}, status=status.HTTP_201_CREATED)
