# Partly AI-generated (Claude)
"""알람 직접 정하기 · 확인 · 제안 답하기 HTTP 계약(iter4 H2-3). docs/iter4/server-alarm-control.md."""

from datetime import datetime, timedelta, timezone

import pytest
from django.utils import timezone as dj_tz
from rest_framework.test import APIClient

from apps.events.models import Event, EventTag, Place
from apps.planning import alarm_control as ac
from apps.planning import services
from apps.planning.models import AlarmPlan
from apps.routing.models import RouteCorrection
from apps.routines.models import RoutineBlock

pytestmark = [pytest.mark.django_db, pytest.mark.integration]
KST = timezone(timedelta(hours=9))


@pytest.fixture
def ctx(django_user_model, monkeypatch):
    user = django_user_model.objects.create_user(email="alarm-api@test.com", password="test12345")
    profile = user.profile
    profile.home_lat, profile.home_lng = 37.48, 126.92
    profile.save()
    place = Place.objects.create(name="학교", lat=37.46, lng=126.95)
    start = (datetime.now(KST) + timedelta(days=2)).replace(hour=9, minute=0, second=0, microsecond=0)
    event = Event.objects.create(user=user, place=place, title="수업", start_at=start)
    RoutineBlock.objects.create(user=user, name="샤워", default_min_minutes=10, default_max_minutes=30)
    RouteCorrection.objects.create(
        route_signature="transit:test", mode="transit", factor=1.2, sd_minutes=6, sample_count=20,
    )
    route = {"minutes": 20, "mode": "transit", "key": "transit:test", "source": "kakao"}
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(route), False))
    monkeypatch.setattr(services.clients, "resolve_route", lambda *a, **kw: (dict(route), False))
    services.compute_and_store(event, trigger="create")
    client = APIClient()
    client.force_authenticate(user)
    return client, event, route


def url(event, tail):
    return f"/api/events/{event.pk}/alarm/{tail}"


def plan_of(response):
    assert response.status_code == 200, response.content
    return response.json()["alarm_plan"]


def iso(value: datetime) -> str:
    return value.astimezone(KST).isoformat()


def recommended(event) -> datetime:
    return AlarmPlan.objects.get(event=event).alarm_at


def test_event_dto_has_new_fields_and_keeps_old_ones(ctx):
    client, event, _ = ctx
    plan = plan_of(client.get(f"/api/events/{event.pk}"))
    assert plan["alarm_at"] == plan["recommended_alarm_at"] == plan["effective_alarm_at"]
    assert plan["alarm_source"] == "recommended"
    assert plan["alarm_confirmed"] is False and plan["alarm_confirmed_at"] is None
    assert plan["user_alarm_at"] is None and plan["user_alarm_reason"] == ""
    assert plan["user_alarm_probability"] is None
    assert plan["effective_on_time_probability"] == plan["on_time_probability"]
    assert plan["alarm_proposal"] is None
    listed = client.get("/api/events").json()["results"][0]["alarm_plan"]
    assert listed["alarm_source"] == "recommended"


def test_list_adds_no_queries_per_event(ctx):
    """새 필드는 이미 읽은 계획에서 만든다. 일정이 늘어도 목록 조회 수가 늘지 않는다."""
    from django.db import connection
    from django.test.utils import CaptureQueriesContext

    client, event, _ = ctx

    def count():
        with CaptureQueriesContext(connection) as queries:
            assert client.get("/api/events").status_code == 200
        return len(queries)

    one = count()
    for days in (3, 4):
        other = Event.objects.create(
            user=event.user, place=event.place, title="추가", start_at=event.start_at + timedelta(days=days),
        )
        services.compute_and_store(other, trigger="create")
    assert count() == one


