"""학습 파이프라인을 돌린다. back-spec.md 6.2 의 Celery 태스크를 겸한다.

## Celery 대신 커맨드인 이유

명세 6.2 는 `train_global_model`(매일 03:00), `update_route_corrections`
(매시 정각)을 Celery Beat 로 돌리도록 정했다. 지금은 Celery 를 넣지 않았다.

  - Redis 가 필요하고, Render 무료 플랜에 Redis 가 없다
  - 워커 프로세스가 하나 더 떠야 하는데 무료 인스턴스 시간을 두 배로 먹는다
  - 학습은 실시간이 아니다. 하루 한 번이면 충분하다

커맨드로 두면 cron·Render Cron Job·손으로 실행이 모두 가능하다. Celery 가
들어오면 태스크가 이 커맨드를 부르면 된다.

    python manage.py train_models                    전역만
    python manage.py train_models --users            개인 아티팩트까지
    python manage.py train_models --routes-only      경로 보정만
    python manage.py train_models --dry-run          저장하지 않고 결과만 출력
"""

from __future__ import annotations

import json

from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand

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
