from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('events', '0016_recurrence_split_from'),
    ]

    operations = [
        migrations.AddField(
            model_name='event',
            name='creation_app_version',
            field=models.CharField(blank=True, db_default='', default='', max_length=32, verbose_name='생성 앱 버전'),
        ),
    ]
