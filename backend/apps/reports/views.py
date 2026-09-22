"""리포트 API. back-spec.md 5.8.

**조회 전용이다.** 저장된 리포트를 읽는 것이 아니라 요청 시점에 계산한다.
근거는 `services.weekly` 주석에 있다 — 한 주 분량이 작고, 저장하면 "언제
만들어진 값인가" 와 "계획이 바뀌면 다시 만드나" 가 문제가 된다.

모든 계산이 `request.user` 로 좁혀진다. 리포트는 개인의 지각 이력이라 남의
것을 보면 안 된다. 범위 제한은 `services.collect_outcomes` 한 곳에 있다.
"""

from __future__ import annotations

from datetime import date

from rest_framework.response import Response
from rest_framework.views import APIView

from . import services


def _parse_week(raw: str | None) -> date | None:
    """`?week=YYYY-MM-DD`. 형식이 틀리면 None 을 주고 기본값(지난 주)으로 간다.

    400 을 내지 않는 이유: 리포트는 읽기 전용 화면이고, 잘못된 쿼리 하나로 화면
    전체를 막을 이유가 없다. 어떤 주를 보여주는지는 응답의 `week_start` 에
    실려 있으므로 사용자가 다른 주를 본 것을 알 수 있다.
    """
    if not raw:
        return None
    try:
        return date.fromisoformat(raw.strip())
    except ValueError:
        return None


class WeeklyReportView(APIView):
    """GET /api/reports/weekly?week=YYYY-MM-DD

    `week` 는 그 날짜가 속한 주를 뜻한다. 생략하면 **지난 주**다 — 이번 주는
    아직 진행 중이라 값이 매 시간 달라지고 사용자가 최종인지 알 수 없다.
    """

    def get(self, request):
        anchor = _parse_week(request.query_params.get("week"))
        return Response(services.weekly(request.user, anchor=anchor))


class CalibrationView(APIView):
    """GET /api/reports/calibration

    전체 기간(기본 90일)의 τ 버킷별 예측 대비 실제 정시율. 주간 리포트 안에도
    같은 구조가 들어가지만 그쪽은 한 주짜리라 표본이 적다. 모델이 과신하는지는
    긴 창으로 봐야 알 수 있다.
    """

    def get(self, request):
        return Response(services.calibration(request.user))
