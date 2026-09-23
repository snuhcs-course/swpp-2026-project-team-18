"""버스·지하철 실시간 도착정보 테스트.

실제 API 를 부르지 않는다. 키와 일일 쿼터를 쓰지 않으면서도, 실제 응답에서
확인한 필드 모양을 fixture 로 고정한다.

가장 중요한 규칙은 두 가지다.

1. 한 역에서 **노선과 방향을 모두** 맞춘다. 신림역에는 2호선·신림선과
   양방향 열차가 함께 오므로 하나만 맞추면 반대 방향 시각을 보여 준다.
2. 외부 API 가 실패해도 경로 후보는 남는다. 실시간은 보조 정보다.
"""

from __future__ import annotations

from copy import deepcopy
import urllib.error
import xml.etree.ElementTree as ET

import pytest
from django.core.cache import cache

from apps.routing import clients, realtime


@pytest.fixture(autouse=True)
def clear_cache():
    cache.clear()
    yield
    cache.clear()


def subway_segment(**updates):
    value = {
        "kind": "subway",
        "region": "metro_seoul",
        "vehicle": "2호선",
        "stops": ["신림", "봉천", "서울대입구(관악구청)"],
    }
    value.update(updates)
    return value


def bus_segment(**updates):
    value = {
        "kind": "bus",
        "region": "metro_seoul",
        "vehicle": "5511",
        "vehicle_type": "지선",
        "stops": ["제2공학관", "관악구청"],
        "boarding_lat": 37.44877463,
        "boarding_lng": 126.952058,
    }
    value.update(updates)
    return value


# --- 사람이 읽는 시간 -------------------------------------------------------


@pytest.mark.parametrize(
    ("seconds", "label"),
    [
        (0, "곧 도착"),
        (7, "7초 뒤 도착"),
        (60, "1분 뒤 도착"),
        (110, "1분 50초 뒤 도착"),
        (210, "3분 30초 뒤 도착"),
        (-5, "곧 도착"),
    ],
)
def test_초를_도착_문구로_바꾼다(seconds, label):
    assert realtime.format_arrival_seconds(seconds) == label


def test_사람용_문구에서_초를_복구한다():
    assert realtime._seconds_from_message("8분56초후[3번째 전]") == 536
    assert realtime._seconds_from_message("3분 30초 후") == 210
    assert realtime._seconds_from_message("40초 후") == 40
    assert realtime._seconds_from_message("곧 도착") == 0
    assert realtime._seconds_from_message("전역 출발") is None


# --- 지하철 -----------------------------------------------------------------


def test_지하철은_노선과_방향을_모두_맞추고_두_대만_고른다(monkeypatch):
    # 2026-09-23 신림역 실측 응답 모양. 2호선·신림선과 양방향이 섞인다.
    rows = [
        {
            "subwayId": "1002", "subwayNm": "2호선",
            "trainLineNm": "성수행 - 봉천방면", "barvlDt": "210",
            "btrainNo": "2201", "ordkey": "02002성수0",
        },
        {
            "subwayId": "1094", "subwayNm": "신림선",
            "trainLineNm": "샛강행 - 당곡방면", "barvlDt": "80",
            "btrainNo": "9401",
        },
        {
            "subwayId": "1002", "subwayNm": "2호선",
            "trainLineNm": "신도림행 - 신대방방면", "barvlDt": "50",
            "btrainNo": "2202",
        },
        {
            "subwayId": "1002", "subwayNm": "2호선",
            "trainLineNm": "성수행 - 봉천방면", "barvlDt": "110",
            "btrainNo": "2203",
        },
        {
            "subwayId": "1002", "subwayNm": "2호선",
            "trainLineNm": "성수행 - 봉천방면", "barvlDt": "500",
            "btrainNo": "2204",
        },
    ]
    monkeypatch.setattr(realtime, "_subway_rows", lambda station: rows)

    result = realtime.subway_arrivals(subway_segment())

    assert [a["seconds"] for a in result["arrivals"]] == [110, 210]
    assert [a["message"] for a in result["arrivals"]] == [
        "1분 50초 뒤 도착",
        "3분 30초 뒤 도착",
    ]
    assert all(a["source"] == "seoul_subway" for a in result["arrivals"])


def test_지하철_방향을_확인할_수_없으면_아무것도_보이지_않는다(monkeypatch):
    rows = [{
        "subwayId": "1002", "subwayNm": "2호선",
        "trainLineNm": "성수행", "barvlDt": "110", "btrainNo": "2201",
    }]
    monkeypatch.setattr(realtime, "_subway_rows", lambda station: rows)

    # "봉천" 이 방면 문자열에 없다. 반대 방향일 수 있으므로 실패를 닫는다.
    assert realtime.subway_arrivals(subway_segment()) == {}


