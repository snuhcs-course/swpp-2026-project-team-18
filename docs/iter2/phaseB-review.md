# 주간 리포트 줄글 요약과 ⑨ 상세 리포트

주간 리포트 응답에 일정별 계획 대비 실제(`outcomes`)·편차·목표·앞 주·기록 반영과 서버가 쓴 요약(`summary`·`summary_source`)을 더하고, 앱은 ⑧ 을 요약 중심으로 다시 짜고 하루 단위 ⑨ 를 새로 만든 변경이다. 이번 판(구현 리뷰 2차)은 1차 6건 — 리다이렉트로 새는 키, 사용자당 호출 상한, Figma 명세의 리뷰 전 값, ⑨ 원인 제목의 분, 검사 실패 해시의 재호출, ⑧ 주 이동 테스트 — 을 모두 반영했다(내용은 `phaseB-verification.md` "구현 리뷰 반영"). 요청한 점검 항목(집계값만 담은 프롬프트, 키 없음·실패·시간 초과·검사 실패의 템플릿과 `summary_source`, (사용자, 주) 저장, `OPENAI_BASE_URL` 기본값과 `.env.example`·`render.yaml` `sync: false`, 앱에 키 없음, ⑧·⑨ 빈·적은 데이터 상태와 "학습" 없음, 구버전 서버용 null 안전 DTO, LLM 을 부르지 않는 서버 테스트)은 코드와 맞는다. 검증 기록 뒤에 `llm.py` 가 바뀌어 `pytest apps/reports` 만 한 번 다시 돌렸고 205건이 통과했다.

Watch for: 호출 상한은 `/tmp` 파일 캐시라 인스턴스가 잠들거나 재배포되면 0 부터 다시 세고(likely), 사용자별로만 세서 스크립트로 만든 계정마다 30회가 새로 생긴다(likely). 둘 다 넘쳐도 템플릿으로 떨어지는 쪽이라 막지는 않는다. 확인 스크립트는 이번에 바꿀 수 있게 된 주소로 키를 일반 헤더에 실어 리다이렉트까지 보낸다(confirmed). `HomeViewModel.kt` 는 앱 검증 기록 뒤에 고쳐졌다(confirmed).

**Verdict**: APPROVED

## High-level view

서버 클라이언트는 키를 `add_unredirected_header` 로 붙여 301/302/303 을 따라간 요청에 키가 없고, 테스트가 표준 라이브러리 리다이렉트 처리로 그것을 단언한다. 같은 설정을 읽는 `scripts/check_external_apis.py` 5절은 이번에 `OPENAI_BASE_URL` 을 따르게 됐는데, 키를 일반 헤더로 붙이고 서버보다 느슨한 주소 검사를 쓴다.

상한은 실제로 부르기 직전에만 세고, 셀 수 없으면 부르지 않으며(fail-closed), 지도 이미지와 분리된 별칭에 있다. 한 인스턴스 수명 안에서는 1차 지적(수치를 A→B→A 로 바꿔 해시를 빗나가게 하기)을 막지만, 카운터가 `/tmp` 에 있어 잠들거나 재배포하면 비워지고, 사용자별로만 있어 계정 수만큼 늘어난다.

검사는 그대로 엄격하고 떨어진 해시는 12주 쉰다. LLM 표본 하나가 검사에 떨어지면 그 주는 수치·모델·`PROMPT_VERSION` 이 바뀌거나 그 키가 `default` 캐시에서 밀려날 때까지 템플릿이다 — 같은 입력을 다시 보내는 비용과 지연을 막는 쪽을 고른 의도된 선택이다.

앱 DTO 는 개수만 `Int = 0` 이고 나머지는 전부 nullable 이라 구버전 서버·null 이 "0%·0분" 으로 새지 않는다. `AndroidViewModel` 은 JVM 테스트에서 만들 수 없어서 ⑧ 의 상태와 주 이동 규칙을 `ReportWeekNavigator` 로 옮겼고, `HomeViewModel` 은 위임만 한다.

