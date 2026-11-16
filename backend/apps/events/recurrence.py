"""Bounded weekly expansion; existing IDs, manual switches and exclusions survive reads.

iter4 A2: "이후 일정 모두" 수정(`split_following`)·삭제(`delete_following`)와 반복 끝(`until`).
규칙은 docs/iter4/server-recurrence-scope.md. (Partly AI-generated (Claude))
"""

from collections import defaultdict
from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo

from django.db import transaction
from django.db.models import F, Q
from django.utils import timezone

from apps.planning import services
from apps.planning.models import PlanRevision

from .alarms import reset_alarm_overrides, user_timezone
from .models import Event, EventRecurrence

TEMPLATE_FIELDS = (
    "title", "destination_is_home", "tau_override", "route_key",
    "origin_lat", "origin_lng", "origin_label",
)

# 회차별 표시. 수정이 아니라 **그날의** 선택이다("안 감", 알람 켬/끔, 준비 학습 제외).
# "이후 일정 모두" 가 값을 덮어도 남기고, 서버가 행을 다른 날로 옮기면 따라가지 않는다.
PER_DAY_FIELDS = ("skipped_at", "alarm_enabled", "prep_learning_excluded_at")


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
    if rule.until is not None:
        # 끝난 규칙에 끝 뒤 자리·끝 뒤 시각의 회차가 남아 있으면 나눈 뒤에 잘못 이어 붙은 행이다
        # (리뷰 A2-2). 보이지도 남기지도 않는다. 끝 전 자리에서 끝 뒤로 옮긴 회차("이 일정만")와
        # 끝 뒤 자리에서 끝 전으로 옮긴 회차는 사용자가 옮긴 것이라 남긴다.
        for slot, event in list(existing.items()):
            if slot > rule.until and event.start_at.astimezone(start.tzinfo).date() > rule.until:
                event.delete()
                del existing[slot]
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
    """목록 조회 전에 사용자의 규칙을 펼치고, 보일 회차의 조건(`Q`)을 돌려준다.

    **끝난 규칙(`until` 이 창 첫날 전)은 잠그지 않고 건너뛴다**(리뷰 A2-8). 새 회차가 생기지 않는다.
    창 안에 계획이 없는 회차(예전에 창 밖으로 옮겨 둔 회차)나 끝 뒤에 남은 행이 있을 때만 펼친다.
    """
    bounds = {}
    ended = []
    rules = EventRecurrence.objects.filter(user=user).order_by("pk").values_list("id", "timezone", "until")
    for rule_id, zone, until in rules:
        if zone not in bounds:
            bounds[zone] = window(zone)
        start, _ = bounds[zone]
        if until is not None and until < start.date():
            ended.append(rule_id)
        else:
            expand(rule_id)
    if ended:
        low = min(start for start, _ in bounds.values())
        high = max(end for _, end in bounds.values())
        stale = (
            Event.objects.filter(recurrence_id__in=ended, start_at__gte=low, start_at__lt=high)
            .filter(Q(alarm_plan__isnull=True) | Q(recurrence_date__gt=F("recurrence__until")))
            .values_list("recurrence_id", flat=True)
        )
        for rule_id in sorted(set(stale)):
            expand(rule_id)
    visible = Q(recurrence__isnull=True)
    for zone, (start, end) in bounds.items():
        visible |= Q(recurrence__timezone=zone, start_at__gte=start, start_at__lt=end)
    return visible


@transaction.atomic
def delete_occurrence(event):
    """한 회차만 지운다(`DELETE` 범위 없음·`this`). 그 자리를 회차가 **지금** 속한 규칙에서 뺀다.

    잠그기 전에 동시에 온 "이후 일정 모두" 가 이 회차를 새 규칙으로 옮겼을 수 있다. 예전 규칙에
    날짜를 빼면 새 규칙이 다음 조회에 그 회차를 다시 만든다(리뷰 A2-1). 그래서 `lock_occurrence` 로
    다시 읽은 회차와 그 규칙을 쓴다.
    """
    rule, event = lock_occurrence(event)
    if rule is not None:
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


