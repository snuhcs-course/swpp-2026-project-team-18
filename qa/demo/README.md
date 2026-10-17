# 데모 E2E QA

같은 데모 데이터를 사용한다. AndroidX UI Automator가 실제 화면을 조작하고,
Python이 데이터 준비·GPS 이동·결과 수집을 맡는다. Playwright는 필요 없다.

## 실행

1. [기기 설정](../../docs/device-setup.md)의 API 34+ 에뮬레이터를 켠다. 시간대는 `Asia/Seoul`.
2. [백엔드 가상환경](../../backend/README.md), Android SDK·JDK, `APP/local.properties`의 `sdk.dir`을 준비한다.
   로컬 모드에는 `backend/.env`의 카카오 키가 필요하다.
3. 저장소 루트에서 실행하고 에뮬레이터 화면을 본다. 실행 중 직접 조작하지 않는다.

```bash
backend/.venv/bin/python qa/demo/run.py
```

여러 에뮬레이터가 연결됐으면 `--device emulator-5554`, 영상이 필요 없으면 `--no-video`를 추가한다.
검증은 약 5~7분 걸린다. 최초 빌드는 의존성 다운로드 때문에 더 걸린다.

### 카카오 키 없이 로컬 변경 검증

```bash
backend/.venv/bin/python qa/demo/run.py --replay-routing --no-video
```

일회용 로컬 DB와 현재 백엔드 코드를 사용한다. 외부 응답만
[`replay/route.json`](replay/route.json)의 실제 도보 경로 기록·고정 대중교통 사례와
저장된 지도 이미지로 재생한다. 집 목적지 변경에는 고정 좌표 경로 사례를 추가로 쓴다. 가입·저장·실제 알람·GPS·관측·리포트 검증은
그대로 실행하며, 도보 걸음 수·kcal 추정 표시와 시험 일정의 지각 민감도 배지도 확인한다.
`RouteDisplayTest`는 자전거 카드 → 전체 화면 지도 미리보기 → 뒤로(목록 복귀), 배지 3개와 대체 버스 2개의 실제 카드 표시,
마지막으로 도보 미리보기의 "이 경로로 계산" → 일정 추가 복귀를 확인한다.
선택 카드·미리보기의 경로 안내도 확인한다. `BackendFeaturesTest`는 집 목적지 UI 생성·현재 집 변경,
리스크 빈 상태·모형 옵션·선택 저장·기기 알람 동기화를 확인한다. 리스크 검증 입력만 일회용 DB에 준비하며,
실제 API가 결과를 계산한다. 이후 입력을 복원하고 같은 일정으로 이동 E2E를 이어 간다.
[직접 테스트와 변경 전후 증거](../../docs/iter2/route-risk-home.md).
`PublicSetupTest`의 준비 시간 단계는 시계 다이얼(`prep_value` → 24시간제 시:분)로 고르고, 도보 경로는 미리보기가 열리면
`route_preview_confirm`, 좌표가 없는 응답이면 목록의 `route_confirm` 으로 확정한다.
결과에는 `external_response_mode`와 `route_display_seconds`를 기록한다.
이 모드는 공용 서버 옵션과 함께 사용할 수 없으며 실시간 카카오 응답 검증을 대신하지 않는다.

### 공용 서버에서 완주

```bash
backend/.venv/bin/python qa/demo/run.py --public-e2e
```

실제 UI로 가입·집 설정·루틴·일정을 만든 뒤, **같은 계정·일정**으로 알람 → 루틴 완료
→ GPS 출발·도착 → 관측 업로드 → 주간 리포트까지 확인한다. 보통 5~7분 걸린다.
알람 대기를 줄이고 리포트에 반영하기 위해 방금 만든 일정의 시각만 API로 조정한다.

준비 화면만 빠르게 확인하려면 아래 명령을 사용한다(약 1~2분).

```bash
backend/.venv/bin/python qa/demo/run.py --public-ui
```

최신 소스로 QA APK를 빌드해 공용 서버에 연결한다. **매번 새 QA 계정과 데이터가 생성된다.**
가입 → 집·준비 시간 설정 → 루틴 2개 생성 → 장소·집 출발·도보 경로 선택 → 미래 일정 추가
→ 알람 근거·지도 → 자동 로그인·재로그인 후 값 유지를 화면에서 확인한다.
알람 근거의 준비 블록 표시와 마지막 API 조회로 저장 값·준비 시간 반영·실제 카카오 경로를 대조한다.
준비 데이터를 API로 생성하지 않는다.
공용 모드는 서버의 카카오 키를 사용하므로 로컬 `.env`가 필요 없다.
최초 빌드·공용 서버 깨우기는 더 걸릴 수 있다.

