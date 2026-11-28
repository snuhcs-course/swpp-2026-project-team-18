"""Export KST daily telemetry using existing server records (S-4)."""

from __future__ import annotations

import csv
from datetime import date
from pathlib import Path

from django.core.management.base import BaseCommand, CommandError

from apps.common.telemetry import FIELDS, UNKNOWN_VERSION, canonical_version, daily_rows


class Command(BaseCommand):
    help = "S-4: KST 날짜별 사용량·알람·이동·응답 시간·오류율을 개인정보 없이 집계해요."

    def add_arguments(self, parser):
        parser.add_argument("--from", dest="from_date", required=True, help="시작일 YYYY-MM-DD (KST, 포함)")
        parser.add_argument("--to", dest="to_date", required=True, help="종료일 YYYY-MM-DD (KST, 포함)")
        parser.add_argument("--app-version", help="앱 버전. 0.22.0은 0.22.0 (22)도 포함해요. 미상은 unknown")
        parser.add_argument("--users", help="포함할 베타 테스터 숫자 ID, 쉼표로 구분 (QA 계정은 계속 제외)")
        parser.add_argument("--csv", dest="csv_path", help="화면과 같은 표를 저장할 CSV 경로 (UTF-8)")

    def handle(self, *args, **options):
        try:
            start = date.fromisoformat(options["from_date"])
            end = date.fromisoformat(options["to_date"])
            # fromisoformat also accepts compact/week dates; keep the CLI contract strict.
            if start.isoformat() != options["from_date"] or end.isoformat() != options["to_date"]:
                raise ValueError
            if start > end or end == date.max:
                raise ValueError
        except (TypeError, ValueError):
            raise CommandError("--from/--to에는 시작일 ≤ 종료일인 YYYY-MM-DD 날짜를 넣어 주세요.") from None

        version = options["app_version"]
        if version is not None:
            normalized = canonical_version(version)
            if normalized == UNKNOWN_VERSION and version != UNKNOWN_VERSION:
                raise CommandError("--app-version에는 숫자 앱 버전 또는 unknown을 넣어 주세요.")
            version = normalized
        user_ids = None
        if options["users"] is not None:
            try:
                parts = options["users"].split(",")
                if not parts or any(not part.strip().isascii() or not part.strip().isdecimal() for part in parts):
                    raise ValueError
                user_ids = sorted({int(part.strip()) for part in parts})
                if any(pk <= 0 or pk > 9223372036854775807 for pk in user_ids):
                    raise ValueError
            except (TypeError, ValueError):
                raise CommandError("--users에는 양의 정수 ID를 쉼표로 구분해 넣어 주세요.") from None

        rows = daily_rows(start, end, app_version=version, user_ids=user_ids)

        def write(stream):
            writer = csv.DictWriter(stream, fieldnames=FIELDS, lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)

        if options["csv_path"]:
            try:
                with Path(options["csv_path"]).open("w", encoding="utf-8", newline="") as stream:
                    write(stream)
            except OSError:
                # Do not echo a caller's private filesystem path in an error.
                raise CommandError("CSV 파일을 저장하지 못했어요. 경로와 쓰기 권한을 확인해 주세요.") from None
        write(self.stdout)
