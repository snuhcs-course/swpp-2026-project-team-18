"""일정 API. back-spec.md 5.3.

**모든 조회는 `request.user` 로 좁힌다.** 목록만 필터하고 상세를 빼먹으면
남의 일정을 id 로 읽을 수 있다. `get_queryset()` 한 곳에서 걸러 상속받게 한다.
"""

from __future__ import annotations

import hashlib

from django.core.cache import cache
from django.db import transaction
from django.http import HttpResponse
from django.utils.dateparse import parse_datetime
from rest_framework import status
from rest_framework.generics import ListCreateAPIView, RetrieveUpdateDestroyAPIView
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from apps.planning import services as planning_services
from apps.routing import clients

from .models import Event, EventTag
from .serializers import (
    CalendarImportSerializer,
    EventSerializer,
    EventTagSerializer,
    EventWriteSerializer,
    PlaceInputSerializer,
)


class _UserScopedMixin:
    """사용자 소유 일정만 다룬다. 목록과 상세가 같은 규칙을 쓰게 한다."""

    def get_queryset(self):
        return (
            Event.objects.filter(user=self.request.user)
            .select_related("place", "tag", "alarm_plan", "user__profile")
        )


class EventListCreateView(_UserScopedMixin, ListCreateAPIView):
    """GET /api/events   — 목록. `?from=&to=` ISO8601 로 기간 제한
    POST /api/events   — 생성. 생성 직후 알람을 계산해 함께 내린다
    """

    def get_serializer_class(self):
        return EventWriteSerializer if self.request.method == "POST" else EventSerializer

    def get_queryset(self):
        qs = super().get_queryset()
        raw_from = self.request.query_params.get("from")
        raw_to = self.request.query_params.get("to")
        if raw_from and (dt := parse_datetime(raw_from)):
            qs = qs.filter(start_at__gte=dt)
        if raw_to and (dt := parse_datetime(raw_to)):
            qs = qs.filter(start_at__lte=dt)
        return qs

    def create(self, request, *args, **kwargs):
        write = self.get_serializer(data=request.data)
        write.is_valid(raise_exception=True)
        event = write.save()

        # 생성 시점에 한 번만 계산한다. 목록 조회마다 부르면 카카오 무료
        # 쿼터(일 1,000건)가 금방 마른다.
        planning_services.compute_and_store(event)
        event.refresh_from_db()

        return Response(
            EventSerializer(event, context=self.get_serializer_context()).data,
            status=status.HTTP_201_CREATED,
        )


class EventDetailView(_UserScopedMixin, RetrieveUpdateDestroyAPIView):
    """GET / PATCH / DELETE /api/events/{id}"""

    def get_serializer_class(self):
        if self.request.method in ("PATCH", "PUT"):
            return EventWriteSerializer
        return EventSerializer

    def update(self, request, *args, **kwargs):
        instance = self.get_object()
        write = self.get_serializer(instance, data=request.data, partial=True)
        write.is_valid(raise_exception=True)
        event = write.save()

        # 시각·장소가 바뀌면 알람도 달라진다. 다시 계산한다.
        planning_services.compute_and_store(event)
        event.refresh_from_db()

        return Response(EventSerializer(event, context=self.get_serializer_context()).data)


class EventRecomputeView(_UserScopedMixin, APIView):
    """POST /api/events/{id}/recompute — 알람 계획만 다시 계산한다."""

    def post(self, request, pk: int):
        event = self.get_queryset().filter(pk=pk).first()
        if event is None:
            return Response(status=status.HTTP_404_NOT_FOUND)
        planning_services.compute_and_store(event)
        event.refresh_from_db()
        return Response(EventSerializer(event, context={"request": request}).data)


