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


class RouteCandidateView(APIView):
    """GET /api/routes/candidates?dest_lat=&dest_lng= — 경로 후보 목록.

    출발지는 **프로필의 집 위치**다. 요청으로 받지 않는다. 임의 좌표를 받으면
    이 엔드포인트가 아무 두 지점의 경로를 뽑아 주는 무료 프록시가 된다.

    카카오는 대중교통 후보를 항상 15개 주는데 대부분 같은 버스의 다른 환승
    조합이다. `clients.route_candidates()` 가 축별 대표만 추려 6개 이하로
    내린다(자세한 기준은 그 함수의 주석).

    **호출 비용** — 한 번에 외부 API 를 최대 4번 부른다. 사용자가 일정 추가
    화면에서 명시적으로 요청할 때만 부르고, 알람 재계산에서는 선택된 수단
    하나만 조회한다. `route` 스로틀을 걸어 둔다.
    """

    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "route"

    def get(self, request):
        profile = request.user.profile
        if profile.home_lat is None or profile.home_lng is None:
            return Response(
                {
                    "error": {
                        "code": "no_home",
                        "message": "집 위치를 먼저 설정해야 경로를 계산할 수 있다.",
                        "details": {},
                    }
                },
                status=status.HTTP_409_CONFLICT,
            )

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
            start_lat=profile.home_lat,
            start_lng=profile.home_lng,
            end_lat=dest_lat,
            end_lng=dest_lng,
        )
        return Response(
            {
                "origin": {
                    "label": profile.home_label or "집",
                    "lat": profile.home_lat,
                    "lng": profile.home_lng,
                },
                "results": candidates,
                "degraded": degraded,
            }
        )
