# JustInTime — 앱 실행

Kotlin·Jetpack Compose 앱이며 기본 서버는 `https://justintime-api.onrender.com/`이다.
앱 테스트에 로컬 백엔드나 `.env`는 필요 없다.
[구성과 기능 제한](../README.md) · [MVP 데모 체크리스트](../docs/demo-checklist.md)

## 준비

- Android Studio와 **Android SDK Platform 37**
- Gradle JDK: Android Studio 내장 **JBR 25**
- 실행 기기: **Android 14(API 34) 이상**
- 팀에서 받은 `app/google-services.json`

공용 서버를 쓰려면 `local.properties`의 `devServerHost` 줄을 제거하고 다시 빌드한다.
`sdk.dir`은 유지한다.

## Android Studio

1. 저장소의 **`APP` 폴더**를 열고 Gradle Sync를 완료한다.
2. Device Manager에서 API 34 이상 기기를 켠다. 준비된 Mac 기기는
   **`JIT_API_34`(Pixel 6a, Android 14)**다. 새 Apple Silicon 기기는 ARM64 이미지를 사용한다.
3. 실행 기기를 선택하고 Run ▶을 누른다.
4. 이메일로 로그인한다. 조회용 계정은 `demo@demo.com` / `demo1234`이며,
   일정·루틴을 만드는 시연은 전용 계정을 사용한다.

## 터미널

저장소 루트에서 시작한다. 설치 경로가 다르면 JDK·SDK 경로를 바꾼다.

### macOS

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
cd APP
./gradlew installDebug
adb shell am start -n com.swpp.wakeup/.ui.auth.LoginActivity
```

### Windows PowerShell

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:LOCALAPPDATA\Android\Sdk\platform-tools;$env:Path"
cd APP
.\gradlew.bat installDebug
adb shell am start -n com.swpp.wakeup/.ui.auth.LoginActivity
```

여러 기기가 연결되어 있으면 Android Studio에서 대상을 선택한다.
시간대·키보드·권한·가상 GPS는 [기기 설정](../docs/device-setup.md)을 따른다.
빌드 로그의 `[JustInTime] API=...`와 홈 아바타 메뉴에서 서버 주소를 확인한다.

## 로컬 백엔드 연결

[백엔드 안내](../backend/README.md)에 따라 서버를 띄운 뒤,
`APP/local.properties`에 다음 줄을 추가하고 다시 빌드·설치한다.

```properties
devServerHost=10.0.2.2
```

에뮬레이터는 PC의 로컬 서버에 연결한다. 공용 서버로 돌아갈 때는 이 줄을 제거하고 다시 빌드한다.
실기기 LAN 연결은 [기기 설정](../docs/device-setup.md)을 본다.

## 검증

`APP` 폴더에서 실행한다. Windows는 `.\gradlew.bat`을 사용한다.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

현재 검증 결과는 [MVP 체크리스트](../docs/demo-checklist.md)에 기록한다.

에뮬레이터에서 로그인부터 실제 알람·GPS 도착·리포트까지 자동 검증하려면
[데모 E2E QA](../qa/demo/README.md)를 따른다. 별도 QA 앱과 일회용 DB를 사용한다.
