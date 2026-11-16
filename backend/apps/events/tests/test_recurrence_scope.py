"""iter4 A2 (BE-A2): 반복 일정 "이 일정만 / 이후 일정 모두" 수정·삭제.

계약은 docs/iter4/server-recurrence-scope.md. (Partly AI-generated (Claude))
"""

from datetime import date, datetime, timedelta
from unittest.mock import Mock
from zoneinfo import ZoneInfo

import pytest
from django.utils import timezone
from rest_framework.test import APIClient

from apps.events import recurrence
from apps.events.models import Event, EventRecurrence
from apps.planning import services

pytestmark = [pytest.mark.django_db]
KST = ZoneInfo("Asia/Seoul")
NY = ZoneInfo("America/New_York")
DAILY = list(range(7))


@pytest.fixture
def clock(monkeypatch):
    value = [datetime(2026, 10, 19, 8, tzinfo=KST)]  # Monday
    monkeypatch.setattr(timezone, "now", lambda: value[0])
    return value


@pytest.fixture
def client(django_user_model, clock, monkeypatch):
    user = django_user_model.objects.create_user(email="scope@example.com", password="test")
    profile = user.profile
    profile.home_lat, profile.home_lng = 37.4842, 126.9297
    profile.timezone = "Asia/Seoul"
    profile.save()
    route = {"minutes": 20, "mode": "walk", "source": "kakao_walk", "key": "walk"}
    monkeypatch.setattr(services.clients, "best_route", Mock(return_value=(route, False)))
    api = APIClient()
    api.force_authenticate(user)
    api.owner = user
    return api


def create(client, **changes):
    body = {"title": "반복 수업", "start_at": "2026-10-19T09:00:00+09:00",
            "repeat_weekdays": DAILY, "place": {"name": "학교", "lat": 37.46, "lng": 126.95}}
    response = client.post("/api/events", body | changes, format="json")
    assert response.status_code == 201, response.data
    return response.data


def rows(client):
    response = client.get("/api/events")
    assert response.status_code == 200
    return response.data["results"]


def local(row, tz=KST):
    return datetime.fromisoformat(row["start_at"]).astimezone(tz)


def by_day(client, tz=KST):
    return {local(r, tz).date().isoformat(): r for r in rows(client)}


def patch(client, event_id, body):
    return client.patch(f"/api/events/{event_id}", body, format="json")


# ---------------------------------------------------------------------------
# 단위: 규칙 끝(until)과 첫 날 찾기
# ---------------------------------------------------------------------------


@pytest.mark.integration
def test_expand_stops_after_until(client):
    created = create(client)
    rule = EventRecurrence.objects.get(pk=created["recurrence_id"])
    rule.until = date(2026, 10, 21)
    rule.save(update_fields=["until"])
    Event.objects.filter(recurrence=rule, recurrence_date__gt=rule.until).delete()
    recurrence.expand(rule.pk)
    days = sorted(e.recurrence_date.isoformat() for e in rule.occurrences.all())
    assert days == ["2026-10-19", "2026-10-20", "2026-10-21"]


def test_first_day_skips_excluded_and_respects_until():
    monday = date(2026, 10, 19)
    assert recurrence._first_day(monday, [0, 2], set(), None) == monday
    assert recurrence._first_day(monday, [2], {"2026-10-21"}, None) == date(2026, 10, 28)
    assert recurrence._first_day(monday, [2], set(), date(2026, 10, 20)) is None


@pytest.mark.integration
def test_lock_occurrence_follows_a_concurrent_split(client):
    created = create(client)
    stale = Event.objects.get(pk=created["id"])
    other = EventRecurrence.objects.create(
        user=client.owner, start_at=stale.start_at, timezone="Asia/Seoul", weekdays=DAILY, template={},
    )
    Event.objects.filter(pk=stale.pk).update(recurrence=other)
    rule, fresh = recurrence.lock_occurrence(stale)
    assert rule.pk == other.pk and fresh.recurrence_id == other.pk


# ---------------------------------------------------------------------------
# PATCH scope=following
# ---------------------------------------------------------------------------


