"""S-4 daily telemetry from existing records; no request bodies or user labels.

Dates are occurrence dates in KST, with an inclusive end date. Version metadata
is captured at write time; legacy records stay unknown instead of borrowing a
user's newer app version. Request statistics describe retained ApiRequestLog rows,
not traffic omitted by the request logger.
"""

from __future__ import annotations

from collections import Counter, defaultdict
from datetime import date, datetime, time, timedelta
from fractions import Fraction
from math import floor
import re
from zoneinfo import ZoneInfo

from django.db.models import Q
from django.urls import Resolver404, URLPattern, URLResolver, get_resolver, resolve

from apps.common.cohort import production_users
from apps.common.models import ApiRequestLog
from apps.events.models import Event
from apps.observations.models import AlarmLog, TripObservation

KST = ZoneInfo("Asia/Seoul")
UNKNOWN_VERSION = "unknown"
ALL_VERSIONS = "all"
# A header can contain arbitrary text even after request_log's character filter.
# Only a numeric version and conventional prerelease/build numbers are public.
_VERSION = re.compile(
    r"(?P<version>[0-9]{1,4}(?:\.[0-9]{1,4}){1,3}"
    r"(?:-(?:alpha|beta|rc|dev)(?:\.?[0-9]{1,4})?)?"
    r"(?:\+[0-9]{1,9})?)(?: \([0-9]{1,9}\))?\Z",
    re.ASCII,
)
METHODS = frozenset({"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE"})
FIELDS = (
    "date", "app_version", "table", "metric", "method", "route", "value",
    "samples", "p50_ms", "p95_ms", "p95_limit_ms", "p95_within_limit",
)
DAILY_METRICS = (
    "daily_active_users", "signups", "events_created", "alarm_fired",
    "alarm_dismissed", "alarm_snoozed", "alarm_silenced", "trip_depart",
    "trip_arrive", "alarm_time_updates", "alarm_proposal_accepts",
    "alarm_proposal_rejects", "alarm_time_changes", "weekly_reports_opened",
    "requests", "server_errors", "anonymous_requests",
)
LATENCY_LIMITS = {
    ("POST", "api/events"): 300,
    ("POST", "api/events/<int:pk>/recompute"): 300,
    ("GET", "api/routes/candidates"): 2000,
}
SUCCESS_METRICS = {
    ("PUT", "api/events/<int:pk>/alarm/time"): "alarm_time_updates",
    ("POST", "api/events/<int:pk>/alarm/proposal/accept"): "alarm_proposal_accepts",
    ("POST", "api/events/<int:pk>/alarm/proposal/reject"): "alarm_proposal_rejects",
    ("GET", "api/reports/weekly"): "weekly_reports_opened",
}


def canonical_version(value: str) -> str:
    match = _VERSION.fullmatch(value or "")
    return match["version"] if match else UNKNOWN_VERSION


def api_patterns() -> set[str]:
    """Use authored URL patterns as labels, never URL arguments from a log."""
    found = set()

    def walk(patterns, prefix=""):
        for entry in patterns:
            child = str(entry.pattern)
            route = prefix + (child.removeprefix("^") if prefix else child)
            if isinstance(entry, URLResolver):
                walk(entry.url_patterns, route)
            elif isinstance(entry, URLPattern) and route.removeprefix("^").startswith("api/"):
                found.add(route)

    walk(get_resolver().url_patterns)
    return found


def safe_route(raw: str, patterns: set[str]) -> str:
    if raw in patterns:
        return raw
    # Historic rows may contain the actual URL rather than a resolver pattern.
    # Resolve it, but publish only a pattern from the application allowlist.
    try:
        pattern = resolve("/" + raw.lstrip("/")).route
    except (Resolver404, ValueError):
        return "other"
    return pattern if pattern in patterns else "other"


def percentile(histogram: Counter, quantile: float):
    """Linear interpolation at (n-1)*q, without retaining each request in RAM."""
    count = sum(histogram.values())
    if not count:
        return ""
    # Compute the rank/interpolation exactly before returning a numeric cell.
    # Float ranks can turn an exact 300ms p95 into 300.0000000000003 and fail N8.
    position = (count - 1) * Fraction(str(quantile))
    lower = floor(position)
    upper = min(lower + 1, count - 1)
    lo = hi = None
    seen = 0
    for duration, frequency in sorted(histogram.items()):
        seen += frequency
        if lo is None and seen > lower:
            lo = duration
        if seen > upper:
            hi = duration
            break
    return float(lo + (hi - lo) * (position - lower))


