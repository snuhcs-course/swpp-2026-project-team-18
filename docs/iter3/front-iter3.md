# Iteration 3 프론트 작업 기록 — 팀 18 JustInTime
브랜치 `front-iter3`, 기준 `origin/main` = `3d39fc8`(10-31). 11-03 에 `front-iter3`(`c527cea`)를 main 에 합쳤다. main 은 `dbe38da`(머지 커밋, 부모 `3d39fc8`·`c527cea`)이고 트리는 `c527cea` 와 같다. 그 뒤 11-03 밤 작업(아래 "11-03 노션 남은 프론트 항목")은 `front-iter3` 에서 이어 갔고 아직 main 에 없다. 백엔드가 `todo.md` §2 에 넘긴 일과 팀 회의 내용을 앱에 넣었다. 항목 번호는 [figma-changes.md](figma-changes.md) 의 번호와 같다(1~22 는 아래 절 제목, 5b·5c·19b 는 만들지 않음). 서버에 아직 없는 부분은 [backend-requests.md](backend-requests.md) 에 요청으로 적었다.

## 요약
- 단위 테스트: main 기준 **739건 → 1038건**, 실패 0 (`APP\app\build\test-results\testDebugUnitTest\*.xml`, 최신 앱 커밋 `954d591` 의 실행 결과). 950건은 11-03 밤 끝(`d79fb67`)의 값이고, 11-06 저녁에 88건이 늘었다(975 → 984 → 987 → 1012 → 1018 → 1029 → 1030 → 1038, 아래 "11-06 task.md 프론트 할 일"). 877건은 `c527cea`(main 에 합친 시점)의 값이고, 11-03 밤에 70건이 늘었다(902 → 927 → 939 → 948 → 947 → 950).
- 린트: 오류 0 / 경고 63 (11-06 에 1개 늘었다. 아래 절 참조. 11-03 밤까지는 62 로 기준선과 같았다). 11-03 밤 커밋마다 `lintDebug` 를 돌렸다. S1 커밋에서 늘었던 경고 2개는 `LastEmailStore` 의 UseKtx 였고 `d79fb67` 에서 고쳤다(64 → 62).
- 기기 테스트(`androidTest`)는 컴파일(`compileDebugAndroidTestKotlin`, `assembleDebugAndroidTest`)만 확인했다. 기기에서 돌린 기록은 없다.
- 기준선(11-01, 손대지 않은 브랜치): 단위 739건, 린트 오류 0 / 경고 62, 백엔드 pytest 1684건 통과.
- 에뮬레이터 확인(11-02)과 main 대중교통 5개 확인(11-03)은 맨 아래에 적었다.

## 커밋
| 커밋 | 내용 |
| --- | --- |
| `12ba612` | 다가오는 일정 갱신·필터, 기록 끝내기 조건, 안 감 |
| `b5ef865` | 도착했어요 버튼 |
| `cf75443` | 로그아웃·refresh 회전, 토큰 암호화 저장 |
| `83ec171` | 다시 계산 정리, 적용 알람 줄이기, 안전 버퍼 표시 |
| `9b5b35d` | 경로 기본 선택, 버스 합친 카드 |
| `9616680` | 일정 수정 화면, 준비 시간 스위치 |
| `5f24a92` | 이동 중 늦으면 경로 바꾸기 |
| `2412ccf` | 도착 판정·추적 서비스 복원 |
| `5cad0a6` | 지도 이동 거리·핀치 줌 |
| `cd6b785` | 카카오·구글 로그인 |
| `5206719` | 앱 로고, 대중교통 7개 화면 테스트 |
| `44fd887` | 집 여러 개(기기 저장) |
| `30284f3` | 디버그 HTTP 로그에서 토큰 가리기 |
| `c907c6a` | 앱이 취소한 GET 은 다시 시도하지 않음 |

## 1. 다가오는 일정 갱신 · 필터 (P0) — `12ba612`
- **바뀐 것**: 홈으로 돌아오면(onResume) 목록을 다시 불러오고, 화면이 켜져 있는 동안 다음 경계(시작 시각·시작 뒤 1시간)에 맞춰 로컬에서 다시 거른다. 지난 일정이 앱을 다시 켤 때까지 남던 문제가 없어졌다. 서버에는 `from` = 지금 − 5시간을 붙여 지난 일정을 받지 않는다.
- **파일**: `domain/model/HomeList.kt`, `ui/home/HomeViewModel.kt`(`onForeground`), `data/repository/EventRepository.kt`, `ui/home/HomeScreen.kt`.
- **테스트**: `HomeListTest`, `EventRepositoryTest`.
- **확인**: 단위. 에뮬레이터 확인 1(23:34 에 `GET /api/events?from=…T09:34:22Z`).
- **열린 것**: 없음.

## 2. 기록 끝내기 화면 조건 (P0) — `12ba612`
- **바뀐 것**: `TripTrackingService` 가 출발 관측을 큐에 넣는 순간(도착도 같음) 그 일정의 아침 기록 세션을 닫고 홈의 "아침 기록 n개 남음"을 갱신한다. 누르지 않은 블록은 기록하지 않는다. 나간 뒤에 "마침"을 눌러 이동 시간이 준비 시간으로 올라가던 문제를 막는다.
- **파일**: `sensing/MorningClose.kt`, `data/local/MorningSessionStore.kt`, `sensing/TripGeofence.kt`, `sensing/TripLiveState.kt`, `sensing/TripTrackingService.kt`.
- **테스트**: `MorningCloseTest`.
- **확인**: 단위만. 서비스만 살아 있을 때 세션이 닫히는지는 기기에서 보지 못했다.
- **열린 것**: 실기기에서 집을 나선 뒤 "기록 끝내기"가 안 나타나는지 확인.

## 3. 안 감 (3, 3b) — `12ba612`
- **바뀐 것**: 기록 끝내기 화면에 "오늘 안 가요", 알람 결정 화면 맨 아래 줄과 홈 행에 "안 감으로 표시"(표시한 일정은 흐리게, "되돌리기"). 지난 일정은 ⑨-b 도착 기록 없는 날에서도 표시한다(되돌리기는 누른 직후 안내에서만). 표시·되돌린 뒤 목록을 다시 불러오고 알람을 다시 등록한다. 주간 리포트는 `skipped_count` 가 있으면 "안 감으로 표시한 일정 N건은 빼고 계산함"을 보인다.
- **파일**: `data/remote/EventsApi.kt`(`skipped`·`skippedAt`), `data/repository/EventRepository.kt`, `ui/morning/MorningProgressScreen.kt`, `ui/alarm/AlarmDecisionScreen.kt`, `ui/home/HomeScreen.kt`, `ui/report/ReportDayScreen.kt`, `ui/report/WeeklyReportScreen.kt`, `alarm/AlarmSyncPlan.kt`.
- **테스트**: `SkipMappingTest`, `EventRepositoryTest`, `AlarmSyncPlanTest`, `ReportWeekNavigatorTest`, `HomeListTest`.
- **확인**: 단위. 에뮬레이터 확인 2 에서 결정 화면 맨 아래 줄에 "안 감으로 표시"가 보였다. 눌러 보지는 않았다.
- **열린 것**: 서버가 패치 0038 이상일 때만 동작한다(그 전 서버는 404). 공용 서버에서는 눌러 보지 않았다.

## 4. 도착 판정 버튼 — `b5ef865`
- **바뀐 것**: 이동 중 카드와 추적 알림에 "도착했어요". 누르면 최근 위치(있으면)를 붙여 `detector=manual` 도착을 올리고 화면에 바로 도착을 보인다. 도착이 이미 있으면 버튼이 사라진다.
- **파일**: `sensing/ManualArrival.kt`, `sensing/TripObservations.kt`, `sensing/TripTrackingService.kt`, `ui/alarm/TripProgressCard.kt`, `alarm/AlarmNotifications.kt`, `data/remote/ObservationsApi.kt`.
- **테스트**: `ManualArrivalTest`.
- **확인**: 단위. 에뮬레이터 확인 6 에서 이동 카드에 "도착했어요"와 설명이 보였다. 눌러 보지는 않았다.
- **열린 것**: 알림 버튼은 실기기에서 확인해야 한다.

## 5. 이동 중 늦으면 경로 바꾸기 — `5f24a92`
- **바뀐 것**: `routes/live` 응답에 `switch_suggestion` 이 오면 경로마다 한 번 헤드업 알림("지금 경로로는 N분 늦어요" / "○○로 가면 안 늦어요·M분 덜 늦어요", [바꾸기]·[무시])과 이동 카드 아래 배너(도착 예상, [이 경로로 바꾸기], "이번 이동만 바뀜"). 바꾸면 `POST /api/events/{id}/reroute`. 같은 경로는 다시 알리지 않는다.
- **파일**: `domain/model/RerouteAlert.kt`, `domain/model/LiveRouteDecision.kt`, `ui/alarm/RerouteCard.kt`, `data/repository/EventRepository.kt`, `sensing/TripTrackingService.kt`, `sensing/TripArrivalReceiver.kt`, `alarm/AlarmNotifications.kt`, `data/remote/EventsApi.kt`.
- **테스트**: `RerouteAlertTest`, `RerouteRepositoryTest`, `AppliedRerouteTest`.
- **확인**: 단위. 에뮬레이터 확인 7(알림 한 번, 배너, 201, 배너 사라짐).
- **열린 것**: 서버 계약 문서가 main 에 없다([R3-1](backend-requests.md)). 헤드업 알림은 실기기에서 확인해야 한다.
- **5b·5c**: 출발 전 재계획(5b), ⑦ 화면의 체크포인트(5c)는 이때는 만들지 않았다. 11-03 밤에 만들었다(아래 S2·S3).

