"""고른 경로를 지키면서 "지금 더 빠른 대안" 을 함께 알린다.

## 왜 고른 경로를 바꾸지 않는가

사용자는 일정을 만들 때 수단을 직접 고른다. 배경 갱신이 그것을 더 빠른 쪽으로
갈아치우면, 고른 이유(환승이 싫다·그 노선은 앉아서 간다·버스는 멀미가 난다)를
서버가 알 수 없으므로 매번 무너뜨린다. 그래서 계산 기준은 고른 수단으로 두고,
더 빠른 쪽은 지도에 겹쳐 보여 주기만 한다.

## 왜 추가 호출이 없는가

`resolve_route` 는 대중교통 후보 목록을 **한 번** 받아 그 안에서 고른 key 를
찾는다. 최단 후보는 이미 같은 응답에 있으므로 꺼내 쓰면 된다. 대안을 얻으려고
`best_route` 를 덧붙이면 갱신 한 번에 카카오 호출이 1회에서 3회로 늘어 하루
쿼터를 세 배로 태운다. 아래 `호출_한_번` 단언들이 그 선을 지킨다.

## 왜 절대 소요시간이 아니라 차이만 담는가

계획의 `travel_minutes` 는 학습 보정과 tau 분위수를 거친 값이고, 후보의
`minutes` 는 카카오 원값이다. 화면에서 두 수를 나란히 놓으면 기준이 다른
비교가 된다. 차이만 **원값끼리** 계산해 담는다.
"""

from __future__ import annotations

import pytest

from apps.routing import clients


def _subway_step(line: str, minutes: int, points: list[list[float]]) -> dict:
    return {
        "properties": {
            "type": "SUBWAY",
            "time": minutes * 60,
            "vehicles": [{"name": line, "type": "SUBWAY"}],
            "stops": [{"name": "신림"}, {"name": "강남"}],
        },
        "path": {"points": points},
    }