def daily_rows(
    start: date, end: date, *, app_version: str | None = None,
    user_ids: list[int] | None = None,
) -> list[dict]:
    """Return one rectangular table, suitable for identical stdout/CSV exports.

    Anonymous requests are included without --users and counted explicitly.
    Their account cannot be recovered safely from legacy authentication logs.
    With --users, only eligible authenticated/associated user requests remain.
    """
    users = production_users()
    if user_ids is not None:
        users = users.filter(pk__in=user_ids)
    eligible_ids = users.values("pk")
    since = datetime.combine(start, time.min, KST)
    until = datetime.combine(end + timedelta(days=1), time.min, KST)
    metrics = defaultdict(Counter)
    active_users = defaultdict(set)
    durations = defaultdict(Counter)
    versions = set()

    def keys(when, raw_version):
        version = canonical_version(raw_version)
        if app_version is not None and version != app_version:
            return ()
        versions.add(version)
        day = when.astimezone(KST).date()
        if app_version is not None:
            return ((day, version),)
        return ((day, ALL_VERSIONS), (day, version))

    # Select metadata columns only: never query request bodies, labels, or GPS.
    request_users = Q(user__in=eligible_ids)
    if user_ids is None:
        request_users |= Q(user__isnull=True)
    logs = ApiRequestLog.objects.filter(
        request_users, created_at__gte=since, created_at__lt=until,
    ).order_by().values_list(
        "created_at", "app_version", "user_id", "method", "route", "status_code", "duration_ms",
    )
    patterns = api_patterns()
    routes = {}
    for when, version, user_id, method, raw_route, status, duration in logs.iterator(chunk_size=2000):
        if raw_route not in routes:
            routes[raw_route] = safe_route(raw_route, patterns)
        route = routes[raw_route]
        method = method if method in METHODS else "OTHER"
        for key in keys(when, version):
            count = metrics[key]
            count["requests"] += 1
            count["server_errors"] += int(500 <= status < 600)
            count["anonymous_requests"] += int(user_id is None)
            if user_id is not None:
                active_users[key].add(user_id)
            durations[(*key, method, route)][duration] += 1
            metric = SUCCESS_METRICS.get((method, route))
            if metric and 200 <= status < 300:
                count[metric] += 1
                if metric in {"alarm_time_updates", "alarm_proposal_accepts"}:
                    count["alarm_time_changes"] += 1

    def count_records(queryset, time_field, version_field, kind_field=None, kind_metrics=None):
        columns = [time_field, version_field]
        if kind_field:
            columns.append(kind_field)
        records = queryset.filter(**{
            f"{time_field}__gte": since, f"{time_field}__lt": until,
        }).order_by().values_list(*columns)
        for record in records.iterator(chunk_size=2000):
            metric = kind_metrics.get(record[2]) if kind_field else kind_metrics
            if metric:
                for key in keys(record[0], record[1]):
                    metrics[key][metric] += 1

    count_records(users, "date_joined", "registration_app_version", kind_metrics="signups")
    count_records(
        Event.objects.filter(user__in=eligible_ids), "created_at", "creation_app_version",
        kind_metrics="events_created",
    )
    count_records(
        AlarmLog.objects.filter(user__in=eligible_ids), "occurred_at", "app_version", "kind",
        {kind: f"alarm_{kind}" for kind in ("fired", "dismissed", "snoozed", "silenced")},
    )
    count_records(
        TripObservation.objects.filter(user__in=eligible_ids), "observed_at", "app_version", "kind",
        {"depart": "trip_depart", "arrive": "trip_arrive"},
    )

    output = []

    def row(day, version, table, metric, **cells):
        output.append(dict.fromkeys(FIELDS, "") | {
            "date": day.isoformat(), "app_version": version, "table": table,
            "metric": metric, **cells,
        })

    selected_versions = [app_version] if app_version is not None else [ALL_VERSIONS, *sorted(versions)]
    day = start
    while day <= end:
        for version in selected_versions:
            key = (day, version)
            count = metrics[key]
            count["daily_active_users"] = len(active_users[key])
            for metric in DAILY_METRICS:
                row(day, version, "daily", metric, value=count[metric])
            total = count["requests"]
            row(
                day, version, "daily", "server_error_rate",
                value=round(count["server_errors"] / total, 6) if total else "",
                samples=total,
            )
            route_stats = {
                (method, route): histogram
                for (route_day, route_version, method, route), histogram in durations.items()
                if (route_day, route_version) == key
            }
            top = sorted(route_stats, key=lambda pair: (-sum(route_stats[pair].values()), *pair))[:20]
            for method, route in top:
                row(day, version, "usage", "requests", method=method, route=route,
                    value=sum(route_stats[(method, route)].values()))
            for method, route in sorted(route_stats.keys() | LATENCY_LIMITS.keys()):
                histogram = route_stats.get((method, route), Counter())
                p95 = percentile(histogram, .95)
                limit = LATENCY_LIMITS.get((method, route), "")
                row(
                    day, version, "latency", "duration_ms", method=method, route=route,
                    samples=sum(histogram.values()),
                    p50_ms=round(percentile(histogram, .5), 3) if histogram else "",
                    p95_ms=round(p95, 3) if histogram else "", p95_limit_ms=limit,
                    p95_within_limit=int(p95 <= limit) if histogram and limit else "",
                )
        day += timedelta(days=1)
    return output
