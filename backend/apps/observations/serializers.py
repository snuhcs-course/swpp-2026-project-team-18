"""이동 관측 시리얼라이저.

읽기·쓰기를 나눈다. 쓰기에는 `user` 가 없다 — 받으면 남의 계정에 관측을
심을 수 있다. 뷰가 `request.user` 를 직접 넣는다.
"""

from rest_framework import serializers
from rest_framework.exceptions import NotFound

from apps.events.models import Event

from .models import AlarmLog, TripObservation

# 판정에 쓸 수 있는 최대 오차. 이보다 흐린 fix 로는 반경 판정을 말할 수 없다.
# 앱의 TripGeofence.MAX_ACCURACY_M 과 같은 값이어야 한다. 앱이 이미 걸러서
# 보내지만 서버도 막는다 — 클라이언트를 신뢰하면 학습이 오염된다.
MAX_ACCURACY_M = 50.0

# 받을 수 있는 최대 체류 시간(초). 추적은 일정 시작 후 한 시간에 끝나므로
# 그보다 긴 체류는 나올 수 없다. 두 배로 여유를 둔다.
MAX_DWELL_SECONDS = 7200


class TripObservationSerializer(serializers.ModelSerializer):
    """응답. 계획 대비 지연을 함께 내려 앱이 다시 계산하지 않게 한다."""

    kind_label = serializers.CharField(source="get_kind_display", read_only=True)
    planned_at = serializers.DateTimeField(read_only=True)
    delay_minutes = serializers.IntegerField(read_only=True)
    dwell_confirmed = serializers.BooleanField(read_only=True)

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
            "dwell_seconds",
            "dwell_confirmed",
            # Partly AI-generated (Claude) — 반경 진입 시각. 출발·옛 관측은 null.
            "entered_at",
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
            "dwell_seconds",
            # Partly AI-generated (Claude) — 선택. 구버전 앱은 보내지 않는다.
            "entered_at",
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

    def validate_dwell_seconds(self, value):
        """체류 시간 상한.

        추적은 일정 시작 후 한 시간에 끝나므로 그보다 긴 체류는 나올 수 없다.
        여유를 두고 상한을 두는 이유는 이 값이 학습 필터로 쓰이기 때문이다 —
        쓰레기 값 하나가 "체류 2분 이상" 필터를 통과해 버린다.
        """
        if value is None:
            return value
        if value > MAX_DWELL_SECONDS:
            raise serializers.ValidationError(
                f"체류 {value}초는 추적 가능한 시간을 넘는다. "
                f"{MAX_DWELL_SECONDS}초 이하만 받는다."
            )
        return value

    def validate(self, attrs):
        """출발 관측에는 체류 시간이 없다.

        조용히 지우지 않고 막는다. 출발에 체류 시간이 붙어 오는 것은 앱의
        배선 오류이고, 지워 버리면 그 오류가 드러나지 않는다.
        """
        kind = attrs.get("kind")
        if kind == TripObservation.Kind.DEPART and attrs.get("dwell_seconds") is not None:
            raise serializers.ValidationError(
                {"dwell_seconds": "출발 관측에는 체류 시간이 없다."}
            )
        self._validate_entered_at(attrs)
        return attrs

    # Partly AI-generated (Claude)
    @staticmethod
    def _validate_entered_at(attrs) -> None:
        """반경 진입 시각은 도착에만, 판정 시각보다 앞에, 체류 상한 안에 있다.

        `dwell_seconds` 와 같은 이유로 조용히 지우지 않고 막는다. 출발에 진입 시각이
        붙어 오는 것은 앱의 배선 오류이고, 판정보다 늦은 진입은 시계가 꼬인 값이다 —
        둘 다 학습 필터에서 쓰레기 값이 된다.
        """
        entered = attrs.get("entered_at")
        if entered is None:
            return
        if attrs.get("kind") != TripObservation.Kind.ARRIVE:
            raise serializers.ValidationError(
                {"entered_at": "반경 진입 시각은 도착 관측에만 있다."}
            )
        observed = attrs.get("observed_at")
        if observed is None:
            return
        if entered > observed:
            raise serializers.ValidationError(
                {"entered_at": "반경 진입 시각이 판정 시각보다 늦을 수 없다."}
            )
        # 초 단위로 내려서 본다. 앱이 `dwell_seconds` 를 같은 차이의 내림으로 보내므로, 체류가
        # 통과하는 관측을 진입 시각 때문에 막지 않는다(막으면 앱 큐가 배치를 통째로 버린다).
        if int((observed - entered).total_seconds()) > MAX_DWELL_SECONDS:
            raise serializers.ValidationError(
                {
                    "entered_at": (
                        f"진입부터 판정까지 {MAX_DWELL_SECONDS}초를 넘을 수 없다. "
                        "추적 가능한 시간을 넘는다."
                    )
                }
            )

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


