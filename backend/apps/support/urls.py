# Partly AI-generated (Claude)
"""문의 경로. `config/urls.py` 의 `api/support/` 아래에 붙는다. 끝의 `/` 는 있어도 없어도 받는다."""

from django.urls import re_path

from .views import InquiryCreateView

app_name = "support"

urlpatterns = [
    re_path(r"^inquiries/?$", InquiryCreateView.as_view(), name="inquiry_create"),
]