@pytest.mark.integration
def test_following_splits_rule_keeps_ids_and_leaves_past_alone(client, clock):
    create(client)
    before = by_day(client)
    assert len(before) == 7
    old_rule = before["2026-10-19"]["recurrence_id"]
    # 지난 회차를 따로 고친 값은 그대로 남아야 한다.
    assert patch(client, before["2026-10-20"]["id"], {"title": "보강"}).status_code == 200

    target = before["2026-10-21"]["id"]
    response = patch(client, target, {"title": "알고리즘", "scope": "following"})
    assert response.status_code == 200, response.data
    assert response.data["id"] == target and response.data["title"] == "알고리즘"
    new_rule = response.data["recurrence_id"]
    assert new_rule != old_rule

    after = by_day(client)
    assert {d: r["id"] for d, r in after.items()} == {d: r["id"] for d, r in before.items()}
    assert after["2026-10-19"]["title"] == "반복 수업"
    assert after["2026-10-20"]["title"] == "보강"
    assert after["2026-10-19"]["recurrence_id"] == old_rule
    for day in ("2026-10-21", "2026-10-22", "2026-10-25"):
        assert after[day]["title"] == "알고리즘" and after[day]["recurrence_id"] == new_rule
        assert after[day]["alarm_plan"]["status"] == "ok"
    assert EventRecurrence.objects.get(pk=old_rule).until == date(2026, 10, 20)
    assert EventRecurrence.objects.get(pk=new_rule).template["title"] == "알고리즘"

    # 다음 주 회차는 새 규칙에서만 생긴다. 끝난 규칙은 더 펼치지 않는다.
    clock[0] += timedelta(days=7)
    later = rows(client)
    assert len(later) == 7
    assert all(r["recurrence_id"] == new_rule and r["title"] == "알고리즘" for r in later)
    assert not Event.objects.filter(recurrence_id=old_rule, recurrence_date__gt=date(2026, 10, 20)).exists()


@pytest.mark.integration
def test_following_overwrites_edited_future_but_keeps_skip_and_switch(client):
    create(client)
    days = by_day(client)
    patch(client, days["2026-10-23"]["id"], {"title": "따로", "start_at": "2026-10-23T11:00:00+09:00"})
    assert client.post(f"/api/events/{days['2026-10-24']['id']}/skip").status_code == 200
    patch(client, days["2026-10-25"]["id"], {"alarm_enabled": False})

    response = patch(client, days["2026-10-21"]["id"], {"title": "알고리즘", "scope": "following"})
    assert response.status_code == 200
    after = by_day(client)
    edited = after["2026-10-23"]
    assert edited["id"] == days["2026-10-23"]["id"]
    assert edited["title"] == "알고리즘" and local(edited).hour == 9
    assert after["2026-10-24"]["skipped"] is True
    assert after["2026-10-25"]["alarm_enabled"] is False


@pytest.mark.integration
def test_following_time_change_moves_this_and_later_wall_clock(client):
    create(client)
    days = by_day(client)
    old_alarm = days["2026-10-22"]["alarm_plan"]["alarm_at"]
    response = patch(client, days["2026-10-21"]["id"],
                     {"start_at": "2026-10-21T10:30:00+09:00", "scope": "following"})
    assert response.status_code == 200
    after = by_day(client)
    assert local(after["2026-10-20"]).hour == 9
    for day in ("2026-10-21", "2026-10-22", "2026-10-25"):
        assert (local(after[day]).hour, local(after[day]).minute) == (10, 30)
    assert after["2026-10-22"]["alarm_plan"]["alarm_at"] != old_alarm


@pytest.mark.integration
def test_following_weekday_change_on_first_occurrence_edits_rule_in_place(client, clock):
    created = create(client, repeat_weekdays=[0, 2])
    rule_id = created["recurrence_id"]
    days = by_day(client)
    assert sorted(days) == ["2026-10-19", "2026-10-21"]
    wednesday = days["2026-10-21"]["id"]

    response = patch(client, created["id"], {"repeat_weekdays": [3, 1], "scope": "following"})
    assert response.status_code == 200, response.data
    # 월요일 회차가 새 요일의 첫 날(화)로 옮겨 간다. 첫 회차라 규칙을 그 자리에서 고친다.
    assert response.data["id"] == created["id"]
    assert local(response.data).date() == date(2026, 10, 20)
    assert response.data["recurrence_id"] == rule_id
    assert response.data["repeat_weekdays"] == [1, 3]
    after = by_day(client)
    assert sorted(after) == ["2026-10-20", "2026-10-22"]
    assert not Event.objects.filter(pk=wednesday).exists()
    assert EventRecurrence.objects.count() == 1
    rule = EventRecurrence.objects.get()
    assert rule.until is None and rule.weekdays == [1, 3]
    clock[0] += timedelta(days=7)
    assert sorted(by_day(client)) == ["2026-10-27", "2026-10-29"]


@pytest.mark.integration
def test_following_weekday_change_mid_series_ends_old_rule(client, clock):
    create(client, repeat_weekdays=[0, 2])
    days = by_day(client)
    response = patch(client, days["2026-10-21"]["id"], {"repeat_weekdays": [1, 3], "scope": "following"})
    assert response.status_code == 200
    assert local(response.data).date() == date(2026, 10, 22)
    assert response.data["repeat_weekdays"] == [1, 3]
    after = by_day(client)
    assert sorted(after) == ["2026-10-19", "2026-10-22"]
    assert after["2026-10-19"]["repeat_weekdays"] == [0, 2]
    clock[0] += timedelta(days=7)
    assert sorted(by_day(client)) == ["2026-10-27", "2026-10-29"]


