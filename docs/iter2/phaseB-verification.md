# Phase B 구현 검증 기록 — 리포트(⑧·⑨)와 주간 줄글 요약

> 이 문서는 해당 Phase의 설계·리뷰·검증 당시 기록이다. 당시 테스트 수·미구현 설명을 현재 상태로 해석하지 않는다. 2026-10-17 후속 통합 상태는 [최신 TODO](../../task.md), [요청별 완료 상태](backend-requests.md), [최신 검증](account-ranges-and-travel-counts.md)을 따른다.

- 일시: 2026-10-13. worktree `SWPP-PJ-front-iter2`, 브랜치 `front-iter2`, 기준 `5a41095`. **커밋하지 않음**(승인 뒤 다음 단계에서 커밋).
- 기준 설계: [report-design.md](report-design.md) + 설계 리뷰 [phaseB-design-review.md](phaseB-design-review.md) 의 고침 + 구현 리뷰 [phaseB-review.md](phaseB-review.md) 6건의 고침(아래 "구현 리뷰 반영"·"설계와 다르게 한 것"). 버전 0.10.0 → **0.11.0**(서버 `APP_VERSION`, 앱 `versionName`·`versionCode 11`).

## 실행한 명령과 결과

구현 리뷰 6건을 고친 뒤 다시 돌린 결과다. 괄호 안은 1차 구현(리뷰 전)과 작업 전.

백엔드 (`backend`, 인터프리터 `SWPP-PJ\backend\.venv`):

```
python -m pytest -q
1040 passed, 11 warnings in 8.42s        (1차 1032, 작업 전 865)

python scripts\run_local_suite.py
스크립트 8개 · 실패 0개
  check_app_contract.py  통과 94 · 실패 0 · 건너뜀 4   (작업 전 88 · 0 · 2)
```

- 경고 11건은 작업 전과 같은 JWT 테스트 키 길이 경고다.
- 키·네트워크 없이 돈다. 리포트 테스트는 `llm._urlopen` 을 `BaseException` 을 던지는 함수로 막아 두고(`apps/reports/tests/conftest.py`), 성공 경로는 가짜 opener 로만 본다. `test.py` 가 `OPENAI_BASE_URL` 도 고정한다. conftest 는 테스트마다 `default`·`llm_budget` 캐시를 비운다.
- 늘어난 8건: 리다이렉트 요청에 키 없음(301·302·303), `llm_budget` 운영 캐시 설정, 사용자 하루 상한, 상한 캐시 고장 시 템플릿, 검사 실패 해시의 긴 휴식, 프롬프트의 지각 0 표현.
- `check_app_contract` 의 늘어난 건너뜀 2건은 새 계정이라 지난 일정·앞 주가 없어 `OutcomeDto`·`PreviousWeekDto` 필드 비교를 건너뛴 것이다(그 필드는 pytest `TestOutcomeRows`·`TestWeeklyAggregates` 가 본다).

앱 (`APP`, `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`):

```
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --console=plain
BUILD SUCCESSFUL in 1m 26s
```

- 단위 테스트 **598건, 실패 0, 오류 0**(1차 593, 작업 전 563). 이번에 더한 것: `ReportWeekNavigatorTest`(4), `ReportMappingTest`(+1 → 21). 1차에 더한 것: `ReportMappingTest`(20), `ReportDayTest`(4), `NetworkDtoNullSafetyTest`(+4), `WeeklyReportViewTest`(+2), `CalibrationViewTest`(+2).
- lint: **오류 0**, 경고 62(HTML 보고서 기준, 작업 전과 같은 수). 이번에 손댄 파일의 경고는 없다.
- `assembleDebugAndroidTest` 는 1차에서 성공(바꾼 `DemoFlowTest` 가 컴파일된다). 이번에는 androidTest 를 건드리지 않았다.

## 구현 리뷰 반영 (phaseB-review.md 6건)