## 6. 앱 재시작 후 도착 판정 복원 — `2412ccf`
- **바뀐 것**: 도착 결과를 기기에 저장하고, 앱을 다시 켜면 서버 `GET /api/observations?event=` 로 맞춘다. 저장한 도착은 3일이 지났거나 10분보다 먼 미래이면 버린다.
- **파일**: `data/local/ArrivalStore.kt`, `domain/model/ArrivalLedger.kt`, `data/repository/ArrivalRepository.kt`, `sensing/ArrivalRecords.kt`, `sensing/TripLiveState.kt`, `main/res/xml/backup_rules.xml`·`data_extraction_rules.xml`.
- **테스트**: `ArrivalLedgerTest`, `ArrivalRepositoryTest`, `TripLiveStateTest`, `PrefsScopeAuditTest`.
- **확인**: 단위. 기기에서 앱을 죽였다 켜는 확인은 하지 못했다.
- **열린 것**: 같은 일정의 서버 확인은 5분에 한 번이다(아래 "정한 기본값").

## 7. 추적 서비스 재시작 복원 — `2412ccf`
- **바뀐 것**: `TripTrackingService` 가 다시 시작되면 이동 중 상태(움직이기 시작한 시각, 이미 알린 경로 바꾸기 제안, 출발을 놓쳤는지)를 읽어 이어 가고, 실시간 경로는 `routes/live` 를 다시 불러 되살린다. 다른 일정이거나 6시간이 지난 사본은 버린다.
- **파일**: `domain/model/TripProgress.kt`, `data/local/TripProgressStore.kt`, `sensing/TripTrackingService.kt`.
- **테스트**: `TripResumeTest`, `TripLiveStateTest`.
- **확인**: 단위. 기기에서 서비스를 죽이는 확인은 하지 못했다.
- **열린 것**: 없음.

## 8. 이동 중 보라색 보정 경로 확인
- **바뀐 것**: 코드 변경 없음. 에뮬레이터에서 확인했다.
- **확인**: 에뮬레이터 확인 6. 이동 중 지도 제목은 "이동 중 · 현재 위치부터 가장 빠른 길", 보라 선이 현재 위치에서 목적지까지 이어지고 1분마다 `POST /api/routes/live` 를 불러 경로가 바뀌면 선과 요약을 다시 그렸다.
- **열린 것**: 지도 중심이 한 번 크게 튀는 현상을 봤다. 아래 "지도 튐" 참고.

## 9. 알람 결정 후 수정 화면 — `9616680`
- **바뀐 것**: 일정 추가 화면을 수정 모드로 다시 쓴다(제목 "일정 수정"). 알람 결정 화면 맨 아래 줄 "일정 수정"으로 들어가고, 값은 일정에서 채운다. `PATCH /api/events/{id}` 에서 지우는 값은 `null` 을 실제로 보낸다. 장소·출발지를 바꾸면 경로를 다시 고르게 하고, 저장 뒤 목록을 다시 불러오고 알람을 다시 등록한다. 반복 일정은 "이 회차만 바뀜", 요일은 못 고친다.
- **파일**: `ui/events/AddEventScreen.kt`, `data/repository/EventEditPatch.kt`, `data/repository/EventRepository.kt`, `data/remote/EventsApi.kt`, `ui/nav/AppRoute.kt`, `ui/alarm/AlarmDecisionScreen.kt`, `ui/home/HomeViewModel.kt`.
- **테스트**: `EventEditPatchTest`.
- **확인**: 단위. 에뮬레이터 확인 3, 4(수정 화면 값 채움, 경로 다시 고르기).
- **열린 것**: 수정 화면을 "추가 화면 재사용"으로 정한 것은 팀이 아직 합의하지 않았다. 바꾸기 쉽게 두었다.

## 10. 출발지가 집이 아닐 때 준비 시간 스위치 — `9616680`
- **바뀐 것**: 출발지를 집이 아닌 곳으로 고르면 "여기서 자고 출발해요 (준비 시간 포함)" 스위치가 나온다. 켜면 `include_prep: true`, 끄면 `null`(수정에서는 `null` 을 실제로 보냄). 응답의 `include_prep`·`prep_applies` 로 수정 화면 초기값을 정한다. 출발지를 집으로 되돌리면 스위치를 숨기고 값을 `null` 로 한다.
- **파일**: `ui/events/AddEventScreen.kt`, `data/remote/EventsApi.kt`, `data/repository/EventEditPatch.kt`, `data/repository/EventRepository.kt`.
- **테스트**: `EventEditPatchTest`.
- **확인**: 단위. 스위치를 에뮬레이터에서 눌러 보지는 않았다.
- **열린 것**: 서버가 패치 0034 이상일 때만 응답에 필드가 온다.

## 10b. 집 여러 개 (기기 저장) — `44fd887`
- **바뀐 것**: 설정에 기본 집 아래 "다른 집" 목록(이름·주소, "기본으로", 삭제)과 "+ 다른 집 추가". 일정 추가의 출발지 검색을 열면 맨 위에 이 집들과 "현재 위치 사용"이 나오고, 다른 집을 고르면 준비 시간이 켜진다. "기본으로"는 그 집을 서버 프로필에 저장하고 예전 기본 집은 목록에 남긴다.
- **파일**: `domain/model/SavedHome.kt`, `data/local/SavedHomesStore.kt`, `data/local/LocalStores.kt`, `ui/settings/SettingsScreen.kt`, `ui/events/AddEventScreen.kt`, `ui/home/HomeViewModel.kt`.
- **테스트**: `SavedHomesTest`, `PrefsScopeAuditTest`.
- **확인**: 단위. 에뮬레이터에서는 확인하지 못했다.
- **열린 것**: 서버에 `SavedPlace` 가 없어 기기에만 저장된다(앱을 지우면 사라진다). 서버 요청은 [R3-2, R3-3](backend-requests.md). 한 번 올리기는 서버가 나온 뒤에 앱이 만든다.

## 11. "다시 계산" 버튼 정리 — `83ec171`
- **바뀐 것**: 알람 결정 화면의 "다시 계산" 버튼을 없앴다. 블록 정의 화면의 안내(`DEFINITION_NOTICE`)와 루틴 편집 문구를 "앞으로의 알람에 반영했어요" 쪽으로 고쳤다. 블록을 만들고 고치고 지운 뒤 홈을 다시 불러오고 알람을 다시 등록한다.
- **파일**: `ui/home/HomeViewModel.kt`, `ui/routines/RoutineEditorScreen.kt`, `ui/alarm/AlarmDecisionScreen.kt`.
- **테스트**: 전용 단위 테스트 없음. 화면 문구와 버튼이라 `AlarmDecisionScreenTest`(기기 테스트)에 맞췄다.
- **확인**: 에뮬레이터 확인 2(결정 화면에 "다시 계산" 없음).
- **열린 것**: 없음.

## 12. 적용 알람 정보 줄이기 · 문구 — `83ec171`
- **바뀐 것**: "적용 알람" 카드는 알람 시각과 "정시 도착 확률"만 보인다(계산 전이면 "아직 계산할 수 없음"). 경로 기준·도착 예정·계산 시각·대체 경고는 "계산 방법" 카드로 옮겼다. 서버 문장은 다시 조립하지 않는다.
- **파일**: `ui/alarm/AlarmDecisionScreen.kt`, `domain/model/RouteBasis.kt`, `domain/model/AlarmPlan.kt`, `data/repository/EventRepository.kt`.
- **테스트**: `RouteBasisTest`, `AlarmDecisionScreenTest`(기기 테스트, 컴파일만).
- **확인**: 에뮬레이터 확인 2.
- **열린 것**: 서버 문자열 정리(`route_detail` 등)는 백엔드 P2 와 같이 간다.

## 13. 안전 버퍼 표시 — `83ec171`
- **바뀐 것**: 계획의 `buffer_source` 가 `learned` 이면 "지각 기록으로 조정됨"으로 보인다(`fixed` 는 "고정값"). 주간 리포트의 `buffer` 블록이 있으면 "안전 버퍼 조정 · 이번 주" 카드에 `reason_text` 를 보인다. 설정에 버퍼 입력은 만들지 않았다(팀 결정 10-28).
- **파일**: `data/repository/EventRepository.kt`, `data/repository/ReportMapping.kt`, `data/remote/ReportsApi.kt`, `domain/model/WeeklyReport.kt`, `ui/report/WeeklyReportScreen.kt`.
- **테스트**: `RouteBasisTest`. 리포트 매핑 전용 테스트는 없다.
- **확인**: 에뮬레이터 확인 2(안전 버퍼 10분, 고정값). 리포트 카드는 기기에서 보지 못했다.
- **열린 것**: 리포트 `buffer` 는 패치 0031 이후 서버에서만 온다.

## 14. 경로 기본 선택 — `9b5b35d`
- **바뀐 것**: `preferred_key`·`preferred_mode`·`preferred_basis`(모두 nullable)를 받아 기본 선택을 `preferred_key` → `fastest_key` → 첫 후보 순으로 정한다. 그 수단 탭이 먼저 열린다. "지난번에 고른 경로"·"자주 쓰는 수단" 설명을 붙인다. README 와 `docs/demo-checklist.md` 의 "기본 선택은 fastest_key" 설명도 고쳤다.
- **파일**: `data/remote/EventsApi.kt`, `data/repository/EventRepository.kt`, `domain/model/RouteOption.kt`, `ui/events/RouteChoiceScreen.kt`.
- **테스트**: `RoutePreferenceTest`.
- **확인**: 단위. 에뮬레이터 확인 4 에서 `preferred_key` 가 없는 일정(스크립트로 만든 일정)은 자동차 탭(최단 13분)으로 열렸다. 규칙대로이고 바꾸지 않았다.
- **열린 것**: `preferred_*` 가 실제로 오는 경우는 기기에서 보지 못했다.

