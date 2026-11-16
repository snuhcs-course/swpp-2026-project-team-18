# Partly AI-generated (Claude)
"""알람 직접 정하기 · 확인 · 제안 답하기(iter4 H2-3). config/urls.py 에서 `api/` 아래로 include 된다.

경로는 일정 아래(`events/{id}/alarm/...`)지만 상태의 주인이 planning(`AlarmPlan`)이라 여기 둔다.
"""

from django.urls import path

from .views import AlarmConfirmView, AlarmProposalView, AlarmTimeView

app_name = "planning"

urlpatterns = [
    path("events/<int:pk>/alarm/time", AlarmTimeView.as_view(), name="alarm_time"),
    path("events/<int:pk>/alarm/confirm", AlarmConfirmView.as_view(), name="alarm_confirm"),
    path(
        "events/<int:pk>/alarm/proposal/accept",
        AlarmProposalView.as_view(answer="accept"),
        name="alarm_proposal_accept",
    ),
    path(
        "events/<int:pk>/alarm/proposal/reject",
        AlarmProposalView.as_view(answer="reject"),
        name="alarm_proposal_reject",
    ),
]
