# Iteration 5 일정표 반영 검증

2026-11-28 · 김진호 · `front-iter5` · 앱 `0.18.0` / versionCode 19 / minSdk 34

범위는 [일정표 이행표](schedule-frontend-status.md)와 같다. [기계 판독 요약](evidence/schedule-tests.json), [백엔드 요약](evidence/schedule-backend-tests.json), [기기 실행 원문](evidence/schedule-device-tests.log)을 남겼다.

## 실행 결과

| 검사 | 실제 결과 |
| --- | --- |
| Android JVM 전체 | **1,783개 통과**, 실패·오류·건너뜀 0개 |
| Android 화면·저장소 | **107개 통과**, `com.swpp.wakeup.integration` 패키지 전체 |
| Django pytest 전체 | **2,536개 통과**, 실패·오류·건너뜀 0개 |
| 마이그레이션 변경 누락 | `makemigrations --check --dry-run --settings=config.settings.test` 통과 |
| ViewModel 줄 커버리지 | **32.6%** · 1,311/4,017줄 · 목표 30% 충족 |
| Platform 줄 커버리지 | **43.1%** · 1,061/2,461줄 · 목표 30% 충족 |
| 전체 JVM 줄 커버리지 | **33.2%** · 기존 계층 분류와 분모 제외 규칙 유지 |
| Android Lint | 오류 **0**, 기존 포함 경고 65·힌트 1, 작업 성공 |
| 서명 release 빌드 | `assembleRelease`·`lintVitalRelease` 성공, APK v2 서명 검증 통과 |
| QA 빌드 | `assembleE2e`·`assembleE2eAndroidTest` 성공 |

[계층별 JaCoCo 표](evidence/schedule-coverage.md)는 기존 `coverage_summary.py`로 계산했다. 화면을 다른 계층으로 옮기거나 커버리지 분모에서 제외해 목표를 맞추지 않았다. JVM의 화면 계층 수치는 기기 화면 검증을 대신하지 않는다. 기존 Compose 테스트 API의 사용 중단 경고와 테스트용 JWT 키 길이 경고를 모두 해결한 변경은 아니다.

## 검증한 경계

- 고정 동작: 360×420~500dp 할당 영역과 1.5배 글자에서 홈 확인·루틴·추가, 알람 상세 확인·수정, 일정 저장, 경로 확인이 본문 스크롤과 독립적으로 보이고 눌린다. 키보드로 줄어든 영역은 300dp 할당으로도 확인했다.
- 알람 확인: 서버가 미확인으로 알려 준 계산된 미래의 켜진 알람만 확인할 수 있다. 값 미상·실패 계획·꺼짐·건너뜀·지난 실제 시각·로딩·오프라인은 확인 대상으로 만들지 않는다.
- 빠른 선택: 동일·근접 시각, 자정 경계, 실제 서버 key·τ·확률 유지, 직접 지정 시각 유지, 비동기 선택지·계획 응답, 사용자가 선택한 뒤 늦은 초기 시각 도착, 동일 시각의 다른 일정 전환을 확인했다.
- 추천 복원: 이전 직접 선택한 τ를 해제하고 종류·프로필 기본 추천을 저장된 경로로 복원한다. 원 경로를 재사용할 수 없으면 409로 롤백한다. 외부 경로 API를 호출해 수치를 만들지 않는다.
- 이동: 이동 탭은 중복 진행 화면을 그리지 않으며 알람 상세로 연결한다. 상세에는 실제 진행·도착 동작과 출발 전 경로 순서가 있다. 관측 창이 만료되었다고 도착 완료로 만들지 않는다.
- 직접 도착: 위치가 오래됨·미확인·부정확함·100m 반경 밖임을 기기에서 구분한다. 앱과 알림은 직접 기록 확인을 거치고, 확인 사이 도착·만료·로그아웃이 발생하면 다시 기록하지 않는다.
- 접근성: 설정·소셜 연결의 역할과 콜백, 반복 요일·종류·되돌리기·아침 기록 동작·날짜 7개의 실제 48dp 영역, 큰 글자에서의 줄바꿈·날짜 가로 스크롤을 확인했다. 큰 글자 수치 상자는 실제 캡처에서 좁게 꺾이던 문제를 확인하고 세로 배치로 보완한 뒤 재검증했다.
- 좌표 없는 관측: 새 GPS 입력과 구버전 GPS·수동 좌표 입력, 응답 좌표 제외, 파생값 검증·멱등 처리, 실제 `0007→0008` 이행에서 관측 좌표 삭제와 집·선택 목적지·출발지·알람 기록 보존을 검증했다.
- 탐색: 설정·권한·비밀번호·홈 관리·도움말·안내 복귀, 다른 탭에서 저장이 끝나는 경우, 종료된 계정에 늦은 콜백이 도착하는 경우를 확인했다.

## 화면 증거

캡처는 가짜 일정·장소·계정 상태를 주입한 QA 화면이다. 큰 글자·좁은 할당 영역에서는 스크롤된 본문의 일부가 보이며, 아래 고정 동작이 가리지 않는 별도 영역인지도 실제 경계 좌표로 확인했다.

