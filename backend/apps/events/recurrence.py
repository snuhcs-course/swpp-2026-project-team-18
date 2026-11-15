"""Bounded weekly expansion; existing IDs, manual switches and exclusions survive reads.

iter4 A2: "이후 일정 모두" 수정(`split_following`)·삭제(`delete_following`)와 반복 끝(`until`).
규칙은 docs/iter4/server-recurrence-scope.md. (Partly AI-generated (Claude))
"""

from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

from django.db import transaction
from django.db.models import Q
from django.utils import timezone

from apps.planning import services
from apps.planning.models import PlanRevision

from .alarms import reset_alarm_overrides, user_timezone
from .models import Event, EventRecurrence

TEMPLATE_FIELDS = (
    "title", "destination_is_home", "tau_override", "route_key",
    "origin_lat", "origin_lng", "origin_label",
)


def window(zone):
    tz = ZoneInfo(zone)
    today = timezone.now().astimezone(tz).date()
    return (datetime.combine(today, time.min, tzinfo=tz),
            datetime.combine(today + timedelta(days=7), time.min, tzinfo=tz))


def first_start(start_at, weekdays, user):
    local = start_at.astimezone(user_timezone(user))
    offset = next(n for n in range(7) if (local.weekday() + n) % 7 in weekdays)
    return local + timedelta(days=offset)


def _template_of(event):
    template = {field: getattr(event, field) for field in TEMPLATE_FIELDS}
    # 고른 값만 넣는다. 비워 두면(null) 이 필드를 모르는 이전 버전으로 되돌려도
    # 회차를 만들 수 있다(`Event.objects.create(**template)`).
    if event.include_prep is not None:
        template["include_prep"] = event.include_prep
    if event.origin_place_id is not None:
        template["origin_place_id"] = event.origin_place_id
    return template


def attach(event, weekdays):
    tz = user_timezone(event.user)
    template = _template_of(event)
    rule = EventRecurrence.objects.create(
        user=event.user, start_at=event.start_at, timezone=tz.key, weekdays=weekdays,
        template=template,
        place=event.place, tag=event.tag,
    )
    event.recurrence = rule
    event.recurrence_date = event.start_at.astimezone(tz).date()
    event.save(update_fields=["recurrence", "recurrence_date"])


def _live_template(rule):
    """회차를 만들 값. 저장한 집을 지웠으면 그 id 는 빼고 좌표만 쓴다. (Partly AI-generated (Claude))"""
    template = dict(rule.template)
    place_id = template.get("origin_place_id")
    if place_id is not None:
        from apps.accounts.models import SavedPlace

        if not SavedPlace.objects.filter(pk=place_id, user_id=rule.user_id).exists():
            template.pop("origin_place_id")
    return template


def in_window(event):
    if event.recurrence_id is None:
        return True
    start, end = window(event.recurrence.timezone)
    return start <= event.start_at < end