def test_set_user_time(ctx):
    client, event, _ = ctx
    picked = recommended(event) + timedelta(minutes=15)
    plan = plan_of(client.put(url(event, "time"), {"alarm_at": iso(picked)}, format="json"))
    assert plan["alarm_source"] == "user"
    assert plan["user_alarm_reason"] == "picked"
    assert datetime.fromisoformat(plan["user_alarm_at"]) == picked
    assert datetime.fromisoformat(plan["effective_alarm_at"]) == picked
    # 예전 필드는 그대로 추천 시각이다.
    assert datetime.fromisoformat(plan["alarm_at"]) == recommended(event)
    assert plan["user_alarm_probability"] is not None
    assert plan["user_alarm_probability"] < plan["on_time_probability"]
    assert plan["effective_on_time_probability"] == plan["user_alarm_probability"]
    assert plan["alarm_confirmed"] is True and plan["alarm_confirmed_at"]


def test_user_time_drops_seconds(ctx):
    client, event, _ = ctx
    picked = recommended(event) + timedelta(minutes=5, seconds=42)
    plan = plan_of(client.put(url(event, "time"), {"alarm_at": iso(picked)}, format="json"))
    assert datetime.fromisoformat(plan["user_alarm_at"]) == picked.replace(second=0)


@pytest.mark.parametrize(
    "body,code",
    [
        ({}, "invalid_alarm_time"),
        ({"alarm_at": "내일 아침"}, "invalid_alarm_time"),
        ({"alarm_at": "past"}, "alarm_time_past"),
        ({"alarm_at": "late"}, "alarm_time_too_late"),
        ({"alarm_at": "early"}, "alarm_time_too_early"),
    ],
)
def test_set_user_time_errors(ctx, body, code):
    client, event, _ = ctx
    values = {
        "past": dj_tz.now() - timedelta(minutes=1),
        "late": event.start_at - timedelta(minutes=4),
        "early": event.start_at - timedelta(hours=24, minutes=5),
    }
    if body.get("alarm_at") in values:
        body = {"alarm_at": iso(values[body["alarm_at"]])}
    response = client.put(url(event, "time"), body, format="json")
    assert response.status_code == 400
    error = response.json()["error"]
    assert error["code"] == code
    assert error["message"].endswith("요.")
    if code != "invalid_alarm_time":
        assert set(error["details"]) == {"earliest", "latest"}
    assert AlarmPlan.objects.get(event=event).user_alarm_at is None


def test_user_time_bounds_are_inclusive(ctx):
    client, event, _ = ctx
    for at in (event.start_at - timedelta(minutes=5), event.start_at - timedelta(hours=24)):
        response = client.put(url(event, "time"), {"alarm_at": iso(at)}, format="json")
        assert response.status_code == 200


def test_ownership_and_auth(ctx, django_user_model):
    client, event, _ = ctx
    other = APIClient()
    other.force_authenticate(django_user_model.objects.create_user(email="other-alarm@test.com"))
    body = {"alarm_at": iso(recommended(event))}
    for method, tail in (("put", "time"), ("delete", "time"), ("post", "confirm"),
                         ("post", "proposal/accept"), ("post", "proposal/reject")):
        assert getattr(other, method)(url(event, tail), body, format="json").status_code == 404
        assert getattr(APIClient(), method)(url(event, tail), body, format="json").status_code == 401


def test_unavailable_plan_is_409(ctx):
    client, event, _ = ctx
    AlarmPlan.objects.filter(event=event).update(
        status="route_failed", alarm_at=None, depart_by=None, arrive_at=None,
        prep_minutes=None, travel_minutes=None, buffer_minutes=None,
    )
    at = event.start_at - timedelta(hours=1)
    for method, tail in (("put", "time"), ("delete", "time"), ("post", "confirm"),
                         ("post", "proposal/accept")):
        response = getattr(client, method)(url(event, tail), {"alarm_at": iso(at)}, format="json")
        assert response.status_code == 409
        assert response.json()["error"]["code"] == "alarm_unavailable"
    plan = plan_of(client.get(f"/api/events/{event.pk}"))
    assert plan["effective_alarm_at"] is None and plan["alarm_confirmed"] is False


def test_revert_to_recommended(ctx):
    client, event, _ = ctx
    client.put(url(event, "time"), {"alarm_at": iso(recommended(event) + timedelta(minutes=10))}, format="json")
    plan = plan_of(client.delete(url(event, "time")))
    assert plan["alarm_source"] == "recommended"
    assert plan["user_alarm_at"] is None
    assert plan["effective_alarm_at"] == plan["alarm_at"]
    assert plan["alarm_confirmed"] is True
    # 두 번 불러도 같다.
    assert plan_of(client.delete(url(event, "time")))["alarm_source"] == "recommended"


