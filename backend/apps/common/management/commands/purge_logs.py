# Partly AI-generated (Claude)
"""보존 기간이 지난 기록을 지운다(db-schema.md §9-2).

    python manage.py purge_logs                     # 설정값(요청 30일, LLM 90일)
    python manage.py purge_logs --request-days 14   # DB 가 300 MB 를 넘으면

API 요청 기록과 LLM 호출만 지운다. 학습 재료는 건드리지 않는다. 미들웨어가 프로세스마다
하루 한 번 같은 일을 하므로(`retention.maybe_purge`) 평소에는 손으로 돌릴 일이 없다.
"""

from __future__ import annotations

from django.core.management.base import BaseCommand

from apps.common import retention


class Command(BaseCommand):
    help = "보존 기간이 지난 API 요청 기록·LLM 호출 기록을 지운다."

    def add_arguments(self, parser):
        parser.add_argument(
            "--request-days",
            type=int,
            default=None,
            help="API 요청 기록 보존 일수. 기본은 API_REQUEST_LOG_RETENTION_DAYS.",
        )
        parser.add_argument(
            "--llm-days",
            type=int,
            default=None,
            help="LLM 호출 기록 보존 일수. 기본은 LLM_CALL_RETENTION_DAYS.",
        )

    def handle(self, *args, **options):
        deleted = retention.purge(
            request_days=options["request_days"], llm_days=options["llm_days"]
        )
        self.stdout.write(
            f"API 요청 기록 {deleted['api_request_log']}건 · "
            f"LLM 호출 {deleted['llm_call']}건 삭제"
        )
