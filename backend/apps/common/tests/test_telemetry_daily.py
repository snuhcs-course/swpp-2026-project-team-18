"""S-4 export contracts: KST boundaries, attribution, exclusions and safe output."""

from __future__ import annotations

import csv
from datetime import date, datetime, timedelta, timezone
import io
from itertools import count

from django.core.management import call_command
from django.core.management.base import CommandError
from django.db import connection
import pytest

from apps.common.models import ApiRequestLog
from apps.common.telemetry import FIELDS, KST, LATENCY_LIMITS, api_patterns, daily_rows
from apps.events.models import Event, Place
from apps.observations.models import AlarmLog, TripObservation


pytestmark = pytest.mark.django_db
DAY = date(2026, 12, 7)
MIDNIGHT = datetime(2026, 12, 7, tzinfo=KST)
VERSION = "0.22.0"
_sequence = count()


@pytest.fixture
def make_user(django_user_model):
    def make(*, version=VERSION, when=None, **kwargs):
        return django_user_model.objects.create_user(
            email=kwargs.pop("email", f"beta-{next(_sequence)}@example.test"),
            password="test-password", nickname=kwargs.pop("nickname", "Beta participant"),
            registration_app_version=version,
            date_joined=when or MIDNIGHT - timedelta(days=10), **kwargs,
        )
    return make


@pytest.fixture
def make_log():
    def make(user=None, *, when=MIDNIGHT, version=VERSION, **kwargs):
        row = ApiRequestLog.objects.create(
            user=user, app_version=version, method=kwargs.pop("method", "GET"),
            route=kwargs.pop("route", "api/events"),
            status_code=kwargs.pop("status_code", 200),
            duration_ms=kwargs.pop("duration_ms", 10), **kwargs,
        )
        ApiRequestLog.objects.filter(pk=row.pk).update(created_at=when)
        return row
    return make


def cell(rows, metric, *, day=DAY, version="all", table="daily", **where):
    matches = [row for row in rows if row["date"] == day.isoformat()
               and row["app_version"] == version and row["table"] == table
               and row["metric"] == metric
               and all(row[name] == value for name, value in where.items())]
    assert len(matches) == 1, (metric, where, matches)
    return matches[0]


def command(*args, **options):
    out = io.StringIO()
    call_command("telemetry_daily", "--from", DAY.isoformat(), "--to", DAY.isoformat(),
                 *args, stdout=out, **options)
    return out.getvalue()


def test_request_days_use_kst_midnight_and_inclusive_end(make_user, make_log, settings):
    # The command's KST contract must survive a host/default-timezone difference.
    settings.TIME_ZONE = "UTC"
    user = make_user()
    last_day = DAY + timedelta(days=1)
    upper = MIDNIGHT + timedelta(days=2)
    for when in (MIDNIGHT - timedelta(microseconds=1), MIDNIGHT,
                 upper - timedelta(microseconds=1), upper):
        make_log(user, when=when.astimezone(timezone.utc))

    rows = daily_rows(DAY, last_day)
    assert cell(rows, "requests")["value"] == 1
    assert cell(rows, "requests", day=last_day)["value"] == 1
    assert {row["date"] for row in rows} == {DAY.isoformat(), last_day.isoformat()}


@pytest.mark.parametrize("source,metric", [
    ("user", "signups"), ("event", "events_created"),
    ("alarm", "alarm_fired"), ("trip", "trip_arrive"),
])
def test_model_dates_use_occurrence_not_upload_or_event_start(source, metric, make_user):
    owner = make_user()
    event = Event.objects.create(user=owner, title="Unrelated future event",
                                 start_at=MIDNIGHT + timedelta(days=100))
    Event.objects.filter(pk=event.pk).update(created_at=MIDNIGHT - timedelta(days=5))
    for when in (MIDNIGHT - timedelta(microseconds=1), MIDNIGHT,
                 MIDNIGHT + timedelta(days=1) - timedelta(microseconds=1),
                 MIDNIGHT + timedelta(days=1)):
        if source == "user":
            make_user(when=when)
        elif source == "event":
            row = Event.objects.create(user=owner, title="Test event", start_at=event.start_at,
                                       creation_app_version=VERSION)
            Event.objects.filter(pk=row.pk).update(created_at=when)
        elif source == "alarm":
            row = AlarmLog.objects.create(user=owner, event=event, kind="fired",
                                          occurred_at=when, app_version=VERSION,
                                          client_uuid=f"alarm-{next(_sequence)}")
            AlarmLog.objects.filter(pk=row.pk).update(created_at=MIDNIGHT + timedelta(days=3))
        else:
            row = TripObservation.objects.create(user=owner, event=event, kind="arrive",
                                                  detector="manual", observed_at=when,
                                                  app_version=VERSION,
                                                  client_uuid=f"trip-{next(_sequence)}")
            TripObservation.objects.filter(pk=row.pk).update(created_at=MIDNIGHT + timedelta(days=3))
    rows = daily_rows(DAY, DAY)
    assert cell(rows, metric)["value"] == 2
    assert cell(rows, metric, version=VERSION)["value"] == 2