def test_recompute_keeps_user_time(ctx):
    client, event, route = ctx
    picked = recommended(event) + timedelta(minutes=10)
    first = plan_of(client.put(url(event, "time"), {"alarm_at": iso(picked)}, format="json"))
    route["minutes"] = 30
    plan = plan_of(client.post(f"/api/events/{event.pk}/recompute"))
    assert datetime.fromisoformat(plan["user_alarm_at"]) == picked
    assert datetime.fromisoformat(plan["effective_alarm_at"]) == picked
    assert plan["alarm_at"] != first["alarm_at"]
    assert plan["user_alarm_probability"] < first["user_alarm_probability"]
    assert plan["alarm_confirmed"] is True


def test_user_time_low_probability_proposal_and_accept(ctx):
    client, event, route = ctx
    picked = recommended(event) + timedelta(minutes=10)
    client.put(url(event, "time"), {"alarm_at": iso(picked)}, format="json")
    route["minutes"] = 45
    plan = plan_of(client.post(f"/api/events/{event.pk}/recompute"))
    assert plan["user_alarm_probability"] < 70
    proposal = plan["alarm_proposal"]
    assert proposal["kind"] == "user_low_probability"
    assert proposal["proposed_alarm_at"] == plan["alarm_at"]
    assert proposal["current_alarm_at"] == plan["user_alarm_at"]
    assert proposal["keep_probability"] == plan["user_alarm_probability"]
    # 마감은 더 이른 쪽(제안 시각)의 10분 전이다.
    assert datetime.fromisoformat(proposal["answer_by"]) == (
        datetime.fromisoformat(proposal["proposed_alarm_at"]) - timedelta(minutes=10)
    )
    plan = plan_of(client.post(url(event, "proposal/accept"),
                               {"proposed_alarm_at": proposal["proposed_alarm_at"]}, format="json"))
    assert plan["alarm_source"] == "recommended"
    assert plan["effective_alarm_at"] == plan["alarm_at"]
    assert plan["alarm_proposal"] is None and plan["alarm_confirmed"] is True


def _confirm_and_move(client, event, route, minutes=40):
    plan = plan_of(client.post(url(event, "confirm")))
    assert plan["alarm_confirmed"] is True
    before = plan["alarm_at"]
    route["minutes"] = minutes
    plan = plan_of(client.post(f"/api/events/{event.pk}/recompute"))
    return before, plan


def test_recommended_move_proposal_reject(ctx):
    client, event, route = ctx
    before, plan = _confirm_and_move(client, event, route)
    proposal = plan["alarm_proposal"]
    assert proposal["kind"] == "recommended_moved"
    assert proposal["reason"] == "travel" and proposal["reason_text"].startswith("이동 시간이")
    assert plan["effective_alarm_at"] == before == proposal["current_alarm_at"]
    assert plan["alarm_at"] == proposal["proposed_alarm_at"] != before
    assert plan["effective_on_time_probability"] == proposal["keep_probability"]
    assert plan["alarm_confirmed"] is True
    plan = plan_of(client.post(url(event, "proposal/reject")))
    assert plan["alarm_proposal"] is None
    assert plan["alarm_source"] == "user" and plan["user_alarm_reason"] == "kept"
    assert plan["effective_alarm_at"] == before
    assert plan["alarm_confirmed"] is True
    # 같은 추천으로 다시 계산해도 다시 묻지 않는다.
    plan = plan_of(client.post(f"/api/events/{event.pk}/recompute"))
    assert plan["alarm_proposal"] is None


def test_recommended_move_proposal_accept(ctx):
    client, event, route = ctx
    _before, plan = _confirm_and_move(client, event, route)
    proposed = plan["alarm_proposal"]["proposed_alarm_at"]
    plan = plan_of(client.post(url(event, "proposal/accept"), {"proposed_alarm_at": proposed}, format="json"))
    assert plan["effective_alarm_at"] == proposed == plan["alarm_at"]
    assert plan["alarm_source"] == "recommended" and plan["alarm_confirmed"] is True


