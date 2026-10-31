"""일정·장소·태그 모델. back-spec.md 4.2.

**정합성 규칙**

1. `Event` 는 항상 한 사용자에게 속한다. 사용자 없는 일정은 존재할 수 없다.
   조회는 언제나 `Event.objects.filter(user=request.user)` 로 시작한다.
2. `Place` 는 사용자 간 공유한다. "서울대 302동" 을 두 사람이 각자 저장하면
   행이 두 개가 되므로 `kakao_place_id` 로 합친다.
3. `Event.place` 는 null 을 허용한다. 집 목적지는 현재 프로필에서 해석하며,
   장소 미정 일정도 적어둘 수 있다. 계산 가능 여부는 `planning` 이 판단한다.
4. `EventTag` 는 마이그레이션으로 시드한다. 사용자가 만들지 않는다.
"""

from django.conf import settings
from django.core.validators import MaxValueValidator, MinValueValidator
from django.db import models
from django.db.models import Q


class Place(models.Model):
    """장소. 카카오 로컬 검색 결과를 그대로 저장한다.

    사용자별이 아니라 **전역 공유**다. 같은 장소를 여러 사용자가 쓰므로
    `kakao_place_id` 를 유일 키로 삼아 중복을 막는다. 수동 입력 장소는
    `kakao_place_id` 가 null 이고 중복이 생길 수 있다(허용).
    """

    name = models.CharField("장소명", max_length=120)
    lat = models.FloatField("위도")
    lng = models.FloatField("경도")
    address = models.CharField("주소", max_length=200, blank=True)

    # 카카오 로컬 검색의 place id. 수동 입력이면 null.
    kakao_place_id = models.CharField(
        "카카오 장소 ID", max_length=40, null=True, blank=True, unique=True
    )
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        db_table = "events_place"
        verbose_name = "장소"
        verbose_name_plural = "장소"
        constraints = [
            models.CheckConstraint(
                condition=Q(lat__gte=-90) & Q(lat__lte=90),
                name="events_place_lat_range",
            ),
            models.CheckConstraint(
                condition=Q(lng__gte=-180) & Q(lng__lte=180),
                name="events_place_lng_range",
            ),
        ]

    def __str__(self):
        return self.name


class EventTag(models.Model):
    """일정 종류. τ 기본값과 지각 페널티 모양을 결정한다.

    `penalty_shape` 가 τ 기본값의 근거다(back-spec 4.2).
    `step` 은 1분만 늦어도 실패인 경우(기차·시험), `linear` 는 늦은 만큼
    손해가 커지는 경우(수업)다.
    """

    class PenaltyShape(models.TextChoices):
        STEP = "step", "계단형 (1분만 늦어도 실패)"
        LINEAR = "linear", "선형 (늦은 만큼 손해)"

    key = models.CharField("키", max_length=20, unique=True)
    label = models.CharField("표시명", max_length=30)
    default_tau = models.FloatField(
        "기본 τ",
        validators=[MinValueValidator(0.5), MaxValueValidator(0.999)],
    )
    penalty_shape = models.CharField(
        "페널티 모양", max_length=10, choices=PenaltyShape.choices
    )
    order = models.PositiveSmallIntegerField("정렬 순서", default=0)

    class Meta:
        db_table = "events_tag"
        verbose_name = "일정 태그"
        verbose_name_plural = "일정 태그"
        ordering = ["order", "id"]
        constraints = [
            models.CheckConstraint(
                condition=Q(default_tau__gte=0.5) & Q(default_tau__lte=0.999),
                name="events_tag_default_tau_range",
            )
        ]

    def __str__(self):
        return self.label


class EventRecurrence(models.Model):
    """Weekly rule and immutable event inputs; concrete occurrences stay normal Events."""

    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE, related_name="event_recurrences")
    start_at = models.DateTimeField()
    timezone = models.CharField(max_length=64)
    weekdays = models.JSONField(default=list)
    template = models.JSONField(default=dict)
    place = models.ForeignKey(Place, on_delete=models.SET_NULL, null=True, blank=True, related_name="+")
    tag = models.ForeignKey(EventTag, on_delete=models.SET_NULL, null=True, blank=True, related_name="+")
    excluded_dates = models.JSONField(default=list)

    class Meta:
        db_table = "events_recurrence"


