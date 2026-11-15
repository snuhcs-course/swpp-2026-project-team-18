# Partly AI-generated (Claude)
"""저장한 집(R3-2). 계약은 `docs/iter3/saved-places-api.md`.

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/saved-places/` | `{default, results}`. `default` 는 프로필 `home_*` (읽기 전용) |
| `POST /api/saved-places/` | 하나 저장. 50m 안의 행이 있으면 그 행의 `label` 만 바꿔 200 |
| `PATCH /api/saved-places/{id}/` | `label`·`name`·`address`·`lat`·`lng` |
| `DELETE /api/saved-places/{id}/` | 204. 그 집에서 출발하는 일정의 좌표는 남는다 |
| `POST /api/saved-places/{id}/make-default/` | 프로필 집과 맞바꾼다. 알람을 다시 계산한다 |
| `POST /api/saved-places/bulk/` | 앱 기기 목록 한 번 올리기. 한도·중복 규칙을 지킨다 |

끝의 `/` 는 있어도 없어도 된다(이 저장소의 다른 경로는 `/` 가 없다).

**규칙**: 사용자당 5개. 같은 사용자의 다른 행이나 기본 집에서 50m 안이면 같은 집이다. 기본 집
근처는 저장하지 않는다(409 `same_as_default`) — 기본 집은 목록의 `default` 로 이미 보인다.

**이름**(BE-H9, `docs/iter4/server-saved-place-labels-and-report-tone.md`): "집"(기본 집의 이름)이나
내 다른 저장한 집과 같은 이름은 받지 않는다(409 `saved_place_label_duplicate`, `details.label`).
비교는 [label_key] — 앞뒤 공백을 빼고 안의 공백은 하나로, 대소문자는 무시한다. 고치는 행 자신의
지금 이름은 빼고 본다(이름을 그대로 다시 보내면 통과). `bulk` 는 막지 않고 뒤에 " 2" 를 붙여 저장한다
— 기기에 같은 이름이 둘 있던 앱이 첫 업로드에서 막히지 않게. DB 제약은 두지 않는다.

**잠금**: 쓰는 요청(POST·PATCH·bulk·make-default)은 먼저 사용자의 프로필 행을 잠근다([lock_profile]).
사용자별 뮤텍스다 — 행 잠금은 이미 있는 행만 잠그므로, 같은 새 이름이나 5개째·6개째를 동시에 보낸 두
요청이 함께 검사를 통과하던 것을 막는다. 프로필 PATCH 도 같은 행을 잠근다. 순서는 늘 프로필 → 저장한 집.
(Partly AI-generated (Claude))
"""

from __future__ import annotations

import unicodedata

from django.db import transaction
from rest_framework import serializers, status
from rest_framework.exceptions import APIException
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.common.geo import distance_meters
from apps.events.geo import in_service_area

from .models import Profile, SavedPlace

# 같은 집으로 보는 반경(m). 앱의 저장한 집 중복 규칙과 같다.
HOME_RADIUS_M = 50


def near_home(profile, lat: float, lng: float) -> bool:
    """좌표가 기본 집(프로필 `home_*`)에서 [HOME_RADIUS_M] 안인가. 집이 없으면 False."""
    if profile.home_lat is None or profile.home_lng is None:
        return False
    return distance_meters(profile.home_lat, profile.home_lng, lat, lng) <= HOME_RADIUS_M


def lock_profile(user) -> Profile:
    """사용자의 프로필 행을 잠그고 새로 읽는다. 트랜잭션 안에서 부른다(SQLite 에서는 잠그지 않는다).

    저장한 집의 검사-저장을 사용자별로 한 줄로 세운다. 돌려준 프로필은 기본 집 검사에 쓴다 —
    `user.profile` 캐시는 다른 요청이 바꾼 집을 모를 수 있다. (Partly AI-generated (Claude))
    """
    profile = Profile.objects.select_for_update().get(user=user)
    user.profile = profile
    return profile


class SavedPlaceLimit(APIException):
    status_code = status.HTTP_409_CONFLICT
    default_code = "saved_place_limit"
    default_detail = f"집은 기본 집 말고 {SavedPlace.MAX_PER_USER}개까지 저장할 수 있어요."


class SameAsDefault(APIException):
    status_code = status.HTTP_409_CONFLICT
    default_code = "same_as_default"
    default_detail = f"기본 집에서 {HOME_RADIUS_M}m 안이에요. 기본 집으로 이미 저장돼 있어요."


