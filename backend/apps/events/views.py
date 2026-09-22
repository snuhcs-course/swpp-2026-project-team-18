"""일정 API. back-spec.md 5.3.

**모든 조회는 `request.user` 로 좁힌다.** 목록만 필터하고 상세를 빼먹으면
남의 일정을 id 로 읽을 수 있다. `get_queryset()` 한 곳에서 걸러 상속받게 한다.
"""

from __future__ import annotations

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
    EventSerializer,
    EventTagSerializer,
    EventWriteSerializer,
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
    """GET /api/places/search?q= — 카카오 로컬 검색 프록시.

    클라이언트가 카카오를 직접 부르지 않는다. API 키를 앱에 넣으면 APK 를
    뜯어 꺼낼 수 있다. back-spec.md 5.3 의 프록시 규정이다.
    """

    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "route"

    def get(self, request):
        query = (request.query_params.get("q") or "").strip()
        if not query:
            return Response({"results": []})

        places, degraded = clients.search_places(query)
        return Response({"results": places, "degraded": degraded})


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
