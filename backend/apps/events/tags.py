# Partly AI-generated (Claude)
"""일정 종류(BE-21, 화면 12-a 의 "+ 추가"). 계약은 `docs/iter3/event-tags-api.md`.

| 경로 | 하는 일 |
| --- | --- |
| `GET /api/events/tags` | 전역 종류(정렬 순서대로) 다음에 내가 만든 종류(만든 순서). `custom` 으로 구분 |
| `POST /api/events/tags` | 종류 만들기. 같은 이름이 이미 보이면 그것을 200 으로 돌려준다 |
| `PATCH /api/events/tags/{key}` | 내 종류의 이름·τ 바꾸기 |
| `DELETE /api/events/tags/{key}` | 내 종류 지우기. 그 종류를 단 일정은 종류 없음이 된다 |

**규칙.**

- 이름은 앞뒤 공백을 지우고 가운데 공백을 하나로 줄인다. 1~[LABEL_MAX]자.
- 같은 이름은 대소문자를 가리지 않고 같다. 전역 "시험" 이 있는데 "시험" 을 만들면 전역 "시험" 을
  돌려준다(200). 앱은 돌려받은 것을 고르면 된다. 새로 만들면 201.
- 사용자당 [EventTag.MAX_PER_USER]개(409 `tag_limit`).
- `default_tau` 는 0.5~0.999 이거나 null(생략). null 이면 프로필 기본 τ 를 따른다.
- 전역 종류와 남의 종류는 고치거나 지울 수 없다(404, 존재도 알리지 않는다).

**τ 를 바꾸거나 지우면** 그 종류를 단 앞으로의 일정 알람을 경로 조회 없이 다시 섞는다
(`planning.refresh.refresh_upcoming`, 카카오 0회). 이미 울렸거나 출발한 일정은 그대로 둔다.
"""

from __future__ import annotations

import secrets

from django.db import transaction
from django.db.models import F
from django.db.models.functions import Lower
from django.utils import timezone
from rest_framework import serializers, status
from rest_framework.exceptions import APIException, NotFound
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from .models import Event, EventTag
from .serializers import EventTagSerializer

LABEL_MAX = 20


class TagLimit(APIException):
    status_code = status.HTTP_409_CONFLICT
    default_code = "tag_limit"
    default_detail = f"종류는 {EventTag.MAX_PER_USER}개까지 만들 수 있다."


class TagDuplicate(APIException):
    status_code = status.HTTP_409_CONFLICT
    default_code = "tag_duplicate"
    default_detail = "같은 이름의 종류가 이미 있다."


class TagInputSerializer(serializers.Serializer):
    label = serializers.CharField(max_length=60, trim_whitespace=True)
    default_tau = serializers.FloatField(
        required=False, allow_null=True, min_value=0.5, max_value=0.999
    )

    def validate_label(self, value: str) -> str:
        label = " ".join(value.split())
        if not label:
            raise serializers.ValidationError("이름을 입력해야 한다.")
        if len(label) > LABEL_MAX:
            raise serializers.ValidationError(f"이름은 {LABEL_MAX}자까지다.")
        return label


def visible_tags(user) -> list[EventTag]:
    """전역 종류(정렬 순서) 다음에 이 사용자의 종류(만든 순서)."""
    shared = list(EventTag.objects.filter(user__isnull=True).order_by("order", "id"))
    own = list(EventTag.objects.filter(user=user).order_by("id"))
    return shared + own


def _same_label(user, label: str, *, exclude_pk=None) -> EventTag | None:
    """이 사용자에게 보이는 종류 중 이름이 같은 것(대소문자 무시). 전역을 먼저 본다."""
    qs = (
        EventTag.objects.visible_to(user)
        .annotate(label_lower=Lower("label"))
        .filter(label_lower=label.lower())
    )
    if exclude_pk is not None:
        qs = qs.exclude(pk=exclude_pk)
    return qs.order_by(F("user_id").asc(nulls_first=True), "id").first()


