"""Read-only risk previews from a stored raw route and the current planning model."""

from datetime import timedelta
import math

from . import buffers, estimators, history, services
from .models import AlarmPlan

OPTIONS = (("safe", "안전", .97), ("normal", "보통", .90), ("gamble", "도박", .65))


def build_risk_options(event) -> dict:
    tau = event.effective_tau
    profile = event.user.profile
    # τ 를 비운 사용자 종류(BE-21)는 프로필 기본값을 그대로 쓰므로 "자동 조정" 이 아니다.
    auto_adjusted = (
        event.tau_override is None and event.tag_id is not None
        and event.tag.default_tau is not None
        and event.tag.default_tau != profile.default_tau
    )
    # Partly AI-generated (Claude) — BE-H3e. 사용자가 τ 를 직접 고르기 전의 값(종류 기본값, 없으면
    # 프로필 기본값). 앱은 이 칸에 "추천" 을 붙인다. 세 칸 중 같은 τ 가 없으면 null 이다.
    default_tau = (
        event.tag.default_tau
        if event.tag_id is not None and event.tag.default_tau is not None
        else profile.default_tau
    )
    result = {
        "options": [],
        "selected_key": next((key for key, _, value in OPTIONS if value == tau), None),
        "recommended_key": next((key for key, _, value in OPTIONS if value == default_tau), None),
        "default_tau": default_tau,
        "auto_adjusted": auto_adjusted,
        "auto_reason": (
            f"{event.tag.label} 일정 기본값 τ {tau:g} 적용 (프로필 기본값 τ {profile.default_tau:g})"
            if auto_adjusted else None
        ),
        "distribution": None,
        "unavailable_reason": None,
    }
    # Partly AI-generated (Claude) — BE-R1 T-4. 앱 시트가 그대로 보이는 문장이다. 시트에는 [다시 시도]
    # 만 있으므로 "다시 계산해 주세요" 처럼 할 수 없는 일을 시키지 않고, 위험 단계를 화면의
    # "여유 시간" 과 헷갈리게 "여유별" 이라 부르지 않는다.
    plan = getattr(event, "alarm_plan", None)
    if plan is None or plan.status != AlarmPlan.Status.OK:
        result["unavailable_reason"] = "알람이 아직 계산되지 않아 고를 수 있는 알람 시각을 보여 드릴 수 없어요."
        return result

    # Never fall back to older raw values when the latest revision no longer matches the plan.
    revision = history.reusable_revision(event, plan)
    if revision is None:
        result["unavailable_reason"] = "저장된 경로 정보가 없어 고를 수 있는 알람 시각을 아직 계산할 수 없어요."
        return result

    # 실제 알람과 같은 버퍼여야 선택지의 알람 시각이 "이걸 고르면 이렇게 울린다" 가 된다.
    buffer = buffers.current(event.user_id)
    prep, travel = services.estimate_components(event, {
        "minutes": revision.route_minutes_raw,
        "walk_minutes": revision.walk_minutes_raw,
        "key": plan.route_key,
        "mode": plan.travel_mode,
    }, buffer_minutes=buffer.minutes)
    total = estimators.total_distribution(prep, travel)
    if total.quantile(.99) <= total.quantile(.5) + 1e-9:
        result["unavailable_reason"] = "걸리는 시간이 얼마나 달라지는지 아직 몰라 고를 수 있는 알람 시각을 계산할 수 없어요."
        return result

    for key, label, option_tau in OPTIONS:
        calculation = estimators.compute_alarm_math(
            prep, travel, buffer.minutes, option_tau,
        )
        probability = calculation.on_time_probability
        if probability is None:
            # Partly AI-generated (Claude) — 준비·이동 중 한쪽만 변동성을 알면 계획 확률은 비지만,
            # 아래 `distribution` 은 보낸다. 앱은 그 분포로 휠 시각의 확률을 읽으므로(AlarmTimeChoice.
            # estimate) 세 칸만 비면 "빠르게 고르기에 확률이 안 뜬다" 가 된다. 같은 분포에서 그 칸의
            # 시각에 일어날 때의 확률(버퍼 포함 시간 안에 마칠 확률)을 낸다.
            probability = max(0, min(100, int(round(total.cdf(calculation.total_minutes) * 100))))
        result["options"].append({
            "key": key, "label": label, "tau": option_tau,
            "alarm_at": (event.start_at - timedelta(minutes=calculation.total_minutes)).isoformat(),
            "probability": probability,
        })

    # CDF mass is a modeled proportion, not a count of observed trips. Include
    # clipped lower-tail mass in the first bin and upper-tail mass in the last.
    upper = max(1, math.ceil(total.quantile(1 - 1e-9)))
    width = max(1, math.ceil(upper / 10))
    edges = list(range(0, upper, width)) + [upper]
    previous = 0.0
    bins = []
    for index, (lo, hi) in enumerate(zip(edges, edges[1:])):
        cumulative = 1.0 if index == len(edges) - 2 else total.cdf(hi)
        bins.append({"min": lo, "max": hi, "mass": round(max(0, cumulative - previous), 6)})
        previous = cumulative
    result["distribution"] = {
        "bins": bins, "p50": round(total.quantile(.5)), "p90": round(total.quantile(.9)),
        "source": "model", "window_days": None,
    }
    return result
