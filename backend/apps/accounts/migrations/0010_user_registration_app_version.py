from django.db import migrations, models


class Migration(migrations.Migration):

    dependencies = [
        ('accounts', '0009_profile_learning_reset_at'),
    ]

    operations = [
        migrations.AddField(
            model_name='user',
            name='registration_app_version',
            field=models.CharField(blank=True, db_default='', default='', max_length=32, verbose_name='가입 앱 버전'),
        ),
    ]
