"""iter4 A2 리뷰 수정(A2-1 ~ A2-9): 반복 일정 범위 수정·삭제의 경쟁·묶음·회차별 표시.

계약은 docs/iter4/server-recurrence-scope.md. 동시 요청은 미리 읽어 둔 옛 행(stale)으로 흉내 낸다.
기준 시각은 2026-10-19(월) 08:00 KST, 반복은 매일 09:00 이다. (Partly AI-generated (Claude))
"""

from datetime import date, datetime, timedelta
from unittest.mock import Mock

import pytest
from django.utils import timezone

from apps.events import recurrence
from apps.events.models import Event, EventRecurrence
from apps.events.tests.test_recurrence_scope import (  # noqa: F401 - 픽스처
    DAILY, KST, by_day, client, clock, create, local, patch, rows,
)
from apps.planning.models import AlarmPlan

pytestmark = [pytest.mark.django_db, pytest.mark.integration]


def _split_at_wednesday(client, days, title="알고리즘"):
    response = patch(client, days["2026-10-21"]["id"], {"title": title, "scope": "following"})
    assert response.status_code == 200, response.data
    return response.data["recurrence_id"]


def _rows_on(client, day):
    return [r for r in rows(client) if local(r).date().isoformat() == day]


def _stale(event_id):
    return Event.objects.select_related("place", "tag", "alarm_plan", "user__profile", "recurrence").get(
        pk=event_id)


# --- A2-1 -------------------------------------------------------------------


def test_a2_1_this_delete_racing_a_split_excludes_on_the_new_rule(client):
    create(client)
    days = by_day(client)
    stale = _stale(days["2026-10-23"]["id"])
    old_rule = stale.recurrence_id
    new_rule = _split_at_wednesday(client, days)
    # 금요일 "이 일정만" 삭제가 나누기 전에 읽은 행으로 들어온다.
    recurrence.delete_occurrence(stale)
    assert "2026-10-23" not in by_day(client)
    assert EventRecurrence.objects.get(pk=new_rule).excluded_dates == ["2026-10-23"]
    assert "2026-10-23" not in EventRecurrence.objects.get(pk=old_rule).excluded_dates


# --- A2-2 -------------------------------------------------------------------


def test_a2_2_this_patch_racing_a_split_keeps_the_new_rule(client, monkeypatch):
    from apps.events.views import EventDetailView

    create(client)
    days = by_day(client)
    stale = _stale(days["2026-10-23"]["id"])
    new_rule = _split_at_wednesday(client, days)
    with monkeypatch.context() as scoped:
        scoped.setattr(EventDetailView, "get_object", lambda self: stale)
        response = patch(client, stale.pk, {"title": "금요 보강"})
    assert response.status_code == 200, response.data
    friday = _rows_on(client, "2026-10-23")
    assert len(friday) == 1
    assert friday[0]["id"] == stale.pk and friday[0]["title"] == "금요 보강"
    assert friday[0]["recurrence_id"] == new_rule


def test_a2_2_save_writes_only_changed_fields(client):
    from apps.events.serializers import EventWriteSerializer

    create(client)
    event_id = by_day(client)["2026-10-22"]["id"]
    stale = Event.objects.get(pk=event_id)
    Event.objects.filter(pk=event_id).update(skipped_at=timezone.now())
    write = EventWriteSerializer(stale, data={"title": "새 제목"}, partial=True,
                                 context={"request": Mock(user=client.owner)})
    assert write.is_valid(), write.errors
    write.save()
    fresh = Event.objects.get(pk=event_id)
    assert fresh.title == "새 제목" and fresh.skipped_at is not None


def test_a2_2_list_drops_an_occurrence_left_on_an_ended_rule(client, clock):
    create(client)
    days = by_day(client)
    old_rule = days["2026-10-19"]["recurrence_id"]
    _split_at_wednesday(client, days)
    # 옛 저장이 금요일 회차를 끝난 규칙으로 되돌렸다(고치기 전의 경쟁 결과).
    Event.objects.filter(pk=days["2026-10-23"]["id"]).update(recurrence_id=old_rule)
    friday = _rows_on(client, "2026-10-23")
    assert len(friday) == 1 and friday[0]["recurrence_id"] != old_rule

    # 끝난 규칙이 오늘 전에 끝났어도(목록이 펼치기를 건너뛰는 규칙) 같다.
    clock[0] += timedelta(days=1)
    tuesday = by_day(client)["2026-10-20"]
    assert patch(client, tuesday["id"], {"title": "화", "scope": "following"}).status_code == 200
    ended = tuesday["recurrence_id"]
    assert EventRecurrence.objects.get(pk=ended).until == date(2026, 10, 19)
    Event.objects.filter(pk=by_day(client)["2026-10-22"]["id"]).update(recurrence_id=ended)
    assert [r["title"] for r in _rows_on(client, "2026-10-22")] == ["화"]