def _family_ids(user_id, rule_id):
    """이 규칙과 `split_from` 으로 이어진 규칙 id 전부(한 반복). 뿌리부터 모든 자손까지다.

    없는 id 를 가리키는 `split_from`(구버전이 원래 규칙을 지웠다)은 묶음의 끝으로 본다.
    """
    links = dict(EventRecurrence.objects.filter(user_id=user_id).values_list("id", "split_from_id"))
    root, seen = rule_id, {rule_id}
    while links.get(root) in links and links[root] not in seen:
        root = links[root]
        seen.add(root)
    children = defaultdict(list)
    for pk, parent in links.items():
        children[parent].append(pk)
    family, stack = set(), [root]
    while stack:
        pk = stack.pop()
        if pk not in family:
            family.add(pk)
            stack.extend(children.get(pk, ()))
    return family


def _lock(event, *, series):
    """`lock_occurrence`·`lock_series` 의 공통 부분. 규칙들을 id 순으로 잠근 뒤 회차를 잠근다."""
    for _ in range(5):
        rules = {}
        if event.recurrence_id is not None:
            ids = _family_ids(event.user_id, event.recurrence_id) if series else {event.recurrence_id}
            rules = {
                rule.pk: rule
                for rule in EventRecurrence.objects.select_for_update().filter(pk__in=ids).order_by("pk")
            }
        fresh = (
            Event.objects.select_for_update(of=("self",))
            .select_related("place", "tag", "alarm_plan", "user__profile", "recurrence")
            .get(pk=event.pk)
        )
        rule = rules.get(fresh.recurrence_id)
        if fresh.recurrence_id is None and not rules:
            return None, fresh, []
        if rule is not None and (not series or _family_ids(fresh.user_id, rule.pk) == set(rules)):
            fresh.recurrence = rule
            return rule, fresh, [rules[pk] for pk in sorted(rules)]
        event = fresh
    raise ScopeError("scope", "다른 수정과 겹쳤어요. 잠시 뒤 다시 시도해 주세요.")


def lock_occurrence(event):
    """회차와 그 규칙을 잠그고 다시 읽는다. 반복이 아니면 `(None, 다시 읽은 일정)`.

    잠금 순서는 `expand` 와 같다(규칙 먼저). 잠그기 전에 다른 요청이 이 회차를
    다른 규칙으로 옮겼으면(동시에 온 "이후 일정 모두") 새 규칙으로 다시 잠근다. 그사이 지워졌으면
    `Event.DoesNotExist` 다. "이 일정만" 수정·삭제가 쓴다.
    """
    rule, fresh, _ = _lock(event, series=False)
    return rule, fresh


def lock_series(event):
    """`lock_occurrence` 와 같되 이 반복의 나눈 규칙 전부(`_family_ids`)를 id 순으로 잠근다.

    `(이 회차의 규칙, 다시 읽은 회차, 묶음의 규칙 목록)`. "이후 일정 모두" 수정·삭제가 쓴다.
    """
    return _lock(event, series=True)


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


def _first_day(day, weekdays, excluded, until):
    for _ in range(_FIRST_DAY_LOOKAHEAD):
        if until is not None and day > until:
            return None
        if day.weekday() in weekdays and day.isoformat() not in excluded:
            return day
        day += timedelta(days=1)
    return None


def _fits(day, *, first, weekdays, excluded, until):
    return (
        day >= first and day.weekday() in weekdays and day.isoformat() not in excluded
        and (until is None or day <= until)
    )


def _local_day(event, tz):
    return event.start_at.astimezone(tz).date()


def _clear_alarm_choice(event):
    """덮어쓴 이후 회차의 직접 정한 알람 시각·확인·제안을 지운다(리뷰 A2-7). 새 회차와 같아진다.

    `alarm_control` 의 공개 함수(`locked_plan`·`save`·`Control`)만 쓴다. 빈 `Control()` 이 사용자
    상태가 없는 계획이다. 계획이 없으면(창 밖) 할 일이 없다.
    """
    from apps.planning import alarm_control

    plan = alarm_control.locked_plan(event)
    if plan is not None:
        alarm_control.save(plan, alarm_control.Control())


def _end_rule(rule, split_day, vacated):
    """묶음의 다른 규칙을 나누는 날 전날로 끝낸다(이미 그 전에 끝났으면 그대로).

    나누는 날 이후의 지운 날짜는 버린다(새 규칙이 가져갔거나 삭제로 필요 없다). `vacated` 는
    나누는 날 전 자리에서 그 뒤로 옮겨 가 있다가 이번에 떠난 회차의 자리다. 그 자리를 다시 만들지
    않게 지운 날짜로 남긴다(리뷰 A2-6).
    """
    split_iso = split_day.isoformat()
    if rule.until is None or rule.until >= split_day:
        rule.until = split_day - timedelta(days=1)
    rule.excluded_dates = sorted(
        {d for d in rule.excluded_dates if d < split_iso} | {d.isoformat() for d in vacated}
    )
    rule.save(update_fields=["until", "excluded_dates"])