## 15. 버스 번호만 다른 경로 한 카드 — `9b5b35d`
- **바뀐 것**: `minutes_max` 가 `minutes` 보다 크면 "34~36분"처럼 범위로 보인다. 승차·환승 행의 버스 칩은 4개까지 보이고 "+N"을 누르면 전부 펼친다. 카드 `key` 는 그대로 `route_key` 로 저장한다. 대체 버스 실시간 도착은 보이지 않는다(사용자 결정 10-30).
- **파일**: `data/remote/EventsApi.kt`, `domain/model/RouteOption.kt`, `ui/events/RouteChoiceScreen.kt`, `ui/events/TransitVisuals.kt`.
- **테스트**: `RoutePreferenceTest`.
- **확인**: 에뮬레이터 확인 4 에서 합친 카드 "5528 → 5511 (대체 2개)"에 칩 5528·5519(승차), 5511·5516(환승)이 보였다. `minutes_max` 범위 표시는 에뮬레이터 카드에서 따로 보지 못했다(단위 테스트만).
- **열린 것**: 없음.

## 16. 대중교통 후보 7개 화면 — `5206719`
- **바뀐 것**: 앱 상한은 이미 7개였다(`RouteMode.TRANSIT`). 카드 7개 스크롤과 배지 줄을 확인하는 기기 테스트를 더했다.
- **파일**: `androidTest/.../integration/RouteChoiceLayoutTest.kt`.
- **테스트**: `RouteChoiceLayoutTest`(가짜 데이터로 카드 7개를 그리고 스크린샷을 저장, 컴파일만 확인).
- **확인**: 에뮬레이터 확인 4(카드 7개: 23·24·25·26·27·33·34분, 배지는 카드마다 최대 둘이고 한 줄에 들어갔다. 셋은 보지 못했다). main 빌드 확인은 맨 아래.
- **열린 것**: 없음.

## 17. 로그아웃 · refresh 회전 — `cf75443`
- **바뀐 것**: 로그아웃은 기기 토큰을 지운 뒤 `POST /api/auth/logout` `{refresh}` 로 서버 refresh 를 취소한다(인증 헤더 없음, 결과와 관계없이 로그인 화면으로). 설정에 "모든 기기에서 로그아웃"(`POST /api/auth/me/logout-all`). 갱신 응답에 새 `refresh` 가 오면 저장한다.
- **파일**: `data/repository/AuthRepository.kt`, `data/repository/ServerLogout.kt`, `data/remote/AuthApi.kt`, `data/remote/AuthInterceptor.kt`, `data/remote/RefreshOutcome.kt`, `ui/settings/SettingsScreen.kt`.
- **테스트**: `AuthRepositoryTest`.
- **확인**: 단위. 11-03 토큰 로그 확인(작업 B)에서 에뮬레이터 로그인과 목록 요청이 동작했다. 로그아웃 버튼은 기기에서 누르지 않았다.
- **열린 것**: 서버가 패치 0036 이상이어야 한다. `JWT_ROTATE_REFRESH_TOKENS=1` 은 [R3-5](backend-requests.md).

## 18. 토큰 암호화 저장 — `cf75443`
- **바뀐 것**: 토큰을 Android Keystore 의 AES-256/GCM 키로 암호화해 저장한다. 예전 평문 토큰은 읽을 때 옮긴다. 복호화나 암호화가 실패하면 다시 로그인하게 하고 앱은 죽지 않는다. `androidx.security:security-crypto` 는 더 이상 권장되지 않아 쓰지 않았다.
- **파일**: `data/local/TokenCipher.kt`, `data/local/TokenStore.kt`.
- **테스트**: `TokenStoreTest`.
- **확인**: 단위. 에뮬레이터에서 `pm clear` 뒤 로그인해 목록을 불러오는 데 문제가 없었다(작업 B).
- **열린 것**: 평문에서 옮기는 경로는 기기에서 보지 못했다.

## 19. 카카오 · 구글 로그인 — `cd6b785`
- **바뀐 것**: 카카오 SDK 2.26.0 의 ID 토큰, 구글 Credential Manager 1.6.0 + googleid 1.1.1 의 ID 토큰을 `POST /api/auth/social/{provider}` 로 보낸다. 400 `social_terms_required` 면 약관 화면, 409 `social_email_in_use` 면 원래 로그인 방법 안내, 503 이면 "쓸 수 없어요". 설정에 연결·끊기와 `login_methods`. `@social.invalid` 이메일은 숨긴다. 키는 `APP/gradle.properties` 에 있고 `local.properties` 로 덮어쓸 수 있다.
- **파일**: `ui/auth/SocialSignIn.kt`, `domain/model/SocialLogin.kt`, `ui/auth/LoginScreen.kt`, `ui/auth/SocialTermsScreen.kt`, `ui/auth/AuthViewModel.kt`, `data/repository/AuthRepository.kt`, `data/remote/AuthApi.kt`, `ui/settings/SettingsScreen.kt`, `AndroidManifest.xml`, `JitApplication.kt`.
- **테스트**: `AuthRepositorySocialTest`, `SocialLoginTest`, `AuthFlowTest`(기기 테스트, 컴파일만).
- **확인**: 단위만. 실계정으로 로그인해 보지 못했다.
- **열린 것**: 이 PC 의 디버그 키를 콘솔에 등록해야 한다([R3-4](backend-requests.md)). 실기기 확인 필요.
- **19b**: 이메일 계정 기능(마지막 이메일 기억, 비밀번호 보기 토글 등)은 이때는 만들지 않았다. 11-03 밤에 만들었다(아래 S1).

## 20. 지도 연속 이동 거리 두 배 — `5cad0a6`
- **바뀐 것**: 응답을 기다리는 동안 이동이 연달아 일어나도 이동량이 두 번 반영되지 않는다. 손짓이 끝날 때 보이던 화면을 기준으로 확정한다.
- **파일**: `domain/model/MapCameraMath.kt`, `ui/alarm/RouteMapCard.kt`, `ui/places/MapPickScreen.kt`.
- **테스트**: `MapCameraMathTest`.
- **확인**: 단위. 에뮬레이터 확인 8(200px 드래그 한 번에 정적 지도 요청 한 번, "경로 전체 보기", 현재 위치 버튼).
- **열린 것**: 지도 튐(아래).

## 21. 작은 핀치에 한 단계 줌 — `5cad0a6`
- **바뀐 것**: 핀치가 `MapCameraMath.ZOOM_STEP_THRESHOLD_LOG2` = 0.42(약 1.34배 확대·0.75배 축소) 미만이면 줌 단계를 바꾸지 않고, 큰 손짓도 한 번에 세 단계까지만 움직인다.
- **파일**: `domain/model/MapCameraMath.kt`.
- **테스트**: `MapCameraMathTest`.
- **확인**: 단위. 기기에서 핀치는 해 보지 못했다.
- **열린 것**: 임계값 0.42 는 감으로 정했다. 손으로 써 보고 조정하면 된다.

## 22. 앱 로고 — `5206719`
- **바뀐 것**: 시안 A(링 + 점 + 스톱워치 꼭지, 강조색 `#FFB35C`, 남색 `#0E1320`)로 런처 아이콘(적응형 전경·배경, 테마 아이콘용 단색)과 Android 12+ 스플래시. 기본 로봇 비트맵은 지웠다(minSdk 34).
- **파일**: `main/res/drawable/ic_launcher_background.xml`, `ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`, 스플래시 테마.
- **테스트**: 없음.
- **확인**: 빌드됨(이후 커밋의 APK 가 에뮬레이터에서 실행됨). 런처·테마 아이콘·스플래시 모양은 기기에서 따로 보지 않았다.
- **열린 것**: 실기기에서 모양 확인.

## 11-03 노션 남은 프론트 항목
노션 목록에서 프론트 몫으로 남은 것을 `front-iter3` 에서 이어 했다. Figma 를 먼저 그리고(F-36 ~ F-45) 그 프레임대로 코드를 짰다. 코드는 `APP/app/src/main/java/com/swpp/wakeup/` 아래 경로다. 공용 서버는 부르지 않았고 에뮬레이터는 로컬 서버(일회용 SQLite)에 붙였다.

| 항목 | 커밋 | 단위 테스트(누적) | 린트 오류 / 경고 |
| --- | --- | --- | --- |
| S1 이메일 계정 기능 | `1afd2ba` | 902 | 0 / 64 |
| S2 정류장·늦는 이유 | `d536853` | 927 | 0 / 64 |
| S3 ① 알람 전 더 빠른 경로 | `579ca42` | 939 | 0 / 64 |
| S3 ② 알람 끌 때·출발 전 확인 | `c3235a7` | 948 | 0 / 64 |
| S2 고침 | `5adfeaa` | 947 | 0 / 64 (오류 0) |
| 리뷰 지적 m1~m4 고침 | `d79fb67` | 950 | 0 / 62 |

기준은 `c527cea` 의 877건이다. 커밋마다 `testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin` 을 한 번 돌렸다. 코드 리뷰는 승인(막는 문제 없음)이고 사소한 지적 넷(m1~m4)은 `d79fb67` 에서 모두 고쳤다.

