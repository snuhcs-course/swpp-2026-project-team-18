# Partly AI-generated (Claude)
"""알람 직접 정하기 · 확인 · "바꿀까요" 답하기 API (iter4 H2-3, BE-H3a·b·d).

    PUT    /api/events/{id}/alarm/time              직접 정한 시각 저장 {"alarm_at"}
    DELETE /api/events/{id}/alarm/time              추천 시각으로 되돌리기
    POST   /api/events/{id}/alarm/confirm           이 시각으로 알람 맞추기 {"alarm_at"?}
    POST   /api/events/{id}/alarm/proposal/accept   제안 받아들이기 {"proposed_alarm_at"?}
    POST   /api/events/{id}/alarm/proposal/reject   제안 거절(그대로 두기) {"proposed_alarm_at"?}

응답은 모두 일정 객체(`GET /api/events/{id}` 와 같은 모양)다. 카카오를 부르지 않는다.
규칙은 `alarm_control`, 계약은 docs/iter4/server-alarm-control.md.
"""

from __future__ import annotations

from django.db import transaction
from django.utils import timezone
from rest_framework import serializers, status
from rest_framework.response import Response
from rest_framework.views import APIView

from apps.events.models import Event

from . import alarm_control
from .alarm_control import Control


def _error(code: str, message: str, http_status: int, details: dict | None = None) -> Response:
    return Response(
        {"error": {"code": code, "message": message, "details": details or {}}},
        status=http_status,
    )


def _iso(value):
    return None if value is None else serializers.DateTimeField().to_representation(value)


class _BadTime(Exception):
    pass


def _parse_time(raw, *, required: bool, tz=None):
    """ISO 8601 시각. 초 이하는 버린다(알람은 분 단위다). 비었고 필수가 아니면 None.

    오프셋이 없는 시각(`2026-11-17T07:50:00`)은 **사용자 시간대**(`tz`, 프로필 `timezone`)의 벽시계
    시각으로 읽는다. 서버 TIME_ZONE 으로 읽으면 시간대가 다른 사용자의 알람이 몇 시간 어긋난다.
    """
    if raw in (None, ""):
        if required:
            raise _BadTime
        return None
    try:
        value = serializers.DateTimeField(default_timezone=tz).to_internal_value(raw)
    except serializers.ValidationError as exc:
        raise _BadTime from exc
    return value.replace(second=0, microsecond=0)


def _same_minute(a, b) -> bool:
    return a is not None and b is not None and a.replace(second=0, microsecond=0) == b.replace(
        second=0, microsecond=0
    )


class _EventAlarmView(APIView):
    """내 일정만 다룬다. 없는 일정·남의 일정은 본문 없는 404(skip 과 같다)."""

    def _event(self, request, pk: int):
        return (
            Event.objects.filter(user=request.user, pk=pk)
            .select_related("place", "tag", "alarm_plan", "user__profile", "recurrence")
            .first()
        )

    @staticmethod
    def _parse(event, raw, *, required: bool):
        """오프셋 없는 시각은 일정 주인의 시간대로 읽는다(`_parse_time`)."""
        from apps.events.alarms import user_timezone

        return _parse_time(raw, required=required, tz=user_timezone(event.user))

    def _respond(self, request, event):
        from apps.events.serializers import EventSerializer

        event.refresh_from_db()
        return Response(EventSerializer(event, context={"request": request}).data)

    def _locked(self, event):
        """잠근 계획과 그 추천 시각. 계산하지 못한 계획이면 (계획, None)."""
        plan = alarm_control.locked_plan(event)
        if plan is None or plan.status != plan.Status.OK or plan.alarm_at is None:
            return plan, None
        return plan, plan.alarm_at

    @staticmethod
    def _unavailable():
        return _error(
            "alarm_unavailable",
            "알람을 아직 계산하지 못했어요. 알람이 정해진 뒤에 다시 해 주세요.",
            status.HTTP_409_CONFLICT,
        )


