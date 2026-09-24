"""관리 화면 스모크 테스트.

## 왜 필요한가

admin 설정은 **런타임에만 깨진다.** `list_filter` 에 없는 필드를 쓰거나
`autocomplete_fields` 의 대상 admin 에 `search_fields` 가 없으면 `manage.py
check` 가 잡아 주기도 하지만, 잡지 못하는 부류가 더 많다.

- `list_display` 의 커스텀 메서드가 None 을 만나 터지는 경우
- `select_related` 에 없는 관계를 `__str__` 이 읽어 행마다 조회하는 경우
- DB 제약에 걸려 500 이 나는 저장(집 좌표 한쪽만 입력)

이 프로젝트에서 admin 은 장식이 아니다. 앱이 올린 관측이 실제로 쌓였는지,
어느 사용자의 집이 비어 있는지 확인하는 유일한 창구다. 그런데 admin 테스트가
한 건도 없었다 — 등록만 해 두고 열어 본 적이 없다는 뜻이다.

그래서 **등록된 모든 모델의 목록·추가·수정 화면을 실제로 열어 본다.** 모델을
새로 등록하면 이 테스트가 자동으로 포함한다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest
from django.contrib import admin
from django.urls import reverse

from apps.events.models import Event, Place
from apps.observations.models import TripObservation

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db


@pytest.fixture
def staff(django_user_model):
    return django_user_model.objects.create_superuser(
        email="admin@example.com", password="Ke3-lantern-thicket-90", nickname="관리자"
    )


@pytest.fixture
def client_admin(staff, client):
    client.force_login(staff)
    return client


@pytest.fixture
def seeded(django_user_model):
    """한 사용자와 그 사용자의 일정·관측 한 건씩.

    행이 없으면 목록 화면은 비어 있어도 200 이 뜬다. 커스텀 열이 실제 값을
    만나 터지는 것은 행이 있을 때만 드러난다.
    """
    user = django_user_model.objects.create_user(
        email="rider@example.com", password="Qt7-harbor-sockets-31", nickname="탑승자"
    )
    profile = user.profile
    profile.home_lat, profile.home_lng, profile.home_label = 37.4842, 126.9297, "신림역"
    profile.save()

    place = Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)
    event = Event.objects.create(
        user=user, place=place, title="수업",
        start_at=datetime(2026, 10, 5, 9, 0, tzinfo=KST),
    )
    TripObservation.objects.create(
        user=user, event=event, kind="arrive", detector="gps",
        observed_at=datetime(2026, 10, 5, 8, 55, tzinfo=KST),
        lat=37.4601, lng=126.9520, accuracy_m=12.0, distance_m=20.0,
        dwell_seconds=150, client_uuid="admin-smoke-1",
    )
    return user


def registered_models():
    """admin 에 등록된 이 프로젝트의 모델. 등록하면 자동으로 늘어난다."""
    return [
        model
        for model in admin.site._registry
        if model._meta.app_config.name.startswith("apps.")
    ]


def admin_url(model, view: str, **kwargs) -> str:
    meta = model._meta
    return reverse(f"admin:{meta.app_label}_{meta.model_name}_{view}", kwargs=kwargs)


class TestEveryAdminOpens:
    def test_index_lists_the_apps(self, client_admin):
        res = client_admin.get(reverse("admin:index"))
        assert res.status_code == 200
        # 머리글을 바꿔 뒀다. 로컬과 배포를 같은 화면으로 보다가 값을 엉뚱한
        # DB 에서 고친 적이 있어서다.
        assert "JustInTime" in res.content.decode()

    def test_changelists_open(self, client_admin, seeded):
        broken = []
        for model in registered_models():
            res = client_admin.get(admin_url(model, "changelist"))
            if res.status_code != 200:
                broken.append(f"{model.__name__} → {res.status_code}")
        assert not broken, "목록 화면이 열리지 않는다: " + ", ".join(broken)

    def test_changelists_survive_search_and_filters(self, client_admin, seeded):
        """검색어와 쓰레기 필터값에 500 이 나지 않는지.

        `search_fields` 에 없는 관계를 넣으면 검색할 때만 터진다. 목록을 그냥
        여는 것으로는 드러나지 않는다.
        """
        broken = []
        for model in registered_models():
            url = admin_url(model, "changelist")
            for query in ("?q=rider", "?q=%EC%8B%A0%EB%A6%BC", "?dwell=confirmed", "?scope=global"):
                res = client_admin.get(url + query)
                if res.status_code >= 500:
                    broken.append(f"{model.__name__}{query} → {res.status_code}")
        assert not broken, "목록 조회가 터진다: " + ", ".join(broken)

    def test_change_pages_open(self, client_admin, seeded):
        """행이 있는 모델의 수정 화면을 연다.

        `readonly_fields`·`fieldsets` 에 없는 필드를 적으면 여기서만 걸린다.
        """
        broken = []
        for model in registered_models():
            obj = model.objects.first()
            if obj is None:
                continue
            res = client_admin.get(admin_url(model, "change", object_id=obj.pk))
            if res.status_code != 200:
                broken.append(f"{model.__name__}({obj.pk}) → {res.status_code}")
        assert not broken, "수정 화면이 열리지 않는다: " + ", ".join(broken)

    def test_add_pages_open_or_are_closed_on_purpose(self, client_admin, seeded):
        """추가 화면은 열리거나, 막혀 있어야 한다(403).

        계획과 관측은 계산·업로드로만 생긴다. 손으로 만들면 어느 입력에서 나온
        값인지 모르는 행이 학습에 들어가므로 일부러 막았다. 그 외에 403 이
        나오면 설정 실수다.
        """
        closed = {"alarmplan", "tripobservation"}
        broken = []
        for model in registered_models():
            name = model._meta.model_name
            res = client_admin.get(admin_url(model, "add"))
            expected = 403 if name in closed else 200
            if res.status_code != expected:
                broken.append(f"{name} → {res.status_code} (기대 {expected})")
        assert not broken, "추가 화면 상태가 다르다: " + ", ".join(broken)

    def test_every_app_model_is_registered(self):
        """등록을 빠뜨린 모델이 없는지.

        등록하지 않으면 그 표는 관리 화면에 아예 없다. 예전에
        `routing.RouteCorrection` 이 그랬다 — 알람 시각을 보정하는 값인데
        확인할 방법이 `manage.py shell` 뿐이었다.
        """
        from django.apps import apps as django_apps

        missing = []
        for app in django_apps.get_app_configs():
            if not app.name.startswith("apps."):
                continue
            for model in app.get_models():
                if model not in admin.site._registry:
                    missing.append(f"{app.label}.{model.__name__}")
        assert not missing, "admin 에 등록되지 않은 모델: " + ", ".join(missing)


class TestHomeCoordinatePair:
    """집 좌표를 한쪽만 저장하려 할 때.

    DB 제약 `accounts_profile_home_pair` 가 막지만, 제약에 걸리면 admin 은
    IntegrityError 로 500 을 띄운다. 무엇이 틀렸는지 화면에 남지 않아서
    고치려던 사람이 포기한다. 폼에서 먼저 잡아야 한다.
    """

    def _post(self, client_admin, profile, **override):
        data = {
            "user": profile.user_id,
            "home_label": "신림역",
            "home_lat": "37.4842",
            "home_lng": "126.9297",
            "default_tau": "0.9",
            "timezone": "Asia/Seoul",
            "onboarding_prep_min": "",
            "shadow_started_at_0": "",
            "shadow_started_at_1": "",
        }
        data.update(override)
        url = reverse("admin:accounts_profile_change", args=[profile.pk])
        return client_admin.post(url, data)

    def test_latitude_only_is_rejected_with_a_message(self, client_admin, seeded):
        profile = seeded.profile
        res = self._post(client_admin, profile, home_lng="")

        # 폼이 다시 그려진다(302 면 저장된 것이다).
        assert res.status_code == 200
        assert "함께 넣거나 함께 비워야" in res.content.decode()

        profile.refresh_from_db()
        assert profile.home_lng == 126.9297, "거부했는데 값이 바뀌었다"

    def test_both_empty_is_allowed(self, client_admin, seeded):
        """집을 지우는 것은 정상 동작이다. 사용자가 이사하면 비워야 한다."""
        profile = seeded.profile
        res = self._post(client_admin, profile, home_lat="", home_lng="")
        assert res.status_code == 302

        profile.refresh_from_db()
        assert profile.home_lat is None
        assert profile.home_lng is None

    def test_both_present_saves(self, client_admin, seeded):
        profile = seeded.profile
        res = self._post(client_admin, profile, home_lat="37.5", home_lng="127.0")
        assert res.status_code == 302

        profile.refresh_from_db()
        assert profile.home_lat == 37.5
        assert profile.home_lng == 127.0
