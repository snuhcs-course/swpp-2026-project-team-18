"""장소 검색·정적 지도 뷰.

뷰가 하는 일은 쿼리 파라미터를 읽어 클라이언트에 넘기는 것뿐이다. 그런데
그 "뿐" 에서 조용히 틀리기 쉽다 — 좌표를 한쪽만 읽어 넘기면 카카오가 엉뚱한
결과를 주고, 페이지를 숫자로 못 바꾸면 500 이 난다.

정적 지도는 **캐시가 핵심이다.** 지도를 움직일 때마다 카카오를 부르면 하루
한도(1,000건)를 금방 태운다. 같은 화면을 두 번 그릴 때 호출이 한 번인지
검사한다.
"""

from __future__ import annotations

import pytest
from django.core.cache import cache
from rest_framework.test import APIClient

from apps.routing import clients

pytestmark = pytest.mark.django_db

SEARCH_URL = "/api/places/search"
MAP_URL = "/api/places/staticmap"


@pytest.fixture
def user(django_user_model):
    return django_user_model.objects.create_user(
        email="place@example.com", password="Wd4-anchor-pebble-72", nickname="장소"
    )


@pytest.fixture
def client(user):
    c = APIClient()
    c.force_authenticate(user=user)
    return c


@pytest.fixture(autouse=True)
def clear_cache():
    cache.clear()
    yield
    cache.clear()


@pytest.fixture
def fake_search(monkeypatch):
    """검색 클라이언트를 가로채 받은 인자를 기록한다."""
    calls: list[dict] = []

    def fake(query, **kw):
        calls.append({"query": query, **kw})
        return (
            {
                "results": [],
                "page": kw.get("page", 1),
                "total_count": 0,
                "reachable_count": 0,
                "is_end": True,
                "sort": clients.SORT_ACCURACY,
            },
            False,
        )

    monkeypatch.setattr(clients, "search_places", fake)
    return calls


class TestSearchView:
    def test_blank_query_returns_empty_envelope(self, client, fake_search):
        """빈 검색어로 카카오를 부르지 않는다. 쿼터가 있다."""
        res = client.get(f"{SEARCH_URL}?q=")
        assert res.status_code == 200
        assert res.data["results"] == []
        assert res.data["is_end"] is True
        assert fake_search == []

    def test_coordinates_reach_the_client(self, client, fake_search):
        client.get(f"{SEARCH_URL}?q=카페&lat=37.4783&lng=126.9516")
        assert fake_search[0]["lat"] == pytest.approx(37.4783)
        assert fake_search[0]["lng"] == pytest.approx(126.9516)

    def test_half_a_coordinate_is_dropped(self, client, fake_search):
        """위도만 있는 좌표로는 거리도 거리순도 만들 수 없다."""
        client.get(f"{SEARCH_URL}?q=카페&lat=37.4783")
        assert fake_search[0]["lat"] is None
        assert fake_search[0]["lng"] is None

    def test_out_of_range_coordinate_is_dropped(self, client, fake_search):
        client.get(f"{SEARCH_URL}?q=카페&lat=999&lng=126.9")
        assert fake_search[0]["lat"] is None

    def test_page_sort_rect_reach_the_client(self, client, fake_search):
        client.get(
            f"{SEARCH_URL}?q=카페&page=3&sort=distance&rect=126.94,37.46,126.96,37.49"
        )
        call = fake_search[0]
        assert call["page"] == 3
        assert call["sort"] == "distance"
        assert call["rect"] == "126.94,37.46,126.96,37.49"

    @pytest.mark.parametrize("garbage", ["?q=카페&page=abc", "?q=카페&page=-1", "?q=카페&page="])
    def test_garbage_page_falls_back_to_one(self, client, fake_search, garbage):
        client.get(f"{SEARCH_URL}{garbage}")
        assert fake_search[-1]["page"] == 1

    def test_requires_authentication(self):
        assert APIClient().get(f"{SEARCH_URL}?q=카페").status_code in (401, 403)


