"""전 엔드포인트 보안 감사.

## 왜 개별 테스트가 아니라 감사인가

엔드포인트마다 "인증이 필요한지" 테스트를 손으로 쓰면 **새로 추가한 것을
빠뜨린다.** 그리고 빠뜨린 사실이 드러나지 않는다 — 테스트가 없으니 실패도 없다.

그래서 URL 설정을 **열거**해 규칙을 적용한다. 새 엔드포인트를 붙이면 자동으로
감사 대상이 되고, 인증을 빼먹으면 이 파일이 실패한다. 비인증 허용은
`PUBLIC_PATHS` 에 명시적으로 적어야 통과한다 — 그 목록을 고치는 순간 리뷰에
걸린다.

## 검사 항목

1. 모든 `/api/` 경로가 인증을 요구한다 (허용 목록 제외)
2. 남의 자원은 **404** 다. 403 이면 "존재한다" 는 정보가 새어 나간다
3. 잘못된 입력에 500 이 나지 않는다
4. `user` 를 본문에 넣어도 무시된다 (mass assignment)
5. 외부 API 를 부르는 경로에 스로틀이 붙어 있다
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import pytest
from django.urls import URLPattern, URLResolver, get_resolver
from rest_framework.test import APIClient

KST = timezone(timedelta(hours=9))
pytestmark = pytest.mark.django_db


# 인증 없이 접근해도 되는 경로.
#
# **추가할 때 근거를 함께 적는다.** 이 목록을 고치는 것이 곧 "공개로 바꾼다" 는
# 결정이므로, 근거가 없으면 리뷰에서 판단할 수 없다. 근거 길이도 검사한다.
PUBLIC_PATHS = {
    "/api/health": (
        "연결 진단용. DB 도 사용자 정보도 건드리지 않는다. 서버 버전과 "
        "실시간 제공자 키의 설정 여부(참/거짓)만 반환한다 — 키 값도 길이도 "
        "담지 않으며 test_health.py 가 이를 고정한다. 앱이 콜드 스타트를 "
        "감지하고, 배포 서버에서 도착정보가 안 뜰 때 키 미설정과 외부 호출 "
        "실패를 구분하는 데 쓴다"
    ),
    "/api/auth/register": (
        "회원가입. 토큰을 받기 전이라 인증할 수단이 없다. "
        "남용은 비밀번호 검증기와 이메일 유일 제약이 막는다"
    ),
    "/api/auth/token": (
        "로그인. 여기서 토큰을 받는다. 없는 계정과 틀린 비밀번호의 응답이 "
        "동일해야 한다(계정 열거 방지) — 별도 테스트로 고정했다"
    ),
    "/api/auth/token/refresh": (
        "액세스 토큰 재발급. refresh 토큰 자체가 자격증명이므로 "
        "Authorization 헤더가 필요 없다"
    ),
}

# 감사 대상이 아닌 접두. admin 은 Django 가 자체 로그인으로 보호한다.
SKIP_PREFIXES = ("/admin/", "/static/")

# 외부 API 를 부르므로 스로틀이 필요한 경로.
THROTTLED_PATHS = {
    "/api/places/search": "카카오 로컬 (일 1,000건)",
    "/api/places/reverse": "카카오 좌표→주소",
    # 지도를 움직일 때마다 이미지를 새로 받는다. 스로틀과 서버 캐시가 함께
    # 막아야 하루 한도를 태우지 않는다.
    "/api/places/staticmap": "카카오 정적 지도 (일 1,000건)",
    "/api/routes/candidates": "카카오 경로",
    "/api/observations/batch": "쓰기 폭주 방지",
    "/api/routines/observations/batch": "쓰기 폭주 방지",
}


def _flatten(resolver, prefix: str = "") -> list[tuple[str, object]]:
    """URL 설정을 평탄화해 `(경로 패턴, 뷰)` 목록으로 만든다."""
    out: list[tuple[str, object]] = []
    for entry in resolver.url_patterns:
        if isinstance(entry, URLResolver):
            out.extend(_flatten(entry, prefix + str(entry.pattern)))
        elif isinstance(entry, URLPattern):
            out.append((prefix + str(entry.pattern), entry.callback))
    return out


def _concrete(pattern: str) -> str:
    """URL 패턴의 변환자를 더미 값으로 바꿔 실제 요청 가능한 경로를 만든다."""
    import re

    path = re.sub(r"<int:[^>]+>", "999999", pattern)
    path = re.sub(r"<str:[^>]+>", "x", path)
    path = re.sub(r"<slug:[^>]+>", "x", path)
    path = re.sub(r"<uuid:[^>]+>", "00000000-0000-4000-8000-000000000000", path)
    path = re.sub(r"<[^>]+>", "x", path)
    return "/" + path.lstrip("/")


def api_routes() -> list[str]:
    """감사 대상 `/api/` 경로 목록."""
    routes = []
    for pattern, _view in _flatten(get_resolver()):
        path = _concrete(pattern)
        if any(path.startswith(p) for p in SKIP_PREFIXES):
            continue
        if not path.startswith("/api/"):
            continue
        routes.append(path)
    return sorted(set(routes))


class TestEveryApiRouteRequiresAuth:
    """**이 클래스가 이 파일의 핵심이다.**

    새 엔드포인트를 인증 없이 붙이면 여기서 실패한다.
    """

    def test_routes_were_discovered(self):
        """열거 자체가 깨지면 감사가 조용히 통과한다. 그걸 막는다."""
        routes = api_routes()
        assert len(routes) >= 15, f"경로를 {len(routes)}개만 찾았다. 열거 로직 확인: {routes}"

    @pytest.mark.parametrize("path", api_routes())
    def test_unauthenticated_request_is_rejected(self, path):
        client = APIClient()
        public = any(path.startswith(p) for p in PUBLIC_PATHS)

        # GET·POST 를 모두 시도한다. 한쪽만 보호된 경우를 잡는다.
        for method in ("get", "post", "put", "patch", "delete"):
            res = getattr(client, method)(path, {}, format="json")
            if res.status_code == 405:
                continue  # 이 메서드를 지원하지 않는다
            if public:
                # 비인증 허용 경로는 401 이 아니어야 한다(400/200/404 는 정상).
                assert res.status_code != 401, (
                    f"{method.upper()} {path} 는 공개 경로인데 401 이다"
                )
            else:
                assert res.status_code == 401, (
                    f"{method.upper()} {path} 가 인증 없이 {res.status_code} 를 냈다. "
                    "인증이 필요하거나, 공개가 맞다면 PUBLIC_PATHS 에 근거와 함께 추가할 것."
                )

    def test_public_path_list_is_documented(self):
        """허용 목록의 모든 항목에 근거가 적혀 있어야 한다."""
        for path, reason in PUBLIC_PATHS.items():
            assert reason and len(reason) > 3, f"{path} 의 근거가 비었다"


class TestThrottleIsAttached:
    """외부 API 를 부르는 경로에 스로틀이 붙어 있는지.

    빠지면 카카오 무료 쿼터(일 1,000건)가 한 사용자의 연타로 마른다.
    """

    @pytest.mark.parametrize("path,why", sorted(THROTTLED_PATHS.items()))
    def test_view_declares_a_throttle(self, path, why):
        from django.urls import resolve

        match = resolve(path)
        view_class = getattr(match.func, "cls", None) or getattr(
            match.func, "view_class", None
        )
        assert view_class is not None, f"{path} 의 뷰 클래스를 찾지 못했다"

        throttles = getattr(view_class, "throttle_classes", None)
        assert throttles, f"{path} 에 throttle_classes 가 없다 ({why})"

        # ScopedRateThrottle 을 쓰면 scope 가 설정에 있어야 한다.
        from django.conf import settings

        rates = settings.REST_FRAMEWORK.get("DEFAULT_THROTTLE_RATES", {})
        scope = getattr(view_class, "throttle_scope", None)
        if scope is not None:
            assert scope in rates, (
                f"{path} 의 throttle_scope={scope!r} 가 DEFAULT_THROTTLE_RATES 에 없다"
            )


# ---------------------------------------------------------------------------
# 교차 사용자 격리
# ---------------------------------------------------------------------------


def make_user(django_user_model, email):
    u = django_user_model.objects.create_user(
        email=email, password="Vt7-silver-canyon-42", nickname=email.split("@")[0]
    )
    p = u.profile
    p.home_lat, p.home_lng, p.home_label = 37.4842, 126.9297, "신림역"
    p.onboarding_prep_min = 30
    p.save()
    return u


def auth_client(user):
    c = APIClient()
    c.force_authenticate(user=user)
    return c


@pytest.fixture
def alice(django_user_model):
    return make_user(django_user_model, "audit_alice@example.com")


@pytest.fixture
def bob(django_user_model):
    return make_user(django_user_model, "audit_bob@example.com")


@pytest.fixture
def stub_route(monkeypatch):
    from apps.planning import services

    route = {
        "minutes": 20, "mode": "transit", "source": "kakao",
        "summary": "2호선 · 20분", "key": "transit:2호선", "detail": "2호선",
    }
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(route), False))
    monkeypatch.setattr(
        services.clients, "resolve_route", lambda key, **kw: (dict(route, key=key), False)
    )


@pytest.fixture
def alice_event(alice, stub_route):
    from apps.events.models import Event, Place

    place = Place.objects.create(name="관악캠퍼스", lat=37.4601, lng=126.9520)
    return Event.objects.create(
        user=alice, place=place, title="앨리스 일정",
        start_at=datetime(2026, 10, 5, 9, 0, tzinfo=KST),
    )


class TestCrossUserIsolation:
    """남의 자원은 **404** 여야 한다.

    403 을 쓰면 "그 id 는 존재하지만 권한이 없다" 는 정보가 새어 나간다.
    일정 id 를 훑어 다른 사람이 몇 개의 일정을 가졌는지 셀 수 있다.
    """

    def test_event_detail(self, alice, bob, alice_event):
        res = auth_client(bob).get(f"/api/events/{alice_event.pk}")
        assert res.status_code == 404

    def test_event_patch(self, alice, bob, alice_event):
        res = auth_client(bob).patch(
            f"/api/events/{alice_event.pk}", {"title": "탈취"}, format="json"
        )
        assert res.status_code == 404
        alice_event.refresh_from_db()
        assert alice_event.title == "앨리스 일정"

    def test_event_delete(self, alice, bob, alice_event):
        from apps.events.models import Event

        assert auth_client(bob).delete(f"/api/events/{alice_event.pk}").status_code == 404
        assert Event.objects.filter(pk=alice_event.pk).exists()

    def test_event_recompute(self, alice, bob, alice_event, stub_route):
        res = auth_client(bob).post(f"/api/events/{alice_event.pk}/recompute")
        assert res.status_code == 404

    def test_event_list_excludes_others(self, alice, bob, alice_event):
        res = auth_client(bob).get("/api/events")
        assert res.status_code == 200
        assert res.data["results"] == []

    def test_event_blocks(self, alice, bob, alice_event):
        res = auth_client(bob).get(f"/api/events/{alice_event.pk}/blocks")
        assert res.status_code == 404

    def test_observation_list_excludes_others(self, alice, bob, alice_event):
        from apps.observations.models import TripObservation

        TripObservation.objects.create(
            user=alice, event=alice_event, kind=TripObservation.Kind.DEPART,
            observed_at=datetime(2026, 10, 5, 8, 0, tzinfo=KST),
            lat=37.48, lng=126.93, accuracy_m=10.0, distance_m=60.0,
            client_uuid="iso-1",
        )
        res = auth_client(bob).get("/api/observations")
        assert res.status_code == 200
        assert (res.data.get("results") or []) == []

    def test_cannot_upload_observation_for_another_users_event(
        self, alice, bob, alice_event
    ):
        """**남의 일정에 관측을 붙이면 그 사람의 학습 데이터가 오염된다.**

        `observations` 는 404 로 막는다(`TripObservationWriteSerializer.
        validate_event` 가 `NotFound` 를 던진다). `routines` 는 참조 필드의
        queryset 을 좁혀 400 을 낸다. **둘 다 존재 여부를 흘리지 않으므로
        보안상 동등하다.**

        코드가 다른 이유는 막는 지점이 달라서다 — 한쪽은 값을 검증하고
        한쪽은 선택 가능한 값 자체를 제한한다. 클라이언트는 두 경우를 모두
        "이 일정/블록을 쓸 수 없음" 으로 다뤄야 한다. 이 테스트가 그 차이를
        문서로 고정한다.
        """
        from apps.observations.models import TripObservation

        res = auth_client(bob).post(
            "/api/observations/batch",
            {
                "observations": [{
                    "event": alice_event.pk,
                    "kind": "depart",
                    "detector": "gps",
                    "observed_at": "2026-10-05T08:00:00+09:00",
                    "lat": 37.48, "lng": 126.93,
                    "accuracy_m": 10.0, "distance_m": 60.0,
                    "client_uuid": "attack-1",
                }]
            },
            format="json",
        )
        assert res.status_code == 404
        assert not TripObservation.objects.filter(client_uuid="attack-1").exists()

    def test_foreign_resource_never_returns_403(self, alice, bob, alice_event):
        """403 은 "존재하지만 권한 없음" 을 뜻해 id 열거를 가능하게 한다.

        일정 id 를 훑어 다른 사람이 몇 개의 일정을 가졌는지 셀 수 있다.
        전 경로에서 403 이 나오지 않아야 한다.
        """
        bob_client = auth_client(bob)
        attempts = [
            ("get", f"/api/events/{alice_event.pk}", None),
            ("patch", f"/api/events/{alice_event.pk}", {"title": "x"}),
            ("delete", f"/api/events/{alice_event.pk}", None),
            ("post", f"/api/events/{alice_event.pk}/recompute", None),
            ("get", f"/api/events/{alice_event.pk}/blocks", None),
            ("put", f"/api/events/{alice_event.pk}/blocks",
             {"selections": [{"block": 1, "checked": True}]}),
        ]
        for method, path, body in attempts:
            res = getattr(bob_client, method)(
                path, body if body is not None else {}, format="json"
            )
            assert res.status_code != 403, f"{method.upper()} {path} 가 403 을 냈다"

    def test_profile_is_per_user(self, alice, bob):
        """프로필 경로에 id 가 없다. 항상 자기 것만 본다."""
        auth_client(alice).patch(
            "/api/profile", {"home_label": "앨리스집"}, format="json"
        )
        res = auth_client(bob).get("/api/profile")
        assert res.data["home_label"] != "앨리스집"


class TestMassAssignment:
    """`user` 를 본문에 넣어도 무시돼야 한다.

    받으면 다른 사용자 앞으로 자원을 만들 수 있다.
    """

    def test_event_user_cannot_be_forced(self, alice, bob, stub_route):
        from apps.events.models import Event

        res = auth_client(alice).post(
            "/api/events",
            {
                "title": "주입 시도",
                "start_at": "2026-10-06T09:00:00+09:00",
                "user": bob.pk,
            },
            format="json",
        )
        assert res.status_code == 201
        assert Event.objects.get(pk=res.data["id"]).user_id == alice.pk
        assert not Event.objects.filter(user=bob).exists()

    def test_observation_user_cannot_be_forced(self, alice, bob, alice_event):
        from apps.observations.models import TripObservation

        res = auth_client(alice).post(
            "/api/observations/batch",
            {
                "observations": [{
                    "event": alice_event.pk,
                    "kind": "depart",
                    "detector": "gps",
                    "observed_at": "2026-10-05T08:00:00+09:00",
                    "lat": 37.48, "lng": 126.93,
                    "accuracy_m": 10.0, "distance_m": 60.0,
                    "client_uuid": "mass-1",
                    "user": bob.pk,
                }]
            },
            format="json",
        )
        assert res.status_code in (200, 201)
        obs = TripObservation.objects.get(client_uuid="mass-1")
        assert obs.user_id == alice.pk

    def test_block_user_cannot_be_forced(self, alice, bob):
        from apps.routines.models import RoutineBlock

        res = auth_client(alice).post(
            "/api/routines/blocks",
            {
                "name": "주입",
                "default_min_minutes": 1,
                "default_max_minutes": 2,
                "user": bob.pk,
            },
            format="json",
        )
        assert res.status_code == 201
        assert RoutineBlock.objects.get(pk=res.data["id"]).user_id == alice.pk


class TestNoFiveHundredOnBadInput:
    """잘못된 입력에 500 이 나면 안 된다.

    500 은 스택트레이스가 로그에 남고, 어디서 깨졌는지에 따라 내부 구조가
    드러난다. 사용자 입력은 전부 400 으로 돌려보내야 한다.
    """

    BAD_BODIES = [
        {},
        {"title": None},
        {"title": "x" * 500},
        {"start_at": "not-a-date"},
        {"start_at": ""},
        {"place": "문자열인데 객체여야 한다"},
        {"place": {"lat": "abc", "lng": "def"}},
        {"tau_override": 5.0},
        {"tau_override": "높게"},
        {"origin_lat": 37.5},          # 반쪽 좌표
        {"origin_lat": 999, "origin_lng": 999},
        {"tag_key": "없는태그"},
        {"route_key": "x" * 500},
    ]

    @pytest.mark.parametrize("body", BAD_BODIES)
    def test_event_create(self, alice, stub_route, body):
        res = auth_client(alice).post("/api/events", body, format="json")
        assert res.status_code < 500, f"{body} → {res.status_code}"

    @pytest.mark.parametrize(
        "query",
        [
            "",
            "?q=",
            "?q=" + "x" * 1000,
            "?lat=abc&lng=def",
            "?lat=999&lng=999",
            "?lat=&lng=",
            "?dest_lat=abc&dest_lng=def",
            "?dest_lat=37.5",
            "?origin_lat=37.5&dest_lat=37.4&dest_lng=127.0",
        ],
    )
    def test_place_and_route_queries(self, alice, query):
        for path in ("/api/places/search", "/api/places/reverse", "/api/routes/candidates"):
            res = auth_client(alice).get(f"{path}{query}")
            assert res.status_code < 500, f"{path}{query} → {res.status_code}"

    BAD_OBSERVATION_BATCHES = [
        {},
        {"observations": None},
        {"observations": "배열이어야 한다"},
        {"observations": []},
        {"observations": [{}]},
        {"observations": [{"kind": "없는종류"}]},
        {"observations": [{"event": 999999, "kind": "depart"}]},
        {"observations": [{"lat": "abc"}]},
    ]

    @pytest.mark.parametrize("body", BAD_OBSERVATION_BATCHES)
    def test_observation_batch(self, alice, body):
        res = auth_client(alice).post("/api/observations/batch", body, format="json")
        assert res.status_code < 500, f"{body} → {res.status_code}"

    @pytest.mark.parametrize("body", BAD_OBSERVATION_BATCHES)
    def test_block_observation_batch(self, alice, body):
        res = auth_client(alice).post(
            "/api/routines/observations/batch", body, format="json"
        )
        assert res.status_code < 500, f"{body} → {res.status_code}"

    BAD_BLOCK_BODIES = [
        {},
        {"name": ""},
        {"name": "x" * 500},
        {"name": "정상", "default_min_minutes": -1, "default_max_minutes": 5},
        {"name": "정상", "default_min_minutes": "abc"},
        {"name": "정상", "default_min_minutes": 5, "default_max_minutes": 1},
        {"name": "정상", "default_min_minutes": 0, "default_max_minutes": 999999},
        {"name": "정상", "default_min_minutes": 1, "default_max_minutes": 2,
         "precondition": 999999},
        {"name": "정상", "default_min_minutes": 1, "default_max_minutes": 2,
         "drop_cost": "없는값"},
    ]

    @pytest.mark.parametrize("body", BAD_BLOCK_BODIES)
    def test_block_create(self, alice, body):
        res = auth_client(alice).post("/api/routines/blocks", body, format="json")
        assert res.status_code < 500, f"{body} → {res.status_code}"

    BAD_PROFILE_BODIES = [
        {"home_lat": "abc"},
        {"home_lat": 999, "home_lng": 999},
        {"default_tau": 5.0},
        {"default_tau": -1},
        {"default_tau": "높게"},
        {"onboarding_prep_min": -5},
        {"onboarding_prep_min": 999999},
        {"home_label": "x" * 500},
        {"timezone": "없는/시간대"},
    ]

    @pytest.mark.parametrize("body", BAD_PROFILE_BODIES)
    def test_profile_patch(self, alice, body):
        res = auth_client(alice).patch("/api/profile", body, format="json")
        assert res.status_code < 500, f"{body} → {res.status_code}"

    def test_malformed_json_is_400(self, alice):
        c = auth_client(alice)
        res = c.post(
            "/api/events", data="{깨진 JSON", content_type="application/json"
        )
        assert res.status_code == 400

    def test_deeply_nested_json_does_not_crash(self, alice):
        """중첩 폭탄. 파서가 재귀 한도를 넘으면 500 이 된다."""
        body = {"title": "깊음"}
        nested = body
        for _ in range(60):
            nested["place"] = {"name": "x"}
            nested = nested["place"]
        res = auth_client(alice).post(
            "/api/events", json.dumps(body), content_type="application/json"
        )
        assert res.status_code < 500


class TestErrorFormatDoesNotLeakInternals:
    """에러 본문에 내부 정보가 실리지 않아야 한다."""

    def test_no_traceback_in_response(self, alice):
        res = auth_client(alice).post(
            "/api/events", {"start_at": "not-a-date"}, format="json"
        )
        body = json.dumps(res.data, ensure_ascii=False)
        for leak in ("Traceback", "site-packages", "/backend/", "\\backend\\"):
            assert leak not in body, f"에러 본문에 {leak!r} 이 있다: {body[:300]}"

    def test_unknown_user_login_message_is_identical(self, django_user_model):
        """없는 계정과 틀린 비밀번호의 응답이 같아야 한다.

        다르면 이메일 존재 여부를 확인할 수 있다(계정 열거).
        """
        make_user(django_user_model, "exists@example.com")
        c = APIClient()
        wrong_pw = c.post(
            "/api/auth/token",
            {"email": "exists@example.com", "password": "Xz1-wrong-password-99"},
            format="json",
        )
        no_user = c.post(
            "/api/auth/token",
            {"email": "nobody@example.com", "password": "Xz1-wrong-password-99"},
            format="json",
        )
        assert wrong_pw.status_code == no_user.status_code
        assert json.dumps(wrong_pw.data, ensure_ascii=False) == json.dumps(
            no_user.data, ensure_ascii=False
        )