class SavedPlaceDuplicate(APIException):
    status_code = status.HTTP_409_CONFLICT
    default_code = "saved_place_duplicate"
    default_detail = f"저장한 다른 집에서 {HOME_RADIUS_M}m 안이에요."


# 기본 집의 이름. 앱은 기본 집을 늘 "집" 으로 보여 주므로 다른 집에 쓰면 둘을 가를 수 없다.
RESERVED_LABEL = "집"
LABEL_RESERVED_MESSAGE = f"'{RESERVED_LABEL}'은 기본 집 이름이라 쓸 수 없어요. 다른 이름을 써 주세요."
LABEL_DUPLICATE_MESSAGE = "같은 이름의 집이 이미 있어요. 다른 이름을 써 주세요."
LABEL_BLANK_MESSAGE = "이름을 입력해 주세요."
# make-default 로 내린 예전 기본 집에 이름이 없거나 "집" 일 때 쓰는 이름.
PREVIOUS_HOME_LABEL = "예전 집"
LABEL_MAX = 40  # SavedPlace.label 의 max_length

RESULT_LABEL_RESERVED = "label_reserved"
RESULT_LABEL_DUPLICATE = "label_duplicate"


class SavedPlaceLabelDuplicate(APIException):
    """409. 이름 칸 오류라 `details.label` 에 이유를 담는다."""

    status_code = status.HTTP_409_CONFLICT
    default_code = "saved_place_label_duplicate"
    default_detail = LABEL_DUPLICATE_MESSAGE

    def __init__(self, reason: str = RESULT_LABEL_DUPLICATE):
        message = LABEL_RESERVED_MESSAGE if reason == RESULT_LABEL_RESERVED else LABEL_DUPLICATE_MESSAGE
        super().__init__(detail={"label": [message]})


def label_key(label: str | None) -> str:
    """이름 비교용 키. 유니코드 NFKC 로 맞추고, 서식 문자(Cf: 폭 없는 공백 U+200B, ZWJ, BOM 등)를 빼고,
    앞뒤 공백을 빼고 안의 공백은 하나로, casefold.

    " 본가 " · "본가" · "Home"·"home" · "우리  집"·"우리 집" · "집\u200b"·"집" 은 각각 같다.
    "본가2" 와 "본가 2" 는 다르다.
    """
    text = unicodedata.normalize("NFKC", label or "")
    text = "".join(ch for ch in text if unicodedata.category(ch) != "Cf")
    return " ".join(text.split()).casefold()


def label_conflict(label: str, rows, *, exclude_pk=None, current: str | None = None) -> str | None:
    """이 이름을 쓸 수 없으면 이유(`label_reserved`·`label_duplicate`), 쓸 수 있으면 None.

    [current] 는 고치는 행의 지금 이름이다. 같으면 통과한다 — 이름을 바꾸지 않는 요청(재시도,
    다른 칸만 고침)이 예전에 생긴 겹침 때문에 막히지 않게.
    """
    key = label_key(label)
    if current is not None and key == label_key(current):
        return None
    if key == label_key(RESERVED_LABEL):
        return RESULT_LABEL_RESERVED
    for row in rows:
        if row.pk != exclude_pk and label_key(row.label) == key:
            return RESULT_LABEL_DUPLICATE
    return None


def _suffixed(label: str):
    base = " ".join(label.split())
    for n in range(2, 100):
        suffix = f" {n}"
        yield base[: LABEL_MAX - len(suffix)].rstrip() + suffix


def unique_label(label: str, rows, *, exclude_pk=None, taken=frozenset(), prefer: str | None = None) -> str:
    """겹치지 않을 때까지 뒤에 " 2"·" 3"… 을 붙인다(`bulk`·`make-default` 용). [LABEL_MAX] 자를 넘지 않게
    앞을 자른다.

    [taken] 은 [rows] 밖에서 이미 쓰기로 한 이름의 [label_key] 다 — `bulk` 의 뒤 항목들이 보낸 이름.
    `["집", "집 2"]` 에서 앞 항목이 "집 2" 를 가져가 뒤 항목이 "집 2 2" 가 되던 것을 막는다.
    [prefer] 는 고치는 행의 지금 이름이다. 이 이름도 후보 중 하나이고 비어 있으면 그대로 쓴다 — 더 작은
    번호가 비었다고 다시 보낼 때마다 이름이 바뀌지 않게. (Partly AI-generated (Claude))
    """
    candidates = list(_suffixed(label))

    def free(candidate: str) -> bool:
        return (label_key(candidate) not in taken
                and label_conflict(candidate, rows, exclude_pk=exclude_pk) is None)

    if prefer is not None:
        prefer_key = label_key(prefer)
        if any(label_key(c) == prefer_key for c in candidates) and free(prefer):
            return prefer
    for candidate in candidates:
        if free(candidate):
            return candidate
    return " ".join(label.split())[:LABEL_MAX]  # 한도가 5개, bulk 20개라 닿지 않는다


