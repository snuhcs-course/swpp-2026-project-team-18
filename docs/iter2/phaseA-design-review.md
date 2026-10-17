# Phase A 설계 리뷰

- 대상: `docs/iter2/phaseA-design.md` (참고 `figma-changes.md`, `backend-requests.md`). 기준 `front-iter2` HEAD `b1eff1f`.
- 방법: 설계의 주장을 앱(`APP/app/src/**`)·서버(`backend/**`)·QA(`qa/demo/**`) 소스와 하나씩 대조했다. 지시대로 빌드·테스트는 돌리지 않았다. 색 대비는 WCAG 2.x 상대 휘도 식으로 계산했다.
- 판정: **CHANGES_REQUESTED** — HIGH 2 · MEDIUM 9 · NIT 11.

설계 방향(⑬ 전체 화면 미리보기, 50m 오류를 해당 줄 아래에, "학습 중" 제거, 준비 시간 시계 다이얼)은 PM 지시와 맞는다. 서버 동작에 대한 주장(50m 오류 모양, 확률이 나오는 조건, 보정 구조, τ 출처, 배지·대체 버스·걸음 수 꼬리)도 코드와 일치한다. 막는 것은 두 가지다. `onFill` 대비 규칙이 자기 테스트와 모순되면서 설명보다 훨씬 많은 칩 색을 바꾼다는 점, 그리고 A1·A4 가 QA 파이프라인의 `PublicSetupTest` 를 깨뜨리는데 계획에 없다는 점이다. 나머지 MEDIUM 은 구현 전에 계약·문구·테스트를 한 줄씩 확정하면 풀린다.

---

## 지적 사항

### 1. [HIGH] A1-5 `onFill` 대비 규칙이 자기 테스트와 모순되고, 바뀌는 범위가 설명보다 훨씬 넓음

- 위치: A1-5 "변경은 하나다. 글자색을 대비로 고른다", §5 `SegmentPaletteTest 추가분`, figma-changes F-0 `transit/on-light`, F-4.
- 문제: 규칙은 "흰색과 `#0E1320` 중 WCAG 대비가 큰 쪽"이다. 그런데 테스트는 "간선·서울 2호선 → `ON_VEHICLE`"을 기대한다. 2호선 `#00A84D` 는 흰 글자 3.13:1, 어두운 글자 5.92:1 이라서 규칙대로면 `ON_LIGHT_FILL` 이 나온다. 규칙과 테스트를 동시에 만족할 수 없다. 이 규칙은 "순환·마을 같은 밝은 칸"만이 아니라 지선(가장 흔한 시내버스)과 서울 2·3·4·5·9호선, 공항, 자동차, 자전거의 글자색도 바꾼다. 막대·칩 대부분의 모양이 바뀌는데 설계는 이 사실을 밝히지 않았다. 1차 반복 때 사용자는 "더 바꿀 건 결과 보고 내가 정할게"라고 했다.

  | 칸 | 색 | 흰 글자 | 어두운 글자 | "큰 쪽" 규칙 결과 |
  | --- | --- | --- | --- | --- |
  | 간선 | `#3D5BAB` | 6.40 | 2.90 | 흰 |
  | 지선 | `#53B332` | 2.67 | 6.94 | 어두움 |
  | 마을 | `#7BC855` | 2.05 | 9.04 | 어두움 |
  | 광역·좌석·급행 | `#E60012` | 4.80 | 3.86 | 흰 |
  | 순환 | `#F99D1C` | 2.13 | 8.71 | 어두움 |
  | 심야 | `#2A3A7A` | 10.58 | 1.75 | 흰 |
  | 공항 | `#AA9872` | 2.82 | 6.57 | 어두움 |
  | 서울 1호선 | `#0052A4` | 7.66 | 2.42 | 흰 |
  | **서울 2호선** | `#00A84D` | 3.13 | 5.92 | **어두움 (테스트 기대와 반대)** |
  | 서울 3호선 | `#EF7C1C` | 2.77 | 6.68 | 어두움 |
  | 서울 4호선 | `#00A5DE` | 2.82 | 6.57 | 어두움 |
  | 서울 5호선 | `#996CAC` | 4.12 | 4.50 | 어두움 |
  | 서울 9호선 | `#BB8336` | 3.27 | 5.66 | 어두움 |
  | 자동차 | `#E0823C` | 2.83 | 6.56 | 어두움 |
  | 자전거 | `#3FA37A` | 3.12 | 5.94 | 어두움 |
  | 중립 | `#6B7793` | 4.48 | 4.14 | 흰 |