def test_proposal_errors(ctx):
    client, event, route = ctx
    response = client.post(url(event, "proposal/accept"))
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "no_alarm_proposal"
    _before, plan = _confirm_and_move(client, event, route)
    stale = iso(datetime.fromisoformat(plan["alarm_proposal"]["proposed_alarm_at"]) + timedelta(minutes=5))
    response = client.post(url(event, "proposal/reject"), {"proposed_alarm_at": stale}, format="json")
    assert response.status_code == 409
    error = response.json()["error"]
    assert error["code"] == "alarm_proposal_changed"
    assert error["details"]["proposed_alarm_at"] == plan["alarm_proposal"]["proposed_alarm_at"]
    response = client.post(url(event, "proposal/reject"), {"proposed_alarm_at": "x"}, format="json")
    assert response.status_code == 400


def test_expired_proposal_is_applied_lazily(ctx):
    client, event, route = ctx
    _before, plan = _confirm_and_move(client, event, route)
    # 지키는 시각을 지금 쪽으로 당겨 마감이 지난 상태를 만든다(서버에는 스케줄러가 없다).
    # 지키는 시각(5분 뒤)이 제안 시각보다 이르므로 답이 없으면 지키는 시각에 울린다.
    held = (dj_tz.now() + timedelta(minutes=5)).replace(second=0, microsecond=0)
    AlarmPlan.objects.filter(event=event).update(held_alarm_at=held, confirmed_alarm_at=held)
    shown = plan_of(client.get(f"/api/events/{event.pk}"))
    # 읽기는 처리한 모습을 보여 준다.
    assert shown["alarm_proposal"] is None
    assert datetime.fromisoformat(shown["effective_alarm_at"]) == held
    assert shown["alarm_source"] == "user" and shown["user_alarm_reason"] == "kept"
    assert shown["alarm_confirmed"] is True
    assert AlarmPlan.objects.get(event=event).proposed_alarm_at is not None  # GET 은 쓰지 않는다
    response = client.post(url(event, "proposal/accept"))
    assert response.status_code == 409
    error = response.json()["error"]
    assert error["code"] == "alarm_proposal_expired"
    assert datetime.fromisoformat(error["details"]["effective_alarm_at"]) == held
    stored = AlarmPlan.objects.get(event=event)
    assert stored.proposed_alarm_at is None and stored.held_alarm_at is None
    assert stored.user_alarm_at == held and stored.rejected_alarm_at is not None


def test_confirm(ctx):
    client, event, _ = ctx
    plan = plan_of(client.get(f"/api/events/{event.pk}"))
    response = client.post(url(event, "confirm"), {"alarm_at": iso(recommended(event) - timedelta(minutes=5))},
                           format="json")
    assert response.status_code == 409
    error = response.json()["error"]
    assert error["code"] == "alarm_changed"
    assert error["details"]["effective_alarm_at"] == plan["effective_alarm_at"]
    plan = plan_of(client.post(url(event, "confirm"), {"alarm_at": plan["effective_alarm_at"]}, format="json"))
    assert plan["alarm_confirmed"] is True
    stored = AlarmPlan.objects.get(event=event)
    assert stored.confirmed_alarm_at == stored.alarm_at
    assert client.post(url(event, "confirm"), {"alarm_at": "x"}, format="json").status_code == 400


def test_small_recompute_change_turns_unconfirmed(ctx):
    client, event, route = ctx
    before = plan_of(client.post(url(event, "confirm")))["alarm_at"]
    route["minutes"] = 22
    plan = plan_of(client.post(f"/api/events/{event.pk}/recompute"))
    moved = datetime.fromisoformat(before) - datetime.fromisoformat(plan["alarm_at"])
    assert timedelta(0) < moved <= timedelta(minutes=5)
    # 5분 이하는 묻지 않고 바로 바꾸며, 바뀐 시각은 확인 전이다.
    assert plan["alarm_proposal"] is None
    assert plan["effective_alarm_at"] == plan["alarm_at"]
    assert plan["alarm_confirmed"] is False


