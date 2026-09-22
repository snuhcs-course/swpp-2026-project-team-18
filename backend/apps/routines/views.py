"""루틴 블록 API.

**모든 조회는 `request.user` 로 좁힌다.** 목록만 필터하고 상세를 빼먹으면
남의 블록을 id 로 읽을 수 있다. `get_queryset()` 한 곳에서 걸러 상속받게 한다.

## 재계산을 언제 하는가

블록을 고치면 준비 시간이 바뀌므로 알람도 바뀐다. 그런데 매번 전부 다시
계산하면 카카오 경로 API 무료 쿼터(일 1,000건)를 블록 수정 횟수만큼 먹는다.

    블록 생성·수정·삭제        재계산하지 않는다. 다음 조회·명시적 재계산 때 반영
    일정의 블록 체크 변경      **그 일정만** 재계산한다

체크 변경은 "이 아침에 뭘 할지" 를 정하는 행위라 사용자가 즉시 결과를 기대한다.
블록 정의 수정은 설정 변경이라 다음 계산에 반영되면 된다. 블록 정의를 고친 뒤
바로 반영하고 싶으면 `POST /api/events/{id}/recompute` 를 쓴다.
"""

from __future__ import annotations

from django.db import transaction
from django.db.models import Avg, Count
from rest_framework import status
from rest_framework.generics import ListCreateAPIView, RetrieveUpdateDestroyAPIView
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from apps.events.models import Event
from apps.events.serializers import EventSerializer
from apps.planning import services as planning_services

from .models import BlockObservation, EventBlockSelection, RoutineBlock
from .serializers import (
    BlockObservationBatchSerializer,
    BlockSelectionBulkSerializer,
    RoutineBlockSerializer,
)


class _BlockScopedMixin:
    """요청자 소유 블록만 다룬다."""

    serializer_class = RoutineBlockSerializer

    def get_queryset(self):
        # 관측 통계를 함께 붙인다. 시리얼라이저가 블록마다 쿼리를 날리면
        # 블록 6개에 6번 조회가 된다.
        return (
            RoutineBlock.objects.filter(user=self.request.user)
            .select_related("precondition")
            .annotate(
                obs_count=Count("observations", distinct=True),
                obs_mean=Avg("observations__duration_minutes"),
            )
            .order_by("order", "id")
        )


class RoutineBlockListCreateView(_BlockScopedMixin, ListCreateAPIView):
    """GET  /api/routines/blocks — 내 블록 목록
    POST /api/routines/blocks — 블록 추가
    """

    # 페이지네이션을 끈다. 블록은 보통 5~10개이고 화면이 전체를 한 번에
    # 그린다. 페이지로 잘리면 준비 시간 합계를 클라이언트가 만들 수 없다.
    pagination_class = None

    def perform_create(self, serializer):
        # **user 를 본문에서 받지 않는다.** 받으면 다른 사용자 앞으로 블록을
        # 만들 수 있다.
        serializer.save(user=self.request.user)


class RoutineBlockDetailView(_BlockScopedMixin, RetrieveUpdateDestroyAPIView):
    """GET/PATCH/DELETE /api/routines/blocks/{id}"""

    def perform_destroy(self, instance):
        """삭제 전에 이 블록을 선행으로 삼는 블록들을 풀어 준다.

        모델의 `on_delete=SET_NULL` 이 처리하지만, 그러면 의존 블록이 조용히
        "선행 없음" 이 된다. 명시적으로 해 두면 나중에 로그를 남기거나 사용자에게
        알릴 자리가 생긴다.
        """
        with transaction.atomic():
            RoutineBlock.objects.filter(
                user=instance.user, precondition=instance
            ).update(precondition=None)
            instance.delete()