- 고침: 아래 중 하나를 골라 A1-5, F-0/F-4, §5 에 같은 규칙과 기대값을 적는다.
  - **(권장) C. Phase A 에서는 `onFill` 을 바꾸지 않는다.** A1-5 는 부품 이동만 한다(동작 변화 없음). 대비 개선은 위 표를 붙여 사용자에게 따로 제안한다. §6 2단계는 "부품 이동"만 남기고, `ON_LIGHT_FILL`·`transit/on-light` 는 뺀다.
  - A. 문턱값 규칙: 흰 글자 대비가 3.0:1 미만일 때만 어두운 글자를 쓴다. 이 경우 테스트 기대값("간선·서울 2호선 → 흰")이 성립한다.
    ```kotlin
    fun onFill(segment: RouteSegment): Long = when (segment.kind) {
        RouteSegment.Kind.WALK, RouteSegment.Kind.WAIT -> ON_NEUTRAL
        else -> if (contrastRatio(fill(segment), ON_VEHICLE) >= 3.0) ON_VEHICLE else ON_LIGHT_FILL
    }
    /** WCAG 2.x 대비. 색은 ARGB Long 그대로 받는다(기기 없이 테스트). */
    internal fun contrastRatio(a: Long, b: Long): Double
    ```
    기대값: 지선·마을·순환·공항·서울 3·4호선·경의중앙·수인분당·자동차 → `ON_LIGHT_FILL`. 간선·광역·심야·서울 1·2·5·9호선·자전거·중립 → `ON_VEHICLE`. 테스트는 "순환·마을·지선 → `ON_LIGHT_FILL`, 간선·서울 2호선 → `ON_VEHICLE`, 도보 → `ON_NEUTRAL`"로 쓴다.
  - B. 지금 문구대로 "큰 쪽" 규칙을 쓴다. 테스트의 "서울 2호선"을 "서울 1호선"으로 바꾸고, 위 표의 어두움 칸 목록 전체를 A1-5 와 F-0 에 적는다.
  - A·B 는 지선·지하철 칩 모양이 바뀌므로 사용자 확인을 받은 뒤 진행한다.

### 2. [HIGH] 기존 E2E(`PublicSetupTest`·`RouteDisplayTest`)가 A1·A4 로 깨지는데 계획에 없음

- 위치: §5 계측 테스트, §6 수정 파일 목록, §5 빌드 확인 명령.
- 문제:
  - `qa/demo/run.py` 는 모든 모드에서 `PublicSetupTest` 를 먼저 실행하고, 재생 모드면 이어서 `RouteDisplayTest` 를 실행한다(run.py 399-405, `qa/demo/README.md` 68행 "준비 데이터 생성·알람 스위치는 모든 모드에서 PublicSetupTest.kt 가 조작한다"). `PublicSetupTest` 는 다음에 의존한다.
    - step 02/03: `prep_minutes` 입력칸을 6곳에서 쓴다(대기·빈 값 확인·입력·값 확인). A4-3 이 이 칸을 삭제한다.
    - step 03: 설정에서 연 화면의 "나중에 입력"을 누른다. A4-4 가 설정 진입 시 이 버튼을 숨긴다.
    - step 05: 도보 카드를 누른 뒤 목록 안 `candidate_route_map` 을 확인하고 `route_confirm` 을 누른다(`b1eff1f` 에서 추가). A1 에서는 카드를 누르면 미리보기가 열리고, 미리보기에는 `route_confirm` 이 없다. 공용 서버 모드도 같다(좌표가 오면 미리보기가 열림).
    - 결과: step 02 에서 90초 대기 후 실패하고, 뒤 단계(루틴·일정·알람 스위치)는 검증되지 않는다.
  - `RouteDisplayTest` 의 새 흐름도 그대로는 돌지 않는다. 자전거→도보 반복 안에서 도보가 `route_preview_confirm` 으로 일정 추가 화면에 돌아가면, 다음 줄 `ui.click(By.text("대중교통"), "route_scroll")` 이 실패한다. 경로를 고른 뒤에는 `RouteRow` 문구가 "경로 고르기 ›" 에서 "18분 · 도보 / 변경 ›" 으로 바뀌므로 `By.textContains("경로 고르기")` 로 다시 들어갈 수도 없다(AddEventScreen.kt `RouteRow`).
  - §5 빌드 확인 명령은 androidTest 를 컴파일하지 않아 이 문제가 드러나지 않는다.