def test_지하철_노선을_이름이_없어도_ID로_맞춘다(monkeypatch):
    rows = [{
        "subwayId": "1002", "subwayNm": "",
        "trainLineNm": "성수행 - 봉천방면", "barvlDt": "110", "btrainNo": "2201",
    }]
    monkeypatch.setattr(realtime, "_subway_rows", lambda station: rows)

    assert realtime.subway_arrivals(subway_segment())["arrivals"][0]["seconds"] == 110


def test_지하철_숫자가_0이면_문구에서_초를_복구한다(monkeypatch):
    rows = [{
        "subwayId": "1002", "subwayNm": "2호선",
        "trainLineNm": "성수행 - 봉천방면", "barvlDt": "0",
        "arvlMsg2": "3분 30초 후", "btrainNo": "2201",
    }]
    monkeypatch.setattr(realtime, "_subway_rows", lambda station: rows)

    assert realtime.subway_arrivals(subway_segment())["arrivals"][0]["seconds"] == 210


def test_지하철은_서울_밖에서_서울_API를_부르지_않는다(monkeypatch):
    monkeypatch.setattr(
        realtime,
        "_subway_rows",
        lambda station: pytest.fail("부산 노선을 서울 API 로 조회했다"),
    )

    assert realtime.subway_arrivals(subway_segment(region="metro_busan")) == {}


def test_역_이름에서_괄호와_역을_없앤다():
    assert realtime._station_token("서울대입구(관악구청)역") == "서울대입구"
    assert realtime._station_token("신림역") == "신림"


# --- 버스 -------------------------------------------------------------------


def test_버스는_다음과_그다음_도착초와_혼잡도를_읽는다(monkeypatch):
    monkeypatch.setattr(realtime, "_bus_station_ars", lambda *args: "21275")
    monkeypatch.setattr(
        realtime,
        "_bus_station_rows",
        lambda ars: [
            {
                "busRouteAbrv": "5513",
                "exps1": "99", "exps2": "777",
            },
            {
                "busRouteAbrv": "5511",
                "exps1": "536", "exps2": "930",
                "arrmsg1": "8분56초후[3번째 전]",
                "arrmsg2": "15분30초후[7번째 전]",
                "brerde_Div1": "4", "brdrde_Num1": "3",
                "brerde_Div2": "4", "brdrde_Num2": "5",
                "term": "12",
            },
        ],
    )

    result = realtime.bus_arrivals(bus_segment())

    assert [a["seconds"] for a in result["arrivals"]] == [536, 930]
    assert [a["message"] for a in result["arrivals"]] == [
        "8분 56초 뒤 도착",
        "15분 30초 뒤 도착",
    ]
    assert [a["crowding"] for a in result["arrivals"]] == ["여유", "혼잡"]
    assert result["headway_minutes"] == 12


def test_버스_초가_비면_arrmsg를_쓴다(monkeypatch):
    monkeypatch.setattr(realtime, "_bus_station_ars", lambda *args: "21275")
    monkeypatch.setattr(
        realtime,
        "_bus_station_rows",
        lambda ars: [{
            "rtNm": "5511", "exps1": "", "exps2": "",
            "arrmsg1": "1분10초후[1번째 전]", "arrmsg2": "13분30초후[6번째 전]",
        }],
    )

    result = realtime.bus_arrivals(bus_segment())

    assert [a["seconds"] for a in result["arrivals"]] == [70, 810]


def test_버스_노선이_다르면_도착정보를_붙이지_않는다(monkeypatch):
    monkeypatch.setattr(realtime, "_bus_station_ars", lambda *args: "21275")
    monkeypatch.setattr(
        realtime,
        "_bus_station_rows",
        lambda ars: [{"busRouteAbrv": "5513", "exps1": "60"}],
    )

    assert realtime.bus_arrivals(bus_segment(vehicle="5511")) == {}


def test_버스는_서울_밖에서_서울_API를_부르지_않는다(monkeypatch):
    monkeypatch.setattr(
        realtime,
        "_bus_station_ars",
        lambda *args: pytest.fail("부산 버스를 서울 API 로 조회했다"),
    )

    assert realtime.bus_arrivals(bus_segment(region="metro_busan")) == {}


def test_버스_정류소는_이름이_맞는_가장_가까운_것을_고른다(monkeypatch):
    root = ET.fromstring(
        """
        <ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader><msgBody>
          <itemList><arsId>99999</arsId><stNm>관악구청</stNm><dist>10</dist></itemList>
          <itemList><arsId>21276</arsId><stNm>제2공학관</stNm><dist>80</dist></itemList>
          <itemList><arsId>21275</arsId><stNm>제2공학관</stNm><dist>20</dist></itemList>
        </msgBody></ServiceResult>
        """
    )
    monkeypatch.setattr(realtime, "_bus_xml", lambda *args, **kwargs: root)

    assert realtime._bus_station_ars("제2공학관", 37.44, 126.95) == "21275"


