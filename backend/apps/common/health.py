"""
`/api/health` — 인증 없이 접근 가능한 유일한 엔드포인트.

클라이언트 연결 문제를 진단하는 첫 관문이다(back-spec.md 9.3).
앱의 서버 확인 화면(FE-P0-05)이 이걸 호출한다.

DB 접근을 하지 않는다. DB 가 죽어도 "서버는 살아 있다"를 구분해서 알려야
연결 문제와 DB 문제를 따로 진단할 수 있다.

`realtime` 은 실시간 도착정보 제공자의 **키가 설정됐는지**만 알린다. 키 값도
길이도 담지 않는다.

이게 없으면 배포 서버에서 도착정보가 안 뜰 때 원인을 못 가린다. 키 미설정과
외부 호출 실패가 똑같이 "빈 결과"로 보이기 때문이다(`realtime.py` 는 외부
실패를 예외로 올리지 않고 빈 결과로 닫는다 — 경로 후보를 살리기 위해서다).
실제로 0.4.0 배포 직후 로컬에서는 도착정보가 붙고 배포 서버에서는 안 붙는
상황이 있었고, 둘을 구분할 방법이 없어 진단이 막혔다.
"""

from django.conf import settings
from rest_framework.decorators import api_view, authentication_classes, permission_classes
from rest_framework.response import Response


def _configured(name: str) -> bool:
    return bool(getattr(settings, name, "").strip())


@api_view(["GET"])
@authentication_classes([])
@permission_classes([])
def health(request):
    return Response(
        {
            "ok": True,
            "version": settings.APP_VERSION,
            "realtime": {
                "subway": _configured("SEOUL_SUBWAY_API_KEY"),
                # 두 표현 중 하나라도 있으면 호출을 시도한다.
                "bus": (
                    _configured("SEOUL_BUS_API_KEY_ENCODING")
                    or _configured("SEOUL_BUS_API_KEY_DECODING")
                ),
            },
        }
    )
