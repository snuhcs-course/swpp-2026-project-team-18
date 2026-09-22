"""일정 API 시리얼라이저. back-spec.md 5.3."""

from __future__ import annotations

from django.db import transaction
from rest_framework import serializers

# 서비스 범위 판정. `views.py` 가 아니라 `geo.py` 에서 가져온다 — 시리얼라이저가
# 뷰를 import 하면 순환이 된다.
from .geo import in_service_area
from .models import Event, EventTag, Place


class PlaceSerializer(serializers.ModelSerializer):
    class Meta:
        model = Place
        fields = ("id", "name", "address", "lat", "lng", "kakao_place_id")
        read_only_fields = ("id",)


class EventTagSerializer(serializers.ModelSerializer):
    class Meta:
        model = EventTag
        fields = ("id", "key", "label", "default_tau", "penalty_shape")
        read_only_fields = fields


class AlarmPlanSerializer(serializers.Serializer):
    """일정에 딸린 알람 계획.

    `on_time_probability` 는 **null 일 수 있다.** 준비·이동 **양쪽** 모두
    변동성이 있을 때만 채운다. 한쪽만 있으면 없는 쪽을 0 분산으로 치게 되어
    확신도를 과대 보고한다.

    왜 null 인지는 `confidence_basis` 가 알려준다. 화면이 "학습 중" 문구를
    구체적으로 쓸 수 있어야 한다 — 사용자가 할 수 있는 행동이 다르다.

    | confidence_basis | 뜻 | 사용자가 할 일 |
    | --- | --- | --- |
    | `observed` | 둘 다 관측 기반. 확률이 있다 | — |
    | `point_estimate` | 둘 다 점추정 | 루틴 블록을 등록하면 시작된다 |
    | `travel_variance_unknown` | 준비만 변동성 있음 | 같은 경로를 몇 번 다니면 쌓인다 |
    | `prep_variance_unknown` | 이동만 변동성 있음 | 루틴 블록에 범위를 넣으면 된다 |
    """

    status = serializers.CharField()
    status_label = serializers.CharField(source="get_status_display")
    alarm_at = serializers.DateTimeField(allow_null=True)
    depart_by = serializers.DateTimeField(allow_null=True)
    arrive_at = serializers.DateTimeField(allow_null=True)
    prep_minutes = serializers.IntegerField(allow_null=True)
    travel_minutes = serializers.IntegerField(allow_null=True)
    buffer_minutes = serializers.IntegerField(allow_null=True)
    total_minutes = serializers.IntegerField(allow_null=True)
    tau_used = serializers.FloatField(allow_null=True)
    on_time_probability = serializers.IntegerField(allow_null=True)
    # 확률이 없을 때 그 이유. 위 표 참고.
    confidence_basis = serializers.CharField()
    travel_mode = serializers.CharField()
    route_summary = serializers.CharField()
    route_key = serializers.CharField()
    route_detail = serializers.CharField()
    # 블록별 내역. 근거 카드가 "샤워 14분 · 옷 5분" 을 그린다.
    # 블록이 없으면 빈 배열이다.
    prep_breakdown = serializers.JSONField()
    # 사용자가 고른 경로가 그대로 쓰였는지. 배차가 바뀌어 사라지면 서버가
    # 대체 경로로 계산하는데, 화면이 그 사실을 알려야 한다.
    route_choice_honored = serializers.SerializerMethodField()

    # 계산에 쓴 값의 출처. 세 값의 신뢰도가 다른데 나란히 놓으면 전부
    # 학습된 값처럼 읽힌다(front-spec S_alarm 의 지적). 서버가 명시한다.
    prep_source = serializers.SerializerMethodField()
    buffer_source = serializers.SerializerMethodField()
    travel_time_source = serializers.SerializerMethodField()

    def get_route_choice_honored(self, obj) -> bool | None:
        chosen = (obj.event.route_key or "").strip()
        if not chosen:
            return None  # 고른 적이 없다
        return chosen == (obj.route_key or "")

    def get_prep_source(self, obj) -> str:
        """준비 시간의 출처.

        이제 계산기가 직접 기록한다. 추측하지 않는다.

          observed        블록 관측 기반
          declared_range  블록에 범위를 신고했다 (변동성 있음)
          declared_point  블록을 신고했지만 범위가 한 점이다
          onboarding      블록이 없어 온보딩 응답을 썼다
          fixed           온보딩도 없어 기본값을 썼다
        """
        if obj.prep_minutes is None:
            return ""
        return obj.prep_source or "fixed"

    def get_buffer_source(self, obj) -> str:
        return "" if obj.buffer_minutes is None else "fixed"

    def get_travel_time_source(self, obj) -> str:
        return obj.travel_source or ""


