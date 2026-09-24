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
from django.db.models import Q, Value


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

    # 준비·이동 **양쪽** 모두 변동성이 있을 때만 채운다. 한쪽만 있으면
    # 없는 쪽을 0 분산으로 치게 되어 확신도를 과대 보고한다. 근거는
    # `apps/planning/estimators.py` 상단에 있다.
    on_time_probability = models.PositiveSmallIntegerField(
        "정시 도착 확률(%)",
        null=True,
        blank=True,
        validators=[MaxValueValidator(100)],
    )

    # 확률을 낼 수 있었는지, 못 냈으면 왜인지. 화면이 "학습 중" 문구를
    # 구체적으로 쓸 수 있게 한다 — "관측이 없다" 와 "이동 변동성만 모른다" 는
    # 사용자가 할 수 있는 행동이 다르다.
    #
    #   observed                  둘 다 관측 기반. 확률이 있다
    #   point_estimate            둘 다 점추정. 알람은 나오지만 확률은 없다
    #   travel_variance_unknown   준비만 변동성 있음
    #   prep_variance_unknown     이동만 변동성 있음
    #
    # **`db_default` 를 반드시 둔다.** Django 의 `default` 는 파이썬 계층에만
    # 있어서 DB 에는 기본값이 남지 않는다. 그러면 마이그레이션이 먼저 적용되고
    # 코드 배포가 늦어지는 순간(무중단 배포, 롤백, 팀원이 migrate 만 먼저 돌린
    # 경우) **구버전 코드의 INSERT 가 NOT NULL 위반으로 500 을 낸다.**
    # 실제로 그렇게 배포 서버가 깨졌다 — 읽기는 정상이고 /api/health 도 200
    # 이어서 겉으로는 멀쩡해 보였다.
    confidence_basis = models.CharField(
        "확신도 근거", max_length=32, blank=True, default="", db_default=""
    )

    # 준비 시간의 출처. 화면이 "실측" / "신고 범위" / "고정값" 을 구분한다.
    # 세 값의 신뢰도가 다른데 나란히 놓으면 전부 학습된 값처럼 읽힌다
    # (front-spec S_alarm 의 지적).
    prep_source = models.CharField(
        "준비시간 출처", max_length=24, blank=True, default="", db_default=""
    )

    # 블록별 내역. 근거 카드와 "무엇을 버릴까" 판단에 쓴다.
    # 리스트라 JSONField 로 둔다. 별도 표로 빼면 계획을 덮어쓸 때마다
    # 행을 지우고 다시 넣어야 하는데, 이력이 필요한 데이터가 아니다.
    prep_breakdown = models.JSONField(
        "준비 내역",
        default=list,
        blank=True,
        # 위 confidence_basis 와 같은 이유. JSON 배열 리터럴을 DB 기본값으로 둔다.
        db_default=Value("[]", output_field=models.JSONField()),
    )

    # 분포의 τ 분위수 원값(분). 반올림 전이라 재계산 비교에 쓴다.
    total_quantile_minutes = models.FloatField(
        "합성 분위수(분)", null=True, blank=True
    )

    # 이동 시간의 출처를 남긴다. 나중에 실측과 비교할 때 필요하다.
    travel_mode = models.CharField("이동 수단", max_length=20, blank=True)
    travel_source = models.CharField("이동시간 출처", max_length=30, blank=True)
    route_summary = models.CharField("경로 요약", max_length=200, blank=True)

    # 실제로 계산에 쓴 경로 key. `Event.route_key` 와 다르면 사용자가 고른
    # 경로가 사라져 대체된 것이다. 화면이 그 사실을 알려야 한다.
    # `db_default` 를 반드시 함께 준다. 이유는 `Event.route_key` 에 적어 두었다 —
    # 없으면 이 컬럼을 모르는 구버전 코드의 INSERT 가 쓰기만 500 을 낸다.
    route_key = models.CharField(
        "사용한 경로", max_length=120, blank=True, default="", db_default=""
    )
    # 탑승 노선 등 사람이 읽는 경로 설명. "2호선 → 5513"
    route_detail = models.CharField(
        "경로 상세", max_length=120, blank=True, default="", db_default=""
    )

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
        # 일정 제목을 쓴다. id 만 보여 주면 관리 화면에서 어느 일정의 계획인지
        # 확인하려고 매번 다른 표를 열어야 한다.
        label = self.event.title if self.event_id else "(일정 없음)"
        if self.status != self.Status.OK:
            return f"{label} — {self.get_status_display()}"
        return f"{label} — 알람 {self.alarm_at:%m-%d %H:%M}"

    @property
    def total_minutes(self) -> int | None:
        parts = (self.prep_minutes, self.travel_minutes, self.buffer_minutes)
        if any(p is None for p in parts):
            return None
        return sum(parts)
