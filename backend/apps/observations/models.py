"""이동 관측. 앱이 GPS 로 판별한 실제 출발·도착 시각을 담는다.

**이 표가 이 프로젝트의 유일한 학습 재료다.**

제안서가 파는 것은 "확률"이지만 지금 서버에는 확률을 만들 재료가 없다.
카카오 경로 API 는 점추정치 하나만 주고 변동성 정보를 주지 않는다
(checklist "BE-P0-06 결론"). 그래서 분포는 우리가 관측해서 만들어야 한다.
계획과 실제의 차이가 쌓이는 곳이 여기다.

**정합성 규칙**

1. 관측은 일정에 속한다. 일정이 지워지면 관측도 지운다 — 어떤 계획과
   비교할지 알 수 없는 관측은 학습에 쓸 수 없다.
2. `client_uuid` 는 앱이 만들고 사용자당 유일하다. 업로드가 실패해 다시
   보내도 행이 늘지 않는다. 오프라인 큐가 재전송하므로 필수다.
3. 좌표와 정확도를 함께 저장한다. **정확도 없는 좌표는 판정 근거가 될 수
   없다.** 오차 100m 인 fix 로 "반경 50m 진입"을 말할 수 없다. GPS 관측은
   위치가 필수다. 직접 누른 관측(`manual`)은 실내에서 위치를 못 잡을 수 있어
   위치가 없어도 받는다 — 위치는 판정 근거가 아니라 참고값이다.
4. 계획 대비 지연은 필드가 아니라 파생값이다([delay_minutes]). 계획이
   재계산되면 낡은 값이 남으면 안 된다.

**여기서 하지 않는 것** — 관측이 들어와도 알람을 다시 계산하지 않는다.
매 관측마다 `compute_and_store` 를 부르면 카카오 무료 쿼터(일 1,000건)를
관측 수만큼 먹는다. 재계산은 기존 `/api/events/{id}/recompute` 에 맡긴다.
"""

from django.conf import settings
from django.db import models
from django.db.models import Q

# 과거 앱의 체류 기록을 해석하는 참고값. 현재 도착 유효성의 조건이 아니다.
# BE-24 이후 앱은 100m 진입 즉시 도착하며 체류 시간은 0이다.
DWELL_CONFIRM_SECONDS = 120