def test_versions_do_not_borrow_users_newer_request_version(make_user, make_log):
    user = make_user(version="0.21.0 (21)", when=MIDNIGHT)
    event = Event.objects.create(user=user, title="Legacy record", start_at=MIDNIGHT,
                                 creation_app_version="")
    Event.objects.filter(pk=event.pk).update(created_at=MIDNIGHT)
    for version in ("0.21.0 (21)", "0.22.0 (22)", "0.22.0", ""):
        make_log(user, version=version)

    rows = daily_rows(DAY, DAY)
    assert cell(rows, "requests")["value"] == 4
    assert cell(rows, "daily_active_users")["value"] == 1
    assert cell(rows, "requests", version="0.21.0")["value"] == 1
    assert cell(rows, "requests", version=VERSION)["value"] == 2
    assert cell(rows, "requests", version="unknown")["value"] == 1
    assert cell(rows, "signups", version="0.21.0")["value"] == 1
    assert cell(rows, "signups", version=VERSION)["value"] == 0
    assert cell(rows, "events_created", version="unknown")["value"] == 1
    assert cell(rows, "events_created", version=VERSION)["value"] == 0

    filtered = list(csv.DictReader(io.StringIO(command("--app-version", VERSION))))
    assert {row["app_version"] for row in filtered} == {VERSION}
    assert cell(filtered, "requests", version=VERSION)["value"] == "2"
    assert cell(filtered, "events_created", version=VERSION)["value"] == "0"
    unknown = daily_rows(DAY, DAY, app_version="unknown")
    assert cell(unknown, "events_created", version="unknown")["value"] == 1


@pytest.mark.parametrize("email", ["qa-ui-beta@example.test", "QA-UI_beta@example.test",
                                   "depcheck_beta@example.test", "e2e-beta@example.test"])
def test_test_accounts_excluded_from_every_record_even_if_explicitly_selected(
    email, make_user, make_log,
):
    qa = make_user(email=email, when=MIDNIGHT)
    beta = make_user(when=MIDNIGHT)
    for user in (qa, beta):
        event = Event.objects.create(user=user, title="Private event", start_at=MIDNIGHT,
                                     creation_app_version=VERSION)
        Event.objects.filter(pk=event.pk).update(created_at=MIDNIGHT)
        AlarmLog.objects.create(user=user, kind="fired", occurred_at=MIDNIGHT,
                                app_version=VERSION, client_uuid=f"alarm-{user.pk}")
        TripObservation.objects.create(user=user, event=event, kind="depart", detector="manual",
                                       observed_at=MIDNIGHT, app_version=VERSION,
                                       client_uuid=f"trip-{user.pk}")
        make_log(user, status_code=503)
    for selected in (None, [qa.pk, beta.pk]):
        rows = daily_rows(DAY, DAY, user_ids=selected)
        for metric in ("signups", "events_created", "alarm_fired", "trip_depart",
                       "requests", "server_errors", "daily_active_users"):
            assert cell(rows, metric)["value"] == 1, metric
    rows = daily_rows(DAY, DAY, user_ids=[qa.pk])
    assert all(row["value"] == 0 for row in rows
               if row["table"] == "daily" and row["metric"] != "server_error_rate")