class EventCalendarImportView(_UserScopedMixin, APIView):
    """POST /api/events/import — 기기 캘린더 일정을 가져온다.

    ## 멱등하다

    `(user, external_id)` 로 upsert 한다. 같은 요청을 다시 보내면 행이 늘지
    않는다. 동기화는 반복 실행되는 것이 정상이므로 이게 없으면 목록이 사본으로
    찬다.

    ## 변경이 없으면 재계산하지 않는다

    이게 쿼터를 지키는 핵심이다. 앱이 열릴 때마다 동기화하면, 재계산을 무조건
    돌 경우 일정 20건짜리 계정이 **한 번 열 때마다 카카오 20콜**을 쓴다. 무료
    쿼터는 하루 1,000건이다. 제목·시각·장소가 그대로면 계획도 그대로다.

    ## 사용자가 앱에서 고친 것을 덮어쓰지 않는다

    `route_key`·`origin_*`·`tau_override` 는 건드리지 않는다. 캘린더에는 그런
    정보가 없으므로 매번 빈 값으로 밀면 사용자가 고른 경로와 출발지가 동기화
    한 번에 사라진다.

    ## 지운 일정이 되살아나는 문제

    앱에서 지운 일정이 캘린더에 남아 있으면 다시 가져올 수 있다. 삭제 이력을
    두지 않는 대신 **가져오기를 자동으로 돌리지 않는다** — 사용자가 목록에서
    고른 것만 보낸다. 그러면 되살리는 것도 사용자의 선택이다.
    """

    # 새 일정마다 카카오 경로를 부른다. 연속 호출로 쿼터를 태우지 못하게 막는다.
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "route"

    def post(self, request):
        serializer = CalendarImportSerializer(
            data=request.data, context={"request": request}
        )
        serializer.is_valid(raise_exception=True)
        rows = serializer.validated_data["events"]

        created: list[Event] = []
        updated: list[Event] = []
        unchanged: list[Event] = []

        with transaction.atomic():
            existing = {
                event.external_id: event
                for event in Event.objects.filter(
                    user=request.user,
                    external_id__in=[r["external_id"] for r in rows],
                ).select_related("place")
            }

            for row in rows:
                place = self._resolve_place(row.get("place"))
                tag = (
                    EventTag.objects.filter(key=row["tag_key"]).first()
                    if row.get("tag_key")
                    else None
                )
                event = existing.get(row["external_id"])

                if event is None:
                    event = Event.objects.create(
                        user=request.user,
                        source=Event.Source.CALENDAR,
                        external_id=row["external_id"],
                        title=row["title"],
                        start_at=row["start_at"],
                        place=place,
                        tag=tag,
                    )
                    created.append(event)
                    continue

                # 계획에 영향을 주는 값만 비교한다. 제목은 계획을 바꾸지 않지만
                # 화면에 보이므로 갱신은 한다 — 재계산 여부와는 따로 판단한다.
                #
                # **시각은 분 단위로 비교한다.** 계획은 분 단위로 계산되므로
                # 초 이하의 차이는 알람을 바꾸지 못한다. 초까지 비교하면 시각을
                # 미세하게 다르게 보내는 클라이언트가 동기화마다 전건 재계산을
                # 유발해 카카오 쿼터를 태운다.
                plan_changed = (
                    _minute(event.start_at) != _minute(row["start_at"])
                    or event.place_id != (place.pk if place else None)
                )
                display_changed = event.title != row["title"] or event.tag_id != (
                    tag.pk if tag else None
                )

                if not plan_changed and not display_changed:
                    unchanged.append(event)
                    continue

                event.title = row["title"]
                # 분이 같으면 저장된 시각을 그대로 둔다. 초만 다른 값을 계속
                # 덮어쓰면 위의 분 단위 비교가 무의미해진다(매번 새 초가 저장됨).
                if plan_changed:
                    event.start_at = row["start_at"]
                event.place = place
                event.tag = tag
                # source 를 calendar 로 승격한다. 직접 만든 일정에 같은
                # external_id 가 붙는 경로는 없지만, 있었다면 출처가 바뀐 것이
                # 사실이다.
                event.source = Event.Source.CALENDAR
                event.save()

                if plan_changed:
                    updated.append(event)
                else:
                    # 제목만 바뀌었다. 계획은 그대로이므로 카카오를 부르지 않는다.
                    unchanged.append(event)

            # 계획 계산은 트랜잭션 안에서 한다. 일정은 들어갔는데 계획이 없는
            # 중간 상태를 남기지 않는다.
            for event in created + updated:
                planning_services.compute_and_store(event)

        touched = created + updated + unchanged
        for event in touched:
            event.refresh_from_db()

        return Response(
            {
                "created": len(created),
                "updated": len(updated),
                "unchanged": len(unchanged),
                # 계산을 실제로 돌린 건수. 쿼터 소모량이 눈에 보여야 한다.
                "recomputed": len(created) + len(updated),
                "results": EventSerializer(
                    touched, many=True, context={"request": request}
                ).data,
            },
            status=status.HTTP_200_OK,
        )

    def _resolve_place(self, place_data):
        if not place_data:
            return None
        inner = PlaceInputSerializer(data=place_data)
        inner.is_valid(raise_exception=True)
        return inner.resolve()


