# Phase A 구현 검증 기록

> 이 문서는 해당 Phase의 설계·리뷰·검증 당시 기록이다. 당시 테스트 수·미구현 설명을 현재 상태로 해석하지 않는다. 2026-10-17 후속 통합 상태는 [최신 TODO](../../task.md), [요청별 완료 상태](backend-requests.md), [최신 검증](account-ranges-and-travel-counts.md)을 따른다.

- 일시: 2026-10-13. worktree `SWPP-PJ-front-iter2`, 브랜치 `front-iter2`, 기준 `b1eff1f`. **커밋하지 않음**(리뷰 승인 뒤 다음 단계에서 커밋).
- 기준 설계: [phaseA-design.md](phaseA-design.md) + 끝의 "Review resolutions"(설계 리뷰 [phaseA-design-review.md](phaseA-design-review.md) 반영).

## 실행한 명령과 결과

앱 (`APP`, `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`):

```
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain
BUILD SUCCESSFUL in 1m 28s (93 actionable tasks)
```

- 단위 테스트: **563건, 실패 0, 오류 0, 건너뜀 0** (`app/build/test-results/testDebugUnitTest`).
  - 새 테스트: `ConfidenceCopyTest`(5), `EventFailureFieldsTest`(6), `TravelSourceNoteTest`(3), `TauSourceLabelTest`(3), `EndpointErrorsTest`(7), `RoutePreviewRuleTest`(3), `DurationLabelTest`(3), `UnreliableCopyAuditTest`(1).
  - 고친·늘린 테스트: `NetworkDtoNullSafetyTest`(+1, `badges: []`·`steps/kcal: null`·`path` 없음·`alt_vehicles: null`), `PrepOnboardingStateTest`(+2, 범위 경계·설정 모드 기본값), `RouteChoiceSelectionStyleTest`(기준점을 `TransitVisuals.kt` 로), `RoutineBlockTest`(`isLearning` → `isUnavailable`).
- lint: **오류 0**, 경고 62. 이번에 손댄 파일의 경고는 `CandidateRouteMapCard.kt` `AutoboxingStateCreation` 1건뿐이고 기존 코드(`retry` 상태)다.
- `assembleDebug`(`app-debug.apk`)와 `assembleDebugAndroidTest`(`app-debug-androidTest.apk`) 성공 — 바꾼 `PublicSetupTest`·`RouteDisplayTest` 가 컴파일된다.

백엔드: 코드를 고치지 않았으므로 `pytest`·`run_local_suite.py` 는 돌리지 않았다(서버 요청은 [backend-requests.md](backend-requests.md)).

## 확인하지 못한 것

- **기기 위 계측 테스트**: `qa/demo/run.py`(로컬·`--replay-routing`·공용 모드)는 이 단계에서 돌리지 않았다. 머지 전에 재생 모드를 한 번 돌려야 한다(`docs/demo-checklist.md` 로컬 E2E 행에 "재실행 대기" 로 적음).
  - 다이얼 조작의 접근성 설명은 Material3 1.4.0 AAR 문자열로 맞췄다: 24시간 시 `"%1$d hours"`(한국어 `"%1$d시간"`), 분 `"%1$d minutes"`(`"%1$d분"`), `"Select minutes"`(`"분 선택"`). 실제 낭독·드래그 정확도는 기기에서만 확인된다.
- 다크 테마 다이얼 색, 전체 화면 지도 미리보기의 체감(overscan·핀치), TalkBack 낭독은 실기기 확인 대상이다.
- **Figma 미반영**: 이 워크플로우에는 Figma 도구가 없다. 메인 세션이 [figma-changes.md](figma-changes.md)(F-0~F-9, 리뷰 반영본)대로 따로 반영한다.
- 유지하고 사용자 확인이 필요한 것: 밝은 칸 글자 대비(지금 흰 글자 그대로, 리뷰 #1), ⑬ 카드 출발~도착 시각(앱 임시 계산, 리뷰 #22).
