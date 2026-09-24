"""장소 검색 파라미터 조립과 응답 정규화.

## 왜 요청 문자열까지 검사하는가

이 기능의 버그는 전부 **조용했다.**

- `rect` 의 좌표 순서를 뒤바꿔 보내면 카카오는 에러 대신 **0건**을 준다.
  "검색 결과가 없다" 로 보여서 원인을 찾을 수 없다.
- 마커 여러 개를 한 값 안에서 `&` 로 이어 붙이면 쿼리 구분자로 먹혀 **첫
  마커만** 그려진다. 응답은 200 이고 이미지도 정상이라 알아채기 어렵다.
- `sort=distance` 를 좌표 없이 보내면 400 이다. 위치 권한이 없는 사용자에게
  검색이 실패하는 것으로 보인다.

그래서 카카오에 **무엇을 보내는지**를 고정한다. 응답 파싱만 검사하면 이
부류가 전부 빠져나간다(버스 도착정보에서 실제로 그랬다 — 가짜 픽스처로
테스트는 통과하는데 기능은 죽어 있었다).
"""

from __future__ import annotations

import urllib.parse

import pytest

from apps.routing import clients

# 카카오 응답 한 건. 실제 응답에서 옮긴 필드 구성이다(probe_local.py).
DOC = {
    "address_name": "서울 관악구 봉천동 1685-1",
    "category_group_code": "",
    "category_group_name": "",
    "category_name": "가정,생활 > 여가시설 > 게임방,PC방",
    "distance": "203",
    "id": "1234567890",
    "phone": "070-8666-6554",
    "place_name": "레드포스PC",
    "place_url": "http://place.map.kakao.com/1234567890",
    "road_address_name": "서울 관악구 봉천로 12",
    "x": "126.952141877838",
    "y": "37.4800760249697",
}

META = {"total_count": 136, "pageable_count": 45, "is_end": False}


@pytest.fixture
def captured(monkeypatch):
    """`_get` 을 가로채 요청 URL 을 모은다."""
    seen: list[str] = []

    def fake_get(url):
        seen.append(url)
        return {"documents": [DOC], "meta": META}, False

    monkeypatch.setattr(clients, "_get", fake_get)
    return seen


def params_of(url: str) -> dict:
    return dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(url).query))


class TestRequestAssembly:
    def test_coordinates_go_as_x_lng_and_y_lat(self, captured):
        """카카오는 경도를 x, 위도를 y 로 받는다. 바꿔 보내면 엉뚱한 결과가 온다."""
        clients.search_places("카페", lat=37.4783, lng=126.9516)
        p = params_of(captured[0])
        assert p["x"] == "126.9516"
        assert p["y"] == "37.4783"

    def test_no_coordinates_means_no_x_y(self, captured):
        clients.search_places("카페")
        p = params_of(captured[0])
        assert "x" not in p and "y" not in p

    def test_distance_sort_needs_coordinates(self, captured):
        """좌표 없이 거리순을 보내면 카카오가 400 을 준다.

        요청을 거부하지 않고 정확도순으로 내린다 — 위치 권한이 없다고 검색
        자체를 막을 이유가 없다. 대신 무엇이 적용됐는지 응답에 적는다.
        """
        payload, _ = clients.search_places("카페", sort=clients.SORT_DISTANCE)
        assert "sort" not in params_of(captured[0])
        assert payload["sort"] == clients.SORT_ACCURACY

    def test_distance_sort_with_coordinates_is_applied(self, captured):
        payload, _ = clients.search_places(
            "카페", lat=37.4783, lng=126.9516, sort=clients.SORT_DISTANCE
        )
        assert params_of(captured[0])["sort"] == clients.SORT_DISTANCE
        assert payload["sort"] == clients.SORT_DISTANCE

    def test_size_is_clamped_to_kakao_limit(self, captured):
        """16 이상은 400 이다."""
        clients.search_places("카페", size=99)
        assert params_of(captured[0])["size"] == str(clients.MAX_PAGE_SIZE)

    def test_page_is_clamped(self, captured):
        clients.search_places("카페", page=0)
        assert params_of(captured[0])["page"] == "1"
        captured.clear()
        clients.search_places("카페", page=9999)
        assert params_of(captured[0])["page"] == str(clients.MAX_PAGE)

    def test_blank_query_does_not_call_kakao(self, captured):
        payload, degraded = clients.search_places("   ")
        assert captured == []
        assert payload["results"] == []
        assert degraded is False


