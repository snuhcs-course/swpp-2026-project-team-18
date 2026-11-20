# Partly AI-generated (Claude)
"""정시 도착 확률을 내는 **하나의 식** (BE-P100, BE-H3c).

계획의 확률(홈 · 알람 상세), 빠르게 고르기 세 칸, 휠 표, 직접 정한 시각의 확률, "바꿀까요"
제안의 두 확률이 모두 여기를 지난다. 같은 분포 · 같은 시각이면 어디서나 같은 숫자다.

## 식

    확률 = P(준비 + 이동 <= 일정 시작 − 알람 시각)

버퍼도 쓸 수 있는 시간에 든다(`buffers` 머리말). 추천 알람이면 "일정 시작 − 알람" 이 τ 분위수
(분 단위로 반올림) + 버퍼다.

## 언제 확률을 내는가

준비 · 이동 중 **한쪽이라도** 변동성을 알면 낸다(11-20 결정, "어디서나 보여주기"). 모르는 쪽은
예상값(카카오 예상 시간, 사용자가 입력한 준비 시간)에 고정된다. 그래서 실제보다 조금 높게 나올
수 있고, 그 차이는 버퍼 학습이 지각으로 본다. 둘 다 모르면(점추정) 확률을 내지 않는다.
왜 그런지는 계획의 `confidence_basis` 가 그대로 알려 준다.

## 숫자

- `precise`: 소수 한 자리 퍼센트. **100.0 은 내지 않는다** — 모델은 확실하다고 말할 수 없다.
  상한 99.9, 쓸 시간이 남아 있으면 하한 0.1. 시각이 일정 시작과 같거나 뒤면 0.0.
- `whole`: 앱이 원래 읽는 정수 필드의 값. `precise` 를 반올림하되 99 를 넘지 않고, 0 보다 크면
  1 이상이다. 100 으로 반올림하지 않는 것이 BE-P100 의 요청이다.
  정수 필드를 소수로 바꾸지 않는 이유: 배포된 앱(Gson `Int?`)이 `99.8` 을 받으면 파싱이 깨진다.
"""

from __future__ import annotations

import math
from typing import Callable

PRECISE_MAX = 99.9
PRECISE_MIN = 0.1
WHOLE_MAX = 99
WHOLE_MIN = 1

# 휠 표가 덮는 가장 긴 시간(분). 이보다 일찍 일어나면 표의 마지막 값(상한)이다.
# 앱 휠은 일정 24시간 전까지라 그 전체를 다 보낼 필요는 없다 — 상한에 닿은 뒤는 모두 같다.
TABLE_MAX_MINUTES = 24 * 60


def can_tell(prep, travel) -> bool:
    """확률을 낼 수 있는가. 이동 또는 (준비가 있으면) 준비 중 한쪽이라도 변동성이 있어야 한다."""
    if travel is None:
        return False
    return travel.has_variance or (prep is not None and prep.has_variance)


def precise_of(total, available_minutes: float) -> float:
    """분포 `total` 에서 `available_minutes` 분 안에 마칠 확률(%, 소수 한 자리)."""
    if available_minutes <= 0:
        return 0.0
    value = round(total.cdf(available_minutes) * 100, 1)
    return min(PRECISE_MAX, max(PRECISE_MIN, value))


def whole(precise: float | None) -> int | None:
    """정수 필드의 값. None 은 None."""
    if precise is None:
        return None
    if precise <= 0:
        return 0
    # 0.5 는 올린다(파이썬 round 의 짝수 반올림을 쓰지 않는다).
    return min(WHOLE_MAX, max(WHOLE_MIN, int(math.floor(precise + 0.5))))


def stored(precise: float | None, whole_value: int | None) -> float | None:
    """저장된 확률의 소수 값. 정수 칸이 기준이다.

    소수 칸은 정수 칸과 맞을 때만 믿는다. 0.21.0 서버는 같은 DB 에서 정수 칸만 고쳐 쓰므로(소수 칸을
    모른다) 소수 칸이 옛 계산의 값으로 남을 수 있다. 맞지 않거나 비었으면 정수 값을 쓰되 상한을 지킨다.
    """
    if whole_value is None:
        return None
    if precise is not None and whole(precise) == min(whole_value, WHOLE_MAX):
        return precise
    if whole_value <= 0:
        return 0.0
    return min(PRECISE_MAX, float(whole_value))


def label(precise: float) -> str:
    """문장에 넣는 퍼센트 숫자. 소수 한 자리이되 .0 은 뗀다(46.0 → "46", 69.6 → "69.6")."""
    return f"{precise:.1f}".removesuffix(".0")


def function(prep, travel, start_at) -> Callable[[object], float | None]:
    """"그 시각에 일어나면" 의 확률(`precise`)을 내는 함수. 낼 수 없으면 늘 None.

    분포는 처음 부를 때 한 번만 만든다(몬테카를로 합성이 있을 수 있다).
    """
    if not can_tell(prep, travel):
        return lambda _at: None
    from .estimators import total_distribution

    total = None

    def at(alarm_at) -> float | None:
        nonlocal total
        if total is None:
            total = total_distribution(prep, travel)
        return precise_of(total, (start_at - alarm_at).total_seconds() / 60)

    return at


def table(total) -> dict:
    """휠 표(BE-H3c). `precise[i]` 는 일정 시작 **i 분 전**에 일어날 때의 확률이다.

    i 는 0 부터 상한(99.9)에 처음 닿는 분까지다. 그보다 일찍이면 마지막 값과 같다.
    앱은 `일정 시작 − 휠 시각` 을 분으로 세어 그 칸을 읽는다(초는 버린다).
    """
    precise = [0.0]
    minute = 0
    while minute < TABLE_MAX_MINUTES:
        minute += 1
        value = precise_of(total, minute)
        precise.append(value)
        if value >= PRECISE_MAX:
            break
    return {
        "unit": "minutes_before_start",
        "precise": precise,
        "whole": [whole(value) for value in precise],
        "max_minutes": len(precise) - 1,
    }
