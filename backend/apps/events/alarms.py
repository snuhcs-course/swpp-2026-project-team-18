"""사용자 시간대의 일정 시작 날짜별 유효 알람 판정."""

from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from django.conf import settings

from apps.planning.models import AlarmPlan

from .models import Event


def alarm_states(user) -> dict[int, tuple[bool, bool]]:
    """id → (alarm_on, is_first_alarm). 계산 불가·명시적 OFF는 제외한다."""
    try:
        tz = ZoneInfo(user.profile.timezone)
    except (ZoneInfoNotFoundError, ValueError):
        tz = ZoneInfo(settings.TIME_ZONE)

    events = (
        Event.objects.filter(
            user=user, alarm_plan__status=AlarmPlan.Status.OK,
            alarm_plan__alarm_at__isnull=False,
        )
        .exclude(alarm_enabled=False)
        .order_by("alarm_plan__alarm_at", "id")
        .values_list("id", "start_at", "alarm_enabled")
    )
    days = set()
    states = {}
    for event_id, start_at, enabled in events:
        day = start_at.astimezone(tz).date()
        first = day not in days
        days.add(day)
        states[event_id] = (enabled is True or first, first)
    return states
