# Partly AI-generated (Claude)
"""API 요청 기록(db-schema.md A4).

앱과 주고받은 요청을 한 줄씩 남긴다. **본문은 정해 둔 경로만, 정리한 값만** 담는다
(`apps/common/request_log.py`). 비밀번호·토큰·키는 값을 지우고, 이동 중 위치·경로 좌표는
좌표 대신 "지웠다" 표시와 점 개수만 남긴다(N19). 거리·정확도 같은 파생값은 그대로다.

학습에 쓰는 값(계획·알람·관측)은 각자의 표에 따로 있다. 이 표는 "그때 무엇을 보냈고
무엇을 받았나" 를 확인하는 용도이고 가장 빨리 커서 30일 뒤 지운다(`purge_logs`).
"""

from django.conf import settings
from django.db import models
from django.db.models import Value


class ApiRequestLog(models.Model):
    """요청 한 건의 메타와 정리한 본문."""

    created_at = models.DateTimeField("요청 시각", auto_now_add=True, db_index=True)
    # 인증된 요청만 채운다. 가입·로그인·익명 요청은 비어 있다.
    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        null=True,
        blank=True,
        related_name="api_request_logs",
        verbose_name="사용자",
    )
    method = models.CharField("메서드", max_length=8)
    # URL 패턴(예 `api/events/<int:pk>`). 패턴을 못 찾은 요청(404)은 경로 그대로다.
    route = models.CharField("경로", max_length=200)
    # 경로 안의 id(`pk`, `event_id`). 없으면 null.
    object_id = models.PositiveBigIntegerField("대상 id", null=True, blank=True)
    status_code = models.PositiveSmallIntegerField("상태 코드")
    duration_ms = models.PositiveIntegerField("걸린 시간(ms)")
    # 앱이 `X-App-Version` 헤더로 보낸다. 구버전 앱·다른 클라이언트는 비어 있다.
    app_version = models.CharField(
        "앱 버전", max_length=32, blank=True, default="", db_default=""
    )
    # 정리한 값. 딕셔너리를 넘긴다 — 문자열 "{}" 는 JSON 문자열이 된다
    # (planning.AlarmPlan.prep_breakdown 주석).
    query = models.JSONField(
        "쿼리", default=dict, blank=True, db_default=Value({}, output_field=models.JSONField())
    )
    request_body = models.JSONField(
        "요청 본문",
        default=dict,
        blank=True,
        db_default=Value({}, output_field=models.JSONField()),
    )
    response_body = models.JSONField(
        "응답 본문",
        default=dict,
        blank=True,
        db_default=Value({}, output_field=models.JSONField()),
    )
    # 공통 오류 형식(`{"error": {"code": ...}}`)의 code. 성공이면 빈 값.
    error_code = models.CharField(
        "오류 코드", max_length=64, blank=True, default="", db_default=""
    )

    class Meta:
        db_table = "common_api_request_log"
        verbose_name = "API 요청 기록"
        verbose_name_plural = "API 요청 기록"
        ordering = ["-created_at", "-id"]

    def __str__(self):
        return f"{self.method} {self.route} {self.status_code}"
