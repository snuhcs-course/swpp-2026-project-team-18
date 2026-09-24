"""경로 폴리라인 추출·길이 계산.

## 왜 수단마다 따로 시험하는가

세 API 가 좌표를 **서로 다른 모양**으로 준다. 2026-09-25 실측(jit-tools/
probe_geom_modes.py)에서 확인한 것이다.

    대중교통  routes[].steps[].path.points        [[lng, lat], ...]
    도보·자전거 route.legs[].steps[].path.points   [[lng, lat], ...]  ← legs 한 단계 더
    자동차    routes[].sections[].roads[].vertexes [lng, lat, lng, lat, ...]  ← 평평함

하나로 보고 짜면 조용히 실패한다. 도보는 `legs` 를 건너뛰면 빈 배열이 나오고,
자동차는 평평한 배열을 쌍으로 읽어 좌표 절반이 경도 자리에 들어간다. 둘 다
에러 없이 경로선만 사라지거나 엉뚱한 곳에 그려진다.
"""

from __future__ import annotations

import math

import pytest

from apps.routing import clients


# --- 좌표 순서 --------------------------------------------------------------


def test_경도_위도_순서를_뒤집어_담는다():
    # 카카오는 [경도, 위도] 로 준다. 그대로 쓰면 서울이 아니라 바다가 된다.
    out = clients._pairs_to_latlng([[126.9297, 37.4842], [127.0280, 37.4980]])
    assert out == [[37.4842, 126.9297], [37.498, 127.028]]


def test_좌표가_망가진_항목은_건너뛴다():
    out = clients._pairs_to_latlng([[126.9, 37.4], None, [1], "x", {"x": 127.0, "y": 37.5}])
    assert out == [[37.4, 126.9], [37.5, 127.0]]


# --- 수단별 모양 ------------------------------------------------------------


def test_대중교통은_steps_의_path_를_이어_붙인다():
    route = {
        "steps": [
            {"path": {"points": [[126.90, 37.40], [126.91, 37.41]]}},
            {"path": {"points": [[126.92, 37.42]]}},
        ]
    }
    assert clients.transit_route_path(route) == [
        [37.40, 126.90], [37.41, 126.91], [37.42, 126.92],
    ]


def test_도보는_legs_단계를_거친다():
    # **legs 를 빼먹으면 빈 배열이 된다.** 대중교통과 같은 모양으로 착각한
    # 코드가 정확히 그렇게 실패한다.
    data = {
        "route": {
            "legs": [
                {"steps": [{"path": {"points": [[126.90, 37.40], [126.91, 37.41]]}}]},
                {"steps": [{"path": {"points": [[126.92, 37.42]]}}]},
            ]
        }
    }
    assert clients.legs_route_path(data) == [
        [37.40, 126.90], [37.41, 126.91], [37.42, 126.92],
    ]


def test_도보_모양을_대중교통처럼_읽으면_비어_있다():
    # 회귀 방지. legs 를 건너뛴 구현이 들어오면 이 단언이 깨진다.
    data = {"route": {"legs": [{"steps": [{"path": {"points": [[126.9, 37.4]]}}]}]}}
    assert clients.transit_route_path(data) == []
    assert clients.legs_route_path(data) == [[37.4, 126.9]]


def test_자동차는_평평한_배열을_둘씩_끊어_읽는다():
    data = {
        "routes": [
            {
                "sections": [
                    {"roads": [
                        {"vertexes": [126.90, 37.40, 126.91, 37.41]},
                        {"vertexes": [126.92, 37.42]},
                    ]},
                ]
            }
        ]
    }
    assert clients.car_route_path(data) == [
        [37.40, 126.90], [37.41, 126.91], [37.42, 126.92],
    ]


def test_자동차_좌표가_홀수개면_남는_값을_버린다():
    # 짝이 맞지 않는 마지막 값을 위도 없이 쓰면 0 이 들어가 경로가 적도로 간다.
    data = {"routes": [{"sections": [{"roads": [{"vertexes": [126.90, 37.40, 126.91]}]}]}]}
    assert clients.car_route_path(data) == [[37.40, 126.90]]


# --- 솎아내기 ---------------------------------------------------------------


def test_상한_아래면_그대로_둔다():
    pts = [[37.0 + i * 0.001, 127.0] for i in range(10)]
    assert clients._decimate(pts, cap=10) == pts


def test_상한을_넘으면_고르게_솎고_끝점을_남긴다():
    pts = [[37.0 + i * 0.0001, 127.0] for i in range(1000)]
    out = clients._decimate(pts, cap=100)

    assert len(out) == 100
    # 끝점을 잃으면 경로선이 목적지 앞에서 끊기고 진행률 분모도 짧아진다.
    assert out[0] == pts[0]
    assert out[-1] == pts[-1]