@pytest.mark.integration
def test_following_date_move_later_starts_series_there(client):
    create(client)
    days = by_day(client)
    target = days["2026-10-21"]["id"]
    response = patch(client, target, {"start_at": "2026-10-23T10:00:00+09:00", "scope": "following"})
    assert response.status_code == 200
    after = by_day(client)
    assert sorted(after) == ["2026-10-19", "2026-10-20", "2026-10-23", "2026-10-24", "2026-10-25"]
    assert after["2026-10-23"]["id"] == target
    assert not Event.objects.filter(pk__in=[days["2026-10-22"]["id"], days["2026-10-23"]["id"]]).exists()


@pytest.mark.integration
def test_following_carries_excluded_dates_after_split(client):
    create(client)
    days = by_day(client)
    assert client.delete(f"/api/events/{days['2026-10-19']['id']}").status_code == 204
    assert client.delete(f"/api/events/{days['2026-10-23']['id']}").status_code == 204
    response = patch(client, days["2026-10-21"]["id"], {"title": "알고리즘", "scope": "following"})
    assert response.status_code == 200
    assert "2026-10-23" not in by_day(client)
    old = EventRecurrence.objects.get(pk=days["2026-10-20"]["recurrence_id"])
    new = EventRecurrence.objects.get(pk=response.data["recurrence_id"])
    assert old.excluded_dates == ["2026-10-19"] and new.excluded_dates == ["2026-10-23"]


@pytest.mark.integration
def test_following_retry_is_idempotent(client):
    create(client)
    target = by_day(client)["2026-10-21"]["id"]
    body = {"title": "알고리즘", "start_at": "2026-10-21T10:00:00+09:00", "scope": "following"}
    first = patch(client, target, body)
    second = patch(client, target, body)
    assert first.status_code == second.status_code == 200
    assert first.data["recurrence_id"] == second.data["recurrence_id"]
    assert first.data["start_at"] == second.data["start_at"]
    assert EventRecurrence.objects.count() == 2
    assert len(rows(client)) == 7


@pytest.mark.integration
def test_following_alarm_switch_applies_to_this_occurrence_only(client):
    create(client)
    days = by_day(client)
    response = patch(client, days["2026-10-21"]["id"],
                     {"title": "알고리즘", "alarm_enabled": False, "scope": "following"})
    assert response.status_code == 200 and response.data["alarm_enabled"] is False
    after = by_day(client)
    assert after["2026-10-22"]["alarm_enabled"] is None


@pytest.mark.integration
def test_following_uses_rule_timezone_not_profile(client):
    create(client)
    days = by_day(client)
    profile = client.owner.profile
    profile.timezone = "America/New_York"
    profile.save()
    response = patch(client, days["2026-10-21"]["id"],
                     {"start_at": "2026-10-21T07:15:00+09:00", "scope": "following"})
    assert response.status_code == 200
    rule = EventRecurrence.objects.get(pk=response.data["recurrence_id"])
    assert rule.timezone == "Asia/Seoul"
    later = Event.objects.filter(recurrence=rule).order_by("recurrence_date")
    assert [e.start_at.astimezone(KST).strftime("%H:%M") for e in later] == ["07:15"] * 5


@pytest.mark.integration
def test_following_keeps_wall_clock_across_dst(client, clock):
    profile = client.owner.profile
    profile.timezone = "America/New_York"
    profile.save()
    clock[0] = datetime(2026, 10, 29, 6, tzinfo=NY)
    create(client, start_at="2026-10-29T09:00:00-04:00")
    days = by_day(client, NY)
    response = patch(client, days["2026-10-30"]["id"],
                     {"start_at": "2026-10-30T08:00:00-04:00", "scope": "following"})
    assert response.status_code == 200
    after = by_day(client, NY)
    assert local(after["2026-10-29"], NY).hour == 9
    hours = {d: local(r, NY).hour for d, r in after.items() if d >= "2026-10-30"}
    assert set(hours.values()) == {8} and "2026-11-02" in hours  # DST 가 11-01 에 끝난다


# ---------------------------------------------------------------------------
# PATCH 오류와 범위 없는 경우
# ---------------------------------------------------------------------------


@pytest.mark.integration
@pytest.mark.parametrize("scope", ["all", "FOLLOWING", 1, ["following"]])
def test_unknown_scope_is_400(client, scope):
    created = create(client)
    response = patch(client, created["id"], {"title": "x", "scope": scope})
    assert response.status_code == 400
    assert "scope" in response.data["error"]["details"]
    assert Event.objects.get(pk=created["id"]).title == "반복 수업"


