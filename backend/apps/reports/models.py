# Partly AI-generated (Claude)
"""리포트 앱의 저장 모델. 수치는 저장하지 않고 LLM 이 쓴 요약 문장만 둔다.

## 왜 캐시가 아니라 DB 인가

Render 무료 플랜은 잠들거나 배포할 때 `/tmp` 파일 캐시가 비워진다. 캐시에만 두면
깨어날 때마다 LLM 을 다시 부르고, **같은 주의 요약이 열 때마다 바뀐다.** DB 는
문장을 고정하고, 무엇을 보냈는지(`facts`)를 남겨 나중에 확인할 수 있게 한다.

수치가 바뀌면(늦게 올라온 관측, 일정 삭제, 모델·프롬프트 변경) `facts_hash` 가
달라지고 저장된 문장은 쓰지 않는다 — 낡은 숫자를 보이지 않는다.
"""

from django.conf import settings
from django.db import models


class WeeklySummary(models.Model):
    """LLM 이 쓴 주간 요약. 수치는 요청 때 계산하고 여기에는 문장만 둔다."""

    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="weekly_summaries",
        verbose_name="사용자",
    )
    week_start = models.DateField("주 시작(월)")
    # sha256(프롬프트 버전 | 모델 | 집계값 JSON). 같으면 다시 부르지 않는다.
    facts_hash = models.CharField("집계 해시", max_length=64)
    # LLM 에 보낸 집계값 그대로. 좌표·장소·제목·날짜·시각은 들어가지 않는다
    # (summary.build_facts 가 고정 키로 만든다).
    facts = models.JSONField("보낸 집계값", default=dict)
    text = models.TextField("요약")
    # `db_default` 를 함께 둔다. 이 컬럼을 모르는 구버전 코드의 INSERT 가 NOT NULL
    # 위반으로 500 을 내지 않게 한다(planning.AlarmPlan.confidence_basis 주석).
    model = models.CharField("모델", max_length=100, blank=True, default="", db_default="")
    created_at = models.DateTimeField("생성 시각", auto_now_add=True)
    updated_at = models.DateTimeField("수정 시각", auto_now=True)

    class Meta:
        db_table = "reports_weekly_summary"
        verbose_name = "주간 요약"
        verbose_name_plural = "주간 요약"
        constraints = [
            models.UniqueConstraint(
                fields=["user", "week_start"], name="report_summary_user_week_unique"
            ),
        ]

    def __str__(self):
        return f"{self.week_start} 주간 요약"


# Partly AI-generated (Claude)
class LlmCall(models.Model):
    """주간 요약 LLM 호출 한 번(db-schema.md A5).

    ## 왜 캐시가 아니라 DB 인가

    호출 상한을 `/tmp` 파일 캐시에서 세면 Render 무료 플랜이 잠들거나 재배포할 때
    0 부터 다시 센다(phaseB-review.md 1·2). 이 표는 그날 행 수가 곧 호출 수라 프로세스와
    무관하게 상한이 유지되고, 전체 하루 상한도 같은 표에서 센다.

    부르기 직전에 `pending` 으로 만들고 결과로 바꾼다. 상한에 걸린 시도는 행을 만들지
    않는다(호출하지 않았으므로). 보낸 집계값은 저장하지 않는다 — 그건 `WeeklySummary`
    가 성공한 문장과 함께 둔다. 여기는 `facts_hash` 만 둔다.
    """

    class Outcome(models.TextChoices):
        PENDING = "pending", "호출 중"
        OK = "ok", "성공"
        INVALID = "invalid", "검사 탈락"
        TIMEOUT = "timeout", "시간 초과"
        NETWORK = "network", "연결 실패"
        HTTP_429 = "http_429", "요청 한도(429)"
        HTTP_5XX = "http_5xx", "서버 오류(5xx)"
        HTTP_4XX = "http_4xx", "설정 오류(4xx)"
        BAD_RESPONSE = "bad_response", "응답 형식 다름"
        BAD_BASE_URL = "bad_base_url", "주소 설정 오류"
        NOT_CONFIGURED = "not_configured", "키 없음"

    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="llm_calls",
        verbose_name="사용자",
    )
    # 서버 시간대(Asia/Seoul)의 날짜. 하루 상한이 이 값으로 센다.
    day = models.DateField("날짜")
    week_start = models.DateField("요약한 주(월)")
    outcome = models.CharField(
        "결과",
        max_length=16,
        choices=Outcome.choices,
        default=Outcome.PENDING,
        db_default="pending",
    )
    model = models.CharField("모델", max_length=100, blank=True, default="", db_default="")
    facts_hash = models.CharField(
        "집계 해시", max_length=64, blank=True, default="", db_default=""
    )
    duration_ms = models.PositiveIntegerField("걸린 시간(ms)", null=True, blank=True)
    created_at = models.DateTimeField("호출 시각", auto_now_add=True)

    class Meta:
        db_table = "reports_llm_call"
        verbose_name = "LLM 호출"
        verbose_name_plural = "LLM 호출"
        ordering = ["-created_at", "-id"]
        indexes = [
            # 전체 하루 상한과 사용자 하루 상한을 센다.
            models.Index(fields=["day"], name="llm_call_day_idx"),
            models.Index(fields=["user", "day"], name="llm_call_user_day_idx"),
        ]

    def __str__(self):
        return f"{self.day} {self.get_outcome_display()}"
