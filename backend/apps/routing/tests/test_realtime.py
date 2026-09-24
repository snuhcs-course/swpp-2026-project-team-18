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
    #
    # ``subwayNm`` 이 ``None`` 인 것은 오타가 아니다. 2026-09-24 재실측
    # (8개 역 80행, jit-tools/probe_subway.py)에서 이 필드는 **모든 행에서
    # null** 이었다. 전에는 여기에 "2호선" 이 적혀 있었고, 그래서 이 테스트가
    # 통과하는 동안에도 실제로는 한 번도 타지 않는 이름 비교 경로만 검사했다.
    # 버스 픽스처에서 이미 같은 방식으로 당했다(아래 버스 절 주석 참고).
    rows = [
        {
            "subwayId": "1002", "subwayNm": None,
            "trainLineNm": "성수행 - 봉천방면", "barvlDt": "210",
            "btrainNo": "2201", "ordkey": "02002성수0",
        },
        {
            "subwayId": "1094", "subwayNm": None,
            "trainLineNm": "샛강행 - 당곡방면", "barvlDt": "80",
            "btrainNo": "9401",
        },
        {
            "subwayId": "1002", "subwayNm": None,
            "trainLineNm": "신도림행 - 신대방방면", "barvlDt": "50",
            "btrainNo": "2202",
        },
        {
            "subwayId": "1002", "subwayNm": None,
            "trainLineNm": "성수행 - 봉천방면", "barvlDt": "110",
            "btrainNo": "2203",
        },
        {
            "subwayId": "1002", "subwayNm": None,
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


# --- 지하철 급행·막차 -------------------------------------------------------
#
# 아래 픽스처의 `btrainSttus`·`lstcarAt` 값은 2026-09-24 실측이다
# (노량진·부천·당산, jit-tools/probe_subway.py). 문서는 `btrainSttus` 를
# 0 특급 / 1 급행 / 2 ITX / 3 일반 **코드**로 적어 놓았지만 실제로는 "일반"·"급행"
# 한국어 문자열이 온다. `lstcarAt` 은 "0"/"1" 이다.


def test_지하철_급행과_막차를_표시한다(monkeypatch):
    rows = [
        {
            "subwayId": "1002", "subwayNm": None,
            "trainLineNm": "성수행 - 봉천방면 (급행)", "barvlDt": "110",
            "btrainNo": "2201", "btrainSttus": "급행", "lstcarAt": "0",
        },
        {
            "subwayId": "1002", "subwayNm": None,
            "trainLineNm": "성수행 - 봉천방면 (막차)", "barvlDt": "210",
            "btrainNo": "2202", "btrainSttus": "일반", "lstcarAt": "1",
        },
    ]
    monkeypatch.setattr(realtime, "_subway_rows", lambda station: rows)

    arrivals = realtime.subway_arrivals(subway_segment())["arrivals"]

    assert [a["train_kind"] for a in arrivals] == ["급행", None]
    assert [a["last_train"] for a in arrivals] == [False, True]
    # 방면 문자열에 (급행)·(막차) 가 붙어도 방향 판정이 깨지지 않아야 한다.
    assert [a["seconds"] for a in arrivals] == [110, 210]


def test_지하철_일반_열차에는_등급을_붙이지_않는다(monkeypatch):
    rows = [{
        "subwayId": "1002", "subwayNm": None,
        "trainLineNm": "성수행 - 봉천방면", "barvlDt": "110",
        "btrainNo": "2201", "btrainSttus": "일반", "lstcarAt": "0",
    }]
    monkeypatch.setattr(realtime, "_subway_rows", lambda station: rows)

    arrival = realtime.subway_arrivals(subway_segment())["arrivals"][0]

    assert arrival["train_kind"] is None
    assert arrival["last_train"] is False


def test_지하철_등급_필드가_없어도_방면_접미로_급행을_잡는다(monkeypatch):
    # btrainSttus 가 문서대로 숫자로 바뀌거나 빠져도 (급행) 접미는 남는다.
    rows = [{
        "subwayId": "1002", "subwayNm": None,
        "trainLineNm": "성수행 - 봉천방면 (급행)", "barvlDt": "110",
        "btrainNo": "2201", "btrainSttus": "1",
    }]
    monkeypatch.setattr(realtime, "_subway_rows", lambda station: rows)

    assert realtime.subway_arrivals(subway_segment())["arrivals"][0]["train_kind"] == "급행"


def test_지하철_모르는_등급값은_지어내지_않는다(monkeypatch):
    # 어느 근거도 급행이라고 하지 않으면 라벨을 만들지 않는다. 숫자 코드를
    # 추측해 매핑했다가 문서가 틀리면 사용자에게 거짓을 보여 준다.
    rows = [{
        "subwayId": "1002", "subwayNm": None,
        "trainLineNm": "성수행 - 봉천방면", "barvlDt": "110",
        "btrainNo": "2201", "btrainSttus": "0",
    }]
    monkeypatch.setattr(realtime, "_subway_rows", lambda station: rows)

    assert realtime.subway_arrivals(subway_segment())["arrivals"][0]["train_kind"] is None


def test_지하철_급행을_목록에서_걸러내지_않는다(monkeypatch):
    # 정차 패턴이 응답에 없어 급행이 하차역을 지나치는지 알 수 없다. 숨기면
    # 더 빠른 선택지를 말없이 빼앗고, 지나친다고 단정하면 거짓말이 된다.
    rows = [
        {
            "subwayId": "1002", "subwayNm": None,
            "trainLineNm": "성수행 - 봉천방면 (급행)", "barvlDt": "60",
            "btrainNo": "2201", "btrainSttus": "급행",
        },
        {
            "subwayId": "1002", "subwayNm": None,
            "trainLineNm": "성수행 - 봉천방면", "barvlDt": "300",
            "btrainNo": "2202", "btrainSttus": "일반",
        },
    ]
    monkeypatch.setattr(realtime, "_subway_rows", lambda station: rows)

    arrivals = realtime.subway_arrivals(subway_segment())["arrivals"]

    assert len(arrivals) == 2
    assert arrivals[0]["train_kind"] == "급행"


def test_지하철_막차는_lstcarAt만_와도_참이다(monkeypatch):
    # 방면 문자열에 (막차) 가 없는 응답도 실측에 있었다. 막차를 놓치면
    # 사용자가 집에 못 가므로 한쪽 근거만으로도 참으로 본다.
    rows = [{
        "subwayId": "1002", "subwayNm": None,
        "trainLineNm": "성수행 - 봉천방면", "barvlDt": "110",
        "btrainNo": "2201", "lstcarAt": "1",
    }]
    monkeypatch.setattr(realtime, "_subway_rows", lambda station: rows)

    assert realtime.subway_arrivals(subway_segment())["arrivals"][0]["last_train"] is True


# --- 버스 -------------------------------------------------------------------
#
# 아래 픽스처의 필드 이름은 **실제 getStationByUid / getStationByPos 응답에서
# 그대로 옮긴 것**이다. 지어내면 안 된다.
#
# 한동안 키가 401 이라 이 코드가 실데이터로 돌아본 적이 없었고, 그 사이 픽스처가
# `exps1`·`brerde_Div1`·(getStationByPos 의) `stNm` 처럼 다른 오퍼레이션
# (getArrInfoByRouteAll)의 이름으로 쓰여 있었다. 테스트는 전부 통과했지만 승인이
# 난 뒤에도 도착정보가 하나도 붙지 않았다. 필드 이름을 실측으로 고정한다.
#
#   getStationByPos  정류소 이름 = stationNm
#   getStationByUid  정류소 이름 = stNm · 노선 = rtNm/busRouteAbrv
#                    초 ETA = traTime1/2 · 문구 = arrmsg1/2
#                    혼잡도 = congestion1/2 (3 여유 / 4 보통 / 5 혼잡)
#                    배차간격(분) = term
#   arrmsgSec1/2 는 이름과 달리 초가 아니라 arrmsg 와 같은 문구다.


def test_버스는_다음과_그다음_도착초와_혼잡도를_읽는다(monkeypatch):
    monkeypatch.setattr(realtime, "_bus_station_ars", lambda *args: "21275")
    monkeypatch.setattr(
        realtime,
        "_bus_station_rows",
        lambda ars: [
            {
                "busRouteAbrv": "5513",
                "traTime1": "99", "traTime2": "777",
            },
            {
                "busRouteAbrv": "5511",
                "traTime1": "536", "traTime2": "930",
                "arrmsg1": "8분56초후[3번째 전]",
                "arrmsg2": "15분30초후[7번째 전]",
                "arrmsgSec1": "8분56초후[3번째 전]",
                "arrmsgSec2": "15분30초후[7번째 전]",
                "congestion1": "3",
                "congestion2": "5",
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
            "rtNm": "5511", "traTime1": "", "traTime2": "",
            "arrmsg1": "1분10초후[1번째 전]", "arrmsg2": "13분30초후[6번째 전]",
        }],
    )

    result = realtime.bus_arrivals(bus_segment())

    assert [a["seconds"] for a in result["arrivals"]] == [70, 810]


def test_traTime이_0이면_문구로_되돌린다(monkeypatch):
    """실측에서 '곧 도착' 은 traTime 이 5 초였지만 0 으로 오는 차도 있다."""
    monkeypatch.setattr(realtime, "_bus_station_ars", lambda *args: "21275")
    monkeypatch.setattr(
        realtime,
        "_bus_station_rows",
        lambda ars: [{
            "rtNm": "5511", "traTime1": "0", "traTime2": "0",
            "arrmsg1": "곧 도착", "arrmsg2": "3분후[2번째 전]",
        }],
    )

    result = realtime.bus_arrivals(bus_segment())

    assert [a["seconds"] for a in result["arrivals"]] == [0, 180]


def test_운행종료는_도착으로_세지_않는다(monkeypatch):
    """실측: 새벽 노선은 arrmsg 가 '운행종료' 이고 term 도 0 이다."""
    monkeypatch.setattr(realtime, "_bus_station_ars", lambda *args: "21275")
    monkeypatch.setattr(
        realtime,
        "_bus_station_rows",
        lambda ars: [{
            "rtNm": "5511", "traTime1": "0", "traTime2": "0",
            "arrmsg1": "운행종료", "arrmsg2": "운행종료", "term": "0",
        }],
    )

    assert realtime.bus_arrivals(bus_segment()) == {}


def test_출발대기도_도착으로_세지_않는다(monkeypatch):
    monkeypatch.setattr(realtime, "_bus_station_ars", lambda *args: "21275")
    monkeypatch.setattr(
        realtime,
        "_bus_station_rows",
        lambda ars: [{
            "rtNm": "5511", "traTime1": "0",
            "arrmsg1": "출발대기", "arrmsg2": "출발대기",
        }],
    )

    assert realtime.bus_arrivals(bus_segment()) == {}


def test_혼잡도가_0이면_담지_않는다(monkeypatch):
    """congestion 은 0 과 빈 값이 '정보 없음'이다."""
    monkeypatch.setattr(realtime, "_bus_station_ars", lambda *args: "21275")
    monkeypatch.setattr(
        realtime,
        "_bus_station_rows",
        lambda ars: [{
            "rtNm": "5511", "traTime1": "120", "traTime2": "600",
            "congestion1": "0", "congestion2": "",
        }],
    )

    result = realtime.bus_arrivals(bus_segment())

    assert [a["crowding"] for a in result["arrivals"]] == [None, None]


def test_버스_노선이_다르면_도착정보를_붙이지_않는다(monkeypatch):
    monkeypatch.setattr(realtime, "_bus_station_ars", lambda *args: "21275")
    monkeypatch.setattr(
        realtime,
        "_bus_station_rows",
        lambda ars: [{"busRouteAbrv": "5513", "traTime1": "60"}],
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
    """getStationByPos 의 정류소 이름 필드는 stationNm 이다(stNm 이 아니다)."""
    root = ET.fromstring(
        """
        <ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader><msgBody>
          <itemList><arsId>99999</arsId><stationNm>관악구청</stationNm><dist>10</dist></itemList>
          <itemList><arsId>21276</arsId><stationNm>제2공학관</stationNm><dist>80</dist></itemList>
          <itemList><arsId>21275</arsId><stationNm>제2공학관</stationNm><dist>20</dist></itemList>
        </msgBody></ServiceResult>
        """
    )
    monkeypatch.setattr(realtime, "_bus_xml", lambda *args, **kwargs: root)

    assert realtime._bus_station_ars("제2공학관", 37.44, 126.95) == "21275"


def test_버스_정류소_이름이_안_맞으면_가까워도_고르지_않는다(monkeypatch):
    root = ET.fromstring(
        """
        <ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader><msgBody>
          <itemList><arsId>99999</arsId><stationNm>관악구청</stationNm><dist>1</dist></itemList>
        </msgBody></ServiceResult>
        """
    )
    monkeypatch.setattr(realtime, "_bus_xml", lambda *args, **kwargs: root)

    assert realtime._bus_station_ars("제2공학관", 37.44, 126.95) is None


def test_실제_getStationByPos_응답에서_ARS를_뽑는다(monkeypatch):
    """실측 응답을 그대로 넣는다. 필드 이름이 바뀌면 여기서 먼저 깨진다.

    2026-09-23 신림역 좌표(37.484267, 126.929745) radius=200 응답의 일부다.
    같은 이름의 정류소가 여러 개 있어 dist 로 갈라야 한다.
    """
    root = ET.fromstring(
        """
        <ServiceResult><msgHeader><headerCd>0</headerCd>
          <headerMsg>정상적으로 처리되었습니다.</headerMsg></msgHeader><msgBody>
          <itemList><stationId>120900192</stationId><stationNm>신림역4번출구</stationNm>
            <arsId>21916</arsId><dist>82</dist>
            <gpsX>126.92886</gpsX><gpsY>37.484025</gpsY></itemList>
          <itemList><stationId>120000421</stationId><stationNm>신림사거리.신림역</stationNm>
            <arsId>21350</arsId><dist>130</dist>
            <gpsX>126.9282759755</gpsX><gpsY>37.4841991011</gpsY></itemList>
          <itemList><stationId>120000018</stationId><stationNm>신림사거리.신림역</stationNm>
            <arsId>21117</arsId><dist>156</dist>
            <gpsX>126.9280626243</gpsX><gpsY>37.483817804</gpsY></itemList>
          <itemList><stationId>120000048</stationId><stationNm>신림동별빛거리입구</stationNm>
            <arsId>21149</arsId><dist>175</dist>
            <gpsX>126.9296319572</gpsX><gpsY>37.4858478867</gpsY></itemList>
        </msgBody></ServiceResult>
        """
    )
    monkeypatch.setattr(realtime, "_bus_xml", lambda *args, **kwargs: root)

    assert realtime._bus_station_ars("신림사거리.신림역", 37.484267, 126.929745) == "21350"
    assert realtime._bus_station_ars("신림동별빛거리입구", 37.484267, 126.929745) == "21149"


def test_실제_getStationByUid_응답에서_도착정보를_뽑는다(monkeypatch):
    """실측 응답을 그대로 넣는다. arsId 21350 의 643 번이다.

    2026-09-23 측정값: arrmsg1='2분후[1번째 전]' 인데 traTime1=188 이다.
    문구는 분 단위로 내림하므로 초 단위는 traTime 을 써야 한다.
    """
    root = ET.fromstring(
        """
        <ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader><msgBody>
          <itemList><stId>120000421</stId><stNm>신림사거리.신림역</stNm><arsId>21350</arsId>
            <rtNm>500</rtNm><busRouteAbrv>500</busRouteAbrv>
            <arrmsg1>2분후[1번째 전]</arrmsg1><arrmsg2>18분후[11번째 전]</arrmsg2>
            <arrmsgSec1>2분후[1번째 전]</arrmsgSec1><arrmsgSec2>18분후[11번째 전]</arrmsgSec2>
            <traTime1>177</traTime1><traTime2>1094</traTime2>
            <congestion1>3</congestion1><congestion2>4</congestion2>
            <term>9</term></itemList>
          <itemList><stId>120000421</stId><stNm>신림사거리.신림역</stNm><arsId>21350</arsId>
            <rtNm>643</rtNm><busRouteAbrv>643</busRouteAbrv>
            <arrmsg1>2분후[1번째 전]</arrmsg1><arrmsg2>9분후[5번째 전]</arrmsg2>
            <traTime1>188</traTime1><traTime2>584</traTime2>
            <congestion1>4</congestion1><congestion2>3</congestion2>
            <term>10</term></itemList>
        </msgBody></ServiceResult>
        """
    )
    monkeypatch.setattr(realtime, "_bus_station_ars", lambda *args: "21350")
    monkeypatch.setattr(realtime, "_bus_xml", lambda *args, **kwargs: root)

    result = realtime.bus_arrivals(
        bus_segment(vehicle="643", stops=["신림사거리.신림역", "진흥아파트"])
    )

    assert [a["seconds"] for a in result["arrivals"]] == [188, 584]
    assert [a["message"] for a in result["arrivals"]] == [
        "3분 8초 뒤 도착",
        "9분 44초 뒤 도착",
    ]
    assert [a["crowding"] for a in result["arrivals"]] == ["보통", "여유"]
    assert result["headway_minutes"] == 10


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
