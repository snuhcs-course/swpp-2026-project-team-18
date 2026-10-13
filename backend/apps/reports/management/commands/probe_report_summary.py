# Partly AI-generated (Claude)
"""LLM 설정이 주간 요약에 쓸 수 있는 상태인지 한 번 확인한다.

학교 키(·주소)를 `.env` 나 Render 대시보드에 넣은 뒤 이것 하나로 확인한다.

    python manage.py probe_report_summary

코드에 박아 둔 **표본 집계값**(실제 사용자 데이터가 아니다)으로 `llm.chat` 을 한 번
부르고, 앱에 내보낼 때와 같은 `check_text` 검사를 돌린다. 설정 여부·엔드포인트
호스트·걸린 시간·검사 결과·문장을 출력한다. **키는 출력하지 않는다.**

종료 코드: 통과 0, 설정 없음·요청 실패·검사 실패 1.
"""

from __future__ import annotations

import json
import time
import urllib.parse

from django.conf import settings
from django.core.management.base import BaseCommand

from apps.reports import llm, summary

# 설계 문서(report-design.md §4-2)의 예시 주. 일정 7 · 도착 기록 6 · 정시 5 ·
# 화요일 10분 지각. 키 집합은 summary.FACT_KEYS 와 같아야 한다(테스트로 고정).
SAMPLE_FACTS = {
    "week_complete": True,
    "events": 7,
    "arrived": 6,
    "unobserved": 1,
    "on_time": 5,
    "late": 1,
    "on_time_pct": 83,
    "target_pct": 90,
    "target_met": False,
    "thin_data": False,
    "median_slack_min": 9,
    "tightest_slack_min": -10,
    "late_max_min": 10,
    "late_max_weekday": "화",
    "late_causes": {"이동 초과": 1},
    "top_cause": "이동 초과",
    "top_cause_count": 1,
    "top_cause_mean_min": 13,
    "prep_over_mean_min": 4,
    "prep_over_n": 2,
    "depart_late_mean_min": 3,
    "depart_late_n": 5,
    "travel_over_mean_min": -1,
    "travel_over_n": 5,
    "travel_over_sd_min": 7,
    "prev_on_time_pct": 60,
    "on_time_change_pp": 23,
    "alarm_events": 7,
    "unobserved_with_alarm": 1,
    "suggestion": "이동이 계획보다 길어지는 날이 있었으니 다른 경로를 골라 보거나 몇 분 일찍 나서 볼 것.",
}


class Command(BaseCommand):
    help = "표본 집계값으로 LLM 요약을 한 번 만들어 설정을 확인한다(키는 출력하지 않음)."

    def handle(self, *args, **options):
        out = self.stdout
        if not llm.configured():
            out.write(
                "설정 없음: OPENAI_API_KEY·OPENAI_MODEL 이 비어 있다. "
                "리포트 요약은 고정 문장(template)으로 나간다."
            )
            raise SystemExit(1)

        url = llm.endpoint()
        host = urllib.parse.urlsplit(url).hostname if url else None
        out.write(f"모델      {settings.OPENAI_MODEL.strip()}")
        out.write(f"호스트    {host or '(쓸 수 없는 주소 — https 가 아니거나 쿼리가 있음)'}")

        started = time.monotonic()
        text, failure = llm.chat(
            summary.SYSTEM_PROMPT, json.dumps(SAMPLE_FACTS, ensure_ascii=False)
        )
        elapsed = time.monotonic() - started
        out.write(f"걸린 시간 {elapsed:.1f}초")

        if failure is not None:
            out.write(f"실패      {failure}")
            raise SystemExit(1)

        clean = summary.normalize(text)
        reason = summary.check_text(clean, SAMPLE_FACTS)
        out.write(f"검사      {'통과' if reason is None else '실패 ' + reason}")
        out.write(f"문장      {clean}")
        if reason is not None:
            raise SystemExit(1)