class SavedPlaceSerializer(serializers.ModelSerializer):
    class Meta:
        model = SavedPlace
        fields = ("id", "client_uuid", "label", "name", "address", "lat", "lng", "created_at")
        read_only_fields = ("id", "created_at")
        extra_kwargs = {
            "client_uuid": {"required": False, "allow_blank": True},
            # 빈 이름·공백만 있는 이름(앞뒤 공백은 DRF 가 뺀다)도 앱이 그대로 보일 문구로. (H9-5)
            "label": {"error_messages": {"blank": LABEL_BLANK_MESSAGE}},
            "name": {"required": False, "allow_blank": True},
            "address": {"required": False, "allow_blank": True},
        }

    def validate_label(self, value: str) -> str:
        label = value.strip()
        # 폭 없는 공백 같은 서식 문자만 있는 이름도 빈 이름이다(H9-4). (Partly AI-generated (Claude))
        if not label_key(label):
            raise serializers.ValidationError(LABEL_BLANK_MESSAGE)
        return label

    def validate(self, attrs):
        lat = attrs.get("lat", getattr(self.instance, "lat", None))
        lng = attrs.get("lng", getattr(self.instance, "lng", None))
        if lat is not None and lng is not None and not in_service_area(lat, lng):
            raise serializers.ValidationError({"lat": "국내 좌표만 집으로 저장할 수 있어요."})
        return attrs


def default_json(profile) -> dict | None:
    """프로필 집을 목록 행과 같은 모양으로. 없으면 None."""
    if profile.home_lat is None or profile.home_lng is None:
        return None
    return {
        "id": None,
        "client_uuid": "",
        "label": profile.home_label or "집",
        "name": profile.home_label or "",
        "address": "",
        "lat": profile.home_lat,
        "lng": profile.home_lng,
        "created_at": None,
    }


def list_json(user) -> dict:
    return {
        "default": default_json(user.profile),
        "results": SavedPlaceSerializer(user.saved_places.all(), many=True).data,
    }


def _near(rows, lat: float, lng: float, *, exclude_pk=None) -> SavedPlace | None:
    for row in rows:
        if row.pk == exclude_pk:
            continue
        if distance_meters(row.lat, row.lng, lat, lng) <= HOME_RADIUS_M:
            return row
    return None


def save_one(
    user, data: dict, *, profile: Profile, rename: bool = False, taken=frozenset()
) -> tuple[SavedPlace | None, str]:
    """한 곳을 저장한다. `(행, 결과)`. 결과는 `created`·`updated`·`same_as_default`·`limit`·
    `label_reserved`·`label_duplicate`.

    [profile] 은 [lock_profile] 로 잠근 프로필이다(사용자별 뮤텍스, H9-2).
    같은 `client_uuid` 가 있으면 그 행을 고친다. 50m 안의 행이 있으면 그 행의 `label` 만 바꾼다.
    이름이 겹치면 저장하지 않는다. [rename] 이면 대신 [unique_label] 로 바꿔 저장한다(`bulk`,
    [taken] 은 뒤 항목들의 이름).

    검사 순서: 기본 집 50m → 같은 자리가 아닌 새 행이면 한도 → 이름. 같은 `client_uuid` 행을 고치는
    요청은 한도를 보지 않는다.
    """
    rows = list(SavedPlace.objects.select_for_update().filter(user=user))
    uuid = (data.get("client_uuid") or "").strip()
    target = next((r for r in rows if r.client_uuid == uuid), None) if uuid else None
    same_uuid = target is not None
    if target is None:
        if near_home(profile, data["lat"], data["lng"]):
            return None, "same_as_default"
        target = _near(rows, data["lat"], data["lng"])
        if target is None and len(rows) >= SavedPlace.MAX_PER_USER:
            return None, "limit"

    label = data["label"]
    exclude_pk = target.pk if target is not None else None
    conflict = label_conflict(
        label, rows, exclude_pk=exclude_pk, current=target.label if target is not None else None
    )
    if conflict is not None:
        if not rename:
            return None, conflict
        label = unique_label(
            label, rows, exclude_pk=exclude_pk, taken=taken,
            prefer=target.label if target is not None else None,
        )

    if target is not None:
        target.label = label
        fields = ["label"]
        if not same_uuid and uuid and not target.client_uuid:
            target.client_uuid = uuid
            fields.append("client_uuid")
        target.save(update_fields=fields)
        return target, "updated"
    row = SavedPlace.objects.create(
        user=user,
        client_uuid=uuid,
        label=label,
        name=data.get("name") or "",
        address=data.get("address") or "",
        lat=data["lat"],
        lng=data["lng"],
    )
    return row, "created"


