"""계정·프로필 API 테스트.

프로필의 집 위치는 **알람 계산의 출발지**다. 값이 이상하면 카카오 호출이
실패하고 알람이 조용히 계산되지 않는다. 그런데 범위 검증이 없어서
`home_lat=999` 가 200 으로 저장됐다. 이 파일이 그 구멍을 막은 것을 고정한다.
"""

from __future__ import annotations

import pytest
from django.db import IntegrityError, transaction
from rest_framework.test import APIClient

from apps.accounts.models import Profile

pytestmark = pytest.mark.django_db

PASSWORD = "Gk9-hollow-meadow-51"


def error_fields(response) -> set[str]:
    """프로젝트 공통 에러 포맷에서 필드 이름을 꺼낸다.

        {"error": {"code", "message", "details": {"필드": [...]}}}
    """
    data = response.data or {}
    details = (data.get("error") or {}).get("details")
    if isinstance(details, dict):
        return set(details.keys())
    return {k for k in data.keys() if k != "error"}


@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(
        email="acct@example.com", password=PASSWORD, nickname="계정"
    )


@pytest.fixture
def client(user):
    c = APIClient()
    c.force_authenticate(user=user)
    return c


class TestRegister:
    def test_creates_user_and_profile(self, django_user_model):
        res = APIClient().post(
            "/api/auth/register",
            {
                "email": "New@Example.COM",
                "nickname": "신규",
                "password": PASSWORD,
                "password_confirm": PASSWORD,
            },
            format="json",
        )
        assert res.status_code == 201
        # 이메일은 소문자로 정규화한다. 안 하면 대문자로 다시 가입할 수 있다.
        u = django_user_model.objects.get(email="new@example.com")
        assert Profile.objects.filter(user=u).exists()
        assert res.data.get("access")

    def test_rejects_duplicate_email_case_insensitively(self, user):
        res = APIClient().post(
            "/api/auth/register",
            {
                "email": "ACCT@EXAMPLE.COM",
                "nickname": "중복",
                "password": PASSWORD,
                "password_confirm": PASSWORD,
            },
            format="json",
        )
        assert res.status_code == 400
        assert "email" in error_fields(res)

    def test_rejects_mismatched_confirmation(self):
        res = APIClient().post(
            "/api/auth/register",
            {
                "email": "x@example.com",
                "nickname": "불일치",
                "password": PASSWORD,
                "password_confirm": PASSWORD + "z",
            },
            format="json",
        )
        assert res.status_code == 400
        assert "password_confirm" in error_fields(res)

    def test_rejects_password_similar_to_email(self):
        """Django 검증기를 태우는지 확인한다.

        이 검증기 때문에 검증 스크립트가 간헐적으로 실패한 적이 있다.
        동작 자체는 옳으므로 테스트로 고정한다.
        """
        res = APIClient().post(
            "/api/auth/register",
            {
                "email": "similar_password@example.com",
                "nickname": "유사",
                "password": "similar_password",
                "password_confirm": "similar_password",
            },
            format="json",
        )
        assert res.status_code == 400
        assert "password" in error_fields(res)

    def test_rejects_short_password(self):
        res = APIClient().post(
            "/api/auth/register",
            {
                "email": "short@example.com",
                "nickname": "짧음",
                "password": "ab1",
                "password_confirm": "ab1",
            },
            format="json",
        )
        assert res.status_code == 400

    def test_password_is_hashed(self, django_user_model):
        APIClient().post(
            "/api/auth/register",
            {
                "email": "hash@example.com",
                "nickname": "해시",
                "password": PASSWORD,
                "password_confirm": PASSWORD,
            },
            format="json",
        )
        u = django_user_model.objects.get(email="hash@example.com")
        assert u.password != PASSWORD
        assert u.check_password(PASSWORD)

    def test_blank_nickname_is_rejected(self):
        res = APIClient().post(
            "/api/auth/register",
            {
                "email": "blank@example.com",
                "nickname": "   ",
                "password": PASSWORD,
                "password_confirm": PASSWORD,
            },
            format="json",
        )
        assert res.status_code == 400