class EventTagListView(APIView):
    """GET /api/events/tags — 태그 목록. 일정 추가 화면의 선택지다."""

    def get(self, request):
        tags = EventTag.objects.all()
        return Response(EventTagSerializer(tags, many=True).data)


# 서비스 범위 판정은 `geo.py` 로 옮겼다. 시리얼라이저도 같은 규칙을 써야 하는데
# 시리얼라이저가 뷰를 import 하면 순환이 되기 때문이다. 실제로 그 때문에
# 일정 생성에는 검사가 빠져 국외 출발지가 통과했다.
#
# 기존 import 경로를 쓰는 코드가 있을 수 있어 이름을 여기서도 노출한다.
from .geo import (  # noqa: E402,F401
    KOREA_LAT_RANGE,
    KOREA_LNG_RANGE,
    in_service_area,
)


def _minute(value):
    """초 이하를 버린다. 캘린더 가져오기의 변경 판정에 쓴다.

    알람 계획은 분 단위로 계산된다. 초까지 비교하면 시각을 미세하게 다르게
    보내는 클라이언트 때문에 동기화마다 전건이 "변경" 으로 잡혀 카카오 쿼터를
    태운다. 그 비용은 사용자에게 보이지 않으면서 하루 한도를 말린다.
    """
    return None if value is None else value.replace(second=0, microsecond=0)


def _error(code: str, message: str, http_status: int) -> Response:
    return Response(
        {"error": {"code": code, "message": message, "details": {}}},
        status=http_status,
    )


def _resolve_origin(request, profile) -> tuple[dict | None, Response | None]:
    """요청의 출발지를 정한다. `(origin, error_response)` 중 하나만 채워진다.

    `origin_lat`/`origin_lng` 가 오면 그것을 쓰고, 없으면 프로필의 집으로
    돌아간다. 둘 다 없으면 출발지를 모르므로 409 `no_home` 이다 — 집을
    설정하지 않은 사용자도 출발지를 직접 골라 경로를 볼 수 있다는 뜻이다.

    좌표 하나만 오는 요청(`origin_lat` 만)은 조용히 집으로 폴백하지 않고
    400 으로 막는다. 사용자가 출발지를 지정했다고 믿는데 다른 곳에서
    계산되는 상황을 만들지 않는다.
    """
    raw_lat = request.query_params.get("origin_lat")
    raw_lng = request.query_params.get("origin_lng")

    if raw_lat is None and raw_lng is None:
        if profile.home_lat is None or profile.home_lng is None:
            return None, _error(
                "no_home",
                "집 위치를 설정하거나 출발지를 직접 골라야 경로를 계산할 수 있다.",
                status.HTTP_409_CONFLICT,
            )
        return (
            {
                "label": profile.home_label or "집",
                "lat": profile.home_lat,
                "lng": profile.home_lng,
            },
            None,
        )

    if raw_lat is None or raw_lng is None:
        return None, _error(
            "invalid_origin",
            "origin_lat 과 origin_lng 는 함께 보내야 한다.",
            status.HTTP_400_BAD_REQUEST,
        )

    try:
        lat = float(raw_lat)
        lng = float(raw_lng)
    except (TypeError, ValueError):
        return None, _error(
            "invalid_origin", "출발지 좌표를 읽을 수 없다.", status.HTTP_400_BAD_REQUEST
        )

    if not in_service_area(lat, lng):
        return None, _error(
            "invalid_origin",
            "국내 좌표만 경로를 계산할 수 있다.",
            status.HTTP_400_BAD_REQUEST,
        )

    label = (request.query_params.get("origin_label") or "").strip()[:80]
    return {"label": label or "출발지", "lat": lat, "lng": lng}, None