class EventBlockSelectionView(APIView):
    """GET  /api/events/{event_id}/blocks — 이 일정의 블록 체크 상태
    PUT  /api/events/{event_id}/blocks — 체크를 바꾸고 알람을 재계산

    `PUT` 은 보낸 블록만 갱신한다. 전체 교체가 아니다 — 화면이 일부만 토글한
    뒤 저장하는 경우가 흔하고, 전체 교체로 만들면 화면이 모든 블록을 들고
    있어야 한다.
    """

    # 재계산이 카카오 경로 API 를 부르므로 스로틀을 붙인다. 체크를 빠르게
    # 여러 번 토글하면 쿼터가 마른다.
    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "route"

    def _get_event(self, request, event_id: int) -> Event:
        from rest_framework.generics import get_object_or_404

        # **요청자 소유 일정만.** 남의 일정 id 로 블록 구성을 읽거나 바꿀 수
        # 없어야 한다.
        return get_object_or_404(
            Event.objects.select_related("user__profile", "place", "tag"),
            pk=event_id,
            user=request.user,
        )

    def get(self, request, event_id: int):
        event = self._get_event(request, event_id)
        blocks = (
            RoutineBlock.objects.filter(user=request.user)
            .annotate(
                obs_count=Count("observations", distinct=True),
                obs_mean=Avg("observations__duration_minutes"),
            )
            .order_by("order", "id")
        )
        overrides = {
            sel.block_id: sel.checked
            for sel in EventBlockSelection.objects.filter(event=event)
        }

        rows = []
        for b in blocks:
            data = RoutineBlockSerializer(b, context={"request": request}).data
            # 이 일정에서 실제로 포함되는지. 행이 없으면 기본값을 따른다.
            data["checked"] = overrides.get(b.pk, b.included_by_default)
            # 사용자가 이 일정에서 명시적으로 바꿨는지. 화면이 "기본값" 과
            # "직접 바꿈" 을 구분해 표시할 수 있다.
            data["explicit"] = b.pk in overrides
            rows.append(data)

        return Response({"event": event.pk, "blocks": rows})

    def put(self, request, event_id: int):
        event = self._get_event(request, event_id)
        serializer = BlockSelectionBulkSerializer(
            data=request.data, context={"request": request}
        )
        serializer.is_valid(raise_exception=True)

        with transaction.atomic():
            for row in serializer.validated_data["selections"]:
                EventBlockSelection.objects.update_or_create(
                    event=event,
                    block=row["block"],
                    defaults={"checked": row["checked"]},
                )
            # 체크가 바뀌면 준비 시간이 바뀐다. 사용자가 즉시 결과를 기대하는
            # 행위라 여기서 재계산한다.
            planning_services.compute_and_store(event)

        event.refresh_from_db()
        return Response(
            EventSerializer(event, context={"request": request}).data,
            status=status.HTTP_200_OK,
        )


class BlockObservationBatchView(APIView):
    """POST /api/routines/observations/batch — 블록 관측 배치 업로드

    멱등하다. 같은 `client_uuid` 를 다시 보내면 무시한다. 오프라인 큐가
    재전송하므로 필수다 — 행이 늘면 같은 아침이 두 번 학습된다.
    """

    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "observation"

    def post(self, request):
        serializer = BlockObservationBatchSerializer(
            data=request.data, context={"request": request}
        )
        serializer.is_valid(raise_exception=True)
        rows = serializer.validated_data["observations"]

        existing = set(
            BlockObservation.objects.filter(
                user=request.user,
                client_uuid__in=[r["client_uuid"] for r in rows],
            ).values_list("client_uuid", flat=True)
        )

        created = []
        with transaction.atomic():
            for row in rows:
                if row["client_uuid"] in existing:
                    continue
                obs = BlockObservation.objects.create(user=request.user, **row)
                created.append(obs.pk)

        return Response(
            {
                "accepted": len(created),
                "duplicated": len(rows) - len(created),
                "results": created,
            },
            status=status.HTTP_201_CREATED if created else status.HTTP_200_OK,
        )
