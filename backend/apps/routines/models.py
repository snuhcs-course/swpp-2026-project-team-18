"""아침 루틴 블록. back-spec.md 4.3.

## 왜 블록으로 쪼개는가

준비 시간을 "30분" 한 덩어리로 받으면 두 가지를 못 한다.

1. **왜 30분인지 설명할 수 없다.** 사용자가 알람을 믿으려면 근거가 보여야 한다.
2. **줄일 여지를 찾을 수 없다.** 늦었을 때 "아침 식사를 건너뛰면 12분 벌 수
   있다" 를 말하려면 항목별 소요와 포기 비용을 알아야 한다.

블록으로 쪼개면 관측도 블록 단위로 쌓여서 학습이 정밀해진다. "샤워가 원래
14분인데 오늘 20분 걸렸다" 를 구분할 수 있다.

## 정합성 규칙

1. 블록은 **사용자별**이다. `EventTag` 처럼 전역 시드가 아니다 — 아침 루틴은
   사람마다 다르다. 기본 6종은 `seed_blocks` 커맨드가 계정마다 복제한다.
2. `precondition` 은 자기참조 FK 다. **같은 사용자의 블록만** 가리킬 수 있다.
   남의 블록을 가리키면 그 사람의 루틴 이름과 소요 시간이 새어 나간다.
   순환 참조(A→B→A)도 막는다 — 임계경로 계산이 무한 루프에 빠진다.
3. `default_min_minutes <= default_max_minutes` 를 DB 제약으로 박는다.
   뒤집힌 범위가 들어오면 표준편차가 음수가 되어 분포 생성이 터진다.
"""

from __future__ import annotations

from django.conf import settings
from django.core.exceptions import ValidationError
from django.core.validators import MaxValueValidator, MinValueValidator
from django.db import models
from django.db.models import F, Q

# 블록 하나의 상한. 아침 루틴의 항목이 8시간을 넘을 수는 없다. 상한이 없으면
# 실수로 큰 값이 들어가 알람이 며칠 전으로 계산된다.
MAX_BLOCK_MINUTES = 480

# precondition 사슬을 따라갈 때의 최대 깊이. 순환을 clean() 에서 막지만,
# 이미 저장된 데이터가 순환일 수도 있으므로 계산부에도 상한을 둔다.
MAX_PRECONDITION_DEPTH = 32