def test_a2_2_occurrence_moved_past_the_end_by_this_edit_is_kept(client):
    create(client)
    days = by_day(client)
    old_rule = days["2026-10-20"]["recurrence_id"]
    _split_at_wednesday(client, days)
    # 끝난 규칙의 화요일 회차를 "이 일정만" 으로 토요일로 옮긴다. 사용자가 옮긴 것이라 남는다.
    assert patch(client, days["2026-10-20"]["id"], {"start_at": "2026-10-24T15:00:00+09:00"}).status_code == 200
    saturday = _rows_on(client, "2026-10-24")
    assert sorted(r["recurrence_id"] == old_rule for r in saturday) == [False, True]


# --- A2-3 -------------------------------------------------------------------


def test_a2_3_moved_occurrence_takes_the_marks_of_its_new_day(client):
    create(client)
    days = by_day(client)
    wednesday, friday = days["2026-10-21"]["id"], days["2026-10-23"]["id"]
    assert client.post(f"/api/events/{wednesday}/skip").status_code == 200
    Event.objects.filter(pk=wednesday).update(prep_learning_excluded_at=timezone.now())
    assert patch(client, friday, {"alarm_enabled": False}).status_code == 200

    response = patch(client, wednesday, {"start_at": "2026-10-23T10:00:00+09:00", "scope": "following"})
    assert response.status_code == 200, response.data
    moved = Event.objects.get(pk=wednesday)
    assert moved.recurrence_date == date(2026, 10, 23)
    assert moved.skipped_at is None and moved.prep_learning_excluded_at is None
    assert moved.alarm_enabled is False  # 금요일의 끔이 금요일에 남는다
    assert not Event.objects.filter(pk=friday).exists()


def test_a2_3_weekday_move_to_an_empty_day_clears_marks_but_keeps_sent_switch(client):
    created = create(client, repeat_weekdays=[0, 2])
    assert client.post(f"/api/events/{created['id']}/skip").status_code == 200
    response = patch(client, created["id"],
                     {"repeat_weekdays": [1, 3], "alarm_enabled": False, "scope": "following"})
    assert response.status_code == 200
    assert local(response.data).date() == date(2026, 10, 20)
    assert response.data["skipped"] is False
    assert response.data["alarm_enabled"] is False


# --- A2-4 -------------------------------------------------------------------


def test_a2_4_following_starts_from_the_values_the_user_saw(client, clock):
    create(client)
    days = by_day(client)
    wednesday = days["2026-10-21"]["id"]
    assert patch(client, wednesday, {"title": "보강", "start_at": "2026-10-21T11:00:00+09:00",
                                     "tau_override": 0.9}).status_code == 200
    assert patch(client, days["2026-10-23"]["id"], {"title": "따로"}).status_code == 200

    response = patch(client, wednesday, {"route_key": "car", "scope": "following"})
    assert response.status_code == 200, response.data
    assert response.data["title"] == "보강" and local(response.data).hour == 11
    rule = EventRecurrence.objects.get(pk=response.data["recurrence_id"])
    assert rule.template["title"] == "보강" and rule.template["tau_override"] == 0.9
    assert rule.template["route_key"] == "car"
    after = by_day(client)
    for day in ("2026-10-22", "2026-10-23", "2026-10-25"):
        # 따로 고친 금요일도 새 값으로 덮는다(팀 결정).
        assert after[day]["title"] == "보강" and local(after[day]).hour == 11
    assert after["2026-10-20"]["title"] == "반복 수업"
    clock[0] += timedelta(days=7)
    assert {(r["title"], local(r).hour) for r in rows(client)} == {("보강", 11)}


# --- A2-5 -------------------------------------------------------------------


def test_a2_5_delete_following_reaches_parts_split_off_earlier(client, clock):
    create(client)
    days = by_day(client)
    first_rule = days["2026-10-19"]["recurrence_id"]
    friday = patch(client, days["2026-10-23"]["id"], {"title": "금부터", "scope": "following"})
    assert friday.status_code == 200
    part = EventRecurrence.objects.get(pk=friday.data["recurrence_id"])
    assert part.split_from_id == first_rule

    assert client.delete(f"/api/events/{days['2026-10-20']['id']}?scope=following").status_code == 204
    assert sorted(by_day(client)) == ["2026-10-19"]
    assert not EventRecurrence.objects.filter(pk=part.pk).exists()
    assert EventRecurrence.objects.get(pk=first_rule).until == date(2026, 10, 19)
    clock[0] += timedelta(days=7)
    assert rows(client) == []