class EventSerializer(serializers.ModelSerializer):
    """일정 조회용. 장소·태그·알람 계획을 펼쳐서 내린다.

    클라이언트가 목록 한 번으로 화면을 그릴 수 있게 중첩해서 준다. 홈 화면이
    일정마다 알람을 또 조회하면 N+1 왕복이 된다.
    """

    place = PlaceSerializer(read_only=True)
    tag = EventTagSerializer(read_only=True)
    alarm_plan = serializers.SerializerMethodField()

    class Meta:
        model = Event
        fields = (
            "id",
            "title",
            "start_at",
            "source",
            "place",
            "tag",
            "tau_override",
            "route_key",
            "origin_lat",
            "origin_lng",
            "origin_label",
            "alarm_plan",
            "created_at",
        )
        read_only_fields = ("id", "source", "created_at")

    def get_alarm_plan(self, obj):
        plan = getattr(obj, "alarm_plan", None)
        if plan is None:
            return None
        return AlarmPlanSerializer(plan).data


class PlaceInputSerializer(serializers.Serializer):
    """일정 생성 시 함께 넘어오는 장소.

    카카오 검색 결과를 그대로 받는다. `kakao_place_id` 가 같으면 기존 행을
    재사용해 중복을 막는다.
    """

    name = serializers.CharField(max_length=120)
    lat = serializers.FloatField(min_value=-90, max_value=90)
    lng = serializers.FloatField(min_value=-180, max_value=180)
    address = serializers.CharField(max_length=200, required=False, allow_blank=True)
    kakao_place_id = serializers.CharField(
        max_length=40, required=False, allow_null=True, allow_blank=True
    )

    def validate(self, attrs):
        """목적지도 국내여야 한다.

        **출발지만 막고 있었다.** 파리를 목적지로 넣으면 일정이 201 로
        생성되고, 알람 계산이 카카오에 국외 목적지를 물어 쿼터를 쓰고 실패한다.
        경로의 양 끝이 모두 국내여야 카카오가 답한다.

        `Place` 는 전역 공유 표라서 더 중요하다 — 국외 행이 쌓이면 다른
        사용자의 검색 결과에도 섞인다.
        """
        lat, lng = attrs.get("lat"), attrs.get("lng")
        if lat is not None and lng is not None and not in_service_area(lat, lng):
            raise serializers.ValidationError(
                {"lat": "국내 좌표만 목적지로 쓸 수 있다."}
            )
        return attrs

    def resolve(self) -> Place:
        data = self.validated_data
        kakao_id = (data.get("kakao_place_id") or "").strip() or None

        if kakao_id:
            place, _ = Place.objects.update_or_create(
                kakao_place_id=kakao_id,
                defaults={
                    "name": data["name"],
                    "lat": data["lat"],
                    "lng": data["lng"],
                    "address": data.get("address", ""),
                },
            )
            return place

        # 수동 입력. 같은 이름·좌표가 이미 있으면 재사용한다.
        place, _ = Place.objects.get_or_create(
            name=data["name"],
            lat=data["lat"],
            lng=data["lng"],
            kakao_place_id=None,
            defaults={"address": data.get("address", "")},
        )
        return place


