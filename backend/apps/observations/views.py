"""이동 관측 API.

**모든 조회는 `request.user` 로 좁힌다.** events 앱과 같은 규칙이다. 공유
베이스가 없어 믹스인을 앱마다 다시 정의한다.

멱등성이 이 엔드포인트의 핵심이다. 앱은 오프라인이면 로컬 큐에 쌓아 두고
나중에 다시 보낸다. 같은 `client_uuid` 가 두 번 와도 행이 늘지 않아야 한다.
"""

from __future__ import annotations

from django.db import transaction
from django.utils.dateparse import parse_datetime
from rest_framework import status
from rest_framework.generics import ListAPIView
from rest_framework.response import Response
from rest_framework.throttling import ScopedRateThrottle
from rest_framework.views import APIView

from .models import TripObservation
from .serializers import (
    TripObservationBatchSerializer,
    TripObservationSerializer,
)


class _UserScopedMixin:
    """사용자 소유 관측만 다룬다."""

    def get_queryset(self):
        return (
            TripObservation.objects.filter(user=self.request.user)
            .select_related("event", "event__alarm_plan", "event__place")
        )


class ObservationBatchView(APIView):
    """POST /api/observations/batch — 관측 여러 건 업로드.

    같은 `client_uuid` 가 이미 있으면 **조용히 무시한다**(덮어쓰지 않는다).
    앱이 재전송하는 것은 "저장됐는지 모르겠다" 는 뜻이고, 이미 저장된 값을
    덮어쓸 이유는 없다. 첫 판정이 가장 신뢰할 만하다.

    응답은 저장 결과를 건수로 알려 준다. 앱은 `accepted + duplicated` 만큼을
    로컬 큐에서 지운다 — 중복도 "서버에 있다" 는 확인이므로 지워야 한다.
    """

    throttle_classes = [ScopedRateThrottle]
    throttle_scope = "observation"

    def post(self, request):
        batch = TripObservationBatchSerializer(
            data=request.data, context={"request": request}
        )
        batch.is_valid(raise_exception=True)
        items = batch.validated_data["observations"]

        created: list[TripObservation] = []
        duplicated = 0

        with transaction.atomic():
            for item in items:
                obs, was_created = TripObservation.objects.get_or_create(
                    user=request.user,
                    client_uuid=item["client_uuid"],
                    defaults={
                        "event": item["event"],
                        "kind": item["kind"],
                        "detector": item.get(
                            "detector", TripObservation.Detector.GPS
                        ),
                        "observed_at": item["observed_at"],
                        "lat": item["lat"],
                        "lng": item["lng"],
                        "accuracy_m": item["accuracy_m"],
                        "distance_m": item["distance_m"],
                    },
                )
                if was_created:
                    created.append(obs)
                else:
                    duplicated += 1

        return Response(
            {
                "accepted": len(created),
                "duplicated": duplicated,
                "results": TripObservationSerializer(created, many=True).data,
            },
            status=status.HTTP_201_CREATED if created else status.HTTP_200_OK,
        )


class ObservationListView(_UserScopedMixin, ListAPIView):
    """GET /api/observations — 목록.

    `?event=` 로 일정별, `?kind=depart|arrive` 로 종류별,
    `?from=&to=` ISO8601 로 기간을 좁힌다. 리포트 화면(Figma ⑧⑨)과
    학습 파이프라인이 같은 엔드포인트를 쓴다.
    """

    serializer_class = TripObservationSerializer

    def get_queryset(self):
        qs = super().get_queryset()

        if raw_event := self.request.query_params.get("event"):
            if raw_event.isdigit():
                qs = qs.filter(event_id=int(raw_event))

        if kind := self.request.query_params.get("kind"):
            if kind in TripObservation.Kind.values:
                qs = qs.filter(kind=kind)

        if raw_from := self.request.query_params.get("from"):
            if dt := parse_datetime(raw_from):
                qs = qs.filter(observed_at__gte=dt)

        if raw_to := self.request.query_params.get("to"):
            if dt := parse_datetime(raw_to):
                qs = qs.filter(observed_at__lte=dt)

        return qs