def test_a2_5_edit_following_takes_over_parts_split_off_earlier(client, clock):
    create(client)
    days = by_day(client)
    friday = patch(client, days["2026-10-23"]["id"], {"title": "금부터", "scope": "following"})
    assert friday.status_code == 200
    middle = friday.data["recurrence_id"]

    response = patch(client, days["2026-10-20"]["id"], {"title": "화부터", "scope": "following"})
    assert response.status_code == 200
    after = by_day(client)
    assert after["2026-10-19"]["title"] == "반복 수업"
    assert all(after[d]["title"] == "화부터" for d in after if d >= "2026-10-20")
    assert {after[d]["id"] for d in after} == {days[d]["id"] for d in days}
    assert not EventRecurrence.objects.filter(pk=middle).exists()
    newest = EventRecurrence.objects.get(pk=response.data["recurrence_id"])
    assert newest.until is None
    clock[0] += timedelta(days=7)
    later = rows(client)
    assert len(later) == 7 and {r["title"] for r in later} == {"화부터"}


def test_a2_5_edit_following_keeps_the_family_end(client, clock):
    create(client)
    days = by_day(client)
    assert client.delete(f"/api/events/{days['2026-10-24']['id']}?scope=following").status_code == 204
    response = patch(client, days["2026-10-21"]["id"], {"title": "알고리즘", "scope": "following"})
    assert response.status_code == 200
    assert EventRecurrence.objects.get(pk=response.data["recurrence_id"]).until == date(2026, 10, 23)
    assert sorted(by_day(client))[-1] == "2026-10-23"


def test_a2_5_family_ignores_a_dangling_parent(client):
    created = create(client)
    rule = EventRecurrence.objects.get(pk=created["recurrence_id"])
    EventRecurrence.objects.filter(pk=rule.pk).update(split_from_id=987654)
    child = EventRecurrence.objects.create(
        user=client.owner, start_at=rule.start_at, timezone="Asia/Seoul", weekdays=DAILY, split_from=rule,
    )
    assert recurrence._family_ids(client.owner.pk, child.pk) == {rule.pk, child.pk}


def test_a2_5_lock_series_retries_when_the_family_grows(client, monkeypatch):
    created = create(client)
    rule = EventRecurrence.objects.get(pk=created["recurrence_id"])
    real = recurrence._family_ids
    grown = []

    def family(user_id, rule_id):
        # 첫 계산과 잠근 뒤 계산 사이에 다른 요청이 규칙을 하나 나눴다.
        if not grown:
            ids = real(user_id, rule_id)
            grown.append(EventRecurrence.objects.create(
                user=client.owner, start_at=rule.start_at, timezone="Asia/Seoul", weekdays=DAILY,
                split_from=rule,
            ))
            return ids
        return real(user_id, rule_id)

    monkeypatch.setattr(recurrence, "_family_ids", family)
    locked, _, parts = recurrence.lock_series(Event.objects.get(pk=created["id"]))
    assert locked.pk == rule.pk and [p.pk for p in parts] == [rule.pk, grown[0].pk]


# --- A2-6 -------------------------------------------------------------------


def test_a2_6_occurrence_moved_before_the_split_day_stays_behind(client):
    create(client)
    days = by_day(client)
    friday = days["2026-10-23"]["id"]
    assert patch(client, friday, {"start_at": "2026-10-20T15:00:00+09:00"}).status_code == 200
    _split_at_wednesday(client, days)
    moved = Event.objects.get(pk=friday)
    assert moved.title == "반복 수업" and moved.start_at.astimezone(KST).hour == 15
    assert moved.recurrence_id == days["2026-10-19"]["recurrence_id"]
    # 금요일 자리는 화요일로 옮긴 회차가 쓰고 있다. 새 반복이 금요일을 다시 만들지 않는다.
    assert _rows_on(client, "2026-10-23") == []
    assert len(_rows_on(client, "2026-10-20")) == 2


def test_a2_6_occurrence_moved_after_the_split_day_follows_the_new_series(client):
    create(client)
    days = by_day(client)
    old_rule = days["2026-10-19"]["recurrence_id"]
    assert patch(client, days["2026-10-20"]["id"], {"start_at": "2026-10-23T15:00:00+09:00"}).status_code == 200
    _split_at_wednesday(client, days)
    friday = _rows_on(client, "2026-10-23")
    assert [(r["title"], local(r).hour) for r in friday] == [("알고리즘", 9)]
    assert _rows_on(client, "2026-10-20") == []  # 화요일 자리가 되살아나지 않는다
    assert "2026-10-20" in EventRecurrence.objects.get(pk=old_rule).excluded_dates


