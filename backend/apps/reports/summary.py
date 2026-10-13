# Partly AI-generated (Claude)
"""주간 리포트 줄글 요약. report-design.md §4-2.

한 주를 3~5문장으로 정리한다 — 정시율과 목표, 지각 원인(준비 초과·이동 변동·늦은
출발), 추세, 구체적인 제안 하나. LLM 이 쓰거나(키가 있을 때) 같은 수치로 만든
**고정 문장(템플릿)** 을 쓴다. 어느 쪽인지는 `summary_source` 로 내린다.

## 보내는 것은 집계 수치뿐이다 (N19)

`build_facts` 가 **고정된 키**로 집계값만 만든다. 제목·장소·주소·좌표·날짜·
시각·일정 id·이메일·닉네임·태그 이름은 넣지 않는다. 문자열 값은 고정된 원인
이름·요일 한 글자·고정 제안 문장뿐이다. 보내는 곳이 이 함수 하나라서 여기 한
곳에서 막고, 테스트가 키 집합과 실제로 보낸 본문을 함께 본다.

## LLM 문장은 검사를 통과해야 쓴다

`check_text` 가 길이·한글·숫자·날짜·"학습" 을 본다. **숫자는 집계값에 있는 것만
통과한다.** 지어낸 숫자가 하나라도 있으면 그 문장을 버리고 템플릿을 쓴다. 템플릿
출력도 같은 검사를 통과한다(테스트로 고정).

## 실패해도 화면은 막히지 않는다

`summary_for` 는 예외를 던지지 않는다. 키가 없거나, 시간이 넘거나, 서버가 오류를
주거나, 검사에 떨어지면 템플릿을 내린다. 장애 동안 요청마다 8초씩 기다리지 않게
실패 종류별로 잠시 부르지 않는다(백오프). 검사에 떨어진 수치는 바뀔 때까지 다시
보내지 않는다.

## 사용자마다, 그리고 서버 전체에 하루 상한이 있다

저장은 (사용자, 주) 한 줄이라 수치가 A→B→A 로 오가면 매번 다시 부른다. 그래서
부르기 직전에 오늘의 호출 수를 세고 넘으면 템플릿을 쓴다. 사용자별
([LLM_USER_DAILY_LIMIT])과 전체(`settings.LLM_GLOBAL_DAILY_LIMIT`) 둘 다 본다. 리포트
GET 은 막지 않는다 — 주 넘기기는 그대로 된다.

호출 수는 DB 의 `LlmCall` 행으로 센다(db-schema.md A5). `/tmp` 캐시로 세면 서버가
잠들거나 재배포할 때 0 부터 다시 센다. (Partly AI-generated (Claude))
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
import time
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation

from django.conf import settings
from django.core.cache import cache
from django.db import DatabaseError, IntegrityError, transaction
from django.utils import timezone

from . import llm
from .models import LlmCall, WeeklySummary
from .services import (
    RESULT_LATE,
    RESULT_UNOBSERVED,
    THIN_ARRIVALS,
    half_up,
    late_minutes,
)

logger = logging.getLogger(__name__)

SOURCE_LLM = "llm"
SOURCE_TEMPLATE = "template"

# 프롬프트를 고치면 올린다. 해시가 바뀌어 저장된 문장을 다시 만든다.
# 2: 지각 0 표현("지각은 없었음")과 한글 수 관용구 금지를 더함.
PROMPT_VERSION = 2

# LLM 을 부르는 가장 오래된 주. 비용 상한이고, 리포트의 90일 범위와도 맞다.
LLM_MAX_WEEKS_BACK = 12

MIN_CHARS = 40
MAX_CHARS = 400

# 백오프. `default` 캐시를 쓴다(prod 는 워커 공용 파일 캐시).
DOWN_KEY = "reports:llm:down"
INVALID_KEY = "reports:llm:invalid:{digest}"
BACKOFF_SHORT_SECONDS = 10 * 60
BACKOFF_LONG_SECONDS = 60 * 60
# 검사에 떨어진 해시는 수치가 바뀔 때까지 쉰다. 같은 입력이면 비슷한 문장이 나오고,
# 그 주를 열 때마다 LLM 을 기다린 뒤 템플릿을 받게 된다. LLM 을 부르는 기간(12주)
# 동안이면 충분하다. 파일 캐시라 재시작·재배포로 비워지면 한 번 더 부를 수 있다.
BACKOFF_INVALID_SECONDS = LLM_MAX_WEEKS_BACK * 7 * 24 * 60 * 60

# 사용자별 하루 LLM 호출 상한. LLM 용 `nlp` 스로틀(30/hour)과 같은 수를 하루 상한으로
# 쓴다. 정상 사용(최근 12주를 한 번씩 열고 수치가 몇 번 바뀜)은 넉넉히 덮는다.
# 서버 전체 상한은 `settings.LLM_GLOBAL_DAILY_LIMIT`(환경변수)다. 둘 다 `LlmCall` 로 센다.
LLM_USER_DAILY_LIMIT = 30
_BACKOFF_SECONDS = {
    llm.BAD_BASE_URL: BACKOFF_LONG_SECONDS,
    llm.HTTP_4XX: BACKOFF_LONG_SECONDS,
    llm.TIMEOUT: BACKOFF_SHORT_SECONDS,
    llm.NETWORK: BACKOFF_SHORT_SECONDS,
    llm.HTTP_429: BACKOFF_SHORT_SECONDS,
    llm.HTTP_5XX: BACKOFF_SHORT_SECONDS,
    llm.BAD_RESPONSE: BACKOFF_SHORT_SECONDS,
}

WEEKDAY_CHARS = "월화수목금토일"

# 원인 이름. 키는 서버 `LatenessCause.label` 이다.
CAUSE_NAMES = {
    "prep_over": "준비 초과",
    "travel_over": "이동 초과",
    "depart_late": "늦은 출발",
    "plan_too_tight": "계획 여유 부족",
}
CAUSE_UNKNOWN = "원인 측정 못 함"
# 측정된 원인의 동률을 가르는 순서.
CAUSE_ORDER = ("travel_over", "depart_late", "prep_over", "plan_too_tight")
# 템플릿 2번 문장의 "… 때문이었고" 앞 말.
CAUSE_REASONS = {
    "준비 초과": "준비가 길어진 것",
    "이동 초과": "이동이 계획보다 오래 걸린 것",
    "늦은 출발": "집에서 늦게 나선 것",
    "계획 여유 부족": "계획의 여유가 부족했던 것",
}
# 원인 이름 → 그 원인의 편차 키(평균을 낼 수 있는 것만).
CAUSE_DEVIATION_KEYS = {
    "준비 초과": "prep_over",
    "이동 초과": "travel_over",
    "늦은 출발": "depart_late",
}

# 템플릿에서 예외가 나면 쓰는 문장. 수치를 말하지 않는다.
FALLBACK = "이 주 기록을 문장으로 정리하지 못했음. 아래 수치를 확인할 것."

# LLM 에 보내는 키. 테스트가 이 집합을 그대로 단언한다 — 키를 더할 때마다
# 개인정보가 섞이지 않는지 리뷰에서 다시 보게 하려는 것이다.
FACT_KEYS = (
    "week_complete",
    "events",
    "arrived",
    "unobserved",
    "on_time",
    "late",
    "on_time_pct",
    "target_pct",
    "target_met",
    "thin_data",
    "median_slack_min",
    "tightest_slack_min",
    "late_max_min",
    "late_max_weekday",
    "late_causes",
    "top_cause",
    "top_cause_count",
    "top_cause_mean_min",
    "prep_over_mean_min",
    "prep_over_n",
    "depart_late_mean_min",
    "depart_late_n",
    "travel_over_mean_min",
    "travel_over_n",
    "travel_over_sd_min",
    "prev_on_time_pct",
    "on_time_change_pp",
    "alarm_events",
    "unobserved_with_alarm",
    "suggestion",
)

SYSTEM_PROMPT = """너는 알람 앱의 주간 리포트를 쓰는 도우미다. 사용자 메시지의 JSON 은 한 사용자의 한 주 기록을 집계한 값이다. 이 값만 근거로 한국어 요약 한 문단을 쓴다.
1. 3~4문장, 300자 이내. 제목·목록·마크다운·이모지 없이 평문 한 문단.
2. 문장은 '~함', '~음', '~임' 으로 끝낸다.
3. 숫자는 JSON 에 있는 값만 그대로 쓰고 모두 아라비아 숫자로 쓴다('두 번' 이 아니라 '2번'). '한 번도'·'한번 더' 처럼 한글 수가 든 관용구도 쓰지 않는다. late 가 0 이면 '지각은 없었음' 이라고 쓴다. 새로 계산하거나 날짜·시각을 만들지 않는다.
4. *_slack_min 은 일정 시작보다 일찍 도착한 분(음수면 늦은 분), *_mean_min 은 계획보다 더 걸리거나 늦은 분(음수면 덜 걸리거나 일찍)이다. late_max_min 은 가장 많이 늦은 분(양수), on_time_change_pp 는 지난주 대비 정시율 차이(%p), unobserved 는 도착 기록이 없는 일정 수, alarm_events 는 알람이 켜진 일정 수, top_cause 는 가장 많은 지각 원인이고 top_cause_count 는 그 건수, suggestion 은 마지막 문장의 근거다.
5. null 은 기록이 없다는 뜻이다. 0 으로 읽지 말고 언급하지 않거나 '기록이 없음' 이라고만 쓴다.
6. 순서: 정시율과 목표 → 지각과 주원인 → 지난주 대비 변화나 계획 대비 차이 중 하나 → 마지막 문장은 suggestion 의 뜻을 바꾸지 않고 다듬은 제안 하나.
7. '학습' 이라는 낱말을 쓰지 않는다. 사용자를 탓하지 않는다. 없는 앱 기능을 말하지 않는다."""

# 검사용 패턴.
_NUMBER = re.compile(r"\d+(?:\.\d+)?")
_HANGUL = re.compile(r"[가-힣]")
# "지각 두 번" 처럼 한글로 쓴 수. 앞 글자가 한글이면 낱말의 일부다("충분한 시간").
_NUMBER_WORD = re.compile(
    r"(?<![가-힣])(?:한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|스무)\s*(?:번|건|회|차례|분|시간)"
)
# 날짜·시각. facts 에 우연히 있는 숫자(10·7·9)로 "10월 7일"·"9시" 를 만들 수 있다.
_DATE_TIME = re.compile(r"\d+\s*(?:월|일|시)|\d{1,2}\s*:\s*\d{2}")


# ---------------------------------------------------------------------------
# 집계값
# ---------------------------------------------------------------------------


def _pct(rate) -> int | None:
    return None if rate is None else half_up(float(rate) * 100)


def _minutes(value) -> int | None:
    return None if value is None else half_up(float(value))


def _slack_minutes(value) -> int | None:
    """여유를 정수 분으로. 지각(음수)이면 지각 분 규칙을 쓴다(최소 1분)."""
    if value is None:
        return None
    value = float(value)
    return -late_minutes(value) if value < 0 else half_up(value)


def build_facts(report: dict) -> dict:
    """주간 리포트에서 LLM 에 보낼 집계값을 만든다. 키는 [FACT_KEYS] 로 고정이다.

    `outcomes` 에서는 `result`·`slack_minutes`·`weekday`·`cause`·`deviation`·
    `alarm_on` 만 읽는다. 제목·시각·id 는 읽지 않는다.
    """
    outcomes = report.get("outcomes") or []
    deviation = report.get("deviation") or {}
    previous = report.get("previous_week") or {}
    arrived = int(report.get("arrived_count") or 0)

    late_rows = [
        o for o in outcomes
        if o.get("result") == RESULT_LATE and o.get("slack_minutes") is not None
    ]
    names = [CAUSE_NAMES.get(o.get("cause"), CAUSE_UNKNOWN) for o in late_rows]
    late_causes: dict[str, int] = {}
    for name in names:
        late_causes[name] = late_causes.get(name, 0) + 1

    # 가장 많이 늦은 일정. 동률이면 먼저 시작한 것(행이 시작 순이고 min 은 첫 값을 고른다).
    worst = min(late_rows, key=lambda o: o["slack_minutes"]) if late_rows else None

    top_cause, top_count, top_mean = _top_cause(late_rows, names)

    def stat(key: str, field: str):
        return (deviation.get(key) or {}).get(field)

    facts = {
        "week_complete": bool(report.get("week_complete")),
        "events": int(report.get("event_count") or 0),
        "arrived": arrived,
        "unobserved": int(report.get("unobserved_count") or 0),
        "on_time": int(report.get("on_time_count") or 0),
        "late": int(report.get("late_count") or 0),
        "on_time_pct": _pct(report.get("on_time_rate")),
        "target_pct": _pct(report.get("target_rate")),
        "target_met": report.get("target_met"),
        "thin_data": 0 < arrived < THIN_ARRIVALS,
        "median_slack_min": _minutes(report.get("median_slack_minutes")),
        "tightest_slack_min": _slack_minutes(report.get("tightest_slack_minutes")),
        "late_max_min": late_minutes(worst["slack_minutes"]) if worst else None,
        "late_max_weekday": _weekday_char(worst.get("weekday")) if worst else None,
        "late_causes": late_causes,
        "top_cause": top_cause,
        "top_cause_count": top_count,
        "top_cause_mean_min": top_mean,
        "prep_over_mean_min": _minutes(stat("prep_over", "mean_minutes")),
        "prep_over_n": int(stat("prep_over", "count") or 0),
        "depart_late_mean_min": _minutes(stat("depart_late", "mean_minutes")),
        "depart_late_n": int(stat("depart_late", "count") or 0),
        "travel_over_mean_min": _minutes(stat("travel_over", "mean_minutes")),
        "travel_over_n": int(stat("travel_over", "count") or 0),
        "travel_over_sd_min": _minutes(stat("travel_over", "sd_minutes")),
        "prev_on_time_pct": _pct(previous.get("on_time_rate")),
        "on_time_change_pp": previous.get("on_time_rate_change_pp"),
        # 출발·도착은 알람을 해제할 때만 기록된다. 알람이 없던 일정의 미기록은
        # 사용자가 고칠 일이 아니므로 제안을 고를 때 따로 센다. 알람 여부를 모르면
        # (null) 켜진 것으로 센다 — 앱의 측정 공백 안내와 같은 기준이다.
        "alarm_events": sum(1 for o in outcomes if o.get("alarm_on") is not False),
        "unobserved_with_alarm": sum(
            1 for o in outcomes
            if o.get("alarm_on") is not False and o.get("result") == RESULT_UNOBSERVED
        ),
    }
    facts["suggestion"] = _suggestion(facts)
    return facts


def _weekday_char(index) -> str | None:
    if isinstance(index, int) and 0 <= index < len(WEEKDAY_CHARS):
        return WEEKDAY_CHARS[index]
    return None


def _top_cause(late_rows: list[dict], names: list[str]) -> tuple[str | None, int | None, int | None]:
    """가장 많은 지각 원인. **측정된 원인 중에서만** 고른다.

    동률이면 이동 초과 → 늦은 출발 → 준비 초과 → 계획 여유 부족 순이다. 측정된
    원인이 없으면 "원인 측정 못 함" 이다. 평균은 그 원인으로 늦은 일정들의 그 값
    평균이고, 계획 여유 부족·측정 못 함은 평균이 없다.
    """
    if not late_rows:
        return None, None, None

    counts = {CAUSE_NAMES[key]: names.count(CAUSE_NAMES[key]) for key in CAUSE_ORDER}
    best = max(counts.values())
    if best == 0:
        return CAUSE_UNKNOWN, names.count(CAUSE_UNKNOWN), None

    top = next(CAUSE_NAMES[key] for key in CAUSE_ORDER if counts[CAUSE_NAMES[key]] == best)
    key = CAUSE_DEVIATION_KEYS.get(top)
    mean = None
    if key is not None:
        values = [
            row["deviation"][key]
            for row, name in zip(late_rows, names)
            if name == top and (row.get("deviation") or {}).get(key) is not None
        ]
        if values:
            mean = half_up(sum(values) / len(values))
    return top, best, mean


def _suggestion(facts: dict) -> str:
    """마지막 문장. 위에서부터 먼저 맞는 것 하나.

    문구는 실제 동작에 맞춘다. 출발·도착은 알람을 해제한 뒤에만 기록되고, 지각이
    기록됐다면 위치 권한은 켜져 있었다 — 그래서 원인을 못 나눈 지각에 "위치
    권한을 켜라" 고 하지 않는다.
    """
    late = facts["late"]
    top = facts["top_cause"]
    tightest = facts["tightest_slack_min"]

    if facts["events"] == 0:
        return "일정을 추가해 두면 다음 주부터 알람과 함께 기록이 쌓임."
    alarm_events = facts["alarm_events"]
    missing = facts["unobserved_with_alarm"]
    if (facts["arrived"] == 0 and missing > 0) or (alarm_events > 0 and missing * 2 >= alarm_events):
        return "위치 권한을 켜 두고 알람을 해제한 뒤 이동하면 출발·도착이 자동으로 기록됨."
    if facts["arrived"] == 0:
        # 기록이 없는 일정이 전부 알람이 없던 일정이다.
        return "출발·도착은 알람을 해제한 뒤에만 기록되니 기록할 일정은 알람을 켜 둘 것."
    if late > 0 and top == "늦은 출발":
        mean = facts["top_cause_mean_min"]
        if mean is not None and mean >= 1:
            return f"다음 주에는 지금보다 {mean}분 먼저 문을 나서 볼 것."
        return "다음 주에는 알람이 정한 출발 시각을 지켜 볼 것."
    if late > 0 and top == "준비 초과":
        return "오래 걸린 준비 항목의 시간 범위를 루틴에서 늘려 두면 알람이 그만큼 앞당겨짐."
    if late > 0 and top == "이동 초과":
        return "이동이 계획보다 길어지는 날이 있었으니 다른 경로를 골라 보거나 몇 분 일찍 나서 볼 것."
    if late > 0 and top == "계획 여유 부족":
        return "계획대로 움직여도 늦은 날이 있었으니 평소 준비 시간을 넉넉히 잡아 알람을 앞당겨 볼 것."
    if late > 0:
        return "원인을 나누려면 출발 시각이 필요하니 집을 나서기 전에 알람을 해제해 둘 것."
    if tightest is not None and tightest < 5:
        return "아슬아슬한 날이 있었으니 지금처럼 출발 시각을 지키는 것이 좋음."
    return "지금 설정을 그대로 유지해도 좋음."


# ---------------------------------------------------------------------------
# 템플릿
# ---------------------------------------------------------------------------


def render_template(facts: dict) -> str:
    """같은 집계값으로 만든 고정 문장. 개요 → 지각 → 하나 → 제안, 2~5문장.

    수치가 같으면 늘 같은 문장이라 저장하지 않는다.
    """
    sentences = [_overview(facts)]
    if facts["arrived"] > 0:
        sentences.append(_lateness(facts))
        extra = _one_more(facts)
        if extra:
            sentences.append(extra)
    sentences.append(facts["suggestion"])

    if not facts["week_complete"]:
        sentences.insert(0, "진행 중인 주라 지금까지 기록만 요약함.")
    return " ".join(sentences)


def _overview(facts: dict) -> str:
    events, arrived, on_time = facts["events"], facts["arrived"], facts["on_time"]
    if events == 0:
        return "이 주에는 지난 일정이 없음."
    if arrived == 0:
        return f"이 주 일정 {events}건 모두 도착 기록이 없어 정시 여부를 채점하지 못했음."
    if facts["thin_data"]:
        return (
            f"이 주 도착을 기록한 {arrived}번 중 {on_time}번 정시 도착했지만 "
            "기록이 적어 비율로 판단하기는 이름."
        )

    head = (
        f"도착을 기록한 {arrived}번 중"
        if events == arrived
        else f"일정 {events}건 중 도착을 기록한 {arrived}번에서"
    )
    met, target = facts["target_met"], facts["target_pct"]
    if met is True:
        tail = f"로 목표 {target}%를 달성했음."
    elif met is False:
        tail = f"로 목표 {target}%에 못 미쳤음."
    else:
        tail = "였음."
    return f"이 주 {head} {on_time}번 정시 도착해 정시율 {facts['on_time_pct']}%{tail}"


def _lateness(facts: dict) -> str:
    late = facts["late"]
    if late == 0:
        tightest = facts["tightest_slack_min"]
        if tightest is not None and tightest < 5:
            if tightest == 0:
                return "지각은 없었지만 가장 아슬아슬한 날은 일정 시작에 딱 맞춰 도착했음."
            return f"지각은 없었지만 가장 아슬아슬한 날은 {tightest}분 전에 도착했음."
        return "지각은 없었음."

    top = facts["top_cause"]
    if top is None or top == CAUSE_UNKNOWN:
        return f"지각 {late}번은 기록이 부족해 원인을 나누지 못했음."

    reason = CAUSE_REASONS[top]
    count = facts["top_cause_count"]
    worst, weekday = facts["late_max_min"], facts["late_max_weekday"]
    if late == 1:
        when = f"{weekday}요일에 " if weekday else ""
        return f"지각 1번은 {reason} 때문이었고 {when}{worst}분 늦었음."
    most = f"가장 많이 늦은 날은 {weekday}요일 {worst}분이었음." if weekday else (
        f"가장 많이 늦은 것은 {worst}분이었음."
    )
    if late == count:
        return f"지각 {late}번은 모두 {reason} 때문이었고 {most}"
    return f"지각 {late}번 중 {count}번은 {reason} 때문이었고 {most}"


def _one_more(facts: dict) -> str | None:
    """지난주 대비 변화, 없으면 계획 대비 차이, 없으면 이동 편차 중 하나."""
    change = facts["on_time_change_pp"]
    if change is not None:
        previous = facts["prev_on_time_pct"]
        if change > 0:
            return f"지난주 {previous}%보다 {change}%p 올랐음."
        if change < 0:
            return f"지난주 {previous}%보다 {abs(change)}%p 내렸음."
        return "지난주와 정시율이 같았음."

    # 2번 이상 쟀고 평균 3분 이상 늘어난 것 중 가장 큰 것. 동률은 이동 → 출발 → 준비.
    candidates = [
        ("travel_over", "이동은 계획보다 평균 {m}분 더 걸렸음({n}번 기록)."),
        ("depart_late", "출발은 계획보다 평균 {m}분 늦었음({n}번 기록)."),
        ("prep_over", "준비는 계획보다 평균 {m}분 더 걸렸음({n}번 기록)."),
    ]
    best = None
    for key, sentence in candidates:
        mean, count = facts[f"{key}_mean_min"], facts[f"{key}_n"]
        if mean is not None and count >= 2 and mean >= 3 and (best is None or mean > best[0]):
            best = (mean, sentence.format(m=mean, n=count))
    if best is not None:
        return best[1]

    sd = facts["travel_over_sd_min"]
    if sd is not None and sd >= 5 and facts["travel_over_n"] >= 3:
        return f"이동 시간 편차가 {sd}분으로 큰 편이었음."
    return None


# ---------------------------------------------------------------------------
# 검사
# ---------------------------------------------------------------------------


def normalize(text: str | None) -> str:
    """앞뒤 따옴표·공백, 마크다운 기호를 지우고 한 줄로 만든다."""
    cleaned = re.sub(r"[*#`]", "", text or "")
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned.strip("\"'“”‘’「」『』").strip()


def _canonical_number(raw: str) -> str:
    """"083" → "83", "13.0" → "13". 비교할 수 있는 모양으로."""
    try:
        value = Decimal(raw)
    except InvalidOperation:
        return raw
    if value == value.to_integral_value():
        return str(int(value))
    return format(value.normalize(), "f")


def allowed_numbers(facts: dict) -> set[str]:
    """LLM 문장에 써도 되는 숫자. facts 의 모든 수(bool 제외)의 절댓값과,
    문자열 값(제안 문장 등) 속 숫자. dict 값(`late_causes` 의 건수)도 재귀로 센다."""
    found: set[str] = set()

    def walk(value) -> None:
        if value is None or isinstance(value, bool):
            return
        if isinstance(value, (int, float)):
            found.add(_canonical_number(str(abs(value))))
        elif isinstance(value, str):
            found.update(_canonical_number(n) for n in _NUMBER.findall(value))
        elif isinstance(value, dict):
            for item in value.values():
                walk(item)
        elif isinstance(value, (list, tuple)):
            for item in value:
                walk(item)

    walk(facts)
    return found


def check_text(text: str, facts: dict) -> str | None:
    """쓸 수 없는 문장이면 이유, 쓸 수 있으면 None. [normalize] 를 거친 문장을 받는다."""
    if not MIN_CHARS <= len(text) <= MAX_CHARS:
        return "length"
    if not _HANGUL.search(text):
        return "no_hangul"
    if "학습" in text:
        return "learning_word"
    if "http" in text.lower():
        return "url"
    if _NUMBER_WORD.search(text):
        return "number_word"
    if _DATE_TIME.search(text):
        return "date_or_time"
    allowed = allowed_numbers(facts)
    for raw in _NUMBER.findall(text):
        number = _canonical_number(raw)
        if number not in allowed:
            return f"invented_number:{number}"
    return None


# ---------------------------------------------------------------------------
# 요약 고르기
# ---------------------------------------------------------------------------


def _week_start(report: dict) -> date | None:
    try:
        return date.fromisoformat(str(report.get("week_start")))
    except ValueError:
        return None


def llm_allowed(report: dict) -> bool:
    """LLM 을 부를 만한 주인가. 키 여부는 보지 않는다(`llm.configured`).

    진행 중인 주, 도착 기록이 [THIN_ARRIVALS] 건 미만인 주, [LLM_MAX_WEEKS_BACK] 주
    보다 오래된 주는 템플릿만 쓴다. 기록이 적은 주에 LLM 이 "목표 달성" 을 쓰면
    판정을 보류한 화면과 문장이 엇갈린다.
    """
    if not report.get("week_complete"):
        return False
    if int(report.get("arrived_count") or 0) < THIN_ARRIVALS:
        return False
    start = _week_start(report)
    if start is None:
        return False
    return start >= timezone.localdate() - timedelta(weeks=LLM_MAX_WEEKS_BACK)


def facts_digest(facts: dict) -> str:
    """같은 프롬프트·모델·집계값이면 같은 해시."""
    payload = json.dumps(facts, ensure_ascii=False, sort_keys=True)
    model = (getattr(settings, "OPENAI_MODEL", "") or "").strip()
    return hashlib.sha256(f"{PROMPT_VERSION}|{model}|{payload}".encode()).hexdigest()


def summary_for(user, report: dict) -> tuple[str, str]:
    """`(문장, "llm" | "template")`. **예외를 던지지 않는다.**"""
    try:
        return _summary_for(user, report)
    except Exception:
        logger.exception("주간 요약을 만들지 못했다. 고정 문장을 쓴다")
        return FALLBACK, SOURCE_TEMPLATE


def _render_safely(facts: dict) -> str:
    try:
        return render_template(facts)
    except Exception:
        logger.exception("요약 템플릿이 실패했다")
        return FALLBACK


def _summary_for(user, report: dict) -> tuple[str, str]:
    facts = build_facts(report)
    template = _render_safely(facts)
    # 키가 없는 동안은 DB 도 보지 않는다. 지금 대부분의 요청이 이 경로다.
    if not llm.configured():
        return template, SOURCE_TEMPLATE

    start = _week_start(report)
    if start is None:
        return template, SOURCE_TEMPLATE
    digest = facts_digest(facts)

    # 저장된 문장이 지금 수치와 같으면 그대로 쓴다. 오래된 주도 마찬가지다 —
    # 수치가 같으니 문장이 틀릴 일이 없고, 열 때마다 문장이 바뀌지 않는다.
    stored = _stored_text(user, start, digest)
    if stored is not None:
        return stored, SOURCE_LLM

    if not llm_allowed(report):
        return template, SOURCE_TEMPLATE
    if cache.get(DOWN_KEY) or cache.get(INVALID_KEY.format(digest=digest)):
        return template, SOURCE_TEMPLATE
    # 실제로 부를 때만 센다. 저장된 문장·백오프로 끝나는 요청은 상한을 쓰지 않는다.
    call = _start_llm_call(user, start, digest)
    if call is None:
        return template, SOURCE_TEMPLATE

    started = time.monotonic()
    text, failure = llm.chat(SYSTEM_PROMPT, json.dumps(facts, ensure_ascii=False))
    if failure is not None:
        _finish_llm_call(call, failure, started)
        seconds = _BACKOFF_SECONDS.get(failure)
        if seconds:
            cache.set(DOWN_KEY, failure, seconds)
        return template, SOURCE_TEMPLATE

    clean = normalize(text)
    reason = check_text(clean, facts)
    if reason is not None:
        _finish_llm_call(call, LlmCall.Outcome.INVALID, started)
        # 같은 수치로 다시 불러도 비슷한 문장이 나온다. 그 해시는 수치가 바뀔 때까지
        # 쉰다(다른 주·다른 수치는 막지 않는다).
        cache.set(INVALID_KEY.format(digest=digest), reason, BACKOFF_INVALID_SECONDS)
        logger.warning("LLM 요약을 쓰지 않는다: %s", reason)
        return template, SOURCE_TEMPLATE

    _finish_llm_call(call, LlmCall.Outcome.OK, started)
    _store(user, start, digest, facts, clean)
    return clean, SOURCE_LLM


# Partly AI-generated (Claude)
def _start_llm_call(user, start: date, digest: str) -> LlmCall | None:
    """오늘의 호출 수를 세고, 상한 안이면 `pending` 행을 만든다. 상한이면 None.

    지난 일정의 알람 켬/끔만으로도 수치가 바뀐다. 저장은 (사용자, 주) 한 줄이라 두
    수치를 번갈아 만들면 해시가 매번 빗나가 다시 부른다 — 로그인한 사용자 하나가
    학교 키 예산을 원하는 만큼 쓸 수 있다. 사용자 상한이 그 길을, 전체 상한이 계정을
    여러 개 만드는 길을 막는다.

    **DB 행으로 센다.** 서버가 잠들거나 재배포해도 그날 센 수가 남는다(db-schema.md A5).
    두 요청이 같은 순간 세면 한두 번 넘칠 수 있다. 비용 방어용 상한이라 받아들인다.
    셀 수 없으면(DB 오류) 부르지 않는다 — 상한이 없는 상태로 열지 않는다. 상한에 걸린
    시도는 행을 만들지 않는다(부르지 않았으므로).
    """
    today = timezone.localdate()
    global_limit = int(getattr(settings, "LLM_GLOBAL_DAILY_LIMIT", 0) or 0)
    try:
        with transaction.atomic():
            today_calls = LlmCall.objects.filter(day=today)
            if today_calls.count() >= global_limit:
                logger.warning(
                    "서버 하루 LLM 요약 상한(%s회)에 닿았다. 오늘은 고정 문장을 쓴다",
                    global_limit,
                )
                return None
            if today_calls.filter(user=user).count() >= LLM_USER_DAILY_LIMIT:
                logger.warning(
                    "사용자 하루 LLM 요약 상한(%s회)에 닿았다. 오늘은 고정 문장을 쓴다",
                    LLM_USER_DAILY_LIMIT,
                )
                return None
            return LlmCall.objects.create(
                user=user,
                day=today,
                week_start=start,
                outcome=LlmCall.Outcome.PENDING,
                model=(getattr(settings, "OPENAI_MODEL", "") or "").strip()[:100],
                facts_hash=digest,
            )
    except DatabaseError:
        logger.exception("LLM 호출 수를 세지 못했다. 고정 문장을 쓴다")
        return None


def _finish_llm_call(call: LlmCall, outcome: str, started: float) -> None:
    """결과와 걸린 시간을 적는다. 실패해도 요약은 그대로 나간다(행은 pending 으로 남는다)."""
    known = {choice.value for choice in LlmCall.Outcome}
    call.outcome = outcome if outcome in known else LlmCall.Outcome.BAD_RESPONSE
    call.duration_ms = max(0, int((time.monotonic() - started) * 1000))
    try:
        with transaction.atomic():
            call.save(update_fields=["outcome", "duration_ms"])
    except DatabaseError:
        logger.warning("LLM 호출 결과를 적지 못했다")


def _stored_text(user, start: date, digest: str) -> str | None:
    try:
        row = WeeklySummary.objects.filter(user=user, week_start=start).first()
    except DatabaseError:
        logger.warning("저장된 주간 요약을 읽지 못했다")
        return None
    if row is None or row.facts_hash != digest:
        return None
    return row.text


def _store(user, start: date, digest: str, facts: dict, text: str) -> None:
    """저장한다. 실패해도 방금 만든 문장은 그대로 내린다."""
    try:
        with transaction.atomic():
            WeeklySummary.objects.update_or_create(
                user=user,
                week_start=start,
                defaults={
                    "facts_hash": digest,
                    "facts": facts,
                    "text": text,
                    "model": (settings.OPENAI_MODEL or "").strip()[:100],
                },
            )
    except IntegrityError:
        # 같은 주를 동시에 연 두 요청. 먼저 저장한 쪽이 남는다.
        pass
    except DatabaseError:
        logger.warning("주간 요약을 저장하지 못했다")
