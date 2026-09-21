# 실기기 디버깅 스크립트

무선 디버깅으로 연결한 실기기에서 알람·GPS 동작을 확인할 때 쓴다.
연결 절차 자체는 `docs/device-setup.md` 를 볼 것.

**모든 스크립트가 `-Target <IP>:<포트>` 를 받는다.** 기본값이 박혀 있지만
무선 디버깅 포트는 **화면을 열 때마다 바뀌므로** 대개 직접 넘겨야 한다.
포트는 폰의 `설정 → 개발자 옵션 → 무선 디버깅` 화면 맨 위
`IP 주소 및 포트` 줄에 있다(페어링 포트와 다른 값이다).

| 스크립트 | 용도 |
| --- | --- |
| `device_install.ps1` | 연결 → SDK 확인 → 빌드 → 설치 → 권한 부여를 한 번에 |
| `device_grant.ps1` | 알림·위치 권한만 다시 부여하고 상태를 확인 |
| `device_alarmcheck.ps1` | Doze 예외·standby bucket·등록된 알람 확인 |
| `device_applog.ps1` | 앱 PID 로만 걸러낸 logcat (시스템 로그 제외) |
| `device_launch.ps1` | 앱 재시작 후 로그와 최상단 액티비티 확인 |
| `device_netcheck.ps1` | 폰에서 공용 서버까지 닿는지. 인터페이스·DNS 확인 |
| `device_wake.ps1` | 화면 깨우기 + 잠금 상태 확인 |
| `device_ui.ps1` | UI 계층 덤프. 스크린샷이 검게 나올 때 화면 대신 읽는다 |
| `device_shot.ps1` | 스크린샷 |

## 자주 쓰는 순서

```powershell
cd APP

# 1. 설치 (포트는 폰 화면에서 확인한 값)
.\scripts\device_install.ps1 -Port 37551

# 2. 서버까지 닿는지
.\scripts\device_netcheck.ps1 -Target 10.148.189.137:37551

# 3. 알람이 안 울렸을 때 원인 구분
.\scripts\device_alarmcheck.ps1 -Target 10.148.189.137:37551
.\scripts\device_applog.ps1 -Target 10.148.189.137:37551
```

## 알아 둘 것

- **스크린샷이 검게 나온다.** 잠금화면이거나, Compose 가 Vulkan 으로 렌더링해서
  `screencap` 이 빈 프레임을 받는 경우다. `device_ui.ps1` 로 UI 계층을 텍스트로
  읽으면 화면 내용을 확인할 수 있다.
- **출력이 잘린다.** PowerShell 인라인 출력이 자주 끊긴다. 스크립트가 결과를
  `$env:TEMP\*.txt` 로 쓰는 이유다. 파일을 열어 볼 것.
- **Doze 예외를 먼저 걸지 말 것.** 기본 상태에서 One UI 가 알람을 얼마나
  지연시키는지가 측정해야 할 값이다. 먼저 풀면 그 데이터를 못 얻는다.
- **`.ps1` 은 UTF-8 BOM 으로 저장한다.** Windows PowerShell 5.1 은 BOM 이
  없는 파일을 CP949 로 읽어서, 한글 주석의 멀티바이트가 따옴표처럼 해석돼
  파싱이 깨진다. 이 디렉터리의 스크립트는 전부 BOM 이 붙어 있다.
- **Tailscale 은 팀 공용으로 쓰지 않는다.** 앱은 공개 HTTPS 서버를 보므로
  VPN 이 필요 없다. 무선 디버깅이 막히는 망(eduroam 등)에서만 각자 자기
  계정으로 쓰면 된다. 근거는 `docs/team-setup.md` 7절.