# Partly AI-generated (Claude)
# 미루기 간격의 상한(분). 앱은 5분으로 고정하지만 서버는 학습을 오염시키지 않는 범위만 본다.
MAX_SNOOZE_MINUTES = 60


class AlarmLogWriteSerializer(serializers.ModelSerializer):
    """앱이 올리는 알람 사건 한 건(db-schema.md A3).

    `user`·`source` 는 받지 않는다. 뷰가 `request.user` 와 `app` 을 넣는다. 켬/끔은
    서버가 일정 수정에서 직접 남기므로 앱이 보낼 수 있는 종류에서 뺀다.
    """

    kind = serializers.ChoiceField(choices=[(k.value, k.label) for k in AlarmLog.APP_KINDS])

    class Meta:
        model = AlarmLog
        fields = (
            "event",
            "kind",
            "occurred_at",
            "alarm_at",
            "snooze_minutes",
            "client_uuid",
        )
        extra_kwargs = {
            # 앱은 언제나 어느 일정의 알람인지 안다. 일정이 지워지면 DB 에서만 null 이 된다.
            "event": {"required": True, "allow_null": False},
        }

    def validate_event(self, value: Event) -> Event:
        # 이동 관측과 같은 규칙. 403 이 아니라 404 — 그 id 의 일정이 있다는 사실을 흘리지 않는다.
        request = self.context.get("request")
        if request is None or value.user_id != request.user.id:
            raise NotFound("일정을 찾을 수 없다.")
        return value

    def validate_client_uuid(self, value: str) -> str:
        key = (value or "").strip()
        if not key:
            raise serializers.ValidationError("client_uuid 가 필요하다.")
        return key[:40]

    def validate(self, attrs):
        """미룬 분은 미루기에만 있다. 출발 관측의 체류 시간과 같은 이유로 막는다."""
        minutes = attrs.get("snooze_minutes")
        if attrs.get("kind") == AlarmLog.Kind.SNOOZED:
            if minutes is None:
                raise serializers.ValidationError({"snooze_minutes": "미룬 분이 필요하다."})
            if not 1 <= minutes <= MAX_SNOOZE_MINUTES:
                raise serializers.ValidationError(
                    {"snooze_minutes": f"1~{MAX_SNOOZE_MINUTES}분만 받는다."}
                )
        elif minutes is not None:
            raise serializers.ValidationError(
                {"snooze_minutes": "미룬 분은 미루기 기록에만 있다."}
            )
        return attrs


class AlarmLogBatchSerializer(serializers.Serializer):
    """배치 업로드 본문. 이동 관측 배치와 같은 상한을 둔다."""

    MAX_ITEMS = 100

    logs = AlarmLogWriteSerializer(many=True)

    def validate_logs(self, value):
        if not value:
            raise serializers.ValidationError("기록이 비어 있다.")
        if len(value) > self.MAX_ITEMS:
            raise serializers.ValidationError(
                f"한 번에 {self.MAX_ITEMS}건까지 보낼 수 있다."
            )
        return value