class TripObservation(models.Model):
    """한 일정의 이동에서 관측한 사건 하나(출발 또는 도착)."""

    class Kind(models.TextChoices):
        DEPART = "depart", "출발"
        ARRIVE = "arrive", "도착"

    class Detector(models.TextChoices):
        # 집 반경을 벗어남 / 목적지 반경에 진입함. 앱의 기본 판별기다.
        GPS = "gps", "GPS 반경 판정"
        # 사용자가 직접 눌렀다. 학습에는 쓰지만 GPS 관측과 구분해야 한다.
        MANUAL = "manual", "직접 입력"

    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="trip_observations",
        verbose_name="사용자",
    )
    event = models.ForeignKey(
        "events.Event",
        on_delete=models.CASCADE,
        related_name="trip_observations",
        verbose_name="일정",
    )

    kind = models.CharField("종류", max_length=10, choices=Kind.choices)
    detector = models.CharField(
        "판별기", max_length=10, choices=Detector.choices, default=Detector.GPS
    )

    # 앱이 판정한 시각. 업로드 시각(created_at)과 다르다 — 오프라인이면
    # 몇 시간 뒤에 올라온다. 학습에 쓰는 것은 이쪽이다.
    observed_at = models.DateTimeField("관측 시각")

    # 위치 네 값은 GPS 관측에서 필수다(`obs_gps_has_location`). 직접 누른
    # 관측은 비어 있을 수 있다.
    lat = models.FloatField("위도", null=True, blank=True)
    lng = models.FloatField("경도", null=True, blank=True)
    # 판정에 쓴 fix 의 오차 반경(m). 클수록 신뢰도가 낮다.
    accuracy_m = models.FloatField("위치 정확도(m)", null=True, blank=True)
    # 판정 시점의 기준점까지 거리(m). 출발은 집, 도착은 목적지 기준이다.
    distance_m = models.FloatField("기준점까지 거리(m)", null=True, blank=True)

    # 목적지 반경 안에서 머문 시간(초). 도착에만 있다.
    #
    # 새 앱은 진입 즉시 확정해 0을 보낸다. 과거 앱이 보낸 체류 시간도 보존한다.
    # null 을 허용하는 이유는 두 가지다. 출발 관측에는 머문 시간이 없고,
    # 이 필드가 생기기 전에 올라온 관측도 그대로 남아야 한다.
    dwell_seconds = models.PositiveIntegerField(
        "체류 시간(초)", null=True, blank=True
    )

    # Partly AI-generated (Claude)
    # 목적지 반경에 처음 들어온 fix 의 시각. 도착에만 있다(db-schema.md A1).
    #
    # 새 앱은 `observed_at`과 같은 시각을 보낸다. 과거 앱에서는 체류 시작 시각이다.
    # 학습·리포트는 `observed_at`을 사용한다. null은 출발 또는 옛 관측이다.
    entered_at = models.DateTimeField("반경 진입 시각", null=True, blank=True)

    # 앱이 만드는 멱등 키. 재전송해도 행이 늘지 않는다.
    client_uuid = models.CharField("클라이언트 UUID", max_length=40)

    created_at = models.DateTimeField("업로드 시각", auto_now_add=True)

    class Meta:
        db_table = "observations_trip"
        verbose_name = "이동 관측"
        verbose_name_plural = "이동 관측"
        ordering = ["-observed_at", "-id"]
        indexes = [
            models.Index(fields=["user", "observed_at"], name="obs_user_observed_idx"),
            # "이 일정의 도착을 이미 기록했나" 를 자주 묻는다.
            models.Index(fields=["event", "kind"], name="obs_event_kind_idx"),
        ]
        constraints = [
            # 같은 사용자가 같은 관측을 두 번 올리지 않게 한다. 오프라인 큐가
            # 재전송하므로 이 제약이 멱등성의 근거다.
            models.UniqueConstraint(
                fields=["user", "client_uuid"], name="obs_user_client_uuid_unique"
            ),
            models.CheckConstraint(
                condition=Q(lat__gte=-90) & Q(lat__lte=90), name="obs_lat_range"
            ),
            models.CheckConstraint(
                condition=Q(lng__gte=-180) & Q(lng__lte=180), name="obs_lng_range"
            ),
            # 음수 오차·거리는 센서 오류이거나 조작이다. 학습을 오염시킨다.
            models.CheckConstraint(
                condition=Q(accuracy_m__gte=0), name="obs_accuracy_nonneg"
            ),
            models.CheckConstraint(
                condition=Q(distance_m__gte=0), name="obs_distance_nonneg"
            ),
            # Partly AI-generated (Claude)
            # GPS 판정은 위치가 근거다. 위치 없는 GPS 관측은 배선 오류다.
            models.CheckConstraint(
                condition=Q(detector="manual")
                | (
                    Q(lat__isnull=False) & Q(lng__isnull=False)
                    & Q(accuracy_m__isnull=False) & Q(distance_m__isnull=False)
                ),
                name="obs_gps_has_location",
            ),
            # 좌표는 둘 다 있거나 둘 다 없다.
            models.CheckConstraint(
                condition=Q(lat__isnull=True, lng__isnull=True)
                | Q(lat__isnull=False, lng__isnull=False),
                name="obs_lat_lng_together",
            ),
        ]

    def __str__(self):
        return f"{self.event_id} {self.get_kind_display()} {self.observed_at:%m-%d %H:%M}"

    @property
    def planned_at(self):
        """계획된 시각. 비교 대상이 없으면 None.

        필드로 복사해 두지 않는다. 알람이 재계산되면 계획 시각이 바뀌는데
        복사본은 낡은 값을 들고 있게 된다.
        """
        plan = getattr(self.event, "alarm_plan", None)
        if plan is None:
            return None
        return plan.depart_by if self.kind == self.Kind.DEPART else plan.arrive_at

    @property
    def dwell_confirmed(self) -> bool | None:
        """과거 2분 체류 여부를 읽는 호환 필드. 도착 유효성·리포트 필터가 아니다."""
        if self.kind != self.Kind.ARRIVE or self.dwell_seconds is None:
            return None
        return self.dwell_seconds >= DWELL_CONFIRM_SECONDS

    @property
    def delay_minutes(self) -> int | None:
        """계획보다 늦은 분. 이르면 음수다.

        **부호를 살린다.** 절댓값만 쌓으면 "항상 늦는 사람"과 "들쭉날쭉한
        사람"을 구분할 수 없고, 그 차이가 곧 분산이다.
        """
        planned = self.planned_at
        if planned is None:
            return None
        return round((self.observed_at - planned).total_seconds() / 60)


