# Phase A 설계 — 경로 미리보기 · 백엔드 후속 · 불확실 정보 정리 · 준비 시간 다이얼

> 이 문서는 해당 Phase의 설계·리뷰·검증 당시 기록이다. 당시 테스트 수·미구현 설명을 현재 상태로 해석하지 않는다. 2026-10-17 후속 통합 상태는 [최신 TODO](../../task.md), [요청별 완료 상태](backend-requests.md), [최신 검증](account-ranges-and-travel-counts.md)을 따른다.

- 기준: `front-iter2` (HEAD `b1eff1f`, origin/main 과 같음). 서버 `APP_VERSION 0.10.0`, 앱 `versionName 0.10.0`.
- 범위: Figma 시안 + 앱 화면(프론트). 서버 변경은 하지 않는다. 서버가 해 줘야 하는 것은 [backend-requests.md](backend-requests.md).
- Figma: 이 작업 환경에 Figma MCP 도구가 없어 직접 수정하지 못했다. 프레임별 변경 명세는 [figma-changes.md](figma-changes.md). **팀 규칙대로 Figma 반영 → 코드 순서**로 진행한다.
- 경로 표기: `ui/…` = `APP/app/src/main/java/com/swpp/wakeup/ui/…`.

## 0. 개요

네 가지를 한 번에 정리한다. A1은 도보·자전거 카드를 누르면 **전체 화면 지도 미리보기**로 넘어가게 하고, 구간 색 규칙(`SegmentPalette`)과 막대·노선 칩을 한 파일의 공용 부품으로 묶는다. A2는 `8a04736` 이후 서버가 추가한 필드·오류를 모두 앱에서 처리한다(가까운 출발·도착 400을 해당 줄 아래에 표시하는 것이 핵심). A3는 "학습 중"을 화면에서 없애고, 서버 동작과 맞지 않는 문구·고정값("0일 / 14일" 등)을 지우거나 고친다. A4는 준비 시간 입력을 ±버튼·숫자칸·프리셋에서 **시계 다이얼(24시간제, 시:분 = 걸리는 시간)** 하나로 바꾸고, 다이얼 대화상자를 `ui/common` 공용 부품으로 뺀다.

기술 스택은 기존 그대로 고정한다: Kotlin · Jetpack Compose(Material3, BOM `2026.09.00`) · Retrofit/Gson · JUnit4 단위 테스트 · UiAutomator 계측 테스트. 새 의존성은 없다.

지켜야 할 것:
- **지어낸 숫자 금지.** 서버가 값을 주지 않으면 그 자리를 숨기거나 짧은 중립 문구를 쓴다. 0·임의값으로 채우지 않는다.
- **N19 이동 흔적 없음.** 미리보기는 현재 위치를 묻지 않고, 경로 좌표를 저장하지 않는다(메모리의 후보 응답만 쓴다).
- **앱에 API 키 없음.** 지도는 지금처럼 서버 `places/staticmap` 프록시로 받는다.
- **규칙은 서버가 소유.** 50m 거리 규칙, 확률 판정, τ 판정을 앱에서 다시 계산하지 않는다. 서버 메시지를 그대로 보여 준다.
- 화면 문구는 짧은 "~함/~음" 체, 코드는 한국어 KDoc. 네트워크 DTO 의 새 필드는 전부 nullable + 기본값(구버전 서버 대비).

## A1. 경로 선택 — 도보·자전거 지도 미리보기, 수단별 색 컴포넌트

### A1-1. 지금 상태 (코드 확인)

- `RouteChoice.optionsOf` 가 도보·자전거를 1개만 돌려준다(이미 됨). 탭을 바꾸면 그 탭의 첫 후보가 자동 선택된다.
- 선택된 도보·자전거 카드 아래에 `CandidateRouteMapCard`(260dp, 핀치 줌)가 **바로** 붙는다(`59e5253`). 그래서 "눌러서 지도" 가 아니라 탭만 바꿔도 지도가 뜨고, 세로 스크롤 안에서 핀치·드래그가 스크롤과 겹친다.
- 서버 후보 응답은 도보·자전거에만 `path`(≤600점)와 `path_distance_m` 을 준다. 대중교통·자동차 후보에는 없다.

### A1-2. 결정: 전체 화면 미리보기 (같은 경로 안에서 겹쳐 그림)

Figma ⑬-c·⑬-d 에는 카드 안 지도가 없고, 전체 지도 화면은 ⑰ 지도에서 고르기가 기준이다. PM 지시도 "눌렀을 때 지도 화면으로" 다. 그래서 **카드를 누르면 전체 화면 지도**로 간다(새 Figma 프레임 ⑬-e).

새 `AppRoute` 를 만들지 않는다. 미리보기에서 "이 경로로 계산" 을 누르면 일정 추가로 바로 돌아가야 하는데, 경로를 하나 더 쌓으면 pop 을 두 번 해야 한다. `RoutineHost` 의 블록 편집 폼과 같은 방식(같은 경로 안에서 바꿔 그림 + `BackHandler` 가 먼저 닫음)을 쓴다.

- 상태: `HomeViewModel.RouteState` 에 `previewKey: String? = null` 추가.
- `HomeViewModel`:
  - `openRoutePreview(key)` = `onRouteSelected(key)` 후 `previewKey = key`.
  - `closeRoutePreview()` = `previewKey = null`.
  - `confirmRoute()` 첫 줄에서 `previewKey = null`(후보 캐시는 재진입용으로 남으므로 열린 채 남으면 안 됨). `loadCandidates` 성공 시 `previewKey` 의 후보가 새 목록에 없으면 null.
- `MainActivity` `BackHandler`: 조건에 `routePreviewOpen = nav.current == AppRoute.RouteChoice && routeState?.previewKey != null` 추가, 우선순위 `draftOpen → routePreviewOpen(closeRoutePreview) → goBack`.
- `RouteChoiceScreen`: 새 파라미터 `onPreview: (String) -> Unit`, `onClosePreview: () -> Unit`. `state.previewKey` 의 후보가 있으면 목록 대신 `RoutePreviewScreen` 을 그리고, 없으면 목록.

### A1-3. 목록 카드 변경

