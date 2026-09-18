"""이동 관측 라우팅. config/urls.py 에서 `api/` 아래로 include 된다."""

from django.urls import path

from .views import ObservationBatchView, ObservationListView

app_name = "observations"

urlpatterns = [
    # 고정 경로를 목록보다 먼저 둔다. events 앱과 같은 이유다.
    path("observations/batch", ObservationBatchView.as_view(), name="batch"),
    path("observations", ObservationListView.as_view(), name="list"),
]