| 항목 | 캡처 |
| --- | --- |
| 홈 확인·루틴·추가 고정 | [홈](evidence/screenshots/schedule/iter5-pinned-actions/home-fixed-actions.png) |
| 상세 확인·시각 변경·수정 고정 | [알람 상세](evidence/screenshots/schedule/iter5-pinned-actions/alarm-fixed-actions.png) |
| 큰 글자 근거 수치·출처 | [수치 상자](evidence/screenshots/schedule/iter5-pinned-actions/alarm-source-boxes.png) |
| 알람 확인 시트의 고정 동작 | [확인 시트](evidence/screenshots/schedule/iter5-pinned-actions/confirmation-fixed-action.png) |
| 일정 수정 저장 고정 | [일정 수정](evidence/screenshots/schedule/iter5-form-actions/edit-save-large-text.png) |
| 작은 할당 영역 | [300dp 추가 화면](evidence/screenshots/schedule/iter5-form-actions/add-short-viewport.png) |
| 경로 확인 고정 | [경로 목록](evidence/screenshots/schedule/iter5-form-actions/route-confirm-large-text.png) |
| 아침 기록의 큰 글자 동작 | [아침 기록](evidence/screenshots/schedule/iter5-pinned-actions/morning-large-text-actions.png) |
| 설정 보조 버튼 | [설정](evidence/screenshots/schedule/iter5-a11y/settings-large-font-actions.png) |
| 48dp 날짜·가로 스크롤 | [리포트](evidence/screenshots/schedule/iter5-a11y/report-large-font-days.png) |
| 직접 도착 위치 경고 | [목적지 밖](evidence/screenshots/schedule/iter5-schedule/manual-arrival-far.png) · [위치 미확인](evidence/screenshots/schedule/iter5-schedule/manual-arrival-unknown.png) |
| 이동 탭과 상세의 역할 | [이동 입구](evidence/screenshots/schedule/iter5-frontend/trip-index.png) · [상세 진행](evidence/screenshots/schedule/iter5-frontend/trip-progress.png) |

Figma는 [전체 변경](https://www.figma.com/design/FoiB0Q7bTIjUsjhiQLefJQ?node-id=724-2847), [설정](https://www.figma.com/design/FoiB0Q7bTIjUsjhiQLefJQ?node-id=727-2997), [리포트](https://www.figma.com/design/FoiB0Q7bTIjUsjhiQLefJQ?node-id=727-3037), [아침 기록](https://www.figma.com/design/FoiB0Q7bTIjUsjhiQLefJQ?node-id=729-3020), [큰 글자 수치](https://www.figma.com/design/FoiB0Q7bTIjUsjhiQLefJQ?node-id=732-3025)다. 모두 편집 가능한 텍스트·프레임·인스턴스·벡터로 구성했으며 완성 화면 이미지를 Figma 레이어나 구현 자산으로 사용하지 않았다. 본문 폰트는 Noto Sans KR, 색·간격·반경은 기존 변수다. 기본 버튼은 앱의 기존 글자 크기와 맞췄다. 새 정적 이미지·외부 자산 URL을 앱 코드에 넣지 않았다.

## APK와 재현

[로컬 서명 APK](../../APP/app/build/outputs/apk/release/app-release.apk)는 현재 작업 폴더의 산출물이며 Git에 빌드 바이너리를 넣지 않았다. 정확한 해시는 [SHA-256](evidence/release-apk.sha256), 서명 검사는 [apksigner 결과](evidence/release-signature.txt)를 따른다. 기존 팀 키의 인증서 SHA-256은 `09cdd39643f2e1d5c28ab6c2440863e024591bb524bbb735d59b32651f6097fe`다.

`APP`에서 다음 두 빌드를 **별도로** 실행한다. 저장소 설정이 `jitE2e`에 따라 활성 테스트 변형을 바꾸므로 한 명령에 Debug JVM과 E2e 테스트 작업을 섞지 않는다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:jacocoDebugUnitTestReport :app:lintDebug :app:assembleRelease --offline
.\gradlew.bat :app:assembleE2e :app:assembleE2eAndroidTest -PjitE2e=true --offline
```

실행한 기기는 API 36 에뮬레이터이며 `-read-only -no-snapshot-load -no-snapshot-save`로 시작했다. 개인 앱 `com.swpp.wakeup`을 설치·삭제·초기화하지 않고 별도 `com.swpp.wakeup.qa`만 사용했다. 테스트 종료 뒤 실행한 에뮬레이터도 종료한다. 실제 서버를 쓰는 최상위 PublicSetup/Demo/BackendFeatures 테스트는 이번 107개에 포함하지 않았다. 외부 계정·일정·문의는 생성하지 않았다.

백엔드는 `backend/pytest.ini`의 `config.settings.test`로 실행했다. 메모리 SQLite와 비어 있는 외부 키를 사용하므로 운영 DB나 실제 카카오·OpenAI 호출을 이용한 결과가 아니다.

## 운영·사람의 확인이 필요한 것

- 서버 선행 배포·마이그레이션 적용·전체 재학습·실서버 업로드 검증은 실행하지 않았다. 특히 `observations.0008` 좌표 삭제는 역방향 복원이 불가능하다. 구버전 서버가 이행 중 좌표를 다시 저장하지 않도록 관측 쓰기를 드레인/중단하고 이행·새 서버 전환·좌표 NULL 검증을 마친 뒤 재개해야 한다.
- Scanner가 설치되지 않은 QA 에뮬레이터에서 Android Lint나 Compose 검사를 Scanner 실행으로 기록하지 않았다. 실제 TalkBack 탐색과 Scanner 5화면, 인간 평가 10건, UAT 2차 결과 검토가 남아 있다.
- 실제 휴대전화의 무도움 설치, 일곱 번의 아침·5초 오차·24시간 배터리 비교, 12/7 이후 베타 사용은 이번 자동 검증으로 완료했다고 하지 않는다. 빈 양식에는 실제 측정만 기록해야 한다.
- 그룹 제외와 목적지 밖 직접 도착의 정시율 포함, 처리방침·원스토어 기준·AT 개정은 PM·BE가 확정해야 한다. S-6 베타 종합 집계는 일정표의 BE 후속 작업이다.
