# Partly AI-generated (Claude)
"""소셜 로그인 API. 규칙은 `social` 과 docs/iter3/social-login-api.md.

    POST   /api/auth/social/{provider}      ID 토큰 → JWT (없으면 가입)
    POST   /api/auth/me/social/{provider}   로그인한 사용자에게 연결
    DELETE /api/auth/me/social/{provider}   연결 끊기

`provider` 는 `kakao`·`google` 이다. 그 밖은 404.
"""

from __future__ import annotations

from django.http import Http404
from rest_framework import status
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView
from rest_framework_simplejwt.tokens import RefreshToken

from . import social
from .serializers import SocialLoginSerializer, SocialTokenSerializer, UserSerializer


def _provider(name: str) -> str:
    if name not in social.PROVIDERS:
        raise Http404
    return name


class SocialLoginView(APIView):
    """POST /api/auth/social/{provider} — `{id_token, terms_agreed?, nickname?}`

    응답은 가입(`register`)과 같은 모양에 `created` 를 더한다. 새 계정이면 201, 아니면 200.
    """

    permission_classes = [AllowAny]
    authentication_classes = []
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "social"

    def post(self, request, provider: str):
        name = _provider(provider)
        body = SocialLoginSerializer(data=request.data)
        body.is_valid(raise_exception=True)
        identity = social.verify(name, body.validated_data["id_token"])
        user, created = social.sign_in(
            identity,
            terms_agreed=body.validated_data["terms_agreed"],
            nickname=body.validated_data["nickname"],
        )
        refresh = RefreshToken.for_user(user)
        return Response(
            {
                "user_id": user.id,
                "user": UserSerializer(user).data,
                "access": str(refresh.access_token),
                "refresh": str(refresh),
                "created": created,
            },
            status=status.HTTP_201_CREATED if created else status.HTTP_200_OK,
        )


class SocialLinkView(APIView):
    """POST·DELETE /api/auth/me/social/{provider} — 로그인한 사용자만."""

    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "social"

    def post(self, request, provider: str):
        name = _provider(provider)
        body = SocialTokenSerializer(data=request.data)
        body.is_valid(raise_exception=True)
        social.link(request.user, social.verify(name, body.validated_data["id_token"]))
        return Response({"user": UserSerializer(request.user).data})

    def delete(self, request, provider: str):
        if not social.unlink(request.user, _provider(provider)):
            raise Http404
        return Response({"user": UserSerializer(request.user).data})
