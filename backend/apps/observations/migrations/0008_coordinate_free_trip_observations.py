"""Discard measured observation coordinates; user-selected Place/Profile coordinates are untouched.

Deploy this before the coordinate-free Android build. Erased coordinates cannot be
reconstructed, so this data migration intentionally has no reverse operation.
"""

from django.db import migrations, models


def erase_observation_coordinates(apps, schema_editor):
    observation = apps.get_model("observations", "TripObservation")
    observation.objects.using(schema_editor.connection.alias).filter(
        models.Q(lat__isnull=False) | models.Q(lng__isnull=False)
    ).update(lat=None, lng=None)


class Migration(migrations.Migration):
    dependencies = [
        ("observations", "0007_alarmlog_app_version_tripobservation_app_version"),
    ]

    operations = [
        migrations.RemoveConstraint(
            model_name="tripobservation",
            name="obs_gps_has_location",
        ),
        migrations.RunPython(erase_observation_coordinates),
        migrations.AddConstraint(
            model_name="tripobservation",
            constraint=models.CheckConstraint(
                condition=models.Q(detector="manual")
                | (models.Q(accuracy_m__isnull=False) & models.Q(distance_m__isnull=False)),
                name="obs_gps_has_evidence",
            ),
        ),
    ]
