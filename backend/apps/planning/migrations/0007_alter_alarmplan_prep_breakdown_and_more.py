"""JSON 기본값이 배열이 아니라 문자열로 들어가던 것을 고친다.

`db_default=Value("[]", output_field=JSONField())` 는 받은 값을 `json.dumps` 로
인코딩한다. 그래서 파이썬 **문자열** `"[]"` 를 주면 컬럼 DEFAULT 가 JSON 배열
`'[]'` 이 아니라 JSON 문자열 `'"[]"'` 이 된다.

    "route_path" text DEFAULT '"[]"' NOT NULL      ← 틀림
    "route_path" text DEFAULT '[]'   NOT NULL      ← 맞음

기본값을 받은 행은 API 응답에서 배열 대신 문자열로 나가고, 앱의 Gson 이

    Expected BEGIN_ARRAY but was STRING at $.results[0].alarm_plan.route_path

으로 터진다. **필드 하나가 일정 목록 전체의 파싱을 깨뜨려** 화면이 "오프라인"
으로 떨어진다. 실기기에서 그 증상으로 발견했다.

`AddField` 가 기존 행을 이 기본값으로 채우므로 **이미 저장된 행도 고쳐야 한다.**
컬럼 기본값만 바꾸면 새 행만 맞고 기존 행은 그대로 깨진 채 남는다.
"""

import json

from django.db import migrations, models

FIELDS = ("prep_breakdown", "route_path")


def repair_stringified_json(apps, schema_editor):
    """문자열로 저장된 값을 배열로 되돌린다.

    타입으로 거르는 문법이 백엔드마다 달라서 전부 훑는다. 이 표는 사용자당
    일정 수만큼이라 작고, 이 마이그레이션은 한 번만 돈다.
    """
    AlarmPlan = apps.get_model("planning", "AlarmPlan")
    fixed = 0
    for plan in AlarmPlan.objects.all().iterator(chunk_size=200):
        dirty = []
        for name in FIELDS:
            value = getattr(plan, name)
            if not isinstance(value, str):
                continue
            try:
                parsed = json.loads(value)
            except (TypeError, ValueError):
                parsed = []
            # 배열이 아닌 것이 들어 있었으면 버린다. 좌표·내역은 목록이어야
            # 하고, 모양이 다른 값을 살려 두면 앱이 같은 자리에서 또 터진다.
            setattr(plan, name, parsed if isinstance(parsed, list) else [])
            dirty.append(name)
        if dirty:
            plan.save(update_fields=dirty)
            fixed += 1
    if fixed:
        print(f"  문자열로 저장된 JSON 을 배열로 고친 행 {fixed}개")


class Migration(migrations.Migration):

    dependencies = [
        ("planning", "0006_alarmplan_route_distance_m_alarmplan_route_path"),
    ]

    operations = [
        migrations.AlterField(
            model_name="alarmplan",
            name="prep_breakdown",
            field=models.JSONField(
                blank=True,
                db_default=models.Value([], output_field=models.JSONField()),
                default=list,
                verbose_name="준비 내역",
            ),
        ),
        migrations.AlterField(
            model_name="alarmplan",
            name="route_path",
            field=models.JSONField(
                blank=True,
                db_default=models.Value([], output_field=models.JSONField()),
                default=list,
                verbose_name="경로 좌표",
            ),
        ),
        # 되돌리지 않는다. 문자열로 다시 만들 이유가 없다.
        migrations.RunPython(repair_stringified_json, migrations.RunPython.noop),
    ]
