"""루틴 블록 직렬화.

## 보안 규칙

블록은 사용자별이고 `precondition` 은 자기참조 FK 다. 그래서 두 곳에서
소유권을 확인해야 한다.

1. **조회 범위** — 뷰의 `get_queryset()` 이 `user=request.user` 로 좁힌다.
2. **참조 대상** — `precondition` 으로 **남의 블록 id** 를 보낼 수 있다. DRF 의
   `PrimaryKeyRelatedField` 는 기본 queryset 전체에서 찾으므로 그대로 두면
   통과한다. 그러면 응답에 남의 블록 이름이 실려 나가고, 준비 시간 계산에
   남의 데이터가 섞인다. `get_fields()` 에서 queryset 을 요청자 것으로 좁힌다.

`user` 는 **쓰기 필드가 아니다.** 요청 본문으로 받으면 다른 사용자 앞으로
블록을 만들 수 있다. 뷰의 `perform_create` 가 `request.user` 를 넣는다.
"""

from __future__ import annotations

from django.core.exceptions import ValidationError as DjangoValidationError
from rest_framework import serializers

# `apps.events` 는 `apps.routines` 를 import 하지 않으므로 순환이 생기지 않는다.
# 반대 방향(events 가 routines 를 참조)은 만들지 않는다 — 그래서 일정별 블록
# 선택 경로도 routines/urls.py 에 두었다.
from apps.events.models import Event

from .models import MAX_BLOCK_MINUTES, BlockObservation, EventBlockSelection, RoutineBlock


class RoutineBlockSerializer(serializers.ModelSerializer):
    """블록 읽기·쓰기 공용.

    학습 결과를 함께 내린다(`observation_count`, `observed_mean_minutes`).
    front-spec S4 가 "관측이 5회 이상 쌓인 블록은 실측 평균을 보여준다" 고
    정했다 — 사용자가 자기 설정을 스스로 교정하게 만드는 장치다.
    """

    # 남의 블록을 가리키지 못하게 get_fields 에서 queryset 을 좁힌다.
    precondition = serializers.PrimaryKeyRelatedField(
        queryset=RoutineBlock.objects.none(), allow_null=True, required=False
    )
    observation_count = serializers.SerializerMethodField()
    observed_mean_minutes = serializers.SerializerMethodField()

    class Meta:
        model = RoutineBlock
        fields = (
            "id",
            "name",
            "default_min_minutes",
            "default_max_minutes",
            "precondition",
            "drop_cost",
            "parallelizable",
            "included_by_default",
            "order",
            "observation_count",
            "observed_mean_minutes",
        )
        read_only_fields = ("id", "observation_count", "observed_mean_minutes")

    def get_fields(self):
        fields = super().get_fields()
        request = self.context.get("request")
        if request is not None and request.user.is_authenticated:
            qs = RoutineBlock.objects.filter(user=request.user)
            # 수정 중인 블록 자신은 후보에서 뺀다. 자기참조는 가장 짧은 순환이다.
            if self.instance is not None and getattr(self.instance, "pk", None):
                qs = qs.exclude(pk=self.instance.pk)
            fields["precondition"].queryset = qs
        return fields

    def get_observation_count(self, obj) -> int:
        # 뷰가 annotate 로 미리 채운다. 없으면 0 — 여기서 쿼리를 날리면 N+1 이다.
        return getattr(obj, "obs_count", 0) or 0

    def get_observed_mean_minutes(self, obj) -> float | None:
        value = getattr(obj, "obs_mean", None)
        return round(value, 1) if value is not None else None

    def validate_name(self, value):
        """같은 사용자의 블록 이름 중복을 막는다.

        모델에 `UniqueConstraint(["user", "name"])` 가 있지만 **DRF 는
        `Meta.constraints` 에서 검증기를 자동 생성하지 않는다.** `unique_together`
        만 인식한다. 그래서 여기서 막지 않으면 IntegrityError 가 올라가
        **사용자 입력에 500 이 난다.** 이름이 겹치면 관측을 어느 블록에 붙일지도
        모호해지므로 400 으로 돌려보내는 것이 맞다.

        `user` 는 시리얼라이저 필드가 아니라 뷰가 넣으므로 컨텍스트에서 읽는다.
        """
        request = self.context.get("request")
        if request is None or not request.user.is_authenticated:
            return value

        name = (value or "").strip()
        qs = RoutineBlock.objects.filter(user=request.user, name=name)
        if self.instance is not None:
            qs = qs.exclude(pk=self.instance.pk)
        if qs.exists():
            raise serializers.ValidationError("같은 이름의 블록이 이미 있다.")
        return name

    def validate(self, attrs):
        """범위와 순환을 확인한다.

        DB 제약과 모델 `clean()` 이 이미 막지만, 여기서 걸러야 400 과 친절한
        메시지가 나간다. 모델까지 내려가면 IntegrityError 로 500 이 된다.
        """
        lo = attrs.get(
            "default_min_minutes",
            getattr(self.instance, "default_min_minutes", None),
        )
        hi = attrs.get(
            "default_max_minutes",
            getattr(self.instance, "default_max_minutes", None),
        )
        if lo is not None and hi is not None and lo > hi:
            raise serializers.ValidationError(
                {"default_max_minutes": "최대 소요가 최소보다 작을 수 없다."}
            )
        for key in ("default_min_minutes", "default_max_minutes"):
            value = attrs.get(key)
            if value is not None and value > MAX_BLOCK_MINUTES:
                raise serializers.ValidationError(
                    {key: f"{MAX_BLOCK_MINUTES}분을 넘을 수 없다."}
                )

        # 순환 검증은 모델 clean() 에 있다. 여기서 부르려면 user 가 필요한데
        # 생성 시점에는 아직 없으므로, 기존 인스턴스를 수정할 때만 확인한다.
        if self.instance is not None and "precondition" in attrs:
            candidate = RoutineBlock(
                pk=self.instance.pk,
                user_id=self.instance.user_id,
                name=attrs.get("name", self.instance.name),
                default_min_minutes=lo,
                default_max_minutes=hi,
                precondition=attrs["precondition"],
            )
            try:
                candidate.clean()
            except DjangoValidationError as exc:
                raise serializers.ValidationError(
                    exc.message_dict if hasattr(exc, "message_dict") else str(exc)
                ) from exc
        return attrs


