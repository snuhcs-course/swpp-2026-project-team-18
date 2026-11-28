"""Run 0007 -> 0008 on a separate in-memory database, never the developer/default DB."""

from copy import deepcopy
from datetime import datetime, timedelta, timezone

from django.db import connections, router
from django.db.migrations.executor import MigrationExecutor

PREVIOUS = ("observations", "0007_alarmlog_app_version_tripobservation_app_version")
TARGET = ("observations", "0008_coordinate_free_trip_observations")


def test_historical_coordinates_are_erased_without_changing_derived_or_selected_place_data(django_db_blocker, monkeypatch):
    # Deliberately start from an empty, separate SQLite :memory: connection. This
    # exercises the actual irreversible migration without rolling the shared test
    # schema backwards or ever using DATABASE_URL / the production database.
    alias = "observation_coordinate_migration_test"
    configuration = deepcopy(connections.databases["default"])
    assert configuration["ENGINE"] == "django.db.backends.sqlite3"
    default_name = str(configuration["NAME"])
    assert default_name == ":memory:" or (
        default_name.startswith("file:") and "mode=memory" in default_name
    )
    configuration["NAME"] = ":memory:"
    configuration["TEST"] = {"NAME": ":memory:"}
    connections.databases[alias] = configuration

    class IsolatedMigrationRouter:
        # Old tag-seed migrations predate multi-DB support and use an unbound
        # manager. Keep those prerequisite seeds on this same temporary DB too.
        def db_for_read(self, model, **hints):
            return alias

        def db_for_write(self, model, **hints):
            return alias

        def allow_migrate(self, db, app_label, **hints):
            return db == alias

    monkeypatch.setattr(router, "routers", [IsolatedMigrationRouter()])
    with django_db_blocker.unblock():
        connection = connections[alias]
        try:
            executor = MigrationExecutor(connection)
            executor.migrate([PREVIOUS])
            old = executor.loader.project_state([PREVIOUS]).apps
            user = old.get_model("accounts", "User").objects.using(alias).create(
                email="migration@example.com", password="!", nickname="마이그레이션",
            )
            profile = old.get_model("accounts", "Profile").objects.using(alias).create(
                user_id=user.pk, home_lat=37.48, home_lng=126.93, home_label="선택한 집",
            )
            place = old.get_model("events", "Place").objects.using(alias).create(
                name="선택한 목적지", lat=37.5, lng=127.0,
            )
            observed = datetime(2026, 11, 27, 8, 0, tzinfo=timezone(timedelta(hours=9)))
            event = old.get_model("events", "Event").objects.using(alias).create(
                user_id=user.pk, place_id=place.pk, title="보존할 일정", start_at=observed + timedelta(hours=1),
                origin_label="선택한 출발지", origin_lat=37.49, origin_lng=126.94,
            )
            observation = old.get_model("observations", "TripObservation")
            for kind, detector, uid, latitude, longitude in [
                ("depart", "gps", "gps-depart", 37.481, 126.931),
                ("arrive", "gps", "gps-arrive", 37.501, 127.001),
                ("arrive", "manual", "manual-fix", 37.502, 127.002),
                ("arrive", "manual", "manual-empty", None, None),
            ]:
                observation.objects.using(alias).create(
                    user_id=user.pk, event_id=event.pk, kind=kind, detector=detector,
                    observed_at=observed, lat=latitude, lng=longitude,
                    accuracy_m=12, distance_m=65 if kind == "depart" else 18,
                    entered_at=observed if kind == "arrive" else None,
                    dwell_seconds=0 if kind == "arrive" else None,
                    client_uuid=uid, app_version="0.17.0 (18)",
                )
            before = {
                row["client_uuid"]: {key: value for key, value in row.items() if key not in {"lat", "lng"}}
                for row in observation.objects.using(alias).values()
            }
            alarm = old.get_model("observations", "AlarmLog").objects.using(alias).create(
                user_id=user.pk, event_id=event.pk, kind="fired", occurred_at=observed,
                client_uuid="keep-alarm", app_version="0.17.0 (18)",
            )
            alarm_before = old.get_model("observations", "AlarmLog").objects.using(alias).values().get(pk=alarm.pk)

            executor = MigrationExecutor(connection)
            executor.migrate([TARGET])
            current = executor.loader.project_state([TARGET]).apps
            observation = current.get_model("observations", "TripObservation")
            rows = list(observation.objects.using(alias).values())
            assert len(rows) == 4
            assert all(row["lat"] is None and row["lng"] is None for row in rows)
            for row in rows:
                assert {key: value for key, value in row.items() if key not in {"lat", "lng"}} == before[row["client_uuid"]]
            saved_profile = current.get_model("accounts", "Profile").objects.using(alias).get(pk=profile.pk)
            saved_place = current.get_model("events", "Place").objects.using(alias).get(pk=place.pk)
            saved_event = current.get_model("events", "Event").objects.using(alias).get(pk=event.pk)
            assert (saved_profile.home_lat, saved_profile.home_lng, saved_profile.home_label) == (37.48, 126.93, "선택한 집")
            assert (saved_place.lat, saved_place.lng) == (37.5, 127.0)
            assert (saved_event.origin_lat, saved_event.origin_lng, saved_event.origin_label) == (37.49, 126.94, "선택한 출발지")
            assert current.get_model("observations", "AlarmLog").objects.using(alias).values().get(pk=alarm.pk) == alarm_before
            # Verify the new physical schema accepts GPS evidence with no coords.
            observation.objects.using(alias).create(
                user_id=user.pk, event_id=event.pk, kind="depart", detector="gps",
                observed_at=observed, accuracy_m=12, distance_m=65, client_uuid="new-client",
            )
            with connection.cursor() as cursor:
                constraints = connection.introspection.get_constraints(cursor, "observations_trip")
            assert "obs_gps_has_location" not in constraints
            assert "obs_gps_has_evidence" in constraints
        finally:
            connection.close()
            del connections[alias]
            connections.databases.pop(alias, None)
