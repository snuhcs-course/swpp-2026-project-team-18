"""
`/api/health` — 인증 없이 접근 가능한 유일한 엔드포인트.

클라이언트 연결 문제를 진단하는 첫 관문이다(back-spec.md 9.3).
앱의 서버 확인 화면(FE-P0-05)이 이걸 호출한다.

DB 접근을 하지 않는다. DB 가 죽어도 "서버는 살아 있다"를 구분해서 알려야
연결 문제와 DB 문제를 따로 진단할 수 있다.
"""

from django.conf import settings
from rest_framework.decorators import api_view, authentication_classes, permission_classes
from rest_framework.response import Response


@api_view(["GET"])
@authentication_classes([])
@permission_classes([])
def health(request):
    return Response({"ok": True, "version": settings.APP_VERSION})
