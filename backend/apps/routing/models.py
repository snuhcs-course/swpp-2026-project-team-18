"""경로 보정 계수. back-spec.md 4.6.

## 왜 필요한가

카카오 경로 API 는 **점추정치 하나만** 준다. 백분위도 실시간 지연도 없다
(checklist "BE-P0-06 결론"). 그래서 "23분" 이 평균인지 최선인지 알 수 없고,
변동성은 전혀 알 수 없다.

변동성 없이는 확신도를 말할 수 없다. τ=0.9 로 올려도 같은 23분이 나온다.
그래서 변동성을 **우리가 관측해서** 만든다. 예측 23분이었는데 실제 27분이
걸렸다는 기록이 쌓이면 두 가지가 나온다.

    factor    = 평균(실제/예측)      — 카카오가 체계적으로 낙관적인지
    sd_minutes= 표준편차(실제−예측·factor) — 얼마나 들쭉날쭉한지

## 두 단계 집계

경로별 보정이 이상적이지만 특정 경로를 여러 번 다녀야 모인다. 그래서
전역 보정을 함께 둔다.

    route_signature = ""  →  전역. 모든 사용자·모든 경로의 상대 오차
    route_signature = "transit:2호선>5513"  →  이 경로 전용

`apps.planning.estimators` 가 shrinkage 로 둘을 섞는다(관측이 적으면 전역
쪽으로 끌어당긴다). 경로 전용 관측이 3건일 때 그 값만 믿으면 우연히 막혔던
하루가 알람을 20분 앞당긴다.

## 사용자별이 아니다

보정은 **경로의 성질**이다. 2호선이 아침에 얼마나 지연되는지는 누가 타도
같다. 사용자별로 나누면 각자 같은 경로를 수십 번 타야 학습이 시작된다.
개인차(걷는 속도 등)는 준비 시간 쪽 블록 학습이 담당한다.
"""

from __future__ import annotations

from django.db import models
from django.db.models import Q

# 보정 계수의 허용 범위. 카카오 예측이 실제의 4배 이상 틀리면 그건 보정이
# 아니라 데이터 오류다(GPS 오판, 중간에 딴 데 들름). 학습을 오염시키므로 막는다.
MIN_FACTOR = 0.25
MAX_FACTOR = 4.0

# 전역 보정을 나타내는 시그니처. 빈 문자열을 쓰는 이유는 null 이면
# UniqueConstraint 의 NULL 취급이 DB 마다 달라서다.
GLOBAL_SIGNATURE = ""


class RouteCorrection(models.Model):
    """경로·시간대별 예측 대비 실제 비율."""

    class WeekdayType(models.TextChoices):
        WEEKDAY = "weekday", "평일"
        WEEKEND = "weekend", "주말"
        ANY = "any", "구분 없음"

    # 카카오 후보의 key 에서 파생한 경로 식별자. 빈 문자열이면 전역이다.
    route_signature = models.CharField(
        "경로 시그니처", max_length=120, blank=True, default=GLOBAL_SIGNATURE
    )
    # walk / bicycle / car / transit. 빈 문자열이면 수단 구분 없음.
    mode = models.CharField("이동 수단", max_length=20, blank=True, default="")

    # 출발 시각의 시간대. -1 이면 시간대 구분 없음.
    # 아침 8시와 낮 2시의 지연은 다르므로 나눈다.
    hour_bucket = models.SmallIntegerField("시간대(시)", default=-1)
    weekday_type = models.CharField(
        "요일 구분", max_length=8, choices=WeekdayType.choices, default=WeekdayType.ANY
    )

    # 실제/예측의 평균. 1.0 이면 카카오가 정확하다.
    factor = models.FloatField("보정 계수", default=1.0)
    # 보정 후 잔차의 표준편차(분). 이게 확신도의 재료다.
    sd_minutes = models.FloatField("잔차 표준편차(분)", default=0.0)

    sample_count = models.PositiveIntegerField("표본 수", default=0)
    updated_at = models.DateTimeField("갱신 시각", auto_now=True)

    class Meta:
        db_table = "routing_route_correction"
        verbose_name = "경로 보정"
        verbose_name_plural = "경로 보정"
        ordering = ["route_signature", "mode", "hour_bucket"]
        constraints = [
            models.UniqueConstraint(
                fields=["route_signature", "mode", "hour_bucket", "weekday_type"],
                name="routing_correction_unique",
            ),
            models.CheckConstraint(
                condition=Q(factor__gte=MIN_FACTOR) & Q(factor__lte=MAX_FACTOR),
                name="routing_correction_factor_range",
            ),
            models.CheckConstraint(
                condition=Q(sd_minutes__gte=0), name="routing_correction_sd_nonneg"
            ),
            models.CheckConstraint(
                condition=Q(hour_bucket__gte=-1) & Q(hour_bucket__lte=23),
                name="routing_correction_hour_range",
            ),
        ]

    def __str__(self):
        scope = self.route_signature or "(전역)"
        when = "전체" if self.hour_bucket < 0 else f"{self.hour_bucket}시"
        return f"{scope} {when} ×{self.factor:.2f} ±{self.sd_minutes:.1f}분 (n={self.sample_count})"

    @property
    def is_global(self) -> bool:
        return self.route_signature == GLOBAL_SIGNATURE