- 판정은 순수 함수 `RouteOption.canPreviewMap: Boolean = (travelMode == WALK || travelMode == BICYCLE) && routePath.size >= 2`.
- `canPreviewMap` 카드: 카드 전체 클릭 = `onPreview(key)`. 카드 맨 아래에 오른쪽 정렬 "지도로 보기 ›"(Accent, 12sp Bold, `testTag("route_preview_<key>")`).
- 좌표가 없는 도보·자전거 카드(구버전 서버, 빈 path): 클릭은 선택만. 선택된 경우 카드 아래 "지도 미리보기를 받지 못했음"(TextSecondary 10sp).
- 목록 안의 `CandidateRouteMapCard` 호출은 **삭제**한다(지도는 미리보기에서만).
- 대중교통·자동차 카드는 그대로(누르면 선택, 대중교통은 타임라인 펼침).

### A1-4. 미리보기 화면 `ui/events/RoutePreviewScreen.kt` (신규)

위에서 아래로, 화면 패딩은 다른 화면과 같음(`JitSpace`):
1. `ScreenHeader(title = "경로 지도", onBack = onClosePreview)`.
2. 제목 행: `option.minutesLabel` 24sp Bold + 수단 `option.mode` 13sp TextSecondary, 오른쪽에 배지(`option.badges`, 목록과 같은 칩).
3. 부제: "출발지 → 도착지"(목록 화면과 같은 값) 13sp TextSecondary.
4. 지도: `CandidateRouteMapCard(path, loadMap, mapHeight = null)` 가 남은 높이를 채운다(`Modifier.weight(1f)`).
5. 요약: 도보는 `option.detailLine`(서버 문구, 예 "1.2km · 1,600걸음 · 60kcal (거리 기반 추정)"), 자전거는 목록과 같은 `MetricCells`(거리·자전거도로).
6. `JitPrimaryButton("이 경로로 계산", testTag "route_preview_confirm")` = `onSelect(key)` → `onConfirm()`.

지도 부품 변경:
- `RouteMapCard` 에 `mapHeight: Dp? = MAP_HEIGHT`(null = `Modifier.weight(1f)`, 호출 쪽이 높이를 제한)와 `showCaption: Boolean = true` 추가. 미리보기는 `showCaption = false`.
- `CandidateRouteMapCard` 에 `mapHeight: Dp? = 260.dp` 를 받아 그대로 넘긴다. 처음 맞춤(`RouteMapProjection.fit(path, 360, 260)`)은 그대로 두고, 실제 크기는 기존 `onViewport` 가 다시 맞춘다.

격리(요구사항): 미리보기 상태는 `CandidateRouteMapCard` 의 `remember` 뿐이다. `_routeMap`, `TripLiveState`, `LiveRouteStore`, `TripTrackingService` 를 읽거나 쓰지 않고, `CurrentLocation` 을 부르지 않는다(`showCurrentLocation = false`, `eventId = 0`). 화면을 닫으면 버려진다.

오류: 지도 이미지 실패(네트워크, 503 `map_daily_budget_exhausted`/`map_unavailable`)는 지금처럼 지도 자리에 서버 메시지를 띄우고 "경로 전체 보기" 로 다시 시도한다. 복구 가능 오류이며 "이 경로로 계산" 은 막지 않는다(경로 선택에 지도는 필요 없음). 로그는 기존 `EventRepository.guard` 의 `Log.w`.

### A1-5. 수단별 색 공용 컴포넌트

색 규칙은 이미 `ui/events/SegmentPalette.kt` 한 곳에 있다(ARGB Long, 기기 없이 테스트됨). 그리는 부품이 `RouteChoiceScreen.kt` 안에 `private` 으로 흩어져 있어 다른 화면이 쓸 수 없다. 새 파일 `ui/events/TransitVisuals.kt` 로 옮긴다(패키지 그대로라 `SegmentBarWeightsTest` 수정 없음):

- `internal fun TransitSegmentBar(segments: RouteSegments, modifier)` ← 지금 `SegmentBar`.
- `internal fun TransitLineChip(segment)` ← 지금 `LineChip`.
- `internal fun TransitLineChips(primary: RouteSegment, max: Int = 4)` ← `TimelineRowView` 안의 "대표 + 대체 노선 칩, 넘치면 +N" 코드.
- `barSegments`, `labeledWeights`, `MAX_LINE_CHIPS` 도 이 파일로.

색 표(값은 지금 코드 그대로, 바꾸지 않음):

| 구간 | 규칙 | 색 |
| --- | --- | --- |
| 도보 | 카카오맵처럼 회색 | `#48526A` |
| 대기 | 막대에서 뺌(`barSegments`) | `#39415A` |
| 버스 | `vehicle_type` 정확히 일치 → 포함 검사("지선버스"→지선, "마을버스"→마을) | 간선 `#3D5BAB` · 지선 `#53B332` · 마을 `#7BC855` · 광역/직행좌석/좌석/급행 `#E60012` · 순환 `#F99D1C` · 심야 `#2A3A7A` · 공항 `#AA9872` |
| 버스(모르는 종류) | "일반"·"농어촌" 등 표에 없음 | 중립 `#6B7793` |
| 지하철 | `region` 별 노선색표(서울·부산·대구·대전·광주). 권역이 없으면 추측하지 않음 | 노선색 / 없으면 `#6B7793` |
| 자동차 · 자전거 | 단일 구간 | `#E0823C` · `#3FA37A` |

변경은 하나다. **글자색을 대비로 고른다**: `onFill` 이 흰색(`ON_VEHICLE`)과 어두운 글자(`JitColor.Bg` `#0E1320`, 새 상수 `ON_LIGHT_FILL`) 중 WCAG 대비가 큰 쪽을 돌려준다. 순환(`#F99D1C`)·마을(`#7BC855`) 같은 밝은 칸의 9sp 흰 글자가 읽히지 않기 때문이다. 도보·대기는 지금처럼 `ON_NEUTRAL`. 새 버스 종류 문자열은 실제 응답에서 확인한 것만 추가한다(픽스처는 실측에서 옮긴다는 팀 규칙).

## A2. 백엔드 커밋(8a04736 이후) 프론트 후속

### A2-1. 커밋별 응답 변화와 앱 처리

`git log 8a04736..HEAD` 와 각 diff 를 확인했다.

