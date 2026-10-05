# 데모 E2E QA

시나리오는 **하나**를 유지한다. AndroidX UI Automator가 실제 화면을 조작하고,
Python이 데이터 준비·GPS 이동·결과 수집을 맡는다. Playwright는 필요 없다.

## 실행

1. [기기 설정](../../docs/device-setup.md)의 API 34+ 에뮬레이터를 켠다. 시간대는 `Asia/Seoul`.
2. [백엔드 설정](../../backend/README.md)을 완료한다. `backend/.env`의 카카오 키가 필요하다.
   Android SDK, JDK, `APP/local.properties`의 `sdk.dir`도 준비한다.
3. 저장소 루트에서 실행하고 에뮬레이터 화면을 본다. 실행 중 직접 조작하지 않는다.

```bash
backend/.venv/bin/python qa/demo/run.py
```

여러 에뮬레이터가 연결됐으면 `--device emulator-5554`, 영상이 필요 없으면 `--no-video`를 추가한다.
검증은 약 4~5분 걸린다. 최초 빌드는 의존성 다운로드 때문에 더 걸린다.

## 흐름과 데이터

[scenario.json](scenario.json)은 출발지·실제 장소 검색어·루틴·대기 시간을 담는다.
[run.py](run.py)는 별도 SQLite DB에 **실제 API**로 새 계정, 루틴 2개, 카페 일정과 카카오 도보 경로를 만든다.
[DemoFlowTest.kt](../../APP/app/src/androidTest/java/com/swpp/wakeup/DemoFlowTest.kt)는 다음 흐름을 검증한다.

로그인 → 경로·알람 근거 → 화면 잠금 → 실제 알람 → 해제·루틴 완료 → GPS 출발·이동
→ 목적지 체류 → 도착 완료 → 화면 재진입 → 관측 업로드·이번 주 리포트.

준비 블록은 각각 1~2분으로 신고해 확률 계산의 근거를 만든다.
알람은 화면 준비 후 약 **30초 뒤**로 계산한다. 루틴은 각 5초, 이동은 65초로 시뮬레이션한다.
도착의 **50m 반경·120초 체류**는 앱의 원래 조건을 그대로 사용한다.
GPS 공급자를 QA에서 활성화하고 ADB로 카카오 경로 좌표를 이동시킨다. 앱의 추적 서비스가 출발·도착 관측을 생성한다.
주간 리포트는 미래 일정을 제외하므로, 도착 기록 저장 후 **같은 일정의 약속 시각만 API로 마감**한다.

QA 앱(`com.swpp.wakeup.qa`, **JustInTime QA**)만 초기화한다. 개인 앱과 공용 DB는 유지된다.
매번 새 로컬 데이터로 시작하며, 종료하면 임시 백엔드도 종료된다.
FCM, 재부팅·오프라인 복구, 앱 프로세스 재시작 후 도착 복원, 실기기 GPS는 이 시나리오의 범위 밖이다.

## 결과 확인

마지막 `PASSED`와 종료 코드 `0`이 성공이다. 실패는 종료 코드 `1`이며 통과로 처리하지 않는다.
출력된 `.artifacts/demo-qa/<실행 시각>/`에서 확인한다.

- `result.json`, `server-evidence.json`: 실제 출발·도착 각 1건, 루틴 관측, 리포트 결과.
- `device/steps.json`, 단계별 PNG·XML: 화면과 단계별 통과 여부. 실패 시 `failure.png`.
- `device/screen-*.mp4`: 화면 녹화. 3분 단위로 나뉜다.
- `instrumentation.txt`, `logcat.txt`, `backend.log`, `build.log`: 실패 원인.

실행 결과·일회용 DB는 `.artifacts/`의 기존 Git ignore 규칙으로 제외한다.
시뮬레이션 결과는 기능 연결 검증이며 실제 이동 성능 평가 자료로 쓰지 않는다.