### S1. 이메일 계정 기능 — `1afd2ba` (Figma F-36 ~ F-41)
- **바뀐 것**:
  - 이메일 형식 검사(`EmailRule`). 로그인은 안내만 하고, 가입과 비밀번호 찾기는 형식이 맞아야 버튼이 켜진다. 칸을 벗어난 뒤에 "이메일 형식이 아님"이 나온다.
  - 비밀번호 칸마다 "보기/숨김" 토글. 비밀번호 칸에서 키보드 Done 을 누르면 로그인한다.
  - 마지막으로 로그인한 이메일을 기기에 기억해 로그인 화면에 채운다. 로그아웃해도 남고, 이메일 로그인·가입·재설정에 성공했을 때만 저장한다(소셜은 저장하지 않음).
  - 로그인 화면의 "비밀번호 찾기" → 확인 코드 받기 → 새 비밀번호 정하기 화면 두 개.
  - 설정의 "비밀번호" 행 → 비밀번호 변경 화면. 비밀번호로 로그인하는 계정에만 보인다.
- **파일**: `domain/model/EmailRule.kt`, `data/local/LastEmailStore.kt`, `ui/auth/{EmailField,LoginScreen,SignupScreen,PasswordResetScreens,AuthViewModel,LoginActivity}.kt`, `ui/common/JitTextField.kt`, `ui/settings/{ChangePasswordScreen,PasswordChangeState,SettingsScreen}.kt`, `ui/nav/AppRoute.kt`, `data/remote/{AuthApi,AuthInterceptor,ApiClient}.kt`, `data/repository/AuthRepository.kt`, `MainActivity.kt`, `main/res/xml/backup_rules.xml`·`data_extraction_rules.xml`.
- **테스트**: `EmailRuleTest`(3), `AuthRepositoryPasswordTest`(14), `SignupContractTest`(+5), `ApiLogRedactionTest`(+1), `PrefsScopeAuditTest`(+2). 합쳐서 25건이 늘었다(877 → 902).
- **확인**: 단위(서버는 가짜로 대체). 에뮬레이터에서 잘못된 이메일 안내, 보기/숨김 토글, 비밀번호 찾기에 입력한 이메일이 넘어가는 것, 설정의 비밀번호 변경 화면(칸 셋, 규칙 목록, 일치 확인), 로그아웃 뒤 이메일이 남는 것을 봤다.
- **서버가 없을 때**: 세 엔드포인트가 아직 없어서(R3-9) 404·405 에는 "아직 서버에서 지원하지 않아요"를 보인다. 로컬 서버에서 이 문구가 나오는 것까지 확인했다. 코드를 보내고 바꾸는 성공 경로는 가짜 응답 단위 테스트로만 확인했다.
- **정한 것**: 이메일 기억 파일 `jit_last_email.xml` 은 사용자별이 아니라 기기 단위다. 로그아웃·`wipeAll` 로 지우지 않고, 백업·기기 이전에서는 제외한다(`PrefsScopeAuditTest` 에 기록). 비밀번호 변경이 성공하면 서버가 모든 기기를 로그아웃시키므로 이 기기는 새 토큰을 저장한다. 저장에 실패하면 토큰을 지운다.
- **열린 것**: R3-9 (서버). (고침 `d79fb67`, m2) `AuthFlowTest` 의 `launchLoggedOut()` 이 `LastEmailStore` 도 비운다. (m4) `LastEmailStore` 는 `prefs.edit {}` 를 쓴다. 이메일 인증은 만들지 않았다(R3-10).

### S2. 경로 정류장과 늦는 이유 — `d536853`, 고침 `5adfeaa` (Figma F-42)
- **바뀐 것**: 이동 중 알람 결정 화면에 "경로 정류장" 카드(출발 → 승차 → 하차 → 도착)를 더했다. 시각은 실제 출발 시각에 카카오 구간 시간을 더한 예상이다. 출발 시각을 모르면 이름만 보이고 "출발 시각을 몰라 시각은 생략함"을 적는다. 늦을 때는 진행 카드에 이유 한 줄을 붙인다: "출발이 N분 늦었어요" 또는 "이동이 예상보다 N분 길어요". 둘 중 큰 쪽 하나만 적고, 차이가 2분 미만이거나 근거 숫자가 없으면 쓰지 않는다.
- **파일**: `domain/model/{LateCause,CheckpointBasis,LiveRouteDecision,AlarmPlan}.kt`, `ui/alarm/{CheckpointCard,AlarmDecisionScreen}.kt`, `data/repository/EventRepository.kt`, `sensing/{TripLiveState,TripTrackingService}.kt`, `ui/home/HomeViewModel.kt`, `MainActivity.kt`.
- **테스트**: `LateCauseTest`(10), `CheckpointBasisTest`(5), `CheckpointRowsTest`(4), `BaseRouteSegmentsTest`(3), `TripLiveRouteBackgroundContractTest`(+1).
- **확인**: 단위 + 에뮬레이터. 에뮬레이터에서 처음에는 정류장 카드가 안 나왔다. 원인은 정류장의 기준을 실시간 경로의 key 로 잡았는데, 실시간 경로는 현재 위치에서 다시 찾은 것이라 출발지 기준 후보 목록에 없었다. 후보 요청도 한 번 더 나갔다. `5adfeaa` 에서 적용한 경로 또는 계획한 경로의 정류장만 쓰게 고쳤다. 고친 뒤 카드에 출발 → 신림(2호선 승차) → 서울대입구(하차) → 서울대입구역3번출구(5513 승차) → 자연대.행정관입구(하차) → SNU(도착)가 보였다.
- **보지 못한 것**: 늦는 이유 줄(에뮬레이터의 모의 이동이 정시였다), 시각이 붙은 정류장 목록(모의 GPS 가 집 반경 밖으로 튀어서 출발을 놓친 것으로 처리돼 시각이 빠졌다).
- **열린 것**: 알림의 [바꾸기]로 경로를 바꾸면(`TripTrackingService.handleReroute`) 화면의 정류장 카드는 바꾼 경로가 아니라 계획한 경로의 정류장을 보인다. 앱 안 카드로 바꾸면 반영된다. (고침 `d79fb67`, m1) 서비스가 바꾼 key 를 `TripLiveState.recordReroute` 로 올리고 `HomeViewModel` 이 이를 받아 `rerouteApplied` 에 채운다. 앱 안 카드도 같은 기록을 거친다. 기록이 null 이 되면(추적 종료) 화면 상태는 그대로 둔다.

### S3. 출발 전 재계획 — `579ca42`, `c3235a7` (Figma F-43 ~ F-45)
- **바뀐 것**:
  - ① 알람 전: 서버가 주는 대안 경로(`alt_*`)가 지금 경로보다 5분 이상 빠르면 홈 알람 카드와 알람 결정 화면에 "9호선 → 2호선으로 가면 6분 빨라요"와 [이 경로로 바꾸기]를 보인다. 누르면 일정에 저장한 경로를 PATCH 하고 알람을 다시 계산한다. 알람이 울린 뒤에는 보이지 않고, 누를 때도 한 번 더 확인한다.
  - ② 알람을 끈 뒤: 추적 서비스가 알람을 끌 때 한 번, 계획한 출발 10분 전에 한 번, 최대 두 번 `routes/live` 를 불러 지금 나가도 늦는지 본다. 늦으면 헤드업 알림과 준비 중 화면의 카드 "지금 나가도 N분 늦어요"를 보이고, 더 일찍 닿는 경로가 있으면 [이 경로로 바꾸기](이번 이동만 `reroute`)를 붙인다. 없으면 "더 일찍 닿는 경로가 없음"만 보인다.
- **파일**: `domain/model/{FasterRoute,PreDepartureCheck,UpcomingEvent,AlarmPlan}.kt`, `ui/alarm/{FasterRouteRow,AlarmDecisionScreen}.kt`, `ui/home/{HomeScreen,HomeViewModel}.kt`, `data/repository/EventRepository.kt`, `sensing/{TripLiveState,TripTrackingService}.kt`, `MainActivity.kt`.
- **테스트**: `FasterRouteTest`(7), `FasterRouteMappingTest`(5), `PreDepartureCheckTest`(8), `RerouteRepositoryTest`(+1), `AppliedRerouteTest`(+2). 출발 전 확인 경로가 `liveRouteStore`·`publishLiveRoute`·`update` 를 건드리지 않는다는 소스 계약 테스트도 있다.
- **확인**: 단위. 에뮬레이터에서는 알람을 울리고 끈 뒤 아침 기록 화면, 홈, 결정 화면이 죽지 않고 열리는 것까지만 봤다. 알람을 끌 때의 경로 확인은 화면으로 보이지 않았다. 더 빠른 경로 줄·카드, 출발 전 카드와 알림은 기기에서 보지 못했다.
- **열린 것**: 서버가 이 동작을 알도록 하는 요청은 R3-11. (고침 `d79fb67`, m3) 알린 경로 key 를 출발 때 비우지 않아 같은 경로는 한 번의 이동에서 한 번만 알린다.