- 고침:
  - 수정 파일에 `androidTest/.../PublicSetupTest.kt` 와 `qa/demo/README.md`(30행 RouteDisplayTest 설명)를 추가한다.
  - `PublicSetupTest`:
    - step 02: `find(By.res("prep_value"), timeout = 90_000)`.
    - step 03: 처음 값은 `assertEquals("—", ui.text(By.res("prep_value")))`. 새 도우미 `pickDuration(minutes)` 를 둔다. 순서는 `prep_value` 클릭 → 24시간 다이얼에서 0시 선택(오전/오후 클릭 없음, 접근성 설명은 실기기로 확인) → `pickTime` 과 같은 방식으로 분 드래그 → "확인" → `prep_submit`. 설정에서 다시 열었을 때 값 확인은 `ui.text(By.res("prep_value")) == durationLabel(prep)` 로 한다. "나중에 입력" 대신 `device.pressBack()` 으로 취소 경로를 확인한다(저장하지 않고 닫힘).
    - step 05: `route_walk` 클릭 뒤 미리보기에서 `candidate_route_map`, `desc("경로 지도")`, "걸음", "거리 기반 추정"을 스크롤 인자 없이 확인하고 `route_preview_confirm` 을 누른다. `candidate_route_map` 이 일정 시간 안에 안 보이면(좌표 없는 응답) `route_confirm` 으로 떨어지게 분기한다.
  - `RouteDisplayTest` 순서: 자전거 미리보기 열기 → 뒤로 → 목록 복귀 → 대중교통 배지·대체 버스 단언 → **마지막에** 도보 미리보기 → `route_preview_confirm` → `add_event_scroll` 과 `By.textContains("도보")` 확인.
  - §5 빌드 확인에 `./gradlew :app:assembleDebugAndroidTest` 를 넣는다. 머지 전 `qa/demo/run.py` 재생 모드를 한 번 돌린다.

### 3. [MEDIUM] A1-5 부품 이동이 `RouteChoiceSelectionStyleTest` 를 깨뜨림

- 위치: A1-5 "(패키지 그대로라 `SegmentBarWeightsTest` 수정 없음)", §6 2단계 "동작 변화 없음 … 각 단계가 따로 빌드·테스트 통과", 수정 파일 목록.
- 문제: `test/.../ui/events/RouteChoiceSelectionStyleTest.kt` 는 `RouteChoiceScreen.kt` 소스를 읽고 `"private fun RouteCard"` 부터 `"private fun SegmentBar"` 까지 잘라 검사한다. `SegmentBar` 가 `TransitVisuals.kt` 로 옮겨지면 테스트 4개 중 3개가 실패한다. 1번은 `end > start` 단언에서, 3번은 `substring(start, -1)` 예외로, 4번은 `"private fun SegmentBar"` 를 찾지 못해 실패한다. 그러면 2단계가 `testDebugUnitTest` 를 통과하지 못한다.
- 고침: 수정 파일에 이 테스트를 넣고 기준점을 바꾼다.
  ```kotlin
  // RouteCard 구간: 바로 다음 최상위 함수까지
  val start = source.indexOf("private fun RouteCard")
  val end = Regex("\n(private|internal) fun ").find(source, start + 1)?.range?.first ?: source.length
  // 막대 라벨 검사는 새 파일에서
  private val visuals by lazy { readSource("ui/events/TransitVisuals.kt") }
  val bar = visuals.substring(
      visuals.indexOf("internal fun TransitSegmentBar"),
      visuals.indexOf("internal fun barSegments"),
  )
  ```

### 4. [MEDIUM] `RoutePreviewScreen`·`CandidateRouteMapCard` 계약이 정의되지 않음

- 위치: A1-2 마지막 줄, A1-4 4번 "지도", A1-4 "지도 부품 변경", A1-5 이동 목록.
- 문제:
  1. `RoutePreviewScreen` 의 시그니처(무엇을 받고 무엇을 부르는지)가 없다.
  2. "남은 높이를 채운다(`Modifier.weight(1f)`)"가 되려면 세 가지가 필요한데 설계에 없다. (a) 미리보기 루트에 `verticalScroll` 이 없어야 한다. 스크롤 Column 안의 weight 자식은 높이가 0 이 된다. (b) `CandidateRouteMapCard` 가 modifier 를 받아 `RouteMapCard` 에 넘겨야 한다. 지금은 `modifier` 파라미터가 없고 `Modifier.testTag("candidate_route_map")` 을 고정으로 넘긴다. (c) `showCaption = false` 를 미리보기에 주려면 `CandidateRouteMapCard` 도 그 값을 받아야 한다.
  3. 요약의 `MetricCells` 와 제목 행의 배지 칩은 `RouteChoiceScreen.kt` 안의 `private` 함수와 인라인 코드다. 새 파일 `RoutePreviewScreen.kt` 에서 부를 수 없는데, A1-5 이동 목록에 없다.
  4. `RouteChoiceScreen.loadMap` 은 nullable(프리뷰에서 null)인데 `canPreviewMap` 은 이를 보지 않는다. `loadMap == null` 일 때 카드를 누르면 어떻게 되는지 정해져 있지 않다.
