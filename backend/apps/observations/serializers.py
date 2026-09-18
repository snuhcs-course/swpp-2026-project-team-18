"""이동 관측 시리얼라이저.

읽기·쓰기를 나눈다. 쓰기에는 `user` 가 없다 — 받으면 남의 계정에 관측을
심을 수 있다. 뷰가 `request.user` 를 직접 넣는다.
"""

from rest_framework import serializers
from rest_framework.exceptions import NotFound

from apps.events.models import Event

from .models import TripObservation

# 판정에 쓸 수 있는 최대 오차. 이보다 흐린 fix 로는 반경 판정을 말할 수 없다.
# 앱의 TripGeofence.MAX_ACCURACY_M 과 같은 값이어야 한다. 앱이 이미 걸러서
# 보내지만 서버도 막는다 — 클라이언트를 신뢰하면 학습이 오염된다.
MAX_ACCURACY_M = 50.0


class TripObservationSerializer(serializers.ModelSerializer):
    """응답. 계획 대비 지연을 함께 내려 앱이 다시 계산하지 않게 한다."""

    kind_label = serializers.CharField(source="get_kind_display", read_only=True)
    planned_at = serializers.DateTimeField(read_only=True)
    delay_minutes = serializers.IntegerField(read_only=True)

    class Meta:
        model = TripObservation
        fields = (
            "id",
            "event",
            "kind",
            "kind_label",
            "detector",
            "observed_at",
            "lat",
            "lng",
            "accuracy_m",
            "distance_m",
            "planned_at",
            "delay_minutes",
            "client_uuid",
            "created_at",
        )
        read_only_fields = fields


class TripObservationWriteSerializer(serializers.ModelSerializer):
    """입력 한 건.

    `event` 는 요청자 소유여야 한다. 아니면 404 다 — 403 을 주면 그 id 의
    일정이 존재한다는 사실이 새어 나간다(events 앱과 같은 규칙).
    """

    class Meta:
        model = TripObservation
        fields = (
            "event",
            "kind",
            "detector",
            "observed_at",
            "lat",
            "lng",
            "accuracy_m",
            "distance_m",
            "client_uuid",
        )

    def validate_event(self, value: Event) -> Event:
        request = self.context.get("request")
        if request is None or value.user_id != request.user.id:
            raise NotFound("일정을 찾을 수 없다.")
        return value

    def validate_client_uuid(self, value: str) -> str:
        key = (value or "").strip()
        if not key:
            raise serializers.ValidationError("client_uuid 가 필요하다.")
        return key[:40]

    def validate_accuracy_m(self, value: float) -> float:
        if value < 0:
            raise serializers.ValidationError("정확도는 음수일 수 없다.")
        if value > MAX_ACCURACY_M:
            raise serializers.ValidationError(
                f"오차 {value:.0f}m 인 위치로는 반경 판정을 신뢰할 수 없다. "
                f"{MAX_ACCURACY_M:.0f}m 이하만 받는다."
            )
        return value

    def validate_distance_m(self, value: float) -> float:
        if value < 0:
            raise serializers.ValidationError("거리는 음수일 수 없다.")
        return value

    def validate_lat(self, value: float) -> float:
        if not -90 <= value <= 90:
            raise serializers.ValidationError("위도 범위를 벗어났다.")
        return value

    def validate_lng(self, value: float) -> float:
        if not -180 <= value <= 180:
            raise serializers.ValidationError("경도 범위를 벗어났다.")
        return value


class TripObservationBatchSerializer(serializers.Serializer):
    """배치 업로드 본문.

    앱은 오프라인일 수 있으므로 한 번에 여러 건을 보낸다. 상한을 두지 않으면
    한 요청이 DB 를 오래 잡는다.
    """

    MAX_ITEMS = 100

    observations = TripObservationWriteSerializer(many=True)

    def validate_observations(self, value):
        if not value:
            raise serializers.ValidationError("관측이 비어 있다.")
        if len(value) > self.MAX_ITEMS:
            raise serializers.ValidationError(
                f"한 번에 {self.MAX_ITEMS}건까지 보낼 수 있다."
            )
        return value
