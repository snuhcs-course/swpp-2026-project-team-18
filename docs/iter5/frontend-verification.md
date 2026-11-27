# Iteration 5 프론트엔드 검증

2026-11-27 · 김진호 · `front-iter5` · 기준 커밋 `c3ea88f`

## 실행 결과

| 검사 | 결과 | 근거 |
| --- | --- | --- |
| JVM 테스트 | 1,743개 통과, 실패·오류·건너뜀 0개 | `APP/app/build/test-results/testDebugUnitTest/` |
| ViewModel 줄 커버리지 | **32.8%** · 1,311/3,997줄 · 목표 30% 충족 | [계층별 보고서](evidence/coverage.md) |
| Platform 줄 커버리지 | **43.1%** · 1,061/2,463줄 · 목표 30% 충족 | 같은 JaCoCo XML |
| 전체 JVM 줄 커버리지 | 33.0% · 화면 계층은 JVM 결과만으로 11.0% | 계층별 보고서 |
| Android 화면·초기화 테스트 | **24개 통과** · API 36 에뮬레이터 | [최종 기기 실행 로그](evidence/device-smoke.log) |
| 알람 근거 분석기 | Python 테스트 13개 통과 | `APP/scripts/tests/test_alarm_evidence.py` |
| 릴리스 빌드 | `0.17.0` / versionCode 18 / minSdk 34 | [APK](../../APP/app/build/outputs/apk/release/app-release.apk) |
| APK 서명 | 기존 팀 키, APK v2 검증 통과 | [서명 검사](evidence/release-signature.txt), [SHA-256](evidence/release-apk.sha256) |
| Android Lint | 오류 0개 · 경고 65개 · 힌트 1개, 작업 성공 | `APP/app/build/reports/lint-results-debug.xml` |
| 공백 검사 | `git diff --check` 통과 | 최종 작업 트리 |

커버리지는 기존 `coverage_summary.py`의 파일별 집계 규칙을 그대로 사용했다. 목표를 맞추려고 소스를 제외하거나 계층을 바꾸지 않았다. Domain 97.4%, Data 73.5%이며, 화면·실기기 커버리지를 JVM 수치로 대체하지 않았다. Lint의 기존 코드를 포함한 경고를 전부 해결했다고 주장하지 않는다.

## 확인한 동작

- 관측 JSON에서 좌표 제외, 예전 대기 큐 좌표 정리, 이동 중/출발 전 live API 호출 0회, GPS가 검색·지도 중심으로 전달되지 않는 회귀 검사
- 약속방 DTO 검증, 미지원·권한 오류, 생성·참가·재시도, 멤버별 알람·조정 제안, 오래된 응답·중복 저장·취소 처리
- Trip·AlarmDecision·Me·EventEditor ViewModel의 요청 취소·세대 보호·계정 종료, 비밀번호 변경 후 토큰 덮어쓰기 방지, 이전 계정 리포트 알림 방지
- 알람 상세의 측정/고정·보정 횟수, 확률 부재 이유와 확인 버튼, 실제 AI 요약 라벨
- 이동 탭에서 직접 진행 상태·도착 동작, 홈 알림 안내, 집 이름 변경, 1.5배 글자에서 집 관리 동작의 표시·48dp 터치 영역
- 실제 Android 저장소를 사용하는 `HomeViewModel`의 생성·화면 이동·종료. 생성자 초기화 순서를 소스 문자열만으로 검사하지 않았다.

## 기기 테스트 조건과 캡처

JBR 25·Android SDK 37로 빌드하고 API 36 에뮬레이터에서 실행했다. 개인 앱 `com.swpp.wakeup` 대신 **`com.swpp.wakeup.qa`**와 테스트 APK만 설치했다. 에뮬레이터는 읽기 전용으로 실행했고, 화면용 값과 응답은 가짜 저장소로 주입했다. 공용 서버의 계정·방·일정을 생성하지 않았다. 마지막 캡처는 기기 시간대를 `Asia/Seoul`로 맞췄다.