### S4. 지각 위험 바꾸기 확인
- **확인**: 에뮬레이터에서 동작했다. 결정 화면의 τ 줄이 "적용 τ 0.90 · 수업 일정 기본값"이었고, 지각 위험 바꾸기에서 안전 3:45 / 보통 3:47 / 도박 3:51 이 보였다. "안전으로 계속하기" 뒤 알람이 3:45, τ 줄이 "적용 τ 0.97 · 직접 고른 값"이 됐고 서버는 `PATCH /api/events/4` 200, `dumpsys alarm` 으로 기기 알람이 03:47 → 03:45:01 로 옮겨진 것도 확인했다. 정시 도착 확률은 "아직 계산할 수 없음"으로 남는다(분산 데이터가 없어서, 예상된 동작).
- **바뀐 것**: 없음. 앱도 서버도 고칠 것이 없었다.

### FN. Figma 이름 고침
- 로그인(①) 화면인데 "⑦ 변형"으로 불리던 프레임 `495:9`·`496:9` 와 그 캡션 `496:26`·`496:27` 의 이름과 글을 "① 변형"으로 고쳤다.
- 다른 프레임에서 복사된 캡션 `488:66`(⑫ 일정 수정)의 레이어 이름과 `491:31`(⑦ 늦으면 경로 바꾸기)의 이름과 글을 각 프레임 내용에 맞게 다시 썼다. 자세한 것은 [figma-changes.md](figma-changes.md).

### 이 밤에 지켜진 것
- `backend/` 는 바꾸지 않았다. 필요한 서버 일은 [R3-9 ~ R3-11](backend-requests.md) 에 요청으로 적었다.
- 에뮬레이터 확인은 로컬 서버에서만 했고 확인 뒤 에뮬레이터와 서버를 껐다.
## 11-06 task.md 프론트 할 일 (A1-A7, B1-B5)
`task.md` 의 "🟢 프론트 할 일 (11-04 정리)" 를 `front-iter3` 에서 했다. 서버는 main `5dc51ef`(0.20.0)이고 `backend/` 는 바꾸지 않았다. Figma 를 먼저 그리고(F-46 ~ F-54, [figma-changes.md](figma-changes.md)) 그 프레임대로 코드를 짰다. 코드는 `APP/app/src/main/java/com/swpp/wakeup/` 아래 경로다. 커밋마다 `testDebugUnitTest lintDebug assembleDebug compileDebugAndroidTestKotlin` 을 한 번 돌렸다. 에뮬레이터는 로컬 서버(일회용 SQLite)에만 붙였고 공용 서버는 부르지 않았다. 코드 리뷰는 승인(막는 문제 없음, 사소한 지적 넷)이다.
| 항목 | 커밋 | 단위 테스트(누적) | 린트 오류 / 경고 |
| --- | --- | --- | --- |
| A1-A7 일정 저장 오류 | `6d6627b` | 975 | 0 / 63 |
| B1 종류 직접 추가 | `bdc392e` | 984 | 0 / 63 |
| B3 출발 전 표시, X1, T2 이유 줄 | `b067d14` | 987 | 0 / 63 |
| B2 저장한 집 서버 동기화 | `e024b4d` | 1012 | 0 / 63 |
| B4 주간 기상 패널 | `2da5146` | 1018 | 0 / 63 |
| B5 이메일 인증 | `1bc8617` | 1029 | 0 / 63 |
| 리뷰 지적 m1 (B2 첫 업로드 중 변경) | `495820a` | 1030 | 0 / 63 |
| 리뷰 지적 m2 (B4 미룬 알람 기록) | `954d591` | 1038 | 0 / 63 |
기준은 `5dc51ef` 의 950건 / 경고 62 다. 경고 1개는 `UnusedResources`·의존성 버전 쪽이고 이번에 고친 파일에는 없다.
### A1-A7. 일정 저장 오류 — `6d6627b` (Figma F-46, F-47, F-48)
- **바뀐 것**:
  - A1·A3: 저장이 실패하면 스낵바 "일정을 저장하지 못했어요. 입력한 내용은 그대로 있어요"와 [다시 시도]를 보인다. 네트워크·시간 초과·500 은 칸 위 한 줄에 이유를 적는다. 401 은 로그인으로 보내되 입력을 남긴다.
  - A2: 400 `invalid` 의 `error.details` 를 제목·시각·종류·경로·출발지·도착지 칸 아래에 보인다. 칸이 없는 필드는 `error.message` 로 보인다. 출발지 = 도착지 오류도 이 길로 나온다(`endpointErrorsOf` 를 없애고 `SaveErrorsTest` 로 옮김).
  - A4: 홈의 "일정 추가"에서 저장 못 한 초안이 있으면 "작성 중인 일정이 있어요. 이어서 쓸까요?"를 묻는다(이어서 쓰기·새로 쓰기). 성공했거나 버렸을 때만 지운다. 초안은 디스크에 쓰지 않고 프로세스 안(`AddDraftHolder`)에만 둔다. 앱 프로세스가 죽으면 초안도 사라진다(task.md 의 "(선택) 앱이 꺼져도 남게"는 하지 않음).
  - A5: 추가 화면을 새로 열 때 UUID 를 `AddState.clientRequestId` 에 두고 `POST /api/events` 에 `client_request_id` 로 보낸다. 다시 시도는 같은 값이고 성공·버림 뒤에는 새 값이다. 200 도 성공으로 본다.
  - A6: 201 인데 `alarm_plan.status == "route_failed"` 면 "일정은 저장했어요. 알람을 계산하지 못해 다시 계산이 필요해요"를 보이고 알람 결정 화면으로 이끈다. 결정 화면의 "다시 계산"은 계획이 계산되지 않았을 때만 다시 나온다(`NotComputedCard`, `recomputePlan`).
  - A7: 일정 수정(`submitEdit`)도 같은 오류 표시와 입력 남기기를 쓴다. 수정 뒤 `route_failed` 면 "일정을 고쳤어요. 알람을 계산하지 못해 다시 계산이 필요해요".
