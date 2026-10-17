# Phase B 설계 — 리포트(⑧ 주간 · ⑨ 상세)와 주간 줄글 요약

> 이 문서는 해당 Phase의 설계·리뷰·검증 당시 기록이다. 당시 테스트 수·미구현 설명을 현재 상태로 해석하지 않는다. 2026-10-17 후속 통합 상태는 [최신 TODO](../../task.md), [요청별 완료 상태](backend-requests.md), [최신 검증](account-ranges-and-travel-counts.md)을 따른다.

> 구현은 설계 리뷰·구현 리뷰를 반영한 판이다. 이 본문과 다른 점은 [phaseB-verification.md](phaseB-verification.md) "설계와 다르게 한 것" 에 있다.

- 기준: `front-iter2` HEAD `5a41095`(Phase A 끝). 서버 `APP_VERSION 0.10.0`, 앱 `versionName 0.10.0` → 둘 다 `0.11.0`(§4-5).
- 범위: ⑧·⑨ 화면(Figma 명세 + 앱), `GET /api/reports/weekly` 확장, 서버 LLM 요약(B2). 서버는 `apps/reports`·설정·배포 파일만 고치고 다른 앱은 읽기만 한다. `back-iter2` 와 겹칠 수 있어 서버 변경은 백엔드 담당에게 PR 로 공유한다.
- Figma: 이 환경에 Figma MCP 도구가 없다. 프레임별 명세는 [figma-changes.md](figma-changes.md) Phase B(F-10 ~ F-13).
- 경로: `ui/…` = `APP/app/src/main/java/com/swpp/wakeup/ui/…`, `be/…` = `backend/…`.

## 0. 개요

지금 리포트는 수치 카드의 나열이고 ⑨ 는 ⑧ 에 합쳐져 따로 없다. 무엇을 할지는 사용자가 읽어 내야 한다. Phase B 는 ⑧ 맨 위에 서버가 만든 3~5문장 **주간 요약**을 두고, 그 아래에 근거 수치(정시율·평소 도착·요일별 도착·지각한 날·계획 대비 평균·예측 검증)를 둔다. 요일 칸이나 지각한 날을 누르면 **⑨ 상세 리포트**로 가서 그날 일정 하나의 계획 대비 실제(준비·출발·이동·도착), 지각 원인, 90일 예측 검증을 본다. 요약은 서버가 **집계 수치만** OpenAI 호환 chat completions 에 보내 만들고, 키가 없거나 실패·시간 초과·검증 실패면 같은 수치로 만든 **고정 문장(템플릿)**을 쓴다. 어느 쪽인지는 `summary_source: "llm" | "template"`. LLM 문장은 사용자·주 단위로 DB 에 저장해 수치가 같으면 다시 부르지 않는다.

기술 스택(고정, 새 의존성 없음): 서버 Django 5.2 · DRF 3.18 · 표준 라이브러리 `urllib`(`apps/routing/clients.py` 방식) · pytest-django. 앱 Kotlin · Compose Material3 · Retrofit/Gson · JUnit4 · UiAutomator.

지켜야 할 것:
- **지어낸 숫자 금지.** 화면 숫자는 서버 값이거나 서버 값의 표시용 집계(최솟값·개수)뿐이다. LLM 문장의 숫자는 집계값에 있는 것만 통과한다(§4-2).
- **N19.** 응답·프롬프트·DB 어디에도 좌표·이동 흔적이 없다. 프롬프트에는 제목·장소·주소·날짜·시각·이메일·닉네임·일정 id 도 없다.
- **N20.** 키는 서버 환경변수에만 있다. 앱은 문장과 출처만 받는다.
- **"학습 중" 금지.** 모르는 값은 "측정 안 됨"·"기록 없음"·"표본 부족"(PM 지시. N29 의 "learning" 표기보다 우선, Phase A 와 같음).
- **측정 못 한 값은 0 이 아니다**(AT-19). 서버 None 을 그대로 내리고 앱은 숨기거나 "측정 안 됨".
- 테스트는 키·네트워크 없이 통과하고 LLM 을 부르지 않는다(§7).
- 화면 문구 "~함/~음" 체. 분 표시 반올림은 **절댓값을 0.5 올림한 뒤 부호**(서버 템플릿·앱 같은 규칙: 8.5 → 9, −8.5 → −9).

## 1. 지금 상태 (코드 확인)

- 앱: `WeeklyReportScreen` 하나가 요약(정시율·중간 여유·가장 아슬아슬)·요일별 지각 비율·이 주 캘리브레이션·90일 캘리브레이션·지각 원인을 나열한다. KDoc·`AppRoute.WeeklyReport` 는 "Figma ⑨" 라고 적었지만 노드 `1:137` 은 ⑧ 이다(⑨ `16:2` 는 "⑧에 합침", 메인 `docs/instruction.md` §14.3).
- 서버: `services.weekly` 는 요청마다 계산하고 저장하지 않는다. `collect_outcomes` 를 세 번 부른다(weekly·lateness_causes·calibration).
- **결함**: weekly 의 `calibration(user, since=start)` 은 끝을 "지금" 으로 잡는다. 과거 주를 보면 **그 뒤 주의 일정까지** 이 주 캘리브레이션에 들어간다(§4-1 에서 고침).
- `WeeklyReportDto` 는 기본값 없는 `weekStart`/`weekEnd` 때문에 Gson 이 생성자를 건너뛴다. 키가 빠지면 non-null 목록 필드도 null 이 된다(`NetworkDtoNullSafetyTest` 머리말의 함정). 매핑은 `private` 이라 테스트가 없다.
- Figma ⑧/⑨ 에서 서버에 재료가 없는 것: 수면(B-9 미결정), 알람 수락·스누즈(기록 없음), 기상 지연(`wake` 관측 없음, US-16 미완), 요일별 자동 조정·"도입 전 정시율"(모델·대조군 없음, AT-21), 취침↔기상 상관, p50/p80/p95 채점(서버는 τ 0.05 구간). → §2-5 에서 뺀다.
- 학습값(R-4): `prediction.ModelArtifact` 는 알람 계산이 읽지 않는다(`planning/estimators.py` 는 `BlockObservation`·`RouteCorrection` 을 직접 읽음). 아티팩트 값을 "학습 반영" 으로 보이면 사실이 아니다. 대신 각 계획이 실제로 쓴 출처(`prep_source == "observed"`, `travel_source` 의 `+observed_*`)를 센다(§3 `learning`). **R-4 는 이것으로 닫는다.**
- 이동 보정(`RouteCorrection`)은 모든 사용자의 기록으로 만든다(R-3). 그래서 이동은 "내 기록" 이 아니라 "이동 기록 보정" 이라고 쓴다. 준비 블록 관측만 개인 기록이다.
- `DemoFlowTest` 10단계는 리포트에서 `report_next_week` 로 **진행 중인 이번 주**에 가서 "1 / 1건"·"정시 도착 100%" 를 찾는다. 이번 주로 가는 길은 남긴다.
- Figma 변수 `color/surface-danger #4A2121` 은 있는데 `Tokens.kt` 에 없다(메인 `instruction.md` §8.3 의 빚). ⑨ 지각 카드에 쓰면서 맞춘다.

## 2. 화면

### 2-1. ⑧ 주간 리포트 (`ui/report/WeeklyReportScreen.kt`, Figma `1:137`)

루트는 지금처럼 `Column(verticalScroll)` + `JitSpace` 패딩 + `spacedBy(Section)`. 위에서 아래로:

1. **헤더** `ScreenHeader("주간 리포트", onBack, enabled = !loading)`.
2. **주 전환** "‹ 10월 5일 ~ 10월 11일 ›"(지금 `WeekSwitcher`, testTag 그대로). "‹" 는 `!loading`, "›" 는 `!loading && canGoNext`. `canGoNext` = 서버 `has_next_week`(키가 없으면 true). 이번 주까지 가고 미래 주는 못 간다.
3. **주간 요약** (testTag `report_summary`, `summary` 가 null·빈칸이면 없음) — `JitCard(padding 16, gap 8)`. 윗줄 `JitDotLabel("주간 요약", dotColor = Accent, fontSize = 12)`, 오른쪽은 진행 중인 주면 `JitChip("진행 중", Amber)`, 아니고 `summary_source == "llm"` 이면 "AI 요약 · 집계 수치만 사용함"(TextSecondary 10), 템플릿이면 없음. 본문 TextPrimary 13sp · lineHeight 20sp · 줄 수 제한 없음(서버가 400자 이하).
4. **타일 두 개** — `Row(Modifier.height(IntrinsicSize.Min))` 안에 `weight(1f).fillMaxHeight()` 카드 두 개(padding 14, gap 3).
   - 왼쪽(testTag `report_on_time_count`): 큰 값 `"{on_time}/{arrived}"` 24sp Bold. 색: `target_met` true Green, false Amber, null TextPrimary. 아래 "정시 도착 {pct}%"(TextSecondary 11). 그 아래 "목표 {t}% · 지난주 {o}/{a}"(TextSecondary 10, 있는 조각만, 없으면 줄 없음). 도착 기록 0 → "—" / "정시 도착 · 기록 없음".
   - 오른쪽: `median_slack_minutes` 반올림 n — n ≥ 1 "{n}분 일찍"(Green), n ≤ −1 "{|n|}분 늦게"(Red), 그 밖 "정각"(TextPrimary). 아래 "평소 도착 (중간값)". 그 아래 `tightest_slack_minutes` t — 음수 "가장 늦은 날 {|t|}분 늦음", 0 "가장 아슬아슬 정각 도착", 양수 "가장 아슬아슬 {t}분 전". 기록 0 → "—" / "평소 도착 · 기록 없음".