class SavedPlaceListView(APIView):
    """GET·POST /api/saved-places/"""

    def get(self, request):
        return Response(list_json(request.user))

    def post(self, request):
        serializer = SavedPlaceSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        with transaction.atomic():
            profile = lock_profile(request.user)
            row, result = save_one(request.user, serializer.validated_data, profile=profile)
        if result == "same_as_default":
            raise SameAsDefault()
        if result == "limit":
            raise SavedPlaceLimit()
        if result in (RESULT_LABEL_RESERVED, RESULT_LABEL_DUPLICATE):
            raise SavedPlaceLabelDuplicate(result)
        return Response(
            SavedPlaceSerializer(row).data,
            status=status.HTTP_201_CREATED if result == "created" else status.HTTP_200_OK,
        )


class SavedPlaceDetailView(APIView):
    """PATCH·DELETE /api/saved-places/{id}/ — 남의 행은 404."""

    def _row(self, request, pk):
        return SavedPlace.objects.filter(user=request.user, pk=pk).first()

    def patch(self, request, pk: int):
        # 검사-저장을 프로필 잠금 안에서 한다(H9-2). 전에는 트랜잭션도 잠금도 없어 POST 와 겹친
        # PATCH 가 같은 이름을 만들 수 있었다. (Partly AI-generated (Claude))
        with transaction.atomic():
            profile = lock_profile(request.user)
            row = SavedPlace.objects.select_for_update().filter(user=request.user, pk=pk).first()
            if row is None:
                return Response(status=status.HTTP_404_NOT_FOUND)
            serializer = SavedPlaceSerializer(row, data=request.data, partial=True)
            serializer.is_valid(raise_exception=True)
            data = serializer.validated_data
            others = list(SavedPlace.objects.filter(user=request.user))
            if "lat" in data or "lng" in data:
                lat, lng = data.get("lat", row.lat), data.get("lng", row.lng)
                if near_home(profile, lat, lng):
                    raise SameAsDefault()
                if _near(others, lat, lng, exclude_pk=row.pk) is not None:
                    raise SavedPlaceDuplicate()
            if "label" in data:
                conflict = label_conflict(data["label"], others, exclude_pk=row.pk, current=row.label)
                if conflict is not None:
                    raise SavedPlaceLabelDuplicate(conflict)
            data.pop("client_uuid", None)  # 앱이 만든 식별자는 바꾸지 않는다
            serializer.save()
        return Response(serializer.data)

    def delete(self, request, pk: int):
        row = self._row(request, pk)
        if row is None:
            return Response(status=status.HTTP_404_NOT_FOUND)
        row.delete()
        return Response(status=status.HTTP_204_NO_CONTENT)


def demoted_label(home_label: str, others) -> str:
    """make-default 로 저장한 집이 되는 예전 기본 집의 이름(H9-1).

    예전 `home_label`(장소 이름인 경우가 많다)을 쓰고, 비어 있거나 "집" 이면 [PREVIOUS_HOME_LABEL]
    을 쓴다. 다른 저장한 집과 겹치면 [unique_label] 로 번호를 붙인다. 전에는 `home_label or "집"`
    을 이름 규칙 없이 넣어 "집" 이름의 행이나 같은 이름의 행이 생겼다. (Partly AI-generated (Claude))
    """
    base = " ".join((home_label or "").split())[:LABEL_MAX].rstrip()
    if not label_key(base) or label_key(base) == label_key(RESERVED_LABEL):
        base = PREVIOUS_HOME_LABEL
    if label_conflict(base, others) is None:
        return base
    return unique_label(base, others)


