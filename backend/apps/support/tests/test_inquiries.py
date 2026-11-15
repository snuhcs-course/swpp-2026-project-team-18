# Partly AI-generated (Claude)
"""앱 안 문의하기(iter4 BE-H4b). 계약은 `docs/iter4/server-inquiry.md`.

본문 · 상태 코드는 앱의 `SupportApi.kt` · `Inquiry.kt` 와 맞춘다: 201 `{id}`, 400 칸별 `details`,
401, 429. 메일은 locmem 으로 받는다(`mailoutbox`).
"""

from __future__ import annotations

import importlib

import pytest
from django.core.cache import cache
from django.urls import reverse
from rest_framework.test import APIClient
from rest_framework.throttling import ScopedRateThrottle

from apps.support import notify
from apps.support.models import Inquiry
from apps.support.serializers import (
    APP_VERSION_TOO_LONG,
    DEVICE_TOO_LONG,
    EMAIL_INVALID,
    EMAIL_REQUIRED,
    MESSAGE_REQUIRED,
    MESSAGE_TOO_LONG,
    MESSAGE_TOO_SHORT,
    InquirySerializer,
)

pytestmark = pytest.mark.django_db

PATH = "/api/support/inquiries"
BODY = {
    "message": "알람이 예상보다 30분 일찍 울렸어요",
    "reply_email": "me@example.com",
    "app_version": "0.21.0",
    "device": "samsung SM-S911N · Android 14",
}


@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(
        email="asker@example.com", password="Pw3-cobalt-harbor-28", nickname="문의자"
    )


@pytest.fixture
def client(user):
    c = APIClient()
    c.force_authenticate(user)
    return c


class TestSerializer:
    def _errors(self, **override):
        s = InquirySerializer(data={**BODY, **override})
        assert not s.is_valid()
        return {k: [str(m) for m in v] for k, v in s.errors.items()}

    def test_counts_after_trimming(self):
        # 앞뒤 공백을 빼면 8자다.
        assert self._errors(message="   아홉글자입니다요   ") == {"message": [MESSAGE_TOO_SHORT]}
        s = InquirySerializer(data={**BODY, "message": "  열 글자는 받아요요  "})
        assert s.is_valid(), s.errors
        assert s.validated_data["message"] == "열 글자는 받아요요"

    def test_counts_code_points_like_the_app(self):
        # 이모지 하나는 UTF-16 으로 2칸이지만 코드 포인트는 1개다(앱 `codePointCount`).
        assert InquirySerializer(data={**BODY, "message": "😀" * 1000}).is_valid()
        assert self._errors(message="😀" * 1001) == {"message": [MESSAGE_TOO_LONG]}

    def test_required_and_blank(self):
        s = InquirySerializer(data={})
        assert not s.is_valid()
        assert [str(m) for m in s.errors["message"]] == [MESSAGE_REQUIRED]
        assert [str(m) for m in s.errors["reply_email"]] == [EMAIL_REQUIRED]
        assert self._errors(message="     ") == {"message": [MESSAGE_REQUIRED]}

    def test_email_and_lengths(self):
        assert self._errors(reply_email="not-an-email") == {"reply_email": [EMAIL_INVALID]}
        assert self._errors(app_version="1" * 33) == {"app_version": [APP_VERSION_TOO_LONG]}
        assert self._errors(device="d" * 121) == {"device": [DEVICE_TOO_LONG]}

    def test_optional_fields_may_be_missing_or_null(self):
        s = InquirySerializer(data={"message": BODY["message"], "reply_email": " me@example.com "})
        assert s.is_valid(), s.errors
        assert s.validated_data["reply_email"] == "me@example.com"
        s = InquirySerializer(data={**BODY, "app_version": None, "device": None})
        assert s.is_valid(), s.errors
        assert (s.validated_data["app_version"], s.validated_data["device"]) == ("", "")


class TestNotify:
    def _inquiry(self, user, **kw):
        return Inquiry.objects.create(user=user, **{**BODY, **kw})

    def test_skips_without_inbox(self, user, settings, mailoutbox):
        settings.SUPPORT_INBOX_EMAILS = []
        assert notify.notify_team(self._inquiry(user)) == notify.SKIPPED
        assert mailoutbox == []

    def test_sends_to_every_inbox_with_reply_to(self, user, settings, mailoutbox):
        settings.SUPPORT_INBOX_EMAILS = ["team-a@example.com", "team-b@example.com"]
        inquiry = self._inquiry(user, message="첫 줄\n둘째 줄에 자세히 적었어요")
        assert notify.notify_team(inquiry) == notify.SENT
        [mail] = mailoutbox
        assert mail.to == ["team-a@example.com", "team-b@example.com"]
        assert mail.reply_to == ["me@example.com"]
        # 제목에는 줄바꿈이 들어가지 않는다(헤더 주입 · BadHeaderError).
        assert "\n" not in mail.subject
        assert mail.subject.startswith(f"[JustInTime 문의 #{inquiry.pk}]")
        assert "둘째 줄에 자세히 적었어요" in mail.body
        assert "asker@example.com" in mail.body
        assert "0.21.0" in mail.body

    def test_failure_does_not_raise(self, user, settings, monkeypatch):
        settings.SUPPORT_INBOX_EMAILS = ["team@example.com"]

        def broken(self, fail_silently=False):
            raise OSError("SMTP 막힘")

        monkeypatch.setattr("django.core.mail.EmailMessage.send", broken)
        assert notify.notify_team(self._inquiry(user)) == notify.FAILED