class Event(models.Model):
    """일정. 사용자별로 격리된다."""

    class Source(models.TextChoices):
        MANUAL = "manual", "직접 입력"
        CALENDAR = "calendar", "캘린더 동기화"
        NLP = "nlp", "자연어 파싱"

    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="events",
        verbose_name="사용자",
    )
    source = models.CharField(
        "출처", max_length=10, choices=Source.choices, default=Source.MANUAL
    )
    recurrence = models.ForeignKey(
        EventRecurrence, on_delete=models.CASCADE, null=True, blank=True,
        default=None, db_default=None, related_name="occurrences",
    )
    # Original local slot survives a one-occurrence time edit.
    recurrence_date = models.DateField(null=True, blank=True, default=None, db_default=None)
    # 캘린더 동기화용 외부 식별자. 직접 입력이면 null.
    external_id = models.CharField(
        "외부 ID", max_length=120, null=True, blank=True
    )

    title = models.CharField("제목", max_length=120)
    start_at = models.DateTimeField("시작 시각")

    # null = 하루 첫 알람 규칙, true/false = 사용자 선택.
    alarm_enabled = models.BooleanField(
        "알람 켬/끔", null=True, blank=True, default=None, db_default=None
    )

    # 장소를 모르면 알람을 계산할 수 없지만 일정은 적어둘 수 있어야 한다.
    place = models.ForeignKey(
        Place,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="events",
        verbose_name="장소",
    )
    # 집은 장소 사본을 저장하지 않고 계산할 때 현재 프로필에서 해석한다.
    # 구버전 INSERT 도 허용하도록 DB 기본값을 함께 남긴다.
    destination_is_home = models.BooleanField(
        "도착지가 집", default=False, db_default=False
    )
    tag = models.ForeignKey(
        EventTag,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="events",
        verbose_name="태그",
    )
    # 이 일정만 τ 를 다르게 쓸 때. null 이면 태그 → 프로필 순으로 내려간다.
    tau_override = models.FloatField(
        "τ 재지정",
        null=True,
        blank=True,
        validators=[MinValueValidator(0.5), MaxValueValidator(0.999)],
    )

    # 사용자가 고른 경로. `apps.routing.clients` 의 후보 key 형식이다.
    #   walk / bicycle / car / transit:<대표노선 체인>
    #
    # **소요시간을 저장하지 않고 key 만 저장한다.** 클라이언트가 보낸 분을
    # 그대로 믿으면 조작할 수 있고, 배차가 바뀌면 낡은 값이 된다. 알람을
    # 계산할 때마다 이 key 로 해당 수단만 다시 조회한다(호출 1회).
    #
    # 비어 있으면 서버가 `best_route()` 로 알아서 고른다.
    #
    # `db_default` 를 반드시 함께 준다. Django 의 `default` 는 파이썬 쪽
    # 기본값이라 `AddField` 가 기존 행을 채운 뒤 **DB 기본값을 남기지 않는다.**
    # 그러면 이 컬럼을 모르는 구버전 코드의 INSERT 가 NOT NULL 제약을 위반해
    # **쓰기만 500** 이 된다. 읽기와 `/api/health` 는 정상이라 겉으로는 멀쩡해
    # 보인다 — 실제로 그 조합을 한 번 겪었다(docs/team-setup.md 배포).
    route_key = models.CharField(
        "선택 경로", max_length=120, blank=True, default="", db_default=""
    )

    # 이 일정만의 출발지. null 이면 프로필의 집에서 출발한다.
    #
    # **왜 일정별로 저장하는가.** `route_key` 에는 수단만 들어 있고 출발지가
    # 없다(`transit:2호선>5513`). 경로 선택 화면에서 집이 아닌 곳을 출발지로
    # 골랐는데 이걸 저장하지 않으면, 알람 재계산이 집 좌표로 같은 key 를 다시
    # 풀어 **다른 경로의 소요시간으로 알람을 잡는다.** 그러면서
    # `route_choice_honored` 는 key 만 비교하므로 true 로 보고해 사용자를
    # 속인다. 저장해서 후보 조회와 재계산이 같은 출발지를 보게 만든다.
    #
    # `Place` FK 를 쓰지 않는다. 현재 위치를 역지오코딩한 결과는
    # `kakao_place_id` 가 없어서 `Place` 행이 계속 쌓이고, 출발지는 다른
    # 일정과 공유할 이유도 없다.
    origin_lat = models.FloatField(
        "출발지 위도",
        null=True,
        blank=True,
        validators=[MinValueValidator(-90), MaxValueValidator(90)],
    )
    origin_lng = models.FloatField(
        "출발지 경도",
        null=True,
        blank=True,
        validators=[MinValueValidator(-180), MaxValueValidator(180)],
    )
    # `db_default` 를 주는 이유는 위 `route_key` 와 같다.
    origin_label = models.CharField(
        "출발지 표시명", max_length=80, blank=True, default="", db_default=""
    )
    # 알람에 준비 시간을 넣을지. null 이면 출발지로 정한다(`prep_applies`).
    #
    # 출발지를 따로 고른 일정은 "이미 밖에 있다" 로 보고 준비 시간을 뺀다. 그런데
    # 친구 집·숙소에서 자고 바로 나서는 아침도 출발지가 집이 아니다. 그날은 준비를
    # 해야 하는데 알람이 준비 시간만큼 늦게 울린다. 그 경우 사용자가 `true` 로 고른다.
    # 반대로 집에서 출발해도 이미 준비를 마친 일정이면 `false` 로 뺄 수 있다.
    include_prep = models.BooleanField(
        "준비 시간 포함", null=True, blank=True, default=None, db_default=None
    )
    # 사용자가 "안 감" 으로 표시한 시각. null 이면 가는 일정이다.
    #
    # 안 간 아침은 도착 기록이 없어서 리포트에 "측정 못 함" 으로 섞이고, 그날 눌린
    # 블록 기록·출발 관측은 실제 등굣길이 아니다. 표시한 일정은 리포트와 학습에서
    # 빼고 알람도 울리지 않는다(`skipped`). 계획은 지우지 않는다 — 표시를 되돌리면
    # 그대로 다시 쓴다. API 는 `POST/DELETE /api/events/{id}/skip`.
    skipped_at = models.DateTimeField(
        "안 감 표시 시각", null=True, blank=True, default=None, db_default=None
    )
    # 준비 시간 학습에서 이 아침을 뺀 시각. null 이면 학습에 쓴다.
    #
    # 간 일정이지만 그날 준비 기록이 평소와 동떨어진 아침이다(알람을 끄고 다시 잠들었거나
    # 출발이 늦게 잡힘). "안 감" 과 달리 리포트·알람에는 그대로 두고 준비 학습(GPS 준비
    # 시간·블록 기록)에서만 뺀다. 앱 화면은 없다. 사용자가 요청하면
    # `manage.py exclude_prep_mornings` 로 붙이고 뗀다(`planning.prep_observations`).
    prep_learning_excluded_at = models.DateTimeField(
        "준비 학습 제외 시각", null=True, blank=True, default=None, db_default=None
    )

    created_at = models.DateTimeField("생성 시각", auto_now_add=True)
    updated_at = models.DateTimeField("수정 시각", auto_now=True)

    class Meta:
        db_table = "events_event"
        verbose_name = "일정"
        verbose_name_plural = "일정"
        ordering = ["start_at", "id"]
        indexes = [
            models.Index(fields=["user", "start_at"], name="events_user_start_idx"),
        ]
        constraints = [
            models.UniqueConstraint(fields=["recurrence", "recurrence_date"], name="events_recurrence_date_unique"),
            models.CheckConstraint(
                condition=(Q(recurrence__isnull=True) & Q(recurrence_date__isnull=True))
                | (Q(recurrence__isnull=False) & Q(recurrence_date__isnull=False)),
                name="events_recurrence_slot_pair",
            ),
            models.CheckConstraint(
                condition=Q(destination_is_home=False) | Q(place__isnull=True),
                name="events_event_home_without_place",
            ),
            # 같은 사용자가 같은 외부 일정을 두 번 담지 않게 한다.
            # external_id 가 null 인 직접 입력 일정에는 적용되지 않는다.
            models.UniqueConstraint(
                fields=["user", "external_id"],
                condition=Q(external_id__isnull=False),
                name="events_event_user_external_unique",
            ),
            models.CheckConstraint(
                condition=Q(tau_override__isnull=True)
                | (Q(tau_override__gte=0.5) & Q(tau_override__lte=0.999)),
                name="events_event_tau_override_range",
            ),
            # 위도·경도는 둘 다 있거나 둘 다 없어야 한다. 하나만 있으면
            # 출발지를 절반만 아는 상태이고, 계산기가 조용히 집으로
            # 돌아가 사용자가 고른 곳과 다르게 계산한다.
            models.CheckConstraint(
                condition=(
                    Q(origin_lat__isnull=True) & Q(origin_lng__isnull=True)
                )
                | (Q(origin_lat__isnull=False) & Q(origin_lng__isnull=False)),
                name="events_event_origin_pair",
            ),
        ]

    def __str__(self):
        return f"{self.start_at:%m-%d %H:%M} {self.title}"

    def resolve_origin(self, profile) -> tuple[float, float, str] | None:
        """이 일정의 출발지 `(lat, lng, label)`. 정할 수 없으면 None.

        일정에 지정된 출발지가 우선이고, 없으면 프로필의 집이다. 둘 다 없으면
        None 이고 호출자가 `no_home` 으로 처리한다.

        경로 후보 조회와 알람 재계산이 **반드시 같은 값을 봐야 한다.** 그래서
        규칙을 모델에 두고 양쪽이 이 메서드를 부른다.
        """
        if self.origin_lat is not None and self.origin_lng is not None:
            return self.origin_lat, self.origin_lng, self.origin_label or "출발지"
        if profile.home_lat is not None and profile.home_lng is not None:
            return profile.home_lat, profile.home_lng, profile.home_label or "집"
        return None

    # Partly AI-generated (Claude)
    @property
    def prep_applies(self) -> bool:
        """알람에 준비 시간을 넣는가.

        사용자가 고른 값(`include_prep`)이 우선이다. 고르지 않았으면 집에서 출발할 때만
        넣는다. 출발지를 따로 골랐다는 것은 보통 이미 밖에 있다는 뜻이다. 좌표를 집과
        비교하지 않는 이유는, 집 근처 카페를 골랐어도 그건 집이 아니고 준비 단계도
        아니기 때문이다.

        알람 계산(`services.estimate_components`)과 수정 분류(`edits.plan_inputs`)가
        같은 규칙을 보도록 여기 둔다.
        """
        if self.include_prep is not None:
            return self.include_prep
        return self.origin_lat is None

    # Partly AI-generated (Claude)
    @property
    def skipped(self) -> bool:
        """사용자가 "안 감" 으로 표시했는가(`skipped_at`)."""
        return self.skipped_at is not None

    def resolve_destination(self, profile) -> tuple[float, float, str] | None:
        """현재 목적지. 집은 프로필을 따르며 Place 행을 만들지 않는다."""
        if self.destination_is_home:
            if profile.home_lat is not None and profile.home_lng is not None:
                return profile.home_lat, profile.home_lng, profile.home_label or "집"
            return None
        if self.place is not None:
            return self.place.lat, self.place.lng, self.place.name
        return None

    @property
    def effective_tau(self) -> float:
        """이 일정에 적용할 τ.

        우선순위는 일정 재지정 → 태그 기본값 → 프로필 기본값이다.
        프로필이 없을 수는 없다(accounts 시그널이 보장한다).
        """
        if self.tau_override is not None:
            return self.tau_override
        if self.tag_id and self.tag and self.tag.default_tau:
            return self.tag.default_tau
        return self.user.profile.default_tau