@pytest.mark.integration
def test_weekdays_need_following_and_at_least_one_day(client):
    created = create(client)
    for body in ({"repeat_weekdays": [1]}, {"repeat_weekdays": [1], "scope": "this"},
                 {"repeat_weekdays": [], "scope": "following"}):
        response = patch(client, created["id"], body)
        assert response.status_code == 400
        assert "repeat_weekdays" in response.data["error"]["details"]
    assert EventRecurrence.objects.get().weekdays == DAILY


@pytest.mark.integration
def test_this_scope_keeps_one_occurrence_behaviour(client):
    create(client)
    days = by_day(client)
    response = patch(client, days["2026-10-21"]["id"], {"title": "한 번", "scope": "this"})
    assert response.status_code == 200
    after = by_day(client)
    assert after["2026-10-21"]["title"] == "한 번" and after["2026-10-22"]["title"] == "반복 수업"
    assert EventRecurrence.objects.count() == 1 and EventRecurrence.objects.get().until is None


@pytest.mark.integration
def test_following_cannot_move_before_this_occurrence(client):
    create(client)
    days = by_day(client)
    response = patch(client, days["2026-10-21"]["id"],
                     {"title": "알고리즘", "start_at": "2026-10-20T09:00:00+09:00", "scope": "following"})
    assert response.status_code == 400 and "start_at" in response.data["error"]["details"]
    assert EventRecurrence.objects.count() == 1
    assert all(r["title"] == "반복 수업" for r in rows(client))


@pytest.mark.integration
def test_following_on_single_event_is_a_normal_edit(client):
    single = client.post("/api/events", {"title": "한 번", "start_at": "2026-10-20T09:00:00+09:00",
                                         "place": {"name": "학교", "lat": 37.46, "lng": 126.95}},
                         format="json").data
    response = patch(client, single["id"], {"title": "고침", "scope": "following"})
    assert response.status_code == 200 and response.data["title"] == "고침"
    response = patch(client, single["id"], {"repeat_weekdays": [1], "scope": "following"})
    assert response.status_code == 400 and "repeat_weekdays" in response.data["error"]["details"]
    assert EventRecurrence.objects.count() == 0


@pytest.mark.integration
def test_other_users_occurrence_is_404(client, django_user_model):
    created = create(client)
    other = APIClient()
    other.force_authenticate(django_user_model.objects.create_user(email="scope-other@example.com", password="t"))
    assert patch(other, created["id"], {"title": "x", "scope": "following"}).status_code == 404
    assert other.delete(f"/api/events/{created['id']}?scope=following").status_code == 404
    assert Event.objects.filter(recurrence_id=created["recurrence_id"]).count() == 7


# ---------------------------------------------------------------------------
# DELETE ?scope=following
# ---------------------------------------------------------------------------


@pytest.mark.integration
def test_delete_following_ends_series(client, clock):
    create(client)
    days = by_day(client)
    response = client.delete(f"/api/events/{days['2026-10-22']['id']}?scope=following")
    assert response.status_code == 204
    assert sorted(by_day(client)) == ["2026-10-19", "2026-10-20", "2026-10-21"]
    rule = EventRecurrence.objects.get()
    assert rule.until == date(2026, 10, 21)
    clock[0] += timedelta(days=7)
    assert rows(client) == []
    assert client.get(f"/api/events/{days['2026-10-21']['id']}").status_code == 200
    assert client.get(f"/api/events/{days['2026-10-22']['id']}").status_code == 404


@pytest.mark.integration
def test_delete_following_from_first_occurrence_removes_rule(client):
    created = create(client)
    assert client.delete(f"/api/events/{created['id']}?scope=following").status_code == 204
    assert rows(client) == []
    assert EventRecurrence.objects.count() == 0 and Event.objects.count() == 0


@pytest.mark.integration
def test_delete_scope_this_and_single_following(client):
    created = create(client)
    days = by_day(client)
    assert client.delete(f"/api/events/{days['2026-10-21']['id']}?scope=this").status_code == 204
    assert EventRecurrence.objects.get().excluded_dates == ["2026-10-21"]
    assert len(rows(client)) == 6
    single = client.post("/api/events", {"title": "한 번", "start_at": "2026-10-20T09:00:00+09:00"},
                         format="json").data
    assert client.delete(f"/api/events/{single['id']}?scope=following").status_code == 204
    assert not Event.objects.filter(pk=single["id"]).exists()
    assert client.delete(f"/api/events/{created['id']}?scope=everything").status_code == 400
    assert Event.objects.filter(pk=created["id"]).exists()