`phaseB-verification.md` 에 백엔드 pytest 1040 passed·로컬 스위트 실패 0·앱 단위 테스트 598건·lint 오류 0 이 있다. 그 기록 뒤에 `llm.py`·`HomeViewModel.kt` 가 고쳐졌고, `llm.py` 쪽은 리포트 테스트를 다시 돌려 확인했지만 앱 쪽은 다시 빌드하지 않았다.

<details>
<summary>Issues (4)</summary>

1. **인스턴스와 함께 비워지는 호출 상한** (non-blocking, likely) — prod `llm_budget` 은 `/tmp` 파일 캐시라 Render 무료 플랜이 15분 쉬고 잠들거나 재배포하면 카운터가 0 이 되고, 15분 쉬었다 오는 사용자는 30회를 다시 받는다. 학교 키를 넣기 전에 호출을 DB 에 한 줄씩 남겨(예: `LlmCall(user, day, outcome)`) 그것으로 세거나, 이 한계를 받아들인다고 설계 §4-5 에 적을 것.
2. **사용자별로만 있는 상한** (non-blocking, likely) — 가입에 스로틀이 없고 지난 일정과 도착 관측 `observed_at` 을 클라이언트가 만들 수 있어, 스크립트로 만든 계정마다 하루 30회가 생긴다. `_take_llm_budget` 에 전체 하루 상한을 더하고 1번과 같은 저장소에서 셀 것.
3. **확인 스크립트의 키 전송** (non-blocking NIT, confirmed) — `check_external_apis.py` 5절이 `OPENAI_BASE_URL` 로 키를 `add_header` 로 실어 GET 리다이렉트까지 보내고, 계정 정보·쿼리가 든 주소도 막지 않는다. 서버 `llm.endpoint()` 와 같은 주소 규칙을 쓰고 키는 `add_unredirected_header` 로 붙일 것.
4. **검증 기록 뒤의 수정** (non-blocking NIT, confirmed) — `llm.py`·`HomeViewModel.kt` 가 `phaseB-verification.md` 보다 늦게 고쳐졌다. 백엔드는 `pytest apps/reports` 205 passed 로 확인했으니, 커밋 전에 앱 `testDebugUnitTest lintDebug assembleDebug` 를 다시 돌리고 기록을 그 결과로 바꿀 것.

</details>

<details>
<summary>Details</summary>

## 호출 상한의 수명과 범위

남은 틈은 둘이다. 하나는 카운터의 수명이다(likely). prod 의 `llm_budget` 은 `/tmp/jit-llm-budget` 의 `FileBasedCache` 다. README 는 서버가 "sleeps after 15 minutes" 라 적고, `models.py` 머리말은 "Render 무료 플랜은 잠들거나 배포할 때 `/tmp` 파일 캐시가 비워진다" 를 근거로 요약 문장을 DB 에 둔다. 같은 전제라면 카운터도 그때 비워진다. 앱의 정기 동기화는 6시간 간격이고 경로 갱신은 3시간 안 일정에만 15분이라, 밤에는 서버가 쉽게 잠든다.

```
지난 일정 alarm_enabled 토글 + GET weekly  ×30   → 31번째부터 템플릿
15분 동안 아무 요청 없음                          → 인스턴스 잠듦, /tmp 비움
GET 한 번으로 깨움(30~60초)                       → reports:llm:user:{pk}:{오늘} 없음 → 다시 30회
```

1차 리뷰가 제안한 것도 파일 캐시였고, 지도 예산(`static_map_budget`)도 같은 한계를 받아들였으며, `phaseB-verification.md` "상한 카운터의 수명" 이 이 한계를 적어 두었다. 학교 키가 한도에 닿아도 429/4xx 를 거쳐 템플릿으로 떨어진다. 그래서 막지는 않지만, 지도 쿼터와 달리 이쪽은 학교가 준 키의 예산이다.

다른 하나는 범위다(likely, 직접 돌려 보지는 않음). 상한은 사용자별이고 전체 상한이 없다. `RegisterView` 에는 스로틀이 없고(`DEFAULT_THROTTLE_CLASSES` 도 없음), `EventWriteSerializer` 는 지난 `start_at` 을 받으며(지난 일정 거부는 캘린더 가져오기에만 있음), 도착 관측의 `observed_at` 은 클라이언트가 보낸다. 스크립트로 계정을 만들고 지난주에 일정·도착 관측을 3개씩 넣으면 계정마다 하루 30회가 새로 생긴다.