@transaction.atomic
def expand(rule_id, *, reset_choices=False, reset_created=False):
    """창(오늘부터 7일) 안의 회차를 채우고 계획이 없는 회차를 계산한다.

    `reset_choices` 는 창 안 모든 회차의 날에, `reset_created` 는 이번에 새로 만든 회차의 날에만
    다른 일정의 수동 켬/끔을 돌린다(일정이 새로 들어온 날, BE-25).
    """
    # The same lock protects expansion and deletion exclusions.
    rule = EventRecurrence.objects.select_for_update(of=("self",)).select_related("user__profile").get(pk=rule_id)
    start, end = window(rule.timezone)
    anchor = rule.start_at.astimezone(start.tzinfo)
    existing = {event.recurrence_date: event for event in rule.occurrences.filter(
        Q(recurrence_date__gte=start.date(), recurrence_date__lt=end.date())
        | Q(start_at__gte=start, start_at__lt=end),
    ).select_related("alarm_plan", "user__profile", "recurrence")}
    day = max(start.date(), anchor.date())
    # 끝난 규칙(`until`)은 그 날까지만 펼친다.
    last = end.date() if rule.until is None else min(end.date(), rule.until + timedelta(days=1))
    created = set()
    while day < last:
        if day.weekday() in rule.weekdays and day.isoformat() not in rule.excluded_dates:
            event = existing.get(day)
            if event is None:
                template = _live_template(rule)
                existing[day] = Event.objects.create(
                    user=rule.user, recurrence=rule, recurrence_date=day,
                    start_at=datetime.combine(day, anchor.time(), tzinfo=start.tzinfo),
                    place=rule.place, tag=rule.tag, **template,
                )
                created.add(day)
        day += timedelta(days=1)
    # A moved occurrence keeps its original slot, but needs its plan by actual date.
    for slot, event in existing.items():
        if start <= event.start_at < end:
            if getattr(event, "alarm_plan", None) is None:
                services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
            if reset_choices or (reset_created and slot in created):
                reset_alarm_overrides(event)
    return rule.timezone, start, end


def expand_for_user(user):
    bounds = {}
    for rule_id in EventRecurrence.objects.filter(user=user).values_list("id", flat=True):
        zone, start, end = expand(rule_id)
        bounds[zone] = (start, end)
    visible = Q(recurrence__isnull=True)
    for zone, (start, end) in bounds.items():
        visible |= Q(recurrence__timezone=zone, start_at__gte=start, start_at__lt=end)
    return visible


@transaction.atomic
def delete_occurrence(event):
    if event.recurrence_id is not None:
        rule = EventRecurrence.objects.select_for_update().get(pk=event.recurrence_id)
        rule.excluded_dates = sorted(set(rule.excluded_dates) | {event.recurrence_date.isoformat()})
        rule.save(update_fields=["excluded_dates"])
    event.delete()


# ---------------------------------------------------------------------------
# iter4 A2: "이 일정만 / 이후 일정 모두". (Partly AI-generated (Claude))
# `edits` 는 함수 안에서 가져온다. `edits` → `planning.refresh` → 이 모듈 순으로 돌아온다.
# ---------------------------------------------------------------------------

THIS = "this"
FOLLOWING = "following"
SCOPES = (THIS, FOLLOWING)

# 옮긴 첫 회차를 찾을 때 볼 최대 날 수. 지운 날이 몇 주 이어져도 찾는다.
_FIRST_DAY_LOOKAHEAD = 400


class ScopeError(Exception):
    """범위 수정이 할 수 없는 요청이다. 뷰가 그 필드의 400 으로 바꾼다."""

    def __init__(self, field: str, message: str):
        super().__init__(message)
        self.field = field
        self.message = message


def lock_occurrence(event):
    """회차와 그 규칙을 잠그고 다시 읽는다. 반복이 아니면 `(None, 다시 읽은 일정)`.

    잠금 순서는 `expand`·`delete_occurrence` 와 같다(규칙 먼저). 잠그기 전에 다른 요청이 이 회차를
    다른 규칙으로 옮겼으면(동시에 온 "이후 일정 모두") 새 규칙으로 다시 잠근다. 그사이 지워졌으면
    `Event.DoesNotExist` 다.
    """
    for _ in range(5):
        rule = None
        if event.recurrence_id is not None:
            rule = EventRecurrence.objects.select_for_update().get(pk=event.recurrence_id)
        fresh = (
            Event.objects.select_for_update(of=("self",))
            .select_related("place", "tag", "alarm_plan", "user__profile", "recurrence")
            .get(pk=event.pk)
        )
        if fresh.recurrence_id == (rule.pk if rule else None):
            if rule is not None:
                fresh.recurrence = rule
            return rule, fresh
        event = fresh
    raise ScopeError("scope", "다른 수정과 겹쳤어요. 잠시 뒤 다시 시도해 주세요.")