def test_preset_choice_clears_user_time_without_confirming(ctx):
    """τ 칸(PATCH)은 직접 정한 시각만 지운다. 확인은 명시적인 조작에서만 생긴다(H3-4)."""
    client, event, _ = ctx
    client.put(url(event, "time"), {"alarm_at": iso(recommended(event) + timedelta(minutes=10))}, format="json")
    plan = plan_of(client.patch(f"/api/events/{event.pk}", {"tau_override": .97}, format="json"))
    assert plan["alarm_source"] == "recommended"
    assert plan["user_alarm_at"] is None
    assert plan["effective_alarm_at"] == plan["alarm_at"]
    assert plan["alarm_confirmed"] is False
    # 새 시트는 칸을 고른 뒤 확인을 부른다.
    plan = plan_of(client.post(url(event, "confirm"), {"alarm_at": plan["effective_alarm_at"]}, format="json"))
    assert plan["alarm_confirmed"] is True


def test_current_app_preset_then_route_refresh_creates_no_proposal(ctx):
    """지금 앱: τ 칸 PATCH 뒤 경로 갱신(POST recompute)이 5분 넘게 움직여도 묻지 않고 바로 바꾼다."""
    client, event, route = ctx
    first = plan_of(client.patch(f"/api/events/{event.pk}", {"tau_override": .9}, format="json"))
    route["minutes"] = 40
    plan = plan_of(client.post(f"/api/events/{event.pk}/recompute"))
    assert datetime.fromisoformat(first["alarm_at"]) - datetime.fromisoformat(plan["alarm_at"]) > timedelta(
        minutes=5
    )
    assert plan["alarm_proposal"] is None
    assert plan["effective_alarm_at"] == plan["alarm_at"]
    assert plan["alarm_source"] == "recommended"
    stored = AlarmPlan.objects.get(event=event)
    assert stored.proposed_alarm_at is None and stored.user_alarm_at is None


def test_preset_choice_clears_user_time_when_replan_fails(ctx, monkeypatch):
    """τ 칸을 골랐는데 다시 계산이 실패해도 직접 정한 시각은 지운다(H3-6). 되살아나지 않는다."""
    from apps.planning import refresh

    client, event, route = ctx
    picked = recommended(event) + timedelta(minutes=10)
    client.put(url(event, "time"), {"alarm_at": iso(picked)}, format="json")

    # 원값으로 다시 섞지 못해 경로부터 다시 구하는데, 경로 조회도 실패한다.
    monkeypatch.setattr(refresh, "refresh_edited", lambda *a, **kw: None)
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (None, True))
    monkeypatch.setattr(services.clients, "resolve_route", lambda *a, **kw: (None, True))
    response = client.patch(f"/api/events/{event.pk}", {"tau_override": .97}, format="json")
    assert response.status_code == 200, response.content
    stored = AlarmPlan.objects.get(event=event)
    assert stored.status != AlarmPlan.Status.OK
    assert stored.user_alarm_at is None and stored.proposed_alarm_at is None
    # 경로가 돌아와 다시 계산되면 추천 시각이다. 예전 직접 정한 시각이 되살아나지 않는다.
    monkeypatch.setattr(services.clients, "best_route", lambda **kw: (dict(route), False))
    monkeypatch.setattr(services.clients, "resolve_route", lambda *a, **kw: (dict(route), False))
    plan = plan_of(client.post(f"/api/events/{event.pk}/recompute"))
    assert plan["alarm_source"] == "recommended"
    assert plan["effective_alarm_at"] == plan["alarm_at"]


def test_wheel_pick_equal_to_recommended_is_not_pinned(ctx):
    """휠로 추천 시각 그대로를 고르면 직접 정한 시각이 아니라 추천 시각 · 확인함이다(H3-7b)."""
    client, event, route = ctx
    plan = plan_of(client.put(url(event, "time"), {"alarm_at": iso(recommended(event))}, format="json"))
    assert plan["alarm_source"] == "recommended"
    assert plan["user_alarm_at"] is None
    assert plan["alarm_confirmed"] is True
    assert AlarmPlan.objects.get(event=event).user_alarm_at is None
    # 그 뒤 다시 계산하면 추천을 따라간다(5분 이하라 바로 바뀐다).
    route["minutes"] = 22
    plan = plan_of(client.post(f"/api/events/{event.pk}/recompute"))
    assert plan["effective_alarm_at"] == plan["alarm_at"]