- **파일**: `ui/home/{EventSaveErrors,AddDraft,HomeViewModel,RouteChoiceRules}.kt`, `ui/events/{AddEventScreen,AddResumeDialog}.kt`, `ui/alarm/AlarmDecisionScreen.kt`, `data/repository/EventRepository.kt`, `data/remote/EventsApi.kt`, `MainActivity.kt`.
- **테스트**: `SaveErrorsTest`(15), `AddDraftTest`(11), `EventFailureFieldsTest`(+2), `EventRepositoryTest`(+3). `EndpointErrorsTest` 는 `RouteChoiceRulesTest` 로 이름을 바꾸고 8건 중 2건만 남겼다(나머지 6건은 `SaveErrorsTest` 로 옮김). 합쳐서 25건이 늘었다(950 → 975).
- **확인**: 단위 + 에뮬레이터(로컬 서버). 과거 시각으로 저장하면 시각 칸 아래 "반복 시작은 현재 이후로 설정해야 한다."와 스낵바가 떴고 입력이 남았다. 출발지 = 도착지는 도착지 아래 "출발지와 도착지는 50m 이상 떨어져 있어야 한다."가 떴다. 서버를 끄고 저장하면 스낵바 + [다시 시도]와 "서버에 연결하지 못했어요…" 줄이 떴고, 서버를 켠 뒤 빠르게 두 번 저장해도 `POST /api/events` 201 한 번, 일정 1개였다. 초안 이어쓰기 대화상자(이어서 쓰기는 "Past" 유지, 새로 쓰기는 빈 칸)도 봤다. 계획을 `route_failed` 로 바꾼 일정은 결정 화면에 "경로 조회 실패"와 [다시 계산]이 보였고, 누르면 계산되어 "알람을 다시 계산했어요"가 떴다.
- **보지 못한 것**: 만들 때 `route_failed` 로 오는 스낵바 문구(단위 테스트만), 서버의 200 재전송 응답(단위 테스트만; 위 재시도는 스낵바가 먼저 사라져 주 버튼으로 했다), 401 때 초안 보관함 왕복, 일정 수정 화면의 오류 표시.
- **정한 것**: `AddField` 는 `AddState.fieldErrors` 의 키라서 `public` 이다. 다시 계산 컨트롤은 Figma F-48 처럼 `JitPrimaryButton` 이 아니라 강조색 글자 버튼과 설명 한 줄이다. 서버는 시작 시각이 맞을 때만 출발지 = 도착지를 돌려줬다(시각이 틀리면 시각 오류만 옴).
### B1. 종류 직접 추가 — `bdc392e` (Figma F-49, F-49b, F-49c)
- **바뀐 것**: 종류 목록 끝의 "+ 추가" → 이름 대화상자("종류 이름", 20자까지) → `POST /api/events/tags {label}` → 돌려받은 종류를 목록에 넣고 고른다. `default_tau` 가 null 이면 τ 칸에 "프로필 기본". 같은 이름은 서버가 200 으로 돌려주고, 이미 목록에 있는 `key` 는 두 번 넣지 않고 고르기만 한다. 409 `tag_limit` 는 "종류는 20개까지 만들 수 있어요". 추가 화면을 열 때마다 목록을 다시 받고(받는 동안은 이전 목록), 로그아웃하면 목록과 대화상자 상태를 비운다. task.md L250(12-a) 도 이것이다.
- **파일**: `data/remote/EventsApi.kt`, `data/repository/EventRepository.kt`, `ui/events/{AddEventScreen,TagAddDialog}.kt`, `ui/home/{EventSaveErrors,HomeViewModel}.kt`, `MainActivity.kt`, `testing/Fakes.kt`(sharedTest).
- **테스트**: `TagOptionsTest`(+2), `TagAddErrorTest`(4), `EventRepositoryTest`(+3: 201·200 본문 `{"label":"Lab"}`, 409 `tag_limit`, 400 label). 합쳐서 9건(975 → 984).
- **확인**: 단위 + 에뮬레이터. "+ 추가" → `Lab` → "프로필 기본"으로 골라졌고 `lab` 을 다시 넣어도 목록에 Lab 한 줄이었다. 에뮬레이터는 ASCII 만 입력할 수 있어 한글 이름은 보지 않았다.
### B2. 저장한 집 서버 동기화 — `e024b4d` (Figma F-50)
- **바뀐 것**: 기기의 집 목록(`SavedHomesStore`)을 `POST /api/saved-places/bulk/` 로 한 번 올리고, 200 이 오면 서버 목록으로 바꾼다. 그 뒤 추가·삭제·"기본으로"는 API 로 한다(`make-default`). 일정 출발지가 저장한 집이면 생성·수정에 `origin_place_id` 를 보내고, 이때 스위치가 꺼져 있으면 `include_prep: false` 도 보낸다. 설정에는 "다른 집은 계정에 저장돼 다른 기기에서도 보임…" 안내를 보인다. 서버가 404·405 로 답하면 기기 목록을 그대로 쓴다(추가는 기기에 저장). 네트워크 오류·5xx 는 기기 목록을 남기고 나중에 다시 올린다. 400 이면 그 앱 실행 동안은 기기 모드다. 한도(5개)·기본 집과 같은 곳(`same_as_default`) 안내 문구를 더했다. 저장한 집 수정(PATCH) 화면은 만들지 않았다.
- **파일**: `data/remote/{SavedPlacesApi,ApiClient,EventsApi}.kt`, `data/repository/{SavedPlacesRepository,EventRepository,EventEditPatch}.kt`, `data/local/SavedHomesStore.kt`, `domain/model/{SavedHome,SavedHomeSync}.kt`, `ui/home/HomeViewModel.kt`, `ui/settings/SettingsScreen.kt`, `ui/events/AddEventScreen.kt`.
- **테스트**: `SavedHomeSyncTest`(9), `SavedPlacesRepositoryTest`(9), `SavedHomesTest`(+1), `EventRepositoryTest`(+2), `EventEditPatchTest`(+4). 합쳐서 25건(987 → 1012). `PrefsScopeAuditTest` 는 같은 prefs 파일이라 그대로 통과한다.
- **확인**: 단위 + 에뮬레이터. 이전 빌드(`5dc51ef`)에 집 두 개(Home2, Home3)를 저장한 뒤 새 빌드로 덮어 설치했더니 두 행이 남았고 서버 로그에 `POST /api/saved-places/bulk/` 200, 서버 목록 `['Home3', 'Home2']` 였다. 출발지 선택에 "🏠 Home3 / Home2"가 보였고 Home2 에서 출발하는 일정을 저장하니 DB 에 `origin_place_id = 2`, 결정 화면에 "Home2에서 출발"이 떴다.
- **보지 못한 것**: 저장한 집 삭제·"기본으로"·`same_as_default` 오류를 기기에서 직접 눌러 보지 않았다(단위 테스트만). `HomeViewModel` 의 동기화 흐름은 단위 테스트가 없다.
### B3, X1, T2. 출발 전 표시 · 버퍼 문구 · 준비 시간 이유 — `b067d14` (Figma F-54)
- **B3**: 알람을 끌 때와 출발 10분 전의 `POST /api/routes/live` 에 `"context": "pre_departure"` 를 싣는다(`TripTrackingService.runPreDepartureCheck` 만 `preDeparture = true`). 이동 중 갱신은 `context` 를 보내지 않는다(null 이면 Gson 이 빼 준다). 테스트 `EventRepositoryTest`(+1), `TripLiveRouteBackgroundContractTest`(소스 계약을 새 호출에 맞춤). 본문은 단위로만 확인했고 서버 로그로 `context` 를 보지는 않았다.
- **X1**: 안전 버퍼 기본이 5분이 된 것(`5dc51ef`)에 맞춰 `EventRepository.kt` 의 주석 "기본 10분"을 고쳤다. 화면 문구에는 10분 기본 버퍼를 말하는 곳이 없어 바뀐 문구는 없다.
- **T2**: 알람 결정 화면 ④ "계산 방법"의 준비 시간 줄이 `include_prep == true` 이고 출발지가 집이 아니며 저장한 집도 아닐 때 "준비 시간 포함(직접 켬) · "으로 시작한다(`prepEnabledByUser`, `PREP_USER_ENABLED_REASON`). 테스트 `PrepReasonTest`(2). B3·X1·T2 합쳐서 3건(984 → 987).
### B4. 주간 리포트 기상 패널 — `2da5146` (Figma F-51, F-52)
- **바뀐 것**: 주간 응답의 `wake` 를 ⑧ 카드 "기상 · 이번 주"(울린 알람, 미루기, 끄기까지 중간값, 요일 칸)와 ⑨ 카드 "기상"으로 그린다. `wake` 가 null 이거나 `alarms_fired` 가 0 이면 카드를 숨긴다. 중간값이 없으면 "기록 없음"/"—". 숫자는 모두 서버 값이다.
- **파일**: `data/remote/ReportsApi.kt`, `domain/model/WeeklyReport.kt`, `ui/report/{ReportMapping,WeeklyReportScreen,ReportDayScreen,ReportPreviewData}.kt`.
- **테스트**: `ReportMappingTest`(+5), `NetworkDtoNullSafetyTest`(+1). 합쳐서 6건(1012 → 1018).
- **확인**: 단위 + 에뮬레이터. 알람을 울려 한 번 미루고 끈 뒤 패널에 울린 알람 2번, 미루기 1번, 끄기까지 31초, 금요일 칸 "0:31"·"미룸 1"이 보였다. 고친 것 m2 참조(이 숫자가 틀렸던 이유).
### B5. 이메일 인증 — `1bc8617` (Figma F-53, F-53b, F-53c)
- **바뀐 것**: 설정에 "이메일 인증" 행("아직 인증하지 않음 · 로그인에는 필요 없음", 인증되면 "인증됨")과 코드 입력 화면을 더했다. `POST /api/auth/me/email/verify` 로 코드를 받고(`expires_in` 600초), `…/verify/confirm {code}` 로 확인한다. 409(이미 인증·메일 못 받는 계정), 400 세 가지 코드, 429 에 각각 문구가 있다. 404·405 는 "아직 서버에서 지원하지 않아요". 로그인은 막지 않는다. 행은 `email_verified` 와 계정 이메일을 알 때만 보인다. 코드는 메모리에만 두며 저장하지 않으므로 `wipeAll`·감사 테스트·백업 제외는 바꾸지 않았다. HTTP 로그에서는 `"code"` 의 4~8자리 숫자를 가린다(`ApiClient.VERIFY_CODE_FIELD`).
- **파일**: `data/remote/{AuthApi,ApiClient}.kt`, `data/repository/AuthRepository.kt`, `ui/settings/{EmailVerifyState,EmailVerifyScreen,SettingsScreen}.kt`, `ui/nav/AppRoute.kt`, `ui/home/HomeViewModel.kt`, `MainActivity.kt`.
- **테스트**: `AuthRepositoryEmailVerifyTest`(9), `ApiLogRedactionTest`(+1), `AuthResponseNullSafetyTest`(+1). 합쳐서 11건(1018 → 1029).
- **확인**: 단위 + 에뮬레이터. 설정 행 → 서버 콘솔의 코드 입력 → "인증됨". 로그아웃·로그인 뒤에도 "인증됨"이다. "코드 다시 받기" 대기 시간은 보지 않았다.
### X·S·T. 나머지
- **S1 비밀번호 찾기·변경 끝까지 확인**: 에뮬레이터 + 로컬 서버. 비밀번호 찾기 → 콘솔 코드 → 새 비밀번호 → 로그인됨(`/password/reset/confirm` 200). 설정 → 비밀번호 변경으로 원래 비밀번호로 되돌리니 200, "비밀번호를 바꿨어요. 다른 기기는 로그아웃됐어요"가 뜨고 앱은 로그인된 채였다. 앱 코드는 바꾸지 않았다.
- **T1 (task.md L223)**: A2 로 해결. 도착지 아래 줄을 에뮬레이터에서 봤다.
- **T2 (task.md L224)**: `9616680` 에 스위치·초기 상태·출발지 변경 시 초기화·생성과 PATCH 전송이 있었고, 마지막 하위 항목인 결정 화면 이유 줄은 `b067d14` 에서 채웠다. 남은 차이는 스위치가 출발지를 따로 골랐을 때만 보이고(저장한 집 포함), 끄면 보통 `false` 를 보내지 않는다는 것이다(`include-prep-api.md` 5절 3번이 허용함). 예외는 저장한 집(B2)이다. 서버가 저장한 집 출발을 집으로 쳐서 준비 시간을 넣으므로, 끈 것을 `false` 로 보낸다(`createIncludePrep`).
- **T3 (task.md L250)**: B1 로 해결.
### 고친 것 (코드 리뷰 지적 m1, m2)
- **m1 (B2) 첫 업로드 중 추가·삭제가 덮어써짐 — `495820a`**: 처음 `bulk` 업로드가 도는 동안(`savedHomesSyncJob` 이 활성이고 저장소가 아직 `synced` 가 아님) 집 추가·삭제·"기본으로"는 아무것도 하지 않고 "저장한 집을 서버에 올리는 중이에요. 잠시 뒤 다시 해 주세요"만 띄운다(`HomeViewModel.blockedByFirstUpload`). 판단은 순수 함수 `savedHomesFirstUploadRunning` 에 있다. B2 의 나머지는 바꾸지 않았다. 테스트 `SavedHomeSyncTest`(+1, 1029 → 1030).
- **m2 (B4) 미룬 알람이 두 번 셈됨 — `954d591`**: `AlarmSchedule.firstAlarmAtMillis`(계획의 알람 시각, 기본 null, 예전 저장 JSON 은 null 로 읽힘)를 더했다. 미루기 사본은 순수 함수 `AlarmSchedule.snoozedTo` 가 만들고 두 번 미뤄도 처음 값을 지킨다. `alarmLogOf` 는 모든 종류에서 `alarm_at = firstAlarmAtMillis ?: alarmAtMillis` 를 보낸다. `AlarmSyncPlan` 이 미룬 알람을 지킬 때 이 값도 같이 지킨다. 알람이 울리는 시각, `MissedAlarms` 키, SCHEDULED 기록의 비교(`changedRegistrations`)는 그대로다. 테스트 `AlarmLogTest`(+6: 울림·미루기·끄기 기록이 모두 처음 시각, 미루지 않은 알람, 두 번 미룸, 예전 JSON, 저장 왕복), `AlarmSyncPlanTest`(+2). 합쳐서 8건(1030 → 1038). 에뮬레이터에서 다시 확인하지는 않았다(단위 테스트만).
### 열린 것
- **m3 (Figma)**: F-50 의 `same_as_default` 오류는 프레임처럼 이름 칸 아래가 아니라 폼 아래의 기존 오류 줄(`HomeSetupState.error`)에 나온다.
- **m4 (테스트)**: `HomeViewModel` 이 `Application` 을 요구해서 `syncSavedHomes`, `onAddEventClick`, `onCleared`, `logout`, 이메일 인증 흐름은 단위 테스트가 없다. 결정은 테스트된 순수 함수로 뺐고 에뮬레이터로 확인했다.
- 기기 테스트(`androidTest`)는 이번에도 컴파일만 했다. 실기기 확인과 공용 서버 확인은 하지 않았다.
- 에뮬레이터 확인 중 홈이 스크립트로 바꾼 계획을 앱을 다시 시작하기 전까지 보이지 않은 적이 있다(당겨서 새로고침 첫 시도). 테스트 준비 방식 때문일 수 있고 더 보지 않았다.
- `backend-requests.md` 에 새로 적은 요청은 없다. 이번 필요는 모두 0.20.0 에 있다.
## 정한 기본값
코드로 확인한 값이다. 바꾸고 싶으면 괄호의 상수를 고친다.
- 이동 중 경로 바꾸기 알림은 탑승 중이면 미룬다. 6 m/s(약 22 km/h) 이상으로 움직이면 2분 동안 탑승 중으로 본다(`RerouteAlert.RIDING_SPEED_MPS`, `RIDING_HOLD_MILLIS`). 남은 이동이 5분 이하이거나 목적지까지 400m 이하여도 알리지 않는다(`NEAR_ARRIVAL_MINUTES`, `NEAR_ARRIVAL_METERS`). 미룬 경로는 "알림 보냄"에 넣지 않아서 내린 뒤 다음 갱신에서 알린다.
- 줌 단계는 로그2 배율 0.42 부터 바뀐다(`MapCameraMath.ZOOM_STEP_THRESHOLD_LOG2`).
- 도착 기록은 3일, 30건까지 둔다(`ArrivalLedger.KEEP_MILLIS`, `MAX_ENTRIES`. 저장은 `data/local/ArrivalStore.kt`). 같은 일정의 서버 확인은 5분 간격이다.
- 수동 도착 뒤 GPS 도착을 3분 더 기다린다(`TripTrackingService.MANUAL_GRACE_MILLIS`). 그 사이 GPS 도착이 나면 서버가 GPS 쪽을 먼저 쓴다.
- 홈으로 돌아올 때의 다시 불러오기는 60초에 한 번이다(`HomeList.RESUME_MIN_INTERVAL_MILLIS`).
- 서버 요청의 `from` 은 지금 − 5시간이다(`HomeList.SERVER_LOOKBACK_MILLIS`, 팀 결정 10-26).
- 시작한 일정은 도착이 확인될 때까지, 아니면 시작 + 60분까지 홈에 남는다(`HomeList.OPEN_TRIP_MILLIS` = `AlarmSchedule.TRACKING_GRACE_MILLIS`).
- 다른 집은 최대 5개, 50m 안의 같은 자리는 새로 만들지 않고 바꾼다(`SavedHomes.MAX`, `SAME_PLACE_METERS`). 이름은 12자까지.
- 로고는 시안 A 로 정했다.
- 일정 수정 화면은 일정 추가 화면을 수정 모드로 다시 쓴다.
- 이동 중 추적 사본은 6시간 안의 같은 일정에만 쓴다(`TripProgress.MAX_AGE_MILLIS`).
- 늦는 이유는 2분 이상 차이일 때만 쓴다(`LateCause.MIN_MINUTES`). 출발 지연과 이동 초과가 같으면 출발을 말한다.
- 알람 전 더 빠른 경로는 5분 이상 빠를 때만 제안한다(`FasterRoute.MIN_FASTER_MINUTES`).
- 출발 전 확인은 한 번의 추적에서 최대 2번이다(`PreDepartureCheck.MAX_CALLS`). 두 번째는 `max(출발 − 10분, 첫 확인 + 5분)` 에 하고(`LEAD_MILLIS`, `MIN_GAP_MILLIS`), 20분이 지난 결과는 카드로 보이지 않는다(`MAX_AGE_MILLIS`).
- 마지막 로그인 이메일은 기기 단위로 저장하고 로그아웃으로 지우지 않는다(`LastEmailStore`). 확인 코드는 6자리다(`AuthViewModel.RESET_CODE_LENGTH`).
- 서버가 비밀번호 엔드포인트에 404·405 로 답하면 "아직 서버에서 지원하지 않아요"를 보인다. 저장한 집·이메일 인증도 같다.
- 쓰다 만 일정(초안)은 디스크에 쓰지 않고 프로세스 안에만 둔다(`AddDraftHolder`). 제목·목적지·출발지·경로·반복 요일 중 하나라도 채웠으면 초안이다. 날짜·시각·종류만으로는 초안이 아니다.
- 일정 추가 화면을 새로 열 때마다 `client_request_id` 로 새 UUID 를 만든다. 실패한 뒤 다시 시도하는 동안은 같은 값이다.
- 직접 만드는 종류 이름은 20자까지이고 계정당 20개까지다(`TAG_LABEL_MAX`, 서버 `tag_limit`). τ 는 보내지 않아서 "프로필 기본"이다.
- 저장한 집 `bulk` 업로드는 한 번에 20개까지이고 기기 목록은 5개까지다(`SAVED_PLACES_BULK_MAX`, `SavedHomes.MAX`). 서버가 200 으로 답해야 기기 목록을 서버 목록으로 바꾼다.
- 출발 전 확인(알람을 끌 때, 출발 10분 전)만 `context: "pre_departure"` 를 싣는다. 이동 중 갱신은 싣지 않는다.
- 주간 기상 카드는 `wake` 가 있고 `alarms_fired` 가 0 보다 클 때만 보인다.
- 이메일 인증 코드는 6자리다(`EmailVerifyState.CODE_LENGTH`). 코드는 저장하지 않는다.