def _rule_time(rule):
    return rule.start_at.astimezone(ZoneInfo(rule.timezone)).time()


def _fill_from_rule(event, rule, day):
    """회차를 규칙 값으로 맞춘다(저장하지 않는다). 시각은 규칙 시간대의 벽시계 시각이다.

    "안 감"·알람 켬/끔·준비 학습 제외처럼 수정이 아닌 회차별 표시는 건드리지 않는다.
    """
    template = _live_template(rule)
    for field in TEMPLATE_FIELDS:
        if field in template:
            setattr(event, field, template[field])
    event.include_prep = template.get("include_prep")
    event.origin_place_id = template.get("origin_place_id")
    event.place = rule.place
    event.tag = rule.tag
    event.recurrence = rule
    event.recurrence_date = day
    event.start_at = datetime.combine(day, _rule_time(rule), tzinfo=ZoneInfo(rule.timezone))


def reset_to_rule(event):
    """이 회차를 규칙 값으로 되돌린다(저장하지 않는다). "이후 일정 모두" 수정 전에 부른다.

    수정 본문은 바뀐 필드뿐이라 **규칙 값 + 이번 수정** 이 새 규칙이 된다. 이 회차를 전에 따로
    고쳤어도 다른 이후 회차처럼 덮어쓴다.
    """
    _fill_from_rule(event, event.recurrence, event.recurrence_date)


def _first_day(day, weekdays, excluded, until):
    for _ in range(_FIRST_DAY_LOOKAHEAD):
        if until is not None and day > until:
            return None
        if day.weekday() in weekdays and day.isoformat() not in excluded:
            return day
        day += timedelta(days=1)
    return None


def replan_after_edit(event, before, *, start_before):
    """고친 회차(또는 일정)의 계획을 다시 만든다. `PATCH` 의 두 범위가 같은 규칙을 쓴다.

    창 밖 회차는 지난 계획을 남기고 날짜를 옮겼을 때만 지운다. 다른 날로 옮기면 옮겨 간 날의
    수동 켬/끔을 돌린다(BE-25). 이 일정의 선택은 그대로 둔다.
    """
    from . import edits

    if not in_window(event):
        # A late switch must preserve the historical plan. Only a moved date clears it.
        if event.start_at != start_before:
            plan = getattr(event, "alarm_plan", None)
            if plan is not None:
                plan.delete()
    else:
        # 제목·켬/끔만 바꾸면 기존 계획을 쓰고, 시각·τ 만 바꾸면 경로를 다시 조회하지 않는다.
        edits.replan(event, before, start_before=start_before)
    tz = user_timezone(event.user)
    if event.start_at.astimezone(tz).date() != start_before.astimezone(tz).date():
        with transaction.atomic():
            reset_alarm_overrides(event)