| 커밋 (task.md) | 새로 생기거나 바뀐 것 | 앱에서 할 일 |
| --- | --- | --- |
| `710545a` BE-19 | `POST /api/events`, `PATCH /api/events/{id}`(장소·출발지 변경 시), `GET /api/routes/candidates` 가 직선 50m 미만이면 **400** `{"error":{"code":"invalid","message":"출발지와 도착지는 50m 이상 떨어져 있어야 한다.","details":{"destination":["…"]}}}` | **새로 처리**(A2-2). 지금은 일정 추가 화면 맨 아래 공통 줄, 경로 선택에서는 "다시 시도" 블록에 뜸 |
| `f6f1630` BE-9·10 | 대중교통 후보 `badges`(["최단 시간","최소 비용","최소 환승"] 중 해당분, 없으면 `[]`), `reason` = 첫 배지 또는 ""; 도보·자전거·자동차 `reason` 은 "가장 빠름"/"가장 저렴"/"걸어갈 수 있는 거리". 버스·지하철 구간 `alt_vehicles: [{name, vehicle_type}]`(없으면 `[]`). `detail` 에 "(대체 N개)" | 매핑은 이미 됨(`toOption`, `toSegment`, E2E `RouteDisplayTest`). 칩은 A1-5 `TransitLineChips` 로 그림. `badges: []` 면 `reason` 으로, 그것도 ""면 배지 없음. `detail` 문구는 자르지 않음 |
| `65899dd` | "(대체 N개)" 숫자를 `alt_vehicles` 와 같은 기준으로 셈 | 없음 |
| `4b699d7` BE-15 | 일정 목록·상세 `lateness_sensitivity` = "high"(τ≥0.95) / "normal"(≥0.85) / "low" | 이미 연결: "high" 일 때만 ④ 요약 카드에 "지각 민감도 높음" 칩. normal·low·null·모르는 값은 칩 없음 |
| `3cd1a1f` BE-13 | 도보 후보 `steps`·`kcal`(정수 또는 null), 잘못된 `distance_m` 은 null. `summary` 끝에 " · 1,600걸음 · 60kcal (거리 기반 추정)"(1.2km 기준: 보폭 0.75m, 1km당 50kcal). **알람 계획 `route_summary` 에도 같은 꼬리가 붙음**(`resolve_route`/`best_route` 가 같은 함수를 씀) | 서버 문구로 표시: 도보 카드 요약 줄, 미리보기 요약. DTO 에 `steps`/`kcal` 을 추가하지 않음(앱이 문장을 다시 만들지 않음). 홈·④ 의 계획 요약에서 빼는 것은 서버 요청 R-5 |
| `59e5253` BE-23 | 도보·자전거 후보 `path`(`[[lat,lng],…]`, ≤600점), `path_distance_m`. 대중교통·자동차에는 없음 | `path` 는 이미 `routePath`. `path_distance_m` 은 쓰지 않음. 화면은 A1 |
| `4e51e88` | 캘린더 가져오기 응답의 프로필 캐시 재사용(모양 변화 없음) | 없음 |

구버전 서버 대비: 위 필드는 DTO 에 이미 nullable·기본값으로 있다(`badges`, `altVehicles`, `path`, `latenessSensitivity`, `isFirstAlarm`). 이번에 추가하는 DTO 필드는 없다.

### A2-2. 가까운 출발·도착(400) 처리

규칙(50m)은 서버가 소유한다. 앱은 미리 검사하지 않고 숫자를 하드코딩하지 않는다. 서버 메시지를 그대로 보여 준다.

저장소(`data/repository/EventRepository.kt`):
- `Result.Failure(message: String, fields: Map<String, String> = emptyMap())` — 기본값이 있어 기존 호출부는 그대로 컴파일된다.
- `internal fun failureFrom(code: Int, raw: String?): Result.Failure` 를 새로 둔다. `ApiClient.parseErrorMessage(raw)` + `ApiClient.parseErrorFields(raw)`, 메시지가 없으면 지금 `errorMessage` 의 코드별 문구. 429 문구 "요청이 너무 잦음. 잠시 뒤 다시 시도" 추가. `errorBody()` 는 **한 번만** 읽는다(`RoutineRepository.failure` 와 같은 이유).
- `createEvent`, `routeCandidates` 의 실패 경로를 `failureFrom(response.code(), response.errorBody()?.string())` 로 바꾼다.

뷰모델·화면:
- `AddState` 에 `originError: String? = null`, `destinationError: String? = null`.
- `submitAdd` 실패: `fields["destination"]` → `destinationError`, `fields["origin_lat"] ?: fields["origin_lng"]` → `originError`. 둘 중 하나라도 있으면 맨 아래 `error` 는 null(같은 말을 두 번 하지 않음), 없으면 지금처럼 `error = message`.
- 두 오류는 출발지·도착지가 바뀌는 모든 경로(`onAddPlaceSelected`, `onAddOriginSelected`, 집 버튼, `useCurrentLocationForAddOrigin`)에서 null 로 지운다.
- `AddEventScreen`: 해당 `EndpointRow` 바로 아래에 빨간 11sp 한 줄(`JitColor.Red`, `testTag("destination_error")`/`"origin_error"`).
- `RouteState` 에 `retryable: Boolean = true`. `loadCandidates` 실패에 `fields["destination"]` 가 있으면 `retryable = false` 로 두고 같은 메시지를 `_add.destinationError` 에도 넣는다. `ErrorBlock` 은 `retryable == false` 면 "눌러서 다시 시도" 대신 "출발지·도착지 다시 고르기"(→ `onBack`). 다시 시도해도 같은 400 이 나오기 때문이다.

연산별 실패 정리:

| 연산 | 실패 | 복구 | 사용자에게 | 로그 |
| --- | --- | --- | --- | --- |
| 일정 생성 | 400 + `details.destination`/`origin_*` | 복구 가능(다른 장소 선택) | 해당 줄 아래 서버 메시지 | 없음(정상 검증) |
| 일정 생성 | 400 기타·5xx·네트워크 | 복구 가능 | 맨 아래 공통 줄(지금과 같음) | `guard` 의 `Log.w`/`Log.e` |
| 경로 후보 | 400 가까움 | 복구 가능(뒤로) | 오류 블록 + "출발지·도착지 다시 고르기" + 도착지 줄 오류 | 없음 |
| 경로 후보 | 409 `no_home`, 400 `invalid_origin`/`invalid_destination`, 429, 5xx, 네트워크 | 복구 가능 | 오류 블록 + "눌러서 다시 시도"(지금과 같음) | `guard` |
| 알람 켬/끔 PATCH | 가까운 기존 일정도 서버가 허용 | — | 변화 없음 | — |

## A3. 불확실한 정보 정리 (P5)

