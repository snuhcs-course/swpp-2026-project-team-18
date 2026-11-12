# 첫 실행 안내 · 홈 코치마크 "본 적 있음" (iter4 H2-4)

<!-- Partly AI-generated (Claude) -->

첫 실행 안내 세 장(Figma `545:1305` · `546:2214` · `547:2009`)과 홈 코치마크(`549:1845`)를 **계정**이 본 적
있는지 서버에 둔다(팀 결정 11-11). 기기에만 두면 다른 기기로 로그인하거나 앱을 다시 깔 때마다 같은 안내가
다시 뜬다.

서버 구현: `accounts.Profile.intro_seen_at` · `coachmark_seen_at`(마이그레이션 `0008_profile_guide_seen`),
`ProfileSerializer` · 테스트: `apps/accounts/tests/test_api.py::TestGuideSeen`

앱 구현: `data/local/FirstRunGuideStore.kt`, `ui/onboarding/GuideSeenRules.kt`,
`EventRepository.markGuideSeen`, `HomeViewModel.finishIntro/dismissCoachmark/syncGuideSeen` ·
테스트: `GuideSeenRulesTest`

## 1. 필드

`GET /api/profile` · `PATCH /api/profile` 에 두 필드가 생긴다. 둘 다 처음에는 `null` 이다.

| 필드 | 뜻 | 앱이 채우는 때 |
| --- | --- | --- |
| `intro_seen_at` | 첫 실행 안내를 끝까지 넘겼거나 [건너뛰기] 한 시각 | 안내를 닫을 때 |
| `coachmark_seen_at` | 홈 코치마크를 닫은 시각 | 코치마크의 아무 곳이나 누를 때 |

- 값은 ISO 8601 시각이다. 앱은 `Instant.now().toString()`(UTC, `Z`)을 보낸다.
- `null` 을 보내면 지운다(시험용). 앱은 지우지 않는다. [처음 안내 다시 보기]는 기기 안에서만 다시 띄운다.
- 이 두 필드만 바꾼 PATCH 는 알람을 다시 계산하지 않는다(`recomputed_plans: 0`).

앱이 보내는 예:

```json
{"intro_seen_at": "2026-11-11T04:12:30.512Z"}
```

## 2. 앱의 판단

기기 저장소(`FirstRunGuideStore`)는 지우지 않고 **함께** 쓴다. 오프라인이거나 서버가 이 필드를 모르면(구버전은
모르는 필드를 무시한다) 기기 값으로 판단한다.

- **안내를 띄울지:** 가입 중(집 · 준비 시간을 묻는 중)이고, 서버 `intro_seen_at` 도 기기 표시도 없을 때.
- **코치마크를 띄울지:** 기기에 "띄울 차례"가 있거나(이 기기에서 안내를 봤거나 다시 보기를 눌렀다), 서버에 안내를
  본 시각은 있는데 코치마크를 본 시각이 없고 이 기기에서도 닫은 적이 없을 때.
- **올리기:** 기기에서 봤는데 서버가 모르면 홈을 받을 때 한 번 올린다(PATCH 가 실패했던 경우를 메운다).
  앱이 켜져 있는 동안 한 번만 시도한다.
- 캐시로 그린 홈은 판단에 쓰지 않는다.
