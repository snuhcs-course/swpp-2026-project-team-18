from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('observations', '0006_trip_reroute_raw_minutes'),
    ]

    operations = [
        migrations.AddField(
            model_name='alarmlog',
            name='app_version',
            field=models.CharField(blank=True, db_default='', default='', max_length=32, verbose_name='업로드 앱 버전'),
        ),
        migrations.AddField(
            model_name='tripobservation',
            name='app_version',
            field=models.CharField(blank=True, db_default='', default='', max_length=32, verbose_name='업로드 앱 버전'),
        ),
    ]