5. **측정 공백 안내**(지금 `NoticeCard`, `observationGapMatters` 일 때): "측정하지 못한 아침 {u}건" / "도착을 기록하지 못한 아침은 정시로 세지 않음. 위 비율은 {a}건만 반영한 값임". 도착 기록 0 이면 둘째 문장을 뺀다.
6. **요일별 도착** (`days` 가 비면 없음 — 구버전 서버):
   - 윗줄 "요일별 도착"(TextSecondary 11) · 오른쪽 "기록 {arrived}/{events}건"(TextSecondary 10).
   - 7칸(각 `weight(1f)`): 높이 64dp 상자 + 요일 + 값. 상자 가운데 기준선(Track 1dp, 칸 폭 전체). 대표값 = 그날 도착 기록 중 **가장 작은 여유**. 정시 = 기준선 위로 Green, 지각 = 아래로 Red. 막대 폭 14dp, 모서리 4dp, 높이 `3 + 29 × min(|여유|, 30) / 30` dp.
   - 일정만 있고 도착 기록이 없는 날 = 기준선 위 회색 점(6dp, TextSecondary). 일정 없는 날 = 비움.
   - 요일(10sp): 일정 있음 TextSecondary, 없음 Track. 값(9sp Bold, 막대 색): "+12" / "−10" / 기록 없음 "—"(TextSecondary) / 일정 없음 빈칸.
   - 일정 있는 칸만 누를 수 있다: `clickable(role = Button, onClickLabel = "그날 상세 보기")`, `semantics(mergeDescendants)` 로 "화요일, 10분 늦게 도착" / "월요일, 12분 일찍 도착" / "금요일, 도착 기록 없음", testTag `report_day_{0..6}` → `onOpenDay(date, null)`.
   - 캡션(TextSecondary 10) "위는 일찍, 아래는 늦게 도착한 분. 요일을 누르면 그날 상세를 봄".
7. **지각한 날** (`late_count > 0` 이고 `outcomes` 가 있을 때): 윗줄 "지각한 날 · {L}건". 지각 일정마다 한 줄(시작 순, 최소 높이 48dp, 줄 전체 버튼, testTag `report_late_{eventId}` → `onOpenDay(date, eventId)`): 왼쪽 "화 10월 7일"(TextPrimary 12 Bold) / 원인 제목(TextSecondary 11, §2-2 표) · 오른쪽 "10분 늦음"(Red 12 Bold) + "›". `unknown` 이 있으면 카드 아래 "\"원인 측정 못 함\"은 그 요인이 괜찮았다는 뜻이 아니라 기록이 없다는 뜻임"(TextSecondary 10).
8. **계획 대비 실제 · 이번 주 평균** (`event_count > 0` 이고 `deviation` 이 있을 때): 준비 · 출발 · 이동 세 줄, 왼쪽 TextPrimary 12 · 오른쪽 12 Bold. 반올림 평균 m, 횟수 n:
   - 준비·이동: n = 0 "측정 안 됨"(TextSecondary) / m ≥ 1 "평균 {m}분 더 걸림 · {n}회"(Amber) / m ≤ −1 "평균 {|m|}분 덜 걸림 · {n}회"(Green) / 그 밖 "계획과 비슷함 · {n}회"(TextPrimary).
   - 출발: "평균 {m}분 늦게 나섬" / "평균 {|m|}분 일찍 나섬" / "계획대로 나섬", 색 규칙 같음.
   - 구분선 + 기록 반영(`learning.plan_count > 0`): `JitDotLabel("기록 반영", dotColor = Blue, fontSize = 11)` + 본문(TextSecondary 10) — 둘 다 0 이면 "이번 주 알람은 기록 없이 기본값으로 계산됨", 아니면 "이번 주 알람 {N}개 중 {p}개는 내 준비 기록으로, {t}개는 이동 기록 보정으로 계산함"(0인 조각은 뺌).
9. **예측 검증 · 최근 90일** (`longTerm` 이 있을 때, 지금 `CalibrationCard` 에서 버킷 줄을 뺀 모양): 제목 + 판정 칩(`verdictLabel`, 지금 `verdictColor`) / `verdictNote`(신뢰 가능 TextPrimary 12, 아니면 TextSecondary) / `action` 이 있으면 "→ …"(Accent 10) / "채점 {s}건 · 채점 못 함 {u}건"(TextSecondary 10, u = 0 이면 앞 조각만). `overconfident` 면 Accent 테두리(지금과 같음). 버킷 표는 ⑨ 에 둔다.

없앤다: 이 주 캘리브레이션 카드(한 주는 거의 늘 "표본 부족", 응답 필드는 구버전 앱용으로 남김), 요일별 지각 비율 막대(`WeekdayCard`), `CausesCard`(→ 7번).

### 2-2. ⑨ 상세 리포트 (`ui/report/ReportDayScreen.kt` 신규, Figma `16:2`)

하루 단위 화면이다. 그날 일정이 둘 이상이면 칩으로 고른다.