### A3-1. 서버가 실제로 하는 일 (문구의 근거, 코드 확인)

- 확률은 준비·이동 **둘 다** 분산이 있을 때만 나온다(`compute_alarm_math`). 집에서 출발하지 않는 일정은 이동 분산만 본다.
- 이동 분산은 `RouteCorrection` 에서만 온다(`estimate_travel`). 보정은 경로 서명(`route_key`)별 값과 전역 값을 `w = n/(n+5)` 로 섞는다. 경로별 보정은 표본 3건 이상, 표준편차는 2건 이상이어야 생긴다.
- 보정 표본은 **모든 사용자**의 출발·도착 쌍에서 만든다(`update_route_corrections`, "보정 자체는 사용자별로 나누지 않는다"). 그리고 관리 명령 `train_models` 를 사람이 돌려야 갱신된다(스케줄 없음).
- 그래서 "같은 경로를 몇 번 다니면" 은 틀린 말이다. 어느 경로든 이동 기록이 쌓이면(전역 보정) 변동성이 생기고, 같은 경로를 다녀도 학습 배치가 돌기 전에는 안 생긴다. 맞는 표현은 PM 제안대로 **"이동 기록이 쌓이면 변동성이 생김"** 이다(배치 주기는 서버 요청 R-3).
- "이동 시간만 실측값" 상자: 코드에는 이미 없다(`804bb43` 에서 `SourceNote` 삭제, Figma `77:51` 삭제). ⑤ 리스크 선택 화면에도 이 문구는 없다. 남은 흔적은 `EventRepository.kt` 의 주석(~1062행)과 이동 줄 앞의 "실측" 이고, A3-2 에서 함께 고친다. Figma 에 남아 있으면 지운다([figma-changes.md](figma-changes.md) F-6).

### A3-2. "학습 중" 제거와 확률 문구

확률이 null 이면 숫자를 만들지 않는다. 자리별로 정한다.

| 위치 | 지금 | 바꾼 뒤 |
| --- | --- | --- |
| `EventRepository.toConfidence` 제목 | "정시 도착 확률 학습 중" | "정시 도착 확률 · 아직 계산할 수 없음" |
| ④ `AlarmDecisionScreen.ProbabilityRow` | 제목 + 이유 + "→ 할 일" | 구조 유지, 새 문구(아래 표) |
| ③ 홈 `NextAlarmCard` 확률 줄 | "정시 도착 확률 학습 중" | **숨김**. "눌러서 근거 보기" 는 오른쪽 끝 그대로(`Spacer(Modifier.weight(1f))`) |
| ③ 홈 일정 행 오른쪽 아래 | "학습 중" | **빈칸**(텍스트 없음). 계산 불가 일정의 사유 표시는 그대로 |
| ⑤ `RiskOptionCard` | "학습 중" + 0% 막대 | 확률 글자와 `JitProgressBar` 를 **둘 다 숨김**(빈 막대도 숫자처럼 읽힘) |

- `ConfidenceView.isLearning` → `isUnavailable` 로 이름을 바꾼다(사용처 2곳 + 테스트). KDoc·프리뷰의 "학습 중"도 모두 고친다(`AlarmDecisionScreen.kt` 61·674·676, `RiskChoiceScreen.kt` 490, `HomeScreen.kt` 509·556, `AlarmPlan.kt` 226, `EventsApi.kt` 463·489).
- `toConfidence` 를 `internal` 로 연다(`toOption` 과 같은 이유: Gson 을 거친 DTO 로 테스트).

확률이 없을 때 이유·할 일(`confidence_basis` × `travel_time_source`):

| basis | 이유 | 할 일(→) |
| --- | --- | --- |
| `point_estimate` | 준비·이동 둘 다 단일 추정값이라 분포가 없음 | 루틴 블록에 최소~최대 범위를 넣으면 준비 시간 변동이 반영됨 |
| `travel_variance_unknown` | 출처에 `+observed` 가 있으면 "이동 시간은 기록으로 보정됐지만 변동 폭은 아직 모름", 없으면 "이동 시간은 경로 조회값 하나뿐임" | 이동 기록이 쌓이면 변동성이 생김 |
| `prep_variance_unknown` | 이동 시간은 변동 폭이 있지만 준비 시간이 고정값임 | 루틴 블록에 최소~최대 범위를 넣으면 됨 |
| `travel_only` | 준비 단계가 없는 일정이고 이동 시간은 경로 조회값 하나뿐임(보정된 경우 위와 같은 변형) | 이동 기록이 쌓이면 변동성이 생김 |
| 그 밖 | 아직 확률을 계산할 근거가 부족함 | 없음 |

`point_estimate` 의 할 일에서 "확률 계산이 시작됨" 을 뺐다. 준비 범위만으로는 이동 분산이 없어 확률이 나오지 않기 때문이다.

④ 이동 줄의 첫 마디 "실측" 은 카카오 추정치를 측정값처럼 읽게 한다. `AlarmPlanDto.travelTimeSource`(이미 받고 있지만 안 씀)로 바꾼다.
- `+observed_route` 포함 → "카카오 추정 · 이 경로 이동 기록으로 보정"
- `+observed_global` 포함 → "카카오 추정 · 전체 이동 기록으로 보정"
- 그 밖(`kakao_transit` 등, 빈 값) → "카카오 경로 추정"

나머지 조각(출발지, `route_detail`, `route_summary`)은 지금 순서 그대로 뒤에 붙인다.

### A3-3. 고정값·자리표시·오해 소지 점검 (전 화면)

`ui/`, `data/repository/`, `domain/` 의 화면 문자열을 전부 훑었다. 바꾸는 것만 적는다.