class PlaceSearchView(APIView):
    """GET /api/places/search — 카카오 로컬 검색 프록시.

    클라이언트가 카카오를 직접 부르지 않는다. API 키를 앱에 넣으면 APK 를
    뜯어 꺼낼 수 있다. back-spec.md 5.3 의 프록시 규정이다.

    파라미터
      `q`         검색어 (필수)
      `lat`,`lng` 기준 좌표. **주면 결과에 거리가 붙는다.** 카카오는 기준
                  좌표를 함께 받았을 때만 `distance` 를 채운다
      `page`      1부터. 한 페이지 15건, 3페이지에서 끝난다(총 45건)
      `sort`      `accuracy`(기본) / `distance`. 거리순은 좌표가 있어야 한다
      `rect`      지도 영역 재검색. `minLng,minLat,maxLng,maxLat`
    """

    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "route"

    def get(self, request):
        query = (request.query_params.get("q") or "").strip()
        if not query:
            return Response(
                {
                    "results": [],
                    "page": 1,
                    "total_count": 0,
                    "reachable_count": 0,
                    "is_end": True,
                    "sort": clients.SORT_ACCURACY,
                    "degraded": False,
                }
            )

        lat, lng = _optional_coordinate(request, "lat", "lng")
        page = _positive_int(request.query_params.get("page"), default=1)
        sort = request.query_params.get("sort") or clients.SORT_ACCURACY
        rect = (request.query_params.get("rect") or "").strip() or None

        payload, degraded = clients.search_places(
            query,
            page=page,
            lat=lat,
            lng=lng,
            sort=sort,
            rect=rect,
        )
        return Response({**payload, "degraded": degraded})


class PlaceStaticMapView(APIView):
    """GET /api/places/staticmap — 정적 지도 이미지 프록시.

    **앱이 지도 SDK 없이 실제 지도를 그리는 방법이다.** 카카오지도 안드로이드
    SDK 를 쓰면 네이티브 앱 키를 APK 에 넣고 서명 키 해시를 등록해야 하는데,
    이 경로는 서버가 가진 REST 키로 같은 지도를 만들어 준다.

    파라미터
      `lat`,`lng` 지도 중심 (필수)
      `lv`        줌 레벨 1~15. 4가 요청 단위당 1m 이고 한 레벨마다 두 배
      `w`,`h`     이미지 크기. 지리적 범위는 이 값과 `lv` 로만 정해진다
      `markers`   `lat,lng` 를 세미콜론으로 이은 목록. 최대 5개
      `scale`     1 또는 2(기본). 해상도만 바뀌고 범위는 그대로다

    **이미지를 캐시한다.** 지도를 움직일 때마다 새로 부르면 하루 한도를
    금방 태운다. 같은 중심·줌·크기·마커 조합은 한 번만 카카오에 묻는다.
    """

    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "route"

    # 지도 타일은 자주 바뀌지 않는다. 한 시간이면 같은 화면을 여러 번 그려도
    # 카카오 호출은 한 번이다.
    CACHE_SECONDS = 60 * 60

    def get(self, request):
        lat, lng = _optional_coordinate(request, "lat", "lng")
        if lat is None or lng is None:
            return _error_response(
                "invalid_coordinate", "lat 과 lng 가 필요하다."
            )

        # **범위를 벗어난 값은 조용히 줄이지 않고 거절한다.**
        #
        # `clients.static_map` 은 범위로 잘라서 카카오에 보낸다. 그러면 lv=20 을
        # 요청한 앱은 lv=15 그림을 받아 놓고 자기는 20 기준으로 좌표를 계산한다 —
        # 경로선이 32배 어긋난 자리에 그려지고, 그림은 정상이라 원인을 찾기 어렵다.
        # 400 으로 돌려주면 앱이 잘못 물었다는 것을 바로 안다.
        #
        # 값이 아예 없거나 숫자가 아니면 기본값을 쓴다. "정하지 않았다" 와
        # "불가능한 값을 요구했다" 는 다르다.
        level = _bounded_int(
            request.query_params.get("lv"), 5,
            clients.STATIC_MAP_MIN_LEVEL, clients.STATIC_MAP_MAX_LEVEL,
        )
        width = _bounded_int(request.query_params.get("w"), 360, 1, clients.STATIC_MAP_MAX_W)
        height = _bounded_int(request.query_params.get("h"), 500, 1, clients.STATIC_MAP_MAX_H)
        if level is None or width is None or height is None:
            return _error_response(
                "invalid_viewport",
                f"lv 는 {clients.STATIC_MAP_MIN_LEVEL}~{clients.STATIC_MAP_MAX_LEVEL}, "
                f"w 는 1~{clients.STATIC_MAP_MAX_W}, h 는 1~{clients.STATIC_MAP_MAX_H} 여야 한다.",
            )

        scale = _positive_int(request.query_params.get("scale"), default=2)
        markers = _parse_markers(request.query_params.get("markers"))

        cache_key = "staticmap:" + hashlib.sha1(
            f"{lat:.6f},{lng:.6f},{level},{width},{height},{scale},"
            f"{';'.join(f'{a:.6f},{b:.6f}' for a, b in markers)}".encode()
        ).hexdigest()

        cached = cache.get(cache_key)
        if cached is not None:
            body, content_type = cached
            return _image_response(body, content_type, hit=True)

        body, content_type, degraded = clients.static_map(
            lat=lat, lng=lng, level=level,
            width=width, height=height,
            markers=markers, scale=scale,
        )
        if degraded or not body:
            # 지도를 못 그렸다고 화면을 막지 않는다. 앱은 목록으로 계속 쓴다.
            return _error_response(
                "map_unavailable",
                "지도를 불러오지 못했다. 목록으로 계속 고를 수 있다.",
                status.HTTP_503_SERVICE_UNAVAILABLE,
            )

        cache.set(cache_key, (body, content_type), self.CACHE_SECONDS)
        return _image_response(body, content_type, hit=False)


