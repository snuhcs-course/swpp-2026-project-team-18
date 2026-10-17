"""사용자 시간대의 일정 시작 날짜별 알람 선택과 수동 선택 초기화."""

from datetime import datetime, time, timedelta
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from django.conf import settings
from django.utils import timezone

from apps.observations import alarm_logs
from apps.planning.models import AlarmPlan

from .models import Event


def _user_timezone(user):
    try:
        return ZoneInfo(user.profile.timezone)
    except (ZoneInfoNotFoundError, ValueError):
        return ZoneInfo(settings.TIME_ZONE)


def reset_alarm_overrides(event: Event) -> None:
    """직접 추가된 일정과 같은 현지 날짜의 기존 선택을 자동으로 돌린다.

    생성 트랜잭션 안에서 호출한다. 새 일정의 명시적 선택과 감사 로그는 보존한다.
    """
    tz = _user_timezone(event.user)
    day = event.start_at.astimezone(tz).date()
    start = datetime.combine(day, time.min, tzinfo=tz)
    end = datetime.combine(day + timedelta(days=1), time.min, tzinfo=tz)
    previous = list(
        Event.objects.filter(
            user=event.user, start_at__gte=start, start_at__lt=end,
            alarm_enabled__isnull=False,
        ).exclude(pk=event.pk).select_related("alarm_plan").select_for_update(of=("self",))
    )
    if not previous:
        return
    Event.objects.filter(pk__in=[item.pk for item in previous]).update(
        alarm_enabled=None, updated_at=timezone.now(),
    )
    for item in previous:
        alarm_logs.record_switch(item, item.alarm_enabled, None)


def alarm_states(user) -> dict[int, tuple[bool, bool]]:
    """id → (alarm_on, is_first_alarm). 계산 불가·명시적 OFF는 제외한다."""
    tz = _user_timezone(user)

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
