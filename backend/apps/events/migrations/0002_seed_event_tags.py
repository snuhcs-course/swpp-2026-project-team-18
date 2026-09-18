"""일정 태그 시드.

back-spec.md 4.2 의 `EventTag.key` 목록을 채운다. 사용자가 만드는 값이 아니라
앱이 제공하는 분류이므로 마이그레이션에 둔다.

`default_tau` 는 `penalty_shape` 에서 나온다.
- `step`(1분만 늦어도 실패): 기차·시험·발표 → 높은 τ
- `linear`(늦은 만큼 손해): 수업·알바·약속 → 낮은 τ
"""

from django.db import migrations

TAGS = [
    # key, label, default_tau, penalty_shape, order
    ("class", "수업", 0.90, "linear", 1),
    ("exam", "시험", 0.99, "step", 2),
    ("presentation", "발표", 0.98, "step", 3),
    ("train", "기차·비행", 0.99, "step", 4),
    ("parttime", "알바", 0.95, "linear", 5),
    ("meetup", "약속", 0.85, "linear", 6),
]


def seed(apps, schema_editor):
    EventTag = apps.get_model("events", "EventTag")
    for key, label, tau, shape, order in TAGS:
        EventTag.objects.update_or_create(
            key=key,
            defaults={
                "label": label,
                "default_tau": tau,
                "penalty_shape": shape,
                "order": order,
            },
        )


def unseed(apps, schema_editor):
    EventTag = apps.get_model("events", "EventTag")
    EventTag.objects.filter(key__in=[t[0] for t in TAGS]).delete()


class Migration(migrations.Migration):

    dependencies = [
        ("events", "0001_initial"),
    ]

    operations = [
        migrations.RunPython(seed, unseed),
    ]
