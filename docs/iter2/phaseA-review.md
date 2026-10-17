# Phase A — 도보·자전거 전체 화면 지도 미리보기, 필드별 오류, 확률 문구 정리, 준비 시간 다이얼

> 이 문서는 해당 Phase의 설계·리뷰·검증 당시 기록이다. 당시 테스트 수·미구현 설명을 현재 상태로 해석하지 않는다. 2026-10-17 후속 통합 상태는 [최신 TODO](../../task.md), [요청별 완료 상태](backend-requests.md), [최신 검증](account-ranges-and-travel-counts.md)을 따른다.

PM 지시 네 가지(도보·자전거는 눌러서 지도, 백엔드 후속 확인, 불확실한 정보 정리, 가입 때 준비 시간을 시계 다이얼로)를 [phaseA-design.md](phaseA-design.md)와 그 "Review resolutions" 대로 구현했다. ⑬ 에서 좌표가 있는 도보·자전거 카드를 누르면 같은 경로 안에서 전체 화면 지도(⑬-e)가 열리고, 구간 막대·노선 칩은 `TransitVisuals.kt` 로 옮겨졌을 뿐 색 규칙(`SegmentPalette`)은 그대로다. 가까운 출발·도착 400 은 일정 추가의 해당 줄 아래에 붙고 경로 선택에서는 "다시 시도" 대신 장소를 고치러 돌아가게 했으며, 확률이 없으면 숫자 자리를 숨기고 서버 동작에 맞는 이유를 쓴다. 준비 시간은 일정 추가와 같은 공용 시계 다이얼(24시간제, 5분~4시간)로만 고르고, 가입 직후 온보딩과 설정 ⑯ 이 같은 화면을 모드만 바꿔 쓴다.

Watch for: 미리보기에서 "이 경로로 계산" 을 누르면 사라지는 화면이 잠깐 경로 목록으로 바뀜(likely, 외관만). 바뀐 E2E 두 개(`PublicSetupTest`·`RouteDisplayTest`)는 컴파일만 됐고 기기 실행이 머지 전에 남아 있음(confirmed). Figma F-0~F-9 는 아직 반영되지 않음(confirmed, 사용자가 정한 작업 범위에 포함).

**Verdict**: APPROVED

## High-level view

미리보기는 새 화면 경로를 쌓지 않고 `RouteState.previewKey` 하나로 ⑬ 안에서 바꿔 그린다. 뒤로는 `MainActivity` 의 `BackHandler` 가 먼저 미리보기를 닫고, "이 경로로 계산" 은 `onSelect` + `confirmRoute` 로 일정 추가에 바로 돌아간다. 지도 상태는 `CandidateRouteMapCard` 의 `remember` 에만 있어 일정 지도(`_routeMap`)·추적 서비스·현재 위치로 가는 길이 없다(N19·N20). 남은 문제는 확인 직후의 퇴장 애니메이션과, 스크롤이 없는 화면이라 가로 모드에서 지도가 작아지는 점이다.

실패 응답은 파일 수준 `failureFrom` 이 본문을 한 번만 읽어 서버 메시지와 `details` 필드를 함께 담는다. 일정 생성은 필드 오류가 있으면 출발지·도착지 줄에만 붙이고, 경로 후보는 `details.destination` 이면 재시도를 막고 같은 문구를 도착지 줄에도 남긴다. 50m 규칙은 서버 문구로만 들어오고, 판정 순수 함수는 테스트됐지만 뷰모델 연결은 테스트가 없다.

확률이 null 이면 홈은 확률 줄과 행 값을 비우고, ⑤ 옵션 카드는 글자와 막대를 함께 숨기며, ④ 는 "정시 도착 확률 · 아직 계산할 수 없음" 과 basis × 출처별 이유, PM 문구 "이동 기록이 쌓이면 변동성이 생김" 을 쓴다. ④ 이동 줄의 "실측" 은 `travel_time_source` 에 따른 추정·보정 문구로, τ 설명은 서버 `effective_tau` 순서의 출처로 바뀌었고, "0일 / 14일" 진행 줄은 없어졌다. 화면 문구는 `UnreliableCopyAuditTest` 가 지키지만, 주석·KDoc 몇 곳은 옛 전제("관측이 쌓이기 전")를 그대로 말한다.