class RoutineBlock(models.Model):
    """아침 루틴의 한 항목. 사용자별 정의다."""

    class DropCost(models.TextChoices):
        """포기했을 때의 손실. 늦었을 때 무엇을 버릴지 고르는 근거다."""

        NONE = "none", "없음 (건너뛰어도 무관)"
        SMALL = "small", "작음"
        MEDIUM = "medium", "보통"
        LARGE = "large", "큼"
        IMPOSSIBLE = "impossible", "불가 (반드시 해야 함)"

    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="routine_blocks",
        verbose_name="사용자",
    )
    name = models.CharField("이름", max_length=40)

    # 사용자가 답한 범위. 분포의 사전값이 된다 — 변환 규칙은
    # `apps.planning.estimators.block_distribution` 에 있다.
    default_min_minutes = models.PositiveSmallIntegerField(
        "최소 소요(분)", validators=[MinValueValidator(0), MaxValueValidator(MAX_BLOCK_MINUTES)]
    )
    default_max_minutes = models.PositiveSmallIntegerField(
        "최대 소요(분)", validators=[MinValueValidator(0), MaxValueValidator(MAX_BLOCK_MINUTES)]
    )

    # 이 블록보다 먼저 끝나야 하는 블록. null 이면 제약 없음.
    # 같은 사용자의 블록만 가리킬 수 있다(clean 에서 검증).
    precondition = models.ForeignKey(
        "self",
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="dependents",
        verbose_name="선행 블록",
    )

    drop_cost = models.CharField(
        "포기 시 손실",
        max_length=12,
        choices=DropCost.choices,
        default=DropCost.MEDIUM,
    )

    # 다른 일과 겹쳐 진행할 수 있는가. 세탁기를 돌리며 샤워하는 경우다.
    # 병렬 블록은 총 준비 시간에 합으로 더해지지 않고 max 로 들어간다.
    parallelizable = models.BooleanField("병렬 가능", default=False)

    # 새 일정에 기본으로 포함할지. 사용자가 일정마다 체크를 바꿀 수 있다.
    included_by_default = models.BooleanField("기본 포함", default=True)

    order = models.PositiveSmallIntegerField("정렬 순서", default=0)

    created_at = models.DateTimeField("생성 시각", auto_now_add=True)
    updated_at = models.DateTimeField("수정 시각", auto_now=True)

    class Meta:
        db_table = "routines_block"
        verbose_name = "루틴 블록"
        verbose_name_plural = "루틴 블록"
        ordering = ["order", "id"]
        indexes = [
            models.Index(fields=["user", "order"], name="routines_user_order_idx"),
        ]
        constraints = [
            # 같은 사용자가 같은 이름의 블록을 두 개 만들지 않게 한다.
            # 이름이 겹치면 관측을 어느 블록에 붙일지 모호해진다.
            models.UniqueConstraint(
                fields=["user", "name"], name="routines_block_user_name_unique"
            ),
            # 뒤집힌 범위는 표준편차를 음수로 만들어 분포 생성이 터진다.
            models.CheckConstraint(
                condition=Q(default_min_minutes__lte=F("default_max_minutes")),
                name="routines_block_min_lte_max",
            ),
            models.CheckConstraint(
                condition=Q(default_max_minutes__lte=MAX_BLOCK_MINUTES),
                name="routines_block_max_upper_bound",
            ),
            # 자기 자신을 선행 블록으로 둘 수 없다. 가장 짧은 순환이다.
            models.CheckConstraint(
                condition=~Q(precondition=F("id")),
                name="routines_block_no_self_precondition",
            ),
        ]

    def __str__(self):
        return f"{self.name} ({self.default_min_minutes}~{self.default_max_minutes}분)"

    def clean(self):
        """선행 블록의 소유권과 순환을 검증한다.

        `full_clean()` 을 부르는 경로에서만 돈다. 시리얼라이저가 반드시
        부르게 해 두었다 — DB 제약으로는 "남의 블록인지" 와 "사슬이 순환하는지"
        를 표현할 수 없다.
        """
        super().clean()
        if self.precondition_id is None:
            return

        if self.pk and self.precondition_id == self.pk:
            raise ValidationError({"precondition": "자기 자신을 선행 블록으로 둘 수 없다."})

        # 소유권. 남의 블록을 가리키면 그 사람의 루틴이 새어 나간다.
        if self.user_id and self.precondition.user_id != self.user_id:
            raise ValidationError({"precondition": "다른 사용자의 블록을 가리킬 수 없다."})

        # 순환. 사슬을 거슬러 올라가며 자기 자신을 만나는지 본다.
        seen: set[int] = set()
        node = self.precondition
        depth = 0
        while node is not None:
            depth += 1
            if depth > MAX_PRECONDITION_DEPTH:
                raise ValidationError({"precondition": "선행 블록 사슬이 너무 깊다."})
            if self.pk and node.pk == self.pk:
                raise ValidationError({"precondition": "선행 블록이 순환한다."})
            if node.pk in seen:
                # 나를 포함하지 않는 순환. 이미 저장된 데이터가 깨진 경우다.
                raise ValidationError({"precondition": "선행 블록 사슬이 순환한다."})
            seen.add(node.pk)
            node = node.precondition


