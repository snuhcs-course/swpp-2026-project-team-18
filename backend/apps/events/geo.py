"""서비스 범위 판정. 좌표를 받는 모든 곳이 같은 규칙을 써야 한다.

## 왜 별도 모듈인가

이 규칙은 원래 `views.py` 안에 있었다. 그래서 경로 후보 조회는 국외 좌표를
400 으로 막았지만 **일정 생성은 막지 않았다** — 시리얼라이저가 뷰를 import
하면 순환이 되므로 그쪽에는 검사가 없었다.

결과적으로 파리 좌표를 `origin_lat/lng` 로 보내면 일정이 201 로 생성됐다.
그러면 알람 계산이 국외 좌표로 카카오를 부르고, 쿼터를 쓰고 실패한다.
막는 지점이 하나 빠져 있으면 방어가 아니다.

규칙을 여기 한 곳에 두고 뷰와 시리얼라이저가 모두 이걸 부른다.

## 범위의 근거

카카오 경로·주소 API 는 국내만 다룬다. 그래서 국외 좌표는 정상 사용에서
나올 수 없고, 나온다면 이 엔드포인트를 전 세계 경로 프록시로 쓰려는 시도다.
한반도 남부와 주변 해역을 넉넉히 덮는다 — 울릉도·독도(동경 131.9)와
마라도(북위 33.1)가 들어가야 한다.
"""

from __future__ import annotations

KOREA_LAT_RANGE = (32.5, 39.0)
KOREA_LNG_RANGE = (124.0, 132.5)


def in_service_area(lat: float, lng: float) -> bool:
    """카카오가 경로를 줄 수 있는 범위인지."""
    if lat is None or lng is None:
        return False
    return (
        KOREA_LAT_RANGE[0] <= lat <= KOREA_LAT_RANGE[1]
        and KOREA_LNG_RANGE[0] <= lng <= KOREA_LNG_RANGE[1]
    )