def _transit_route(minutes: int, lines: list[str], points: list[list[float]]) -> dict:
    """노선 하나당 step 하나. 두 개 넘기면 key 가 `transit:A>B` 가 된다."""
    per = max(1, minutes // len(lines))
    return {
        "properties": {
            "totalTime": minutes * 60,
            "totalDistance": 10000,
            "transfers": len(lines) - 1,
            "fare": {"value": 1550},
        },
        "steps": [_subway_step(line, per, points) for line in lines],
    }


# 카카오는 `[경도, 위도]` 로 주고 우리는 `[위도, 경도]` 로 보관한다. 입력과
# 기대값을 따로 적어 그 뒤집기가 대안 경로에도 적용되는지 함께 확인한다.
CHOSEN_POINTS = [[126.93, 37.48], [127.02, 37.49]]
FASTER_POINTS = [[126.93, 37.48], [126.98, 37.52], [127.02, 37.49]]
FASTER_PATH = [[37.48, 126.93], [37.52, 126.98], [37.49, 127.02]]


def _stub(monkeypatch, raw: dict) -> dict:
    """`_get` 을 막고 호출 횟수를 센다."""
    calls = {"n": 0}

    def fake_get(url):
        calls["n"] += 1
        return raw, False

    monkeypatch.setattr(clients, "_get", fake_get)
    return calls


@pytest.fixture
def two_routes(monkeypatch):
    raw = {
        "status": "OK",
        "routes": [
            _transit_route(20, ["2호선"], CHOSEN_POINTS),
            _transit_route(16, ["9호선"], FASTER_POINTS),
        ],
    }
    return _stub(monkeypatch, raw)


# --- 대안을 붙인다 ----------------------------------------------------------


def test_고른_경로보다_빠른_후보가_있으면_붙인다(two_routes):
    item, degraded = clients.resolve_route(
        "transit:2호선", 37.48, 126.93, 37.49, 127.02
    )

    assert not degraded
    # 고른 경로가 그대로 계산 기준이다.
    assert item["key"] == "transit:2호선"
    assert item["minutes"] == 20

    alt = item["alternative"]
    assert alt["key"] == "transit:9호선"
    assert alt["label"] == "9호선"
    assert alt["faster_minutes"] == 4
    assert alt["path"] == FASTER_PATH


def test_대안을_붙여도_호출은_한_번이다(two_routes):
    clients.resolve_route("transit:2호선", 37.48, 126.93, 37.49, 127.02)
    assert two_routes["n"] == 1, "대안 때문에 카카오를 더 부르면 쿼터가 늘어난다"


def test_환승_경로의_이름은_화살표로_읽힌다(monkeypatch):
    raw = {
        "status": "OK",
        "routes": [
            _transit_route(30, ["2호선"], CHOSEN_POINTS),
            _transit_route(21, ["9호선", "2호선"], FASTER_POINTS),
        ],
    }
    _stub(monkeypatch, raw)

    item, _ = clients.resolve_route("transit:2호선", 37.48, 126.93, 37.49, 127.02)

    # key 는 `>` 로 잇고 화면은 `→` 로 읽는다. `detail` 을 쓰면 "(대체 3개)" 가
    # 따라붙어 한 줄 라벨이 지저분해진다.
    assert item["alternative"]["key"] == "transit:9호선>2호선"
    assert item["alternative"]["label"] == "9호선 → 2호선"


# --- 대안을 붙이지 않는다 ---------------------------------------------------


def test_고른_경로가_이미_최단이면_붙이지_않는다(two_routes):
    item, _ = clients.resolve_route("transit:9호선", 37.48, 126.93, 37.49, 127.02)

    assert item["key"] == "transit:9호선"
    assert "alternative" not in item, "자기보다 빠른 자기를 알릴 수는 없다"


def test_후보가_하나면_붙이지_않는다(monkeypatch):
    raw = {"status": "OK", "routes": [_transit_route(20, ["2호선"], CHOSEN_POINTS)]}
    _stub(monkeypatch, raw)

    item, _ = clients.resolve_route("transit:2호선", 37.48, 126.93, 37.49, 127.02)
    assert "alternative" not in item


def test_같은_소요시간이면_붙이지_않는다(monkeypatch):
    # 1분이라도 빨라야 알린다. 0분 차이를 "더 빠름" 으로 알리면 거짓말이다.
    raw = {
        "status": "OK",
        "routes": [
            _transit_route(20, ["2호선"], CHOSEN_POINTS),
            _transit_route(20, ["9호선"], FASTER_POINTS),
        ],
    }
    _stub(monkeypatch, raw)

    item, _ = clients.resolve_route("transit:2호선", 37.48, 126.93, 37.49, 127.02)
    assert "alternative" not in item


def test_고른_경로가_사라지면_최단으로_대체하고_대안은_없다(two_routes):
    # 대체한 것이 곧 최단이므로 "더 빠른 쪽" 이 없다. 대체 사실은
    # `route_choice_honored` 가 따로 알린다.
    item, degraded = clients.resolve_route(
        "transit:사라진노선", 37.48, 126.93, 37.49, 127.02
    )

    assert not degraded
    assert item["key"] == "transit:9호선"
    assert "alternative" not in item


def test_후보_목록이_없는_수단은_대안을_내지_않는다(monkeypatch):
    """자동차·도보·자전거는 한 번에 하나만 조회한다.

    비교할 목록이 없으므로 대안을 얻으려면 호출을 더 해야 한다. 그 값이
    쿼터보다 크지 않다고 판단했다.
    """
    raw = {
        "routes": [
            {
                "summary": {
                    "duration": 900,
                    "distance": 8000,
                    "fare": {"taxi": 12000},
                },
                "sections": [{"roads": [{"vertexes": [126.93, 37.48, 127.02, 37.49]}]}],
            }
        ]
    }
    calls = _stub(monkeypatch, raw)

    item, degraded = clients.resolve_route("car", 37.48, 126.93, 37.49, 127.02)

    assert not degraded
    assert "alternative" not in item
    assert calls["n"] == 1


# --- 이름 변환 --------------------------------------------------------------


@pytest.mark.parametrize(
    "key,expected",
    [
        ("transit:9호선", "9호선"),
        ("transit:9호선>2호선", "9호선 → 2호선"),
        ("transit:간선:472>2호선", "간선:472 → 2호선"),  # 노선명에 콜론이 들어간다
        ("walk", ""),
        ("", ""),
    ],
)
def test_대중교통_key_에서_노선_이름을_뽑는다(key, expected):
    assert clients._transit_label(key) == expected
