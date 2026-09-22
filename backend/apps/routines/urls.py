"""루틴 블록 경로.

`config/urls.py` 의 `api/` 아래에 붙는다. 일정별 블록 체크는 경로가
`events/{id}/blocks` 라 events 쪽이 아니라 여기 둔다 — 블록이라는 개념의
소유자가 이 앱이고, events 가 routines 를 import 하면 의존이 반대가 된다.
"""

from django.urls import path

from . import views

urlpatterns = [
    path(
        "routines/blocks",
        views.RoutineBlockListCreateView.as_view(),
        name="routine-block-list",
    ),
    path(
        "routines/blocks/<int:pk>",
        views.RoutineBlockDetailView.as_view(),
        name="routine-block-detail",
    ),
    path(
        "routines/observations/batch",
        views.BlockObservationBatchView.as_view(),
        name="block-observation-batch",
    ),
    path(
        "events/<int:event_id>/blocks",
        views.EventBlockSelectionView.as_view(),
        name="event-block-selection",
    ),
]
