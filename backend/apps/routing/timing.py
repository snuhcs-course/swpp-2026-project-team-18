# Partly AI-generated (Claude)
"""도착 목표 시각에 맞춘 출발 · 도착 · 구간 시각 (BE-11, task.md).

경로 후보 요청에 `arrive_by`(보통 일정 시작 또는 계획의 도착 예정)를 주면 후보마다

    arrive_at = arrive_by
    depart_at = arrive_by − 구간 시간의 합(없으면 `minutes`)

이고, 구간(`segments[]`)마다 앞에서부터 차례로 `start_at` · `end_at` 을 붙인다. 대중교통이면 타는
구간의 `start_at` 이 차에 오르는 시각, `end_at` 이 내리는 시각이다(기다리는 시간은 그 앞 `wait`
구간에 있다). 구간 시간의 합은 카카오 `totalTime` 과 같게 맞춰져 있다(`clients._segments`).

## 하지 않는 것

카카오 대중교통 API 는 출발 · 도착 시각을 받지 않는다. 그래서 **그 시각에 실제로 다니는 경로만
고르기(막차 · 첫차 · 운행 시간)는 할 수 없다.** 소요 시간도 지금 시각 기준 추정치라 이 시각들은
"대략 이때" 다. 앱이 하던 임시 계산과 같은 식을 서버 한 곳에서 하는 것이다.
"""

from __future__ import annotations

from datetime import datetime, timedelta

from rest_framework.fields import DateTimeField

_iso = DateTimeField().to_representation


def total_seconds(candidate: dict) -> int:
    segments = candidate.get("segments") or []
    seconds = sum(int(s.get("seconds") or 0) for s in segments)
    if seconds > 0:
        return seconds
    return int(candidate.get("minutes") or 0) * 60


def with_times(candidate: dict, arrive_by: datetime) -> dict:
    """후보 사본에 `depart_at` · `arrive_at` 과 구간 시각을 붙인다. 원본은 바꾸지 않는다."""
    depart = arrive_by - timedelta(seconds=total_seconds(candidate))
    timed = dict(candidate, depart_at=_iso(depart), arrive_at=_iso(arrive_by))
    if candidate.get("segments"):
        at = depart
        segments = []
        for segment in candidate["segments"]:
            end = at + timedelta(seconds=int(segment.get("seconds") or 0))
            segments.append(dict(segment, start_at=_iso(at), end_at=_iso(end)))
            at = end
        timed["segments"] = segments
    return timed
