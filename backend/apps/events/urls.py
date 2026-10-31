"""일정·장소 라우팅. config/urls.py 에서 `api/` 아래로 include 된다."""

from django.urls import path

from .views import (
    EventCalendarImportView,
    EventDetailView,
    EventListCreateView,
    EventRecomputeView,
    EventRerouteView,
    EventRiskOptionsView,
    EventSkipView,
    EventTagListView,
    PlaceReverseView,
    PlaceSearchView,
    PlaceStaticMapView,
    RouteCandidateView,
    RouteLiveView,
)

app_name = "events"

urlpatterns = [
    # 고정 경로를 <int:pk> 보다 먼저 둔다. 순서가 뒤바뀌면 "tags" 를
    # pk 로 해석하려 해서 404 가 난다.
    path("events/tags", EventTagListView.as_view(), name="tag_list"),
    path("events/import", EventCalendarImportView.as_view(), name="calendar_import"),
    path("events", EventListCreateView.as_view(), name="list_create"),
    path("events/<int:pk>", EventDetailView.as_view(), name="detail"),
    path("events/<int:pk>/recompute", EventRecomputeView.as_view(), name="recompute"),
    path("events/<int:pk>/risk-options", EventRiskOptionsView.as_view(), name="risk_options"),
    # "안 감" 표시(POST)와 되돌리기(DELETE).
    path("events/<int:pk>/skip", EventSkipView.as_view(), name="skip"),
    # 이동 중 [이 경로로 바꾸기]. 이번 이동만 바꾼다.
    path("events/<int:pk>/reroute", EventRerouteView.as_view(), name="reroute"),
    path("places/search", PlaceSearchView.as_view(), name="place_search"),
    # 정적 지도 이미지. 앱이 지도 SDK 없이 실제 지도를 그리는 경로다.
    path("places/staticmap", PlaceStaticMapView.as_view(), name="place_staticmap"),
    path("places/reverse", PlaceReverseView.as_view(), name="place_reverse"),
    path("routes/candidates", RouteCandidateView.as_view(), name="route_candidates"),
    # 이동 중 1분마다. 현재 위치부터 목적지까지 다시 푼 최단 경로를 받는다.
    path("routes/live", RouteLiveView.as_view(), name="route_live"),
]
