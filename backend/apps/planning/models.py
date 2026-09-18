"""알람 계획. back-spec.md 4.5 `AlarmPlan` 의 축소판이다.

**지금 단계에서 의도적으로 비워 둔 것**

`prep_p50/p90`, `travel_p90`, `distribution_snapshot` 은 관측이 쌓여야 나오는
값이다. 새 계정에는 관측이 없으므로 **분포를 만들 수 없다.**

그래서 `on_time_probability` 를 **null 허용**으로 뒀다. 값이 없으면 화면이
"학습 중" 으로 표시한다. 임의의 90% 를 넣으면 화면은 그럴싸해지지만 거짓이다.
카카오 경로 API 도 점추정치만 주므로(checklist "BE-P0-06 결론") 확률을 만들
재료가 서버에도 없다.

P3 에서 `TravelObservation` 이 쌓이면 이 표에 분위수 필드를 더한다.
"""

from django.conf import settings
from django.core.validators import MaxValueValidator, MinValueValidator
from django.db import models
from django.db.models import Q


class AlarmPlan(models.Model):
    """일정 하나에 대한 알람 계산 결과.

    일정이 만들어지거나 바뀔 때 계산해 저장한다. 목록 조회마다 카카오 경로
    API 를 부르면 무료 쿼터(일 1,000건)가 금방 마른다.
    """

    class Status(models.TextChoices):
        OK = "ok", "계산 완료"
        NO_HOME = "no_home", "집 위치 미설정"
        NO_PLACE = "no_place", "일정 장소 없음"
        ROUTE_FAILED = "route_failed", "경로 조회 실패"

    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="alarm_plans",
        verbose_name="사용자",
    )
    # 일정 1건당 계획 1건. 재계산하면 갱신한다.
    event = models.OneToOneField(
        "events.Event",
        on_delete=models.CASCADE,
        related_name="alarm_plan",
        verbose_name="일정",
    )

    status = models.CharField(
        "상태", max_length=16, choices=Status.choices, default=Status.OK
    )

    # 계산 결과. status 가 OK 가 아니면 전부 null 이다.
    alarm_at = models.DateTimeField("알람 시각", null=True, blank=True)
    depart_by = models.DateTimeField("출발 시각", null=True, blank=True)
    arrive_at = models.DateTimeField("도착 예정", null=True, blank=True)

    prep_minutes = models.PositiveIntegerField("준비 시간(분)", null=True, blank=True)
    travel_minutes = models.PositiveIntegerField("이동 시간(분)", null=True, blank=True)
    buffer_minutes = models.PositiveIntegerField("안전 버퍼(분)", null=True, blank=True)

    tau_used = models.FloatField(
        "적용 τ",
        null=True,
        blank=True,
        validators=[MinValueValidator(0.5), MaxValueValidator(0.999)],
    )

    # 관측이 쌓이기 전에는 null 이다. 임의값을 채우지 않는다.
    on_time_probability = models.PositiveSmallIntegerField(
        "정시 도착 확률(%)",
        null=True,
        blank=True,
        validators=[MaxValueValidator(100)],
    )

    # 이동 시간의 출처를 남긴다. 나중에 실측과 비교할 때 필요하다.
    travel_mode = models.CharField("이동 수단", max_length=20, blank=True)
    travel_source = models.CharField("이동시간 출처", max_length=30, blank=True)
    route_summary = models.CharField("경로 요약", max_length=200, blank=True)

    # 실제로 계산에 쓴 경로 key. `Event.route_key` 와 다르면 사용자가 고른
    # 경로가 사라져 대체된 것이다. 화면이 그 사실을 알려야 한다.
    route_key = models.CharField("사용한 경로", max_length=120, blank=True, default="")
    # 탑승 노선 등 사람이 읽는 경로 설명. "2호선 → 5513"
    route_detail = models.CharField("경로 상세", max_length=120, blank=True, default="")

    computed_at = models.DateTimeField("계산 시각", auto_now=True)

    class Meta:
        db_table = "planning_alarm_plan"
        verbose_name = "알람 계획"
        verbose_name_plural = "알람 계획"
        indexes = [
            models.Index(fields=["user", "alarm_at"], name="plan_user_alarm_idx"),
        ]
        constraints = [
            # status 가 OK 면 계산 결과가 있어야 한다. 반쯤 채워진 행을 막는다.
            models.CheckConstraint(
                condition=~Q(status="ok")
                | (
                    Q(alarm_at__isnull=False)
                    & Q(depart_by__isnull=False)
                    & Q(arrive_at__isnull=False)
                    & Q(prep_minutes__isnull=False)
                    & Q(travel_minutes__isnull=False)
                    & Q(buffer_minutes__isnull=False)
                ),
                name="planning_plan_ok_requires_values",
            ),
        ]

    def __str__(self):
        if self.status != self.Status.OK:
            return f"{self.event_id} — {self.get_status_display()}"
        return f"{self.event_id} — 알람 {self.alarm_at:%m-%d %H:%M}"

    @property
    def total_minutes(self) -> int | None:
        parts = (self.prep_minutes, self.travel_minutes, self.buffer_minutes)
        if any(p is None for p in parts):
            return None
        return sum(parts)