1. **리다이렉트로 새는 키**(blocking) — `llm.chat` 이 키를 `add_unredirected_header` 로 붙인다. urllib 이 POST 의 301/302/303 을 따라가도 새 요청에는 키가 없어 401/405 → `http_4xx` 로 끝난다. `test_llm.py` 가 만든 요청을 `HTTPRedirectHandler().redirect_request(…, "http://evil.example/")` 에 넣어 Authorization 이 없음을 단언한다. `routing/clients.py` 의 KakaoAK 헤더는 리뷰대로 범위 밖이라 그대로 둔다.
2. **사용자당 LLM 호출 상한**(blocking) — `summary._take_llm_budget` 가 `llm.chat` 바로 앞에서만 사용자·KST 날짜별 호출 수를 센다(`LLM_USER_DAILY_LIMIT = 30`, `nlp` 30/hour 와 같은 수). 넘으면 템플릿·`summary_source="template"`. 저장된 문장·백오프로 끝나는 요청은 세지 않고, 리포트 GET 도 막지 않아 주 넘기기는 그대로 된다. 셀 수 없으면(캐시 고장) 부르지 않는다. 리뷰 예시와 달리 카운터는 `default` 가 아니라 **새 캐시 별칭 `llm_budget`** 에 둔다 — 운영 `default` 는 지도 이미지 때문에 300개에서 퇴출하고, `FileBasedCache.incr()` 는 기본 TTL(300초)로 다시 써서 5분만 쉬면 상한이 0부터 다시 센다(`static_map_budget` 과 같은 이유, `base.py`·`prod.py` 주석). 테스트: A→B→A 로 해시를 빗나가게 해도 상한에서 멈춤·다른 사용자는 됨·저장된 문장은 상한과 무관·캐시 고장 시 opener 미호출.
3. **Figma 명세의 리뷰 전 값**(non-blocking) — [figma-changes.md](figma-changes.md) F-10~F-13 을 앱 값으로 고쳤다. 지각일 "10월 6일"(F-10 5, F-11 머리·1), 기록 반영 "이번 주 일정 7건의 계획 중 …"(F-10 6)·"이번 주 계획은 준비·이동 기록 없이 입력한 준비 시간과 경로 예상 시간으로 계산됨"(F-12 6), 범례 계획·늘어남·줄어듦(F-11 4, F-13). 알람이 꺼진 일정의 변형(F-12 4 측정 공백 안내·요약 제안, F-13 ⑨-b "· 알람 꺼짐")도 더했다. [report-design.md](report-design.md) 머리에 "구현은 리뷰 반영판, 차이는 이 문서" 한 줄을 뒀다.
4. **⑨ 원인 제목과 차이 줄의 엇갈림**(NIT) — 원인 제목의 분은 차이 줄과 같은 `halfUp` 이고, 1분이 안 되면 숫자를 뺀다("이동이 더 걸렸음", "집에서 늦게 나섰음"). 설계 §2-2 의 "n = 그 원인의 deviation 반올림" 을 따르되 "0분 더 걸렸음" 을 쓰지 않는다. `ReportMappingTest` 에 이동 0.3·출발 0.4·준비 0.5 사례.
5. **관용구 거절과 같은 수치 재호출**(NIT) — 프롬프트 3번에 "'한 번도'·'한번 더' 처럼 한글 수가 든 관용구도 쓰지 않는다. late 가 0 이면 '지각은 없었음' 이라고 쓴다" 를 더하고 `PROMPT_VERSION` 을 2 로 올렸다. 검사는 그대로 엄격하다("한 번도 늦지 않았음" 을 지각이 있던 주에 쓰면 거짓이라 예외로 두지 않음). 검사에 떨어진 해시는 1시간이 아니라 `BACKOFF_INVALID_SECONDS`(12주, LLM 을 부르는 기간) 동안 쉬어, 수치가 바뀔 때까지 같은 입력을 다시 보내지 않는다.
6. **⑧ 주 이동 규칙의 테스트**(NIT) — `HomeViewModel` 은 `Application` 이 있어야 만들어져 JVM 테스트가 닿지 않는다(`HomeViewModelInitOrderTest` 머리말). 그래서 리포트 상태와 규칙을 `ui/home/ReportWeekNavigator.kt` 로 옮기고(동작 그대로) `HomeViewModel` 은 위임만 한다. `ReportWeekNavigatorTest` 가 가짜 저장소 응답으로 실패 시 보이던 주로 되돌림(다음 "›" 가 10월 12일을 부름)·처음 실패는 요청한 주에 머묾·`has_next_week: false` 면 "›" 무시·`openReport` 마다 `openCount` 증가(주 이동은 그대로)를 단언한다.

## 설계와 다르게 한 것 (설계 리뷰·구현 리뷰 반영)

설계 문서 본문은 고치지 않았다(머리에 이 목록을 가리키는 한 줄만 있음). 리뷰 고침을 코드에 넣었고, Figma 명세 F-10~F-13 은 아래 값으로 고쳤다.

구현 리뷰에서 더한 것:
- §4-5 "스로틀은 더하지 않는다" 대신 사용자·날짜별 LLM 호출 상한 30회(`llm_budget` 캐시). 리포트 GET 은 그대로 막지 않는다.
- §4-2 프롬프트 3번에 관용구 금지·"지각은 없었음" 을 더함(`PROMPT_VERSION = 2`). §4-3 백오프의 검사 실패(`invalid_output`)는 1시간이 아니라 12주 쉰다.
- §2-2 원인 제목의 n 이 반올림해 0 이면 숫자를 뺀다("이동이 더 걸렸음").
- LLM 키는 리다이렉트를 따라가지 않는 헤더로 붙인다(§4-3 클라이언트).
- ⑧ 의 상태·주 이동 규칙은 `HomeViewModel` 이 아니라 `ReportWeekNavigator` 에 있다(동작 같음, §2-3).

설계 리뷰에서 바꾼 것:

- 예시 지각일을 2026-10-06(화)로(#1). 요일 칸·위치는 `date` 로만 정하고 주 밖·못 읽는 날짜는 뺀다.
- ⑧ 스크롤은 `MainHost` 가 들고 있어 ⑨ 에서 돌아와도 그대로다. 주가 바뀌거나 홈에서 새로 열면 맨 위(#2). 홈에서 열면 마지막으로 본 주(처음은 지난 주)다(#20).
- 분·%·%p 는 서버·앱 모두 절댓값 0.5 올림(#3). 지각은 최소 1분 — ⑨ "1분 안쪽 지각" 대신 "1분 지각"(#4).
- 준비가 해당 없는 일정(`not_from_home`)은 준비를 측정 못 함으로 세지 않는다. 그 일정의 늦은 출발 제목은 "출발이 n분 늦었음"(#5).
- 원인을 나누지 못한 지각의 안내를 기록 방식에 맞춤(계획 없음 / 출발지가 집 아님 / 출발 미기록 / 준비만 미기록). 요약 제안 g 는 "원인을 나누려면 출발 시각이 필요하니 집을 나서기 전에 알람을 해제해 둘 것."(#6)
- `outcomes[].alarm_on` 추가. 측정 공백 안내·요약 제안은 알람이 꺼진 일정을 따로 센다. ⑨ "· 알람 꺼짐", 기록 반영 문장은 "이번 주 일정 N건의 계획 중 …" / "이번 주 계획은 준비·이동 기록 없이 입력한 준비 시간과 경로 예상 시간으로 계산됨"(#7). 알람이 모두 꺼진 기록 없는 주의 제안 문장 하나를 더했다("출발·도착은 알람을 해제한 뒤에만 기록되니 기록할 일정은 알람을 켜 둘 것.").
- LLM 은 도착 기록 3건 이상인 끝난 주만(#8). 검사에 한글 수사·날짜·시각·"학습" 거절 추가, 프롬프트에 아라비아 숫자·키 설명(#9, #29).
- 그 밖: `test.py` 주소 고정(#10), 개수 아닌 수는 nullable(#11), `week_complete == has_next_week` 단언(#12), 주소 빈 값·쿼리(#13), 허용 숫자 재귀(#14), 템플릿 2~5문장(#15), 출발 "· n회"(#16), "지난주 0/0"·"null ~ null" 없음(#17), 카드별 보이는 조건 한곳(#18), 로딩 중 누름 막기·실패 시 주 되돌림(#19), 시작 `HH:mm`·τ "0.90"·부호 "−"(#21), 범례 "계획·늘어남·줄어듦"(#22), 꺼진 "›" Track(#23), `prep_applies` null 조건(#24), 동률·주 끝 미포함·반올림한 % 로 목표 판정(#25), 템플릿 실패도 `template`·저장된 LLM 문장은 12주 지나도 수치가 같으면 그대로·`URLError(timeout)` → `timeout`(#26), 확인 스크립트 404·빈 계정은 건너뜀(#27), 지각 줄 버튼 역할·설명과 칩 터치 영역(#30).

## 확인하지 못한 것

- **실제 LLM 문장**: 키가 없어 부르지 않았다. 학교 키·주소를 `backend/.env`·Render 대시보드에 넣은 뒤 `python manage.py probe_report_summary` 를 한 번 돌린다(OpenAI 호환 `Bearer` + `/chat/completions` 가정. Azure 식이면 `apps/reports/llm.py` 만 바꾼다).
- **기기 위 계측 테스트**: `DemoFlowTest`(10단계 "1/1"·"정시 도착 100%")는 컴파일만 했고 에뮬레이터에서 돌리지 않았다. 화면 배치·TalkBack 낭독은 프리뷰·기기로 사람이 확인한다. 전체 WCAG 판정은 보조 기술 실기기 확인이 따로 필요하다.
- **배포**: 공용 서버는 0.10.0 이다. 이 앱(0.11.0)을 공용 서버에 붙이면 요약·요일·지각한 날·계획 대비 실제는 숨고 "서버가 앱보다 오래됨" 안내가 붙는다. 배포 뒤 `scripts/check_deployed.py` 14절이 `summary`·`summary_source` 를 확인한다.
- **Figma 미반영**: 이 환경에 Figma 도구가 없다. 명세 [figma-changes.md](figma-changes.md) F-10~F-13 은 앱 값으로 맞췄고, Figma 파일에 옮기는 것은 메인 세션 몫이다.
- **상한 카운터의 수명**: `llm_budget` 은 `/tmp` 파일 캐시라 Render 무료 플랜이 잠들거나 재배포하면 비워진다(지도 예산과 같은 한계, `models.py` 머리말). 그때는 그날 상한을 다시 센다. 실제 서버에서 30회째 다음 호출이 템플릿으로 떨어지는지는 키를 넣은 뒤에만 확인할 수 있다.
