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
- 소수 초는 마이크로초(6자리)까지 저장한다. 나노초 9자리(`"…30.512345678Z"`, `Instant.toString()`)도 받고 뒤를 버린다
  → `"…30.512345+09:00"`.
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

## 같은 때 들어온 PATCH (리뷰 수정 H4-1 · H4-2)

- **보낸 칸만 저장한다**(`ProfileSerializer.update` → `save(update_fields=…)`). 전에는 모든 칸을 다시 썼다. 서버 워커가 둘이라
  안내 닫기 PATCH 가 같은 때의 집 · 준비 시간 변경(또는 저장한 집 `make-default`)을 옛 값으로 되돌릴 수 있었고, 1초 간격의
  `intro_seen_at` · `coachmark_seen_at` PATCH 가 서로를 `null` 로 지울 수 있었다.
- PATCH 는 트랜잭션 안에서 **프로필 행을 잠그고(`select_for_update`) 다시 읽은 뒤** 검사 · 저장한다. "바뀌었나"(다시 계산 여부)도
  잠근 행 기준이다. 저장한 집의 쓰기 요청(`POST` · `PATCH` · `bulk` · `make-default`)도 같은 행을 먼저 잠근다. 알람 다시 계산은
  잠금을 푼 뒤에 한다.
- **집 좌표 검사는 `home_lat` 이나 `home_lng` 를 보낸 PATCH 에서만 한다.** 예전 규칙으로 국외 집이 저장된 계정도 안내를 본
  시각 · τ · 집 이름만 바꾸는 PATCH 는 200 이다. 좌표 하나만 보내면 저장된 다른 하나와 짝지어 검사한다.
- 집 검사 문구는 해요체다: "집 위도와 경도는 함께 설정해야 해요." · "위도 범위를 벗어났어요." · "경도 범위를 벗어났어요." ·
  "국내 좌표만 집으로 설정할 수 있어요." (400 `invalid`, `details.home_lat` / `details.home_lng`). 코드는 그대로다.

테스트: `test_guide_seen.py`(`TestOnlySentFieldsAreSaved` · `TestHomeValidationOnlyWhenSent` · `test_nanosecond_timestamps_are_truncated`),
`test_api.py` `TestProfileSerializerSavesOnlySentFields`(먼저 읽은 인스턴스로 저장). SQLite 는 `FOR UPDATE` 를 내지 않으므로
잠금은 부르는지만 테스트하고, 동시 요청은 임시 Postgres 에서 손으로 확인했다.

## 구버전 서버

0.20.0 까지는 두 칸을 모른다. 모르는 칸은 무시하고 응답에도 없으므로 앱은 기기 기록(`FirstRunGuideStore`)으로
판단한다(앱 계약 2절). 앱이 바꿀 것은 없다(리뷰 수정 뒤에도 요청 · 응답 모양은 같다).

## 운영

Django admin 프로필 화면 **첫 실행 안내** 칸에서 보고 비울 수 있다. 비우면 그 계정의 다음 가입 · 홈에서 다시 뜬다
(기기 기록이 남아 있으면 그 기기에서는 뜨지 않는다).