class TestLogin:
    def test_returns_tokens_and_user(self, user):
        res = APIClient().post(
            "/api/auth/token",
            {"email": user.email, "password": PASSWORD},
            format="json",
        )
        assert res.status_code == 200
        assert res.data["access"] and res.data["refresh"]
        # 로그인 직후 닉네임을 쓰려고 /me 를 한 번 더 부르는 왕복을 없앤다.
        assert res.data["user"]["nickname"] == "계정"

    def test_refresh_yields_a_working_access_token(self, user):
        tokens = APIClient().post(
            "/api/auth/token",
            {"email": user.email, "password": PASSWORD},
            format="json",
        ).data
        res = APIClient().post(
            "/api/auth/token/refresh", {"refresh": tokens["refresh"]}, format="json"
        )
        assert res.status_code == 200

        c = APIClient()
        c.credentials(HTTP_AUTHORIZATION=f"Bearer {res.data['access']}")
        assert c.get("/api/auth/me").status_code == 200

    def test_garbage_token_is_401(self):
        c = APIClient()
        c.credentials(HTTP_AUTHORIZATION="Bearer not-a-real-token")
        assert c.get("/api/auth/me").status_code == 401

    def test_email_is_case_insensitive_at_login(self, user):
        """**가입은 소문자로 저장하는데 로그인은 정규화하지 않았다.**

        `New@Example.COM` 으로 가입하면 `new@example.com` 이 저장되는데, 같은
        문자열로 로그인하면 401 이 났다. 모바일 키보드가 첫 글자를 대문자로
        바꾸는 일이 흔해서 실제로 걸린다. 저장 규칙과 조회 규칙을 맞췄다.
        """
        res = APIClient().post(
            "/api/auth/token",
            {"email": "ACCT@EXAMPLE.COM", "password": PASSWORD},
            format="json",
        )
        assert res.status_code == 200

    def test_email_whitespace_is_trimmed_at_login(self, user):
        """복사·붙여넣기에 공백이 섞이는 일이 흔하다."""
        res = APIClient().post(
            "/api/auth/token",
            {"email": "  acct@example.com  ", "password": PASSWORD},
            format="json",
        )
        assert res.status_code == 200

    def test_registering_mixed_case_then_logging_in_works(self, django_user_model):
        """가입→로그인 왕복이 대소문자와 무관하게 성립해야 한다."""
        APIClient().post(
            "/api/auth/register",
            {
                "email": "MiXeD@Example.COM",
                "nickname": "대소문자",
                "password": PASSWORD,
                "password_confirm": PASSWORD,
            },
            format="json",
        )
        for typed in ("MiXeD@Example.COM", "mixed@example.com", "MIXED@EXAMPLE.COM"):
            res = APIClient().post(
                "/api/auth/token", {"email": typed, "password": PASSWORD}, format="json"
            )
            assert res.status_code == 200, f"{typed} 로 로그인 실패"


