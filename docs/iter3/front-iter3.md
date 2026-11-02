# Iteration 3 프론트 작업 기록 — 팀 18 JustInTime
브랜치 `front-iter3`, 기준 `origin/main` = `3d39fc8`(10-31). 백엔드가 `todo.md` §2 에 넘긴 일과 팀 회의 내용을 앱에 넣었다. 항목 번호는 [figma-changes.md](figma-changes.md) 의 번호와 같다(1~22 는 아래 절 제목, 5b·5c·19b 는 만들지 않음). 서버에 아직 없는 부분은 [backend-requests.md](backend-requests.md) 에 요청으로 적었다.

## 요약
- 단위 테스트: main 기준 **739건 → 877건**, 실패 0 (`APP\app\build\test-results\testDebugUnitTest\*.xml` 111개, 최신 커밋 `c907c6a` 의 실행 결과).
- 린트: 오류 0 / 경고 62 (기준선과 같음). 마지막 린트 실행은 `44fd887` 시점(11-02 20:03)이다. 그 뒤 커밋 둘(`30284f3`, `c907c6a`)은 `ApiClient.kt`·`ColdStartRetryInterceptor.kt` 와 테스트만 바꿨고 린트는 다시 돌리지 않았다.
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
- **5b·5c**: 출발 전 재계획(5b), ⑦ 화면의 체크포인트(5c)는 범위 밖이라 만들지 않았다.

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
- **19b**: 이메일 계정 기능(마지막 이메일 기억, 비밀번호 보기 토글 등)은 범위 밖이라 만들지 않았다.

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

## 사용자나 팀이 해야 하는 것
- 이 PC 의 디버그 키를 카카오·구글 콘솔에 등록한다. 지금은 김수연의 디버그 키만 있다. 값은 [R3-4](backend-requests.md) 에 있다.
- 실기기에서 확인한다: 카카오·구글 로그인, 이동 중 경로 바꾸기 헤드업 알림, 추적 알림의 "도착했어요", GPS 출발·도착, 집을 나선 뒤 "기록 끝내기"가 안 나오는지, 로고와 스플래시, 핀치 줌 느낌.
- `JWT_ROTATE_REFRESH_TOKENS=1` 은 팀원이 이 브랜치의 앱을 쓴 뒤에 켠다([R3-5](backend-requests.md)).
- 서버 쪽 요청: [R3-1 ~ R3-8](backend-requests.md). 앞의 `SavedPlace`(R3-2)가 나오면 앱이 기기 목록을 한 번 올리는 일을 더해야 한다.
- 공용 서버(Render)가 이 브랜치가 쓰는 필드(`preferred_key`, `switch_suggestion`, `skipped`, 패치 0034·0036·0038 등)를 이미 내는지는 보지 않았다. 공용 서버는 부르지 않았다.

## 하지 않은 것
- **도착 감지 개선(활동 인식·기압계·Wi-Fi)**: 팀 회의에서 새 센서는 쓰지 않기로 했다("센서 필요 없을 듯").
- **5b 출발 전 경로 재계획, 5c ⑦ 화면의 체크포인트, 19b 이메일 계정 기능**: 범위 밖으로 정했다.
- **리포트의 수면·미루기·요일별 조정 패널**: 기록하는 데이터를 읽을 API 가 없다([R3-6](backend-requests.md)).

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