def _drop_if_empty(rule, split_day, today):
    """회차가 하나도 남지 않고 더 만들 자리도 없는 규칙을 지운다. 자식 규칙은 부모에 잇는다."""
    if rule.occurrences.exists():
        return
    anchor_day = rule.start_at.astimezone(ZoneInfo(rule.timezone)).date()
    if anchor_day < split_day and rule.until is not None and rule.until >= today:
        return  # 아직 펼치지 않은 앞 자리가 남아 있다
    parent = EventRecurrence.objects.filter(pk=rule.pk).values_list("split_from_id", flat=True).first()
    EventRecurrence.objects.filter(split_from_id=rule.pk).update(split_from_id=parent)
    rule.delete()


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
def split_following(event, *, family, weekdays, before, start_before, sent=frozenset()):
    """이 회차부터 반복을 바꾼다(`PATCH` `scope: following`). 이 회차가 속한 규칙을 돌려준다.

    부르기 전: `lock_series` 로 묶음의 규칙(`family`)과 회차를 잠갔고, 이번 수정을 이 회차에
    저장했다. `event` 는 **사용자가 본 이 회차의 값 + 이번 수정** 이고 그대로 새 반복의 값이 된다
    (리뷰 A2-4, 아이폰과 같다). `before`·`start_before` 는 수정 전 값, `sent` 는 본문에 실린 필드다.
    규칙은 docs/iter4/server-recurrence-scope.md 1.1.

    - 나누는 날 = 이 회차가 **보이던** 현지 날짜(수정 전 시작). 회차를 앞·뒤로 가르는 것도 원래
      자리(`recurrence_date`)가 아니라 실제 시작 날짜다(리뷰 A2-6).
    - 묶음의 모든 규칙(앞서 나눈 뒤쪽 규칙 포함, 리뷰 A2-5)에서 나누는 날 이후 회차를 새 반복이
      가져간다. 다른 규칙은 전날로 끝내고, 회차가 남지 않으면 지운다.
    """
    from . import edits

    rule = event.recurrence
    tz = ZoneInfo(rule.timezone)
    split_day = start_before.astimezone(tz).date()
    split_at = datetime.combine(split_day, time.min, tzinfo=tz)
    split_iso = split_day.isoformat()
    local = event.start_at.astimezone(tz)
    if local.date() < split_day:
        raise ScopeError("start_at", "이후 일정 모두를 고칠 때는 이 회차보다 앞 날짜로 옮길 수 없어요.")
    family_ids = [part.pk for part in family]

    # 새 반복의 끝은 나누는 날 뒤를 맡던 부분의 끝이다(하나라도 끝이 없으면 끝없음).
    later = [part for part in family if part.until is None or part.until >= split_day]
    if any(part.until is None for part in later):
        until = None
    elif later:
        until = max(part.until for part in later)
    else:
        until = rule.until
    excluded = {d for part in family for d in part.excluded_dates if d >= split_iso}
    # 나누는 날 뒤 자리를 앞으로 옮겨 둔 회차는 그 자리를 쓰고 있다. 새 반복이 다시 만들지 않는다.
    excluded |= {
        day.isoformat() for day in Event.objects.filter(
            recurrence_id__in=family_ids, start_at__lt=split_at, recurrence_date__gte=split_day,
        ).values_list("recurrence_date", flat=True)
    }
    first = _first_day(local.date(), weekdays, excluded, until)
    if first is None:
        raise ScopeError("start_at", "반복이 끝나는 날 뒤로는 옮길 수 없어요.")
    anchor = datetime.combine(first, local.time(), tzinfo=tz)
    template = _template_of(event)
    followers = list(
        Event.objects.filter(recurrence_id__in=family_ids, start_at__gte=split_at)
        .exclude(pk=event.pk).order_by("pk")
        .select_for_update(of=("self",)).select_related("alarm_plan", "user__profile")
    )

    if split_day <= rule.start_at.astimezone(tz).date():
        # 이 회차가 규칙의 첫 회차다. 규칙을 그 자리에서 고친다.
        target = rule
        target.start_at, target.weekdays, target.template = anchor, list(weekdays), template
        target.place, target.tag, target.until = event.place, event.tag, until
        target.excluded_dates = sorted({d for d in rule.excluded_dates if d < split_iso} | excluded)
        target.save(update_fields=["start_at", "weekdays", "template", "place", "tag", "until", "excluded_dates"])
    else:
        target = EventRecurrence.objects.create(
            user=rule.user, start_at=anchor, timezone=rule.timezone, weekdays=list(weekdays),
            template=template, place=event.place, tag=event.tag, until=until,
            excluded_dates=sorted(excluded), split_from=rule,
        )

    # 이후 회차를 새 반복의 날에 놓는다. 원래 자리가 나누는 날 이후면 그 자리, 앞 자리에서 옮겨 와
    # 있던 회차면 보이던 날. 같은 날에 둘이면 그날이 원래 자리인 회차를 남긴다.
    vacated = defaultdict(set)
    if event.recurrence_date < split_day:
        vacated[rule.pk].add(event.recurrence_date)
    placed, doomed = {}, []
    for other in followers:
        slot = other.recurrence_date
        day = slot if slot >= split_day else _local_day(other, tz)
        if slot < split_day:
            vacated[other.recurrence_id].add(slot)
        if not _fits(day, first=first, weekdays=weekdays, excluded=excluded, until=until):
            doomed.append(other)
            continue
        keeper = placed.get(day)
        if keeper is None:
            placed[day] = other
        elif slot == day and keeper.recurrence_date != day:
            doomed.append(keeper)
            placed[day] = other
        else:
            doomed.append(other)

    # 이 회차가 다른 날로 옮겨 가면 그날의 표시를 쓴다(리뷰 A2-3). 그날 회차가 있었으면 그 회차의
    # 표시를 받고 그 회차를 지운다. 없었으면 비운다. 본문에 실은 켬/끔은 이번 선택이라 그대로 둔다.
    donor = placed.pop(first, None)
    if donor is not None:
        doomed.append(donor)
    if first != split_day:
        for field in PER_DAY_FIELDS:
            if field not in sent:
                setattr(event, field, getattr(donor, field) if donor is not None else None)
    Event.objects.filter(pk__in=[other.pk for other in doomed]).delete()

    event.recurrence, event.recurrence_date, event.start_at = target, first, anchor
    event.save(update_fields=["recurrence", "recurrence_date", "start_at", *PER_DAY_FIELDS, "updated_at"])
    replan_after_edit(event, before, start_before=start_before)

    for day in sorted(placed):
        other = placed[day]
        other_before, other_start = edits.plan_inputs(other), other.start_at
        moved = day != _local_day(other, tz)
        _fill_from_rule(other, target, day)
        if moved:
            for field in PER_DAY_FIELDS:
                setattr(other, field, None)
        other.save()
        replan_after_edit(other, other_before, start_before=other_start)
        _clear_alarm_choice(other)

    today = timezone.now().astimezone(tz).date()
    for part in family:
        if part.pk != target.pk:
            _end_rule(part, split_day, vacated.get(part.pk, ()))
    for part in family:
        if part.pk != target.pk:
            _drop_if_empty(part, split_day, today)

    expand(target.pk, reset_created=True)
    return target


