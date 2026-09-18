"""계정 뷰. back-spec.md 5.1 인증."""

from __future__ import annotations

from rest_framework import status
from rest_framework.generics import CreateAPIView, RetrieveAPIView
from rest_framework.permissions import AllowAny
from rest_framework.response import Response
from rest_framework_simplejwt.tokens import RefreshToken
from rest_framework_simplejwt.views import TokenObtainPairView

from .serializers import LoginSerializer, RegisterSerializer, UserSerializer


class RegisterView(CreateAPIView):
    """POST /api/auth/register

    back-spec 5.1 은 201 `{user_id}` 만 규정하지만 `access`·`refresh`·`user` 를
    함께 내린다. 가입 직후 곧바로 로그인 화면으로 되돌리지 않고 바로 진입시키기
    위해서다. 규정된 `user_id` 는 그대로 포함하므로 명세를 어기지 않는다.
    """

    permission_classes = [AllowAny]
    authentication_classes = []
    serializer_class = RegisterSerializer

    def create(self, request, *args, **kwargs):
        serializer = self.get_serializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        user = serializer.save()

        refresh = RefreshToken.for_user(user)
        return Response(
            {
                "user_id": user.id,
                "user": UserSerializer(user).data,
                "access": str(refresh.access_token),
                "refresh": str(refresh),
            },
            status=status.HTTP_201_CREATED,
        )


class LoginView(TokenObtainPairView):
    """POST /api/auth/token — `{email, password}` → `{access, refresh, user}`"""

    permission_classes = [AllowAny]
    authentication_classes = []
    serializer_class = LoginSerializer


class MeView(RetrieveAPIView):
    """GET /api/auth/me

    명세에 없는 추가 엔드포인트다. 앱을 다시 열었을 때 저장된 access 토큰이
    아직 유효한지 확인하는 용도다. 없으면 클라이언트가 임의의 보호 API 를
    찔러보는 방식으로 대체해야 해서 의도가 드러나지 않는다.
    """

    serializer_class = UserSerializer

    def get_object(self):
        return self.request.user
