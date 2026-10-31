"""관측 기반 학습 배치. GitHub Actions가 매일 한국 시간 23:17에 실행한다.

기본 브랜치의 .github/workflows/train-models.yml이 예약/수동 실행을 관리한다.
학습은 결정 시점 계획 이력의 경로 원값을 사용하며, 원값 없는 과거 이동은 건너뛴다.
전체 학습은 새로 만들 수 없는 과거 학습 보정을 정리하고 n=0 수동 보정은 보존한다.

    python manage.py train_models                    전역만
    python manage.py train_models --users            개인 아티팩트까지
    python manage.py train_models --routes-only      경로 보정만
    python manage.py train_models --dry-run          저장하지 않고 결과만 출력
    python manage.py train_models --users -v 0       공개 배치 로그용 집계만 출력

학습 뒤에는 지각 이력으로 사용자별 안전 버퍼를 조정하고(`planning.buffers`), 앞으로
7일 일정의 알람을 **경로 조회 없이** 다시 계산한다(`planning.refresh`). 새 이동 보정·버퍼와
그날 올라온 블록 기록·GPS 출발이 다음 아침 알람에 들어간다. 알람이 3시간 안에 울릴 일정은
앱 경로 갱신 워커에 맡긴다. --dry-run 이면 바뀔 건수만 출력한다(이때는 새 보정·버퍼도
저장하지 않았으므로 지금 DB 기준). --routes-only 는 버퍼를 건드리지 않는다.
"""

from __future__ import annotations

import json

from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand

from apps.planning import buffers, refresh
from apps.prediction import learning


