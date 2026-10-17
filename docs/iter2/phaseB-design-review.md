# Phase B 설계 리뷰 — 리포트(⑧ 주간 · ⑨ 상세)와 주간 줄글 요약

> 이 문서는 해당 Phase의 설계·리뷰·검증 당시 기록이다. 당시 테스트 수·미구현 설명을 현재 상태로 해석하지 않는다. 2026-10-17 후속 통합 상태는 [최신 TODO](../../task.md), [요청별 완료 상태](backend-requests.md), [최신 검증](account-ranges-and-travel-counts.md)을 따른다.

- 대상: `docs/iter2/report-design.md` (참고 `figma-changes.md` Phase B F-10~F-13, `backend-requests.md` R-3·R-4). 기준 `front-iter2` HEAD `5a41095`(설계 문서는 아직 커밋 전).
- 방법: 설계의 주장을 서버(`backend/apps/{reports,planning,events,routing}`, `config/settings`, `scripts`, `Dockerfile`, `pytest.ini`, `render.yaml`)와 앱(`APP/app/src/**`) 소스에 하나씩 대조했다. 예시 날짜의 요일은 직접 계산했다. 지시대로 빌드·테스트는 돌리지 않았다. 메인 체크아웃 `backend/.env` 는 키 이름과 빈 값 여부만 확인했다(모델 이름 외의 값은 읽지 않음).
- 집계: HIGH 0 · MEDIUM 11 · NIT 19. 판정은 `phaseB-design-review.json` 에 적었다.

