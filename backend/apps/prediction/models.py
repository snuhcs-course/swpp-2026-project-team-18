"""학습 결과 아티팩트. back-spec.md 4.7 / front-spec.md 11.

## 왜 아티팩트로 내리는가

앱이 알람을 계산할 수 있어야 한다. 블록 체크를 바꿀 때마다 서버를 부르면
체감 100ms 를 맞출 수 없고(왕복만 200ms 넘는다), 비행기 모드에서는 아예
안 된다.

그래서 서버가 **모수만** 담은 JSON 을 내리고 앱이 같은 수식으로 계산한다.
TFLite 모델 파일과 인터프리터가 필요 없고, 전 과정이 단위 테스트 가능하다
(front-spec 11 의 논의 결론).

## 스키마

front-spec.md 11 의 스키마를 그대로 쓴다.

```json
{
  "version": "2026-09-15T03:00:00Z",
  "global": {"prep_mean": 38.2, "prep_sd": 9.1},
  "blocks": {"shower": {"mean": 14.3, "sd": 2.1, "n": 42}},
  "tag_adjust": {"presentation": 6.5},
  "slack_coef": 0.18,
  "route_correction": {"transit:2호선": {"factor": 1.07, "sd": 4.2, "n": 31}},
  "weather_adjust": {"rain_prep": 3.0, "rain_travel_factor": 1.12}
}
```

`blocks` 의 키는 **블록 이름**이다(사용자별 블록이라 id 는 기기 간에 의미가
없다). 개인 아티팩트에서는 사용자 자신의 블록 이름과 맞는다.

## 전역과 개인

`user` 가 null 이면 전역 아티팩트다. 관측이 적은 사용자는 전역값으로
시작해서, 관측이 쌓이면 개인 아티팩트가 생긴다. 섞는 비율은
`apps.planning.estimators` 의 shrinkage 가 정한다.

**개인 아티팩트는 그 사용자만 받을 수 있다.** 뷰에서 `user=request.user`
또는 `user__isnull=True` 로만 조회한다 — 남의 아티팩트에는 그 사람의 블록
이름과 평균 준비 시간이 들어 있다.
"""

from __future__ import annotations

from django.conf import settings
from django.db import models
from django.db.models import Q


class ModelArtifact(models.Model):
    """학습 결과 스냅샷. 버전으로 구분한다."""

    # ISO 8601 시각 문자열. 클라이언트가 `If-None-Match` 처럼 비교해
    # 같으면 내려받지 않는다.
    version = models.CharField("버전", max_length=40)

    # null 이면 전역 아티팩트.
    user = models.ForeignKey(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        null=True,
        blank=True,
        related_name="model_artifacts",
        verbose_name="사용자",
    )

    payload = models.JSONField("모수", default=dict)
    sample_count = models.PositiveIntegerField("학습 표본 수", default=0)

    # 활성 아티팩트는 사용자(또는 전역)별로 하나다. 이력을 남기려고 옛 행을
    # 지우지 않고 플래그만 내린다 — "언제 왜 알람이 바뀌었나" 를 추적해야 한다.
    is_active = models.BooleanField("활성", default=True)
    created_at = models.DateTimeField("생성 시각", auto_now_add=True)

    class Meta:
        db_table = "prediction_model_artifact"
        verbose_name = "모델 아티팩트"
        verbose_name_plural = "모델 아티팩트"
        ordering = ["-created_at", "-id"]
        indexes = [
            models.Index(fields=["user", "is_active"], name="pred_user_active_idx"),
        ]
        constraints = [
            models.UniqueConstraint(
                fields=["user", "version"], name="pred_artifact_user_version_unique"
            ),
            # 활성 아티팩트는 사용자별로 하나. 전역(user null)도 하나다.
            # 둘 이상이면 어느 것을 쓸지 호출부가 임의로 고르게 되어
            # 알람이 재계산마다 달라진다.
            models.UniqueConstraint(
                fields=["user"],
                condition=Q(is_active=True, user__isnull=False),
                name="pred_artifact_one_active_per_user",
            ),
            models.UniqueConstraint(
                condition=Q(is_active=True, user__isnull=True),
                fields=["is_active"],
                name="pred_artifact_one_active_global",
            ),
        ]

    def __str__(self):
        scope = "전역" if self.user_id is None else f"user {self.user_id}"
        return f"{scope} {self.version} (n={self.sample_count})"