def test_naive_alarm_time_is_read_in_user_timezone(ctx):
    """오프셋 없는 시각은 사용자 시간대(프로필 `timezone`)로 읽는다(H3-7a)."""
    from zoneinfo import ZoneInfo

    client, event, _ = ctx
    profile = event.user.profile
    profile.timezone = "Asia/Tokyo"  # +09:00. 서버 TIME_ZONE 과 달라도 같은 순간이어야 한다.
    profile.save()
    picked = (recommended(event) + timedelta(minutes=10)).astimezone(ZoneInfo("Asia/Tokyo"))
    naive = picked.replace(tzinfo=None).isoformat()
    plan = plan_of(client.put(url(event, "time"), {"alarm_at": naive}, format="json"))
    assert datetime.fromisoformat(plan["user_alarm_at"]) == picked

    profile.timezone = "America/New_York"
    profile.save()
    target = (recommended(event) + timedelta(minutes=15)).astimezone(ZoneInfo("America/New_York"))
    plan = plan_of(client.put(url(event, "time"), {"alarm_at": target.replace(tzinfo=None).isoformat()},
                              format="json"))
    assert datetime.fromisoformat(plan["user_alarm_at"]) == target


def test_other_edits_keep_or_drop_user_time(ctx):
    client, event, _ = ctx
    picked = recommended(event) + timedelta(minutes=10)
    client.put(url(event, "time"), {"alarm_at": iso(picked)}, format="json")
    plan = plan_of(client.patch(f"/api/events/{event.pk}", {"title": "전공 수업"}, format="json"))
    assert datetime.fromisoformat(plan["user_alarm_at"]) == picked
    moved = event.start_at + timedelta(hours=1)
    plan = plan_of(client.patch(f"/api/events/{event.pk}", {"start_at": iso(moved)}, format="json"))
    assert plan["alarm_source"] == "recommended" and plan["user_alarm_at"] is None
    assert AlarmPlan.objects.get(event=event).user_alarm_at is None
    # 새 시각은 사용자가 확인하지 않았다.
    assert plan["alarm_confirmed"] is False


def test_new_event_starts_unconfirmed(ctx):
    client, event, _ = ctx
    start = event.start_at + timedelta(days=1)
    response = client.post("/api/events", {
        "title": "발표", "start_at": iso(start),
        "place": {"name": "학교", "lat": 37.46, "lng": 126.95},
    }, format="json")
    assert response.status_code == 201, response.content
    plan = response.json()["alarm_plan"]
    assert plan["status"] == "ok"
    assert plan["alarm_confirmed"] is False and plan["alarm_source"] == "recommended"


# ---------------------------------------------------------------------------
# risk-options 의 추천 칸 (BE-H3e)
# ---------------------------------------------------------------------------


def risk(client, event):
    response = client.get(f"/api/events/{event.pk}/risk-options")
    assert response.status_code == 200
    return response.json()


def test_risk_options_recommended_key_follows_default_not_choice(ctx):
    client, event, _ = ctx
    data = risk(client, event)
    assert data["recommended_key"] == "normal" == data["selected_key"]
    assert data["default_tau"] == .9
    client.patch(f"/api/events/{event.pk}", {"tau_override": .65}, format="json")
    data = risk(client, event)
    assert data["selected_key"] == "gamble"
    assert data["recommended_key"] == "normal"


def test_risk_options_recommended_key_uses_tag_default(ctx):
    client, event, _ = ctx
    tag = EventTag.objects.filter(default_tau=.97).first() or EventTag.objects.create(
        key="exam_t", label="시험", default_tau=.97,
    )
    Event.objects.filter(pk=event.pk).update(tag=tag)
    data = risk(client, event)
    assert data["recommended_key"] == "safe" and data["default_tau"] == .97
    profile = event.user.profile
    profile.default_tau = .8
    profile.save()
    Event.objects.filter(pk=event.pk).update(tag=None)
    data = risk(client, event)
    assert data["recommended_key"] is None and data["default_tau"] == .8