- 고침: A1-4 에 다음 계약을 적는다.
  ```kotlin
  @Composable
  internal fun RoutePreviewScreen(
      option: RouteOption,
      originLabel: String,
      destination: String,
      loadMap: suspend (GeoPoint, Int, Int, Int) -> EventRepository.Result<Bitmap>,
      onClose: () -> Unit,
      onConfirm: () -> Unit, // RouteChoiceScreen 이 { onSelect(option.key); onConfirm() } 로 넘김
      modifier: Modifier = Modifier,
  )
  // 루트: Column(fillMaxSize, background(Bg), JitSpace 패딩, spacedBy(Section)). verticalScroll 없음.

  @Composable
  internal fun CandidateRouteMapCard(
      path: List<GeoPoint>,
      loadMap: suspend (GeoPoint, Int, Int, Int) -> EventRepository.Result<Bitmap>,
      modifier: Modifier = Modifier,   // RouteMapCard 에 modifier.testTag("candidate_route_map") 로 넘김
      mapHeight: Dp? = null,           // 목록 호출이 사라지므로 미리보기 값을 기본으로
      showCaption: Boolean = false,
  )
  // 미리보기: CandidateRouteMapCard(path, loadMap, Modifier.weight(1f))
  // RouteMapCard: mapHeight == null 이면 BoxWithConstraints 에 Modifier.fillMaxWidth().weight(1f)
  ```
  - `MetricCells` 는 `TransitVisuals.kt` 로 옮겨 `internal` 로 둔다. 배지 칩은 `internal fun RouteBadges(badges: List<String>)` 로 뽑아 `RouteCard` 와 미리보기가 같이 쓴다.
  - 미리보기 진입 조건은 `option.canPreviewMap && loadMap != null` 이다. 아니면 지금처럼 선택만 한다.
  - `RouteChoiceScreen` 의 새 파라미터 `onPreview`/`onClosePreview` 에는 `{}` 기본값을 둬서 `@Preview` 가 그대로 컴파일되게 한다.

### 5. [MEDIUM] A3-3 #2 의 새 문구가 #4 와 모순됨

- 위치: A3-3 #2 결정 문구, figma-changes F-6 ⑤-a 3번.
- 문제: #4 는 "종류가 없으면(캘린더 가져오기, '기타') 프로필 기본값인데도 '일정 종류'라고 말함"을 고친다. 그런데 #2 의 새 본문 "지금은 일정 종류의 τ 로 계산함"이 같은 오해를 다시 넣는다. 서버 `Event.effective_tau` 는 override → 태그 → 프로필 순으로 정한다(events/models.py 254-264). 직접 고른 τ 나 종류 없는 일정에서는 이 문장이 틀린다.
- 고침: #2 와 F-6 본문을 "안전·보통·도박 중에서 고르는 기능은 서버 계산이 붙으면 열림. 지금은 위에 적힌 τ 로 계산함"으로 바꾼다. τ 출처는 같은 화면 위 카드의 "τ 출처 · {tauSourceLabel}"이 말한다.

### 6. [MEDIUM] A3 점검이 같은 문제의 다른 자리를 놓침

- 위치: A3-3 머리말 "`ui/`, `data/repository/`, `domain/` 의 화면 문자열을 전부 훑었다", #7, #8.
- 문제:
  - `RoutineEditorScreen.kt` `EmptyCard`(551-553행) 첫 문장 "항목이 없으면 **집 설정에서 답한** 준비 시간을 한 덩어리로 씀"은 #8 이 `prepNote` 에서 고치는 것과 같은 잘못이다(준비 시간은 ⑮ 에서 받음). 그런데 #7 은 둘째 문장만 바꾼다. 답하지 않은 사용자는 서버 기본값(`services.py` `FALLBACK_PREP_MINUTES`)을 쓰므로 "답한"도 늘 맞지는 않는다.
  - 점검 범위에서 `alarm/` 이 빠져서 알람 화면 `AlarmActivity.kt`(278-281행)의 "이 기록이 다음 알람을 더 정확하게 만든다."를 보지 못했다. A3-1 이 밝힌 대로 이동 기록은 `train_models` 가 돌아야 보정에 반영된다(R-3). "같은 경로를 몇 번"과 같은 전제를 더 강하게 말하는 문장이다.
- 고침:
  - `EmptyCard`: "항목이 없으면 평소 준비 시간 하나를 한 덩어리로 씀. 쪼개면 왜 그 시각인지 설명됨". 서버 기본값 숫자는 앱에 적지 않는다(규칙은 서버 소유).
  - `AlarmActivity` 추적 카드: "집에서 나가는 시각과 목적지에 닿는 시각을 위치로 판별해 기록함. 이 기록이 쌓이면 이동 시간 보정에 쓰임". 수정 파일에 `alarm/AlarmActivity.kt` 를 넣고, 점검 범위를 `src/main/java` 전체로 적는다(`UnreliableCopyAuditTest` 도 전체를 읽음).
  - (선택) 같은 화면의 "미루면 도착 확률이 그만큼 떨어진다"는 확률이 없는 일정에도 뜬다. "미루면 그만큼 여유가 줄어듦"을 고려한다.

### 7. [MEDIUM] `travel_only` 의 보정 변형 문구가 정해지지 않음

