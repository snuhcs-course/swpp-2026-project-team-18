"""이동 관측 라우팅. config/urls.py 에서 `api/` 아래로 include 된다."""

from django.urls import path

from .views import AlarmLogBatchView, ObservationBatchView, ObservationListView

app_name = "observations"

urlpatterns = [
    # 고정 경로를 목록보다 먼저 둔다. events 앱과 같은 이유다.
    path("observations/batch", ObservationBatchView.as_view(), name="batch"),
    path("observations", ObservationListView.as_view(), name="list"),
    # Partly AI-generated (Claude) — 알람 울림·끄기·미루기(db-schema.md A3). 쓰기만 있다.
    path("alarm-logs/batch", AlarmLogBatchView.as_view(), name="alarm_log_batch"),
]
