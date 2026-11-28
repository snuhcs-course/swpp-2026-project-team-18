# Iteration 5 설치와 첫 알람 확인

현재 로컬 후보: **0.18.0 / versionCode 19**. [최신 검증·APK 해시](schedule-frontend-verification.md)를 확인해요. 실제 휴대전화에서의 무도움 설치 검증은 아직 남아 있어요.

대상은 Android 14 이상 기기예요. 개발 도구 없이 팀이 배포한 **서명된 release APK**를 받아 설치해요. AAB는 기기에 직접 설치할 수 없어요. 이 문서는 설치 절차와 검증 체크리스트이며, 실기기 설치 완료 결과는 아니에요.

## 설치하기

1. 팀이 공유한 `app-release.apk`를 휴대전화로 받아요. 팀이 함께 제공한 버전·SHA-256 체크섬을 확인해요.
2. 받은 파일을 열어요. 설치 권한을 묻는다면 파일을 연 브라우저나 파일 앱에만 ‘이 출처 허용’을 켜고 설치해요. 설치 뒤에는 다시 꺼도 돼요.
3. 기존 JustInTime을 업데이트할 때는 같은 팀 서명키가 필요해요. ‘앱이 설치되지 않았어요’가 나오면 배포 담당자에게 기존 앱과 새 APK의 서명을 확인해 달라고 해요. 앱 삭제는 로그인·기기 기록에 영향을 주므로 먼저 담당자에게 알려 주세요.
4. 앱을 열고 이메일로 가입하거나 연결된 소셜 계정으로 시작해요. 약관을 읽고 동의해요. 소셜 로그인 오류가 나면 이메일 가입을 사용할 수 있어요.
5. 집 주소를 검색해서 고르고 평소 준비 시간을 설정해요. 캘린더 연동은 선택할 수 있어요.
6. 앱의 알람·권한 안내에 따라 알림과 정확한 알람을 허용해요. 잠금화면 전체 화면 알림도 기기에서 허용할 수 있어요. 위치 권한은 기기에서 출발·도착을 감지할 때 필요해요.
7. 홈에서 알람을 추가해요. 도착 장소와 도착 시각을 정하고, 계산된 기상 시각을 확인해요. 바로 테스트하려면 준비·이동 시간을 고려해서 **기상 시각이 앞으로 2~3분 뒤**가 되도록 직접 조정해요. 도착 시각만 2~3분 뒤로 잡으면 이미 지난 기상 시각이 나올 수 있어요.
8. 기상 시각이 보이고 알람 스위치가 켜졌는지 확인한 뒤 화면을 잠가요. 예정 시각에 소리·진동·잠금화면이 뜨는지 확인해요. 알람을 끄면 아침 기록으로 이동하는지도 확인해요.

알람이 울리지 않으면 홈의 권한 안내를 확인하고 기기 설정에서 알림·정확한 알람을 허용해요. 앱을 강제 중지하면 Android가 등록된 알람을 지울 수 있어요. 다시 앱을 열어 알람 등록 상태를 확인해요.

## 배포 담당자 검증