# Partly AI-generated (Claude)
class AlarmLog(models.Model):
    """알람 사건 하나. 울림·끄기·미루기·자동 멈춤·등록과 서버의 켬/끔(db-schema.md A3).

    **왜 필요한가.** 알람 → 끄기 → 출발 사이에서 "깨는 데 걸린 시간" 과 "실제 준비
    시간" 을 나누려면 울린 시각과 끈 시각이 있어야 한다. 지금까지는 기기에만 있었다.

    **이동 학습은 첫 울림을 계획 선택의 기준 시각으로 쓴다.** 기상 반응 분석과 리포트 반영은
    아직 하지 않는다. 조회 API도 두지 않는다(admin과 학습 배치용).

    **정합성 규칙**

    1. 일정이 지워져도 행은 남긴다(`SET_NULL`). 기상 반응은 일정과 무관하게 유효한
       재료다 — 블록 관측과 같은 규칙이다.
    2. `client_uuid` 는 사용자당 유일하다. 앱의 오프라인 큐가 재전송해도 행이 늘지
       않는다. 서버가 남기는 기록(`source=server`)은 서버가 키를 만든다.
    3. `occurred_at` 은 기기 시각이다. 업로드 시각(`created_at`)과 다르다.
    """

    class Kind(models.TextChoices):
        # 앱이 올린다.
        SCHEDULED = "scheduled", "등록·변경"
        FIRED = "fired", "울림"
        SNOOZED = "snoozed", "미루기"
        DISMISSED = "dismissed", "끄기"
        SILENCED = "silenced", "소리 자동 멈춤"
        # 서버가 남긴다. 사용자가 일정의 알람 스위치를 바꿨을 때다.
        ENABLED = "enabled", "켬"
        DISABLED = "disabled", "끔"
        # 스위치를 "자동(하루 첫 알람 규칙)" 으로 되돌렸다. 켬·끔과 다른 세 번째 상태다.
        AUTO = "auto", "자동(하루 첫 알람)"

    class Source(models.TextChoices):
        APP = "app", "앱"
        SERVER = "server", "서버"

    # 앱이 올릴 수 있는 종류. 켬/끔은 서버가 일정 수정에서 직접 남긴다.
    APP_KINDS = (
        Kind.SCHEDULED,
        Kind.FIRED,
        Kind.SNOOZED,
        Kind.DISMISSED,
        Kind.SILENCED,
    )

    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="alarm_logs",
        verbose_name="사용자",
    )
    event = models.ForeignKey(
        "events.Event",
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="alarm_logs",
        verbose_name="일정",
    )

    kind = models.CharField("종류", max_length=12, choices=Kind.choices)
    occurred_at = models.DateTimeField("일어난 시각")
    # 그때 기기에 등록돼 있던 알람 시각. 계획 이력(PlanRevision)과 맞춰 볼 수 있다.
    alarm_at = models.DateTimeField("등록된 알람 시각", null=True, blank=True)
    # 미루기에만 있다.
    snooze_minutes = models.PositiveSmallIntegerField("미룬 분", null=True, blank=True)
    source = models.CharField(
        "기록한 곳",
        max_length=8,
        choices=Source.choices,
        default=Source.APP,
        db_default="app",
    )

    # 멱등 키. 앱이 만들고 재전송해도 같은 값을 쓴다.
    client_uuid = models.CharField("클라이언트 UUID", max_length=40)

    created_at = models.DateTimeField("수신 시각", auto_now_add=True)

    class Meta:
        db_table = "observations_alarm_log"
        verbose_name = "알람 기록"
        verbose_name_plural = "알람 기록"
        ordering = ["-occurred_at", "-id"]
        indexes = [
            models.Index(fields=["user", "occurred_at"], name="alarm_log_user_time_idx"),
            # "이 일정의 첫 울림" 을 묻는다(계획 이력의 결정 시점).
            models.Index(fields=["event", "kind"], name="alarm_log_event_kind_idx"),
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["user", "client_uuid"], name="alarm_log_user_client_uuid_unique"
            ),
        ]

    def __str__(self):
        return f"{self.event_id} {self.get_kind_display()} {self.occurred_at:%m-%d %H:%M}"
