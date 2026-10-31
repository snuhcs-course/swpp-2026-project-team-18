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

## 시간대는 따로 곱하는 계수다 (P2 A-1, 10-30)

예전에는 (경로, 시) 칸마다 보정을 따로 만들었다. 24시 × 주중·주말 48칸이라 표본이 모이지
않았고, 학습은 실제 출발 시각의 시로, 알람 계산은 일정 시작 시각의 시로 칸을 찾아 서로
어긋났다(8시 20분에 나가 9시 수업이면 칸이 다르다).

이제 시간대는 **전역·이동 종류별 상대 계수**다(`mode` 칸에 walk·transit 같은 종류,
`travel_kind`). 출근 06~09시, 낮 09~17시, 퇴근 17~20시,
밤 20~06시 네 구간(`time_bucket`)으로 묶고 `hour_bucket` 에 구간 시작 시를 넣는다.
전역 행(`route_signature=""`)의 `hour_bucket >= 0` 은 그 구간의 평균 비율 ÷ 그 수단 전체
평균 비율이다(절대 비율이 아니다). 쓸 때는 1.0 에서 시작해 표본 수만큼 옮겨 간다
(`time_factor`, w = n / (n + 5)). 학습과 계산 모두 **계획한 출발 시각**으로 구간을 고른다.

경로 행은 시간대를 나누지 않는다(`hour_bucket=-1`). 경로 행은 시간대 계수로 나눈 비율에서
배운다. 그래야 아침에만 타는 경로의 지연이 경로 계수와 시간대 계수에 두 번 들어가지 않는다.

## 사용자별이 아니다