class TestRect:
    """지도 영역 재검색.

    순서는 `minLng,minLat,maxLng,maxLat` 다. 뒤바꿔 보내면 카카오가 에러 없이
    0건을 주므로 여기서 걸러야 한다.
    """

    GOOD = "126.94,37.46,126.96,37.49"

    def test_valid_rect_is_passed_through(self, captured):
        clients.search_places("카페", rect=self.GOOD)
        assert params_of(captured[0])["rect"] == self.GOOD

    @pytest.mark.parametrize(
        "bad,why",
        [
            ("37.46,126.94,37.49,126.96", "위경도를 뒤바꿨다"),
            ("126.96,37.46,126.94,37.49", "min 이 max 보다 크다"),
            ("126.94,37.49,126.96,37.46", "위도 min 이 max 보다 크다"),
            ("126.94,37.46,126.96", "값이 셋뿐이다"),
            ("126.94,37.46,126.96,37.49,1", "값이 다섯이다"),
            ("a,b,c,d", "숫자가 아니다"),
            ("200,37.46,201,37.49", "경도 범위를 벗어났다"),
            ("126.94,95,126.96,96", "위도 범위를 벗어났다"),
        ],
    )
    def test_bad_rect_is_dropped_not_sent(self, captured, bad, why):
        clients.search_places("카페", rect=bad)
        assert "rect" not in params_of(captured[0]), why

    def test_dropping_rect_still_returns_results(self, captured):
        """rect 가 틀렸다고 검색을 실패시키지 않는다. 범위만 무시한다."""
        payload, degraded = clients.search_places("카페", rect="쓰레기")
        assert degraded is False
        assert len(payload["results"]) == 1


class TestResponseShape:
    def test_item_carries_what_the_screen_needs(self, captured):
        payload, _ = clients.search_places("레드포스", lat=37.4783, lng=126.9516)
        item = payload["results"][0]

        assert item["name"] == "레드포스PC"
        assert item["lat"] == pytest.approx(37.48007602)
        assert item["lng"] == pytest.approx(126.95214188)
        # 도로명을 먼저 쓴다. 없는 지역은 지번으로 떨어진다.
        assert item["address"] == "서울 관악구 봉천로 12"
        assert item["jibun_address"] == "서울 관악구 봉천동 1685-1"
        assert item["distance_m"] == 203
        assert item["phone"] == "070-8666-6554"
        # 평점·사진은 로컬 API 에 없다. 이 링크가 그것을 보여 주는 유일한 곳이다.
        assert item["place_url"].startswith("http")

    def test_category_group_falls_back_to_last_segment(self, captured):
        """`category_group_name` 은 자주 빈다. PC방이 그렇다(실측).

        비었을 때 칩이 빈칸으로 남으면 업종을 알 수 없다. 전체 경로의 마지막
        조각을 쓴다.
        """
        payload, _ = clients.search_places("레드포스")
        assert payload["results"][0]["category_group"] == "게임방,PC방"

    def test_category_group_prefers_the_group_name(self):
        assert clients.short_category(
            {"category_group_name": "카페", "category_name": "음식점 > 카페 > 테마카페"}
        ) == "카페"

    def test_category_group_is_blank_when_nothing_is_given(self):
        assert clients.short_category({}) == ""

    def test_missing_distance_becomes_none(self, monkeypatch):
        """좌표를 안 보내면 카카오가 `distance` 를 빈 문자열로 준다."""
        monkeypatch.setattr(
            clients, "_get",
            lambda url: ({"documents": [{**DOC, "distance": ""}], "meta": META}, False),
        )
        payload, _ = clients.search_places("레드포스")
        assert payload["results"][0]["distance_m"] is None

    def test_items_without_coordinates_are_skipped(self, monkeypatch):
        monkeypatch.setattr(
            clients, "_get",
            lambda url: (
                {"documents": [{**DOC, "x": None, "y": None}, DOC], "meta": META},
                False,
            ),
        )
        payload, _ = clients.search_places("레드포스")
        assert len(payload["results"]) == 1

    def test_reachable_count_is_separate_from_total(self, captured):
        """`total_count` 는 14만이 나올 수 있는데 받아 볼 수 있는 것은 45건이다.

        화면에 total_count 를 쓰면 45건에서 끝나는 목록 옆에 14만이 적힌다.
        """
        payload, _ = clients.search_places("카페")
        assert payload["total_count"] == 136
        assert payload["reachable_count"] == 45
        assert payload["is_end"] is False

    def test_degraded_returns_empty_payload(self, monkeypatch):
        monkeypatch.setattr(clients, "_get", lambda url: (None, True))
        payload, degraded = clients.search_places("카페")
        assert degraded is True
        assert payload["results"] == []
        assert payload["is_end"] is True


