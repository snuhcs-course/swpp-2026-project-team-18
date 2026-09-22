"""일정·장소 라우팅. config/urls.py 에서 `api/` 아래로 include 된다."""

from django.urls import path

from .views import (
    EventCalendarImportView,
    EventDetailView,
    EventListCreateView,
    EventRecomputeView,
    EventTagListView,
    PlaceReverseView,
    PlaceSearchView,
    RouteCandidateView,
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
    path("places/search", PlaceSearchView.as_view(), name="place_search"),
    path("places/reverse", PlaceReverseView.as_view(), name="place_reverse"),
    path("routes/candidates", RouteCandidateView.as_view(), name="route_candidates"),
]