def test_a2_6_delete_following_uses_the_day_the_occurrence_is_shown(client):
    create(client)
    days = by_day(client)
    friday, tuesday = days["2026-10-23"]["id"], days["2026-10-20"]["id"]
    assert patch(client, friday, {"start_at": "2026-10-19T15:00:00+09:00"}).status_code == 200
    assert patch(client, tuesday, {"start_at": "2026-10-24T15:00:00+09:00"}).status_code == 200
    assert client.delete(f"/api/events/{days['2026-10-21']['id']}?scope=following").status_code == 204
    remaining = {r["id"] for r in rows(client)}
    assert friday in remaining and tuesday not in remaining
    assert sorted(by_day(client)) == ["2026-10-19"]


# --- A2-7 -------------------------------------------------------------------


def _pin_user_alarm(event_id):
    plan = AlarmPlan.objects.select_related("event").get(event_id=event_id)
    at = plan.event.start_at - timedelta(hours=2)
    AlarmPlan.objects.filter(pk=plan.pk).update(
        user_alarm_at=at, user_alarm_reason="picked", user_alarm_start_at=plan.event.start_at,
        confirmed_alarm_at=at, confirmed_at=timezone.now(),
    )


@pytest.mark.parametrize("body", [{"title": "알고리즘"}, {"tau_override": 0.8}])
def test_a2_7_following_clears_user_alarm_on_overwritten_occurrences(client, body):
    create(client)
    days = by_day(client)
    for day in ("2026-10-20", "2026-10-22", "2026-10-23"):
        _pin_user_alarm(days[day]["id"])
    response = patch(client, days["2026-10-21"]["id"], body | {"scope": "following"})
    assert response.status_code == 200, response.data
    for day in ("2026-10-22", "2026-10-23"):
        plan = AlarmPlan.objects.get(event_id=days[day]["id"])
        assert plan.user_alarm_at is None and plan.confirmed_alarm_at is None
    past = AlarmPlan.objects.get(event_id=days["2026-10-20"]["id"])
    assert past.user_alarm_at is not None  # 나누는 날 전 회차는 그대로


# --- A2-8 -------------------------------------------------------------------


def test_a2_8_ended_rules_are_not_expanded_on_list(client, clock, monkeypatch):
    create(client)
    days = by_day(client)
    old_rule = days["2026-10-19"]["recurrence_id"]
    _split_at_wednesday(client, days)
    clock[0] += timedelta(days=3)  # 오늘 = 목요일. 옛 규칙은 화요일에 끝났다.
    calls = []
    real = recurrence.expand

    def spy(rule_id, **kwargs):
        calls.append(rule_id)
        return real(rule_id, **kwargs)

    monkeypatch.setattr(recurrence, "expand", spy)
    assert len(rows(client)) == 7
    assert calls and old_rule not in calls


def test_a2_8_ended_rule_still_plans_a_moved_occurrence_entering_the_window(client, clock):
    create(client)
    days = by_day(client)
    old_rule = days["2026-10-19"]["recurrence_id"]
    _split_at_wednesday(client, days)
    # 끝난 규칙의 월요일 회차를 창 밖(11-05)으로 옮겼다. 창에 들어오면 계획이 생겨야 한다.
    monday = days["2026-10-19"]["id"]
    assert patch(client, monday, {"start_at": "2026-11-05T09:00:00+09:00"}).status_code == 200
    assert not AlarmPlan.objects.filter(event_id=monday).exists()
    clock[0] = datetime(2026, 11, 2, 8, tzinfo=KST)
    shown = {r["id"]: r for r in rows(client)}
    assert shown[monday]["alarm_plan"]["status"] == "ok"
    assert Event.objects.get(pk=monday).recurrence_id == old_rule


# --- A2-9 -------------------------------------------------------------------


def test_a2_9_delete_conflict_is_400_not_500(client, monkeypatch):
    created = create(client)

    def busy(event):
        raise recurrence.ScopeError("scope", "다른 수정과 겹쳤어요. 잠시 뒤 다시 시도해 주세요.")

    monkeypatch.setattr(recurrence, "lock_occurrence", busy)
    monkeypatch.setattr(recurrence, "lock_series", busy, raising=False)
    for suffix in ("", "?scope=following"):
        response = client.delete(f"/api/events/{created['id']}{suffix}")
        assert response.status_code == 400
        assert "scope" in response.data["error"]["details"]
    assert Event.objects.filter(pk=created["id"]).exists()