| # | 위치 | 문제 | 결정 |
| --- | --- | --- | --- |
| 1 | `RiskChoiceScreen.kt` ~398 `ProgressRow("실제 출발·도착 기록", 0f, "0일 / 14일")` + 아래 "며칠 쓰면 … 계산할 수 있음" | 고정값. 서버에 14일 규칙이 없음 | **삭제**. 쓰는 곳이 없어지는 `ProgressRow` 도 삭제 |
| 2 | 같은 화면 "선택지를 만들려면 관측이 필요함" 카드 본문("τ 를 0.97 로 올려도 0.65 로 내려도 같은 시각이 나옴") | 준비 범위가 있으면 τ 에 따라 시각이 달라지므로 틀림. 실제로 선택지가 없는 이유는 옵션 API(BE-16)가 없어서임 | 카드 교체: 점 라벨 "선택지 준비 중"(Amber), 본문 "안전·보통·도박 중에서 고르는 기능은 서버 계산이 붙으면 열림. 지금은 일정 종류의 τ 로 계산함" |
| 3 | 같은 화면 "관측이 쌓이면" 카드 | 같은 잘못된 전제 | 머리글을 "열리면 고를 수 있는 것" 으로. 맨 아래 "평균이 아니라 분포를 쓰기 때문에…" 줄 삭제. 세 줄(안전·보통·도박 설명)은 유지 |
| 4 | ④ `BreakdownCard` "적용 τ 0.90 · 일정 종류에서 결정됨", ⑤ "{종류} 일정이라 τ 가 이렇게 정해졌음" | 종류가 없으면(캘린더 가져오기, "기타") 프로필 기본값인데도 "일정 종류" 라고 말함 | `AlarmPlanView.tauSourceLabel: String?` 추가. 저장소에서 `EventDto.tauOverride != null` → "직접 고른 값", `tag != null` → "{tag.label} 일정 기본값", 그 밖 → "기본값". ④ "적용 τ 0.90 · {tauSourceLabel}", ⑤ "τ 출처 · {tauSourceLabel}" |
| 5 | ③ `RoutineShortcut` "…근거가 생기고 확률 계산이 시작됨" | 준비만 쪼개서는 확률이 나오지 않음 | "준비 시간을 항목으로 쪼개면 알람 시각의 근거가 보임" |
| 6 | ④ `BlocksEntryCard`(블록 없음) "…범위를 주면 정시 도착 확률이 계산됨" | 같음 | "…범위를 주면 준비 시간 변동이 계산에 반영됨" |
| 7 | `RoutineEditorScreen.kt` ~401 "범위가 확률의 재료임" 카드, ~552 "…확률 계산도 시작됨" | 같음 | 제목 "범위가 변동 폭의 재료임", 본문 "최소와 최대를 다르게 주면 그 폭을 준비 시간 변동으로 계산함. 정시 도착 확률은 이동 기록도 쌓여야 나옴". ~552 는 "쪼개면 왜 그 시각인지 설명됨" |
| 8 | `EventRepository.prepNote` `declared_point` "…범위를 주면 확률이 계산됨", `onboarding` "고정값 · 집 설정에서 답한 값" | 앞은 같은 문제. 뒤는 준비 시간을 ⑮ 에서 묻는데 "집 설정" 이라고 함 | "…범위를 주면 변동 폭이 반영됨", "고정값 · 평소 준비 시간으로 답한 값" |
| 9 | `RoutineRepository.kt` ~253 "범위가 한 점이라 확률 계산에 쓰이지 않음" | 블록 값은 알람 계산에 쓰임 | "범위가 한 점이라 변동 폭이 없음" |

바꾸지 않는 것(사용자 결정 존중): ⑬ 카드의 출발~도착 시각(앱 임시 계산, BE-11 대기, 10-05 결정), 자전거 "자전거도로" 빈 칸(10-05 결정). 주간 리포트·캘리브레이션 값, 설정 값, "안전 버퍼 · 고정값" 은 서버 값이거나 사실이라 그대로 둔다.

### A3-4. 서버가 이미 주는 학습값

| 값 | 지금 | 할 일 |
| --- | --- | --- |
| `on_time_probability` | ③·④ 표시 | 없음(null 처리만 A3-2) |
| `prep_source`, `prep_breakdown[].observation_count` | ④ "관측 N회로 학습됨" | 없음 |
| `travel_time_source` (`+observed_route`/`+observed_global`) | 받지만 안 씀 | ④ 이동 줄에 표시(A3-2) |
| `tau_used`, `lateness_sensitivity` | ④ 표시 | 출처 문구만 #4 |
| 캘리브레이션(`reports/weekly`의 `calibration`, `reports/calibration` 90일) | ⑧ 표시 | 없음 |

서버가 아직 주지 않아 표시할 수 없는 것(보정에 쓴 이동 기록 수, 리스크 옵션·분포, 개인 학습 요약)은 [backend-requests.md](backend-requests.md) R-1·R-2·R-4.

## A4. 준비 시간 — 시계 다이얼 공용 컴포넌트

### A4-1. 지금 상태 (코드 확인)

- ⑮ `PrepOnboardingScreen`: "평소 준비 시간 / 분 단위로 입력" 줄, `−5분`·`+5분` 버튼, 숫자 입력칸, 프리셋 칩 15·30·45·60. 상태는 `PrepOnboardingState.minutes: String`, 유효 범위 `PREP_MIN..PREP_MAX`(5~240, 앱 상수).
- 설정 ⑯ "평소 준비 시간 · 변경" 도 `openPrepOnboarding()` 으로 **같은 화면**을 연다(값을 채워서). 이 값을 고치는 곳은 이 두 입구뿐이다.
- 일정 추가의 시계 다이얼은 `AddEventScreen.kt` 의 `private JitTimePickerDialog`(12시간제).
- 서버 `onboarding_prep_min` 은 `PositiveIntegerField` 이고 범위 검사가 없다. 5~240 은 앱에만 있다 → 서버 요청 R-6.

### A4-2. 공용 부품 `ui/common/JitTimePickerDialog.kt` (신규)

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JitTimePickerDialog(
    title: String,
    initialHour: Int,
    initialMinute: Int,
    is24Hour: Boolean,
    onConfirm: (hour: Int, minute: Int) -> Unit,
    onDismiss: () -> Unit,
    /** 확인 버튼을 켤지. 다이얼을 돌릴 때마다 다시 계산한다 */
    confirmEnabled: (hour: Int, minute: Int) -> Boolean = { _, _ -> true },
    /** 다이얼과 버튼 사이에 넣을 한 줄 */
    supporting: (@Composable (hour: Int, minute: Int) -> Unit)? = null,
)

@Composable
fun JitDurationPickerDialog(
    minutes: Int?, range: IntRange,
    onConfirm: (Int) -> Unit, onDismiss: () -> Unit,
)