설계는 구체적이다. 두 화면의 카드 순서·문구·색·testTag, 응답 키, 템플릿 문장 규칙, LLM 실패별 백오프, 저장·해시 규칙, 테스트 목록이 거의 그대로 코드로 옮길 수 있는 수준이다. 핵심 주장(주간 캘리브레이션 결함, `collect_outcomes` 3회 호출, Gson 함정, ⑧ `1:137`/⑨ `16:2`, `ModelArtifact` 미사용, `travel_source` 형식, 설정·배포 파일 현황)도 코드와 맞다. 요청한 점검 항목별로 보면, 개인정보는 `build_facts` 가 고정 키이고 자유 문자열이 없어서 구조적으로 막히며 테스트가 보낸 본문까지 본다. "학습 중" 은 템플릿·앱 문구 어디에도 없다(LLM 검사의 우회는 #9). 키·네트워크 없는 테스트 자세는 빈 키 + autouse 가드로 명시돼 있다(주소 고정 누락은 #10). 구버전 서버는 새 키가 전부 nullable 이고 카드별로 숨긴다(정수 형 규칙의 빈칸은 #11). `summary_source` 흐름은 닫혀 있다(세부는 #26).

막는 것은 세 부류다. (1) 예시·테스트 기대값과 화면 동작의 사실 오류: 예시 날짜의 요일(#1), "스크롤 그대로"(#2). (2) 서버와 앱, 문장과 타일이 서로 다른 숫자를 말하는 경우: 퍼센트 반올림(#3), 1분 안쪽 지각(#4), 기록 적은 주의 LLM(#8), 정수 DTO 형(#11). (3) 실제 기록 방식과 맞지 않는 원인 분류·안내: 준비 해당 없음(#5), 출발 미기록 안내(#6), 알람이 없던 일정(#7). 그 밖에 검사 우회(#9)와 테스트의 `.env` 의존(#10).

---

## 지적 사항

### 1. [MEDIUM] 예시 주의 날짜와 요일이 달력과 맞지 않음. 테스트 기대값이 이 예시에서 나옴

- 위치: §3 `outcomes[]` 예시(`"start_at": "2026-10-07T00:00:00Z"`, `"date": "2026-10-07"`, `"weekday": 1`), §2-1 7 "화 10월 7일", §2-2 1 trailing "10월 7일", §7 `ReportMappingTest`(7칸 "화 −10", 지각한 날 한 줄), figma-changes F-10 5 "화 10월 7일", F-11 "화요일 10월 7일".
- 문제: 예시 주 "10월 5일 ~ 10월 11일" 이 월~일이 되는 해는 2026년이다(2026-10-05 월요일). 그러면 화요일은 10월 6일이고 **2026-10-07 은 수요일**이다. 같은 문서의 F-13 "목요일 10월 8일"·"금요일 10월 9일" 은 맞으므로 화요일 예시만 하루 밀렸다. 앱은 칸을 `weekStart + 0..6` 으로 만들고 `date` 로 묶으므로 이 JSON 을 그대로 쓰면 지각이 수요일 칸(index 2)에 들어간다. "화 −10" 단언이 실패하고 ⑨ 헤더도 "수요일 상세" 가 된다. 응답 안에서도 `weekday: 1`(화)과 `date`(수)가 서로 다른 요일을 말한다.
- 고침: 예시를 2026-10-06(화)로 옮긴다.
  ```json
  {"event": 412, "title": "자료구조", "start_at": "2026-10-06T00:00:00Z",
   "date": "2026-10-06", "weekday": 1, "result": "late", "slack_minutes": -10.0, "...": "...",
   "planned": {"alarm_at": "2026-10-05T22:40:00Z", "depart_by": "2026-10-05T23:08:00Z",
               "arrive_at": "2026-10-05T23:50:00Z", "prep_minutes": 28, "travel_minutes": 42,
               "buffer_minutes": 10},
   "actual": {"depart_at": "2026-10-05T23:15:00Z", "arrive_at": "2026-10-06T00:10:00Z",
              "prep_minutes": 34.0, "travel_minutes": 55.0}}
  ```
  §2-1 7·F-10 5 는 "화 10월 6일", §2-2 1·F-11 은 "10월 6일" 로 고친다. 규칙도 한 줄 더한다: "앱의 요일 글자와 칸 위치는 `date` 로만 정한다. `DAYS.between(weekStart, date)` 가 0..6 밖이면 그 일정은 뺀다. `weekday` 는 서버 facts 용이고 앱은 읽지 않는다." `ReportMappingTest` 에 주 밖 `date` 가 빠지는 경우를 하나 넣는다.

### 2. [MEDIUM] "⑨ → ⑧ 스크롤 그대로" 가 지금 내비게이션 구조에서는 성립하지 않음

- 위치: §2-3 "⑨ ‹ · 시스템 뒤로 → ⑧ (보던 주·스크롤 그대로)", §5-3 `WeeklyReportScreen`.
- 문제: `MainActivity.kt` 344~357행은 `AnimatedContent(targetState = nav, contentKey = { it.current.toString() })` 로 현재 경로 하나만 그린다. ⑨ 로 가면 ⑧ 컴포저블이 컴포지션에서 빠지고, `WeeklyReportScreen` 안의 `rememberScrollState()` 는 `SaveableStateHolder` 가 없어서(파일에 `SaveableStateProvider`·`rememberSaveableStateHolder` 없음) 함께 버려진다. 돌아오면 맨 위에서 다시 시작한다. 보던 주는 ViewModel `_report` 에 있어서 유지되지만 스크롤은 유지되지 않는다. 화면 아래쪽 "지각한 날" 에서 들어갔다 나오면 매번 맨 위로 튄다.
- 고침: ⑧ 스크롤 상태를 `MainHost` 로 올리고 이 방식을 §2-3·§5-3 에 적는다.
  ```kotlin
  // MainHost, AnimatedContent 밖
  val reportScroll = rememberScrollState()
  LaunchedEffect(reportState.weekly?.weekStart) { reportScroll.scrollTo(0) } // 주가 바뀌면 맨 위
  // ...
  AppRoute.WeeklyReport -> WeeklyReportScreen(/* … */, scrollState = reportScroll)
  ```
  `WeeklyReportScreen(…, scrollState: ScrollState = rememberScrollState(), modifier = …)` 로 받고 루트 `Column` 이 `verticalScroll(scrollState)` 를 쓴다. 홈에서 새로 열 때도 맨 위로 보내려면 `openReport()` 가 올리는 토큰(예: `ReportState.openCount`)을 키로 `scrollTo(0)` 한다. 이 방식을 쓰지 않을 거면 §2-3 에서 "스크롤 그대로" 를 지운다.

### 3. [MEDIUM] 퍼센트 반올림 규칙이 없어 요약 문장과 타일 숫자가 어긋날 수 있음

- 위치: §0 반올림 규칙(분만 정의), §4-2 `build_facts`("비율은 % 정수"), §3 `on_time_rate_change_pp`("round(…)"), §2-1 4 타일, 앱 `percent()`(`ReportRepository.kt`, `roundToInt`).
- 문제: 분은 "절댓값을 0.5 올림한 뒤 부호" 로 서버·앱을 맞췄지만 퍼센트·%p 는 규칙이 없다. 서버가 파이썬 `round()` 를 쓰면 은행가 반올림이라 `round(62.5) == 62`, `round(92.5) == 92` 이다. 앱 `roundToInt()` 는 63·93 이다. 도착 8건 중 5건 정시(0.625)인 주, τ 0.90·0.95 일정이 반반이라 목표가 0.925 인 주에서 요약은 "정시율 62%·목표 92%", 타일은 "정시 도착 63%·목표 93%" 가 된다. "AI 요약 · 집계 수치만 사용함" 바로 아래에서 숫자가 다르면 지어낸 숫자로 읽힌다.
- 고침: §0 에 "퍼센트·%p 도 분과 같은 규칙" 을 적고 서버는 함수 하나로 쓴다.
  ```python
  def _half_up(x: float) -> int:  # summary.py — 분·%·%p 공용
      return int(math.copysign(math.floor(abs(x) + 0.5), x))

  on_time_pct = _half_up(on_time_rate * 100)
  target_pct = _half_up(target_rate * 100)
  change_pp = _half_up((this_rate - prev_rate) * 100)
  ```
  앱 `percent()` 는 0..1 범위라 `roundToInt()` 와 결과가 같지만, 같은 규칙임을 테스트로 고정한다. 서버 `test_summary.py` 와 앱 `ReportMappingTest` 양쪽에 `5/8 → 63`, `target_rate 0.925 → 93`, `change_pp −22.5 → −23` 을 넣는다.

### 4. [MEDIUM] 1분 안쪽 지각(−0.5 < 여유 < 0)이 ⑧·요약에서 "0분 늦음"·"정각" 이 됨

- 위치: §2-1 4 오른쪽 타일, §2-1 6 요일 값, §2-1 7 지각한 날 "10분 늦음", §4-2 `late_max_min`·템플릿 2. 비교 대상: §2-2 3 "n = 0 이면 '1분 안쪽 지각'".
- 문제: 서버는 여유 −0.4 를 지각(`result: late`)으로 센다. ⑨ 만 n = 0 을 따로 다루고 나머지는 반올림한 0 을 그대로 쓴다. 그러면 지각한 날 줄 "0분 늦음", 타일 "가장 아슬아슬 정각 도착"(t 를 반올림하면) 또는 "가장 늦은 날 0분 늦음", 요일 칸 값 "0"(빨강), 요약 "화요일에 0분 늦었음." 이 나온다. 같은 화면의 "지각한 날 · 1건" 과 모순된다. GPS 도착 시각은 초 단위로 흔들리므로 드문 경우가 아니다.
- 고침: §0 에 지각 분 규칙을 하나 두고 모든 곳이 그것을 쓴다.
  - 권장: 지각이면 `late_min = max(1, half_up(−slack))`. 서버 `build_facts.late_max_min`·템플릿, 앱 지각 줄·타일("가장 늦은 날 {late_min}분 늦음")·요일 값("−{late_min}")·⑨ 결과 카드가 같은 값을 쓴다. ⑨ 의 "1분 안쪽 지각" 은 "1분 지각" 으로 바꾼다. 정시는 지금대로 n = 0 → "정각"(요일 값 "0").
  - 대안: "1분 안쪽" 문구를 모든 곳에 쓴다(요일 값 "<1", 템플릿 "1분 안쪽으로 늦었음", `allowed_numbers` 에 1 추가).
  - 테스트: 서버·앱 양쪽에 slack −0.4 일정 하나.

### 5. [MEDIUM] 준비가 해당 없는 일정에서 `prep_over = None` 새 규칙이 `unmeasured`·`label` 을 바꿈

- 위치: §4-1 `_measure` 새 규칙, §3 `cause`·`unmeasured`, §2-2 5 원인 표(`unknown` 행, `depart_late` 덧붙임 문장), `services.LatenessCause.unmeasured`/`label`.
- 문제: `LatenessCause.unmeasured` 는 None 인 요인을 전부 "측정 못 함" 으로 보고, 하나라도 있으면 `label` 이 `unknown` 이다. 준비가 해당 없는 일정에서 prep_over 를 None 으로 두면 (1) 출발·이동을 다 재고 초과가 없어도 `plan_too_tight` 가 아니라 `unknown` 이 된다. (2) ⑨ 는 준비 줄을 숨기면서(`prep_applies == false`) 원인 카드에는 "측정 안 됨: 준비 · …" 를 쓰고, `depart_late` 설명에 "준비 항목을 기록하면 …" 을 덧붙인다. (3) 템플릿 facts 의 `late_causes` 도 "원인 측정 못 함" 으로 센다. 이런 일정의 `depart_late` 제목 "집에서 {n}분 늦게 나섰음" 도 사실이 아니다(출발지가 집이 아님).
- 고침: "해당 없음" 과 "측정 못 함" 을 나눈다.
  ```python
  @dataclass
  class LatenessCause:
      ...
      #: 준비 단계가 있는 일정인가. False 면 prep_over 는 '측정 못 함' 이 아니다
      prep_applies: bool = True

      @property
      def unmeasured(self) -> list[str]:
          pairs = [("travel_over", self.travel_over), ("depart_late", self.depart_late)]
          if self.prep_applies:
              pairs.insert(0, ("prep_over", self.prep_over))
          return [name for name, value in pairs if value is None]
  ```
  `_measure` 가 `prep_applies` 를 함께 돌려주고 `lateness_causes` 가 넘긴다(`as_dict` 키는 그대로). `prep_applies == false` 인 `depart_late` 제목은 "출발이 {n}분 늦었음". 테스트: 준비 해당 없는 지각 + 출발·도착 측정 + 초과 없음 → `label == "plan_too_tight"`, `unmeasured == []`.

### 6. [MEDIUM] 원인을 나누지 못한 지각의 안내("위치 권한을 켜 두면 …")가 실제 원인과 다름

- 위치: §2-2 5 `unknown` 행의 할 일, §4-2 제안 g.
- 문제: 지각은 도착 관측이 있어야만 생긴다(`slack_minutes` 는 arrive 관측에서만 나옴). 도착 관측은 `TripTrackingService` 만 만들고, 그 서비스는 `AlarmActivity.kt` 143행에서 위치 권한이 있을 때만 시작한다. 즉 **지각한 일정은 위치 권한이 켜져 있었다.** 출발·이동이 "측정 안 됨" 인 실제 이유는 (a) 첫 위치가 이미 집 밖이라 출발을 기록하지 않은 경우(`TripTrackingService.kt` 327~329행 "첫 위치부터 집 밖이다. 출발 시각은 기록하지 않는다"), (b) 출발지가 집이 아니거나 집 주소가 없어 출발을 판별하지 않는 경우(`AlarmSchedule.canDetectDeparture` = 프로필 집 좌표), (c) 그날 계획이 OK 가 아니어서 비교할 계획 값이 없는 경우다. "위치 권한을 켜 두면 출발·도착이 자동으로 기록됨" 은 셋 모두에 틀린 안내이고, 제안 g "위치 권한을 켜 두고 이동할 것" 도 같다. PM 지시는 안내 문구를 실제 동작에 맞추는 것이다.
- 고침: `unknown` 의 설명·할 일을 경우별로 고정한다. 서버가 이미 주는 값으로 판단한다(앞 조건부터).

  | 조건 | 설명 | 할 일 |
  | --- | --- | --- |
  | `planned.depart_by == null` | 그날은 알람 계획이 없어 계획과 비교하지 못했음 | 집 주소와 일정 장소를 정해 두면 계획이 계산됨 |
  | `prep_applies == false`, `depart_late` 측정 안 됨 | 출발지가 집이 아닌 일정이라 출발 시각을 기록하지 않았음 | 없음 |
  | `depart_late` 측정 안 됨 | 집을 나선 시각이 기록되지 않았음 | 집 안에서 알람을 해제하면 나서는 시각까지 기록됨 |
  | 준비만 측정 안 됨 | 측정 안 됨: 준비 | 준비 항목을 기록하면 준비 시간도 나눠 볼 수 있음 |

  제안 g 는 "원인을 나누려면 출발 시각이 필요하니 집을 나서기 전에 알람을 해제해 둘 것." 으로 바꾸고 템플릿 테스트 기대 문장도 고친다.

### 7. [MEDIUM] 알람이 울리지 않는 일정은 원래 기록되지 않는데, 리포트가 그것을 사용자가 할 일로 안내함

- 위치: §2-1 5 측정 공백 안내, §4-2 제안 b·facts `unobserved`, §2-2 3 공통 줄 "알람 7:40", §2-1 8 기록 반영 "이번 주 알람 {N}개 중", §3 `learning.plan_count`.
- 문제: 출발·도착 기록은 알람을 해제할 때만 시작된다(`TripTrackingService.start` 를 부르는 곳은 `AlarmActivity` 하나). 알람은 `alarm_on` 일정에만 잡힌다(`EventRepository.kt` 1266행 `toSchedule`: `alarmOn != true` 면 null). 서버 `apps/events/alarms.alarm_states` 규칙상 `alarm_enabled` 가 null 이면 **하루 첫 알람만** 켜진다. 하루에 일정이 둘 이상인 학생은 매주 절반 이상이 원래 기록될 수 없다. 그런데 설계는 이것을 "측정하지 못한 아침" 으로 세고, `unobserved × 2 ≥ events` 이면 제안 b("위치 권한을 켜 두고 알람을 해제한 뒤 이동하면 … 기록됨")를 요약 마지막 문장으로 매주 낸다. 이미 그렇게 하는 사용자에게 틀린 안내다. ⑨ 의 "알람 7:40" 도 울리지 않은 알람을 보여 주고, "이번 주 알람 7개 중" 은 계획 수를 알람 수로 부른다. 둘 다 0 일 때의 "기본값으로 계산됨" 도 온보딩·루틴 입력값으로 계산한 경우에는 틀린다.
- 고침: `outcomes[]` 에 `alarm_on: bool | null` 을 더한다. 서버가 `apps.events.alarms.alarm_states(user)` 를 읽기만 하고, 지금 설정 기준이라는 한계를 주석에 적는다. 그리고 다음을 고친다.
  - facts 에 `alarm_events`(알람이 켜진 지난 일정 수)와 `unobserved_with_alarm` 을 더한다. 제안 b 조건은 `arrived == 0 and unobserved_with_alarm > 0` 또는 `unobserved_with_alarm * 2 >= alarm_events`. 알람 없는 일정만 비었으면 b 를 건너뛴다.
  - ⑧ 측정 공백 안내의 u 는 알람이 켜진 일정만 센다. 알람 없는 미기록 일정이 k > 0 이면 캡션 "알람이 울리지 않은 일정 {k}건은 출발·도착을 기록하지 않음" 을 붙인다.
  - ⑨ 공통 줄은 `alarm_on == true` 면 "· 알람 7:40", false 면 "· 알람 꺼짐", null(구버전)이면 지금처럼.
  - 기록 반영 문구: "이번 주 일정 {N}건의 계획 중 {p}건은 내 준비 기록으로, {t}건은 이동 기록 보정으로 계산함". 둘 다 0 이면 "이번 주 계획은 준비·이동 기록 없이 입력한 준비 시간과 경로 예상 시간으로 계산됨".
  - 새 필드가 부담이면 최소안: 제안 b·측정 공백 안내에 "출발·도착은 알람을 해제한 뒤 이동한 일정만 기록됨" 을 쓰고, "알람 N개" 를 "계획 N건" 으로 바꾼다.

### 8. [MEDIUM] 기록 적은 주(도착 1~2건)도 LLM 으로 가는데 프롬프트에 그 규칙이 없음

- 위치: §4-2 `llm_allowed`(`arrived_count >= 1`), 프롬프트 1~7, §2-4 "기록 적음" 행.
- 문제: §2-4 는 기록 적은 주의 요약에 "기록이 적어 비율로 판단하기는 이름" 이 들어간다고 약속하고, 왼쪽 타일 색도 중립이다(`target_met` null). 그런데 이 주는 LLM 이 쓰고 프롬프트에는 `thin_data` 를 어떻게 다룰지 없다. 1/1 주에는 facts 에 `on_time_pct 100`·`target_pct 90` 이 있으므로 LLM 이 "정시율 100%로 목표를 달성했음" 이라고 쓰기 쉽고, 숫자 검사도 통과한다. 화면(판정 안 함)과 문장(달성)이 엇갈린다.
- 고침(권장): `llm_allowed` 를 `arrived_count >= THIN_ARRIVALS` 로 바꿔 기록 적은 주는 템플릿만 쓴다. §4-2 bullet 과 §7(`도착 2건 → template + opener 미호출`)에 반영한다. 대안: 프롬프트에 "thin_data 가 true 면 정시율로 목표 달성 여부를 말하지 말고 기록이 적다고만 쓴다" 를 넣고, `check_text` 가 `thin_data` 일 때 "달성"·"못 미쳤" 이 있으면 `thin_data_judgement` 로 거절한다.

### 9. [MEDIUM] `check_text` 가 지키려는 세 가지(지어낸 수·날짜·"학습 중")에 쉬운 우회가 있음

- 위치: §0 "LLM 문장의 숫자는 집계값에 있는 것만 통과", §4-2 검증, 프롬프트 3·7.
- 문제: (1) 숫자 검사는 `\d+(?:\.\d+)?` 만 본다. "지각 두 번", "세 차례" 같은 한글 수사는 그대로 통과한다. (2) 날짜·시각을 금지했지만 "10월 7일", "9시" 의 숫자가 facts 에 우연히 있으면(10·7·9 는 흔함) 통과한다. (3) "학습 중"·"학습중" 만 막아서 "학습하는 중", "학습이 진행 중" 은 통과한다. PM 지시는 그 말 자체를 보이지 않게 하는 것이다.
- 고침: 프롬프트 3 에 "수는 모두 아라비아 숫자로 쓴다('두 번' 이 아니라 '2번')" 를 더하고 검사를 늘린다.
  ```python
  NUMBER_WORD = re.compile(r"(한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|스무)\s*(번|건|회|차례|분|시간)")
  DATE_TIME = re.compile(r"\d+\s*월|\d+\s*일|\d+\s*시|\d{1,2}\s*:\s*\d{2}")
  # 실패 이유: number_word / date_or_time / learning_word("학습" 이 들어가면 거절)
  ```
  템플릿은 "학습" 과 "숫자+월·일·시" 를 쓰지 않으므로 기존 단언("모든 템플릿 출력이 `check_text` 통과")이 그대로 성립한다. 테스트에 "지각 두 번", "10월 7일", "학습하는 중" 거절을 더한다.

### 10. [MEDIUM] 테스트 설정이 `OPENAI_BASE_URL` 을 고정하지 않아, 사용자가 `.env` 를 채우면 서버 테스트가 깨짐

- 위치: §4-5 "`test.py` 는 이미 … (그대로)", §7 `test_summary.py`(요청 URL = 기본 주소 + `/chat/completions`, llm 저장 경로), `apps/common/tests/test_settings.py`.
- 문제: `base.py` 17행은 import 때 `load_dotenv(BASE_DIR / ".env")` 를 하고, `test.py` 는 `from .base import *` 뒤 키·모델만 비운다(51·52행). 새 `OPENAI_BASE_URL` 은 `.env` 값을 그대로 물려받는다. 메인 체크아웃 `backend/.env` 에는 이미 `OPENAI_BASE_URL=` 줄이 있고(지금 빈 값), 사용자는 학교 주소를 받으면 채우겠다고 했다. 그 뒤로는 `settings` 로 키·모델만 넣는 성공 경로 테스트가 학교 주소로 요청해 URL 단언이 실패하거나, 원격 http 주소면 `bad_base_url` 로 떨어져 "llm 저장" 단언이 실패한다. 테스트 결과가 개발자 `.env` 에 따라 달라진다.
- 고침: `test.py` 의 외부 키 차단 블록에 `OPENAI_BASE_URL = "https://api.openai.com/v1"` 를 고정하고(주석 "`.env` 를 따르지 않는다"), `test_settings.py` 에 `assert settings.OPENAI_BASE_URL == "https://api.openai.com/v1"` 를 더한다. 리포트 `conftest.py` autouse 에서 `settings.OPENAI_BASE_URL = llm.DEFAULT_BASE_URL` 로 한 번 더 고정해도 된다.

### 11. [MEDIUM] DTO 형 규칙이 "개수가 아닌 정수" 를 정하지 않아 측정 못 한 값이 0 으로 보일 수 있음

- 위치: §5-1 "문자열·목록·객체·소수는 nullable, 개수는 `Int = 0`".
- 문제: 서버가 정수로 내리지만 개수가 아닌 값이 있다. `outcomes[].on_time_probability`(90), `planned.prep_minutes/travel_minutes/buffer_minutes`(`PositiveIntegerField`, 계획이 OK 가 아니면 null), `previous_week.on_time_rate_change_pp`(3건 미만이면 null), `event`(id), `weekday`. 지금 규칙으로는 소수도 개수도 아니어서 형이 정해지지 않는다. `Int = 0` 으로 두면 Gson 은 JSON null 을 원시형 필드에 넣지 않고 0 을 남긴다. 그러면 "앱이 본 정시 확률 0%", "계획 0분" 이 나온다(§0 "측정 못 한 값은 0 이 아니다", AT-19 위반).
- 고침: 규칙을 "`Int = 0` 은 이름이 개수인 것만(`*_count`, `count`, `plan_count`, `prep_from_records`, `travel_from_records`). 그 밖의 모든 수는 nullable + `= null`" 로 바꾸고 형을 적는다.
  ```kotlin
  data class OutcomeDto(
      val event: Long? = null,
      val title: String? = null,
      @SerializedName("start_at") val startAt: String? = null,
      val date: String? = null,
      val weekday: Int? = null,
      val result: String? = null,
      @SerializedName("slack_minutes") val slackMinutes: Double? = null,
      @SerializedName("on_time_probability") val onTimeProbability: Int? = null,
      val tau: Double? = null,
      @SerializedName("tau_bucket_lower") val tauBucketLower: Double? = null,
      @SerializedName("prep_applies") val prepApplies: Boolean? = null,
      val planned: PlannedDto? = null,
      val actual: ActualDto? = null,
      val deviation: OutcomeDeviationDto? = null,
      val cause: String? = null,
      val unmeasured: List<String>? = null,
  )

  data class PlannedDto(
      @SerializedName("alarm_at") val alarmAt: String? = null,
      @SerializedName("depart_by") val departBy: String? = null,
      @SerializedName("arrive_at") val arriveAt: String? = null,
      @SerializedName("prep_minutes") val prepMinutes: Double? = null,
      @SerializedName("travel_minutes") val travelMinutes: Double? = null,
      @SerializedName("buffer_minutes") val bufferMinutes: Double? = null,
  )
  ```
  `event == null` 인 일정은 지각한 날 줄과 ⑨ 칩에서 뺀다(testTag·이동 키가 없음). `NetworkDtoNullSafetyTest` 에 `{"outcomes":[{"on_time_probability":null,"planned":{"prep_minutes":null}}]}` 을 넣고 매핑 결과 어디에도 "0%"·"0분" 이 없음을 단언한다. #7 을 받으면 `@SerializedName("alarm_on") val alarmOn: Boolean? = null` 도 더한다.

### 12. [NIT] `week_complete` 와 `has_next_week` 는 정의상 항상 같은 값

- 위치: §3 표.
- 고침: 둘 다 "다음 주 월요일 00:00 ≤ 지금" 이다. 하나만 두거나, 둘 다 둔다면 "같은 값, 용도별 이름" 이라고 적고 서버 테스트에서 같음을 단언한다.

### 13. [NIT] `endpoint()` 명세와 테스트가 빈 값에서 엇갈림

- 위치: §4-3 `endpoint()`("https·localhost 가 아니면 None"), §7 `test_llm.py`("빈 값 → 기본"), §4-5 `base.py` 식.
- 고침: `raw = (settings.OPENAI_BASE_URL or "").strip() or DEFAULT_BASE_URL` 를 먼저 한다고 적는다. `base.py` 는 `(os.getenv("OPENAI_BASE_URL") or "").strip() or "https://api.openai.com/v1"` 로 써서 공백만 있는 값도 기본값이 되게 한다. 쿼리가 붙은 주소(`?api-version=`)는 지원하지 않고 `bad_base_url` 이라고 명시한다.

### 14. [NIT] `allowed_numbers` 의 범위가 모호함

- 위치: §4-2 검증 "facts 의 모든 수 + 문자열 값(`suggestion`, `late_causes` 값)에 든 숫자".
- 고침: "facts 를 재귀로 돌며 모든 수(dict 값 포함, 즉 `late_causes` 의 건수)와 모든 문자열 값 속 숫자를 모은다" 로 고친다. 지금 문장은 정수인 `late_causes` 값을 "문자열 값" 으로 분류한다.

### 15. [NIT] "템플릿 결과는 3~5문장" 이 실제 규칙과 다름

- 위치: §4-2 템플릿 머리말.
- 고침: 일정 없는 주와 도착 기록 없는 주는 개요 + 제안의 2문장이다(F-12 예시도 2문장). "2~5문장" 으로 고친다. 문장 수를 단언하는 테스트도 이 범위를 쓴다.

### 16. [NIT] ⑧ 계획 대비 실제의 출발 줄 형식이 Figma 와 다름

- 위치: §2-1 8 출발, figma-changes F-10 6("평균 3분 늦게 나섬 · 5회").
- 고침: 출발에도 "· {n}회" 를 붙이고 n = 0 → "측정 안 됨"(TextSecondary) 규칙이 출발에도 적용된다고 적는다.

### 17. [NIT] 타일 "지난주 0/0" 과 주 범위 "null ~ null"

- 위치: §2-1 4 왼쪽 타일 셋째 줄, §5-1 `weekStart`/`weekEnd` nullable(지금 `toView()` 의 폴백은 `"$weekStart ~ $weekEnd"`).
- 고침: "지난주 {o}/{a}" 는 `previous_week.arrived_count > 0` 일 때만 붙인다. `rangeLabel` 은 날짜를 못 읽으면 "—" 로 둔다.

### 18. [NIT] 카드별 보이는 조건이 여러 절에 흩어져 있음

- 위치: §2-1 각 항목 괄호, §2-4 표, F-12 "지난 일정 없음" 변형.
- 고침: 카드마다 보이는 조건을 한 표로 모은다. 예: 타일 = `event_count > 0`(구버전 서버도 같음), 요일 = `event_count > 0 && days.isNotEmpty()`, 계획 대비 실제 = `event_count > 0 && deviation != null`. 지금은 §2-1 6 이 "days 가 비면 없음" 만 말하는데 새 서버는 일정 0 주에도 7칸(NONE)을 만들고, §2-4 의 "지난 일정 없음" 행과 "구버전 서버" 행이 겹치는 주(구버전 + 일정 0)의 타일 처리가 서로 다르다.

### 19. [NIT] ⑨ 진입과 주 전환의 경계 동작

- 위치: §2-2 2, §2-3, `HomeViewModel.loadReport`(851행).
- 고침: `initialEventId` 가 그날 목록에 없으면 대표 일정을 고른다고 적는다. 요일 칸·지각 줄은 "‹"·"›" 처럼 `!loading` 일 때만 누를 수 있게 한다(로딩 중에 누르면 새 주가 도착한 뒤 ⑨ 가 "이 날의 기록을 찾지 못했음" 이 됨). 주 로드가 실패하면 `ReportState.week` 를 보이는 주로 되돌린다. 지금은 `week` 만 바뀌고 낡은 보고서의 `canGoNext` 가 남아서 "›" 를 다시 누르면 미래 주까지 갈 수 있다.

### 20. [NIT] 홈에서 열 때의 주가 문서와 코드에서 다름

- 위치: §2-3 "③ 홈 · 리포트 바로가기 → ⑧ 주간 리포트(지난 주)", `HomeViewModel.openReport()`(846행).
- 고침: `openReport()` 는 마지막으로 본 주(`_report.value.week`)를 다시 연다. 문서를 "마지막으로 본 주(처음은 지난 주)" 로 고치거나, `openReport` 에서 `week` 를 null 로 되돌린다고 적는다.

### 21. [NIT] 시각·τ·음수 표기 규칙

- 위치: §2-2 2·3·4.
- 고침: 일정 시작은 `HH:mm`("09:00 자료구조"), 나머지 시각은 `H:mm` 이라고 명시한다. τ 는 `%.2f` 로 쓰면 0.999 가 "1.00" 이 되므로 끝의 0 만 뺀 최대 3자리("0.9"·"0.925"·"0.999")로 쓰거나, 예시처럼 "0.90" 형식으로 고정하되 0.999 는 그대로 쓴다고 적는다. 앱 문자열의 음수 부호는 U+2212 "−" 로 통일한다(테스트가 문자열을 그대로 단언함).

### 22. [NIT] ⑨ 범례 색과 실제 막대 색 규칙이 다름

- 위치: §2-2 4 범례 "■ 실제(지각 Red, 그 밖 Green)", 막대 "실제(차이 > 0 Red, 아니면 Green)", F-13.
- 고침: 막대는 줄마다 차이 부호로 색이 정해지므로 범례 사각형은 TextSecondary 하나로 두거나 "■ 실제(늘어남 Red · 줄어듦 Green)" 로 쓴다.

### 23. [NIT] 꺼진 "›" 색

- 위치: §2-1 2, F-10 2 "진행 중" 변형("›" 를 `color/track`), 지금 `ArrowButton`(꺼지면 TextSecondary).
- 고침: 둘 중 하나로 정해 적는다(권장: Track — 같은 화면에서 TextSecondary 는 읽히는 글자에 쓰임).

### 24. [NIT] `prep_applies` 가 null 인 조건

- 위치: §3 `prep_applies`("계획 없음 null").
- 고침: "계획이 없거나 `status != ok` 이면 null, `prep_source == not_from_home` 이면 false, 그 밖 true" 로 적는다. OK 가 아닌 계획은 `prep_source` 가 "" 라서 지금 문장대로면 true 가 된다.

### 25. [NIT] 동률과 경계

- 위치: §4-2 `late_max_*`, 템플릿 3 "가장 큰 것", §3 `target_met`, `services.collect_outcomes`.
- 고침: `late_max_*` 가 동률이면 시작이 이른 것, 템플릿 3 의 동률은 이동 → 출발 → 준비 순서. `target_met` 은 반올림한 % 끼리 비교한다(`on_time_pct >= target_pct`). 그래야 "정시율 92%로 목표 92%에 못 미쳤음" 같은 문장이 나오지 않는다. 주 경계: `collect_outcomes` 는 `start_at__lte=end` 라 다음 주 월요일 00:00 일정이 두 주에 모두 들어간다. 주간 계산(이번 주·앞 주·주간 캘리브레이션)은 끝을 포함하지 않게(`start_at__lt`) 한다.

### 26. [NIT] `summary_for` 세부

- 위치: §4-2 의사 코드, §4-3 실패 표.
- 고침: 템플릿 예외로 FALLBACK 을 쓸 때와 `build_facts` 예외 때도 `summary_source = "template"` 이라고 명시한다. 저장된 LLM 문장 조회를 `llm_allowed` 의 12주 조건보다 먼저 할지 정한다. 지금 순서면 13주가 지난 주는 LLM 문장이 템플릿으로 바뀐다(의도라면 그렇게 적는다). `URLError(reason=TimeoutError)` 는 `network` 가 아니라 `timeout` 으로 매핑한다. urllib 의 timeout 은 연결·TLS·읽기 단계마다 적용되고 DNS 조회에는 적용되지 않으므로, 표의 "8초 초과" 를 "단계별 8초(전체 대기는 더 길 수 있음)" 로 고친다. 지금 gunicorn `--timeout 60`, 앱 read 60초 안에는 든다.

### 27. [NIT] 확인 스크립트가 학교 주소·빈 계정에서 잘못 실패할 수 있음

- 위치: §4-5 `check_external_apis.py` 5절, `check_app_contract.py` 6.7.
- 고침: 학교 프록시에는 `/models` 가 없을 수 있으므로 5절은 404 를 실패가 아니라 "확인 불가(→ `probe_report_summary`)" 로 기록한다. 6.7 의 `OutcomeDto`·`PreviousWeekDto` 필드 비교는 `outcomes` 가 비었거나 `previous_week` 가 null 이면 "건너뜀" 으로 둔다(검증용 새 계정에는 데이터가 없음).

### 28. [NIT] §8 "사람이 할 일" 이 이미 끝난 일을 시킴. 학교 API 호환 가정을 적어야 함

- 위치: §8 마지막 bullet, §4-3.
- 고침: 메인 체크아웃 `backend/.env` 에는 이미 `OPENAI_BASE_URL`(빈 값)·`OPENAI_API_KEY`(빈 값)·`OPENAI_MODEL`(설정됨, `gpt-4.1-mini`) 줄이 있다. "학교 키(·주소)를 `.env`·Render 에 채우고 `manage.py probe_report_summary` 를 한 번 돌림" 으로 줄인다. 학교 API 가 OpenAI 호환(`Authorization: Bearer`, `/chat/completions`, 쿼리 없음)이라는 가정을 §4-3 에 적고, Azure 식(`api-key` 헤더, `api-version` 쿼리)이면 `llm.py` 만 바꾼다고 적는다.

### 29. [NIT] 프롬프트에 키 설명이 일부만 있음

- 위치: §4-2 `SYSTEM_PROMPT` 4.
- 고침: 한 줄을 더한다. "`on_time_change_pp` 는 %p 차이, `late_max_min` 은 가장 많이 늦은 분(양수), `unobserved` 는 도착 기록이 없는 일정 수, `thin_data` 가 true 면 기록이 적음, `top_cause*` 는 가장 많은 지각 원인, `suggestion` 은 마지막 문장의 근거." 검증 실패로 템플릿에 떨어지는 경우를 줄인다.

### 30. [NIT] 접근성

- 위치: §2-1 7 지각한 날 줄, §2-2 2 칩, §2-1 6 요일 칸.
- 고침: 지각한 날 줄에도 요일 칸처럼 `clickable(role = Role.Button, onClickLabel = "그날 상세 보기")` 와 합친 설명("화요일 10월 6일, 10분 늦음, 이동이 13분 더 걸렸음")을 주고, 끝의 "›" 는 장식으로 뺀다. ⑨ 칩은 높이가 약 22dp 라 `Modifier.minimumInteractiveComponentSize()` 를 쓴다. 요일 칸은 360dp 화면에서 폭이 약 42dp 로 48dp 보다 좁다는 한계를 문서에 적는다. 전체 WCAG 판정은 보조 기술 실기기 확인이 따로 필요하다.

---

## 확인한 가정 (코드로 확인함)

1. 주간 캘리브레이션 결함: `services.weekly` 가 `calibration(user, since=start)` 를 부르고, `collect_outcomes` 는 `until` 이 없으면 끝을 `now` 로 잡는다. 과거 주를 보면 그 뒤 일정까지 들어간다.
2. `collect_outcomes` 는 weekly·`lateness_causes`·`calibration` 에서 세 번 불린다.
3. `WeeklyReportDto` 는 `weekStart`/`weekEnd` 에 기본값이 없어 무인자 생성자가 없다(Gson Unsafe 경로). 매핑 `toView()` 는 `private` 이다. 새 DTO 를 전부 기본값으로 두면 무인자 생성자가 생긴다.
4. `AppRoute.WeeklyReport`·`WeeklyReportScreen` KDoc 은 "Figma ⑨" 다. 메인 `docs/instruction.md` §14.3 은 `1:137` = ⑧, `16:2` = ⑨ "(⑧에 합침)". `color/surface-danger #4A2121` 은 `Tokens.kt` 에 없다(§8.3 빚).
5. 알람 계산은 `ModelArtifact` 를 읽지 않는다(`planning/services._block_observation_stats`, `estimators._lookup_correction` 이 `BlockObservation`·`RouteCorrection` 을 직접 읽음). `RouteCorrection` 에는 사용자 FK 가 없다(전역).
6. `travel_source` 는 보정이 있으면 `"{route source}+observed_route|observed_global"` 이고(`planning/services.py`), 경로 출처는 `kakao_transit`·`kakao_{key}`·`kakao_car` 라 30자 안에 든다. `"+observed" in travel_source` 판정이 맞다. `prep_source == "observed"` 는 사용자 자신의 블록 관측이 있을 때만이다. `PREP_SOURCE_NOT_FROM_HOME` 상수가 있다.
7. `AlarmPlan`: `arrive_at = start − buffer`, `prep/travel/buffer_minutes` 는 정수, `tau_used`·`on_time_probability` 는 null 허용, OK 가 아닌 계획은 값이 전부 null(`compute_and_store` defaults), OK 면 값이 다 있어야 한다(CheckConstraint).
8. `DemoFlowTest` 10단계는 `open_report` → `report_next_week` → "1 / 1건"·"정시 도착 100%" 를 찾는다. 검증 계정은 실행마다 새로 만든다(`emu_verify_setup.py` uuid 이메일). 그래서 지난주는 비어 있고 이번 주는 진행 중이라 LLM 을 부르지 않는다.
9. `JitDotLabel(textColor, fontSize: Int, dotSize)`, `JitChip(container)`, `JitProgressBar(track)` 이 있다. `JitCard` 에는 `container` 가 없다. `ScreenHeader(title, onBack, enabled)` 는 `ui/events/AddEventScreen.kt` 의 `internal` 함수다. `AppRoute` 를 빠짐없이 나누는 `when` 은 `MainActivity` 에만 있다.
10. 설정: `base.py` 에 `OPENAI_API_KEY`·`OPENAI_MODEL` 은 있고 `OPENAI_BASE_URL` 은 없다. `apps.reports` 주석은 "모델이 없다", `APP_VERSION = "0.10.0"`. `test.py` 가 두 키를 비운다. `test_settings.py` 빈 키 목록에는 `OPENAI_MODEL` 이 없다. 앱 `versionName = "0.10.0"`, `minSdk = 34`.
11. prod `CACHES["default"]` 는 `/tmp/jit-cache` 의 `FileBasedCache`(MAX_ENTRIES 300)이고 지도 이미지와 같이 쓴다. 백오프 키가 밀려날 수 있다는 설명이 맞다.
12. `Dockerfile` 은 시작할 때 `migrate --noinput` 을 하고 gunicorn 동기 워커 2개, `--timeout 60` 이다. 앱 OkHttp 는 read 60초·call 90초다. 8초 LLM 대기가 둘 다에 든다.
13. `routing/clients.py` 는 `urllib` + `ssl.create_default_context()` + timeout 방식이다. HTTP 오류 본문 200자를 로그에 남기는데, 설계가 OpenAI 쪽에서 본문을 남기지 않기로 한 것은 맞는 판단이다(401 본문에 가린 키 일부가 들어감).
14. `render.yaml` 에 OPENAI 변수가 없다. 루트 README 361~364행은 OPENAI 를 "reserved for planned features" 로 적었다. `.env.example` 의 OpenAI 절은 F12 용이다. `check_external_apis.py` 5절은 `https://api.openai.com/v1/models` 를 박아 두었다. `check_app_contract.py` 의 `compare` 는 빠진 필드를 실패로 센다(배포 전 "없음" 보고 = 실패 집계). `check_deployed.py` 14절이 있다.
15. `ServerVersion.isServerBehind` 는 앱이 서버보다 새면 알린다. `ReportRepository` 는 캐시하지 않고 `guard` 가 IOException 은 `Log.w`, 그 밖은 `Log.e` 로 남긴다.
16. `UnreliableCopyAuditTest` 가 앱 소스의 "학습 중" 을 막는다. 새 앱 문구 예시에도 없다.
17. 메인 체크아웃 `backend/.env` 에 `OPENAI_BASE_URL`(빈 값)·`OPENAI_API_KEY`(빈 값)·`OPENAI_MODEL=gpt-4.1-mini` 가 있다. 추론형 모델이 아니므로 `temperature`·`max_tokens` 없이 8초 안에 답한다는 전제와 맞는다.
18. 개인정보: `build_facts` 의 고정 키에는 제목·장소·주소·좌표·날짜·시각·id·이메일·닉네임이 없고, 문자열 값은 고정 원인 이름·요일 한 글자·고정 제안 문장뿐이다. 요청 본문에 사용자 식별자를 넣지 않는다. §7 테스트가 facts 와 보낸 본문을 둘 다 본다.
19. 범위: 서버 변경은 사용자 요청(줄글 요약, `.env` 자리, "오가는 데이터는 DB 에 기록")과 N20(키는 서버에만)에서 나온다. 범위를 넘는 기능은 찾지 못했다. `back-iter2` 는 지금 `origin/main` 과 같은 커밋이라 `apps/reports` 마이그레이션 충돌은 아직 없다.

## 확인 못 함 · 틀린 가정

1. **틀림**: 예시 "화 10월 7일" / `date 2026-10-07, weekday 1`. 2026-10-07 은 수요일이다(#1).
2. **틀림**: "⑨ → ⑧ 스크롤 그대로". `AnimatedContent` 가 ⑧ 을 버리고 `SaveableStateHolder` 가 없다(#2).
3. **틀림**: 원인을 못 나눈 지각에 "위치 권한을 켜 두면 기록됨". 도착이 기록됐으면 권한은 켜져 있었다. 출발 누락은 `departureMissed`·집 아닌 출발지·계획 없음 때문이다(#6).
4. **틀림(암묵)**: 지난 일정은 모두 기록될 수 있다는 전제. 추적은 알람 해제에서만 시작하고, 기본 규칙상 하루 첫 알람만 켜진다(#7).
5. **틀림**: `_measure` 의 "준비가 해당 없는 일정은 재지 않음" 이 `LatenessCause.unmeasured`/`label` 에 반영되지 않는다(#5).
6. **틀림**: §8 "`.env` 에 `OPENAI_BASE_URL=` 줄 추가" 는 이미 되어 있다(#28).
7. **틀림**: "템플릿 결과는 3~5문장". 2문장인 경우가 있다(#15).
8. **부분적으로 틀림**: "timeout 8초 초과". urllib timeout 은 단계별이다(#26).
9. **확인 못 함**: 학교 API 가 OpenAI 호환인지. 아직 받지 않았다. `probe_report_summary` 로 확인할 수 있게 설계돼 있다(#28).
10. **확인 못 함**: Render 무료 플랜이 잠들 때 `/tmp` 가 비는지. 문서·코드로는 확인하지 못했다. 설계가 DB 를 정본으로 두므로 맞든 틀리든 결과는 같다.
11. **확인 못 함**: Figma 프레임(F-10~F-13)과 새 프레임 위치. 이 환경에 Figma MCP 가 없다. 설계도 그렇게 밝혔다.
