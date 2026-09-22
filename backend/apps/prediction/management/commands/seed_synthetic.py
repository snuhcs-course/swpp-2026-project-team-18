"""그럴듯한 관측을 합성한다. back-spec.md 6.3 (F6.6).

## 두 가지 목적

1. **데모 장치.** 관측이 0건이면 확률이 null 이고 τ 슬라이더가 움직이지
   않는다. 제품의 핵심을 보여줄 수 없다. 30일치를 심으면 전체 경로가 살아난다.

2. **모수 복원 검증.** 심어둔 계수를 학습이 되찾는지 확인한다. 이게 없으면
   학습 코드가 틀렸는지 데이터가 부족한지 구분할 수 없다. `--verify` 가
   심은 값과 학습된 값을 나란히 출력한다.

## 심는 구조

블록마다 참 평균과 표준편차를 정하고, 여기에 세 효과를 얹는다.

    요일 효과   월요일은 느리다 (+2분)
    여유 효과   쓸 수 있는 시간이 많으면 늘어진다 (slack_coef 만큼)
    잡음        정규분포

이동은 예측값에 참 factor 를 곱하고 잡음을 더한다. 학습이 factor 와 잔차
표준편차를 되찾아야 한다.

**실데이터와 섞이지 않게 한다.** `client_uuid` 에 `synth-` 접두를 붙이므로
`--clear` 로 정확히 골라 지울 수 있다.

    python manage.py seed_synthetic --email demo@demo.com --days 30
    python manage.py seed_synthetic --email demo@demo.com --verify
    python manage.py seed_synthetic --email demo@demo.com --clear
"""

from __future__ import annotations

import random
from datetime import date, datetime, time, timedelta

from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand, CommandError
from django.db import transaction
from django.utils import timezone as dj_tz

from apps.prediction import learning
from apps.routines.models import BlockObservation, RoutineBlock

# 합성 데이터 표식. 실데이터와 구분해 지울 수 있어야 한다.
SYNTH_PREFIX = "synth-"

# --- 심는 참값 -------------------------------------------------------------
# (블록 이름, 참 평균분, 참 sd분)
#
# seed_blocks 의 기본 6종과 이름을 맞춘다. 신고 범위의 중앙값과 **일부러
# 다르게** 둔다 — 학습이 신고값을 실측으로 교정하는지 봐야 한다.
TRUE_BLOCKS = [
    ("기상·정리", 6.0, 1.5),    # 신고 3~7 (중앙 5.0) → 실제로는 더 걸린다
    ("샤워", 16.0, 2.5),        # 신고 10~18 (중앙 14.0)
    ("머리 손질", 9.0, 3.0),    # 신고 5~12 (중앙 8.5)
    ("옷 입기", 7.0, 2.0),      # 신고 4~9 (중앙 6.5)
    ("아침 식사", 12.0, 4.0),   # 신고 8~20 (중앙 14.0) → 실제로는 덜 걸린다
    ("가방·준비물", 4.0, 1.2),  # 신고 2~6 (중앙 4.0)
]

# 월요일 추가 소요(분). 요일 효과.
TRUE_MONDAY_PENALTY = 2.0

# 여유 1분당 늘어나는 준비 시간(분). back-spec 6.1 S3 의 slack_coef.
TRUE_SLACK_COEF = 0.18

# 이동: 카카오 예측 대비 실제 비율과 잔차 표준편차.
TRUE_ROUTE_FACTOR = 1.12
TRUE_ROUTE_SD = 4.0

# 재현성. 같은 시드로 같은 데이터가 나와야 검증이 흔들리지 않는다.
DEFAULT_SEED = 20260915