class EventBlockSelectionSerializer(serializers.ModelSerializer):
    """일정 하나에서 블록을 포함할지 여부.

    `block` 은 요청자 소유로 좁힌다. `event` 는 URL 에서 오므로 본문에 없다.
    """

    block = serializers.PrimaryKeyRelatedField(queryset=RoutineBlock.objects.none())

    class Meta:
        model = EventBlockSelection
        fields = ("block", "checked")

    def get_fields(self):
        fields = super().get_fields()
        request = self.context.get("request")
        if request is not None and request.user.is_authenticated:
            fields["block"].queryset = RoutineBlock.objects.filter(user=request.user)
        return fields


class BlockSelectionBulkSerializer(serializers.Serializer):
    """블록 체크를 한 번에 바꾼다.

    화면에서 체크박스를 여러 개 토글한 뒤 한 번 저장하는 흐름이다. 블록마다
    요청을 보내면 재계산이 N 번 돌고 카카오 쿼터를 그만큼 먹는다.
    """

    selections = EventBlockSelectionSerializer(many=True)

    def validate_selections(self, value):
        if not value:
            raise serializers.ValidationError("선택이 비어 있다.")
        seen = set()
        for row in value:
            block = row["block"]
            if block.pk in seen:
                raise serializers.ValidationError(
                    f"블록 {block.pk} 가 중복됐다."
                )
            seen.add(block.pk)
        return value


class BlockObservationSerializer(serializers.ModelSerializer):
    """블록 관측 업로드.

    `client_uuid` 로 멱등성을 지킨다. 오프라인 큐가 재전송하므로 같은 건이
    여러 번 온다 — 그때 행이 늘면 학습 데이터가 편향된다.
    """

    # 실제 queryset 은 get_fields 에서 요청자 것으로 좁힌다. 여기서는 빈
    # queryset 을 둔다 — DRF 는 클래스 정의 시점에 queryset 이 None 이 아닌지
    # 검사하므로 None 을 넣을 수 없다.
    block = serializers.PrimaryKeyRelatedField(queryset=RoutineBlock.objects.none())
    event = serializers.PrimaryKeyRelatedField(
        queryset=Event.objects.none(), allow_null=True, required=False
    )

    class Meta:
        model = BlockObservation
        fields = (
            "id",
            "block",
            "event",
            "observed_on",
            "duration_minutes",
            "slack_minutes",
            "was_parallel",
            "client_uuid",
            "client_recorded_at",
        )
        read_only_fields = ("id",)

    def get_fields(self):
        fields = super().get_fields()
        request = self.context.get("request")
        if request is not None and request.user.is_authenticated:
            fields["block"].queryset = RoutineBlock.objects.filter(user=request.user)
            fields["event"].queryset = Event.objects.filter(user=request.user)
        return fields

    def validate_duration_minutes(self, value):
        if value < 0:
            raise serializers.ValidationError("소요 시간은 음수일 수 없다.")
        if value > MAX_BLOCK_MINUTES:
            raise serializers.ValidationError(f"{MAX_BLOCK_MINUTES}분을 넘을 수 없다.")
        return value


class BlockObservationBatchSerializer(serializers.Serializer):
    """관측 배치 업로드. `observations` 배열로 받는다."""

    observations = BlockObservationSerializer(many=True)

    # 한 번에 받을 상한. 오프라인이 길어져도 이 정도면 충분하고, 더 큰 요청은
    # 메모리와 트랜잭션 시간을 먹는다.
    MAX_ITEMS = 200

    def validate_observations(self, value):
        if not value:
            raise serializers.ValidationError("관측이 비어 있다.")
        if len(value) > self.MAX_ITEMS:
            raise serializers.ValidationError(
                f"한 번에 {self.MAX_ITEMS}건까지 보낼 수 있다."
            )
        return value