def _image_response(body: bytes, content_type: str, hit: bool) -> HttpResponse:
    response = HttpResponse(body, content_type=content_type)
    response["Cache-Control"] = f"private, max-age={PlaceStaticMapView.CACHE_SECONDS}"
    # 캐시가 듣고 있는지 확인할 창구. 쿼터를 태우는 원인을 찾을 때 필요하다.
    response["X-Jit-Map-Cache"] = "hit" if hit else "miss"
    return response


def _error_response(code: str, message: str, http_status: int = status.HTTP_400_BAD_REQUEST):
    return Response(
        {"error": {"code": code, "message": message, "details": {}}},
        status=http_status,
    )


def _optional_coordinate(request, lat_key: str, lng_key: str):
    """좌표 두 개를 읽는다. 하나라도 없거나 범위를 벗어나면 (None, None).

    한쪽만 살려 두지 않는다 — 위도만 있는 좌표로는 아무것도 할 수 없고,
    그대로 카카오에 보내면 엉뚱한 결과가 온다.
    """
    try:
        lat = float(request.query_params[lat_key])
        lng = float(request.query_params[lng_key])
    except (KeyError, TypeError, ValueError):
        return None, None
    if not (-90 <= lat <= 90 and -180 <= lng <= 180):
        return None, None
    return lat, lng


def _positive_int(raw, default: int) -> int:
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return default
    return value if value > 0 else default


def _bounded_int(raw, default: int, low: int, high: int) -> int | None:
    """범위 안의 정수. 값이 없거나 숫자가 아니면 [default], 범위를 벗어나면 ``None``.

    셋을 구분하는 것이 요점이다. 없으면 서버가 정하고, 숫자가 아니면 오타로 보고
    기본값으로 가고, **범위를 벗어나면 거절한다.** 잘라서 쓰면 요청한 값과 다른
    그림이 200 으로 돌아가고 클라이언트는 그것을 모른다.
    """
    if raw is None or str(raw).strip() == "":
        return default
    try:
        value = int(raw)
    except (TypeError, ValueError):
        return default
    return value if low <= value <= high else None


def _parse_markers(raw: str | None) -> list[tuple[float, float]]:
    """`lat,lng;lat,lng` 를 좌표 목록으로. 잘못된 항목은 버린다."""
    if not raw:
        return []
    out: list[tuple[float, float]] = []
    for chunk in raw.split(";"):
        parts = chunk.split(",")
        if len(parts) != 2:
            continue
        try:
            lat, lng = float(parts[0]), float(parts[1])
        except ValueError:
            continue
        if -90 <= lat <= 90 and -180 <= lng <= 180:
            out.append((lat, lng))
    return out[: clients.STATIC_MAP_MAX_MARKERS]