class EventBlockSelection(models.Model):
    """일정 하나에서 이 블록을 할지 말지.

    행이 없으면 `RoutineBlock.included_by_default` 를 따른다. 사용자가
    체크를 바꾼 블록만 행이 생긴다 — 일정마다 전체 블록을 복제하면 블록을
    새로 만들 때 과거 일정에 소급 적용할지가 애매해진다.
    """

    event = models.ForeignKey(
        "events.Event",
        on_delete=models.CASCADE,
        related_name="block_selections",
        verbose_name="일정",
    )
    block = models.ForeignKey(
        RoutineBlock,
        on_delete=models.CASCADE,
        related_name="selections",
        verbose_name="블록",
    )
    checked = models.BooleanField("포함", default=True)
    updated_at = models.DateTimeField("수정 시각", auto_now=True)

    class Meta:
        db_table = "routines_event_block_selection"
        verbose_name = "일정별 블록 선택"
        verbose_name_plural = "일정별 블록 선택"
        constraints = [
            models.UniqueConstraint(
                fields=["event", "block"], name="routines_selection_event_block_unique"
            ),
        ]

    def __str__(self):
        mark = "포함" if self.checked else "제외"
        return f"{self.event_id} / {self.block_id} {mark}"

    def clean(self):
        """일정과 블록이 같은 사용자의 것인지 확인한다.

        이걸 빼면 사용자 A 가 자기 일정에 사용자 B 의 블록을 붙일 수 있다.
        준비 시간 계산에 남의 데이터가 섞이고, 응답으로 블록 이름이 돌아가
        정보가 새어 나간다.
        """
        super().clean()
        if self.event_id and self.block_id:
            if self.event.user_id != self.block.user_id:
                raise ValidationError("일정과 블록의 소유자가 다르다.")


class BlockObservation(models.Model):
    """블록 하나의 실제 소요 시간. 학습 재료다. back-spec.md 4.4.

    `TripObservation` 이 이동 구간을 담는 것과 같은 역할을 준비 구간에서 한다.
    블록별로 쌓이므로 "샤워는 평균 14분" 같은 개인 분포가 만들어진다.

    `slack_minutes` 를 함께 저장하는 이유는 back-spec 6.1 S3 의 `slack_coef`
    때문이다. 여유가 많은 날은 준비가 느려진다 — 이 상관을 분리해 두지 않으면
    "이 사람은 원래 느리다" 로 잘못 학습된다.
    """

    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="block_observations",
        verbose_name="사용자",
    )
    block = models.ForeignKey(
        RoutineBlock,
        on_delete=models.CASCADE,
        related_name="observations",
        verbose_name="블록",
    )
    # 어느 일정의 아침이었나. 일정이 지워져도 관측은 남긴다 — 블록 소요
    # 시간은 일정과 무관하게 유효한 학습 재료다.
    event = models.ForeignKey(
        "events.Event",
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="block_observations",
        verbose_name="일정",
    )

    observed_on = models.DateField("관측 날짜")
    duration_minutes = models.FloatField(
        "실제 소요(분)",
        validators=[MinValueValidator(0), MaxValueValidator(MAX_BLOCK_MINUTES)],
    )
    # 그날 쓸 수 있었던 여유(분). 음수면 이미 늦은 상태였다.
    slack_minutes = models.FloatField("가용 여유(분)", null=True, blank=True)
    was_parallel = models.BooleanField("병렬로 진행", default=False)

    # 멱등 키. 오프라인 큐가 재전송해도 행이 늘지 않는다.
    client_uuid = models.CharField("클라이언트 UUID", max_length=40)

    client_recorded_at = models.DateTimeField("앱 기록 시각")
    server_received_at = models.DateTimeField("서버 수신 시각", auto_now_add=True)

    class Meta:
        db_table = "routines_block_observation"
        verbose_name = "블록 관측"
        verbose_name_plural = "블록 관측"
        ordering = ["-observed_on", "-id"]
        indexes = [
            models.Index(fields=["user", "block"], name="routines_obs_user_block_idx"),
            models.Index(fields=["block", "observed_on"], name="routines_obs_block_date_idx"),
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["user", "client_uuid"],
                name="routines_obs_user_client_uuid_unique",
            ),
            models.CheckConstraint(
                condition=Q(duration_minutes__gte=0), name="routines_obs_duration_nonneg"
            ),
            models.CheckConstraint(
                condition=Q(duration_minutes__lte=MAX_BLOCK_MINUTES),
                name="routines_obs_duration_upper_bound",
            ),
        ]

    def __str__(self):
        return f"{self.block_id} {self.observed_on} {self.duration_minutes}분"
