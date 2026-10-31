"""Read-only risk previews from a stored raw route and the current planning model."""

from datetime import timedelta
import math

from . import buffers, estimators, history, services
from .models import AlarmPlan

OPTIONS = (("safe", "안전", .97), ("normal", "보통", .90), ("gamble", "도박", .65))


def build_risk_options(event) -> dict:
    tau = event.effective_tau
    profile = event.user.profile
    auto_adjusted = (
        event.tau_override is None and event.tag_id is not None
        and event.tag.default_tau != profile.default_tau
    )
    result = {
        "options": [],
        "selected_key": next((key for key, _, value in OPTIONS if value == tau), None),
        "auto_adjusted": auto_adjusted,
        "auto_reason": (
            f"{event.tag.label} 일정 기본값 τ {tau:g} 적용 (프로필 기본값 τ {profile.default_tau:g})"
            if auto_adjusted else None
        ),
        "distribution": None,
        "unavailable_reason": None,
    }
    plan = getattr(event, "alarm_plan", None)
    if plan is None or plan.status != AlarmPlan.Status.OK:
        result["unavailable_reason"] = "알람 계획을 계산한 뒤 선택지를 볼 수 있습니다."
        return result

    # Never fall back to older raw values when the latest revision no longer matches the plan.
    revision = history.reusable_revision(event, plan)
    if revision is None:
        result["unavailable_reason"] = "저장된 경로 원값이 없습니다. 알람을 다시 계산해 주세요."
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
        result["unavailable_reason"] = "소요시간의 변동 폭을 아직 몰라 위험별 선택지를 계산할 수 없습니다."
        return result

    for key, label, option_tau in OPTIONS:
        calculation = estimators.compute_alarm_math(
            prep, travel, buffer.minutes, option_tau,
        )
        result["options"].append({
            "key": key, "label": label, "tau": option_tau,
            "alarm_at": (event.start_at - timedelta(minutes=calculation.total_minutes)).isoformat(),
            "probability": calculation.on_time_probability,
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