/** 35 → "35분", 120 → "2시간", 65 → "1시간 5분" */
internal fun durationLabel(minutes: Int): String
```

- `JitTimePickerDialog` 본문은 지금 `AddEventScreen` 의 것을 그대로 옮긴다(`BasicAlertDialog`, `TimePickerDefaults.colors` 팔레트, 취소/확인). `TimePickerState.hour/minute` 은 스냅숏 상태라 `confirmEnabled`·`supporting` 이 다이얼을 돌리는 즉시 갱신된다. 꺼진 확인 버튼 글자색은 `JitColor.TextSecondary`.
- `JitDurationPickerDialog`: 제목 "준비 시간 · 시간:분", `is24Hour = true`, 처음 값은 `minutes` 가 있으면 `minutes / 60 : minutes % 60`, 없으면 `0:00`. 합계 = `시 × 60 + 분`.
  - 범위 안: "총 {durationLabel(합계)}"(TextSecondary 12sp), 확인 켜짐.
  - 범위 밖: "{durationLabel(range.first)}~{durationLabel(range.last)} 사이로 고름" → "5분~4시간 사이로 고름"(Red 12sp), 확인 꺼짐. 값은 바꾸지 않는다(조용히 잘라 넣지 않음).
  - 값이 없을 때 `0:00` 에서 시작하는 이유: 이 화면의 원칙이 "값을 미리 채우지 않는다" 다(KDoc). 0:00 은 제안값이 아니고, 범위 밖이라 그대로 확인할 수 없다.
- `AddEventScreen`: private 다이얼을 지우고 `JitTimePickerDialog(title = "시작 시각", is24Hour = false, …)` 를 부른다. 동작은 같다.
- `AddEventScreen` 의 `private PickerValue` 도 `ui/common/JitPickerValue.kt` 의 `internal fun JitPickerValue(display, fontSize, enabled, onClick, modifier, onClickLabel: String? = null)` 로 옮긴다. 클릭에 `role = Role.Button` 과 `onClickLabel` 을 준다(접근성).

### A4-3. ⑮ 화면

- **삭제**: `StepButton` 두 개, `JitTextField`, 프리셋 칩 4개, "분 단위로 입력", 파라미터 `onStep` 과 쓰지 않게 되는 import.
- 입력 카드(`JitCard`, gap 9dp): 윗줄 왼쪽 "평소 준비 시간"(TextSecondary 11sp), 오른쪽 "눌러서 선택"(Accent 11sp Bold). 아래 `JitPickerValue(display = state.parsed?.let(::durationLabel) ?: "—", fontSize = 24, testTag "prep_value", onClickLabel = "준비 시간 고르기")`.
- 누르면 `JitDurationPickerDialog(minutes = state.parsed, range = PREP_MIN..PREP_MAX)`, 확인 → `onMinutesChange(합계.toString())`. 열림 여부는 `rememberSaveable`.
- 그대로 둠: 제목 "평소 준비하는데\n얼마나 걸리나요?", 부제 "기상 후 문을 나서기까지 걸리는 시간", 오류 줄, 저장 버튼, "나중에 입력"(=뒤로).
- "기록이 쌓이면 조정됨" 같은 문구는 **넣지 않는다.** 루틴 블록이 없으면 이 값은 학습되지 않는다(학습 재료인 블록 관측이 없음). Figma ⑮ 설명 문구도 고친다(F-8).

### A4-4. 뷰모델·설정

- `onPrepOnboardingStep`, `PREP_SEED` 삭제. `PrepOnboardingState.minutes: String` 은 유지한다(`parsed`·`canSubmit` 과 `PrepOnboardingStateTest` 를 그대로 쓴다).
- `PrepOnboardingState.fromSettings: Boolean = false` 추가, `openPrepOnboarding()` 이 true 로 연다. true 면 버튼 "저장"(아니면 "저장하고 시작하기"), "나중에 입력" 숨김(뒤로 = 저장 안 하고 닫기).
- 설정 행 값과 저장 스낵바도 `durationLabel` 을 쓴다: "1시간 5분", "평소 준비 시간을 1시간 5분으로 저장했습니다"("분"·"간" 모두 받침이 있어 "으로" 고정).

입력 검증:

| 입력 | 필수 | 형 | 범위 | 실패 시 |
| --- | --- | --- | --- | --- |
| 준비 시간 | 저장할 때만(건너뛰기 가능) | 분(Int) | 5~240 (앱 상수, R-6 으로 서버와 맞춤) | 다이얼 확인 꺼짐 + 빨간 안내. 저장 실패(4xx·5xx·네트워크)는 지금처럼 `state.error` 에 서버/공통 문구, 복구 가능 |

경계: 0:04 ✗, 0:05 ✓, 4:00 ✓, 4:01 ✗, 23:00 ✗(24시간 다이얼은 0~23시를 다 보여 준다). 저장 중에는 값 칸을 누를 수 없다.

## 5. 테스트 · 검증

`HomeViewModel` 은 `AndroidViewModel` 이라 JVM 단위 테스트로 만들 수 없다. 판정은 순수 함수로 빼서 단위 테스트하고, 화면 흐름은 계측 테스트로 본다.

단위 테스트(`APP/app/src/test`, JUnit4):
- `RoutePreviewRuleTest` — `canPreviewMap`: 도보·자전거 + 2점 이상 = true, 1점·빈 path = false, 대중교통·자동차 = false.
- `SegmentPaletteTest` 추가분 — `onFill`: 순환·마을 → `ON_LIGHT_FILL`, 간선·서울 2호선 → `ON_VEHICLE`, 도보 → `ON_NEUTRAL`. 기존 색 단언은 그대로 통과해야 한다.
- `ConfidenceCopyTest` — Gson 으로 만든 `AlarmPlanDto`(basis 5종 × 출처 `kakao_transit`/`kakao_transit+observed_global`) → `toConfidence()`. 제목에 "학습" 이 없고, 이유·할 일이 A3-2 표와 같은지.
- `TravelSourceNoteTest` — `internal fun travelSourceNote(source: String?)` 3가지 + null.
- `TauSourceLabelTest` — `internal fun tauSourceLabel(tauOverride: Double?, tagLabel: String?)`: 직접 고른 값 / "수업 일정 기본값" / 기본값.
- `EventFailureFieldsTest` — `failureFrom(400, <710545a 의 실제 오류 본문>)` → `fields["destination"]`, `details` 없음 → 빈 맵, HTML 본문 → 코드별 문구, 429 문구.
- `DurationLabelTest` — 5·35·60·65·120·240.
- `UnreliableCopyAuditTest` — `TripLiveRouteBackgroundContractTest` 처럼 `src/main/java` 소스를 읽어, 주석(`*`, `//`)이 아닌 줄에 `학습 중`, `같은 경로를 몇 번`, `0일 / 14일`, `확률 계산이 시작됨`, `확률 계산도 시작됨` 이 있으면 실패. 문구가 다시 들어오는 것을 막는다.
- `RoutineBlockTest` — `isLearning` → `isUnavailable` 이름과 테스트 이름 수정.
- `NetworkDtoNullSafetyTest` 추가분 — `"badges": []`, 도보 `"steps": null, "kcal": null`, `path` 없는 후보가 터지지 않는지.