## 사용자나 팀이 해야 하는 것
- 이 PC 의 디버그 키를 카카오·구글 콘솔에 등록한다. 지금은 김수연의 디버그 키만 있다. 값은 [R3-4](backend-requests.md) 에 있다.
- 실기기에서 확인한다: 카카오·구글 로그인, 이동 중 경로 바꾸기 헤드업 알림, 추적 알림의 "도착했어요", GPS 출발·도착, 집을 나선 뒤 "기록 끝내기"가 안 나오는지, 로고와 스플래시, 핀치 줌 느낌.
- `JWT_ROTATE_REFRESH_TOKENS=1` 은 팀원이 이 브랜치의 앱을 쓴 뒤에 켠다([R3-5](backend-requests.md)).
- 서버 쪽 요청: [R3-1 ~ R3-11](backend-requests.md). 이메일 계정 기능(비밀번호 찾기·변경)은 R3-9 가 나와야 동작한다. `SavedPlace`(R3-2)의 기기 목록 올리기는 11-06 에 앱에 넣었다(B2).
- 공용 서버(Render)가 이 브랜치가 쓰는 필드(`preferred_key`, `switch_suggestion`, `skipped`, 패치 0034·0036·0038 등)를 이미 내는지는 보지 않았다. 공용 서버는 부르지 않았다.

## 하지 않은 것
- **도착 감지 개선(활동 인식·기압계·Wi-Fi)**: 팀 회의에서 새 센서는 쓰지 않기로 했다("센서 필요 없을 듯").
- **이메일 인증(R3-10)**: 11-06 에 설정 화면을 만들었다(B5). 5b·5c·19b 는 11-03 밤에 만들었다.
- **리포트의 수면·요일별 조정 패널**: 수면·요일별 자동 조정은 읽을 API 가 없다. 알람 울림·미루기·기상 패널은 11-06 에 만들었다(B4).