@transaction.atomic
def delete_following(event):
    """이 회차와 이후 회차를 지우고 반복을 전날로 끝낸다(`DELETE ?scope=following`).

    반복이 아니면 그냥 지운다. 앞서 나눈 뒤쪽 규칙까지 묶음 전체에서 이 회차가 보이던 날 이후의
    회차를 지운다(리뷰 A2-5·A2-6). 회차가 남지 않은 규칙은 지운다.
    """
    rule, event, family = lock_series(event)
    if rule is None:
        event.delete()
        return
    tz = ZoneInfo(rule.timezone)
    split_day = _local_day(event, tz)
    split_at = datetime.combine(split_day, time.min, tzinfo=tz)
    doomed = list(
        Event.objects.filter(recurrence_id__in=[part.pk for part in family], start_at__gte=split_at)
        .values_list("pk", "recurrence_id", "recurrence_date")
    )
    vacated = defaultdict(set)
    for _, rule_id, slot in doomed:
        if slot < split_day:
            vacated[rule_id].add(slot)
    Event.objects.filter(pk__in=[pk for pk, _, _ in doomed]).delete()
    today = timezone.now().astimezone(tz).date()
    for part in family:
        _end_rule(part, split_day, vacated.get(part.pk, ()))
    for part in family:
        _drop_if_empty(part, split_day, today)
