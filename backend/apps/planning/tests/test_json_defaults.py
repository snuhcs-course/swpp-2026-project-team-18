"""JSON 컬럼 기본값이 배열인지.

## 실제로 있었던 장애

`db_default=Value("[]", output_field=JSONField())` 는 받은 값을 `json.dumps` 로
인코딩한다. 그래서 파이썬 **문자열** `"[]"` 를 주면 컬럼 DEFAULT 가 JSON 배열이
아니라 JSON 문자열이 된다.

    "route_path" text DEFAULT '"[]"' NOT NULL      ← 틀림
    "route_path" text DEFAULT '[]'   NOT NULL      ← 맞음

`AddField` 가 기존 행을 이 기본값으로 채우므로, 마이그레이션 이후 모든 기존 행이
배열 대신 문자열을 들고 있었다. API 는 그것을 그대로 내려보냈고 앱의 Gson 이

    Expected BEGIN_ARRAY but was STRING at $.results[0].alarm_plan.route_path

으로 터졌다. **필드 하나가 응답 전체의 파싱을 깨뜨려** 일정 목록이 통째로 날아가고
화면은 "오프라인 · 51분 전 정보" 로 떨어졌다. 서버는 200 을 주고 있었으므로
서버 로그에는 아무 흔적이 없었다.

pytest 가 이것을 못 잡은 이유는 테스트가 행을 **모델을 통해** 만들기 때문이다.
`default=list` 는 파이썬 계층이라 항상 올바른 배열을 넣는다. 컬럼 기본값은
그 경로를 지나지 않는다.
"""

from __future__ import annotations

import json

import pytest
from django.db import connection

from apps.planning.models import AlarmPlan

JSON_LIST_FIELDS = ("prep_breakdown", "route_path")


class TestDbDefaultsAreArrays:
    """선언을 직접 확인한다. 모델을 통과하는 경로로는 드러나지 않는다."""

    @pytest.mark.parametrize("name", JSON_LIST_FIELDS)
    def test_db_default_wraps_a_list_not_a_string(self, name):
        field = AlarmPlan._meta.get_field(name)
        value = field.db_default.value
        assert isinstance(value, list), (
            f"{name} 의 db_default 가 {value!r} 다. 문자열을 주면 json.dumps 가 "
            f'한 번 더 감싸 컬럼 기본값이 JSON 문자열 \'"[]"\' 이 된다.'
        )
        assert value == []

    @pytest.mark.django_db
    @pytest.mark.parametrize("name", JSON_LIST_FIELDS)
    def test_column_default_is_a_json_array(self, name):
        """DDL 에 박힌 기본값을 읽는다. 선언이 맞아도 SQL 이 틀릴 수 있다."""
        with connection.cursor() as cursor:
            table = AlarmPlan._meta.db_table
            defaults = {
                column.name: column.default
                for column in connection.introspection.get_table_description(cursor, table)
            }
        raw = defaults.get(name)
        if raw is None:
            pytest.skip(f"{connection.vendor} 가 컬럼 기본값을 노출하지 않는다")

        literal = str(raw).strip().strip("'")
        parsed = json.loads(literal)
        assert isinstance(parsed, list), (
            f"{name} 의 컬럼 기본값이 {literal!r} 다. 읽으면 배열이 아니라 "
            f"{type(parsed).__name__} 가 나온다."
        )


@pytest.mark.django_db
class TestSerializerAlwaysSendsArrays:
    """DB 에 문자열이 남아 있어도 응답은 배열이어야 한다.

    기본값은 고쳤지만 이 방어를 둔다. 한 필드가 응답 전체를 깨뜨릴 수 있는 구조를
    남기지 않는다 — 그 구조 때문에 앱이 통째로 멈췄다.
    """

    @pytest.mark.parametrize("stored", ['"[]"', '"[[37.5,127.0]]"', '"쓰레기"', '"{}"'])
    @pytest.mark.parametrize("name", JSON_LIST_FIELDS)
    def test_stringified_value_is_sent_as_an_array(self, django_user_model, name, stored):
        from apps.events.serializers import _as_list

        # 저장된 JSON 문자열을 읽은 모습 그대로 넣는다.
        assert isinstance(_as_list(json.loads(stored)), list)

    def test_none_and_scalars_become_empty(self):
        from apps.events.serializers import _as_list

        for value in (None, 3, 1.5, True, {"a": 1}):
            assert _as_list(value) == []

    def test_real_lists_pass_through_untouched(self):
        from apps.events.serializers import _as_list

        path = [[37.5, 127.0], [37.6, 127.1]]
        assert _as_list(path) is path