- 위치: A3-2 "확률이 없을 때 이유·할 일" 표 `travel_only` 행, §5 `ConfidenceCopyTest`.
- 문제: "(보정된 경우 위와 같은 변형)"은 두 가지로 읽힌다. (a) 이유 전체를 "이동 시간은 기록으로 보정됐지만 변동 폭은 아직 모름"으로 바꾸거나, (b) "준비 단계가 없는 일정이고 이동 시간은 기록으로 보정됐지만 변동 폭은 아직 모름"으로 쓰는 것이다. `ConfidenceCopyTest` 는 "표와 같은지"를 문자열로 단언하므로 기대값이 하나로 정해져야 한다.
- 고침: 표를 10칸(basis 5종 × 출처 `kakao_transit` / `kakao_transit+observed_global`)으로 펼쳐 문자열을 모두 적는다. `travel_only` + `+observed_*` 는 (b)로 한다. `point_estimate`, `prep_variance_unknown`, 그 밖은 출처와 무관하게 같은 문구라고 명시한다.

### 8. [MEDIUM] "`guard` 가 로그를 남긴다"는 HTTP 오류에서 틀림

- 위치: A1-4 오류 "로그는 기존 `EventRepository.guard` 의 `Log.w`", A2-2 연산별 실패 표의 "로그" 열("`guard` 의 `Log.w`/`Log.e`", "`guard`").
- 문제: `guard` 는 던져진 예외만 기록한다(`IOException` → `Log.w`, 그 밖 → `Log.e`). Retrofit `Response<T>` 의 4xx/5xx/429 는 예외가 아니므로 `Result.Failure(errorMessage(response))` 로 바로 돌아가고 로그가 남지 않는다. 지도 503(`map_daily_budget_exhausted`/`map_unavailable`)도 같다. 표가 약속한 로그가 실제로는 없다.
- 고침: 둘 중 하나를 적는다.
  - `failureFrom` 이 로그를 남기고, `staticMap` 실패 경로도 `failureFrom` 을 쓴다.
    ```kotlin
    internal fun failureFrom(code: Int, raw: String?): EventRepository.Result.Failure {
        val message = ApiClient.parseErrorMessage(raw) ?: fallbackMessage(code)
        // 본문은 남기지 않는다(장소·좌표가 들어 있음). 상태 코드만.
        if (code >= 500 || code == 429) Log.w(TAG, "HTTP $code")
        return EventRepository.Result.Failure(message, ApiClient.parseErrorFields(raw))
    }
    ```
  - 또는 표의 로그 열을 "없음(HTTP 오류는 기록하지 않음. 네트워크 예외만 `guard`)"으로 고친다.

### 9. [MEDIUM] A2-2 의 화면 오류 배치 로직이 테스트되지 않음

- 위치: A2-2 "뷰모델·화면", §5.
- 문제: A2 의 핵심(가까운 출발·도착 400 을 해당 줄 아래에 표시)이 `HomeViewModel`(AndroidViewModel) 안에만 있다. 그래서 단위 테스트가 없고, `RouteDisplayTest` 도 50m 사례를 돌리지 않는다. 필드를 어느 줄에 둘지, 공통 줄을 비울지, `retryable` 판정, 재조회 뒤 `previewKey` 재설정 모두 검증되지 않는다. 설계 자신의 원칙("판정은 순수 함수로 빼서 단위 테스트")이 여기에는 적용되지 않았다.
- 고침: 순수 함수로 빼고 테스트를 추가한다(파일은 `ui/home/RouteChoiceRules.kt` 처럼 따로 둔다).
  ```kotlin
  internal data class EndpointErrors(val origin: String?, val destination: String?, val general: String?)

  internal fun endpointErrorsOf(f: EventRepository.Result.Failure): EndpointErrors {
      val origin = f.fields["origin_lat"] ?: f.fields["origin_lng"]
      val destination = f.fields["destination"]
      return EndpointErrors(origin, destination, f.message.takeIf { origin == null && destination == null })
  }

  internal fun candidatesRetryable(f: EventRepository.Result.Failure): Boolean = f.fields["destination"] == null

  internal fun previewKeyAfterReload(previewKey: String?, options: List<RouteOption>): String? =
      previewKey?.takeIf { key -> options.any { it.key == key } }
  ```
  `EndpointErrorsTest` 에서 destination 만 / origin_lat 만 / 둘 다 / 없음(→ general) / `710545a` 실제 본문을 확인한다.

### 10. [MEDIUM] A4 범위 상수가 `private` 이라 설계대로는 컴파일되지 않음

- 위치: A4-3 "`JitDurationPickerDialog(minutes = state.parsed, range = PREP_MIN..PREP_MAX)`", backend-requests R-6 "`HomeViewModel.PREP_MIN/PREP_MAX`".
- 문제: `PREP_MIN`/`PREP_MAX` 는 `HomeViewModel` 의 `private companion object`(HomeViewModel.kt 2824행) 안에 있다. `ui/events/PrepOnboardingScreen.kt` 에서는 이 식이 컴파일되지 않는다. 어디로 열지 정해져 있지 않다.
- 고침: 범위를 상태에 둔다.
  ```kotlin
  data class PrepOnboardingState(...) {
      /** 다이얼이 받아들이는 범위(분). R-6 으로 서버와 맞출 때까지 앱 상수 */
      val range: IntRange get() = PREP_MIN..PREP_MAX
      val parsed: Int? get() = minutes.toIntOrNull()?.takeIf { it in range }
  }
  // 화면: JitDurationPickerDialog(minutes = state.parsed, range = state.range, …)
  ```