class AlarmTimeView(_EventAlarmView):
    """직접 정한 시각(BE-H3b). PUT 은 저장, DELETE 는 추천 시각으로 되돌리기."""

    def put(self, request, pk: int):
        event = self._event(request, pk)
        if event is None:
            return Response(status=status.HTTP_404_NOT_FOUND)
        data = request.data if hasattr(request.data, "get") else {}
        try:
            at = self._parse(event, data.get("alarm_at"), required=True)
        except _BadTime:
            return _error(
                "invalid_alarm_time",
                "알람 시각을 읽을 수 없어요. ISO 8601 시각(예: 2026-11-16T07:50:00+09:00)으로 보내 주세요.",
                status.HTTP_400_BAD_REQUEST,
            )
        now = timezone.now()
        start = event.start_at
        bounds = {
            "earliest": _iso(start - alarm_control.USER_MAX_LEAD),
            "latest": _iso(start - alarm_control.USER_MIN_LEAD),
        }
        if at <= now:
            return _error(
                "alarm_time_past", "이미 지난 시각으로는 알람을 맞출 수 없어요.",
                status.HTTP_400_BAD_REQUEST, bounds,
            )
        if at > start - alarm_control.USER_MIN_LEAD:
            return _error(
                "alarm_time_too_late", "알람은 일정 시작 5분 전까지만 맞출 수 있어요.",
                status.HTTP_400_BAD_REQUEST, bounds,
            )
        if at < start - alarm_control.USER_MAX_LEAD:
            return _error(
                "alarm_time_too_early", "알람은 일정 시작 24시간 전부터 맞출 수 있어요.",
                status.HTTP_400_BAD_REQUEST, bounds,
            )
        with transaction.atomic():
            plan, recommended = self._locked(event)
            if recommended is None:
                return self._unavailable()
            control = Control.of(plan)
            if _same_minute(at, recommended):
                # 휠로 추천 시각 그대로를 골랐다. 직접 정한 시각으로 굳히면 다시 계산해도 그 시각에
                # 묶이므로 [추천 시각으로 되돌리기] 와 같게 둔다(추천 시각 · 확인함).
                control.use_recommended(recommended, now)
            else:
                probability = alarm_control.probability_now(event, plan, at)
                control.set_user(at, start, probability, now)
            alarm_control.save(plan, control)
        return self._respond(request, event)

    def delete(self, request, pk: int):
        event = self._event(request, pk)
        if event is None:
            return Response(status=status.HTTP_404_NOT_FOUND)
        with transaction.atomic():
            # Lock the event before its plan: restoring the default includes undoing an
            # earlier quick-pick tau override, not just clearing a manually entered clock.
            event = Event.objects.select_for_update().filter(user=request.user, pk=pk).first()
            if event is None:
                return Response(status=status.HTTP_404_NOT_FOUND)
            plan, recommended = self._locked(event)
            if recommended is None:
                return self._unavailable()
            if event.tau_override is not None:
                from . import refresh

                event.tau_override = None
                event.save(update_fields=["tau_override", "updated_at"])
                # Reuse the saved upstream route. Restoring a choice must not spend
                # provider quota or replace it with a stale/guessed recommendation.
                plan = refresh.refresh_edited(event, start_before=event.start_at)
                if plan is None:
                    transaction.set_rollback(True)
                    return _error(
                        "alarm_recommendation_unavailable",
                        "기본 추천을 다시 계산할 정보가 없어요. 일정에서 다시 계산한 뒤 시도해 주세요.",
                        status.HTTP_409_CONFLICT,
                    )
                recommended = plan.alarm_at
            control = Control.of(plan)
            control.use_recommended(recommended, timezone.now())
            alarm_control.save(plan, control)
        return self._respond(request, event)


class AlarmConfirmView(_EventAlarmView):
    """[이 시각으로 알람 맞추기](BE-H3a). 지금 울릴 시각을 확인한 것으로 둔다. 두 번 불러도 같다."""

    def post(self, request, pk: int):
        event = self._event(request, pk)
        if event is None:
            return Response(status=status.HTTP_404_NOT_FOUND)
        data = request.data if hasattr(request.data, "get") else {}
        try:
            expected = self._parse(event, data.get("alarm_at"), required=False)
        except _BadTime:
            return _error(
                "invalid_alarm_time", "알람 시각을 읽을 수 없어요.", status.HTTP_400_BAD_REQUEST,
            )
        now = timezone.now()
        with transaction.atomic():
            plan, recommended = self._locked(event)
            if recommended is None:
                return self._unavailable()
            control = Control.of(plan)
            control.settle(recommended, event.start_at, now)
            effective = control.current(recommended, event.start_at)
            if expected is not None and not _same_minute(expected, effective):
                # 마감이 지나 처리된 제안은 저장해 둔다. 앱은 새 시각을 다시 보여 주고 묻는다.
                alarm_control.save(plan, control)
                return _error(
                    "alarm_changed",
                    "그사이 알람 시각이 바뀌었어요. 새 시각을 확인해 주세요.",
                    status.HTTP_409_CONFLICT,
                    {"effective_alarm_at": _iso(effective)},
                )
            control.confirm(effective, now)
            alarm_control.save(plan, control)
        return self._respond(request, event)


class AlarmProposalView(_EventAlarmView):
    """"바꿀까요" 제안에 답한다(BE-H3d). `answer` 는 URL 의 accept / reject."""

    answer: str = ""

    def post(self, request, pk: int):
        event = self._event(request, pk)
        if event is None:
            return Response(status=status.HTTP_404_NOT_FOUND)
        data = request.data if hasattr(request.data, "get") else {}
        try:
            expected = self._parse(event, data.get("proposed_alarm_at"), required=False)
        except _BadTime:
            return _error(
                "invalid_alarm_time", "제안 시각을 읽을 수 없어요.", status.HTTP_400_BAD_REQUEST,
            )
        now = timezone.now()
        with transaction.atomic():
            plan, recommended = self._locked(event)
            if recommended is None:
                return self._unavailable()
            control = Control.of(plan)
            if control.settle(recommended, event.start_at, now):
                alarm_control.save(plan, control)
                return _error(
                    "alarm_proposal_expired",
                    "답할 시간이 지나 알람 시각을 정해 두었어요.",
                    status.HTTP_409_CONFLICT,
                    {"effective_alarm_at": _iso(control.current(recommended, event.start_at))},
                )
            if not control.has_proposal:
                return _error(
                    "no_alarm_proposal", "답할 알람 변경 제안이 없어요.", status.HTTP_409_CONFLICT,
                )
            if expected is not None and not _same_minute(expected, control.proposed_alarm_at):
                return _error(
                    "alarm_proposal_changed",
                    "그사이 제안이 바뀌었어요. 새 제안을 확인해 주세요.",
                    status.HTTP_409_CONFLICT,
                    {"proposed_alarm_at": _iso(control.proposed_alarm_at)},
                )
            if self.answer == "accept":
                control.accept(recommended, now)
            else:
                control.reject(recommended, event.start_at, now)
            alarm_control.save(plan, control)
        return self._respond(request, event)
