# BE-25·24 테스트

## 정책

- BE-25: 직접 일정 추가가 성공하면 같은 사용자·같은 시작 날짜(프로필 시간대)의
  기존 알람 선택을 자동으로 복원한다. 다른 날짜·다른 사용자·캘린더 동기화는 제외한다.
  새 일정에 명시한 선택과 기존 감사 로그는 보존한다.
- BE-24: 이동 중 새 유효 GPS 위치가 목적지 100m 안에 들어오면 즉시 도착한다.
  GPS 정확도 상한 50m와 출발 판정은 유지한다. 위치 수신 주기는 기본 10초다.

## 앱에서 직접 확인

최신 앱과 이 브랜치의 백엔드를 연결한다. 공용 서버를 사용하려면 먼저 배포해야 한다.

| 작업 | 조작 | 통과 기준 |
| --- | --- | --- |
| BE-25 | 같은 날·같은 목적지/경로/종류로 미래 일정 A, B를 만든다. A를 OFF, B를 ON으로 바꾼 뒤 더 늦은 C를 추가한다. | A 자동 ON, B·C 자동 OFF. 다른 날짜의 수동 선택은 유지. 앱 재진입 후에도 동일. |
| BE-24 | 알람을 해제하고 집에서 출발해 "이동 중"을 확인한다. 에뮬레이터 GPS를 목적지 약 80m 앞에 둔다. | 새 유효 위치가 수신되면 2분 대기 없이 "도착 완료". 화면 재진입 후 유지, 도착 관측 1건. |

에뮬레이터 GPS 조작은 [기기 설정](../device-setup.md)을 따른다. GPS 오차가 50m를 넘는
위치는 판정에 쓰지 않으므로, 반경에 넣은 시각과 새 유효 위치 수신 시각을 구분한다.

## 빠른 자동 검증

저장소 루트에서 실행한다. 백엔드 가상환경과 Android SDK·JDK가 필요하다.
현재 Mac의 Android Studio 기본 경로는 다음과 같다.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export GRADLE_USER_HOME=/private/tmp/jit-qa-gradle
```

```bash
backend/.venv/bin/python -m pytest -c backend/pytest.ini backend/apps/events/tests/test_alarm_switch.py -k be25 -q
APP/gradlew :app:testE2eUnitTest -p APP -PjitE2e=true --tests 'com.swpp.wakeup.sensing.TripGeofenceTest'
```

## 실제 화면·기기 알람·GPS·서버 기록까지

API 34+ 에뮬레이터를 `Asia/Seoul` 시간대로 켜고 실행한다.
외부 API 키 없이 일회용 로컬 DB에서 실행한다. QA 앱 데이터만 초기화한다.

```bash
backend/.venv/bin/python qa/demo/run.py --replay-routing --no-video --device emulator-5554
```

- UI에서 OFF → 실제 생성 API → 서버 선택 null·홈 ON·기기 예약 복원을 확인한다.
- GPS를 목적지 80m 앞까지만 이동시킨다. 새 도착 관측의 거리가 50~100m이고,
  체류가 0초이며 진입 시각과 판정 시각이 같아야 한다.
- 실제 알람·루틴·출발/도착 각 1건·도착 상태 유지·주간 리포트까지 확인한다.
- 외부 경로·지도 응답만 기록된 fixture를 재생한다. 앱·서버 판정이나 기록을 대신 만들지 않는다.
- 결과: `.artifacts/demo-qa/<실행 시각>/result.json`, `device/setup-steps.json`, `device/steps.json`.

## 전후 검증 기록

비교 기준은 프론트 통합 커밋 `f0212e3`이다. 동일한 요구사항 테스트를 먼저 실행했다.

| 시나리오 | 수정 전 | 수정 후 |
| --- | --- | --- |
| 같은 현지 날짜의 기존 ON/OFF 초기화(서울·뉴욕 DST) | 선택값이 남아 2건 실패 | 2건 통과. 다른 날짜·사용자와 기존 로그 보존 |
| 계획 계산 오류 시 생성 취소 | 새 일정 행이 남아 실패 | 통과. 선택값 유지·새 일정 없음 |
| 목적지 80m 안 첫 위치에서 도착 | 도착 사건이 없어 실패 | 통과. 체류 0·진입=판정 시각·중복 없음 |

2026-10-17: 백엔드 1,139건, Android 600건, HTTP 8종 실패 0, lint 오류 0.
2분 대기 전용 테스트는 제거·교체했고 출발·GPS 정확도 검증은 유지했다. 추가 DB 마이그레이션은 없다.

통합 전후 E2E도 각각 통과했다. 통합 기준의 기존 E2E는 약 435초,
수정 후에는 준비 9단계·경로 표시·완주 10단계가 약 302초 걸렸다.
실제 도착 관측은 **91.23m·체류 0초·진입=판정 시각**이며, 출발·도착 각 1건과 리포트 1/1건을 확인했다.
수정 후 실행: `.artifacts/demo-qa/20261017T072044Z/`.

수정 전 로그와 수정 후 실행 증거는 `.artifacts/implementation/2026-10-17-be25-be24/`에 보관한다.
