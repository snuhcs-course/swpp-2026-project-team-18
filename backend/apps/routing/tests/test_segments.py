"""경로 구간 쪼개기 테스트.

앱이 이 값으로 가로 막대를 그린다. 여기서 고정하는 가장 중요한 규칙은
**구간 시간의 합이 총 소요시간과 같다**는 것이다. 어긋나면 막대 길이와
카드 제목의 "25분" 이 서로 다른 말을 하게 되고, 사용자는 둘 중 어느 것도
믿지 못한다.

카카오 응답의 실제 모양은 `jit-tools/probe_segments.py` 로 확인했다
(2026-09-23 실측). `steps` 에는 탑승 구간과 환승 도보만 들어 있고 앞뒤
도보는 빠져 있다 — 25분 경로에서 9분 30초가 비었다.
"""

from __future__ import annotations

from apps.routing.clients import (
    WALK_METERS_PER_MINUTE,
    _haversine_meters,
    _segments,
    _step_endpoints,
)

# 실측에서 쓴 좌표. 제2공학관 → 프라비다2.
START = (37.4488769043997, 126.952647714416)
END = (37.479678885301404, 126.95045416056102)


def bus_step(seconds, name="5511", bus_type="지선", points=None):
    return {
        "properties": {
            "type": "BUS",
            "time": seconds,
            "distance": 3810,
            "vehicles": [{"name": name, "type": bus_type}],
        },
        "path": {"points": points or [[126.952058, 37.44877463], [126.95245697, 37.47795634]]},
    }


def subway_step(seconds, name="2호선", points=None):
    return {
        "properties": {
            "type": "SUBWAY",
            "time": seconds,
            "distance": 5000,
            "vehicles": [{"name": name, "type": ""}],
        },
        "path": {"points": points or [[126.9520, 37.4487], [126.9524, 37.4779]]},
    }


def walk_step(seconds, points=None):
    return {
        "properties": {"type": "WALKING", "time": seconds, "distance": 259},
        "path": {"points": points or [[126.9521, 37.4600], [126.9523, 37.4610]]},
    }


def seg(route, total):
    return _segments(route, total, START[0], START[1], END[0], END[1])


# --- 합이 총시간과 같다 (가장 중요한 불변식) --------------------------------


def test_구간_합이_총시간과_같다():
    # 실측 경로 1: 버스 912초 하나, 총 1482초. 570초가 비어 있다.
    route = {"steps": [bus_step(912)]}
    parts = seg(route, 1482)

    assert sum(p["seconds"] for p in parts) == 1482


def test_환승이_있어도_합이_맞는다():
    route = {"steps": [bus_step(420, "5516"), walk_step(263), bus_step(316, "750B", "간선")]}
    parts = seg(route, 1986)

    assert sum(p["seconds"] for p in parts) == 1986


def test_남는_시간이_없으면_구간이_그대로다():
    route = {"steps": [bus_step(600)]}
    parts = seg(route, 600)

    assert [p["seconds"] for p in parts] == [600]
    assert sum(p["seconds"] for p in parts) == 600


def test_총시간이_구간합보다_작아도_음수가_생기지_않는다():
    # 이런 응답이 올 이유는 없지만, 오면 막대가 뒤집힌다.
    route = {"steps": [bus_step(900)]}
    parts = seg(route, 600)

    assert all(p["seconds"] > 0 for p in parts)


# --- 구간의 종류와 이름 -----------------------------------------------------


def test_버스는_노선명과_종류를_담는다():
    route = {"steps": [bus_step(912, "5511", "지선")]}
    bus = [p for p in seg(route, 1482) if p["kind"] == "bus"]

    assert len(bus) == 1
    assert bus[0]["vehicle"] == "5511"
    # 색을 가르는 값이다. 지선은 녹색, 간선은 파란색이 된다.
    assert bus[0]["vehicle_type"] == "지선"
    assert bus[0]["label"] == "5511"


def test_간선버스도_종류가_구분된다():
    route = {"steps": [bus_step(316, "750B", "간선")]}
    bus = [p for p in seg(route, 500) if p["kind"] == "bus"][0]

    assert bus["vehicle_type"] == "간선"


def test_지하철은_노선명이_이름이다():
    route = {"steps": [subway_step(600, "2호선")]}
    sub = [p for p in seg(route, 900) if p["kind"] == "subway"][0]

    assert sub["vehicle"] == "2호선"
    assert sub["label"] == "2호선"


