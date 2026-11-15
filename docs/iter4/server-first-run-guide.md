# 첫 실행 안내 · 홈 코치마크 "본 적 있음" (서버, iter4 BE-H4)

<!-- Partly AI-generated (Claude) -->

앱 계약 `first-run-guide-api.md`(`origin/front-iter4` 의 `docs/iter4/`)의 서버 구현이다. 계약을 그대로 따랐다.
구현: `backend/apps/accounts/models.py` `Profile.intro_seen_at` · `coachmark_seen_at`
(마이그레이션 `accounts 0008_login_attempt_and_guide_seen`), `serializers.ProfileSerializer`, `views_profile.ProfileView` ·
테스트: `backend/apps/accounts/tests/test_guide_seen.py`

## 필드 — `GET /api/profile` · `PATCH /api/profile` (로그인 필요)

| 필드 | 타입 | 뜻 |
| --- | --- | --- |
| `intro_seen_at` | ISO 8601 시각 또는 `null` | 첫 실행 안내를 끝까지 넘겼거나 [건너뛰기] 한 시각 |
| `coachmark_seen_at` | ISO 8601 시각 또는 `null` | 홈 코치마크를 닫은 시각 |

- 처음에는 둘 다 `null` 이다(DB 기본값 `NULL`, 기존 계정도 `null`).
- 앱이 보낸 시각을 그대로 저장한다. `Z` · `+09:00` 모두 받는다. 응답은 다른 시각 칸처럼 서버 시간대(`+09:00`)로 나온다.
  예: `"2026-11-11T04:12:30.512Z"` → `"2026-11-11T13:12:30.512000+09:00"`. 앱은 null 인지만 본다.
- `null` 을 보내면 지운다. 보내지 않은 칸은 그대로다(부분 수정).
- 시각이 아닌 값은 400 `invalid`(`details.intro_seen_at` 등).
- **이 두 칸(또는 τ · 시간대 · 집 이름)만 바꾼 PATCH 는 알람을 다시 계산하지 않는다** — `recomputed_plans: 0`.
  집 좌표나 준비 시간을 함께 바꾸면 전처럼 다시 계산한다.
- 알람 계산 · 학습은 이 값을 보지 않는다.

```http
PATCH /api/profile
{"intro_seen_at": "2026-11-11T04:12:30.512Z"}

200
{"home_lat": null, ..., "intro_seen_at": "2026-11-11T13:12:30.512000+09:00", "coachmark_seen_at": null, "recomputed_plans": 0}
```

## 구버전 서버

0.20.0 까지는 두 칸을 모른다. 모르는 칸은 무시하고 응답에도 없으므로 앱은 기기 기록(`FirstRunGuideStore`)으로
판단한다(앱 계약 2절). 앱이 바꿀 것은 없다.

## 운영

Django admin 프로필 화면 **첫 실행 안내** 칸에서 보고 비울 수 있다. 비우면 그 계정의 다음 가입 · 홈에서 다시 뜬다
(기기 기록이 남아 있으면 그 기기에서는 뜨지 않는다).