시계 다이얼은 `ui/common/JitTimePickerDialog.kt` 한 벌만 남았고 일정 추가(12시간제)와 준비 시간(`JitDurationPickerDialog`, 시:분 = 걸리는 시간)이 같이 쓴다. 범위는 `PrepOnboardingState.range`(5~240) 하나에서 오고, 범위 밖이면 확인을 끄되 값을 잘라 넣지 않는다. 설정에서 열면 버튼이 "저장" 이 되고 "나중에 입력" 이 숨으며, 온보딩 쪽 건너뛰기는 그대로다. 설정 모드를 닫을 때도 미리보기와 같은 원인으로 사라지는 화면의 버튼이 잠깐 온보딩 모양으로 바뀐다.

단위 테스트 563건(실패 0), lint 오류 0, 앱·androidTest APK 빌드는 마지막 소스 수정 뒤의 산출물로 확인했다. 기기 위 E2E, TalkBack, 다크 테마 다이얼 색은 확인되지 않았다. 밝은 칸 글자 대비와 ⑬ 카드의 출발~도착 시각은 설계대로 지금 동작을 유지했고 사용자 결정을 기다린다.

<details>
<summary>Issues (6)</summary>

1. **미리보기 확인 뒤 퇴장 화면이 목록으로 바뀜** — `confirmRoute()` 가 `goBack()` 전에 `previewKey` 를 지워 사라지는 ⑬ 이 목록으로 다시 그려짐(likely). 재진입 정리는 `openRouteChoice()` 가 이미 하므로 그 첫 줄을 뺀다. 설정 모드 ⑮ 의 같은 현상은 건너뛰기·초기화가 `fromSettings` 를 유지하게 해 함께 정리한다.
2. **기기 E2E 미실행** — 바뀐 `PublicSetupTest`(다이얼 `pickDuration`, 미리보기 분기)·`RouteDisplayTest` 는 컴파일만 확인됨(confirmed). 머지 전에 `qa/demo/run.py --replay-routing` 을 한 번 돌리고 결과를 `docs/demo-checklist.md` 에 적는다.
3. **Figma 미반영** — 사용자가 정한 작업 범위에 Figma 가 들어 있는데 F-0~F-9 가 아직 없음(confirmed). 메인 세션에서 `figma-changes.md` 대로 반영하고, 새 프레임(⑬-e, ⑮-a) 노드 ID 를 코드 KDoc 에 적는다.
4. **가로 화면에서 미리보기 지도가 작아짐** — 스크롤 없는 루트라 가로에서는 지도에 80~100dp 만 남고, 큰 글꼴이면 "이 경로로 계산" 이 화면 밖으로 밀림(likely, 그 조건에서만). 낮은 높이에서는 지도 최소 높이 + 스크롤로 바꾸거나 알려진 한계로 적는다.
5. **옛 전제를 말하는 KDoc** — `RiskEmptyState`, `UpcomingEvent`, `AlarmPlanView.onTimeProbability`, `RoutineBlock.isPointRange`, `RouteChoiceScreen` 머리 KDoc(confirmed). 감사 테스트가 주석 줄을 건너뛰므로 한 줄씩 손으로 고친다.
6. **AI 마커 누락·형식** — 이번에 고친 `HomeViewModel` 여섯 곳과 `MainActivity` `BackHandler` 블록에 "Partly" 표시가 없고, 1차 리포트가 2차 약속으로 적은 "(tool, date, reviewer)" 형식은 어느 마커에도 없음(confirmed). 팀이 형식을 정하고 커밋 전에 맞춘다.

</details>

<details>
<summary>Details</summary>

### ⑬-e 미리보기의 상태 흐름과 가로 화면 높이