class TestStaticMapRequest:
    @pytest.fixture
    def sent(self, monkeypatch):
        seen: list[str] = []

        class FakeResponse:
            headers = {"Content-Type": "image/png"}

            def read(self):
                return b"\x89PNG fake"

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        def fake_urlopen(req, **kw):
            seen.append(req.full_url)
            return FakeResponse()

        monkeypatch.setattr(clients.urllib.request, "urlopen", fake_urlopen)
        monkeypatch.setattr(clients.settings, "KAKAO_REST_API_KEY", "test-key")
        return seen

    def test_markers_repeat_the_parameter(self, sent):
        """마커 여러 개는 `markers` 를 반복해서 보낸다.

        한 값 안에서 `&` 로 이어 붙이면 쿼리 구분자로 먹혀 **첫 마커만**
        그려진다(실측). 200 이 오고 이미지도 정상이라 조용히 틀린다.
        """
        clients.static_map(
            lat=37.4783, lng=126.9516, level=5, width=360, height=500,
            markers=[(37.48, 126.95), (37.49, 126.96), (37.47, 126.94)],
        )
        query = urllib.parse.urlsplit(sent[0]).query
        pairs = urllib.parse.parse_qsl(query)
        markers = [v for k, v in pairs if k == "markers"]
        assert len(markers) == 3
        assert markers[0] == "location:126.95,37.48"

    def test_markers_are_capped_at_five(self, sent):
        clients.static_map(
            lat=37.4783, lng=126.9516, level=5, width=360, height=500,
            markers=[(37.0 + i / 100, 127.0) for i in range(9)],
        )
        pairs = urllib.parse.parse_qsl(urllib.parse.urlsplit(sent[0]).query)
        assert len([v for k, v in pairs if k == "markers"]) == clients.STATIC_MAP_MAX_MARKERS

    def test_center_is_lng_comma_lat(self, sent):
        clients.static_map(lat=37.4783, lng=126.9516, level=5, width=360, height=500)
        p = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(sent[0]).query))
        assert p["center"] == "126.9516,37.4783"

    def test_size_is_clamped(self, sent):
        clients.static_map(lat=37.0, lng=127.0, level=5, width=9999, height=9999)
        p = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(sent[0]).query))
        assert p["size"] == f"{clients.STATIC_MAP_MAX_W}x{clients.STATIC_MAP_MAX_H}"

    def test_level_is_clamped(self, sent):
        clients.static_map(lat=37.0, lng=127.0, level=99, width=360, height=500)
        p = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(sent[0]).query))
        assert p["lv"] == str(clients.STATIC_MAP_MAX_LEVEL)

    def test_missing_key_is_degraded_not_an_exception(self, monkeypatch):
        monkeypatch.setattr(clients.settings, "KAKAO_REST_API_KEY", "")
        body, ctype, degraded = clients.static_map(
            lat=37.0, lng=127.0, level=5, width=360, height=500
        )
        assert body is None and degraded is True


class TestScale:
    """줌 레벨당 거리. 실측값이라 바뀌면 재검색 범위가 화면과 어긋난다."""

    def test_base_level_is_one_meter_per_unit(self):
        assert clients.meters_per_unit(clients.STATIC_MAP_BASE_LEVEL) == 1.0

    def test_each_level_doubles(self):
        for lv in range(clients.STATIC_MAP_BASE_LEVEL, 12):
            assert clients.meters_per_unit(lv + 1) == pytest.approx(
                clients.meters_per_unit(lv) * 2
            )

    def test_below_base_level_does_not_zoom_further(self):
        """lv 1~4 는 모두 1 m/단위였다(실측). 더 확대되지 않는다."""
        for lv in (1, 2, 3, 4):
            assert clients.meters_per_unit(lv) == 1.0

    def test_measured_points_match(self):
        # calibrate_staticmap.py 가 실제로 잰 값: lv7 = 8.03, lv10 = 64.15
        assert clients.meters_per_unit(7) == pytest.approx(8.0, abs=0.1)
        assert clients.meters_per_unit(10) == pytest.approx(64.0, abs=0.5)
