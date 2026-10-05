# 기기 설정과 테스트

기본 테스트는 [공용 서버로 앱을 실행](../APP/README.md)하고
[MVP 체크리스트](demo-checklist.md)에 결과를 기록한다.

## 연결

에뮬레이터는 Android Studio → Device Manager에서 켠다. 준비된 Mac 기기는 `JIT_API_34`다.
ADB 경로는 [앱 안내](../APP/README.md)의 터미널 설정을 사용한다.

- **USB:** 빌드 번호를 7번 눌러 개발자 옵션을 켜고, USB 디버깅을 허용한 뒤 케이블을 연결한다.
  기기에 뜨는 디버깅 승인 창을 허용한다.
- **무선:** PC와 기기를 같은 네트워크에 연결한다. 무선 디버깅의 페어링 주소로
  `adb pair <IP:포트>`를 실행한 뒤, 기본 화면의 연결 주소로 `adb connect <IP:포트>`를 실행한다.
  페어링과 연결 포트는 다르다.

`adb devices -l`에서 `device` 상태를 확인한다. 여러 기기가 있으면 ADB에 `-s <기기-ID>`를 붙인다.
아래는 macOS/Bash 예시이며 ID는 연결 목록에 맞게 바꾼다.

```bash
JIT_DEVICE=emulator-5554
adb -s "$JIT_DEVICE" shell cmd alarm set-timezone Asia/Seoul
adb -s "$JIT_DEVICE" shell settings put secure show_ime_with_hard_keyboard 1
# 신림역. 경도 → 위도 순서이며 프로필 집 위치에 맞춘다.
adb -s "$JIT_DEVICE" emu geo fix 126.929745 37.484267
```

Windows에서는 `$JIT_DEVICE` 대신 실제 기기 ID를 넣는다.
한글 입력은 Android 설정에서 한국어 키보드를 추가한다.

## 권한과 GPS

알림·정확한 위치·전체 화면 알림을 허용하고 기기·PC 음량을 확인한다.
추적은 알람 해제 시 시작하며, 위치는 앱 사용 중 허용으로 테스트할 수 있다.
[Android 14 전체 화면 알림 안내](https://developer.android.com/about/versions/14/behavior-changes-14).

1. 출발 전 GPS를 프로필 집 위치로 보내고 알람을 해제한다.
2. 에뮬레이터 **⋯ → Location → Routes → Play route**로 집에서 목적지까지 1배속 이동한다.
   메뉴가 없으면 에뮬레이터를 별도 창으로 연다.
3. 목적지 위치를 Single points로 보내 **반경 50m 안에서 2분 이상** 유지한다.
4. 이동 상태와 서버 관측 업로드를 확인한다.

출발은 집 반경 150m 밖 연속 2회 또는 누적 80m·60초 이상 이동으로 판정한다.
위치 갱신은 출발 전 30초·이동 중 10초 간격이며 오차 50m 초과 위치는 제외한다.
[Android 가상 GPS 안내](https://developer.android.com/studio/run/emulator-extended-controls).

## 상태와 로그

홈 아바타 메뉴에서 서버 주소, 실제 등록된 알람 수·다음 시각, 미전송 이동 기록 수를 확인한다.

```bash
adb -s "$JIT_DEVICE" logcat -s AlarmScheduler AlarmReceiver TripTrackingService TripObservationQueue
adb -s "$JIT_DEVICE" shell dumpsys alarm
```

예약 목록에서 `com.swpp.wakeup`을 찾는다. 공유 로그에는 토큰·키·개인 위치를 넣지 않는다.

| 문제 | 확인할 것 |
| --- | --- |
| 로그인 실패 | 앱 서버 주소 → health → 인증 오류 메시지 |
| 알람 미발화·화면 미표시 | 미래 시각·기기 예약 → 시간대·알림/전체 화면 권한 → 소리·절전 설정 |
| 위치 기록 없음 | 알람 해제로 추적 시작 → 정확한 위치 권한 → GPS·판정 시간 → 미전송 큐 |
| 도착 상태 사라짐·재시작 후 초기화 | [알려진 버그](demo-checklist.md) 확인 |

실제 GPS 오차, 절전 중 알람, 화면을 끈 채 이동 추적은 실기기에서 별도로 검증한다.

## 선택 사항: 실기기에서 로컬 서버 사용

1. [백엔드](../backend/README.md)를 `runserver 0.0.0.0:8000`으로 띄운다.
2. PC·기기를 같은 LAN에 연결하고 `APP/local.properties`에 `devServerHost=<PC의 LAN 주소>`를 넣는다.
3. 기기 브라우저에서 `http://<PC의 LAN 주소>:8000/api/health`를 확인한다.
   연결되지 않으면 방화벽의 Private 네트워크 8000 포트와 `ALLOWED_HOSTS`를 확인한다.
   자동 감지가 놓친 주소는 `backend/.env`의 `DEV_EXTRA_HOSTS`에 추가한다.
4. 앱을 다시 빌드·설치한다. 공용 서버로 돌아갈 때는 `devServerHost` 줄을 제거하고 다시 빌드한다.

로컬 모드의 에뮬레이터는 자동으로 `10.0.2.2:8000`을 사용하며 PC 루프백 서버에도 접근한다.
[Android 네트워크 주소 안내](https://developer.android.com/studio/run/emulator-networking-address).
