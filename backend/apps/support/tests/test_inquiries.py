# Partly AI-generated (Claude)
"""앱 안 문의하기(iter4 BE-H4b). 계약은 `docs/iter4/server-inquiry.md`.

본문 · 상태 코드는 앱의 `SupportApi.kt` · `Inquiry.kt` 와 맞춘다: 201 `{id}`, 400 칸별 `details`,
401, 429. 메일은 locmem 으로 받는다(`mailoutbox`). 뷰는 커밋 뒤(`on_commit`)에 보내므로 HTTP
테스트는 `committed()`(`django_capture_on_commit_callbacks(execute=True)`) 안에서 보낸다. 테스트
설정은 `SUPPORT_NOTIFY_IN_BACKGROUND = False` 라 스레드 없이 바로 보낸다.
"""

from __future__ import annotations

import importlib
import logging
import threading

import pytest
from django.contrib.auth.models import Permission
from django.core.cache import cache
from django.urls import reverse
from rest_framework.test import APIClient
from rest_framework.throttling import ScopedRateThrottle

from apps.support import notify
from apps.support.models import Inquiry
from apps.support.serializers import (
    APP_VERSION_MAX,
    DEVICE_MAX,
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


@pytest.fixture
def committed(django_capture_on_commit_callbacks):
    """요청이 끝나고 커밋된 것처럼 `on_commit` 콜백을 돌린다."""
    return lambda: django_capture_on_commit_callbacks(execute=True)


def _real_throttle_rates(monkeypatch):
    base = importlib.import_module("config.settings.base")
    monkeypatch.setattr(
        ScopedRateThrottle, "THROTTLE_RATES", base.REST_FRAMEWORK["DEFAULT_THROTTLE_RATES"]
    )


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

    def test_email_format_and_254_limit(self):
        assert self._errors(reply_email="not-an-email") == {"reply_email": [EMAIL_INVALID]}
        # 254자(RFC)까지. 넘으면 400 이다 — 앱도 254자로 막는다(문서 "앱이 할 일").
        local = "a" * 64
        domain = ".".join(["b" * 63, "c" * 63, "d" * 57, "com"])
        assert len(f"{local}@{domain}") == 254
        assert InquirySerializer(data={**BODY, "reply_email": f"{local}@{domain}"}).is_valid()
        assert self._errors(reply_email=f"a{local}@{domain}") == {"reply_email": [EMAIL_INVALID]}

    @pytest.mark.parametrize(
        "address",
        [
            # 따옴표 로컬 부분은 Django 형식 검사를 지나지만 `Reply-To` 헤더를 깨뜨린다.
            '"a\x01b"@example.com',
            '"a\\\tb"@example.com',
            '"x\\"y"@example.com',
            '"quoted"@example.com',
            # 보이지 않는 문자 · 제어 문자.
            "a​b@example.com",
            "me@exam\x7fple.com",
        ],
    )
    def test_rejects_control_chars_and_quoted_local_part(self, address):
        assert self._errors(reply_email=address) == {"reply_email": [EMAIL_INVALID]}

    def test_accepts_common_dot_atom_addresses(self):
        for address in ("first.last+tag@example.com", "o'brien@sub.example.co.kr", "x_y-z@snu.ac.kr"):
            assert InquirySerializer(data={**BODY, "reply_email": address}).is_valid(), address

    def test_long_metadata_is_truncated_not_rejected(self):
        # 앱이 스스로 채우는 값이라 사용자가 고칠 수 없다. 거절하지 않고 자른다.
        s = InquirySerializer(data={**BODY, "app_version": "1" * 40, "device": "d" * 200})
        assert s.is_valid(), s.errors
        assert s.validated_data["app_version"] == "1" * APP_VERSION_MAX
        assert s.validated_data["device"] == "d" * DEVICE_MAX
        # 자른 끝이 공백이면 뺀다.
        s = InquirySerializer(data={**BODY, "device": "d" * (DEVICE_MAX - 1) + " tail"})
        assert s.is_valid(), s.errors
        assert s.validated_data["device"] == "d" * (DEVICE_MAX - 1)

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

    def test_failure_does_not_raise_and_logs_only_id_and_type(
        self, user, settings, monkeypatch, caplog
    ):
        settings.SUPPORT_INBOX_EMAILS = ["team@example.com"]

        def broken(self, fail_silently=False):
            raise OSError("SMTP 막힘")

        monkeypatch.setattr("django.core.mail.EmailMessage.send", broken)
        caplog.set_level(logging.INFO, logger="apps.support.notify")
        inquiry = self._inquiry(user)
        assert notify.notify_team(inquiry) == notify.FAILED
        assert [r.getMessage() for r in caplog.records] == [f"문의 #{inquiry.pk} 알림 메일 실패 OSError"]

    @pytest.mark.parametrize(
        "backend",
        [
            "django.core.mail.backends.console.EmailBackend",
            "django.core.mail.backends.dummy.EmailBackend",
        ],
    )
    def test_undelivered_backend_skips_and_logs_only_the_id(
        self, user, settings, mailoutbox, capsys, caplog, backend
    ):
        # 콘솔 백엔드는 메일 전체(글 · 이메일 · 닉네임)를 stdout(운영 로그)에 찍고 배달하지 않는다.
        settings.SUPPORT_INBOX_EMAILS = ["team@example.com"]
        settings.EMAIL_BACKEND = backend
        caplog.set_level(logging.INFO, logger="apps.support.notify")
        inquiry = self._inquiry(user)
        assert notify.notify_team(inquiry) == notify.SKIPPED
        assert capsys.readouterr().out == ""
        [record] = caplog.records
        text = record.getMessage()
        assert f"#{inquiry.pk}" in text
        for private in (BODY["message"], BODY["reply_email"], user.email, user.nickname):
            assert private not in text
        assert "보냈다" not in text

    def test_schedule_waits_for_commit(
        self, user, settings, mailoutbox, django_capture_on_commit_callbacks
    ):
        settings.SUPPORT_INBOX_EMAILS = ["team@example.com"]
        inquiry = self._inquiry(user)
        with django_capture_on_commit_callbacks(execute=False) as callbacks:
            notify.schedule(inquiry)
        assert len(callbacks) == 1
        assert mailoutbox == []
        callbacks[0]()
        assert len(mailoutbox) == 1

    def test_schedule_skips_without_inbox(self, user, settings, django_capture_on_commit_callbacks):
        settings.SUPPORT_INBOX_EMAILS = []
        with django_capture_on_commit_callbacks(execute=False) as callbacks:
            notify.schedule(self._inquiry(user))
        assert callbacks == []


@pytest.mark.integration
class TestInquiryEndpoint:
    def test_created_returns_id_and_saves(self, client, user, settings, mailoutbox, committed):
        settings.SUPPORT_INBOX_EMAILS = ["team@example.com"]
        with committed():
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

    def test_saved_without_inbox(self, client, settings, mailoutbox, committed):
        settings.SUPPORT_INBOX_EMAILS = []
        with committed():
            assert client.post(PATH, BODY, format="json").status_code == 201
        assert Inquiry.objects.count() == 1
        assert mailoutbox == []

    def test_mail_failure_still_201(self, client, settings, monkeypatch, committed):
        settings.SUPPORT_INBOX_EMAILS = ["team@example.com"]

        def broken(self, fail_silently=False):
            raise OSError("SMTP 막힘")

        monkeypatch.setattr("django.core.mail.EmailMessage.send", broken)
        with committed():
            res = client.post(PATH, BODY, format="json")
        assert res.status_code == 201
        assert Inquiry.objects.filter(pk=res.json()["id"]).exists()

    def test_response_does_not_wait_for_slow_mail(self, client, settings, monkeypatch, committed):
        # 운영 설정처럼 백그라운드 스레드로 보낸다. SMTP 가 멈춰 있어도 응답이 먼저 나온다.
        settings.SUPPORT_INBOX_EMAILS = ["team@example.com"]
        settings.SUPPORT_NOTIFY_IN_BACKGROUND = True
        release, started, sent = threading.Event(), threading.Event(), []

        def slow(self, fail_silently=False):
            started.set()
            if release.wait(5):
                sent.append(self.reply_to)
            return 1

        monkeypatch.setattr("django.core.mail.EmailMessage.send", slow)
        try:
            with committed():
                res = client.post(PATH, BODY, format="json")
            assert res.status_code == 201
            assert started.wait(5)
            assert sent == []  # 응답이 나왔을 때 메일은 아직 보내는 중이다
            name = f"support-notify-{res.json()['id']}"
            [worker] = [t for t in threading.enumerate() if t.name == name]
            assert worker.daemon
        finally:
            release.set()
        worker.join(5)
        assert sent == [[BODY["reply_email"]]]

    def test_long_app_metadata_is_saved_truncated(self, client):
        res = client.post(
            PATH, {**BODY, "app_version": "0.21.0-" + "x" * 40, "device": "d" * 300}, format="json"
        )
        assert res.status_code == 201
        inquiry = Inquiry.objects.get()
        assert inquiry.app_version == ("0.21.0-" + "x" * 40)[:APP_VERSION_MAX]
        assert inquiry.device == "d" * DEVICE_MAX

    def test_quoted_reply_email_is_400(self, client):
        res = client.post(PATH, {**BODY, "reply_email": '"a\x01b"@example.com'}, format="json")
        assert res.status_code == 400
        assert res.json()["error"]["details"]["reply_email"] == [EMAIL_INVALID]
        assert Inquiry.objects.count() == 0

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
        _real_throttle_rates(monkeypatch)
        cache.clear()
        try:
            statuses = [client.post(PATH, BODY, format="json").status_code for _ in range(6)]
        finally:
            cache.clear()
        assert statuses == [201] * 5 + [429]
        assert Inquiry.objects.count() == 5

    def test_throttle_is_per_user(self, client, django_user_model, monkeypatch):
        _real_throttle_rates(monkeypatch)
        other = django_user_model.objects.create_user(
            email="second@example.com", password="Pw3-cobalt-harbor-28", nickname="둘째"
        )
        other_client = APIClient()
        other_client.force_authenticate(other)
        cache.clear()
        try:
            first = [client.post(PATH, BODY, format="json").status_code for _ in range(6)]
            second = [other_client.post(PATH, BODY, format="json").status_code for _ in range(6)]
        finally:
            cache.clear()
        # 한 사람이 다 써도 다른 사람은 따로 5건을 보낸다.
        assert first == [201] * 5 + [429]
        assert second == [201] * 5 + [429]
        assert Inquiry.objects.filter(user=other).count() == 5


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

    def test_view_only_staff_cannot_run_bulk_actions(self, django_user_model, user):
        viewer = django_user_model.objects.create_user(
            email="viewer@example.com",
            password="Ke3-lantern-thicket-90",
            nickname="보기만",
            is_staff=True,
        )
        viewer.user_permissions.add(Permission.objects.get(codename="view_inquiry"))
        c = APIClient()
        c.force_login(viewer)
        inquiry = Inquiry.objects.create(user=user, **BODY)
        url = reverse("admin:support_inquiry_changelist")
        page = c.get(url)
        assert page.status_code == 200
        assert b"mark_closed" not in page.content
        c.post(url, {"action": "mark_closed", "_selected_action": [inquiry.pk]})
        inquiry.refresh_from_db()
        assert inquiry.status == Inquiry.Status.OPEN
        assert inquiry.handled_at is None
