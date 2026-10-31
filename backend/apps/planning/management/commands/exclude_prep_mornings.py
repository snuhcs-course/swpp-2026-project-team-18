# Partly AI-generated (Claude)
"""준비 시간이 너무 길게 잡힌 아침을 준비 학습에서 뺀다. 지우지 않고 표시만 붙인다.

    python manage.py exclude_prep_mornings --email me@example.com --above 80           # 대상만 보여 준다
    python manage.py exclude_prep_mornings --email me@example.com --above 80 --apply   # 표시를 붙인다
    python manage.py exclude_prep_mornings --email me@example.com --undo               # 붙인 표시를 보여 준다
    python manage.py exclude_prep_mornings --email me@example.com --undo --apply       # 모두 뗀다

아침의 준비 시간은 두 가지로 잰다. GPS 준비 시간(알람 끄기 → 집 반경을 벗어남,
`prep_observations.gps_prep_by_event`)과 그날 블록 기록의 합(병렬 블록 제외, 리포트와 같다).
둘 중 하나라도 `--above` 분을 넘으면 대상이다.

표시(`Event.prep_learning_excluded_at`)를 붙인 아침은 GPS 준비 시간과 블록 기록 모두
준비 학습에서 빠진다. 리포트·알람·이동 시간 학습은 그대로다. 붙이거나 뗀 뒤에는 그
사용자의 앞으로의 알람을 경로 조회 없이 다시 계산한다(`planning.refresh`). 개인 학습
아티팩트는 다음 밤 학습(`train_models --users`)에 바뀐다.
"""

from __future__ import annotations

from django.contrib.auth import get_user_model
from django.core.management.base import BaseCommand, CommandError
from django.db import transaction
from django.utils import timezone

from apps.events.models import Event
from apps.planning import refresh
from apps.planning.models import PlanRevision
from apps.planning.prep_observations import gps_prep_by_event
from apps.routines.models import BlockObservation


class Command(BaseCommand):
    help = "준비 시간이 너무 길게 잡힌 아침을 준비 학습에서 뺀다(되돌릴 수 있다)."

    def add_arguments(self, parser):
        parser.add_argument("--email", required=True, help="대상 사용자 이메일")
        parser.add_argument(
            "--above",
            type=float,
            default=None,
            help="이 분을 넘는 아침을 뺀다. --undo 가 아니면 필요하다.",
        )
        parser.add_argument(
            "--undo",
            action="store_true",
            help="붙인 표시를 모두 뗀다.",
        )
        parser.add_argument(
            "--apply",
            action="store_true",
            help="실제로 바꾼다. 없으면 대상만 출력한다.",
        )

    def handle(self, *args, **options):
        user = get_user_model().objects.filter(email__iexact=options["email"].strip()).first()
        if user is None:
            raise CommandError("그 이메일의 사용자가 없다.")

        if options["undo"]:
            self._undo(user, options["apply"])
            return

        above = options["above"]
        if above is None or above <= 0:
            raise CommandError("--above 에 0보다 큰 분을 준다(예: --above 80).")
        self._exclude(user, above, options["apply"])

    def _exclude(self, user, above: float, apply: bool):
        mornings = measured_mornings(user)
        targets = [
            m for m in mornings
            if any(v is not None and v > above for v in (m["gps"], m["blocks"]))
        ]
        self.stdout.write(f"준비 기록이 있는 아침 {len(mornings)}개 중 {above:g}분을 넘는 아침 {len(targets)}개")
        for m in targets:
            self.stdout.write(_line(m))

        new = [m for m in targets if m["event"].prep_learning_excluded_at is None]
        if not new:
            self.stdout.write(self.style.SUCCESS("새로 뺄 아침이 없다."))
            return
        if not apply:
            self.stdout.write(
                self.style.WARNING(f"\n--apply 를 붙이면 {len(new)}개에 표시를 붙인다. 지금은 아무것도 바꾸지 않았다.")
            )
            return

        with transaction.atomic():
            Event.objects.filter(pk__in=[m["event"].pk for m in new]).update(
                prep_learning_excluded_at=timezone.now()
            )
        self.stdout.write(self.style.SUCCESS(f"\n{len(new)}개를 준비 학습에서 뺐다."))
        self._refresh(user)

    def _undo(self, user, apply: bool):
        marked = list(
            Event.objects.filter(user=user, prep_learning_excluded_at__isnull=False).order_by("start_at", "id")
        )
        by_event = {m["event"].pk: m for m in measured_mornings(user)}
        self.stdout.write(f"준비 학습에서 뺀 아침 {len(marked)}개")
        for event in marked:
            self.stdout.write(_line(by_event.get(event.pk) or {"event": event, "gps": None, "blocks": None}))
        if not marked:
            return
        if not apply:
            self.stdout.write(
                self.style.WARNING("\n--apply 를 붙이면 표시를 모두 뗀다. 지금은 아무것도 바꾸지 않았다.")
            )
            return

        with transaction.atomic():
            Event.objects.filter(pk__in=[e.pk for e in marked]).update(prep_learning_excluded_at=None)
        self.stdout.write(self.style.SUCCESS(f"\n{len(marked)}개를 준비 학습에 다시 넣었다."))
        self._refresh(user)

    def _refresh(self, user):
        outcomes = refresh.refresh_upcoming(user, trigger=PlanRevision.Trigger.LEARNING)
        summary = ", ".join(f"{k} {v}" for k, v in sorted(outcomes.items())) or "다시 계산할 일정 없음"
        self.stdout.write(f"앞으로의 알람: {summary}")


def measured_mornings(user) -> list[dict]:
    """준비 기록이 있는 아침. `{"event", "gps", "blocks"}`, 오래된 것부터.

    `gps` 는 GPS 준비 시간(분), `blocks` 는 그날 블록 기록 합(분, 병렬 블록 제외)이다.
    없으면 None 이다. 이미 표시를 붙인 아침도 넣는다.
    """
    gps = dict(gps_prep_by_event(user.pk, include_marked=True))
    blocks: dict[int, float] = {}
    rows = BlockObservation.objects.filter(user=user, event__isnull=False).values_list(
        "event_id", "duration_minutes", "was_parallel"
    )
    for event_id, duration, was_parallel in rows:
        if was_parallel:
            continue
        blocks[event_id] = blocks.get(event_id, 0.0) + float(duration)

    events = Event.objects.filter(user=user, pk__in=set(gps) | set(blocks)).order_by("start_at", "id")
    return [
        {
            "event": event,
            "gps": gps.get(event.pk),
            "blocks": round(blocks[event.pk], 1) if event.pk in blocks else None,
        }
        for event in events
    ]


def _line(morning: dict) -> str:
    event = morning["event"]
    gps = "-" if morning["gps"] is None else f"{morning['gps']:g}분"
    blocks = "-" if morning["blocks"] is None else f"{morning['blocks']:g}분"
    mark = " [이미 뺌]" if event.prep_learning_excluded_at is not None else ""
    when = timezone.localtime(event.start_at).strftime("%Y-%m-%d %H:%M")
    return f"  id={event.pk} {when} GPS {gps} · 블록 합 {blocks}{mark}"