def test_환승_도보는_도보로_남는다():
    route = {"steps": [bus_step(420), walk_step(263), bus_step(316)]}
    parts = seg(route, 1986)
    walks = [p for p in parts if p["kind"] == "walk"]

    # 환승 도보 1개 + 앞뒤 도보. 263초짜리가 그대로 있어야 한다.
    assert 263 in [p["seconds"] for p in walks]
    assert all(p["label"] == "도보" for p in walks)


def test_노선명이_비면_수단_이름으로_대신한다():
    route = {"steps": [{"properties": {"type": "BUS", "time": 600, "vehicles": []}}]}
    bus = [p for p in seg(route, 600) if p["kind"] == "bus"][0]

    assert bus["label"] == "버스"


# --- 앞뒤 도보와 대기 -------------------------------------------------------


def test_앞뒤_도보와_대기가_순서대로_들어간다():
    route = {"steps": [bus_step(912)]}
    kinds = [p["kind"] for p in seg(route, 1482)]

    # 도보(접근) → 대기 → 버스 → 도보(하차 후). 대기는 타기 직전에 있어야 한다.
    assert kinds == ["walk", "wait", "bus", "walk"]


def test_뒤_도보가_앞보다_길다():
    # 실측: 첫 승차점은 출발지에서 52m, 마지막 하차점은 도착지에서 260m.
    # 거리 비율이 그대로 반영돼야 한다.
    route = {"steps": [bus_step(912)]}
    parts = seg(route, 1482)
    walks = [p for p in parts if p["kind"] == "walk"]

    assert len(walks) == 2
    assert walks[1]["seconds"] > walks[0]["seconds"]


def test_좌표가_없으면_남는_시간이_전부_대기가_된다():
    # path 가 없으면 앞뒤 도보 거리를 알 수 없다. 지어내지 않는다.
    route = {"steps": [{"properties": {"type": "BUS", "time": 900, "vehicles": [{"name": "1"}]}}]}
    parts = seg(route, 1200)
    kinds = [p["kind"] for p in parts]

    assert kinds == ["wait", "bus"]
    assert sum(p["seconds"] for p in parts) == 1200


def test_도보_환산이_남는_시간을_넘으면_비율로_줄인다():
    # 남는 시간이 30초인데 거리 환산이 몇 분이면, 그대로 쓰면 합이 총시간을 넘는다.
    route = {"steps": [bus_step(900)]}
    parts = seg(route, 930)

    assert sum(p["seconds"] for p in parts) == 930
    assert all(p["seconds"] >= 0 for p in parts)


# --- 빈 응답 ----------------------------------------------------------------


def test_구간이_없으면_빈_목록이다():
    assert seg({"steps": []}, 1200) == []
    assert seg({}, 1200) == []


def test_시간이_0인_구간은_버린다():
    route = {"steps": [bus_step(0), bus_step(600)]}
    parts = seg(route, 600)

    assert [p["seconds"] for p in parts] == [600]


def test_모르는_수단은_버린다():
    route = {"steps": [{"properties": {"type": "FERRY", "time": 600}}, bus_step(600)]}
    kinds = [p["kind"] for p in seg(route, 600)]

    assert "ferry" not in kinds
    assert kinds == ["bus"]


# --- 좌표 해석 --------------------------------------------------------------


def test_path_points_는_경도_위도_순서다():
    # 뒤집어 읽으면 지구 반대편이 되고 도보 거리가 수천 km 가 된다.
    step = bus_step(600, points=[[126.9520, 37.4487], [126.9524, 37.4779]])
    first, last = _step_endpoints(step)

    assert first == (37.4487, 126.9520)
    assert last == (37.4779, 126.9524)


def test_좌표가_dict_로_와도_읽는다():
    step = {"path": {"points": [{"x": 126.95, "y": 37.44}, {"x": 126.96, "y": 37.48}]}}
    first, last = _step_endpoints(step)

    assert first == (37.44, 126.95)
    assert last == (37.48, 126.96)


def test_path_가_없으면_None_이다():
    assert _step_endpoints({}) == (None, None)
    assert _step_endpoints({"path": {"points": []}}) == (None, None)


def test_거리_계산이_실측과_맞는다():
    # 마지막 하차점 → 도착지. 탐침에서 260m 로 나왔다.
    meters = _haversine_meters(37.47795634, 126.95245697, END[0], END[1])

    assert 230 <= meters <= 290


def test_도보_속도가_상식_범위다():
    # 4.5km/h = 75m/분. 이 값을 크게 바꾸면 앞뒤 도보가 비현실적이 된다.
    assert 60 <= WALK_METERS_PER_MINUTE <= 90