def test_버스_정류소_이름이_안_맞으면_가까워도_고르지_않는다(monkeypatch):
    root = ET.fromstring(
        """
        <ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader><msgBody>
          <itemList><arsId>99999</arsId><stNm>관악구청</stNm><dist>1</dist></itemList>
        </msgBody></ServiceResult>
        """
    )
    monkeypatch.setattr(realtime, "_bus_xml", lambda *args, **kwargs: root)

    assert realtime._bus_station_ars("제2공학관", 37.44, 126.95) is None


def test_버스_키는_Encoding_다음_Decoding을_쓴다(settings):
    settings.SEOUL_BUS_API_KEY_ENCODING = "ENCODED%2BKEY"
    settings.SEOUL_BUS_API_KEY_DECODING = "decoded+key"

    assert realtime._bus_keys() == ["ENCODED%2BKEY", "decoded%2Bkey"]


def test_버스_두_키가_같으면_한_번만_시도한다(settings):
    settings.SEOUL_BUS_API_KEY_ENCODING = "abcdef"
    settings.SEOUL_BUS_API_KEY_DECODING = "abcdef"

    assert realtime._bus_keys() == ["abcdef"]


def test_버스_Encoding_키가_401이면_Decoding으로_재시도한다(monkeypatch, settings):
    settings.SEOUL_BUS_API_KEY_ENCODING = "bad"
    settings.SEOUL_BUS_API_KEY_DECODING = "good+key"
    calls = []

    def fetch(url):
        calls.append(url)
        if "serviceKey=bad" in url:
            raise urllib.error.HTTPError(url, 401, "Unauthorized", {}, None)
        return b"<ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader></ServiceResult>"

    monkeypatch.setattr(realtime, "_fetch_bytes", fetch)

    assert realtime._bus_xml("stationinfo/getStationByUid", {"arsId": "21275"}) is not None
    assert len(calls) == 2
    assert "serviceKey=good%2Bkey" in calls[1]


# --- 후보 연결 --------------------------------------------------------------


def test_같은_역_노선_방향은_한_번만_조회한다(monkeypatch):
    calls = []

    def fake(segment):
        calls.append(segment)
        return {"arrivals": [{"seconds": 100, "message": "1분 40초 뒤 도착"}]}

    monkeypatch.setattr(realtime, "subway_arrivals", fake)
    candidates = [
        {"segments": [subway_segment()]},
        {"segments": [deepcopy(subway_segment())]},
    ]

    realtime.enrich_candidates(candidates)

    assert len(calls) == 1
    assert candidates[0]["segments"][0]["arrivals"][0]["seconds"] == 100
    assert candidates[1]["segments"][0]["arrivals"][0]["seconds"] == 100
    # 후보 간 같은 list 를 공유하면 한쪽 수정이 다른 후보를 오염시킨다.
    assert candidates[0]["segments"][0]["arrivals"] is not candidates[1]["segments"][0]["arrivals"]


def test_실시간_실패가_경로_후보를_지우지_않는다(monkeypatch):
    def broken(segment):
        raise RuntimeError("외부 API 중단")

    monkeypatch.setattr(realtime, "subway_arrivals", broken)
    candidates = [{"key": "transit:2호선", "segments": [subway_segment()]}]
    before = deepcopy(candidates)

    realtime.enrich_candidates(candidates)

    assert candidates == before


def test_route_candidates가_최종_후보에_실시간을_붙인다(monkeypatch):
    transit = {
        "key": "transit:2호선", "kind": "transit", "mode": "지하철",
        "minutes": 20, "distance_m": 5000, "transfers": 0, "fare": 1500,
        "detail": "2호선", "summary": "20분 · 5.0km · 1,500원",
        "source": "kakao_transit", "reason": "", "segments": [subway_segment()],
    }
    monkeypatch.setattr(clients, "_single_route_candidate", lambda *args, **kwargs: None)
    monkeypatch.setattr(clients, "_transit_candidates", lambda *args, **kwargs: ([transit], False))
    monkeypatch.setattr(clients, "_car_candidate", lambda *args, **kwargs: None)

    def enrich(items):
        items[0]["segments"][0]["arrivals"] = [{"seconds": 60}]

    monkeypatch.setattr(realtime, "enrich_candidates", enrich)

    items, degraded = clients.route_candidates(37.4, 126.9, 37.5, 127.0)

    assert degraded is False
    assert items[0]["segments"][0]["arrivals"] == [{"seconds": 60}]


def test_키가_없으면_외부를_호출하지_않는다(monkeypatch, settings):
    settings.SEOUL_SUBWAY_API_KEY = ""
    settings.SEOUL_BUS_API_KEY_ENCODING = ""
    settings.SEOUL_BUS_API_KEY_DECODING = ""
    monkeypatch.setattr(
        realtime,
        "_fetch_bytes",
        lambda url: pytest.fail("키가 없는데 외부 API 를 불렀다"),
    )

    assert realtime._subway_rows("신림") is None
    assert realtime._bus_xml("stationinfo/getStationByUid", {"arsId": "21275"}) is None