class TestProfileCoordinateValidation:
    """**이 클래스가 이 파일의 핵심이다.**

    집 위치가 알람 계산의 출발지다. 범위 검증이 없어서 `home_lat=999` 가
    200 으로 저장됐고, 그 값이 `Event.resolve_origin` 을 통해 계산으로 흘러
    카카오를 불가능한 좌표로 불렀다. 쿼터를 쓰고 실패한다.
    """

    def test_valid_domestic_home_is_accepted(self, client):
        res = client.patch(
            "/api/profile",
            {"home_lat": 37.4842, "home_lng": 126.9297, "home_label": "신림역"},
            format="json",
        )
        assert res.status_code == 200
        assert res.data["has_home"] is True

    @pytest.mark.parametrize(
        "lat,lng,label",
        [
            (999.0, 999.0, "범위 밖"),
            (-91.0, 127.0, "위도 하한 밖"),
            (91.0, 127.0, "위도 상한 밖"),
            (37.5, -181.0, "경도 하한 밖"),
            (37.5, 181.0, "경도 상한 밖"),
        ],
    )
    def test_out_of_range_is_rejected(self, client, lat, lng, label):
        res = client.patch(
            "/api/profile", {"home_lat": lat, "home_lng": lng}, format="json"
        )
        assert res.status_code == 400, f"{label} 이 통과했다"

    @pytest.mark.parametrize(
        "lat,lng,label",
        [
            (48.8584, 2.2945, "에펠탑"),
            (40.7128, -74.0060, "뉴욕"),
            (35.6586, 139.7454, "도쿄"),
        ],
    )
    def test_foreign_home_is_rejected(self, client, lat, lng, label):
        """카카오는 국내만 경로를 준다. 국외 집은 알람을 만들 수 없다."""
        res = client.patch(
            "/api/profile", {"home_lat": lat, "home_lng": lng}, format="json"
        )
        assert res.status_code == 400, f"{label} 이 통과했다"

    def test_half_coordinate_is_rejected(self, client):
        res = client.patch("/api/profile", {"home_lat": 37.5}, format="json")
        assert res.status_code == 400

    def test_clearing_both_is_allowed(self, client):
        client.patch(
            "/api/profile", {"home_lat": 37.5, "home_lng": 127.0}, format="json"
        )
        res = client.patch(
            "/api/profile", {"home_lat": None, "home_lng": None}, format="json"
        )
        assert res.status_code == 200
        assert res.data["has_home"] is False

    def test_db_constraint_blocks_bypass(self, user):
        """**시리얼라이저를 우회하는 경로도 막혀야 한다.**

        admin·shell·시드 커맨드는 시리얼라이저를 타지 않는다. 그래서 같은
        규칙을 DB CheckConstraint 에도 박았다.
        """
        profile = user.profile
        profile.home_lat = 999.0
        profile.home_lng = 999.0
        with pytest.raises(IntegrityError):
            with transaction.atomic():
                profile.save()

    def test_db_constraint_blocks_half_coordinate(self, user):
        profile = user.profile
        profile.home_lat = 37.5
        profile.home_lng = None
        with pytest.raises(IntegrityError):
            with transaction.atomic():
                profile.save()


class TestProfileOtherFields:
    @pytest.mark.parametrize("tau", [0.4, 1.0, 1.5, -1.0])
    def test_tau_outside_range_is_rejected(self, client, tau):
        res = client.patch("/api/profile", {"default_tau": tau}, format="json")
        assert res.status_code == 400

    @pytest.mark.parametrize("tau", [0.5, 0.9, 0.999])
    def test_tau_inside_range_is_accepted(self, client, tau):
        res = client.patch("/api/profile", {"default_tau": tau}, format="json")
        assert res.status_code == 200

    def test_negative_prep_minutes_is_rejected(self, client):
        res = client.patch("/api/profile", {"onboarding_prep_min": -5}, format="json")
        assert res.status_code == 400

    def test_shadow_started_at_is_read_only(self, client):
        """클라이언트가 섀도 시작 시각을 조작하면 학습 구간이 틀어진다."""
        res = client.patch(
            "/api/profile",
            {"shadow_started_at": "2020-01-01T00:00:00+09:00"},
            format="json",
        )
        assert res.status_code == 200
        assert res.data["shadow_started_at"] is None


class TestHomeChangeRecomputesAlarms:
    def test_setting_home_recomputes_pending_events(self, client, user, monkeypatch):
        """집이 없어 `no_home` 이던 계획들이 한꺼번에 계산돼야 한다."""
        from datetime import datetime, timedelta, timezone

        from apps.events.models import Event, Place
        from apps.planning import services
        from apps.planning.models import AlarmPlan

        route = {
            "minutes": 20, "mode": "transit", "source": "kakao",
            "summary": "x", "key": "transit:x", "detail": "x",
        }
        monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(route), False))
        monkeypatch.setattr(
            services.clients, "resolve_route", lambda key, **kw: (dict(route, key=key), False)
        )

        KST = timezone(timedelta(hours=9))
        from django.utils import timezone as dj_tz

        place = Place.objects.create(name="목적지", lat=37.4601, lng=126.9520)
        event = Event.objects.create(
            user=user, place=place, title="집 없음",
            start_at=dj_tz.now() + timedelta(days=3),
        )
        plan = services.compute_and_store(event)
        assert plan.status == AlarmPlan.Status.NO_HOME
        assert KST  # 사용한다

        res = client.patch(
            "/api/profile",
            {"home_lat": 37.4842, "home_lng": 126.9297, "home_label": "신림역"},
            format="json",
        )
        assert res.status_code == 200
        plan.refresh_from_db()
        assert plan.status == AlarmPlan.Status.OK