`APP`에서 팀의 기존 릴리스 서명 설정으로 빌드해요. 새 서명키를 만들거나 비밀번호를 저장소에 넣지 않아요.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleRelease
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs .\app\build\outputs\apk\release\app-release.apk
Get-FileHash -Algorithm SHA256 .\app\build\outputs\apk\release\app-release.apk
```

SDK build-tools 버전은 설치된 버전에 맞춰요. `app-release-unsigned.apk`만 생겼다면 서명 설정이 빠진 것이므로 배포하지 않아요. 서명 검증, 서버 버전 호환 확인, 실제 기기 첫 알람 확인을 마친 APK와 체크섬을 전달해요. 특히 좌표 없는 관측 업로드는 이 브랜치의 `observations.0008`(좌표 필수 제약 교체·과거 관측 좌표 삭제)과 기본 추천 복원 수정의 서버 선행 배포가 필요해요. 관측 좌표 삭제는 역방향 복원이 불가능하므로 배포 담당자가 검토·적용해요.

아래 결과는 테스터가 실제로 수행한 뒤에만 채워요.

| 항목 | 결과 | 근거 |
| --- | --- | --- |
| APK 서명·SHA-256 | 완료 · 0.17.0(18), 기존 팀 키 | [서명 검사](evidence/release-signature.txt), [SHA-256](evidence/release-apk.sha256), [후보 APK](../../APP/app/build/outputs/apk/release/app-release.apk) |
| 개발 도구 없이 설치·업데이트 | 미실행 | 기기·OS·앱 버전, 설치 화면 |
| 신규 가입·집 설정 | 미실행 | 테스트 계정 식별자와 화면 |
| 알림·정확한 알람 권한 설정 | 미실행 | 설정 화면 |
| 잠금화면 첫 알람·끄기·아침 기록 | 미실행 | 예정 시각과 실제 울림 시각 |

## 7일 알람과 배터리 근거

동일한 실기기·앱 버전으로 7일 연속 AT-24를 진행하고 베타 1주 로그를 별도로 모아요. 7+7일 결과는 관찰한 기간에 대한 근거이며 N1의 ‘30일’을 충족했다고 쓰지 않아요.

`evidence/alarms.csv`는 **매일 알람이 울리기 전** 예정 목록을 적고 나중에 실제 울림과 대조하는 표예요. 울린 로그만 추리면 놓친 알람을 찾을 수 없어요.

- `device_id`: 기기 일련번호 대신 `tester-phone-1` 같은 별칭을 써요. `app_version`에는 앱에 표시된 전체 버전을 적어요.
- `event_id`, `scheduled_at`: 실제 일정 ID와 예정 기상 시각을 적어요. 시각은 `2026-11-28T07:30:00+09:00`처럼 시간대를 포함해요. 예시 날짜는 측정값이 아니에요.
- `fired_at`: 서버 관리자에서 확인한 `AlarmLog`의 `kind=fired`인 **발생 시각**이에요. 서버 수신 시각이나 `dismissed`·`snoozed`를 대신 쓰지 않아요. 오프라인 업로드가 남았으면 연결 후 다시 확인해요.
- `expected`: 최종적으로 울려야 했으면 `true`, 사전에 취소·변경한 알람이면 `false`예요. 취소 이유와 새 예정 시각을 `notes`에 남겨요. 미룸 알람은 원래 알람과 섞지 않아요.
- `verified_at`, `evidence`: 미울림을 확인한 시각과 로그·기기 화면 등 근거의 상대 경로를 적어요. 기록이 없고 근거도 없으면 ‘미확인’으로 남아요.

`evidence/battery.csv`에는 같은 기기의 추적 사용일과 미사용일을 각각 기록해요. 24시간 사용량·충전 여부·다른 앱 사용 차이도 기록해요. `app_battery_percent`는 앱이 소비한 **전체 배터리 용량 대비 퍼센트**예요. Android 설정의 숫자가 ‘앱들 사이의 사용 비중’이라면 절대 용량이 아니므로 `measurement_basis=relative_usage_share`로 적고 N11 판정에 쓰지 않아요. 절대 하루 사용량을 확인한 경우에만 `absolute_daily_percent`를 써요. `tracking_enabled`와 `charged`는 `true` 또는 `false`, `period_hours`는 실제 측정 시간을 적어요.

```powershell
python scripts/analyze_alarm_evidence.py --alarms ../docs/iter5/evidence/alarms.csv --battery ../docs/iter5/evidence/battery.csv --as-of 2026-12-04T23:59:59+09:00
python -m unittest discover -s scripts/tests -p test_alarm_evidence.py
```

분석기는 시간 차이 ±5초, 미울림/미확인, 기기·버전별 연속 관찰 일수, 추적 사용/미사용 배터리 비교를 계산해요. 빈 템플릿의 결과는 `incomplete`예요. 원본 로그나 기기 측정을 대신하지 않아요.