class PlaceReverseView(APIView):
    """GET /api/places/reverse?lat=&lng= — 좌표 → 주소 프록시.

    경로 선택 화면이 출발지 기본값으로 "현재 위치" 를 넣을 때 쓴다. GPS 는
    좌표만 주므로 사람이 읽는 주소로 바꿔야 한다. [PlaceSearchView] 와 같은
    이유로 서버가 대신 부른다 — 앱에 카카오 키를 넣지 않는다.

    응답은 `{"result": {...}|null, "degraded": bool}`. `result` 가 null 인
    경우는 두 가지다: 카카오 호출 실패(`degraded=true`) 또는 주소가 없는
    좌표(바다·국외, `degraded=false`). 화면이 둘을 구분해야 하므로 분리한다.
    """

    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "route"

    def get(self, request):
        try:
            lat = float(request.query_params["lat"])
            lng = float(request.query_params["lng"])
        except (KeyError, TypeError, ValueError):
            return Response(
                {
                    "error": {
                        "code": "invalid_coordinate",
                        "message": "lat 과 lng 가 필요하다.",
                        "details": {},
                    }
                },
                status=status.HTTP_400_BAD_REQUEST,
            )

        if not (-90 <= lat <= 90 and -180 <= lng <= 180):
            return Response(
                {
                    "error": {
                        "code": "invalid_coordinate",
                        "message": "좌표 범위를 벗어났다.",
                        "details": {},
                    }
                },
                status=status.HTTP_400_BAD_REQUEST,
            )

        result, degraded = clients.coord_to_address(lat, lng)
        return Response({"result": result, "degraded": degraded})


class RouteCandidateView(APIView):
    """GET /api/routes/candidates?dest_lat=&dest_lng=[&origin_lat=&origin_lng=&origin_label=]

    출발지는 **기본이 프로필의 집 위치**이고, 사용자가 다른 곳에서 출발할 때만
    `origin_*` 으로 덮어쓴다. 집에서만 출발한다는 가정이 틀렸기 때문이다 —
    학교에서 바로 다음 수업으로 가거나 외출 중에 일정을 넣는 경우가 있다.

    **임의 좌표를 받는 위험을 어떻게 막는가.** 원래 이 엔드포인트는 출발지를
    아예 받지 않았다(back-spec.md 5.3.1). 아무 두 지점의 카카오 경로를 뽑아
    주는 무료 프록시가 되는 것을 막으려던 것이다. 출발지를 열면서 그 자리를
    세 가지로 대체한다:

    1. 인증 필수 — 익명 호출이 불가능하다.
    2. `route` 스로틀 — 한 번에 외부 API 를 최대 4번 부르므로 호출량을 묶는다.
    3. **국내 좌표만 허용** — 카카오 경로 API 자체가 국내만 다루므로 정상
       사용을 제한하지 않으면서, 전 세계 경로 프록시로 쓰이는 길을 막는다.

    카카오는 대중교통 후보를 항상 15개 주는데 대부분 같은 버스의 다른 환승
    조합이다. `clients.route_candidates()` 가 축별 대표만 추려 6개 이하로
    내린다(자세한 기준은 그 함수의 주석).

    **호출 비용** — 한 번에 외부 API 를 최대 4번 부른다. 사용자가 일정 추가
    화면에서 명시적으로 요청할 때만 부르고, 알람 재계산에서는 선택된 수단
    하나만 조회한다.

    고른 출발지는 화면에서 끝나지 않고 `Event.origin_*` 에 저장된다. 저장하지
    않으면 알람 재계산이 집 좌표로 `route_key` 를 다시 풀어 **다른 경로의
    소요시간으로 알람을 잡는다**(planning/services.py 3번 주석).
    """

    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "route"

    def get(self, request):
        profile = request.user.profile

        origin, error = _resolve_origin(request, profile)
        if error is not None:
            return error

        try:
            dest_lat = float(request.query_params["dest_lat"])
            dest_lng = float(request.query_params["dest_lng"])
        except (KeyError, TypeError, ValueError):
            return Response(
                {
                    "error": {
                        "code": "invalid_destination",
                        "message": "dest_lat 과 dest_lng 가 필요하다.",
                        "details": {},
                    }
                },
                status=status.HTTP_400_BAD_REQUEST,
            )

        if not (-90 <= dest_lat <= 90 and -180 <= dest_lng <= 180):
            return Response(
                {
                    "error": {
                        "code": "invalid_destination",
                        "message": "좌표 범위를 벗어났다.",
                        "details": {},
                    }
                },
                status=status.HTTP_400_BAD_REQUEST,
            )

        candidates, degraded = clients.route_candidates(
            start_lat=origin["lat"],
            start_lng=origin["lng"],
            end_lat=dest_lat,
            end_lng=dest_lng,
        )
        return Response(
            {
                "origin": origin,
                "results": candidates,
                "degraded": degraded,
            }
        )