class TestStaticMapView:
    @pytest.fixture
    def fake_map(self, monkeypatch):
        calls: list[dict] = []

        def fake(**kw):
            calls.append(kw)
            return b"\x89PNG-body", "image/png", False

        monkeypatch.setattr(clients, "static_map", fake)
        return calls

    def test_returns_the_image_bytes(self, client, fake_map):
        res = client.get(f"{MAP_URL}?lat=37.4783&lng=126.9516&lv=5&w=360&h=500")
        assert res.status_code == 200
        assert res["Content-Type"] == "image/png"
        assert res.content == b"\x89PNG-body"
        assert res["X-Jit-Map-Cache"] == "miss"

    def test_second_identical_request_is_served_from_cache(self, client, fake_map):
        """지도를 움직일 때마다 카카오를 부르면 하루 한도를 태운다."""
        url = f"{MAP_URL}?lat=37.4783&lng=126.9516&lv=5&w=360&h=500"
        first = client.get(url)
        second = client.get(url)

        assert first["X-Jit-Map-Cache"] == "miss"
        assert second["X-Jit-Map-Cache"] == "hit"
        assert second.content == first.content
        assert len(fake_map) == 1, "같은 화면인데 카카오를 두 번 불렀다"

    def test_different_center_is_a_separate_call(self, client, fake_map):
        client.get(f"{MAP_URL}?lat=37.4783&lng=126.9516&lv=5&w=360&h=500")
        client.get(f"{MAP_URL}?lat=37.5000&lng=126.9516&lv=5&w=360&h=500")
        assert len(fake_map) == 2

    def test_markers_change_the_cache_key(self, client, fake_map):
        base = f"{MAP_URL}?lat=37.4783&lng=126.9516&lv=5&w=360&h=500"
        client.get(base)
        client.get(f"{base}&markers=37.48,126.95")
        assert len(fake_map) == 2

    def test_markers_are_parsed_into_pairs(self, client, fake_map):
        client.get(
            f"{MAP_URL}?lat=37.4783&lng=126.9516&markers=37.48,126.95;37.49,126.96"
        )
        assert fake_map[0]["markers"] == [(37.48, 126.95), (37.49, 126.96)]

    def test_broken_markers_are_skipped_not_fatal(self, client, fake_map):
        client.get(
            f"{MAP_URL}?lat=37.4783&lng=126.9516&markers=쓰레기;37.48,126.95;1,2,3;999,1"
        )
        assert fake_map[0]["markers"] == [(37.48, 126.95)]

    def test_missing_coordinate_is_400(self, client, fake_map):
        res = client.get(f"{MAP_URL}?lv=5")
        assert res.status_code == 400
        assert res.data["error"]["code"] == "invalid_coordinate"
        assert fake_map == []

    # --- 뷰포트 범위 --------------------------------------------------------
    #
    # 범위를 벗어난 값을 잘라서 쓰면 **요청한 것과 다른 그림이 200 으로 돌아가고
    # 클라이언트는 그것을 모른다.** 앱은 자기가 보낸 lv 로 좌표를 계산하므로
    # lv=20 을 보내고 lv=15 그림을 받으면 경로선이 32배 어긋난 자리에 그려진다.
    # 그림 자체는 정상이라 원인을 찾기 어렵다.

    @pytest.mark.parametrize(
        "query",
        [
            "lv=0", "lv=16", "lv=99", "lv=-1",
            "w=0", "w=4096",
            "h=0", "h=2048",
        ],
    )
    def test_out_of_range_viewport_is_400(self, client, fake_map, query):
        res = client.get(f"{MAP_URL}?lat=37.4783&lng=126.9516&{query}")
        assert res.status_code == 400, f"{query} 를 200 으로 받았다"
        assert res.data["error"]["code"] == "invalid_viewport"
        assert fake_map == [], f"{query} 인데 카카오를 불렀다"

    @pytest.mark.parametrize("query", ["lv=1", "lv=15", "w=1", "w=2048", "h=1", "h=1024"])
    def test_range_edges_are_accepted(self, client, fake_map, query):
        res = client.get(f"{MAP_URL}?lat=37.4783&lng=126.9516&{query}")
        assert res.status_code == 200, f"{query} 는 범위 안인데 거절했다"

    @pytest.mark.parametrize("query", ["", "lv=", "lv=abc", "w=&h="])
    def test_absent_or_unreadable_viewport_falls_back_to_defaults(
        self, client, fake_map, query
    ):
        """없는 것과 불가능한 것은 다르다. 안 보냈으면 서버가 정한다."""
        res = client.get(f"{MAP_URL}?lat=37.4783&lng=126.9516&{query}")
        assert res.status_code == 200
        assert fake_map[0]["level"] == 5
        assert fake_map[0]["width"] == 360
        assert fake_map[0]["height"] == 500

    def test_unavailable_map_is_503_with_a_usable_message(self, client, monkeypatch):
        """지도를 못 그렸다고 화면을 막지 않는다. 목록으로 계속 고를 수 있다."""
        monkeypatch.setattr(clients, "static_map", lambda **kw: (None, "", True))
        res = client.get(f"{MAP_URL}?lat=37.4783&lng=126.9516")
        assert res.status_code == 503
        assert res.data["error"]["code"] == "map_unavailable"
        assert "목록" in res.data["error"]["message"]

    def test_failures_are_not_cached(self, client, monkeypatch):
        """실패를 캐시하면 카카오가 돌아온 뒤에도 한 시간 동안 지도가 안 나온다."""
        state = {"fail": True}

        def flaky(**kw):
            if state["fail"]:
                return None, "", True
            return b"ok", "image/png", False

        monkeypatch.setattr(clients, "static_map", flaky)
        url = f"{MAP_URL}?lat=37.4783&lng=126.9516"
        assert client.get(url).status_code == 503

        state["fail"] = False
        res = client.get(url)
        assert res.status_code == 200
        assert res.content == b"ok"

    def test_requires_authentication(self):
        res = APIClient().get(f"{MAP_URL}?lat=37.4783&lng=126.9516")
        assert res.status_code in (401, 403)
