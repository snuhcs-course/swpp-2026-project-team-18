"""Summarize real AT-24 / N1 / N2 / N11 evidence; never generate measurements.

python scripts/analyze_alarm_evidence.py --alarms ../docs/iter5/evidence/alarms.csv \
    --battery ../docs/iter5/evidence/battery.csv --as-of 2026-12-04T23:59:59+09:00
"""
from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from datetime import date, datetime, timedelta, timezone
from pathlib import Path


def timestamp(value: str, field: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{field}: ISO-8601 timestamp required") from error
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ValueError(f"{field}: timezone offset required")
    return parsed


def boolean(value: str, field: str) -> bool:
    if value.lower() not in ("true", "false"):
        raise ValueError(f"{field}: use true or false")
    return value.lower() == "true"


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        return [{key: (value or "").strip() for key, value in row.items()} for row in reader]


def longest_streak(days: set[date]) -> int:
    best = current = 0
    previous = None
    for day in sorted(days):
        current = current + 1 if previous is not None and day - previous == timedelta(days=1) else 1
        best = max(best, current)
        previous = day
    return best


def analyze_alarms(rows: list[dict[str, str]], as_of: datetime, required_days: int = 7) -> dict:
    if required_days < 1:
        raise ValueError("required_days must be positive")
    if as_of.tzinfo is None or as_of.utcoffset() is None:
        raise ValueError("as_of: timezone offset required")
    counts = {key: 0 for key in (
        "due", "fired", "on_time", "late", "early", "missing", "unverified_missing",
        "without_evidence", "pending", "excluded", "unexpected_firing",
    )}
    observations = []
    days_by_device = defaultdict(set)
    seen = set()
    for index, row in enumerate(rows, 2):
        prefix = f"alarm row {index}"
        for field in ("device_id", "app_version", "event_id", "scheduled_at", "expected"):
            if not row.get(field):
                raise ValueError(f"{prefix}: {field} required")
        scheduled = timestamp(row["scheduled_at"], f"{prefix} scheduled_at")
        key = (row["device_id"], row["app_version"], row["event_id"], scheduled)
        if key in seen:
            raise ValueError(f"{prefix}: duplicate alarm occurrence")
        seen.add(key)
        expected = boolean(row["expected"], f"{prefix} expected")
        fired = timestamp(row["fired_at"], f"{prefix} fired_at") if row.get("fired_at") else None
        verified = timestamp(row["verified_at"], f"{prefix} verified_at") if row.get("verified_at") else None
        if fired is not None and fired > as_of or verified is not None and verified > as_of:
            raise ValueError(f"{prefix}: evidence timestamp is after as_of")
        delta = (fired - scheduled).total_seconds() if fired is not None else None
        evidence = bool(row.get("evidence"))
        if not expected:
            if fired is not None:
                status = "unexpected_firing"
            else:
                status = "excluded"
        elif scheduled + timedelta(seconds=5) > as_of and fired is None:
            status = "pending"
        else:
            counts["due"] += 1
            if fired is None:
                status = "missing" if verified is not None and verified >= scheduled + timedelta(seconds=5) and evidence else "unverified_missing"
            else:
                status = "on_time" if abs(delta) <= 5 else "late" if delta > 5 else "early"
                counts["fired"] += 1
            if not evidence:
                counts["without_evidence"] += 1
            if fired is not None and evidence:
                days_by_device[(row["device_id"], row["app_version"])].add(scheduled.date())
        counts[status] += 1
        observations.append({
            "device_id": row["device_id"], "app_version": row["app_version"], "event_id": row["event_id"],
            "scheduled_at": row["scheduled_at"], "status": status, "delta_seconds": delta,
        })
    streaks = [
        {"device_id": device, "app_version": version, "observed_days": len(days), "longest_consecutive_days": longest_streak(days)}
        for (device, version), days in sorted(days_by_device.items())
    ]
    enough_days = any(item["longest_consecutive_days"] >= required_days for item in streaks)
    incomplete = not counts["due"] or counts["unverified_missing"] or counts["without_evidence"]
    n1 = "fail" if counts["missing"] or counts["unexpected_firing"] else "incomplete" if incomplete or not enough_days else "pass_observed_period"
    n2 = "fail" if counts["missing"] or counts["late"] or counts["early"] or counts["unexpected_firing"] else "incomplete" if incomplete else "pass_observed_alarms"
    return {
        "as_of": as_of.isoformat(), "required_consecutive_days": required_days,
        "counts": dict(counts), "n1": n1, "n2": n2, "device_periods": streaks,
        "observations": observations,
        "limitation": "Only supplied, evidenced occurrences are evaluated. A 7-day pass does not establish the 30-day N1 requirement.",
    }


def analyze_battery(rows: list[dict[str, str]]) -> dict:
    groups = defaultdict(lambda: {True: [], False: []})
    rejected = []
    seen = set()
    for index, row in enumerate(rows, 2):
        prefix = f"battery row {index}"
        for field in ("device_id", "app_version", "date", "tracking_enabled", "app_battery_percent", "period_hours", "charged", "measurement_basis"):
            if not row.get(field):
                raise ValueError(f"{prefix}: {field} required")
        day = date.fromisoformat(row["date"])
        key = (row["device_id"], row["app_version"], day)
        if key in seen:
            raise ValueError(f"{prefix}: duplicate device day")
        seen.add(key)
        tracked = boolean(row["tracking_enabled"], f"{prefix} tracking_enabled")
        charged = boolean(row["charged"], f"{prefix} charged")
        try:
            percent = float(row["app_battery_percent"])
            hours = float(row["period_hours"])
        except ValueError as error:
            raise ValueError(f"{prefix}: numeric battery percent and hours required") from error
        if not 0 <= percent <= 100 or not 0 < hours <= 48:
            raise ValueError(f"{prefix}: invalid percent or period")
        reasons = []
        if hours != 24:
            reasons.append("not_a_24_hour_period")
        if charged:
            reasons.append("charging_interrupted")
        if row["measurement_basis"] != "absolute_daily_percent":
            reasons.append("relative_usage_share_is_not_battery_capacity")
        if not row.get("evidence"):
            reasons.append("missing_evidence")
        if reasons:
            rejected.append({"row": index, "reasons": reasons})
            continue
        groups[(row["device_id"], row["app_version"])][tracked].append(percent)
    comparisons = []
    failed = False
    for (device, version), values in sorted(groups.items()):
        tracked, baseline = values[True], values[False]
        failed |= any(percent > 3 for percent in tracked)
        comparisons.append({
            "device_id": device, "app_version": version, "tracked_days": len(tracked), "baseline_days": len(baseline),
            "tracked_mean_percent": sum(tracked) / len(tracked) if tracked else None,
            "baseline_mean_percent": sum(baseline) / len(baseline) if baseline else None,
            "tracked_max_percent": max(tracked) if tracked else None,
            "complete_pair": bool(tracked and baseline),
        })
    complete = bool(comparisons) and all(item["complete_pair"] for item in comparisons) and not rejected
    return {"n11": "fail" if failed else "pass_observed_days" if complete else "incomplete", "comparisons": comparisons, "excluded_rows": rejected}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--alarms", type=Path, required=True)
    parser.add_argument("--battery", type=Path)
    parser.add_argument("--as-of", help="ISO-8601 time with timezone offset; defaults to now UTC")
    parser.add_argument("--required-days", type=int, default=7)
    arguments = parser.parse_args()
    try:
        as_of = timestamp(arguments.as_of, "as_of") if arguments.as_of else datetime.now(timezone.utc)
        result = {"alarms": analyze_alarms(read_csv(arguments.alarms), as_of, arguments.required_days)}
        if arguments.battery:
            result["battery"] = analyze_battery(read_csv(arguments.battery))
    except (ValueError, OSError) as error:
        parser.error(str(error))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