class SavedPlaceMakeDefaultView(APIView):
    """POST /api/saved-places/{id}/make-default/ — 이 집과 기본 집(프로필 `home_*`)을 맞바꾼다.

    한 트랜잭션이다. 프로필에는 이 행의 자리를, 이 행에는 예전 기본 집 자리를 넣는다(행 id 는
    그대로, 앱의 `client_uuid` 는 비운다 — 이제 다른 집이다). 기본 집이 없었으면 행을 지운다.

    이 집에서 출발하던 **앞으로의** 일정은 출발지를 비워 기본 집 출발로 바꾼다(좌표가 같다).
    지난 일정은 그대로 둔다 — 좌표는 그날 출발한 자리이고, `origin_place` 가 남아 있어 준비
    학습에서 집 출발로 계속 센다. 기본 집이 바뀌면 프로필 수정과 같이 알람을 다시 계산한다.
    응답은 목록 조회와 같은 모양이다.
    """

    def post(self, request, pk: int):
        from django.utils import timezone

        from apps.events.models import Event
        from apps.planning import services as planning_services
        from apps.planning.models import PlanRevision

        with transaction.atomic():
            # 프로필을 먼저 잠근다(H9-2·H4-1). 예전 기본 집을 잠근 행에서 읽어야 같은 때 들어온 프로필
            # PATCH 의 집 변경을 덮지 않는다.
            profile = lock_profile(request.user)
            row = (
                SavedPlace.objects.select_for_update()
                .filter(user=request.user, pk=pk)
                .first()
            )
            if row is None:
                return Response(status=status.HTTP_404_NOT_FOUND)
            old = default_json(profile)
            profile.home_lat, profile.home_lng = row.lat, row.lng
            profile.home_label = row.label[:80]
            profile.save(update_fields=["home_lat", "home_lng", "home_label"])
            Event.objects.filter(
                user=request.user, origin_place=row, start_at__gte=timezone.now()
            ).update(origin_place=None, origin_lat=None, origin_lng=None, origin_label="")
            if old is None:
                row.delete()
            else:
                row.client_uuid = ""
                others = [r for r in SavedPlace.objects.filter(user=request.user) if r.pk != row.pk]
                row.label = demoted_label(old["name"], others)
                row.name = (old["name"] or "")[:120]
                row.address = ""
                row.lat, row.lng = old["lat"], old["lng"]
                row.save()
        planning_services.recompute_for_user(request.user, trigger=PlanRevision.Trigger.PROFILE)
        return Response(list_json(request.user))


class BulkItemSerializer(SavedPlaceSerializer):
    class Meta(SavedPlaceSerializer.Meta):
        extra_kwargs = {
            **SavedPlaceSerializer.Meta.extra_kwargs,
            "client_uuid": {"required": True, "allow_blank": False},
        }


class BulkSerializer(serializers.Serializer):
    items = BulkItemSerializer(many=True, allow_empty=True)

    def validate_items(self, value):
        if len(value) > 20:
            raise serializers.ValidationError("한 번에 20개까지 올릴 수 있어요.")
        return value


class SavedPlaceBulkView(APIView):
    """POST /api/saved-places/bulk/ `{items: [...]}` — 앱 기기 목록 한 번 올리기.

    항목마다 `save_one` 규칙을 그대로 쓴다. 다시 보내도 늘지 않는다(`client_uuid`). 저장하지 못한
    항목은 `skipped` 에 이유와 함께 돌려주고 나머지는 저장한다. 200 `{default, results, skipped, renamed}`.

    이름이 "집"이거나 다른 집(앞 항목 포함)과 겹치면 건너뛰지 않고 뒤에 " 2" 를 붙여 저장한다
    (BE-H9). 앱은 bulk 응답으로 기기 목록을 바꾸고 `limit` 만 사용자에게 알리므로, 건너뛰면 그
    집이 말없이 사라진다. 바꾼 항목은 `renamed: [{client_uuid, label, requested_label}]` 에 담는다.
    """

    def post(self, request):
        serializer = BulkSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        skipped = []
        renamed = []
        items = serializer.validated_data["items"]
        with transaction.atomic():
            profile = lock_profile(request.user)
            for index, item in enumerate(items):
                # 뒤 항목들이 보낸 이름은 번호를 고를 때 비워 둔다(H9-3).
                taken = frozenset(label_key(later["label"]) for later in items[index + 1:])
                row, result = save_one(request.user, item, profile=profile, rename=True, taken=taken)
                if result in ("same_as_default", "limit"):
                    skipped.append({"client_uuid": item["client_uuid"], "reason": result})
                elif row is not None and row.label != item["label"]:
                    renamed.append({
                        "client_uuid": item["client_uuid"],
                        "label": row.label,
                        "requested_label": item["label"],
                    })
        return Response({**list_json(request.user), "skipped": skipped, "renamed": renamed})