### 11. [MEDIUM] "Figma 반영 → 코드" 순서 때문에 구현 시작 조건이 모호함

- 위치: 머리말 "팀 규칙대로 Figma 반영 → 코드 순서로 진행한다", §6 구현 순서 1, figma-changes "상태: 직접 수정하지 못함".
- 문제: 1단계(F-0~F-9 반영 → 팀 확인)는 이 환경에서 할 수 없는 사람 작업이다(Figma MCP 없음). 그런데 2~7단계가 그 뒤에 묶여 있다. 다음 구현 단계가 기다려야 하는지, `figma-changes.md` 를 기준으로 바로 코드를 짜도 되는지 정해져 있지 않다. 사용자 지시상 Figma 도 작업 범위이므로, 못 한 부분은 사용자에게 보여야 한다.
- 고침: §6 을 다음처럼 바꾼다. "1. (사람 작업, 이 워크플로우 밖) UI 담당이 `figma-changes.md` 대로 F-0~F-9 를 반영한다. 2~7 은 `figma-changes.md` 를 기준으로 바로 진행한다. PR 설명의 '막힌 항목'과 최종 보고에 'Figma 미반영(F-0~F-9)'을 적고, Figma 확인에서 바뀐 값은 후속 PR 로 맞춘다."

### 12. [NIT] `tauSourceLabel` 의 null 의미

- 위치: A3-3 #4.
- 고침: 매핑은 항상 값이 나오므로 `String`(non-null)으로 하거나, `val tauSourceLabel: String? = null`(프리뷰 호환)로 두고 null 이면 ④ 는 "적용 τ 0.90"만, ⑤ 는 줄을 숨긴다고 적는다.

### 13. [NIT] 꺼진 "확인" 색이 "취소"와 같음

- 위치: A4-2 "꺼진 확인 버튼 글자색은 `JitColor.TextSecondary`".
- 고침: `JitColor.Track` 을 쓴다(`RouteRow`·`ModeTabs` 의 비활성 색). F-8 의 "흐림"과도 맞는다.

### 14. [NIT] 값 없이 열면 첫 화면부터 빨간 안내가 뜸

- 위치: A4-2 "값이 없을 때 0:00 에서 시작".
- 고침: 다이얼을 건드리기 전에는 안내를 `TextSecondary` 로, 범위 밖으로 움직였을 때만 `Red` 로 바꾼다.

### 15. [NIT] `failureFrom` 위치

- 위치: A2-2 저장소.
- 고침: `EventRepository.kt` 파일 수준 `internal fun` 이라고 적는다. 클래스 멤버면 테스트가 `EventRepository()` 를 만들다가 `ApiClient` 의 lateinit `tokenStore` 에 걸린다.

### 16. [NIT] 온보딩 진입에서 `fromSettings` 가 남을 수 있음

- 위치: A4-4.
- 고침: `maybeAskOnboarding` 이 `AppRoute.PrepOnboarding` 을 넣을 때 `_prepOnboarding.value = PrepOnboardingState()` 로 초기화한다(`_homeSetup` 과 같은 방식). 설정에서 연 뒤 시스템 뒤로(`goBack` 만 함)로 나가면 `fromSettings = true` 가 남아, 오프라인 첫 진입 뒤의 온보딩에서 "나중에 입력"이 사라질 수 있다.

### 17. [NIT] 색 표에 빠진 규칙

- 위치: A1-5 색 표.
- 고침: `Kind.UNKNOWN → #6B7793` 를 추가한다. 버스 포함 검사 순서(간선 → 지선 → 마을 → 광역 → 순환 → 심야 → 공항 → 직행좌석 → 좌석 → 급행)도 적는다. 키가 둘 들어간 문자열(예: "지선순환")은 순서에 따라 결과가 달라진다.

### 18. [NIT] 함께 고칠 남은 문구

- `EventRepository.kt` `toConfidence` KDoc(1000-1005행, "학습 중"·"같은 경로를 몇 번")
- `RiskChoiceScreen.kt` 프리뷰 action(493행 "확률 계산이 시작됨". 감사 테스트가 잡음)과 KDoc(53-58행, τ 0.97/0.65 설명)
- `RoutineEditorScreen.kt` 프리뷰(633행 "확률 계산에 쓰이지 않음")
- `PrepOnboardingScreen.kt` KDoc "## 이 값은 고정값이 아니다"(A4-3 결정과 반대)
- #3 카드에서 줄을 지운 뒤 남는 `HorizontalDivider`
- #8 `onboarding` 문구가 뒤 문장("루틴 블록을 등록하면 항목별로 쪼개짐")을 남기는지는 전체 문자열로 적는다.