둘을 함께 닫는 방법은 호출을 DB 에 한 줄씩 남기고 그것으로 세는 것이다. LLM 을 부를 때만 하는 조회라 Neon 왕복 두 번은 LLM 대기에 비하면 작다. 지금은 성공한 문장만 `WeeklySummary` 에 남고 실패·거절된 호출은 로그에만 있으므로 "앱에서 오가는 데이터는 DB 에 기록" 요청과도 맞는다. 아래 `LlmCall` 은 새 모델의 예다.

```python
today = timezone.localdate()
calls = LlmCall.objects.filter(day=today)
if calls.count() >= LLM_DAILY_LIMIT or calls.filter(user=user).count() >= LLM_USER_DAILY_LIMIT:
    return template, SOURCE_TEMPLATE          # DB 오류도 템플릿(지금처럼 fail-closed)
call = LlmCall.objects.create(user=user, day=today, outcome="pending")
text, failure = llm.chat(SYSTEM_PROMPT, json.dumps(facts, ensure_ascii=False))
# 검사까지 마친 뒤 call.outcome = failure or reason or "ok"
```

전체 상한만 먼저 넣는다면 같은 `llm_budget` 에 `reports:llm:all:{오늘}` 키를 하나 더 세면 되지만, 수명 문제는 그대로 남는다.

## 확인 스크립트의 키 전송

`check_external_apis.py` 5절은 이번에 고정 주소 대신 `OPENAI_BASE_URL` 로 키를 보낸다(confirmed).

```python
st, body = http(f"{oai_base}/models", {"Authorization": f"Bearer {oai}"})
# http(): req.add_header(k, v) → urllib.request.urlopen(req, ...)
```

urllib 는 GET 의 301/302/303/307/308 을 모두 따라가며 `add_header` 로 붙인 헤더를 새 요청에 그대로 싣는다. 서버에서 고친 1차 #1 과 같은 경로가 개발자 PC 쪽에 남은 것이다. 주소 검사(`oai_base_ok`)도 스킴만 본다 — `https://user:pass@host/v1` 이나 `?api-version=` 이 붙은 주소는 서버 `llm.endpoint()` 가 거절하는데 스크립트는 그 주소로 키를 보낸다. 사람이 한 번 돌리는 스크립트라 NIT 다. 5절에서 서버와 같은 규칙(계정 정보·쿼리·조각 거절)을 쓰고, 키는 `Request.add_unredirected_header` 로 붙이면 된다.

## 검증 근거와 테스트 범위

`phaseB-verification.md` 는 18:16:04 에 쓰였고 `llm.py` 는 18:16:47, `HomeViewModel.kt` 는 18:17:18 에 고쳐졌다(파일 수정 시각, confirmed). 백엔드는 `python -m pytest apps/reports -q` 를 한 번 돌려 205 passed 를 봤다 — 키·네트워크 없이, conftest 가 `llm._urlopen` 을 `BaseException` 으로 막은 채다. `HomeViewModel.kt` 의 리포트 부분은 읽어서 `ReportWeekNavigator` 위임만 있는 것을 봤지만 컴파일·테스트는 돌리지 않았다. 커밋 전에 앱 `testDebugUnitTest lintDebug assembleDebug` 를 다시 돌리고 기록을 그 결과로 바꾸면 된다.

이번 판에 더한 테스트는 1차 고침 여섯 가지를 각각 고정한다(리다이렉트 301·302·303, 사용자 하루 상한과 캐시 고장, 검사 실패 해시의 휴식 길이, 지각 0 표현, ⑧ 주 이동 4건, ⑨ 원인 제목 반올림).