def test_risk_option_probability_agrees_with_user_time_probability(ctx):
    """앱이 휠 확률을 risk-options 분포로 읽는 것(BE-H3c 대신)과 서버의 직접 정한 시각 확률이 맞는다."""
    client, event, _ = ctx
    data = risk(client, event)
    for option in data["options"]:
        at = datetime.fromisoformat(option["alarm_at"])
        plan = plan_of(client.put(url(event, "time"), {"alarm_at": iso(at)}, format="json"))
        # 추천 시각과 같은 칸은 직접 정한 시각이 되지 않는다(추천 시각 · 계획 확률).
        assert abs(plan["effective_on_time_probability"] - option["probability"]) <= 1
    # 분포 구간을 앱처럼 균등 보간해 읽은 값과도 몇 %p 안이다.
    bins = data["distribution"]["bins"]
    at = datetime.fromisoformat(data["options"][1]["alarm_at"]) + timedelta(minutes=10)
    available = (event.start_at - at).total_seconds() / 60
    below = sum(
        b["mass"] if available >= b["max"] else 0 if available <= b["min"]
        else b["mass"] * (available - b["min"]) / (b["max"] - b["min"])
        for b in bins
    )
    plan = plan_of(client.put(url(event, "time"), {"alarm_at": iso(at)}, format="json"))
    assert abs(plan["user_alarm_probability"] - round(below * 100)) <= 5
    assert ac.USER_PROBABILITY_FLOOR == 70


# Partly AI-generated (Claude) — BE-H3f.
def test_prep_values_follow_the_user_time(ctx):
    client, event, _ = ctx
    plan = plan_of(client.get(f"/api/events/{event.pk}"))
    assert plan["effective_prep_minutes"] == plan["prep_minutes"]
    assert plan["prep_margin_minutes"] == 0

    later = datetime.fromisoformat(plan["alarm_at"]) + timedelta(minutes=15)
    plan = plan_of(client.put(url(event, "time"), {"alarm_at": iso(later)}, format="json"))
    depart = datetime.fromisoformat(plan["depart_by"])
    assert plan["effective_prep_minutes"] == int((depart - later).total_seconds() // 60)
    assert plan["prep_margin_minutes"] == -15
    assert datetime.fromisoformat(plan["depart_by"]) == depart  # 출발 시각은 그대로

    earlier = later - timedelta(minutes=25)
    plan = plan_of(client.put(url(event, "time"), {"alarm_at": iso(earlier)}, format="json"))
    assert plan["prep_margin_minutes"] == 10


def test_prep_values_never_go_negative(ctx):
    client, event, _ = ctx
    plan = plan_of(client.get(f"/api/events/{event.pk}"))
    after_departure = datetime.fromisoformat(plan["depart_by"]) + timedelta(minutes=3)
    if after_departure > event.start_at - ac.USER_MIN_LEAD:
        pytest.skip("이 계획은 출발 뒤 · 시작 5분 전 사이에 고를 시각이 없다")
    plan = plan_of(client.put(url(event, "time"), {"alarm_at": iso(after_departure)}, format="json"))
    assert plan["effective_prep_minutes"] == 0
    # 출발 3분 뒤에 일어나면 계획한 준비보다 3분 더 모자라다(리뷰).
    assert plan["prep_margin_minutes"] == -plan["prep_minutes"] - 3


def test_prep_values_are_null_without_a_prep_step(ctx):
    """집에서 출발하지 않는 일정은 준비 단계가 없다. 막대에 준비 구간을 그리지 않게 null 이다(리뷰)."""
    client, event, _ = ctx
    event.origin_lat, event.origin_lng = 37.47, 126.94
    event.save()
    services.compute_and_store(event)
    assert event.prep_applies is False
    plan = plan_of(client.get(f"/api/events/{event.pk}"))
    assert plan["effective_prep_minutes"] is None
    assert plan["prep_margin_minutes"] is None