모든 모드는 별도 QA 앱만 초기화한다. 공용 QA 계정·데이터는 서버에 남는다.
같은 QA 앱을 사용하므로 여러 모드를 동시에 실행하지 않는다.
로컬·공용 모두 가입·약관·집·준비 시간 표시·변경·취소·재로그인 유지·루틴·일정·알람 OFF/ON을 실제 UI로 확인한다.

## 흐름과 데이터

[scenario.json](scenario.json)은 출발지·실제 장소 검색어·루틴·대기 시간을 담는다.
집은 도로명 주소로, 목적지는 상호명으로 검색해 두 검색 방식과 집 저장을 함께 검증한다.
일정은 실행 시각 기준으로 생성하고 GPS는 테스트 출발지로 설정한다. 시각 선택은 오전·오후·자정과 1분 단위도 검증한다.
[run.py](run.py)는 로컬 모드에서 일회용 SQLite 서버를 띄운다.
준비 데이터 생성·알람 스위치는 모든 모드에서 [PublicSetupTest.kt](../../APP/app/src/androidTest/java/com/swpp/wakeup/PublicSetupTest.kt)가 조작한다.
[DemoFlowTest.kt](../../APP/app/src/androidTest/java/com/swpp/wakeup/DemoFlowTest.kt)는 다음 흐름을 검증한다.

준비 UI 검증 후 자동 로그인 → 경로·알람 근거 → 화면 잠금 → 실제 알람 → 해제·루틴 완료 → GPS 출발·이동
→ 목적지 반경 진입 즉시 도착 → 화면 재진입 → 관측 업로드·이번 주 리포트.

준비 블록은 각각 1~2분으로 신고해 준비 시간 변동 폭의 근거를 만든다(확률은 이동 변동 폭도 있어야 나온다).
알람은 화면 준비 후 약 **30초 뒤**로 계산한다. 루틴은 각 5초, 이동은 65초로 시뮬레이션한다.
GPS는 **목적지 80m 앞에서 멈춘다**. 100m 반경의 첫 유효 위치에서 도착하고,
서버에 체류 0초·진입=판정 시각·거리 50~100m가 저장돼야 통과한다.
재생 모드는 UI에서 알람을 끈 뒤 같은 생성 API로 새 일정을 추가해 선택 초기화와
실제 스위치·기기 예약 복원도 확인한다. 검증용 추가 일정은 바로 삭제한다.
[BE-25·24 직접 테스트와 전후 결과](../../docs/iter2/alarm-arrival-policy.md).
GPS 공급자를 QA에서 활성화하고 ADB로 카카오 경로 좌표를 이동시킨다. 앱의 추적 서비스가 출발·도착 관측을 생성한다.
주간 리포트는 미래 일정을 제외하므로, 도착 기록 저장 후 **같은 일정의 약속 시각만 API로 마감**한다.

QA 앱(`com.swpp.wakeup.qa`, **JustInTime QA**)만 초기화한다. 로컬 E2E는 공용 DB를 사용하지 않는다.
매번 새 로컬 데이터로 시작하며, 종료하면 임시 백엔드도 종료된다.
FCM, 재부팅·오프라인 복구, 앱 프로세스 재시작 후 도착 복원, 실기기 GPS는 이 시나리오의 범위 밖이다.

## 결과 확인

마지막 `PASSED`와 종료 코드 `0`이 성공이다. 실패는 종료 코드 `1`이며 통과로 처리하지 않는다.
실패 시 터미널에 예외 원인을 요약한다. 화면 갱신으로 무효화된 요소는 다시 조회하고, `device/steps.json`의 `stale_retries`에 횟수를 기록한다.
출력된 `.artifacts/demo-qa/<실행 시각>/`에서 확인한다.
공용 모드는 `public-e2e-<실행 시각>/` 또는 `public-ui-<실행 시각>/`에 저장한다.

- `result.json`, `server-evidence.json`: 실제 출발·도착 각 1건, 루틴 관측, 리포트 결과.
- `device/steps.json`, 단계별 PNG·XML: 화면과 단계별 통과 여부. 실패 시 `failure.png`.
- `device/screen-*.mp4`: 화면 녹화. 3분 단위로 나뉜다.
- `instrumentation.txt`, `logcat.txt`, `backend.log`, `build.log`: 실패 원인.

전체 완주의 `device/setup-steps.json`, `setup-server-evidence.json`에는 준비 단계 결과가 들어간다.
`--public-ui`만 실행하면 준비 저장 결과가 `server-evidence.json`에 들어간다.
`credentials.json`은 재확인용 비밀번호가 담긴 비공개 파일이므로 공유하지 않는다.

실행 결과·일회용 DB는 `.artifacts/`의 기존 Git ignore 규칙으로 제외한다.
시뮬레이션 결과는 기능 연결 검증이며 실제 이동 성능 평가 자료로 쓰지 않는다.