class EventWriteSerializer(serializers.ModelSerializer):
    """일정 생성·수정.

    `user` 는 요청에서 받지 않는다. **뷰가 `request.user` 를 넣는다.**
    받으면 남의 계정에 일정을 만들 수 있다.
    """

    place = PlaceInputSerializer(required=False, allow_null=True)
    tag_key = serializers.CharField(required=False, allow_null=True, allow_blank=True)

    class Meta:
        model = Event
        fields = (
            "id",
            "title",
            "start_at",
            "place",
            "tag_key",
            "tau_override",
            "route_key",
            "origin_lat",
            "origin_lng",
            "origin_label",
        )
        read_only_fields = ("id",)

    def validate(self, attrs):
        """출발지 좌표는 둘 다 있거나 둘 다 없어야 한다.

        하나만 받아 조용히 집으로 폴백하면, 사용자는 자기가 고른 곳에서
        출발한다고 믿는데 알람은 집 기준으로 계산된다. 모델 CheckConstraint 와
        같은 규칙을 API 층에서 먼저 막아 읽을 수 있는 오류를 준다.

        부분 수정(PATCH)에서는 저장된 값과 합쳐서 판단한다.
        """
        lat = attrs.get("origin_lat", getattr(self.instance, "origin_lat", None))
        lng = attrs.get("origin_lng", getattr(self.instance, "origin_lng", None))
        if (lat is None) != (lng is None):
            raise serializers.ValidationError(
                {"origin_lat": "origin_lat 과 origin_lng 는 함께 보내야 한다."}
            )

        # 국내 좌표만 받는다. `GET /api/routes/candidates` 는 이미 막고 있었는데
        # **여기에는 검사가 없어서 국외 출발지가 일정에 저장됐다.** 그러면
        # 알람 계산이 국외 좌표로 카카오를 불러 쿼터를 쓰고 실패한다.
        # 규칙은 `geo.in_service_area` 한 곳에 둔다.
        if lat is not None and lng is not None and not in_service_area(lat, lng):
            raise serializers.ValidationError(
                {"origin_lat": "국내 좌표만 출발지로 쓸 수 있다."}
            )
        return attrs

    def validate_route_key(self, value):
        """`GET /api/routes/candidates` 가 준 key 형식만 받는다.

        임의 문자열을 받으면 알람 계산 때 `resolve_route` 가 매번 실패해
        `ROUTE_FAILED` 가 된다. 형식을 여기서 막는다.
        """
        key = (value or "").strip()
        if not key:
            return ""
        if key in ("walk", "bicycle", "car") or key.startswith("transit:"):
            return key[:120]
        raise serializers.ValidationError(
            "경로 형식이 올바르지 않다. walk/bicycle/car 또는 transit:... 이어야 한다."
        )

    def validate_title(self, value: str) -> str:
        title = value.strip()
        if not title:
            raise serializers.ValidationError("제목을 입력해야 한다.")
        return title

    def validate_tag_key(self, value):
        key = (value or "").strip()
        if not key:
            return None
        if not EventTag.objects.filter(key=key).exists():
            raise serializers.ValidationError(f"없는 태그다: {key}")
        return key

    @transaction.atomic
    def create(self, validated_data):
        place_data = validated_data.pop("place", None)
        tag_key = validated_data.pop("tag_key", None)

        event = Event(
            user=self.context["request"].user,
            source=Event.Source.MANUAL,
            **validated_data,
        )
        event.place = self._resolve_place(place_data)
        event.tag = EventTag.objects.filter(key=tag_key).first() if tag_key else None
        event.save()
        return event

    @transaction.atomic
    def update(self, instance, validated_data):
        if "place" in validated_data:
            instance.place = self._resolve_place(validated_data.pop("place"))
        if "tag_key" in validated_data:
            key = validated_data.pop("tag_key")
            instance.tag = EventTag.objects.filter(key=key).first() if key else None

        for field, value in validated_data.items():
            setattr(instance, field, value)
        instance.save()
        return instance

    def _resolve_place(self, place_data) -> Place | None:
        if not place_data:
            return None
        inner = PlaceInputSerializer(data=place_data)
        inner.is_valid(raise_exception=True)
        return inner.resolve()
