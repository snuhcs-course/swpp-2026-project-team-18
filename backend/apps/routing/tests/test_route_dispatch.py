"""고른 경로를 다시 조회하는 `resolve_route` 의 수단별 분기, 그리고 좌표 → 주소.

대중교통 분기와 "더 빠른 대안" 은 `test_route_alternative.py` 가 본다. 여기서는
나머지 분기가 **맞는 카카오 API 를 한 번만** 부르고, 경로가 사라졌을 때 예외 대신
`degraded` 를 돌려 호출부가 기본 경로로 되돌릴 수 있는지를 본다.
"""

from __future__ import annotations

import pytest

from apps.routing import clients

START_END = (37.48, 126.93, 37.49, 127.02)


def single_route(seconds=1200, distance=1500):
    """도보·자전거 응답 모양. `route` 단수 + `legs`."""
    return {
        "status": "OK",
        "route": {
            "properties": {"totalTime": seconds, "totalDistance": distance},
            "legs": [{"steps": [{"path": {"points": [[126.93, 37.48], [127.02, 37.49]]}}]}],
        },
    }


@pytest.fixture
def kakao(monkeypatch):
    """`_get` 자리. URL 앞부분으로 답을 고르고 부른 URL 을 모은다."""

    class Fake:
        def __init__(self):
            self.urls = []
            self.replies = {}

        def __call__(self, url):
            self.urls.append(url)
            for prefix, reply in self.replies.items():
                if url.startswith(prefix):
                    return reply
            return None, True

    fake = Fake()
    monkeypatch.setattr(clients, "_get", fake)
    return fake


class TestResolveRouteByMode:
    @pytest.mark.parametrize(
        ("route_key", "url", "mode"),
        [("walk", clients.WALK_URL, "도보"), ("bicycle", clients.BICYCLE_URL, "자전거")],
    )
    def test_single_mode_routes_call_their_own_api_once(self, kakao, route_key, url, mode):
        kakao.replies[url] = (single_route(), False)

        item, degraded = clients.resolve_route(route_key, *START_END)

        assert degraded is False
        assert (item["key"], item["mode"], item["minutes"]) == (route_key, mode, 20)
        # 알람 지도에 그릴 경로를 함께 받는다(추가 호출 없이).
        assert item["path"]
        assert len(kakao.urls) == 1 and kakao.urls[0].startswith(url)

    @pytest.mark.parametrize("route_key", ["walk", "bicycle", "car"])
    def test_vanished_route_is_degraded_so_the_caller_can_fall_back(self, kakao, route_key):
        assert clients.resolve_route(route_key, *START_END) == (None, True)

    def test_route_without_duration_is_degraded(self, kakao):
        kakao.replies[clients.WALK_URL] = (single_route(seconds=0), False)

        assert clients.resolve_route("walk", *START_END) == (None, True)

    def test_transit_failure_is_degraded(self, kakao):
        assert clients.resolve_route("transit:2호선", *START_END) == (None, True)

    def test_unknown_key_calls_nothing(self, kakao):
        assert clients.resolve_route("teleport", *START_END) == (None, True)
        assert kakao.urls == []


class TestCoordToAddress:
    """출발지 기본값(현재 위치)을 사람이 읽는 주소로 바꾼다."""

    def reply(self, kakao, documents):
        kakao.replies[clients.COORD_TO_ADDRESS_URL] = ({"documents": documents}, False)

    def test_building_name_wins_and_coordinates_go_as_x_lng_y_lat(self, kakao):
        self.reply(kakao, [{
            "road_address": {"address_name": "서울 관악구 관악로 1", "building_name": "서울대학교"},
            "address": {"address_name": "서울 관악구 신림동 56-1"},
        }])

        item, degraded = clients.coord_to_address(37.4601, 126.952)

        assert degraded is False
        assert item == {
            "kakao_place_id": None,
            "name": "서울대학교",
            "address": "서울 관악구 관악로 1",
            "lat": 37.4601,
            "lng": 126.952,
            "category": "",
        }
        assert "x=126.952" in kakao.urls[0] and "y=37.4601" in kakao.urls[0]

    def test_jibun_is_used_where_there_is_no_road_address(self, kakao):
        self.reply(kakao, [{"road_address": None, "address": {"address_name": "경기 양평군 옥천면 1"}}])

        item, _ = clients.coord_to_address(37.5, 127.5)

        assert item["name"] == item["address"] == "경기 양평군 옥천면 1"

    def test_document_without_any_address_is_still_named(self, kakao):
        self.reply(kakao, [{}])

        item, _ = clients.coord_to_address(37.5, 127.5)

        assert (item["name"], item["address"]) == ("현재 위치", "")

    def test_sea_or_abroad_is_no_address_not_a_failure(self, kakao):
        """바다·국외 좌표는 문서가 빈다. 실패로 보면 앱이 "다시 시도" 를 띄운다."""
        self.reply(kakao, [])

        assert clients.coord_to_address(35.0, 129.5) == (None, False)

    def test_kakao_failure_is_degraded(self, kakao):
        assert clients.coord_to_address(37.5, 127.0) == (None, True)