```
⑬ RouteChoiceScreen
 ├─ previewKey == null ─> 목록(route_scroll)
 │     카드 탭: canPreviewMap && loadMap != null ? openRoutePreview(key) : onRouteSelected(key)
 │     openRoutePreview = onRouteSelected(key) + previewKey = key      (_routeChoice 만 바뀜)
 └─ previewKey 의 후보가 canPreviewMap ─> RoutePreviewScreen
       ├─ ‹ / 시스템 뒤로 ─> closeRoutePreview()        (선택 유지, 같은 화면 경로)
       └─ 이 경로로 계산  ─> onSelect(key) + confirmRoute() ─> goBack() ─> ⑫ 일정 추가
 CandidateRouteMapCard: remember { RouteMapState(eventId = 0) }, showCurrentLocation = false
   └─ loadCandidateMap ─> repository.staticMap(markers = []) ─> 서버 places/staticmap 프록시
```

미리보기 루트는 지도가 `weight(1f)` 로 남은 높이를 채우도록 일부러 스크롤을 두지 않았다(설계 리뷰 #4). 그런데 고정 높이 요소(헤더·제목 행·부제·요약·버튼, 간격 12dp × 5, 위아래 여백 34dp)만 어림잡아 245dp 라서, 가로 화면(쓸 수 있는 높이 약 320~350dp)에서는 지도에 80~100dp 정도만 남는다. 글꼴을 키우면 고정 요소가 화면 높이를 넘어 지도 높이가 0 이 되고 "이 경로로 계산" 이 화면 밖으로 밀린다(likely, 가로 + 큰 글꼴 조건, 기기에서 보지 않음). 목록으로 돌아가 `route_confirm` 으로 확정할 길은 남는다. 앱은 세로 고정이 아니다(`AndroidManifest.xml` 에 `screenOrientation` 없음).

### "이 경로로 계산" 뒤 사라지는 화면이 목록으로 바뀜

```kotlin
fun confirmRoute() {
    // 후보 캐시는 다시 들어올 때 쓰려고 남긴다. 미리보기가 열린 채 남으면 안 된다.
    _routeChoice.update { it?.copy(previewKey = null) }   // ① 먼저 지움
    ...
    goBack()                                              // ② 그다음 화면 전환 시작
}
```

`MainHost` 는 `AnimatedContent(targetState = nav)` 로 화면을 바꾼다(퇴장 slide 280ms + fadeOut 160ms). 퇴장 중인 ⑬ 도 `routeState` 를 그대로 읽으므로, 미리보기에서 확인하면 사라지는 쪽이 전체 화면 지도에서 경로 목록으로 바뀐 채 미끄러져 나간다(likely, 구조로 추론, 기기에서 보지 않음). 목록의 `route_confirm` 에서는 내용이 바뀌지 않아 생기지 않는다. 재진입 정리는 `openRouteChoice()` 의 캐시 분기가 이미 `previewKey` 를 지우고(비캐시 분기는 새 `RouteState`), `MainActivity` 의 `routePreviewOpen` 은 `nav.current == AppRoute.RouteChoice` 에 묶여 있어 남은 키가 다른 화면의 뒤로를 가로채지 않는다. 그러니 ① 을 빼면 된다.

같은 원인이 설정에서 연 ⑮ 에도 있다. 헤더 뒤로(`skipPrepOnboarding()` → `PrepOnboardingState(done = true)`)와 저장 성공 뒤의 `goBack()` + `resetPrepOnboarding()` 이 상태를 기본값으로 되돌리는데, 기본값은 `fromSettings = false` 다. 그래서 사라지는 설정 모드 화면에 "저장하고 시작하기" 와 "나중에 입력" 이 잠깐 나타난다(likely). 값 칸이 "—" 로 비는 것은 일정 추가·집 설정과 같은 기존 패턴이지만, 버튼 변형이 바뀌는 것은 이번 `fromSettings` 에서 생겼다. 두 진입점(`openPrepOnboarding`, `maybeAskOnboarding`)이 이제 항상 새 상태를 만들므로 건너뛰기와 초기화가 `fromSettings` 를 유지해도 된다.

### 가까운 출발·도착 400 을 줄에 붙이는 규칙

필드 오류가 있으면 공통 줄을 비워도(`endpointErrorsOf`) 다른 메시지를 잃지 않는다. 서버의 50m 검사(`validate_route_distance`)와 국외 출발지 검사는 `EventWriteSerializer.validate()`(객체 단위)에 있어서, 제목 같은 필드 단위 오류가 하나라도 있으면 실행되지 않기 때문이다.

Not tested: 줄별 배치·재시도 판정은 순수 함수(`RouteChoiceRules.kt`)로 단위 테스트됐지만, `HomeViewModel` 이 그 결과를 `AddState`·`RouteState` 에 넣고 출발지·도착지 변경 때 지우는 연결은 테스트가 없다. 50m 사례를 도는 E2E 도 없다.

### 화면 밖에 남은 옛 전제

KDoc·주석 몇 곳이 이번 변경이 화면에서 지운 전제를 그대로 말한다(confirmed). `RiskChoiceScreen.kt` 의 `RiskEmptyState` KDoc 은 "관측이 없어 서버가 옵션을 주지 못할 때" 라고 해서 같은 파일 머리 KDoc 의 "관측이 부족해서가 아니다" 와 반대다. `UpcomingEvent.kt`(14·30행)와 `AlarmPlan.kt`(46행)는 확률 null 을 "관측이 쌓이기 전"·"관측이 없으면" 으로 설명하고, `RoutineBlock.kt`(62행) `isPointRange` 는 "확률 계산이 시작되지 않는다" 고 해서 범위만 주면 확률이 시작된다는 전제를 남긴다. `RouteChoiceScreen.kt` 머리 KDoc 의 "소요시간은 서버가 카카오에서 실측한 값이다" 는 이번에 ④ 에서 지운 표현과 같다. `UnreliableCopyAuditTest` 는 주석 줄을 건너뛰므로 이것들을 잡지 못한다.

### 검증 근거와 남은 기기 확인

[phaseA-verification.md](phaseA-verification.md)의 결과를 빌드 산출물로 대조했다. `testDebugUnitTest` XML 74개 합계는 563건·실패 0·오류 0이고, lint 는 오류 0·경고 62, `app-debug.apk`·`app-debug-androidTest.apk` 가 있다. 모두 13:57~13:58 산출물로, 바뀐 소스의 마지막 수정(13:53)보다 뒤다.

바뀐 E2E 는 10-12 에 기기에서 통과한 UiAutomator 패턴(병합된 클릭 노드 안의 `findObject(By.text(...))`, 분 다이얼 드래그)을 그대로 쓴다. 새로 기대는 것은 24시간 다이얼의 시 접근성 설명("%d hours"/"%d시간")인데, 이 설명은 AAR 문자열로만 맞췄다. `qa/demo/scenario.json` 의 `onboarding_prep_minutes` 가 5 라서 첫 기기 실행이 다이얼 범위의 아래 경계(0:05)를 바로 지난다.

Not tested: `qa/demo/run.py`(로컬·`--replay-routing`·공용) 기기 실행, 퇴장 애니메이션 비침, 가로 화면 미리보기, TalkBack 낭독, 다크 테마 다이얼 색.

### AI 마커 형식

새 파일과 새 최상위 함수는 기존 관례(`// AI-generated (Claude)`, `// Partly AI-generated (Claude)`)를 따르고, KDoc 은 한국어 "~다" 체다. 다만 이번에 AI 가 고친 기존 코드 중 `HomeViewModel` 의 `loadCandidates`·`confirmRoute`·`submitAdd`·`maybeAskOnboarding`·`openPrepOnboarding`·`RouteState` 와 `MainActivity` 의 `BackHandler` 블록에는 "Partly" 표시가 없다(confirmed). 1차 AI 협업 리포트 §7 은 2차의 약속으로 "mark AI-generated code (tool, date, reviewer)" 를 적었는데, 기존 마커 123개와 이번에 더한 36개 모두 도구 이름만 있다. 형식은 팀이 정할 일이고, 정하면 커밋 전에 맞춘다.

</details>

<details>
<summary>File map</summary>

- `MainActivity.kt` — `BackHandler` 가 미리보기를 먼저 닫음, `onPreview`/`onClosePreview` 연결, `onStep` 제거, 저장 스낵바에 `durationLabel`.
- `alarm/AlarmActivity.kt` — 추적 카드 문구("이 기록이 쌓이면 이동 시간 보정에 쓰임").
- `data/remote/EventsApi.kt` — KDoc 만(확률 null, basis 표, 출처 설명).
- `data/repository/EventRepository.kt` — `Result.Failure.fields`, 파일 수준 `failureFrom`·`fallbackErrorMessage`(429 추가), `toConfidence` internal + 새 문구, `travelSourceNote`, `tauSourceLabel`, `prepNote` 문구.
- `data/repository/RoutineRepository.kt` — `learningNote` 문구.
- `domain/model/AlarmPlan.kt` — `tauSourceLabel`, `isLearning` → `isUnavailable`.
- `domain/model/RouteOption.kt` — `canPreviewMap`.
- `ui/alarm/AlarmDecisionScreen.kt` — 확률 없음 문구, τ 출처 줄, 블록 입구 문구.
- `ui/alarm/RiskChoiceScreen.kt` — 확률 없으면 글자·막대 숨김, "0일 / 14일"·`ProgressRow` 삭제, "선택지 준비 중" 카드.
- `ui/alarm/RouteMapCard.kt` — `mapHeight`(null = `weight(1f)`), `showCaption`.
- `ui/common/JitTimePickerDialog.kt` (새) — 공용 시계 다이얼, `JitDurationPickerDialog`, `durationLabel`.
- `ui/common/JitPickerValue.kt` (새) — 값 칸(`Role.Button`, `onClickLabel`).
- `ui/events/AddEventScreen.kt` — 공용 다이얼·값 칸 사용, 출발지·도착지 줄 아래 `EndpointError`.
- `ui/events/CandidateRouteMapCard.kt` — `modifier`·`mapHeight`·`showCaption` 파라미터.
- `ui/events/PrepOnboardingScreen.kt` — ±버튼·숫자칸·프리셋 삭제, 다이얼 입력, 설정 모드.
- `ui/events/RoutePreviewScreen.kt` (새) — 전체 화면 지도 미리보기.
- `ui/events/RouteChoiceScreen.kt` — 미리보기 분기, 카드 탭 → 미리보기, 목록 안 지도 삭제, `ErrorBlock(retryable)`.
- `ui/events/TransitVisuals.kt` (새) — 구간 막대·노선 칩·지표 칸·배지.
- `ui/home/HomeScreen.kt` — 확률 없으면 줄·값 숨김, 루틴 입구 문구.
- `ui/home/HomeViewModel.kt` — `previewKey`·`retryable`, `openRoutePreview`/`closeRoutePreview`, 줄 오류, `PrepOnboardingState.fromSettings`·`range`, ± 단계 삭제.
- `ui/home/RouteChoiceRules.kt` (새) — `endpointErrorsOf`·`candidatesRetryable`·`previewKeyAfterReload`.
- `ui/routines/RoutineEditorScreen.kt` — 범위 안내·빈 상태 문구.
- `ui/settings/SettingsScreen.kt` — 준비 시간 값에 `durationLabel`.
- `androidTest/PublicSetupTest.kt`, `androidTest/RouteDisplayTest.kt` — 다이얼 `pickDuration`, 미리보기 흐름.
- 단위 테스트(새): `ConfidenceCopyTest`, `EventFailureFieldsTest`, `PlanSourceLabelsTest`(`TravelSourceNoteTest`·`TauSourceLabelTest`), `EndpointErrorsTest`, `RoutePreviewRuleTest`, `DurationLabelTest`, `UnreliableCopyAuditTest`. 고침: `NetworkDtoNullSafetyTest`, `PrepOnboardingStateTest`, `RouteChoiceSelectionStyleTest`, `RoutineBlockTest`.
- 문서: `docs/demo-checklist.md`, `qa/demo/README.md`, `task.md`, `docs/iter2/*`(설계·설계 리뷰·Figma 명세·백엔드 요청·검증 기록).

전체 diff: `git -C SWPP-PJ-front-iter2 diff` 와 `git status` 의 새 파일.

</details>
