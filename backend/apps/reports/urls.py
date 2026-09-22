"""리포트 경로. `config/urls.py` 의 `api/` 아래에 붙는다."""

from django.urls import path

from . import views

urlpatterns = [
    path("reports/weekly", views.WeeklyReportView.as_view(), name="report-weekly"),
    path(
        "reports/calibration",
        views.CalibrationView.as_view(),
        name="report-calibration",
    ),
]