@pytest.mark.integration
class TestInquiryEndpoint:
    def test_created_returns_id_and_saves(self, client, user, settings, mailoutbox):
        settings.SUPPORT_INBOX_EMAILS = ["team@example.com"]
        res = client.post(PATH, BODY, format="json")
        assert res.status_code == 201
        inquiry = Inquiry.objects.get()
        assert res.json() == {"id": inquiry.pk}
        assert inquiry.user == user
        assert inquiry.status == Inquiry.Status.OPEN
        assert inquiry.device == BODY["device"]
        assert len(mailoutbox) == 1

    def test_trailing_slash_is_accepted(self, client):
        assert client.post(PATH + "/", BODY, format="json").status_code == 201

    def test_saved_without_inbox(self, client, settings, mailoutbox):
        settings.SUPPORT_INBOX_EMAILS = []
        assert client.post(PATH, BODY, format="json").status_code == 201
        assert Inquiry.objects.count() == 1
        assert mailoutbox == []

    def test_mail_failure_still_201(self, client, settings, monkeypatch):
        settings.SUPPORT_INBOX_EMAILS = ["team@example.com"]

        def broken(self, fail_silently=False):
            raise OSError("SMTP 막힘")

        monkeypatch.setattr("django.core.mail.EmailMessage.send", broken)
        res = client.post(PATH, BODY, format="json")
        assert res.status_code == 201
        assert Inquiry.objects.filter(pk=res.json()["id"]).exists()

    def test_validation_400_has_per_field_details(self, client):
        res = client.post(PATH, {"message": "짧아요", "reply_email": "nope"}, format="json")
        assert res.status_code == 400
        error = res.json()["error"]
        assert error["code"] == "invalid"
        assert error["details"]["message"] == [MESSAGE_TOO_SHORT]
        assert error["details"]["reply_email"] == [EMAIL_INVALID]
        assert error["message"] in (MESSAGE_TOO_SHORT, EMAIL_INVALID)
        assert Inquiry.objects.count() == 0

    def test_requires_login(self):
        assert APIClient().post(PATH, BODY, format="json").status_code == 401

    def test_user_in_body_is_ignored(self, client, user, django_user_model):
        other = django_user_model.objects.create_user(
            email="other@example.com", password="Pw3-cobalt-harbor-28", nickname="남"
        )
        res = client.post(PATH, {**BODY, "user": other.pk}, format="json")
        assert res.status_code == 201
        assert Inquiry.objects.get().user == user

    def test_throttle_five_per_hour(self, client, monkeypatch):
        base = importlib.import_module("config.settings.base")
        monkeypatch.setattr(
            ScopedRateThrottle, "THROTTLE_RATES", base.REST_FRAMEWORK["DEFAULT_THROTTLE_RATES"]
        )
        cache.clear()
        try:
            statuses = [client.post(PATH, BODY, format="json").status_code for _ in range(6)]
        finally:
            cache.clear()
        assert statuses == [201] * 5 + [429]
        assert Inquiry.objects.count() == 5


@pytest.mark.integration
class TestInquiryAdmin:
    @pytest.fixture
    def staff_client(self, django_user_model, client):
        staff = django_user_model.objects.create_superuser(
            email="staff@example.com", password="Ke3-lantern-thicket-90", nickname="관리자"
        )
        c = APIClient()
        c.force_login(staff)
        return c

    def test_change_sets_and_clears_handled_at(self, staff_client, user):
        inquiry = Inquiry.objects.create(user=user, **BODY)
        url = reverse("admin:support_inquiry_change", args=[inquiry.pk])
        assert staff_client.get(url).status_code == 200

        res = staff_client.post(url, {"status": "answered", "note": "메일로 답함"})
        assert res.status_code == 302
        inquiry.refresh_from_db()
        assert inquiry.status == "answered"
        assert inquiry.handled_at is not None
        # 사용자가 보낸 글은 바꿀 수 없다(읽기 전용).
        assert inquiry.message == BODY["message"]

        staff_client.post(url, {"status": "open", "note": ""})
        inquiry.refresh_from_db()
        assert inquiry.handled_at is None

    def test_bulk_actions(self, staff_client, user):
        a = Inquiry.objects.create(user=user, **BODY)
        b = Inquiry.objects.create(user=user, **BODY)
        url = reverse("admin:support_inquiry_changelist")
        res = staff_client.post(url, {"action": "mark_closed", "_selected_action": [a.pk, b.pk]})
        assert res.status_code == 302
        assert set(Inquiry.objects.values_list("status", flat=True)) == {"closed"}
        assert not Inquiry.objects.filter(handled_at__isnull=True).exists()
        assert staff_client.get(url + "?status__exact=closed&q=example").status_code == 200