### 19. [NIT] 문서 목록

- 수정 목록에 다음을 넣는다. `qa/demo/README.md` 30행, `docs/demo-checklist.md`(로컬 E2E 행), `task.md`(§6 7단계에는 있으나 목록에 없음).
- `backend/README.md` 117행 "앱은 '학습 중'으로 표시한다"는 R-3 에 함께 적는다.
- figma-changes 의 `docs/instruction.md §14.3` 은 이 worktree 에 없는 파일이다(메인 worktree `SWPP-PJ/docs/` 에만 있음). 경로를 밝힌다.

### 20. [NIT] 접근성·공용 부품

- 미리보기로 가는 카드에 `clickable(onClickLabel = "지도로 보기")` 를 준다.
- `JitDurationPickerDialog` 는 `ui/common` 공용 부품인데 제목 "준비 시간 · 시간:분"이 고정이다. `title` 파라미터(기본값 그 문구)를 둔다.

### 21. [NIT] 제출 시작 시 줄 오류 처리

- 위치: A2-2.
- 고침: `submitAdd` 시작에서 `originError`/`destinationError` 도 지울지 정한다. 지금 코드는 `error` 만 지운다. 권장은 지우는 것이다.

### 22. [NIT] ⑬ 시각을 유지하는 근거

- 위치: A3-3 "바꾸지 않는 것 … ⑬ 카드의 출발~도착 시각(… 10-05 결정)".
- 고침: task.md 에서 "10-05 결정"은 확인되지 않는다. 13-b 항목은 "⚠️ 시각은 앱 임시 계산", BE-11 대기로만 기록돼 있다. 유지해도 되지만 P5 성격의 항목이므로 근거를 task.md 13-b/BE-11 로 고치고, 최종 보고에 "유지(사용자 확인 필요)"로 올린다.

---

## 확인된 가정 (Verified)