## 에뮬레이터 확인 (11-02)
- **준비**: AVD `SWPP_APP`, `44fd887` 의 디버그 빌드(`devServerHost=10.0.2.2`), 이 워크트리의 로컬 백엔드(main 과 같은 코드, 0.17.0)를 일회용 SQLite 로, 시험 계정, 집 신림역(37.4842, 126.9295), 일정 "GPS check"(서울대학교, 37.4598, 126.9511), 가짜 GPS 는 `adb emu geo fix <lng> <lat>`. 공용 서버는 쓰지 않았다.
1. **홈**: 다음 알람 카드와 "내일" 행이 보였다. 일정 요청은 `from` = 지금 − 5시간이었다(23:34 에 `GET /api/events?from=…T09:34:22Z`).
2. **결정 화면**: "적용 알람" 카드는 알람 시각과 "정시 도착 확률 · 아직 계산할 수 없음"만 보였다. "계산 방법" 카드에 "2호선 → 5513 · 4.8km 기준", 준비 28분(고정값), 이동 23분, 안전 버퍼 10분(고정값), 합계 61분, τ 줄, "방금 계산". "다시 계산" 버튼은 없었다. 맨 아래 줄은 지각 위험 바꾸기 · 안 감으로 표시 · 일정 수정 · 일정 삭제.
3. **수정 화면**: 제목 "일정 수정", 안내 "바꾼 항목만 저장함. 장소·출발지를 바꾸면 경로를 다시 고름", 칸은 일정 값으로 채워졌다.
4. **수정 화면에서 경로 선택**: 대중교통 탭에 카드 7개(23·24·25·26·27·33·34분, 23:37). 배지는 카드마다 최대 둘이고 한 줄이었다("최소 비용"+"최소 도보", "최소 비용"+"최소 환승"). 합친 카드 "5528 → 5511 (대체 2개)"는 승차에 5528·5519, 환승에 5511·5516 칩이 보였다. 이 일정은 스크립트로 만들어 서버가 `preferred_key` 를 주지 않았고, 규칙대로 `fastest_key`(자동차 13분)가 이겨서 자동차 탭으로 열렸다. 기록만 하고 바꾸지 않았다.
5. **알람**: 23:43:57 에 울렸다(`AlarmActivity`). "일어났음 · 이동 기록 시작"을 누르자 `TripTrackingService` 가 시작됐다.
6. **보라색 보정 경로**(todo "이동 중 보라색 보정 경로 확인"): 이동 중 지도 제목은 "이동 중 · 현재 위치부터 가장 빠른 길"이고, 현재 위치에서 목적지까지 보라 선이 그려졌다. `POST /api/routes/live` 가 1분마다 갔고, 경로가 바뀌면 선과 요약을 다시 그렸다("여기서부터 6515 → 5513 · 8분 빠름" → "여기서부터 2호선 → 5513 · 27분"). 이동 카드는 "이동 중 · 1.0km 이동", "도착했어요" 버튼과 설명. 코드 변경은 필요 없었다.
7. **경로 바꾸기**: 서버에서 일정 시작을 00:04 로 옮기자 live 응답에 `switch_suggestion` 이 왔다. 헤드업 알림이 한 번 떴다("지금 경로로는 20분 늦어요" / "6515 → 5513으로 가면 5분 덜 늦어요"). 이동 카드 아래 배너("… · 0:18 도착 예상", [이 경로로 바꾸기], "이번 이동만 바뀜. 일정에 저장한 경로는 그대로임")를 눌러 `POST /api/events/1/reroute` 가 201 이었고 배너가 사라졌다. 서버에서 시작 시각을 앱 몰래 바꿔서 이동 카드는 "정시"로 남았다. 앱 버그가 아니다.
8. **지도**: 200px 드래그 한 번에 정적 지도 요청이 정확히 한 번 갔다. "경로 전체 보기"는 다시 맞췄고, 현재 위치 버튼은 현재 위치로 옮겼다.

## 11-02 에서 나온 문제와 처리
### 토큰이 디버그 로그에 찍힘 → `30284f3`
- 디버그 빌드에서 `HttpLoggingInterceptor` 가 `Authorization` 헤더와 로그인·갱신 본문의 비밀번호·토큰을 그대로 찍었다.
- **고침**: `ApiClient.kt` 가 `Authorization` 헤더를 가리고(`redactHeader`), 본문의 `password`·`password_confirm`·`old_password`·`new_password`·`access`·`refresh`·`id_token` 값을 `***` 로 바꾼다. 이스케이프한 따옴표가 든 값도 끝까지 가린다. 로그 수준 BODY 는 디버그(와 e2e) 빌드에서만 켜고, 릴리스는 `NONE` 이다.
- **테스트**: `ApiLogRedactionTest` 6건.
- **확인**: 에뮬레이터 logcat 에서 `Authorization: ██`, 로그인 본문 `"password":"***"`, 응답 `"access":"***"`·`"refresh":"***"` 였고 `Bearer`·`eyJ` 를 포함한 줄은 없었다.

### 결정 화면 지도가 한 번 크게 튐 → 원인 일부만 찾음, 지도 코드는 안 고침
- **본 것**: 23:51 에 지도 중심이 한 번에 약 40km 북쪽으로 갔다가(C1) 곧 2km 북쪽(C2)으로 돌아와 멈췄다. 정적 지도 요청 좌표가 근거다(23:49:35 M → 23:51:24 C1 → 23:51:25 C2).
- **찾은 것**: 두 이동 모두 지도 격자 북쪽으로만 움직였고(x 변화 4cm 이하), 크기가 정확히 `r`(= 341px, `MapCameraMath.layer` 가 허용하는 최대 이동)의 1배(C2)와 20배(C1)였다. C1 → C2 는 `5cad0a6` 가 설계대로 동작한 결과다(화면에 있던 이미지 중심 + r 에 확정되고, 그 자리가 고정점이다). 맞춤·현재 위치 버튼은 x 도 바뀌므로 원인이 아니다. 지도 중심을 화면 픽셀로 옮기는 코드는 손짓이 끝날 때(`onRouteMapGestureEnd` → `finishGesture`)뿐이다.
- **못 찾은 것**: M → C1(20r)이 어떻게 생겼는지. 낡은 레이어 기준으로 확정이 스무 번쯤 쌓여야 하는데 그런 경로를 코드에서 찾지 못했고, JVM 테스트로 재현하지 못했다. 당시 에뮬레이터 창이 열려 있어 23:51 에 마우스 입력이 들어갔을 가능성이 있다(입력 기록이 없음). 추측으로 고치지 않았다. 당시 logcat 은 이미 지나가서 없었다.
- **같이 고친 것**: 취소된 정적 지도 호출을 `ColdStartRetryInterceptor` 가 "재시도"로 1~2초 기다리던 것을 막았다(`c907c6a`, 테스트 `ColdStartRetryInterceptorTest` › `취소된 GET 은 재시도하지 않는다`). 이미 나간 요청은 막지 못한다.
- **열린 것**: 에뮬레이터에서 logcat 과 `adb shell getevent -lt` 를 켜 두고, 지도를 만지지 않은 채 가짜 GPS 와 15분 `RouteRefreshWorker` 를 돌려 다시 재현한다. 터치 없이 튀면 손짓 끝 확정 경로를 의심하고, 확정을 `_routeMap.value.center` 가 아니라 화면에 보이는 이미지 기준으로 하는 방안을 검토한다(동작이 바뀌므로 먼저 기기 기록이 필요하다). 자세한 숫자는 작업 기록 `c-findings.md`(저장소 밖)에 있다.

## 팀원 확인: main 의 대중교통 경로 5개 이상 (11-03)
- **질문**: "지금 main 버전으로 휴대폰으로 확인했을 때 대중교통 경로 5개 이상 뜨는지."
- **답**: 뜬다. 7개가 떴다(main 의 상한도 7개).
- **방법**: main `3d39fc8` 빌드를 에뮬레이터에서, 같은 서버 코드의 로컬 서버로 확인했다. 신림역("집 · Sillim Station", 가짜 GPS 37.4842, 126.9295) → 서울대학교 관악캠퍼스(서울 관악구 관악로 1), 11-03 00:30 조회(`api/routes/candidates` 한 번), 도착 일정은 11-04 오전 9시. 서버는 카카오에 좌표만 보내고 시각은 보내지 않아서 결과는 조회한 순간의 카카오 값이다.
- **결과**: 카드 7개, 23·24·25·26·27·33·34분. 11-02 의 front-iter3 확인과 같은 일곱 개다. 스크롤을 더 해도 8번째는 없었다.

  | # | 분 | 9시 도착이면 출발 | 배지 | 요약 |
  | --- | --- | --- | --- | --- |
  | 1 | 23 | 8:37 | 최단 시간 | 2호선 → 5513 · 5.1km · 환승 1 · 1,550원 |
  | 2 | 24 | 8:36 | 없음 | 신림선 → 5511 (대체 1개) · 4.5km · 환승 1 · 1,550원 |
  | 3 | 25 | 8:35 | 최소 비용, 최소 도보 | 관악08 → 5516 · 4.7km · 환승 1 · 1,500원 |
  | 4 | 26 | 8:34 | 최소 비용, 최소 환승 | 5516 · 4.6km · 1,500원 |
  | 5 | 27 | 8:33 | 최소 비용 | 5528 → 5511 (대체 2개) · 4.8km · 환승 1 · 1,500원 |
  | 6 | 33 | 8:27 | 없음 | 2호선 → 관악02-1 · 7.1km · 환승 1 · 1,550원 |
  | 7 | 34 | 8:26 | 최소 환승 | 신림선 · 4.4km · 1,550원 |
- **주의**: 개수는 경로와 조회 시간대에 따라 달라질 수 있다. 이 확인은 에뮬레이터와 로컬 서버로 했고 실제 휴대폰은 아니다. 목적지 좌표는 화면과 요청 로그에 나오지 않아 적지 못했다. 카카오 사용은 장소 검색 한 번과 경로 한 번이다. 일정은 저장하지 않았다.