| 검토 화면 | 캡처 |
| --- | --- |
| 약속방 목록 | [목록](evidence/screenshots/iter5-rooms/rooms-list.png) |
| 방 만들기 | [생성](evidence/screenshots/iter5-rooms/rooms-create.png) |
| 초대 코드 | [참가](evidence/screenshots/iter5-rooms/rooms-join.png) |
| 멤버별 기상 시각 | [방 상세](evidence/screenshots/iter5-rooms/rooms-detail.png) |
| 알람 확인·확률 부재·계산 근거 | [알람](evidence/screenshots/iter5-frontend/alarm-confirm.png) |
| 홈의 알림 차단 안내 | [홈](evidence/screenshots/iter5-frontend/home-notifications.png) |
| 집 관리·이름 변경 동작 | [집](evidence/screenshots/iter5-frontend/homes.png) |
| 1.5배 글자 | [큰 글자](evidence/screenshots/iter5-frontend/homes-large-font.png) |
| 이동 탭 직접 진행 표시 | [이동](evidence/screenshots/iter5-frontend/trip-progress.png) |

[Figma 변경 설계](https://www.figma.com/design/FoiB0Q7bTIjUsjhiQLefJQ?node-id=711-2500)와 [집 이름 대화상자](https://www.figma.com/design/FoiB0Q7bTIjUsjhiQLefJQ?node-id=716-2845)를 확인하고 캡처에서 발견한 보조 버튼·뒤로 배치·이동 중 안내를 조정했다. 기본/실패 상태는 분리했고, 변경 전부터 있던 전체 화면 구성은 기존 앱 컴포넌트를 재사용했다. 캡처의 일정·닉네임·장소·시각은 검토용 값이며 베타 관측 자료가 아니다.

## 재현

`APP` 폴더에서 실행한다. 로컬 SDK·Firebase·팀 서명 설정은 기존 개발 환경의 비공개 설정을 사용하며 저장소에 넣지 않는다.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:jacocoDebugUnitTestReport :app:assembleRelease :app:lintDebug --offline
python scripts/coverage_summary.py
python -m unittest discover -s scripts/tests -p 'test_*.py'

.\gradlew.bat :app:assembleE2e :app:assembleE2eAndroidTest -PjitE2e=true --offline
```

`jitE2e=true`는 기기 테스트 변형을 바꾸므로 기본 JVM 검증 명령과 별도로 실행한다. QA 변형에도 Compose 테스트 Activity 의존성을 추가했다. 최종 Android 실행 대상은 `RoomsScreenTest`, `Iter5FrontendSmokeTest`, `HomeViewModelStartupTest`, `AlarmDecisionScreenTest`, `ReportSummaryCardTest`, `RouteChoiceLayoutTest`, `HelpScreenTest`다.

## 미검증·협업이 필요한 항목

- **설치·배포 선행 조건:** BE가 좌표 없는 GPS 관측을 허용하고 기존 관측 좌표를 지우는 변경을 앱보다 먼저 또는 동시에 배포해야 한다. 이 후보 APK를 운영 배포하지 않았다.
- 약속방 실제 서버, 개인 Event·멤버별 알람, 확률 분배·탐욕 선택·다른 방 접근 차단은 BE 구현 후 통합 검증해야 한다. [앱의 계약 제안](rooms-api.md)을 참고한다.
- `wake_late`는 앱 표시를 지원하지만 실제 알람 로그 기반 판정은 BE가 보내야 한다. 준비 지연에 따른 확률 재계산·경고는 이번 FE 구현에 포함하지 않았다.
- 실기기 7일 알람·5초 오차·24시간 배터리 측정, 개발 도구 없이 신규 가입부터 첫 알람까지의 검증은 남아 있다. 빈 실측 양식의 분석 결과는 N1/N2/N11 모두 `incomplete`다. 7일 검사로 30일 무누락을 입증하지 않는다.
- Accessibility Scanner 5화면과 TalkBack 실제 탐색, 2차 UAT의 밀도 판단은 남아 있다. 큰 글자 테스트와 화면 캡처를 Scanner 결과로 간주하지 않는다.
- 다섯 책임의 주요 상태·요청은 분리했지만 일정 폼·경로 선택·지도·알람 상세 등 일부 책임은 Home에 남아 있다. 예상형 뒤로 가기 미리보기도 남아 있다.