class Command(BaseCommand):
    help = "관측에서 분포 모수를 학습해 아티팩트와 경로 보정을 갱신한다."

    def add_arguments(self, parser):
        parser.add_argument(
            "--users",
            action="store_true",
            help=f"관측 {learning.MIN_PERSONAL_OBSERVATIONS}건 이상인 사용자의 개인 아티팩트도 만든다",
        )
        parser.add_argument(
            "--routes-only",
            action="store_true",
            help="경로 보정만 갱신하고 아티팩트는 만들지 않는다",
        )
        parser.add_argument(
            "--dry-run",
            action="store_true",
            help="저장하지 않고 계산 결과만 출력한다",
        )
        parser.add_argument(
            "--show-payload",
            action="store_true",
            help="아티팩트 전문을 출력한다",
        )

    def handle(self, *args, **options):
        self._train(options)
        if not options["routes_only"]:
            self._adjust_buffers(options["dry_run"])
        self._refresh_plans(options["dry_run"])

    # Partly AI-generated (Claude)
    def _adjust_buffers(self, dry: bool):
        """지각 이력으로 안전 버퍼를 조정한다. 사용자 수만 출력한다(공개 로그)."""
        self.stdout.write("\n[4] 안전 버퍼 조정(지각 이력)")
        outcomes = buffers.adjust_all(dry_run=dry)
        self.stdout.write(
            f"  {buffers.INCREASED} {outcomes[buffers.INCREASED]}명 / "
            f"{buffers.DECREASED} {outcomes[buffers.DECREASED]}명"
        )
        for reason in (buffers.KEEP_FEW, buffers.KEEP_ON_TRACK, buffers.KEEP_AT_LIMIT):
            if outcomes[reason]:
                self.stdout.write(f"  {reason} {outcomes[reason]}명")
        if dry:
            self.stdout.write("  (dry-run 이라 저장하지 않았다)")

    # Partly AI-generated (Claude)
    def _refresh_plans(self, dry: bool):
        """학습 결과를 앞으로의 알람에 반영한다. 카카오를 부르지 않는다."""
        self.stdout.write("\n[5] 앞으로 7일 알람 재계산(경로 조회 없음)")
        outcomes = refresh.refresh_after_learning(dry_run=dry)
        self.stdout.write(
            f"  바뀜 {outcomes[refresh.CHANGED]}건 / 그대로 {outcomes[refresh.UNCHANGED]}건"
        )
        skipped = {
            reason: n for reason, n in outcomes.items()
            if reason not in (refresh.CHANGED, refresh.UNCHANGED)
        }
        for reason, n in sorted(skipped.items()):
            self.stdout.write(f"  건너뜀: {reason} {n}건")
        if dry:
            self.stdout.write("  (dry-run 이라 저장하지 않았다)")

    def _train(self, options):
        dry = options["dry_run"]

        # --- 1. 경로 보정 -------------------------------------------------
        self.stdout.write("[1] 경로 보정")
        if dry:
            samples, dropped = learning.collect_travel_samples()
            self.stdout.write(f"  이동 표본 {len(samples)}건")
            for reason, n in sorted(dropped.items()):
                self.stdout.write(f"  버림: {reason} {n}건")
            self.stdout.write("  (dry-run 이라 저장하지 않았다)")
        else:
            result = learning.update_route_corrections()
            self.stdout.write(
                f"  표본 {result['samples']}건 → 보정 {result['written']}행"
            )
            self.stdout.write(
                f"  걷는 속도 {result.get('walk_profiles', 0)}명 · 시간대 계수 {result.get('time_rows', 0)}행"
            )
            self.stdout.write(f"  더 이상 유효하지 않은 학습 보정 {result.get('removed', 0)}행 제거")
            for reason, n in sorted((result.get("dropped") or {}).items()):
                self.stdout.write(f"  버림: {reason} {n}건")

        if options["routes_only"]:
            self.stdout.write(self.style.SUCCESS("경로 보정만 갱신했다."))
            return

        # --- 2. 전역 아티팩트 ---------------------------------------------
        self.stdout.write("\n[2] 전역 아티팩트")
        payload = learning.build_artifact()
        n_blocks = len(payload["blocks"])
        n_obs = learning.observation_count()
        self.stdout.write(f"  블록 관측 {n_obs}건 / 블록 종류 {n_blocks}개")
        self.stdout.write(
            f"  하루 준비 평균 {payload['global']['prep_mean']}분 "
            f"(sd {payload['global']['prep_sd']}, {payload['global']['prep_days']}일)"
        )
        self.stdout.write(f"  slack_coef = {payload['slack_coef']}")
        if options["verbosity"] > 0:
            for name, stat in sorted(payload["blocks"].items()):
                self.stdout.write(
                    f"    {name:14} 평균 {stat['mean']:>6}분  sd {stat['sd']}  n={stat['n']}"
                )
        if options["show_payload"]:
            self.stdout.write(json.dumps(payload, ensure_ascii=False, indent=2))

        if not dry:
            if n_obs == 0:
                # 관측이 0 이면 저장하지 않는다. 빈 아티팩트를 활성으로 두면
                # 클라이언트가 "학습된 값" 으로 오해한다.
                self.stdout.write("  관측이 없어 저장하지 않았다.")
            else:
                artifact = learning.save_artifact(payload)
                self.stdout.write(
                    self.style.SUCCESS(f"  저장: {artifact.version} (n={artifact.sample_count})")
                )

        # --- 3. 개인 아티팩트 ---------------------------------------------
        if not options["users"]:
            self.stdout.write("\n(개인 아티팩트는 --users 로 만든다)")
            return

        self.stdout.write("\n[3] 개인 아티팩트")
        User = get_user_model()
        made = 0
        for user in User.objects.all():
            n = learning.observation_count(user=user)
            if n < learning.MIN_PERSONAL_OBSERVATIONS:
                continue
            personal = learning.build_artifact(user=user)
            if not dry:
                learning.save_artifact(personal, user=user)
            made += 1
            if options["verbosity"] > 0:
                self.stdout.write(
                    f"  {user.email:34} 관측 {n}건 "
                    f"블록 {len(personal['blocks'])}종 slack={personal['slack_coef']}"
                )
        if made == 0:
            self.stdout.write(
                f"  관측 {learning.MIN_PERSONAL_OBSERVATIONS}건 이상인 사용자가 없다."
            )
        else:
            self.stdout.write(self.style.SUCCESS(f"  개인 아티팩트 {made}개"))