class Command(BaseCommand):
    help = "합성 관측을 만들어 학습 파이프라인과 리포트를 데모·검증한다."

    def add_arguments(self, parser):
        parser.add_argument("--email", required=True, help="대상 계정")
        parser.add_argument("--days", type=int, default=30, help="며칠치 (기본 30)")
        parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
        parser.add_argument(
            "--clear", action="store_true", help="합성 관측만 지우고 끝낸다"
        )
        parser.add_argument(
            "--verify",
            action="store_true",
            help="심은 값과 학습된 값을 비교 출력한다",
        )

    def handle(self, *args, **options):
        User = get_user_model()
        email = options["email"].strip().lower()
        try:
            user = User.objects.get(email=email)
        except User.DoesNotExist as exc:
            raise CommandError(f"계정이 없다: {email}") from exc

        if options["clear"]:
            n, _ = BlockObservation.objects.filter(
                user=user, client_uuid__startswith=SYNTH_PREFIX
            ).delete()
            self.stdout.write(self.style.SUCCESS(f"합성 관측 {n}행 삭제"))
            return

        if options["verify"]:
            self._verify(user)
            return

        created = self._seed(user, options["days"], options["seed"])
        self.stdout.write(
            self.style.SUCCESS(f"합성 블록 관측 {created}건 생성 ({options['days']}일치)")
        )
        self.stdout.write("")
        self.stdout.write("다음 단계:")
        self.stdout.write("  python manage.py train_models --users")
        self.stdout.write(f"  python manage.py seed_synthetic --email {email} --verify")

    @transaction.atomic
    def _seed(self, user, days: int, seed: int) -> int:
        blocks = {b.name: b for b in RoutineBlock.objects.filter(user=user)}
        if not blocks:
            raise CommandError(
                "루틴 블록이 없다. 먼저 `python manage.py seed_blocks --email "
                f"{user.email}` 을 돌릴 것."
            )

        rng = random.Random(seed)
        today = dj_tz.localdate()
        created = 0

        # 기존 합성 데이터를 먼저 지운다. 두 번 돌리면 표본이 두 배가 되어
        # 학습 결과가 달라진다.
        BlockObservation.objects.filter(
            user=user, client_uuid__startswith=SYNTH_PREFIX
        ).delete()

        for offset in range(days):
            day: date = today - timedelta(days=offset + 1)
            # 주말은 아침 루틴이 없다고 본다. 요일 효과를 학습하려면 평일
            # 데이터가 필요하고, 주말을 섞으면 분산이 커져 신호가 묻힌다.
            if day.weekday() >= 5:
                continue

            # 그날 쓸 수 있었던 여유. 알람을 넉넉히 맞춘 날과 빡빡한 날.
            slack = rng.uniform(-5.0, 25.0)
            monday_penalty = TRUE_MONDAY_PENALTY if day.weekday() == 0 else 0.0

            recorded_at = dj_tz.make_aware(datetime.combine(day, time(7, 30)))

            for name, true_mean, true_sd in TRUE_BLOCKS:
                block = blocks.get(name)
                if block is None:
                    continue

                duration = (
                    true_mean
                    + monday_penalty
                    + TRUE_SLACK_COEF * slack
                    + rng.gauss(0.0, true_sd)
                )
                # 음수 소요는 물리적으로 불가능하다. 0.5분에서 자른다.
                duration = max(0.5, duration)

                BlockObservation.objects.create(
                    user=user,
                    block=block,
                    observed_on=day,
                    duration_minutes=round(duration, 2),
                    slack_minutes=round(slack, 2),
                    was_parallel=False,
                    client_uuid=f"{SYNTH_PREFIX}{day.isoformat()}-{block.pk}",
                    client_recorded_at=recorded_at,
                )
                created += 1

        return created

    def _verify(self, user):
        """심은 값과 학습된 값을 나란히 출력한다."""
        stats = learning.block_stats(user=user)
        slack = learning.estimate_slack_coef(user=user)
        totals = learning.prep_total_stats(user=user)

        self.stdout.write("=" * 74)
        self.stdout.write("모수 복원 검증 — 심은 값 vs 학습된 값")
        self.stdout.write("=" * 74)
        self.stdout.write("")
        self.stdout.write(
            f"{'블록':<14} {'심은 평균':>9} {'학습 평균':>9} {'오차':>7}   "
            f"{'심은 sd':>7} {'학습 sd':>7}  n"
        )

        # 여유 효과가 평균을 끌어올린다. 여유가 균등분포 U(-5,25) 라 기댓값이
        # 10 이므로, 학습 평균은 심은 평균 + slack_coef * 10 근처가 나와야 한다.
        expected_shift = TRUE_SLACK_COEF * 10.0
        # 평일 5일 중 월요일이 하나라 요일 효과의 기댓값은 penalty/5 다.
        expected_shift += TRUE_MONDAY_PENALTY / 5.0

        worst = 0.0
        for name, true_mean, true_sd in TRUE_BLOCKS:
            got = stats.get(name)
            if got is None:
                self.stdout.write(f"{name:<14} {'(관측 없음)':>9}")
                continue
            expected = true_mean + expected_shift
            err = got["mean"] - expected
            worst = max(worst, abs(err))
            sd_text = f"{got['sd']:>7}" if got["sd"] is not None else f"{'-':>7}"
            self.stdout.write(
                f"{name:<14} {expected:>9.2f} {got['mean']:>9.2f} {err:>+7.2f}   "
                f"{true_sd:>7.2f} {sd_text}  {got['n']}"
            )

        self.stdout.write("")
        self.stdout.write(f"slack_coef   심은 값 {TRUE_SLACK_COEF}  학습 {slack}")
        self.stdout.write(
            f"하루 준비 총합   평균 {totals['prep_mean']}분  sd {totals['prep_sd']}  "
            f"{totals['n']}일"
        )
        self.stdout.write("")
        self.stdout.write(
            "주1: 학습 평균은 심은 평균 + 여유효과(slack_coef x 여유 기댓값 10) "
            "+ 요일효과/5 를 더한 값과 비교한다."
        )
        # 학습 sd 가 심은 sd 보다 크게 나오는 것은 정상이다. 이유를 적어 두지
        # 않으면 "학습이 분산을 과대추정한다" 고 오해한다.
        slack_sd = (25.0 - (-5.0)) / (12.0**0.5)  # 균등분포 U(-5,25) 의 표준편차
        self.stdout.write(
            f"주2: 학습 sd 가 심은 sd 보다 큰 것이 맞다. 관측에는 여유 효과의 "
            f"변동이 섞여 있다 — 여유의 sd 가 {slack_sd:.2f}분이므로 "
            f"slack_coef x {slack_sd:.2f} = {TRUE_SLACK_COEF * slack_sd:.2f}분이 "
            "분산으로 더해진다."
        )
        self.stdout.write(
            "     예: 샤워는 sqrt(2.50^2 + "
            f"{TRUE_SLACK_COEF * slack_sd:.2f}^2) = "
            f"{(2.5**2 + (TRUE_SLACK_COEF * slack_sd) ** 2) ** 0.5:.2f}분이 기대값이다."
        )
        self.stdout.write(
            "     예측에 쓰는 것은 이 **총 변동성**이 맞다. 알람은 "
            "\"다음 한 번이 얼마나 걸릴지\" 를 알아야 한다."
        )
        self.stdout.write(f"최대 오차 {worst:.2f}분")
        if worst < 2.0:
            self.stdout.write(self.style.SUCCESS("복원 양호 (오차 2분 미만)"))
        else:
            self.stdout.write(
                self.style.WARNING("오차가 크다. 표본을 늘리거나 학습 코드를 확인할 것.")
            )
        self.stdout.write("=" * 74)