def _new_key() -> str:
    """전역 키(`exam` 같은 영단어)와 겹치지 않는 키. `u` + 16진수 10자리."""
    while True:
        key = f"u{secrets.token_hex(5)}"
        if not EventTag.objects.filter(key=key).exists():
            return key


def _refresh_tagged(user, had_future_events: bool) -> None:
    """종류의 τ 가 바뀌었으면 앞으로의 알람을 경로 조회 없이 다시 섞는다."""
    if not had_future_events:
        return
    from apps.planning import refresh
    from apps.planning.models import PlanRevision

    refresh.refresh_upcoming(user, trigger=PlanRevision.Trigger.UPDATE)


def _has_future_events(tag: EventTag) -> bool:
    return Event.objects.filter(
        user_id=tag.user_id, tag=tag, start_at__gte=timezone.now()
    ).exists()


class _WriteThrottleMixin:
    """쓰기만 센다. 목록(GET)은 일정 추가 화면을 열 때마다 부른다."""

    throttle_scope = "event_tag"

    def get_throttles(self):
        if self.request.method == "GET":
            return []
        return [ScopedRateThrottle()]


class EventTagListView(_WriteThrottleMixin, APIView):
    """GET·POST /api/events/tags — 종류 목록과 만들기."""

    throttle_classes = [ScopedRateThrottle]

    def get(self, request):
        return Response(EventTagSerializer(visible_tags(request.user), many=True).data)

    def post(self, request):
        data = TagInputSerializer(data=request.data)
        data.is_valid(raise_exception=True)
        label = data.validated_data["label"]
        tau = data.validated_data.get("default_tau")

        with transaction.atomic():
            # 같은 사용자의 동시 요청이 한도·중복 검사를 같이 통과하지 못하게 줄을 세운다.
            from django.contrib.auth import get_user_model

            get_user_model().objects.select_for_update().filter(pk=request.user.pk).first()
            existing = _same_label(request.user, label)
            if existing is not None:
                return Response(EventTagSerializer(existing).data, status=status.HTTP_200_OK)
            if EventTag.objects.filter(user=request.user).count() >= EventTag.MAX_PER_USER:
                raise TagLimit()
            tag = EventTag.objects.create(
                key=_new_key(),
                label=label,
                user=request.user,
                default_tau=tau,
                penalty_shape=EventTag.PenaltyShape.LINEAR,
                order=0,
            )
        return Response(EventTagSerializer(tag).data, status=status.HTTP_201_CREATED)


class EventTagDetailView(_WriteThrottleMixin, APIView):
    """PATCH·DELETE /api/events/tags/{key} — 내 종류 고치기·지우기."""

    throttle_classes = [ScopedRateThrottle]

    def _own(self, request, key: str) -> EventTag:
        tag = EventTag.objects.filter(user=request.user, key=key).first()
        if tag is None:
            raise NotFound("없는 종류다.")
        return tag

    def patch(self, request, key: str):
        tag = self._own(request, key)
        data = TagInputSerializer(data=request.data, partial=True)
        data.is_valid(raise_exception=True)
        values = data.validated_data

        tau_changed = False
        with transaction.atomic():
            if "label" in values and values["label"] != tag.label:
                if _same_label(request.user, values["label"], exclude_pk=tag.pk) is not None:
                    raise TagDuplicate()
                tag.label = values["label"]
            if "default_tau" in values and values["default_tau"] != tag.default_tau:
                tag.default_tau = values["default_tau"]
                tau_changed = True
            tag.save()
        _refresh_tagged(request.user, tau_changed and _has_future_events(tag))
        return Response(EventTagSerializer(tag).data)

    def delete(self, request, key: str):
        tag = self._own(request, key)
        had_events = _has_future_events(tag)
        # 일정·반복 규칙의 `tag` 는 SET_NULL 이다. 종류 없음이 되어 프로필 기본 τ 를 쓴다.
        tag.delete()
        _refresh_tagged(request.user, had_events)
        return Response(status=status.HTTP_204_NO_CONTENT)