1. **헤더** `ScreenHeader("{요일}요일 상세", onBack, trailing = "10월 7일")`. `ScreenHeader` 에 `trailing: String? = null` 을 더한다(있을 때만 `Row(fillMaxWidth)` + `Spacer(weight)` + TextSecondary 13).
2. **일정 칩**(그날 일정 2개 이상, `Row(horizontalScroll)`): "09:00 자료구조"(제목 8자 넘으면 8자 + "…"). 고른 칩 = Accent 테두리 1dp + TextPrimary 11, 나머지 = Surface2 + TextSecondary 11. 처음 고른 것 = 진입 때 받은 `eventId`, 없으면 그날 대표(가장 작은 여유, 도착 기록이 하나도 없으면 시작이 가장 이른 것). 선택은 화면 로컬 상태(`rememberSaveable`).
3. **결과 카드** — `JitCard` 에 `container: Color = JitColor.Surface` 파라미터를 더해 쓴다(기본값이라 기존 호출 그대로).
   - 지각: container `JitColor.SurfaceDanger`(신규 토큰 #4A2121). `JitDotLabel("{n}분 지각", dotColor = Red, textColor = Red, fontSize = 20, dotSize = 9.dp)`(n = 0 이면 "1분 안쪽 지각"). 아래 정시·기록 없음도 같은 모양으로 색만 바꾼다. 이 주 지각이 이 일정 하나뿐이면 오른쪽 `JitChip("이번 주 유일한 지각", Red, container = Red.copy(alpha = 0.16f))`.
   - 정시: Surface, "{n}분 전 도착"(Green 20, n = 0 이면 "정각 도착").
   - 기록 없음: Surface, "도착 기록 없음"(TextSecondary 18) 바로 아래 "도착을 기록하지 못해 채점하지 않았음"(TextSecondary 11).
   - 공통 줄(제목 다음): "09:00 자료구조 · 알람 7:40"(TextPrimary 12, 알람 없으면 앞 조각만), 그 아래(TextSecondary 11) `on_time_probability` 가 있으면 "앱이 본 정시 확률 {p}%", 없고 `tau` 가 있으면 "적용 τ 0.90", 둘 다 없으면 줄 없음.
4. **계획 대비 실제**: 윗줄 "계획 대비 실제"(TextSecondary 11) · 오른쪽 범례 ■ 계획(Track) ■ 실제(지각 Red, 그 밖 Green).
   - 준비(`prep_applies == true` 일 때만)·이동: 제목(TextPrimary 13 Bold) · 오른쪽 차이 "+6분"(Red) / "−3분"(Green) / "±0분"(TextSecondary) / "측정 안 됨"(TextSecondary). 아래 막대 두 줄 `JitProgressBar(track = Color.Transparent, height = 6.dp)` + 오른쪽 라벨(폭 64dp): 계획(Track) "계획 28분"(TextSecondary 10), 실제(차이 > 0 Red, 아니면 Green) "실제 34분"(TextPrimary 10 Bold). 막대 길이 = 값 ÷ 그 줄의 max(계획, 실제)(둘 다 0 이면 0). 실제가 없으면 실제 막대 대신 "실제 측정 안 됨"(TextSecondary 10), 계획이 없으면 "계획 없음".
   - 출발: 막대 없이 "계획 8:08 → 실제 8:15"(TextSecondary 12, 실제 쪽 TextPrimary Bold) · 오른쪽 차이(늦게 Red, 일찍 Green). 실제가 없으면 "계획 8:08 · 실제 측정 안 됨".
   - 구분선, 도착: "계획 8:50 → 실제 9:10"(실제: 지각 Red Bold, 정시 Green Bold). 계획 = 계획의 `arrive_at`(일정 시작 − 버퍼). 차이는 쓰지 않는다(결과 카드가 말함).
   - 시각은 기기 시간대 `H:mm`(24시간제). 차이 분은 서버 `deviation` 반올림.
5. **원인 분석**(지각일 때만): `JitDotLabel("원인 분석", dotColor = Blue, textColor = Blue, fontSize = 12)` → 제목(TextPrimary 13 Bold) → 설명(TextSecondary 11) → 할 일(Accent 10, "→ " 접두). n = 그 원인의 `deviation` 값 반올림.

| `cause` | 제목 | 설명 | 할 일 |
| --- | --- | --- | --- |
| `travel_over` | 이동이 {n}분 더 걸렸음 | 준비·출발·이동 중 계획보다 가장 크게 늘어난 단계임 | 다른 경로를 골라 보거나 몇 분 일찍 나서 볼 것 |
| `depart_late` | 집에서 {n}분 늦게 나섰음 | 같음. `unmeasured` 에 `prep_over` 가 있으면 "준비 항목을 기록하면 준비와 출발 중 어디서 늦었는지 나눠 볼 수 있음" 을 덧붙임 | 알람이 정한 출발 시각을 지켜 볼 것 |
| `prep_over` | 준비가 {n}분 더 걸렸음 | 같음 | 오래 걸린 준비 항목의 시간 범위를 루틴에서 늘려 두면 알람이 그만큼 앞당겨짐 |
| `plan_too_tight` | 계획대로 움직였는데 늦었음 | 준비·출발·이동이 모두 계획 안이었음. 계획의 여유가 부족했음 | 평소 준비 시간을 넉넉히 잡으면 알람이 앞당겨짐 |
| `unknown`·그 밖 | 원인을 나누지 못했음 | "측정 안 됨: 출발 · 이동"(`unmeasured` 를 준비·출발·이동 이름으로) | 출발·이동이 빠졌으면 "위치 권한을 켜 두면 출발·도착이 자동으로 기록됨", 준비만 빠졌으면 "준비 항목을 기록하면 준비 시간도 나눠 볼 수 있음" |

6. **예측 검증 · 최근 90일**(`longTerm` 이 있을 때): 제목 · 오른쪽 "약속 → 실제"(TextSecondary 10). 버킷마다 한 줄: 점 + "90~95%"(11) · 오른쪽 "실제 89% · 27번"(11 Bold; 신뢰 가능 + 과신 Red, 신뢰 가능 Green, 아니면 TextSecondary + " · 표본 부족"). 이 일정의 `tau_bucket_lower` 와 같은 구간(차 1e-6 이하)은 점 Accent + 왼쪽 TextPrimary Bold, 나머지 점 Track. 버킷이 없으면 "아직 채점된 아침이 없음"(TextSecondary 11). 맨 아래 `verdictNote`(TextSecondary 10).

Figma ⑨ 의 "취침 알림 켜기" 버튼은 없다(§2-5).

### 2-3. 이동

```
③ 홈 · 리포트 바로가기 → ⑧ 주간 리포트(지난 주)
⑧ ‹ › → 다른 주(이번 주까지)
⑧ 요일 칸 → ⑨ (date, 대표 일정)
⑧ 지각한 날 줄 → ⑨ (date, 그 일정)
⑨ ‹ · 시스템 뒤로 → ⑧ (보던 주·스크롤 그대로)
```

- `AppRoute.ReportDay(val date: LocalDate, val eventId: Long?)` 신규(KDoc "Figma ⑨ 상세 리포트"). `AppRoute.WeeklyReport` KDoc 을 "Figma ⑧" 로 고친다.
- `HomeViewModel.openReportDay(date, eventId)` = 스택 push(로딩 없음). ⑨ 는 `reportState.weekly` 에서 그 날짜의 `ReportDay` 를 찾는다. 못 찾으면 "이 날의 기록을 찾지 못했음"(TextSecondary 12)만 그린다(뒤로는 헤더).
- `BackHandler` 는 기존 `goBack` 경로 그대로(⑨ pop → ⑧).

### 2-4. 빈·적은 데이터 상태

| 상태 | 판정(서버 값) | ⑧ |
| --- | --- | --- |
| 지난 일정 없음 | `event_count == 0` | 주 전환, 요약(템플릿), 예측 검증만 |
| 도착 기록 없음 | `event_count > 0`, `arrived_count == 0` | 요약, 타일 "—", 측정 공백 안내, 요일(회색 점, ⑨ 로 감), 계획 대비 실제("측정 안 됨" 위주), 예측 검증 |
| 기록 적음 | `0 < arrived_count < 3` | 전부. 왼쪽 타일 색 중립(`target_met` null), 요약에 "기록이 적어 비율로 판단하기는 이름" |
| 진행 중인 주 | `week_complete == false` | 전부 + 요약 "진행 중" 칩, 템플릿 요약, "›" 꺼짐 |
| 구버전 서버 | 새 키 없음 | 요약·요일·지각한 날·계획 대비 실제 없음(⑨ 입구 없음). 타일·측정 공백·예측 검증은 보임 |
| 90일 캘리브레이션 실패 | `longTerm == null` | ⑧·⑨ 예측 검증 카드만 없음(지금처럼 화면은 막지 않음) |

⑨ 는 지각·정시·도착 기록 없음 세 가지(§2-2 의 3). 기록 없음은 계획만 있고 실제는 "측정 안 됨", 원인 분석 없음. 판정이 모자라면 "표본 부족". 어디에도 "학습 중" 이 없다.

### 2-5. Figma 에서 빼는 것 (재료 없음)

| Figma | 이유 | 대신 |
| --- | --- | --- |
| ⑧ "+22분 지난주 대비 수면" | 수면 데이터 없음(B-9 미결정) | 평소 도착(중간값) 타일 |
| ⑧ "요일별 준비시간 (분)" | 준비 관측은 루틴 블록을 쓴 날만 있음 | 요일별 도착(여유) |
| ⑧ "준비시간 평균 오차 · p90 실제 정시율" | 분위수로 채점하지 않음 | 계획 대비 실제 평균 + 예측 검증 |
| ⑧ "학습 반영됨 · 화요일은 평균보다…" | 요일별 학습 없음 | 기록 반영(계획 출처 수) |
| ⑧ "다음 주 자동 조정", "도입 전 62% → 현재 86%" | 요일별 자동 조정·대조군 없음(AT-21) | 요약의 제안 한 문장 |
| ⑧ "신뢰 지표"(알람 수락·스누즈) | 알람 상호작용 기록 없음 | 없음(백로그) |
| ⑨ "기상 지연" 줄 | `wake` 관측 없음(US-16) | 출발 줄 |
| ⑨ 원인 분석의 취침·수면·상관 | 수면 데이터 없음 | 원인 분해(AT-19) |
| ⑨ p50/p80/p90/p95 | 서버는 τ 0.05 구간 채점 | τ 구간 표 |
| ⑨ "취침 알림 켜기" | 기능 없음 | 없음 |

## 3. API 계약 — `GET /api/reports/weekly?week=YYYY-MM-DD`

인증·`week` 규칙은 지금 그대로다(선택, 형식이 틀리면 지난 주, 400 없음). 기존 키는 이름·모양을 바꾸지 않는다(구버전 앱). 아래를 **더한다**. 시각은 UTC ISO 8601(`Z`), 날짜는 서버 시간대(Asia/Seoul) 날짜, 분은 소수 첫째 자리.

| 키 | 형 | 뜻 |
| --- | --- | --- |
| `week_complete` | bool | 그 주 일요일 24:00 이 지났는가 |
| `has_next_week` | bool | 다음 주 월요일 00:00 이 지났는가(앱 "›") |
| `target_rate` | float \| null | 도착 기록이 있는 일정들의 `plan.tau_used` 평균(4자리). 없으면 null |
| `target_met` | bool \| null | `on_time_rate >= target_rate`. 도착 기록 3건 미만이거나 목표가 없으면 null |
| `previous_week` | object \| null | 앞 주 `{week_start, event_count, arrived_count, on_time_count, on_time_rate, on_time_rate_change_pp}`. 앞 주 일정 0 이면 null. `change_pp` = round((이번 − 앞) × 100), 두 주 모두 도착 기록 3건 이상일 때만 |
| `deviation` | object | `{prep_over, depart_late, travel_over}` 각 `{count, mean_minutes, sd_minutes}`. 그 주 **모든** 일정 중 측정된 것. count 0 이면 mean null, count < 2 면 sd null(모표준편차) |
| `learning` | object | `{plan_count, prep_from_records, travel_from_records}` — 계획 OK 일정 수, 그중 `prep_source == "observed"`, `"+observed" in travel_source` |
| `outcomes` | list | 그 주 지난 일정 하나씩, 시작 순(아래) |
| `summary` | string | 요약 문장 |
| `summary_source` | `"llm"` \| `"template"` | 요약 출처 |
| `calibration` | 기존 | **그 주 일정만**으로 계산하게 고침(§1 결함) |

`outcomes[]` (예: 화요일 지각):

```json
{"event": 412, "title": "자료구조", "start_at": "2026-10-07T00:00:00Z",
 "date": "2026-10-07", "weekday": 1, "result": "late", "slack_minutes": -10.0,
 "on_time_probability": 90, "tau": 0.9, "tau_bucket_lower": 0.9, "prep_applies": true,
 "planned": {"alarm_at": "2026-10-06T22:40:00Z", "depart_by": "2026-10-06T23:08:00Z",
             "arrive_at": "2026-10-06T23:50:00Z", "prep_minutes": 28, "travel_minutes": 42,
             "buffer_minutes": 10},
 "actual": {"depart_at": "2026-10-06T23:15:00Z", "arrive_at": "2026-10-07T00:10:00Z",
            "prep_minutes": 34.0, "travel_minutes": 55.0},
 "deviation": {"prep_over": 6.0, "depart_late": 7.0, "travel_over": 13.0},
 "cause": "travel_over", "unmeasured": []}
```

- `result`: `on_time`(여유 ≥ 0) / `late` / `unobserved`(도착 관측 없음, `slack_minutes` null).
- `planned`: 계획이 없거나 OK 가 아니면 객체는 두고 값은 전부 null. `prep_applies`: 계획 없음 null, `prep_source == "not_from_home"` false.
- `actual.prep_minutes` = 그 일정의 병렬 아닌 블록 관측 합(없으면 null). `travel_minutes` 는 출발·도착이 다 있을 때만.
- `deviation` 은 지금 `lateness_causes` 정의 그대로(측정 못 하면 null). `prep_applies == false` 면 `prep_over` null.
- `cause` = 지각일 때 `LatenessCause.label`, 아니면 null. `unmeasured` 는 지각일 때만 채우고 아니면 `[]`.
- `tau_bucket_lower` = `bucket_for(tau)[0]`(tau 없으면 null). 구간 계산은 서버가 한다.
- **좌표·장소·주소는 내리지 않는다**(N19). `title` 은 본인 일정이라 내린다(일정 API 와 같은 범위).

호환: 새 앱 + 구버전 서버 → §2-4 "구버전 서버" 행. 구버전 앱 + 새 서버 → 모르는 키 무시. `check_app_contract.py` 는 배포 전 새 필드를 "없음" 으로 보고한다(정상).

## 4. 서버

### 4-1. `be/apps/reports/services.py` 정리

- 한 번 모아 나눠 쓴다. `weekly()` 는 그 주 `collect_outcomes` 1회, 블록 관측 1회(`_block_minutes(user, event_ids)` — 지금 `lateness_causes` 안의 "병렬 제외 합" 을 함수로 뺌), 앞 주 `_week_counts(user, start, end)` 1회.
- `_measure(outcome, block_minutes) -> dict` — prep_over·travel_over·depart_late, 정의는 지금 그대로. 새 규칙 하나: `plan.prep_source == PREP_SOURCE_NOT_FROM_HOME`(`planning.estimators` 상수)이면 prep_over = None(준비가 해당 없는 일정은 재지 않음). `lateness_causes(user, since)` 는 공개 그대로 두고 내부에서 `_measure` 를 쓴다(기존 테스트 유지).
- `calibration_from(outcomes) -> dict` 로 버킷 계산을 빼고, `calibration(user, since)` = `calibration_from(collect_outcomes(user, since=since))`. `weekly` 의 `calibration` = `calibration_from(그 주 outcomes)`.
- 새 내부 함수: `_outcome_row`, `_deviation_stats`(평균·`statistics.pstdev`, 소수 1자리), `_learning`, `_iso(dt)`(UTC `isoformat()` 의 `+00:00` → `Z`).
- `THIN_ARRIVALS = MIN_BUCKET_SAMPLES`(3) — `target_met`·`change_pp`·템플릿 "기록 적음" 이 같은 기준을 쓴다.
- `weekly()` 는 여전히 순수 계산(저장 없음)이고 요약은 뷰에서 붙인다(§4-5). 그래서 `services` 테스트는 LLM 과 무관하다.

### 4-2. 줄글 요약 `be/apps/reports/summary.py` (신규)

`summary_for(user, report) -> (text, source)` — **예외를 밖으로 던지지 않는다**(전체를 `try/except Exception` → `logger.exception` → 템플릿).

```
facts    = build_facts(report)                 # 집계값만
template = render_template(facts)              # 예외면 FALLBACK + logger.exception
if not llm_allowed(report): return template, "template"
digest = sha256(f"{PROMPT_VERSION}|{OPENAI_MODEL}|" + json.dumps(facts, ensure_ascii=False, sort_keys=True))
row = WeeklySummary(user, week_start)
if row and row.facts_hash == digest: return row.text, "llm"
if cache "reports:llm:down" or f"reports:llm:invalid:{digest}": return template, "template"
text, failure = llm.chat(SYSTEM_PROMPT, json.dumps(facts, ensure_ascii=False))
if failure: 백오프(§4-3 표); return template, "template"
clean = normalize(text); reason = check_text(clean, facts)
if reason: invalid 백오프 1시간, WARNING(reason); return template, "template"
WeeklySummary.update_or_create(user, week_start, defaults={text, facts_hash, facts, model})
return clean, "llm"
```

- `llm_allowed(report)` = `llm.configured()` · `week_complete` · `arrived_count >= 1` · `week_start >= 오늘 − 12주`(`LLM_MAX_WEEKS_BACK = 12`, 비용 상한이자 90일 범위). 진행 중·빈·오래된 주는 템플릿만.
- 해시가 다르면(늦게 올라온 관측, 일정 삭제, 모델·프롬프트 변경) 저장된 문장을 **쓰지 않는다** — 낡은 숫자를 보이지 않는다.
- 저장 실패(`DatabaseError`)는 WARNING 후 새 문장을 그대로 내린다. 동시 요청의 `IntegrityError` 는 무시한다. 템플릿은 저장하지 않는다(같은 수치면 늘 같은 문장).

**`build_facts(report)`** — 키 고정(테스트가 키 집합을 단언). 분은 §0 반올림 정수, 비율은 % 정수. 예시 주(10월 5일 ~ 11일: 일정 7, 도착 6, 정시 5, 화요일 10분 지각):

```json
{"week_complete": true, "events": 7, "arrived": 6, "unobserved": 1, "on_time": 5, "late": 1,
 "on_time_pct": 83, "target_pct": 90, "target_met": false, "thin_data": false,
 "median_slack_min": 9, "tightest_slack_min": -10, "late_max_min": 10, "late_max_weekday": "화",
 "late_causes": {"이동 초과": 1}, "top_cause": "이동 초과", "top_cause_count": 1, "top_cause_mean_min": 13,
 "prep_over_mean_min": 4, "prep_over_n": 2, "depart_late_mean_min": 3, "depart_late_n": 5,
 "travel_over_mean_min": -1, "travel_over_n": 5, "travel_over_sd_min": 7,
 "prev_on_time_pct": 60, "on_time_change_pp": 23,
 "suggestion": "이동이 계획보다 길어지는 날이 있었으니 다른 경로를 골라 보거나 몇 분 일찍 나서 볼 것."}
```

- 원인 이름: prep_over "준비 초과", travel_over "이동 초과", depart_late "늦은 출발", plan_too_tight "계획 여유 부족", unknown "원인 측정 못 함". `late_causes` 는 전부 센다. `top_cause` = **측정된** 원인(앞 넷) 중 건수 최대, 동률은 이동 초과 → 늦은 출발 → 준비 초과 → 계획 여유 부족 순. 측정된 것이 없으면 "원인 측정 못 함". `top_cause_mean_min` = 그 원인으로 늦은 일정들의 그 값 평균(계획 여유 부족·측정 못 함이면 null).
- `late_max_*` = 여유가 가장 작은 지각 일정.
- **넣지 않는 것**: 제목·장소·주소·좌표·날짜·시각·일정 id·이메일·닉네임·태그 이름. `outcomes` 에서는 `result`·`slack_minutes`·`weekday`·`cause`·`deviation` 만 읽는다.

**템플릿 `render_template(facts)`** — 문장 슬롯 넷(개요 → 지각 → 하나 → 제안). 결과는 3~5문장.

1. 개요: `events == 0` "이 주에는 지난 일정이 없음." / `arrived == 0` "이 주 일정 {events}건 모두 도착 기록이 없어 정시 여부를 채점하지 못했음." / 기록 적음 "이 주 도착을 기록한 {arrived}번 중 {on_time}번 정시 도착했지만 기록이 적어 비율로 판단하기는 이름." / 그 밖 "이 주 {머리} {on_time}번 정시 도착해 정시율 {pct}%{꼬리}". 머리 = `events == arrived` 면 "도착을 기록한 {arrived}번 중", 아니면 "일정 {events}건 중 도착을 기록한 {arrived}번에서". 꼬리 = 달성 "로 목표 {t}%를 달성했음." / 미달 "로 목표 {t}%에 못 미쳤음." / 목표 없음 "였음.". 진행 중이면 맨 앞에 "진행 중인 주라 지금까지 기록만 요약함." 을 붙인다.
2. 지각(도착 기록이 있을 때만): 지각 0 — 가장 아슬아슬 t < 5 면 "지각은 없었지만 가장 아슬아슬한 날은 {t}분 전에 도착했음."(t = 0 이면 "…날은 일정 시작에 딱 맞춰 도착했음."), 아니면 "지각은 없었음." / 측정된 원인 없음 — "지각 {L}번은 기록이 부족해 원인을 나누지 못했음." / 그 밖(k = `top_cause_count`, m = `late_max_min`) — L = 1 "지각 1번은 {이유} 때문이었고 {요일}요일에 {m}분 늦었음.", L = k "지각 {L}번은 모두 {이유} 때문이었고 가장 많이 늦은 날은 {요일}요일 {m}분이었음.", 그 밖 "지각 {L}번 중 {k}번은 {이유} 때문이었고 가장 많이 늦은 날은 {요일}요일 {m}분이었음.". 이유: 이동 초과 "이동이 계획보다 오래 걸린 것", 늦은 출발 "집에서 늦게 나선 것", 준비 초과 "준비가 길어진 것", 계획 여유 부족 "계획의 여유가 부족했던 것".
3. 하나만: `on_time_change_pp` 가 있으면 추세 — 양수 "지난주 {p}%보다 {c}%p 올랐음.", 음수 "지난주 {p}%보다 {|c|}%p 내렸음.", 0 "지난주와 정시율이 같았음." / 없으면 n ≥ 2 · 평균 ≥ 3 인 것 중 가장 큰 것 — 이동 "이동은 계획보다 평균 {m}분 더 걸렸음({n}번 기록).", 출발 "출발은 계획보다 평균 {m}분 늦었음({n}번 기록).", 준비 "준비는 계획보다 평균 {m}분 더 걸렸음({n}번 기록)." / 그것도 없고 이동 sd ≥ 5 · n ≥ 3 이면 "이동 시간 편차가 {sd}분으로 큰 편이었음." / 아니면 없음.
4. 제안 = `suggestion`(위에서부터 먼저 맞는 것):
   - a. events 0 → "일정을 추가해 두면 다음 주부터 알람과 함께 기록이 쌓임."
   - b. arrived 0 또는 unobserved × 2 ≥ events → "위치 권한을 켜 두고 알람을 해제한 뒤 이동하면 출발·도착이 자동으로 기록됨."
   - c. 지각 있음 · top 늦은 출발 → m = `top_cause_mean_min` ≥ 1 이면 "다음 주에는 지금보다 {m}분 먼저 문을 나서 볼 것.", 아니면 "다음 주에는 알람이 정한 출발 시각을 지켜 볼 것."
   - d. top 준비 초과 → "오래 걸린 준비 항목의 시간 범위를 루틴에서 늘려 두면 알람이 그만큼 앞당겨짐."
   - e. top 이동 초과 → "이동이 계획보다 길어지는 날이 있었으니 다른 경로를 골라 보거나 몇 분 일찍 나서 볼 것."
   - f. top 계획 여유 부족 → "계획대로 움직여도 늦은 날이 있었으니 평소 준비 시간을 넉넉히 잡아 알람을 앞당겨 볼 것."
   - g. 지각 있음 · 측정된 원인 없음 → "원인을 나누려면 출발·도착 기록이 필요하니 위치 권한을 켜 두고 이동할 것."
   - h. 지각 0 · 가장 아슬아슬 < 5분 → "아슬아슬한 날이 있었으니 지금처럼 출발 시각을 지키는 것이 좋음."
   - i. 그 밖 → "지금 설정을 그대로 유지해도 좋음."

예(위 facts): "이 주 일정 7건 중 도착을 기록한 6번에서 5번 정시 도착해 정시율 83%로 목표 90%에 못 미쳤음. 지각 1번은 이동이 계획보다 오래 걸린 것 때문이었고 화요일에 10분 늦었음. 지난주 60%보다 23%p 올랐음. 이동이 계획보다 길어지는 날이 있었으니 다른 경로를 골라 보거나 몇 분 일찍 나서 볼 것."

FALLBACK(템플릿이 예외): "이 주 기록을 문장으로 정리하지 못했음. 아래 수치를 확인할 것."

**프롬프트** `SYSTEM_PROMPT`(`PROMPT_VERSION = 1`, 고치면 올린다 → 해시가 바뀌어 다시 만든다):

```
너는 알람 앱의 주간 리포트를 쓰는 도우미다. 사용자 메시지의 JSON 은 한 사용자의 한 주 기록을 집계한 값이다. 이 값만 근거로 한국어 요약 한 문단을 쓴다.
1. 3~4문장, 300자 이내. 제목·목록·마크다운·이모지 없이 평문 한 문단.
2. 문장은 '~함', '~음', '~임' 으로 끝낸다.
3. 숫자는 JSON 에 있는 값만 그대로 쓴다. 새로 계산하거나 날짜·시각을 만들지 않는다.
4. *_slack_min 은 일정 시작보다 일찍 도착한 분(음수면 늦은 분), *_mean_min 은 계획보다 더 걸리거나 늦은 분(음수면 덜 걸리거나 일찍)이다.
5. null 은 기록이 없다는 뜻이다. 0 으로 읽지 말고 언급하지 않거나 '기록이 없음' 이라고만 쓴다.
6. 순서: 정시율과 목표 → 지각과 주원인 → 지난주 대비 변화나 계획 대비 차이 중 하나 → 마지막 문장은 suggestion 의 뜻을 바꾸지 않고 다듬은 제안 하나.
7. '학습 중' 이라는 말을 쓰지 않는다. 사용자를 탓하지 않는다. 없는 앱 기능을 말하지 않는다.
```

**검증** `check_text(text, facts) -> 실패 이유 | None`. 먼저 `normalize`(앞뒤 따옴표·공백 제거, `*`·`#`·`` ` `` 삭제, 줄바꿈·연속 공백 → 공백 한 칸):
- 40~400자, 한글 음절이 하나 이상. "학습 중"·"학습중"·"http" 없음.
- **숫자**: `\d+(?:\.\d+)?` 로 뽑은 숫자가 전부 `allowed_numbers(facts)` 안에 있어야 한다. 허용 = facts 의 모든 수(bool 제외)의 절댓값 정수 표기 + 문자열 값(`suggestion`, `late_causes` 값)에 든 숫자. 하나라도 없으면 `invented_number:<값>`.
- 템플릿 출력도 이 검사를 통과해야 한다(§7 테스트).

### 4-3. LLM 클라이언트 `be/apps/reports/llm.py` (신규, `routing/clients.py` 방식)

- 상수 `DEFAULT_BASE_URL = "https://api.openai.com/v1"`, `TIMEOUT_SECONDS = 8`, `_urlopen = urllib.request.urlopen`(테스트가 바꿔 끼우는 자리).
- `endpoint() -> str | None`: `settings.OPENAI_BASE_URL` 의 공백·끝 `/` 를 지우고 끝이 `/chat/completions` 면 떼어 낸 뒤, `https://<host>` 또는 `http://localhost`·`http://127.0.0.1` 일 때만 `+ "/chat/completions"`. 그 밖은 None(원격 평문 http 로 키를 보내지 않음).
- `configured()` = 키·모델이 비어 있지 않음(주소는 보지 않는다 — 잘못된 주소는 `chat()` 이 `bad_base_url` 로 알려 로그에 남게 한다).
- `chat(system, user) -> (text | None, failure | None)` — 예외를 던지지 않는다. 먼저 `endpoint()` 가 None 이면 요청 없이 `bad_base_url`. POST `{"model": OPENAI_MODEL, "messages": [{"role": "system", …}, {"role": "user", …}]}`. **`temperature`·`max_tokens` 는 보내지 않는다**(추론형 모델이 거부해 늘 실패한다. 길이는 프롬프트와 검증이 막음). 헤더 `Authorization: Bearer <key>`, `Content-Type: application/json`. `ssl.create_default_context()`, timeout 8초. 답 = `choices[0].message.content`(문자열. 목록이면 `text` 조각을 이음).
- **키·요청 본문·응답 본문을 로그에 남기지 않는다**(OpenAI 오류 본문에 가린 키 일부가 들어간다). 상태 코드와 응답 `error.code` 만 남긴다.

실패 처리(전부 복구 가능, 사용자는 템플릿을 받는다. 백오프는 `default` 캐시):

| failure | 조건 | 백오프 | 로그 |
| --- | --- | --- | --- |
| `not_configured` | 키·모델 없음 | 시도 안 함 | 없음(키가 올 때까지 정상) |
| `bad_base_url` | 키·모델은 있는데 `endpoint()` None | 전역 1시간 | WARNING |
| `timeout` | 8초 초과 | 전역 10분 | WARNING |
| `network` | DNS·연결·TLS 실패 | 전역 10분 | WARNING |
| `http_429` · `http_5xx` | 한도·서버 오류 | 전역 10분 | WARNING |
| `http_4xx` | 400·401·403·404 등 | 전역 1시간 | ERROR(설정 오류) |
| `bad_response` | JSON 아님·`choices` 없음·내용 빔 | 전역 10분 | WARNING |
| `invalid_output`(summary) | §4-2 검증 실패 | 그 해시만 1시간 | WARNING(이유만) |

전역 키 `reports:llm:down` 은 장애 동안 요청마다 8초씩 기다리지 않게 한다. prod `default` 는 워커 공용 파일 캐시라 워커 사이에도 듣는다(지도 캐시와 같은 곳이라 밀려날 수 있지만 그러면 한 번 더 시도할 뿐이다).

### 4-4. 저장 `be/apps/reports/models.py` (리포트 앱의 첫 모델)

```python
class WeeklySummary(models.Model):
    """LLM 이 쓴 주간 요약. 수치는 요청 때 계산하고 여기에는 문장만 둔다."""
    user = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE,
                             related_name="weekly_summaries", verbose_name="사용자")
    week_start = models.DateField("주 시작(월)")
    facts_hash = models.CharField("집계 해시", max_length=64)
    facts = models.JSONField("보낸 집계값", default=dict)
    text = models.TextField("요약")
    model = models.CharField("모델", max_length=100, blank=True, default="", db_default="")
    created_at = models.DateTimeField("생성 시각", auto_now_add=True)
    updated_at = models.DateTimeField("수정 시각", auto_now=True)

    class Meta:
        db_table = "reports_weekly_summary"
        constraints = [models.UniqueConstraint(
            fields=["user", "week_start"], name="report_summary_user_week_unique")]
```

- 왜 DB 인가: Render 무료 플랜은 잠들거나 배포할 때 `/tmp` 파일 캐시가 비워진다. 캐시에만 두면 깨어날 때마다 다시 부르고 **같은 주의 요약이 열 때마다 바뀐다.** DB 는 문장을 고정하고 무엇을 보냈는지(`facts`)를 남긴다("앱에서 오가는 데이터는 DB 에 기록" 요청과도 맞음).
- `migrations/0001_initial.py`(makemigrations), `admin.py` 읽기 전용 등록(user·week_start·model·updated_at). 컨테이너 시작 때 `migrate` 가 돈다(Dockerfile).
- `config/settings/base.py` 의 `apps.reports` 주석 "**모델이 없다**" → "요약 문장(`WeeklySummary`)만 저장한다. 수치는 요청 때 계산" 으로 고친다.

### 4-5. 뷰·설정·배포

- `views.WeeklyReportView.get`: `report = services.weekly(...)` → `report["summary"], report["summary_source"] = summary.summary_for(request.user, report)`. `CalibrationView` 는 그대로.
- `base.py`: `OPENAI_BASE_URL = (os.getenv("OPENAI_BASE_URL") or "https://api.openai.com/v1").strip()` — **빈 문자열도 기본값**(Render 가 빈 값을 둘 수 있음, `DATABASE_URL` 주석과 같은 함정). OpenAI 주석을 "주간 리포트 요약(P6) · F12 자연어 파싱(미착수)" 으로.
- `test.py` 는 이미 `OPENAI_API_KEY = ""`, `OPENAI_MODEL = ""`(그대로). `apps/common/tests/test_settings.py` 의 빈 키 목록에 `OPENAI_MODEL` 추가.
- `be/.env.example`: OpenAI 절 제목을 "OpenAI 호환 LLM — 주간 리포트 요약 · F12" 로 바꾸고 아래를 더한다.

  ```
  # 셋 다 비어 있어도 서버는 돈다. 리포트 요약은 같은 수치로 만든 고정 문장으로 나간다.
  # 키는 서버에만 둔다. 앱(APK)에 넣지 않는다(N20).
  # 리포트 요약은 일반 chat completions 만 쓴다. 8초 안에 답하는 경량 대화형 모델을 고른다
  # (추론형 모델은 늦어서 고정 문장으로 떨어지기 쉽다).
  # OpenAI 호환 API 주소. 끝의 /chat/completions 는 서버가 붙인다. 비우면 OpenAI 기본 주소.
  # https 만 쓴다(localhost 예외). 학교 제공 주소를 받으면 여기에 넣는다.
  OPENAI_BASE_URL=
  ```
- `render.yaml` `envVars`: `OPENAI_API_KEY`·`OPENAI_MODEL`·`OPENAI_BASE_URL` 을 모두 `sync: false` 로 추가(주석: 대시보드에서 직접 넣음, 비우면 고정 문장).
- `README.md` 설정 표에 OPENAI 세 값 = "Weekly report summary (optional; without them a fixed template is used)" 행을 넣고 "reserved for planned features" 문장에서 OPENAI 를 뺀다.
- 버전: `APP_VERSION = "0.11.0"`(이력 주석 "0.11.0 주간 리포트 줄글 요약·상세 리포트(outcomes)"), 앱 `versionName = "0.11.0"`(주석 줄 추가). 앱이 서버보다 새면 `ServerVersion` 이 알린다.
- 확인 명령 `manage.py probe_report_summary`(신규): 코드에 박은 표본 facts(실제 사용자 데이터 아님)로 `llm.chat` → `check_text` 를 한 번 돌려 설정 여부·엔드포인트 호스트·걸린 시간·검증 결과·문장을 출력한다(키는 출력하지 않음). 실패면 종료 코드 1. 학교 키를 넣은 뒤 이것 하나로 확인한다. `scripts/check_external_apis.py` 5절의 고정 주소 `https://api.openai.com/v1/models` 는 `OPENAI_BASE_URL` 기준으로 바꾼다.
- `scripts/check_app_contract.py` 6.7 에 `OutcomeDto`·`PreviousWeekDto` 필드 비교와 "`summary_source` 가 llm/template 중 하나" 검사, `check_deployed.py` 14절에 `summary`·`summary_source` 존재 검사를 더한다.
- 스로틀은 더하지 않는다. LLM 호출은 (사용자, 주, 수치)당 한 번이고 최근 12주·도착 기록이 있는 주로 묶여 사용자당 상한이 작다.

### 4-6. 입력 검증

| 입력 | 필수 | 형·한계 | 실패 시 |
| --- | --- | --- | --- |
| `?week=` | 아님 | `YYYY-MM-DD` | 지난 주(지금 그대로, 200) |
| `OPENAI_API_KEY`·`OPENAI_MODEL` | LLM 쓸 때 | 비어 있지 않은 문자열 | 템플릿, 로그 없음 |
| `OPENAI_BASE_URL` | 아님 | http(s) URL, 원격은 https, 끝 `/`·`/chat/completions` 허용 | `bad_base_url`(§4-3) |
| LLM 응답 | — | §4-2 검증 | `invalid_output`(§4-3) |

## 5. 앱

### 5-1. DTO `data/remote/ReportsApi.kt` (null-safe)

- 기존 `WeeklyReportDto`: `weekStart`/`weekEnd` → `String?`, `byWeekday`·`lateCauses`·`primaryCounts`·`calibration` → nullable(기본값 null), 매핑에서 `.orEmpty()`. 정수 개수 필드는 그대로(Gson 이 0 으로 둠).
- 새 필드(전부 nullable, 기본값 null, `@SerializedName` = 서버 키): `weekComplete: Boolean?`, `hasNextWeek: Boolean?`, `targetRate: Double?`, `targetMet: Boolean?`, `previousWeek: PreviousWeekDto?`, `deviation: WeeklyDeviationDto?`, `learning: LearningDto?`, `outcomes: List<OutcomeDto>?`, `summary: String?`, `summarySource: String?`.
- 새 DTO: `PreviousWeekDto`, `DeviationStatDto(count, meanMinutes, sdMinutes)`, `WeeklyDeviationDto(prepOver, departLate, travelOver)`, `LearningDto(planCount, prepFromRecords, travelFromRecords)`, `OutcomeDto(event, title, startAt, date, weekday, result, slackMinutes, onTimeProbability, tau, tauBucketLower, prepApplies, planned, actual, deviation, cause, unmeasured)`, `PlannedDto`, `ActualDto`, `OutcomeDeviationDto`. 문자열·목록·객체·소수는 nullable, 개수는 `Int = 0`.
- 상수 `SOURCE_LLM = "llm"`, `RESULT_ON_TIME/LATE/UNOBSERVED`. 모르는 값: `result` → 기록 없음, `cause` → unknown, `summary_source` → 템플릿.
- 앱에는 OpenAI 관련 코드·설정·BuildConfig 필드를 만들지 않는다(N20).

### 5-2. 도메인 모델과 매핑

- 매핑을 `data/repository/ReportMapping.kt`(신규, `internal`, 안드로이드 의존 없음 → JVM 테스트)로 옮기고 `ReportRepository` 는 부르기만 한다. 지금의 `percent`·`minutes`·`slackPhrase`·원인 헤드라인 문장도 이 파일로.
- `domain/model/WeeklyReport.kt`:
  - `WeeklyReportView` — `medianSlackLabel`·`tightestSlackLabel`·`weekdays`·`causes`·`calibration` 을 빼고 더한다: `weekStart: LocalDate?`, `canGoNext`, `inProgress`, `summary: ReportSummary?`(`text`, `isAi`), `onTimeCountLabel: String?`("5/6"), `onTimeTone`, `onTimeSubLabel: String?`, `slackValueLabel: String?`, `slackTone`, `slackSubLabel: String?`, `coverageLabel: String?`, `days: List<ReportDay>`(outcomes 가 없으면 빈 목록), `lateDays: List<LateDayLine>`, `hasUnknownCause`, `deviations: List<DeviationLine>`(deviation 이 없으면 빈 목록), `learningLabel: String?`. `observationGapMatters`·`hasArrivals` 는 유지.
  - `enum class Tone { GOOD, WARN, BAD, NEUTRAL, UNKNOWN }` → 화면이 Green/Amber/Red/TextPrimary/TextSecondary 로 그린다.
  - `ReportDay(date, label, status: DayStatus, slackMinutes: Int?, valueLabel, description, outcomes: List<MorningOutcome>)`, `enum DayStatus { NONE, UNOBSERVED, ON_TIME, LATE }`(지각이 하나라도 있으면 LATE), `representative`(가장 작은 여유, 없으면 첫 일정). 7칸은 `weekStart` + 0..6, `outcomes` 는 서버 `date` 로 묶는다. `weekStart` 나 `date` 를 못 읽으면 그 칸·일정은 빠진다.
  - `MorningOutcome(eventId, chipLabel, result, headline, contextLine, confidenceLine?, rows: List<PlanActualRow>, cause: CauseExplanation?, tauBucketLower?, slackMinutes?, isOnlyLateOfWeek)`.
  - `PlanActualRow(label, kind: DURATION|TIME, plannedLabel?, actualLabel?, deltaLabel, tone, plannedFraction?, actualFraction?)`, `CauseExplanation(title, body, action)`, `LateDayLine(date, eventId, dateLabel, lateLabel, causeTitle)`, `DeviationLine(label, valueLabel, tone)`.
  - `CalibrationPoint` 에 `lower: Double? = null`(⑨ 강조용, 기본값이라 기존 호출 유지).
- `ReportRepository` 는 **캐시하지 않는다**(지금 결정 유지). 새 로컬 저장소가 없으므로 `PrefsScopeAuditTest`·`CacheScopeAuditTest` 영향 없음.

### 5-3. 화면 파일

- `ui/report/WeeklyReportScreen.kt` — §2-1. 파라미터 `onOpenDay: (LocalDate, Long?) -> Unit` 추가, KDoc "Figma ⑧".
- `ui/report/ReportDayScreen.kt`(신규) — `ReportDayScreen(report: WeeklyReportView?, longTerm: CalibrationView?, date: LocalDate, initialEventId: Long?, onBack: () -> Unit, modifier: Modifier = Modifier)`.
- `ui/report/ReportParts.kt`(신규, `internal`) — 두 화면 공용 `ToneText`, `CalibrationTable`, 그리고 지금 private 인 `ErrorCard`·`LoadingBlock`·`NoticeCard`.
- `ui/common/JitCard.kt` `container` 파라미터, `ui/events/AddEventScreen.kt` `ScreenHeader(…, trailing: String? = null)`, `ui/theme/Tokens.kt` `JitColor.SurfaceDanger = Color(0xFF4A2121)`(KDoc "지각 결과 카드. Figma `color/surface-danger`").
- `ui/nav/AppRoute.kt`, `ui/home/HomeViewModel.kt`(`openReportDay`), `MainActivity.kt`(`is AppRoute.ReportDay -> ReportDayScreen(…)`, ⑧ 에 `onOpenDay = viewModel::openReportDay`).
- 새·고친 함수는 지금 규칙대로 `// AI-generated (Claude)` / `// Partly AI-generated (Claude)`. 서버 새 모듈은 머리에 `# AI-generated (Claude)` 한 줄.
- 프리뷰: ⑧ 예시 주, ⑧ 기록 없는 주, ⑨ 지각·정시·기록 없음.

### 5-4. 앱 오류 처리

| 상황 | 결과 | 로그 |
| --- | --- | --- |
| 네트워크·401·5xx | 지금 `ErrorCard`("불러오지 못했음" + 메시지 + 눌러서 다시 시도). 보던 주가 있으면 그 위에 카드 | `ReportRepository.guard` 그대로(IOException `Log.w`, 그 밖 `Log.e`) |
| 90일 캘리브레이션 실패 | 예측 검증 카드만 없음 | 지금 `Log.w` |
| 키 누락·null·모르는 enum | 해당 카드·줄을 숨기거나 중립 문구(§5-1). 터지지 않음 | 없음 |
| 시각·날짜 파싱 실패 | 그 조각만 뺌(`runCatching`) | 없음 |
| ⑨ 날짜를 못 찾음 | "이 날의 기록을 찾지 못했음" | 없음 |

## 6. 불변식과 책임

| 불변식 | 지키는 곳 | 이유 |
| --- | --- | --- |
| 프롬프트에 개인정보·좌표 없음 | 서버 `summary.build_facts`(고정 키) | 보내는 곳이 하나라 한 곳에서 막는다. 테스트가 키 집합과 보낸 본문을 단언 |
| LLM 문장에 없는 숫자 없음 | 서버 `summary.check_text` | 숫자 출처(facts)를 아는 곳이 서버다. 앱은 문장 내용을 모른다 |
| 낡은 요약을 보이지 않음 | 서버 `facts_hash` 비교 | 수치가 바뀌면 문장도 바뀌어야 한다 |
| 측정 못 함 ≠ 0 | 서버 `_measure`(None) + 앱 매핑("측정 안 됨") | AT-19. 서버는 None 을 지우지 않고 앱은 0 으로 바꾸지 않는다 |
| 정시·지각·원인·목표 판정, τ 구간 | 서버(`result`, `cause`, `target_met`, `tau_bucket_lower`) | 규칙은 서버 소유(task.md 원칙). 앱은 최솟값·개수만 집계 |
| 키는 서버에만 | 서버 설정 + render `sync: false` | N20 |
| 테스트가 LLM 을 부르지 않음 | `test.py` 빈 키 + 리포트 테스트 autouse 가드 | 비용·깜빡임 |
| 주 이동 상한(이번 주) | 서버 `has_next_week` | 서버 시간대 기준 |

## 7. 테스트

서버(`backend` 에서 `python -m pytest`, 키·네트워크 없음):
- `apps/reports/tests/conftest.py`(신규, autouse): `llm._urlopen` 을 "테스트가 LLM 을 불렀다" `AssertionError` 를 던지는 함수로 바꾸고 `cache.clear()`. 성공 경로가 필요한 테스트만 가짜 opener 로 다시 바꾼다(네트워크 없음).
- `test_reports.py` 추가: outcomes 한 줄(위 예시 수치로 planned/actual/deviation/cause 단언), `not_from_home` → `prep_applies` false·`prep_over` null, deviation 평균·sd·count 0 → null, previous_week·`change_pp`(3건 미만 null), target_rate·target_met(3건 미만 null), `week_complete`·`has_next_week`(지난 주 true/true, 이번 주 false/false), learning 세 수, **주간 calibration 이 다음 주 일정을 세지 않음**(결함 회귀), 응답에 `summary` 와 `summary_source == "template"`, 응답 어디에도 `lat`·`lng`·`address` 키 없음.
- `test_summary.py`(신규): facts 키 집합 고정, 제목 "비밀 회의"·장소 주소가 `json.dumps(facts)` 에 없음. 템플릿: 빈 주, 기록 없음, 기록 적음, 지각 없음·아슬아슬, 원인 넷, 측정된 원인 없음, 진행 중 → 문장 단언. **모든 템플릿 출력이 `check_text` 를 통과**하고 "학습 중" 이 없음. 검증이 지어낸 숫자·"학습 중"·400자 초과·URL 을 거절. `summary_for`: 설정 없음 → template + opener 미호출 / `settings` 로 키·모델 설정 + 가짜 opener → llm 저장, 두 번째는 opener 미호출(DB), 수치가 바뀌면 다시 호출 / 진행 중·도착 0·13주 전 → template + 미호출 / 시간 초과 → template, 다음 요청 미호출(백오프) / 401 → template / 검증 실패 → template. 가짜 opener 가 받은 요청: URL = 기본 주소 + `/chat/completions`, `Authorization: Bearer`, 본문에 제목·장소·`"event"`·날짜 없음, `temperature`·`max_tokens` 없음.
- `test_llm.py`(신규): `endpoint()` — 빈 값 → 기본, 끝 `/`·`/chat/completions`, 원격 http 거절, localhost http 허용. `chat()` — 원격 http 주소면 opener 를 부르지 않고 `bad_base_url`, 실패 매핑 HTTPError 401·429·500, URLError, `TimeoutError`, JSON 아님, `choices` 없음, 목록 content.
- `probe_report_summary`: 설정이 없으면 "설정 없음" 을 출력하고 opener 를 부르지 않음. `test_settings.py`: `OPENAI_MODEL` 빈 값.

앱(`APP` 에서 `./gradlew :app:testDebugUnitTest`):
- `data/repository/ReportMappingTest.kt`(신규): 예시 응답 JSON → Gson → `toView()`. ⑧: "5/6", "정시 도착 83%", "목표 90% · 지난주 3/5", "9분 일찍", "가장 늦은 날 10분 늦음", "기록 6/7건", 7칸(월 +12, 화 −10, 수 +3, 목 +9, 금 기록 없음, 토·일 NONE), 지각한 날 한 줄, 계획 대비 실제 세 줄, 기록 반영 문장, `summary.isAi`. ⑨(시간대 `Asia/Seoul` 주입): 준비 "+6분"·비율 28/34, 출발 "계획 8:08 → 실제 8:15", 이동 "+13분", 도착, 원인 제목·설명·할 일, `isOnlyLateOfWeek`.
- 구버전 응답(새 키 없음) → 요약 null, days 빈 목록, `canGoNext` true. 새 키 전부 null·모르는 enum → 중립.
- `NetworkDtoNullSafetyTest` 추가: `{}` 와 `{"outcomes":[{}]}` 의 `WeeklyReportDto` 가 `toView()` 까지 터지지 않음.
- `domain/model/WeeklyReportTest.kt`: `WeekdayLoadTest` → `ReportDayTest`(대표 = 가장 작은 여유, 지각 우선, 기록 없음, 일정 없음은 누를 수 없음), `WeeklyReportViewTest` 를 새 생성자에 맞춤. `UnreliableCopyAuditTest` 는 그대로(새 문구도 감사).
- 계측 `DemoFlowTest` 10단계: `find(By.text("1/1"), 45_000)` + `By.text("정시 도착 100%")`. 진행 중인 주라 요약은 템플릿이다(네트워크 LLM 없음).

자동화하지 못하는 것: 실제 LLM 문장 품질(키가 온 뒤 `probe_report_summary` 와 화면으로 사람이 확인), Compose 배치(프리뷰·에뮬레이터 눈 확인).

빌드 확인: `backend` `python -m pytest`, `APP` `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`.

## 8. 구현 순서와 파일

1. Figma(F-10 ~ F-13) — 메인 세션 몫. 코드는 명세 기준으로 먼저 간다(Phase A 와 같음).
2. 서버 `services.py` 정리·결함 수정·새 필드(테스트 먼저).
3. `llm.py`·`summary.py`·`models.py`·마이그레이션·뷰·설정·배포 파일·확인 명령.
4. 앱 DTO·매핑·도메인 + 단위 테스트.
5. ⑧·⑨ 화면, 내비게이션, 토큰·헤더·카드 파라미터, `DemoFlowTest`.
6. 문서: `task.md` 에 "8·9. 리포트" 절, `backend-requests.md` R-4 닫음, README.

- 서버 추가: `apps/reports/{summary.py, llm.py, models.py, admin.py, migrations/__init__.py, migrations/0001_initial.py, management/__init__.py, management/commands/__init__.py, management/commands/probe_report_summary.py, tests/conftest.py, tests/test_summary.py, tests/test_llm.py}`.
- 서버 수정: `apps/reports/{services.py, views.py, tests/test_reports.py}`, `config/settings/base.py`, `apps/common/tests/test_settings.py`, `.env.example`, `README.md`(루트), `render.yaml`(루트), `scripts/{check_app_contract.py, check_deployed.py, check_external_apis.py}`.
- 앱 추가: `data/repository/ReportMapping.kt`, `ui/report/{ReportDayScreen.kt, ReportParts.kt}`, `test/.../data/repository/ReportMappingTest.kt`.
- 앱 수정: `data/remote/ReportsApi.kt`, `data/repository/ReportRepository.kt`, `domain/model/WeeklyReport.kt`, `ui/report/WeeklyReportScreen.kt`, `ui/nav/AppRoute.kt`, `ui/home/HomeViewModel.kt`, `MainActivity.kt`, `ui/common/JitCard.kt`, `ui/events/AddEventScreen.kt`, `ui/theme/Tokens.kt`, `app/build.gradle.kts`, `test/.../domain/model/WeeklyReportTest.kt`, `test/.../data/remote/NetworkDtoNullSafetyTest.kt`, `androidTest/.../DemoFlowTest.kt`.
- 사람이 할 일: 메인 체크아웃 `backend/.env` 에 `OPENAI_BASE_URL=` 줄 추가(이 worktree 에는 `.env` 가 없고, 메인 체크아웃은 이 작업에서 고치지 않음). 학교 키·주소가 오면 `.env` 와 Render 대시보드에 세 값을 넣고 `manage.py probe_report_summary` 를 한 번 돌린다.

## 9. 범위 밖 · 백로그

- `wake` 관측(⑨ 기상 지연 줄), 알람 수락·스누즈 기록(⑧ 신뢰 지표): 기록이 생기면 같은 자리에 붙인다. DB 정리 작업의 후보.
- 수면(B-9), 요일별 자동 조정, 고정 버퍼 대조군(AT-21, "도입 전 → 현재").
- `ModelArtifact` 노출(R-4): 알람 계산이 읽지 않으므로 하지 않는다.
- ⑨ 하루 단위 LLM 문장, 요약 배치 생성·푸시 알림, 요약 비동기 생성.

**Figma 상태: MCP 로 편집하지 못함.** 이 환경에 Figma MCP 도구가 없다. ⑧ `1:137`·⑨ `16:2` 수정과 새 프레임 ⑧-a·⑨-a·⑨-b 를 [figma-changes.md](figma-changes.md) Phase B(F-10 ~ F-13)에 프레임별로 적었다. 새 프레임을 만들면 메인 체크아웃의 `docs/instruction.md` §14.3 표와 `Spec/checklist.md` 프레임 표도 함께 고친다(이 worktree 에는 없음).