# --- 길이 -------------------------------------------------------------------


def test_경로_길이는_점_사이를_누적한다():
    # 위도 0.01도 ≈ 1113m. 두 구간이면 그 두 배다.
    pts = [[37.50, 127.0], [37.51, 127.0], [37.52, 127.0]]
    length = clients.path_length_m(pts)

    expected = 2 * 0.01 * clients.METERS_PER_DEGREE
    assert abs(length - expected) < 2, f"{length} vs {expected}"


def test_경도_길이는_위도에_따라_줄어든다():
    # 서울(위도 37.5)에서 경도 1도는 111320 * cos(37.5) ≈ 88,300m 다.
    pts = [[37.5, 127.0], [37.5, 128.0]]
    length = clients.path_length_m(pts)

    expected = clients.METERS_PER_DEGREE * math.cos(math.radians(37.5))
    assert abs(length - expected) / expected < 0.001


def test_점이_하나_이하면_길이는_0():
    assert clients.path_length_m([]) == 0
    assert clients.path_length_m([[37.5, 127.0]]) == 0


def test_돌아가는_경로는_직선거리보다_길다():
    # 진행률의 분모가 직선거리면 경로를 따라 갈 때 100%를 넘는다.
    detour = [[37.50, 127.00], [37.52, 127.00], [37.52, 127.02], [37.50, 127.02]]
    straight = [[37.50, 127.00], [37.50, 127.02]]

    assert clients.path_length_m(detour) > clients.path_length_m(straight) * 2


# --- 목록 응답을 무겁게 만들지 않는다 -----------------------------------------


def test_후보_목록은_폴리라인을_담지_않는다(monkeypatch):
    """`with_path` 기본값이 False 여야 한다.

    후보는 최대 15개이고 하나가 300점을 넘는다. 목록에 전부 실으면 경로를
    고르기만 해도 수십 KB를 내려받는다.
    """
    raw = {
        "status": "OK",
        "routes": [
            {
                "properties": {
                    "totalTime": 1200, "totalDistance": 10000,
                    "transfers": 0, "fare": {"value": 1550},
                },
                "steps": [
                    {
                        "properties": {
                            "type": "SUBWAY", "time": 1200,
                            "vehicles": [{"name": "2호선", "type": "SUBWAY"}],
                            "stops": [{"name": "신림"}, {"name": "강남"}],
                        },
                        "path": {"points": [[126.93, 37.48], [127.02, 37.49]]},
                    }
                ],
            }
        ],
    }
    monkeypatch.setattr(clients, "_get", lambda url: (raw, False))

    lean, _ = clients._transit_candidates(37.48, 126.93, 37.49, 127.02)
    assert lean, "후보가 있어야 한다"
    assert "path" not in lean[0]
    assert "path_distance_m" not in lean[0]

    full, _ = clients._transit_candidates(37.48, 126.93, 37.49, 127.02, with_path=True)
    assert full[0]["path"] == [[37.48, 126.93], [37.49, 127.02]]
    assert full[0]["path_distance_m"] == clients.path_length_m(full[0]["path"])


def test_보관한_좌표의_길이를_쓴다_카카오_신고값이_아니다(monkeypatch):
    """분모는 `totalDistance` 가 아니라 보관한 점들의 길이다.

    점을 솎아 내면 보관한 좌표가 더 짧다. 카카오 값을 분모로 쓰면 목적지에
    닿았는데도 진행률이 100%에 못 미친다.
    """
    raw = {
        "status": "OK",
        "routes": [
            {
                # 일부러 실제 좌표 길이와 크게 다른 값을 신고한다.
                "properties": {"totalTime": 600, "totalDistance": 99999, "transfers": 0},
                "steps": [
                    {
                        "properties": {
                            "type": "SUBWAY", "time": 600,
                            "vehicles": [{"name": "2호선", "type": "SUBWAY"}],
                        },
                        "path": {"points": [[127.0, 37.50], [127.0, 37.51]]},
                    }
                ],
            }
        ],
    }
    monkeypatch.setattr(clients, "_get", lambda url: (raw, False))

    items, _ = clients._transit_candidates(37.50, 127.0, 37.51, 127.0, with_path=True)

    assert items[0]["distance_m"] == 99999      # 표시용은 카카오 값 그대로
    assert items[0]["path_distance_m"] != 99999  # 분모는 실제 좌표 길이
    assert abs(items[0]["path_distance_m"] - 1113) < 3