보정은 **경로의 성질**이다. 2호선이 아침에 얼마나 지연되는지는 누가 타도
같다. 사용자별로 나누면 각자 같은 경로를 수십 번 타야 학습이 시작된다.
개인차 중 걷는 속도는 사용자별 계수로 따로 둔다(`UserTravelProfile`).
"""

from __future__ import annotations

from django.conf import settings
from django.db import models
from django.db.models import Q

# 보정 계수의 허용 범위. 카카오 예측이 실제의 4배 이상 틀리면 그건 보정이
# 아니라 데이터 오류다(GPS 오판, 중간에 딴 데 들름). 학습을 오염시키므로 막는다.
MIN_FACTOR = 0.25
MAX_FACTOR = 4.0

# 전역 보정을 나타내는 시그니처. 빈 문자열을 쓰는 이유는 null 이면
# UniqueConstraint 의 NULL 취급이 DB 마다 달라서다.
GLOBAL_SIGNATURE = ""

# 새 계수(시간대·걷는 속도)가 1.0 에서 기록 쪽으로 옮겨 가는 속도. 표본이 이만큼이면 반쯤
# 옮겨 간다. 경로 보정을 전역과 섞는 k(`estimators.ROUTE_SHRINKAGE_K`)와 같다.
NEW_FACTOR_SHRINKAGE_K = 5

# 시간대 구간의 시작 시. 출근 06~09, 낮 09~17, 퇴근 17~20, 밤 20~06.
RUSH_MORNING, DAYTIME, RUSH_EVENING, NIGHT = 6, 9, 17, 20
TIME_BUCKETS = (RUSH_MORNING, DAYTIME, RUSH_EVENING, NIGHT)
TIME_BUCKET_LABELS = {RUSH_MORNING: "출근(6~9시)", DAYTIME: "낮(9~17시)", RUSH_EVENING: "퇴근(17~20시)", NIGHT: "밤(20~6시)"}

# 걷는 속도 계수의 범위. 기록 평균이 이 밖이어도 여기서 자른다. 걸음이 카카오 예측의
# 절반이거나 두 배인 사람은 거의 없고, 그런 값은 대개 중간에 들른 이동이다.
MIN_WALK_FACTOR = 0.7
MAX_WALK_FACTOR = 1.5


def time_bucket(hour: int) -> int:
    """시(0~23)를 시간대 구간의 시작 시로. 06~08 → 6, 09~16 → 9, 17~19 → 17, 나머지 → 20."""
    if RUSH_MORNING <= hour < DAYTIME:
        return RUSH_MORNING
    if DAYTIME <= hour < RUSH_EVENING:
        return DAYTIME
    if RUSH_EVENING <= hour < NIGHT:
        return RUSH_EVENING
    return NIGHT


# 이동 종류. 걷는 속도와 시간대 계수는 이것으로 가른다(경로 보정은 예전처럼 수단 이름).
WALK, BICYCLE, CAR, TRANSIT = "walk", "bicycle", "car", "transit"
TRAVEL_KINDS = (WALK, BICYCLE, CAR, TRANSIT)
_KIND_BY_LABEL = {"도보": WALK, "자전거": BICYCLE, "자동차": CAR}


def travel_kind(route_key: str, mode: str = "") -> str:
    """경로 key(없으면 수단 이름)로 이동 종류를 정한다. walk / bicycle / car / transit, 모르면 "".

    **`travel_mode` 는 화면용 이름이다**("도보", "자동차", "버스+지하철"). 경로 후보의 `mode`
    를 그대로 저장하기 때문이다. 종류는 key 가 정확하다("walk", "car", "transit:…").
    대중교통 이름은 "버스"·"지하철"·"버스+지하철" 로 갈리므로 이름으로 나누면 시간대 표본이
    흩어진다.
    """
    key = (route_key or "").strip()
    if key in (WALK, BICYCLE, CAR):
        return key
    if key.startswith("transit:"):
        return TRANSIT
    label = (mode or "").strip()
    if label in TRAVEL_KINDS:
        return label
    if label in _KIND_BY_LABEL:
        return _KIND_BY_LABEL[label]
    return TRANSIT if label else ""


def shrink_to_one(recorded: float, n: int) -> float:
    """1.0 에서 시작해 표본 `n` 개만큼 기록 쪽으로 옮긴 값. w = n / (n + 5)."""
    if n <= 0:
        return 1.0
    w = n / (n + NEW_FACTOR_SHRINKAGE_K)
    return 1.0 + w * (recorded - 1.0)


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

    # 계획한 출발 시각의 시간대 구간 시작 시(6·9·17·20, `time_bucket`). -1 이면 구분 없음.
    # 전역 행만 쓴다. 그 행의 factor 는 절대 비율이 아니라 시간대 상대 계수다(머리말).
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

    @property
    def is_time_of_day(self) -> bool:
        """시간대 상대 계수 행인가. 절대 비율이 아니다(머리말)."""
        return self.is_global and self.hour_bucket >= 0

    @property
    def time_factor(self) -> float:
        """시간대 행을 쓸 때의 계수. 1.0 에서 표본 수만큼 기록 쪽으로 옮긴다."""
        return shrink_to_one(self.factor, self.sample_count)


class UserTravelProfile(models.Model):
    """사용자별 걷는 속도(P2 A-2, 10-30).

    도보로 간 일정의 실제 ÷ 카카오 도보 예측이 그 사람의 걷는 속도 비율이다. 섞인 신호가
    없어 가장 깨끗하다. 경로 보정은 모두가 공유하므로 여기 따로 둔다.

    `walk_ratio` 는 기록 평균 그대로다. 쓸 때 1.0 에서 표본 수만큼 옮기고
    0.7~1.5 로 자른다(`walk_factor`). 도보 경로는 전체에, 대중교통 경로는 도보 구간
    (집→정류장, 환승, 정류장→목적지)에만 곱한다(`estimators.estimate_travel`).
    밤 학습(`prediction.learning.update_route_corrections`)이 다시 만든다.
    """

    user = models.OneToOneField(
        settings.AUTH_USER_MODEL,
        on_delete=models.CASCADE,
        related_name="travel_profile",
        verbose_name="사용자",
    )
    walk_ratio = models.FloatField("걷는 속도 기록 평균(실제/예측)", default=1.0)
    walk_samples = models.PositiveIntegerField("도보 표본 수", default=0)
    updated_at = models.DateTimeField("갱신 시각", auto_now=True)

    class Meta:
        db_table = "routing_user_travel_profile"
        verbose_name = "사용자 이동 특성"
        verbose_name_plural = "사용자 이동 특성"
        constraints = [
            models.CheckConstraint(
                condition=Q(walk_ratio__gte=MIN_FACTOR) & Q(walk_ratio__lte=MAX_FACTOR),
                name="routing_travel_profile_walk_ratio_range",
            ),
        ]

    def __str__(self):
        return f"{self.user_id} 걷기 ×{self.walk_factor:.2f} (n={self.walk_samples})"

    @property
    def walk_factor(self) -> float:
        return walk_factor(self.walk_ratio, self.walk_samples)


def walk_factor(ratio: float, n: int) -> float:
    """걷는 속도 계수. 1.0 에서 시작해 표본만큼 기록 쪽으로 옮기고 0.7~1.5 로 자른다."""
    return min(max(shrink_to_one(ratio, n), MIN_WALK_FACTOR), MAX_WALK_FACTOR)


def walk_factor_for(user_id: int) -> float:
    """사용자의 걷는 속도 계수. 기록이 없으면 1.0."""
    profile = UserTravelProfile.objects.filter(user_id=user_id).only(
        "walk_ratio", "walk_samples"
    ).first()
    return profile.walk_factor if profile is not None else 1.0