def test_all_alarm_and_trip_kinds_are_counted_once_as_persisted_rows(make_user):
    user = make_user()
    event = Event.objects.create(user=user, title="Future", start_at=MIDNIGHT)
    for kind in ("fired", "dismissed", "snoozed", "silenced", "scheduled", "enabled"):
        AlarmLog.objects.create(user=user, event=event, kind=kind, occurred_at=MIDNIGHT,
                                app_version=VERSION, client_uuid=f"kind-{kind}")
    for kind in ("depart", "arrive"):
        TripObservation.objects.create(user=user, event=event, kind=kind, detector="manual",
                                       observed_at=MIDNIGHT, app_version=VERSION,
                                       client_uuid=f"kind-{kind}")
    rows = daily_rows(DAY, DAY)
    for metric in ("alarm_fired", "alarm_dismissed", "alarm_snoozed", "alarm_silenced",
                   "trip_depart", "trip_arrive"):
        assert cell(rows, metric)["value"] == 1
    assert not any(row["metric"] in {"alarm_scheduled", "alarm_enabled"} for row in rows)


def test_response_percentiles_and_budget_boundaries_and_empty_day(make_user, make_log):
    user = make_user()
    for duration in (100, 200, 300, 400):
        make_log(user, method="POST", route="api/events", duration_ms=duration)
    make_log(user, method="POST", route="api/events/<int:pk>/recompute", duration_ms=300)
    make_log(user, route="api/routes/candidates", duration_ms=2000)
    rows = daily_rows(DAY, DAY + timedelta(days=1))
    creation = cell(rows, "duration_ms", table="latency", method="POST", route="api/events")
    assert (creation["samples"], creation["p50_ms"], creation["p95_ms"]) == (4, 250, 385)
    assert creation["p95_limit_ms"] == 300
    assert creation["p95_within_limit"] == 0
    for method, route in (("POST", "api/events/<int:pk>/recompute"),
                          ("GET", "api/routes/candidates")):
        measured = cell(rows, "duration_ms", table="latency", method=method, route=route)
        assert measured["samples"] == 1
        assert measured["p95_ms"] == measured["p95_limit_ms"]
        assert measured["p95_within_limit"] == 1
    empty = cell(rows, "duration_ms", day=DAY + timedelta(days=1), table="latency",
                 method="POST", route="api/events")
    assert empty["samples"] == 0
    assert (empty["p50_ms"], empty["p95_ms"], empty["p95_within_limit"]) == ("", "", "")
    assert cell(rows, "server_error_rate", day=DAY + timedelta(days=1))["value"] == ""


def test_usage_top_twenty_is_deterministic_but_latency_keeps_critical_routes(make_user, make_log):
    user = make_user()
    pairs = sorted(("GET", route) for route in api_patterns()
                   if ("GET", route) not in LATENCY_LIMITS)[:21]
    assert len(pairs) == 21
    for method, route in reversed(pairs):
        for _ in range(2):
            make_log(user, method=method, route=route)
    make_log(user, method="POST", route="api/events", duration_ms=301)
    rows = daily_rows(DAY, DAY)
    usage = [row for row in rows if row["table"] == "usage" and row["app_version"] == "all"]
    assert [(row["method"], row["route"]) for row in usage] == pairs[:20]
    assert all(row["value"] == 2 for row in usage)
    critical = cell(rows, "duration_ms", table="latency", method="POST", route="api/events")
    assert critical["samples"] == 1
    assert critical["p95_within_limit"] == 0


def test_exact_interpolated_budget_is_not_failed_by_floating_point_noise(make_user, make_log):
    user = make_user()
    # Rank 18.05: 280 + (680 - 280) * .05 == 300ms exactly.
    # Binary float arithmetic can instead produce 300.0000000000003.
    for duration in [280] * 19 + [680]:
        make_log(user, method="POST", route="api/events", duration_ms=duration)
    measured = cell(daily_rows(DAY, DAY), "duration_ms", table="latency",
                    method="POST", route="api/events")
    assert measured["samples"] == 20
    assert measured["p95_ms"] == 300
    assert measured["p95_within_limit"] == 1