계측 테스트(`RouteDisplayTest`, QA 재생 서버 `qa/demo`): 지금 "카드 클릭 → 목록 안 지도 대기" 를 "카드 클릭 → 미리보기의 `candidate_route_map` + `desc("경로 지도")` → 뒤로 → 목록(`route_scroll`) 복귀" 로 바꾼다. 도보는 미리보기에서 `route_preview_confirm` → 일정 추가 화면(`add_event_scroll`)으로 돌아오는지 확인한다. 대중교통 배지·대체 버스 단언은 그대로 둔다.

빌드 확인: `APP` 에서 `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. 다크 테마 다이얼 색과 밝은 칸 글자 대비는 실기기에서 눈으로 확인한다(자동화 범위 밖).

## 6. 구현 순서와 Figma 상태

구현 순서(각 단계가 따로 빌드·테스트 통과):
1. Figma 반영(F-1 ~ F-9) → 팀 확인.
2. A1-5 부품 이동(`TransitVisuals.kt`, 동작 변화 없음) + `onFill` 대비 규칙.
3. A1 미리보기(`previewKey`, `RoutePreviewScreen`, `RouteMapCard`/`CandidateRouteMapCard` 높이 파라미터, `BackHandler`).
4. A2 오류 필드(`Result.Failure.fields`, `failureFrom`, `AddState` 오류 두 개, `RouteState.retryable`).
5. A3 문구·고정값 정리, `travelSourceNote`, `tauSourceLabel`.
6. A4 다이얼 공용화(`JitTimePickerDialog.kt`, `JitPickerValue.kt`), ⑮·⑯ 적용.
7. `task.md` 갱신: 13-c·13-d(10-09) 항목, 15번, BE-19 "앱 상태".

수정·추가 파일:
- 추가: `ui/events/RoutePreviewScreen.kt`, `ui/events/TransitVisuals.kt`, `ui/common/JitTimePickerDialog.kt`, `ui/common/JitPickerValue.kt`, 위 단위 테스트 파일들.
- 수정: `ui/events/RouteChoiceScreen.kt`, `ui/events/CandidateRouteMapCard.kt`, `ui/events/SegmentPalette.kt`, `ui/alarm/RouteMapCard.kt`, `ui/home/HomeViewModel.kt`, `MainActivity.kt`, `data/repository/EventRepository.kt`, `domain/model/RouteOption.kt`, `domain/model/AlarmPlan.kt`, `ui/alarm/AlarmDecisionScreen.kt`, `ui/alarm/RiskChoiceScreen.kt`, `ui/home/HomeScreen.kt`, `ui/routines/RoutineEditorScreen.kt`, `data/repository/RoutineRepository.kt`, `ui/events/AddEventScreen.kt`, `ui/events/PrepOnboardingScreen.kt`, `ui/settings/SettingsScreen.kt`, `data/remote/EventsApi.kt`(KDoc만), `androidTest/.../RouteDisplayTest.kt`, `test/.../RoutineBlockTest.kt`, `test/.../SegmentPaletteTest.kt`, `test/.../NetworkDtoNullSafetyTest.kt`.
- 서버(`backend/`)는 수정하지 않는다.

**Figma 상태: MCP 로 편집하지 못함.** 이 환경에 Figma MCP 도구가 없다. 프레임별 변경 명세(F-1 ~ F-9)를 [figma-changes.md](figma-changes.md) 에 적었다. 서버가 아직 주지 않는 값은 [backend-requests.md](backend-requests.md)(R-1 ~ R-7).

## Review resolutions (2026-10-13)

[phaseA-design-review.md](phaseA-design-review.md)(CHANGES_REQUESTED, HIGH 2 · MEDIUM 9 · NIT 11)를 이 설계의 **구속력 있는 수정**으로 보고 구현에 반영했다. 위 본문과 다르면 이 절이 우선한다. 제품 선택이 필요한 곳은 **지금 사용자가 보는 동작을 유지**했다.

| # | 등급 | 결정 · 구현 |
| --- | --- | --- |
| 1 | HIGH | **권장안 C.** `onFill` 을 바꾸지 않는다(글자색 지금 그대로, `ON_LIGHT_FILL` 없음). A1-5 는 부품 이동만(`TransitVisuals.kt`). `SegmentPaletteTest` 의 기존 기대값 유지, 추가분 없음. figma-changes F-0/F-4 에서 `transit/on-light` 삭제. 대비 개선(리뷰 대비표)은 사용자에게 따로 제안할 일로 남김 |
| 2 | HIGH | `PublicSetupTest`: `prep_value` 대기, `pickDuration`(24시간 다이얼 시 → 분 드래그 → "총 N분" 확인 → 확인), 설정 진입 시 "나중에 입력" 없음 단언 + `pressBack` 으로 취소 경로, 도보는 미리보기가 열리면 `route_preview_confirm`, 아니면 `route_confirm`(재생 모드는 미리보기·걸음·"거리 기반 추정" 단언). `RouteDisplayTest`: 자전거 미리보기 → 뒤로 → 대중교통 배지·대체 버스 → **마지막에** 도보 미리보기 → `route_preview_confirm` → `add_event_scroll` + "도보". `qa/demo/README.md` 갱신. 빌드 확인에 `assembleDebugAndroidTest` 추가. `qa/demo` 시나리오·`run.py` 셀렉터는 바뀐 화면 태그에 기대지 않아 수정 없음 |
| 3 | MEDIUM | `RouteChoiceSelectionStyleTest`: RouteCard 는 다음 최상위 함수까지 자르고, 막대 라벨 검사는 `TransitVisuals.kt` 의 `TransitSegmentBar`~`barSegments` 구간을 읽는다 |
| 4 | MEDIUM | 리뷰 시그니처대로 구현. `RoutePreviewScreen(option, originLabel, destination, loadMap, onClose, onConfirm, modifier)` — 스크롤 없는 루트. `CandidateRouteMapCard(path, loadMap, modifier, mapHeight = null, showCaption = false)`, `RouteMapCard(mapHeight: Dp? = 260.dp, showCaption = true)`. `MetricCells`·`RouteBadges`·`bicycleMetrics` 를 `TransitVisuals.kt` 의 internal 로. 진입 조건 `canPreviewMap && loadMap != null`. `onPreview`/`onClosePreview` 기본값 `{}` |
| 5 | MEDIUM | ⑤-a 본문 "…서버 계산이 붙으면 열림. 지금은 위에 적힌 τ 로 계산함"(코드·F-6) |
| 6 | MEDIUM | `RoutineEditorScreen.EmptyCard` "항목이 없으면 평소 준비 시간 하나를 한 덩어리로 씀. 쪼개면 왜 그 시각인지 설명됨". `AlarmActivity` 추적 카드 "…이 기록이 쌓이면 이동 시간 보정에 쓰임". 점검·감사 범위 = `src/main/java` 전체. (선택 항목 "미루면 도착 확률이 그만큼 떨어진다" 는 지금 문구 유지) |
| 7 | MEDIUM | 아래 10칸 표로 확정. `travel_only` + 보정 = (b) |
| 8 | MEDIUM | `failureFrom` 이 5xx·429 를 `Log.w(TAG, "HTTP $code")`(본문 제외)로 남긴다. `createEvent`·`routeCandidates`·`staticMap`(503 지도 예산·불가 포함) 실패 경로가 `failureFrom` 을 쓴다. 다른 연산은 기존 `errorMessage`(같은 코드별 문구표 공유, 429 문구 포함) |
| 9 | MEDIUM | `ui/home/RouteChoiceRules.kt`: `EndpointErrors`, `endpointErrorsOf`, `candidatesRetryable`, `previewKeyAfterReload`. `EndpointErrorsTest`(710545a 실제 본문 포함) |
| 10 | MEDIUM | `PrepOnboardingState.range: IntRange = PREP_MIN..PREP_MAX`, `parsed` 와 화면이 같이 쓴다 |
| 11 | MEDIUM | Figma 반영(F-0~F-9)은 메인 세션이 따로 한다. 코드는 figma-changes.md 기준으로 바로 구현했다. 최종 보고에 "Figma 미반영(이 워크플로우 밖)" 을 적는다 |

확률이 없을 때 문구(#7, `ConfidenceCopyTest` 가 그대로 단언). 제목은 모두 "정시 도착 확률 · 아직 계산할 수 없음". `kakao_transit` = 보정 없음, `kakao_transit+observed_global` = 보정됨(`+observed_route` 도 같음).

| basis | 출처 | 이유 | 할 일(→) |
| --- | --- | --- | --- |
| `point_estimate` | 둘 다 | 준비·이동 둘 다 단일 추정값이라 분포가 없음 | 루틴 블록에 최소~최대 범위를 넣으면 준비 시간 변동이 반영됨 |
| `travel_variance_unknown` | 보정 없음 | 이동 시간은 경로 조회값 하나뿐임 | 이동 기록이 쌓이면 변동성이 생김 |
| `travel_variance_unknown` | 보정됨 | 이동 시간은 기록으로 보정됐지만 변동 폭은 아직 모름 | 이동 기록이 쌓이면 변동성이 생김 |
| `prep_variance_unknown` | 둘 다 | 이동 시간은 변동 폭이 있지만 준비 시간이 고정값임 | 루틴 블록에 최소~최대 범위를 넣으면 됨 |
| `travel_only` | 보정 없음 | 준비 단계가 없는 일정이고 이동 시간은 경로 조회값 하나뿐임 | 이동 기록이 쌓이면 변동성이 생김 |
| `travel_only` | 보정됨 | 준비 단계가 없는 일정이고 이동 시간은 기록으로 보정됐지만 변동 폭은 아직 모름 | 이동 기록이 쌓이면 변동성이 생김 |
| 그 밖·없음 | 둘 다 | 아직 확률을 계산할 근거가 부족함 | 없음 |

NIT 반영: #12 `tauSourceLabel: String? = null`(null 이면 ④ 는 τ 값만, ⑤ 는 출처 줄 숨김) · #13 꺼진 "확인" = `JitColor.Track` · #14 범위 안내는 다이얼을 건드리기 전 TextSecondary, 범위 밖으로 움직이면 Red · #15 `failureFrom` 은 `EventRepository.kt` 파일 수준 `internal fun` · #16 `maybeAskOnboarding` 이 준비 시간 온보딩을 넣을 때 상태 초기화 · #17 A1-5 색 표 보충: `Kind.UNKNOWN` → `#6B7793`, 버스는 정확히 일치 먼저, 그다음 포함 검사를 `BUS_TYPES` 선언 순서(간선 → 지선 → 마을 → 광역 → 순환 → 심야 → 공항 → 직행좌석 → 좌석 → 급행)로 한다("지선순환" → 지선) · #18 남은 KDoc·프리뷰 문구 정리, ⑤ 카드의 남는 `HorizontalDivider` 삭제, `onboarding` 문구 전체 "고정값 · 평소 준비 시간으로 답한 값. 루틴 블록을 등록하면 항목별로 쪼개짐" · #19 `qa/demo/README.md`·`docs/demo-checklist.md`·`task.md` 갱신, `backend/README.md` 116행은 R-3 에, `docs/instruction.md` 는 메인 worktree 경로로 표기 · #20 미리보기 카드 `onClickLabel = "지도로 보기"`, `JitDurationPickerDialog(title = "준비 시간 · 시간:분")` 파라미터 · #21 `submitAdd` 시작 때 줄 오류 두 개도 지움.

유지한 것(사용자 확인 필요):
- #1 밝은 칸 글자 대비: 지금 흰 글자 그대로. 바꾸려면 리뷰 대비표(안 A: 흰 글자 대비 < 3.0 일 때만 어두운 글자)를 보고 정해야 한다.
- #22 ⑬ 카드의 출발~도착 시각: 앱 임시 계산 그대로 둔다. 근거는 task.md 13-b("⚠️ 시각은 앱 임시 계산")·B-4 `arrive_by` 대기(설계 본문의 "10-05 결정" 은 task.md 에서 확인되지 않아 이 근거로 고침).
- 홈 일정 행: 확률이 없으면 빈 줄을 남긴다(텍스트 없음, 스위치 위치가 행마다 달라지지 않게).