@transaction.atomic
def split_following(event, *, weekdays, before, start_before):
    """이 회차부터 반복 규칙을 바꾼다(`PATCH` `scope: following`). 이 회차가 속한 규칙을 돌려준다.

    부르기 전: `lock_occurrence` 로 규칙과 회차를 잠갔고, `reset_to_rule` 뒤 이번 수정을 저장해
    `event` 가 새 값(규칙 값 + 바뀐 필드)을 들고 있다. `before`·`start_before` 는 수정 전 값이다.

    1. 이 회차의 원래 자리(`recurrence_date`)가 나누는 날이다. 그 전 회차는 바꾸지 않는다.
    2. 새 규칙의 첫 날은 고친 시작 날짜부터 처음 맞는 요일이다(생성의 `first_start` 와 같다).
       요일·날짜를 바꾸면 이 회차가 그 날로 옮겨 간다.
    3. 이 회차가 규칙의 첫 회차면 규칙을 그 자리에서 고친다. 아니면 원래 규칙을 전날로 끝내고
       (`until`) 새 규칙을 만든다. 나누는 날 이후의 제외 날짜(지운 회차)는 새 규칙으로 옮긴다.
    4. 이미 펼친 이후 회차는 새 규칙에 다시 잇고 새 값으로 덮어쓴다(따로 고친 값도). id 와 회차별
       표시(안 감·켬/끔)는 남긴다. 새 규칙에 없는 날의 회차는 지운다. 그다음 창을 다시 펼친다.
    """
    from . import edits

    rule = event.recurrence
    tz = ZoneInfo(rule.timezone)
    split_day = event.recurrence_date
    local = event.start_at.astimezone(tz)
    if local.date() < split_day:
        raise ScopeError("start_at", "이후 일정 모두를 고칠 때는 이 회차보다 앞 날짜로 옮길 수 없어요.")
    excluded = set(rule.excluded_dates)
    first = _first_day(local.date(), weekdays, excluded, rule.until)
    if first is None:
        raise ScopeError("start_at", "반복이 끝나는 날 뒤로는 옮길 수 없어요.")
    anchor = datetime.combine(first, local.time(), tzinfo=tz)
    template = _template_of(event)
    followers = list(
        rule.occurrences.filter(recurrence_date__gte=split_day).exclude(pk=event.pk)
        .select_for_update(of=("self",)).select_related("alarm_plan", "user__profile")
    )

    if split_day <= rule.start_at.astimezone(tz).date():
        target = rule
        target.start_at, target.weekdays, target.template = anchor, list(weekdays), template
        target.place, target.tag = event.place, event.tag
        target.save(update_fields=["start_at", "weekdays", "template", "place", "tag"])
    else:
        target = EventRecurrence.objects.create(
            user=rule.user, start_at=anchor, timezone=rule.timezone, weekdays=list(weekdays),
            template=template, place=event.place, tag=event.tag, until=rule.until,
            excluded_dates=sorted(d for d in excluded if d >= split_day.isoformat()),
        )
        rule.until = split_day - timedelta(days=1)
        rule.excluded_dates = sorted(d for d in excluded if d < split_day.isoformat())
        rule.save(update_fields=["until", "excluded_dates"])

    # 이 회차가 옮겨 갈 자리에 다른 회차가 있으면 비운다(같은 규칙·같은 날은 하나뿐이다).
    kept = []
    for other in followers:
        if other.recurrence_date == first:
            other.delete()
        else:
            kept.append(other)
    event.recurrence, event.recurrence_date, event.start_at = target, first, anchor
    event.save(update_fields=["recurrence", "recurrence_date", "start_at", "updated_at"])
    replan_after_edit(event, before, start_before=start_before)

    for other in kept:
        day = other.recurrence_date
        if (day < first or day.weekday() not in target.weekdays
                or day.isoformat() in target.excluded_dates
                or (target.until is not None and day > target.until)):
            other.delete()
            continue
        other_before, other_start = edits.plan_inputs(other), other.start_at
        _fill_from_rule(other, target, day)
        other.save()
        replan_after_edit(other, other_before, start_before=other_start)

    expand(target.pk, reset_created=True)
    return target


@transaction.atomic
def delete_following(event):
    """이 회차와 이후 회차를 지우고 규칙을 전날로 끝낸다(`DELETE ?scope=following`).

    반복이 아니면 그냥 지운다. 이 회차가 첫 회차라 남는 회차가 없으면 규칙도 지운다.
    """
    rule, event = lock_occurrence(event)
    if rule is None:
        event.delete()
        return
    split_day = event.recurrence_date
    rule.occurrences.filter(recurrence_date__gte=split_day).delete()
    if not rule.occurrences.exists():
        rule.delete()
        return
    until = split_day - timedelta(days=1)
    rule.until = until if rule.until is None else min(rule.until, until)
    rule.excluded_dates = sorted(d for d in rule.excluded_dates if d < split_day.isoformat())
    rule.save(update_fields=["until", "excluded_dates"])