Not tested: 재시작·잠듦을 넘는 상한(구조상 단위 테스트로 볼 수 없음), 여러 계정에 걸친 호출량, `_store`·`_stored_text` 의 `DatabaseError`·`IntegrityError` 갈래, 기기 위 `DemoFlowTest`(1차에 컴파일만), Compose 배치·TalkBack 낭독(요일 칸 42dp 한계는 KDoc 에 있음), 실제 LLM 문장(키 없음). 전체 WCAG 판정은 보조 기술 실기기 확인과 전문 검토가 따로 필요하다.

</details>

<details>
<summary>File map</summary>

서버
- `backend/apps/reports/services.py` — `outcomes` 행, 편차·목표·앞 주·기록 반영, 주 경계 `[start, end)`, `calibration_from`, `half_up`·`late_minutes`, `prep_applies`, `alarm_on`
- `backend/apps/reports/summary.py` (신규) — 집계값, 템플릿, `check_text`, `summary_for`, 백오프(검사 실패 12주), 사용자 하루 상한
- `backend/apps/reports/llm.py` (신규) — OpenAI 호환 클라이언트, 주소 검사, 리다이렉트에 싣지 않는 키, 실패 분류
- `backend/apps/reports/{models.py, migrations/0001_initial.py, admin.py, apps.py}` (신규) — `WeeklySummary`, 읽기 전용 관리 화면
- `backend/apps/reports/management/commands/probe_report_summary.py` (신규) — 키를 넣은 뒤 돌리는 확인 명령
- `backend/apps/reports/views.py` — 응답에 요약·출처를 붙임
- `backend/apps/reports/tests/{conftest.py, test_llm.py, test_summary.py}` (신규), `test_reports.py` — LLM 차단 장치와 Phase B 테스트
- `backend/config/settings/{base.py, prod.py, test.py}` — `OPENAI_BASE_URL`(빈 값도 기본 주소), `llm_budget` 캐시, 테스트 주소 고정, `APP_VERSION 0.11.0`
- `backend/apps/common/tests/{test_settings.py, test_admin.py}` — 주소·캐시 설정 테스트, 관리 화면 예외
- `backend/.env.example`, `render.yaml`, `README.md` — OPENAI 세 값 자리와 설명
- `backend/scripts/{check_app_contract.py, check_deployed.py, check_external_apis.py}` — 새 필드·출처 검사, `OPENAI_BASE_URL` 기준 `/models` 확인

앱 (`APP/app/src/main/java/com/swpp/wakeup/` 기준)
- `data/remote/ReportsApi.kt` — null 안전 DTO, 새 DTO 8개
- `data/repository/ReportMapping.kt` (신규), `ReportRepository.kt` — 매핑 분리
- `domain/model/WeeklyReport.kt` — ⑧·⑨ 화면 모델, 카드 가시 조건, `halfUp`
- `ui/report/WeeklyReportScreen.kt` — ⑧ 재구성
- `ui/report/{ReportDayScreen.kt, ReportParts.kt, ReportPreviewData.kt}` (신규) — ⑨, 공용 부품, 프리뷰 데이터
- `ui/home/ReportWeekNavigator.kt` (신규), `ui/home/HomeViewModel.kt` — ⑧ 상태·주 이동 규칙과 위임
- `ui/nav/AppRoute.kt`, `MainActivity.kt` — `ReportDay` 경로, ⑧ 스크롤 보존
- `ui/common/JitCard.kt`, `ui/events/AddEventScreen.kt`, `ui/theme/Tokens.kt` — `container`, `trailing`, `SurfaceDanger`
- `APP/app/build.gradle.kts` — 0.11.0 / versionCode 11
- `APP/app/src/test/…/{ReportMappingTest.kt (신규), ReportWeekNavigatorTest.kt (신규), NetworkDtoNullSafetyTest.kt, WeeklyReportTest.kt}`, `APP/app/src/androidTest/…/DemoFlowTest.kt`

문서
- `docs/iter2/{report-design.md, phaseB-design-review.md, phaseB-design-review.json, phaseB-verification.md}` (신규), `docs/iter2/{figma-changes.md, backend-requests.md}`, `task.md`

전체 변경: `git -C c:\Users\rlaji\AndroidStudioProjects\SWPP-PJ-front-iter2 diff` 와 추적되지 않은 새 파일(`git status --short` 의 `??`).

</details>