- 기준: HEAD `b1eff1f` = `origin/main`. 앱 `versionName 0.10.0`(app/build.gradle.kts), 서버 `APP_VERSION = "0.10.0"`(config/settings/base.py), Compose BOM `2026.09.00`(libs.versions.toml).
- A1-1: `RouteChoice.optionsOf` 는 도보·자전거를 1개만 준다. 탭을 바꾸면 첫 후보가 선택된다. 선택된 도보·자전거 카드 아래에 `CandidateRouteMapCard`(260dp)가 붙는다(`path.size >= 2 && loadMap != null`).
- 서버 후보 `path`: `route_candidates` 가 도보·자전거만 `with_path=True` 로 부르고 자동차는 좌표가 없다. 짧은 거리는 도보만 돌려주고 reason 은 "걸어갈 수 있는 거리"다.
- `RoutineHost` 의 같은 경로 안 겹쳐 그리기와 `BackHandler(draftOpen)` 패턴이 있다. `confirmRoute()` 는 `goBack()` 한다. `openRouteChoice()` 는 캐시된 후보를 재사용한다. 지도 고르기·집 버튼·현재 위치를 포함한 모든 출발·도착 변경은 `onAddPlaceSelected`/`onAddOriginSelected` 를 거치고 `_routeChoice` 를 비운다.
- `RouteMapProjection.fit` 은 빈 경로에서만 null 이다. `bufferedRequestUnits(h, 1024)` 가 높이를 제한하고, 전체 화면 지도에서도 overscan 이 남는다. `CandidateRouteMapCard` 는 `eventId = 0`, `showCurrentLocation = false`, `onRecenter = {}` 로 격리돼 있다.
- A1-5 색 표의 값은 `SegmentPalette` 와 정확히 같다. 지금 `onFill` 은 WALK/WAIT → `ON_NEUTRAL`, 나머지 → `ON_VEHICLE` 이다. 기존 테스트는 "간선 → `ON_VEHICLE`"만 단언한다. `SegmentBarWeightsTest` 는 같은 패키지의 `internal barSegments`/`labeledWeights` 를 쓴다.
- `710545a`: `validate_route_distance` 가 `ValidationError({"destination": [...]})` 를 던진다(일정 생성, 장소·출발지를 바꾸는 PATCH, 경로 후보). `api_exception_handler` 는 `code "invalid"`, 첫 메시지, 문자열화한 `details` 를 준다. `invalid_destination` 400 은 `details: {}` 이고, `origin_lat` "국내 좌표만…"은 문자열 값이다. 앱이 PATCH 하는 것은 `alarm_enabled` 뿐이다.
- `ApiClient.parseErrorMessage`/`parseErrorFields` 가 있다(문자열·배열 처리, 중첩 무시). `RoutineRepository.failure` 는 본문을 한 번만 읽는다. 단위 테스트는 `isReturnDefaultValues = true` 다.
- `f6f1630`·`65899dd`·`4b699d7`·`3cd1a1f`·`59e5253` 의 응답 변화: 배지/reason 규칙, `alt_vehicles` 와 "(대체 N개)" 개수, `lateness_sensitivity` 0.95/0.85(`effective_tau` 기준), 도보 `steps`/`kcal` 꼬리(후보와 `resolve_route`/`best_route` 를 통한 계획 요약 둘 다), 도보·자전거 `path`. DTO 필드는 모두 nullable 에 기본값이 있다.
- A3-1: `compute_alarm_math` 는 이동 분산이 있고 (준비가 없거나 준비 분산도 있을 때)만 확률을 낸다. basis 에 `travel_only` 가 있다. `estimate_travel` 은 `w = n/(n+5)`(`ROUTE_SHRINKAGE_K = 5`)로 섞는다. `MIN_ROUTE_SAMPLES = 3`, 표준편차는 2건 이상에서 나온다. 보정은 사용자를 나누지 않는다. `train_models` 는 수동이다(render.yaml 에 cron 없음). `travel_time_source` = `"<kakao_*>+observed_route|observed_global"` 이고, 경로 source 는 전부 `kakao_*` 다.
- `effective_tau` 는 override → 태그 → 프로필 순이다. 태그 τ 는 시험 0.99, 발표 0.98, 기차·비행 0.99 다(⑤ "시험·발표·기차" 문구가 사실임).
- 화면에 보이는 "학습 중"은 `toConfidence` 제목, 홈 `ProbabilityLabel`·일정 행, `RiskOptionCard` 다. 모두 A3-2 표에 있다. `isLearning` 사용처는 2곳 + `RoutineBlockTest` 다.
- `804bb43` 이 `SourceNote`("이동 시간만 실측값")를 지웠다. 이동 줄의 "실측"과 관련 주석이 남아 있다. `travelTimeSource` 는 받지만 쓰지 않는다.
- ⑤ "0일 / 14일" `ProgressRow` 와 τ 0.97/0.65 문구가 있다. 서버에 14일 규칙은 없다(개인 아티팩트 `MIN_PERSONAL_OBSERVATIONS = 15` 뿐). `MainActivity` 는 `riskOptions` 를 넘기지 않으므로 사용자가 보는 것은 항상 빈 상태다.
- ⑮: ±5 버튼, 숫자칸, 15/30/45/60 칩이 있다. `PREP_MIN`/`PREP_MAX` = 5/240 이다. `PREP_SEED` 는 `onPrepOnboardingStep` 에서만 쓴다. 입구는 `maybeAskOnboarding` 과 설정 `openPrepOnboarding` 두 곳이다. 서버 `onboarding_prep_min` 은 `PositiveIntegerField` 이고 범위 검사가 없다. 블록이 없으면 `estimate_prep` 이 이 값을 sd 0 으로 쓰고 학습하지 않는다. 가입 화면 자체에는 시간 입력이 없다("초기 시간 설정" = ⑮).
- `AddEventScreen` 에 `private JitTimePickerDialog`(12시간제, `BasicAlertDialog`, 색 팔레트)와 `private PickerValue` 가 있다. `HomeViewModel` 은 `AndroidViewModel` 이다. 지도 503 코드 `map_daily_budget_exhausted`/`map_unavailable` 이 있다. `JitColor.Bg = #0E1320` 이다.

## 확인하지 못했거나 틀린 가정 (Unverified / Wrong)

- **틀림** — A1-5 "대비가 큰 쪽" 규칙에서 서울 2호선은 흰 글자가 되지 않는다(#1).
- **틀림** — "부품 이동은 테스트 수정 없음": `RouteChoiceSelectionStyleTest` 가 깨진다(#3).
- **빠짐** — androidTest 영향을 `RouteDisplayTest` 만 봤다. `PublicSetupTest` 와 `qa/demo` 파이프라인이 깨진다(#2).
- **틀림** — HTTP 4xx/5xx/429·지도 503 을 `guard` 가 기록한다는 주장(#8).
- **틀림(컴파일)** — `PREP_MIN..PREP_MAX` 를 화면에서 쓸 수 있다는 전제(#10).
- **틀림** — "전 화면 문자열을 전부 훑었다": `RoutineEditorScreen` `EmptyCard`, `AlarmActivity` 가 빠졌다(#6).
- **확인 못 함** — Figma 노드·프레임(`249:101`, `249:165`, `232:265`, `77:51`, `134:2` 등)과 "⑬-c·⑬-d 에는 카드 안 지도가 없다"는 주장. 이 환경에 Figma 접근이 없다.
- **확인 못 함** — ⑬ 시각 유지의 근거 "10-05 결정"(#22).
- **확인 못 함(기기 필요)** — Material3 24시간 다이얼로 기간을 고르는 사용성과 TalkBack 낭독, 다크 테마 색, 전체 화면 지도의 실제 overscan 체감.
- **확인 못 함** — DRF 429 응답의 메시지 언어. 서버가 메시지를 주면 앱 429 대체 문구는 쓰이지 않는다.
