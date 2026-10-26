# Partly AI-generated (Claude)
"""일정 하나의 대표 출발·도착 관측을 고른다. **GPS 가 먼저다.**

도착은 GPS 판정(`detector=gps`)이 기본이고, 직접 누른 판정(`manual`)은 GPS 가
도착을 못 잡았을 때를 위한 보조다. 같은 일정에 둘 다 있으면 GPS 를 쓴다. 수동을
먼저 누르고 GPS 가 나중에 올라와도, 읽을 때마다 고르므로 자동으로 GPS 로 바뀐다.

## 수동 판정을 거르는 곳

업로드에서 거절하지 않고 **읽을 때** 거른다. 앱 큐는 400 을 받으면 배치를 통째로
버려서, 수동 한 건 때문에 같은 배치의 GPS 관측까지 사라진다.

| 버리는 수동 판정 | 이유 |
| --- | --- |
| 일정 시작 + 1시간 뒤에 누름 | 추적이 끝난 뒤다. 도착한 순간이 아니라 나중에 생각나서 누른 것 |
| 서버가 받은 시각보다 5분 넘게 미래 | 기기 시계가 틀렸다 |

## 쓰는 곳

- 리포트의 정시/지각 채점(`reports.services`): 이 모듈로 고른다. 수동도 보조로 쓴다.
- 이동 시간 학습(`prediction.learning`): **GPS 만 쓴다.** 누른 시각은 실제 도착보다
  늦기 쉬워서, 넣으면 이동 시간이 길게 학습된다.
"""

from __future__ import annotations

from collections.abc import Iterable
from datetime import timedelta

from .models import TripObservation

# 일정 시작 뒤 이만큼까지 누른 수동 판정만 믿는다. 앱 추적이 끝나는 시점과 같다.
MANUAL_GRACE_AFTER_START = timedelta(hours=1)

# 기기 시계 오차로 보고 넘겨 주는 미래 폭.
MANUAL_CLOCK_SKEW = timedelta(minutes=5)


def usable_manual(obs: TripObservation, event) -> bool:
    """직접 누른 판정을 믿어도 되는가."""
    if obs.observed_at > event.start_at + MANUAL_GRACE_AFTER_START:
        return False
    if obs.created_at is not None and obs.observed_at > obs.created_at + MANUAL_CLOCK_SKEW:
        return False
    return True


def representative(observations: Iterable[TripObservation], kind: str, event):
    """그 종류의 대표 관측. GPS 중 가장 이른 것, 없으면 믿을 만한 수동 중 가장 이른 것.

    가장 이른 것을 쓰는 이유는 기존 규칙과 같다. 출발은 처음 집을 나선 순간이고
    도착은 처음 목적지에 닿은 순간이다.
    """
    same_kind = sorted(
        (o for o in observations if o.kind == kind), key=lambda o: (o.observed_at, o.pk or 0)
    )
    for obs in same_kind:
        if obs.detector == TripObservation.Detector.GPS:
            return obs
    for obs in same_kind:
        if obs.detector == TripObservation.Detector.MANUAL and usable_manual(obs, event):
            return obs
    return None
