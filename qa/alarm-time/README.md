# 알람 시각과 추천 선택지의 실제 API 재현

저장소 루트에서 기존 백엔드 가상환경의 Python으로 실행한다. 실행한 `sys.executable`을 그대로 쓰며 별도 worktree 가상환경·외부 키가 필요하지 않다.

```powershell
python qa/alarm-time/run.py --seed --run-id recommendation-before
python qa/alarm-time/run.py --serve --run-id recommendation-before
```

서버를 켜는 두 번째 명령은 별도로 실행한다. `--seed`는 서버 프로세스를 시작하지 않는다. 같은 run ID는 덮어쓰지 않는다. DB는 반드시 `.artifacts/alarm-time/<run-id>/qa.sqlite3`이며 외부 DB·공용 서버 옵션은 없다. `--serve`는 기본 `127.0.0.1:8765`에서만 받는다. Android QA 빌드의 기본 주소 `http://10.0.2.2:8765/`와 연결된다. `-PjitPublicUi=true`를 사용하지 않는다.

처음에 실제 Django migration을 적용하고, 합성 계정·집·온보딩·첫 안내 완료 상태와 내일 오전 9시 일정을 만든다. 루틴 범위 20~40분, 대중교통 원값 20분·잔차 표준편차 8분을 사용한다. 라우팅·지도 외부 응답만 기존 `qa/demo/replay_backend.py`로 재생한다. 일정 생성과 블록 선택, 미리보기는 실제 API 뷰와 계획 계산을 통과한다.

초기 저장 알람과 보통 옵션의 시각이 같은지 확인한 뒤, 일반적인 과거 루틴 관측 일괄 업로드 API로 60분짜리 기록 8개를 올린다. 저장 계획을 직접 수정하거나 기대 결과를 주입하지 않는다. 업로드가 준비 추정에 즉시 들어가지만 저장 계획은 그대로 남던 문제를 재현한다. 수정 이후에는 같은 흐름의 요약이 일치 상태를 보여줄 수 있다.

이 계정은 N26의 운영 QA 접두를 쓰지 않는다. **학습을 시험하기 위한 격리된 합성 입력**이기 때문이다. 실제 베타·운영 데이터가 아니며 다른 DB에 복사하지 않는다. 스크립트가 DB 위치를 강제하고 모든 키를 차단한다.

- `config.json`: Android 재현에 필요한 `email`, `password`, `event_id`, `event_date`, `event_time`, `api_url`, `nickname`. 비공개이며 Git에 추가하거나 결과 보고서에 붙이지 않는다.
- `server.json`: 로컬 서버의 비공개 서명 키. Android에 복사하지 않는다.
- `summary.json`: 초기/업로드 후의 저장 알람·보통 시각·차이와 표본 수. 자격 증명·닉네임·좌표·장소명은 없다.

`--seed` 표준 출력에는 event ID·차이·받은 관측 수만 JSON으로 출력한다. 실제 Android 검증에서는 위 `config.json`으로 QA 앱에 로그인한 뒤 홈 알람, ‘시각 바꾸기’의 보통·추천, `ScheduledAlarmStore`의 실제 예약 시각, GET 일정·risk-options를 대조한다. 이 재현은 실기기 알람 정확도나 실제 사용자 학습 효과의 측정이 아니다.

## QA 앱에서 같은 데이터 확인

API 34 이상 AVD의 시간대를 `Asia/Seoul`로 맞추고 위 로컬 서버를 실행한 상태에서 진행한다. 필요한 경우 `APP`에서 `.\gradlew.bat :app:assembleE2e :app:assembleE2eAndroidTest -PjitE2e=true -PjitPublicUi=false --offline`으로 두 APK를 만든다. 최신 소스의 `AlarmTimeConsistencyTest`가 테스트 APK에 포함돼 있어야 한다.

아래 PowerShell 명령은 저장소 루트 기준이다. `$adb`는 설치된 Android SDK의 `adb.exe` 경로로 지정한다. 예전 시나리오의 로그인 토큰을 재사용하지 않도록 **QA 패키지만** 초기화한다.

```powershell
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$device = 'emulator-5554'
$run = 'recommendation-after' # 이 ID로 --seed와 --serve를 실행한 뒤 선택
& $adb -s $device install -r APP/app/build/outputs/apk/e2e/app-e2e.apk
& $adb -s $device install -r APP/app/build/outputs/apk/androidTest/e2e/app-e2e-androidTest.apk
& $adb -s $device shell pm clear com.swpp.wakeup.qa
& $adb -s $device shell pm grant com.swpp.wakeup.qa android.permission.POST_NOTIFICATIONS
& $adb -s $device shell pm grant com.swpp.wakeup.qa android.permission.ACCESS_COARSE_LOCATION
& $adb -s $device shell pm grant com.swpp.wakeup.qa android.permission.ACCESS_FINE_LOCATION
# API 37 이상에서만 필요한 로컬 네트워크 권한
# & $adb -s $device shell pm grant com.swpp.wakeup.qa android.permission.ACCESS_LOCAL_NETWORK
& $adb -s $device shell appops set com.swpp.wakeup.qa SCHEDULE_EXACT_ALARM allow
& $adb -s $device shell appops set com.swpp.wakeup.qa USE_FULL_SCREEN_INTENT allow

# 자격 증명 내용은 출력하지 않고 앱 전용 내부 저장소에 표준 입력으로 전달한다.
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
Get-Content -Raw -Encoding UTF8 ".artifacts/alarm-time/$run/config.json" |
    & $adb -s $device shell "run-as com.swpp.wakeup.qa sh -c 'mkdir -p files; cat > files/alarm-time-config.json'"
& $adb -s $device shell am instrument -w -r -e class com.swpp.wakeup.AlarmTimeConsistencyTest com.swpp.wakeup.qa.test/androidx.test.runner.AndroidJUnitRunner

New-Item -ItemType Directory -Force ".artifacts/alarm-time/$run/device" | Out-Null
& $adb -s $device pull /sdcard/Android/data/com.swpp.wakeup.qa/files/alarm-time-qa ".artifacts/alarm-time/$run/device"
```

`comparison.json`에는 실제 API 시각·기기 예약 시각·차이만 저장하고, PNG/XML에는 합성 화면만 담는다. 수정 전 seed에서는 19분 불일치를 재현했고, 이 차이는 기기 테스트의 일치 검증 대상이다. 수정 후 같은 관측 입력 8개를 새 DB에 올리면 초기·업로드 후 차이가 모두 0분이다. `config.json`·`server.json`·SQLite는 `.artifacts`의 ignore 규칙으로 제외되므로 커밋하지 않는다.

추가로 테스트는 상세 화면을 유지한 채 실제 API에서 일정 시작을 한 시간 바꾸고, 기기의 준비 기록 큐에 과거 기록 한 건을 적재한다. 시트를 다시 열면 대기가 전송된 뒤 최신 시각을 읽어 홈·시트·예약을 맞추는지 확인한다. `cache-refresh.json`과 `system-alarms.json`에 비교값을 남긴다. 시스템 예약은 `dumpsys alarm`의 QA 패키지 `RTC_WAKEUP` 항목의 `origWhen`을 기대 epoch와 직접 대조한다.