def test_errors_use_all_retained_requests_and_users_filter_excludes_anonymous(make_user, make_log):
    user, other = make_user(), make_user()
    qa = make_user(email="depcheck_errors@example.test")
    for status in (200, 500, 600):
        make_log(user, status_code=status)
    make_log(other, status_code=503)
    make_log(None, status_code=501)
    make_log(qa, status_code=500)
    rows = daily_rows(DAY, DAY)
    assert cell(rows, "requests")["value"] == 5
    assert cell(rows, "server_errors")["value"] == 3
    assert cell(rows, "anonymous_requests")["value"] == 1
    assert cell(rows, "daily_active_users")["value"] == 2
    assert cell(rows, "server_error_rate")["value"] == .6
    selected = daily_rows(DAY, DAY, user_ids=[user.pk])
    assert cell(selected, "requests")["value"] == 3
    assert cell(selected, "server_errors")["value"] == 1
    assert cell(selected, "anonymous_requests")["value"] == 0
    assert cell(selected, "server_error_rate")["value"] == pytest.approx(1 / 3, abs=1e-6)
    assert cell(selected, "server_error_rate")["samples"] == 3


def test_action_metrics_count_successful_correct_method_requests(make_user, make_log):
    user = make_user()
    actions = [
        ("PUT", "api/events/<int:pk>/alarm/time", "alarm_time_updates"),
        ("POST", "api/events/<int:pk>/alarm/proposal/accept", "alarm_proposal_accepts"),
        ("POST", "api/events/<int:pk>/alarm/proposal/reject", "alarm_proposal_rejects"),
        ("GET", "api/reports/weekly", "weekly_reports_opened"),
    ]
    for method, route, _ in actions:
        for status in (200, 204, 400, 500):
            make_log(user, method=method, route=route, status_code=status)
        make_log(user, method="PATCH", route=route)
    rows = daily_rows(DAY, DAY)
    for _, _, metric in actions:
        assert cell(rows, metric)["value"] == 2
    # A rejected proposal is activity, but does not change the alarm time.
    assert cell(rows, "alarm_time_changes")["value"] == 4
    assert cell(rows, "requests")["value"] == 20


def test_stdout_and_utf8_csv_identical_and_contain_no_personal_data(make_user, make_log, tmp_path):
    user = make_user(email="private-address@example.test", nickname="PrivateNickname")
    place = Place.objects.create(name="PrivatePlace", lat=37.448612, lng=126.952678)
    Event.objects.create(user=user, title="PrivateTitle", start_at=MIDNIGHT, place=place)
    private = {"email": user.email, "nickname": user.nickname, "place": place.name,
               "lat": place.lat, "lng": place.lng}
    make_log(user, route="api/events/tags/PrivateNickname", request_body=private,
             response_body=private, query=private)
    make_log(user, method="PrivateNickname", route="api/person/private-address@example.test",
             version="PrivateNickname", request_body=private)
    export = tmp_path / "daily.csv"
    output = command("--csv", str(export))
    assert export.read_bytes() == output.encode("utf-8")
    parsed = list(csv.DictReader(io.StringIO(output)))
    assert tuple(parsed[0]) == FIELDS
    for text in (user.email, user.nickname, place.name, "PrivateTitle", str(place.lat), str(place.lng)):
        assert text not in output
    assert any(row["route"] == "api/events/tags/<str:key>" for row in parsed)
    assert any(row["route"] == "other" and row["method"] == "OTHER" for row in parsed)
    assert any(row["app_version"] == "unknown" for row in parsed)


@pytest.mark.parametrize("args", [
    ("--from", "2026-12-08", "--to", "2026-12-07"),
    ("--from", "2026-02-30"), ("--from", "20261207"),
    ("--to", "9999-12-31"), ("--users", ""), ("--users", "1,,2"),
    ("--users", "0"), ("--users", "-2"), ("--users", "1,abc"),
    ("--users", "9223372036854775808"), ("--users", "１２"),
    ("--app-version", "PrivateNickname"), ("--app-version", "=1+1"),
])
def test_invalid_options_fail_before_querying_database(args, django_assert_num_queries):
    with django_assert_num_queries(0), pytest.raises(CommandError):
        command(*args)


def test_invalid_csv_path_is_private_and_export_never_writes_database(make_user, make_log, tmp_path):
    user = make_user()
    make_log(user)
    private_path = tmp_path / "private-directory" / "out.csv"

    def reject_writes(execute, sql, params, many, context):
        assert sql.lstrip().split()[0].upper() not in {"INSERT", "UPDATE", "DELETE", "CREATE", "DROP"}
        return execute(sql, params, many, context)

    with connection.execute_wrapper(reject_writes):
        assert command("--users", f" {user.pk},{user.pk} ")
        with pytest.raises(CommandError) as raised:
            command("--csv", str(private_path))
    assert str(private_path) not in str(raised.value)
    assert not private_path.exists()
