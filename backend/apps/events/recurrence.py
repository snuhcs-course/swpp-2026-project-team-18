"""Bounded weekly expansion; existing IDs, manual switches and exclusions survive reads."""

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


def attach(event, weekdays):
    tz = user_timezone(event.user)
    rule = EventRecurrence.objects.create(
        user=event.user, start_at=event.start_at, timezone=tz.key, weekdays=weekdays,
        template={field: getattr(event, field) for field in TEMPLATE_FIELDS},
        place=event.place, tag=event.tag,
    )
    event.recurrence = rule
    event.recurrence_date = event.start_at.astimezone(tz).date()
    event.save(update_fields=["recurrence", "recurrence_date"])


def in_window(event):
    if event.recurrence_id is None:
        return True
    start, end = window(event.recurrence.timezone)
    return start <= event.start_at < end


@transaction.atomic
def expand(rule_id, *, reset_choices=False):
    # The same lock protects expansion and deletion exclusions.
    rule = EventRecurrence.objects.select_for_update(of=("self",)).select_related("user__profile").get(pk=rule_id)
    start, end = window(rule.timezone)
    anchor = rule.start_at.astimezone(start.tzinfo)
    existing = {event.recurrence_date: event for event in rule.occurrences.filter(
        Q(recurrence_date__gte=start.date(), recurrence_date__lt=end.date())
        | Q(start_at__gte=start, start_at__lt=end),
    ).select_related("alarm_plan", "user__profile", "recurrence")}
    day = max(start.date(), anchor.date())
    while day < end.date():
        if day.weekday() in rule.weekdays and day.isoformat() not in rule.excluded_dates:
            event = existing.get(day)
            if event is None:
                existing[day] = Event.objects.create(
                    user=rule.user, recurrence=rule, recurrence_date=day,
                    start_at=datetime.combine(day, anchor.time(), tzinfo=start.tzinfo),
                    place=rule.place, tag=rule.tag, **rule.template,
                )
        day += timedelta(days=1)
    # A moved occurrence keeps its original slot, but needs its plan by actual date.
    for event in existing.values():
        if start <= event.start_at < end:
            if getattr(event, "alarm_plan", None) is None:
                services.compute_and_store(event, trigger=PlanRevision.Trigger.CREATE)
            if reset_choices:
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
