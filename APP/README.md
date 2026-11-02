# JustInTime — 앱 실행

Kotlin·Jetpack Compose 앱이며 기본 서버는 `https://justintime-api.onrender.com/`이다.
앱 테스트에 로컬 백엔드나 `.env`는 필요 없다.
[구성과 기능 제한](../README.md) · [MVP 데모 체크리스트](../docs/demo-checklist.md)

## 준비

- Android Studio와 **Android SDK Platform 37**
- Gradle JDK: Android Studio 내장 **JBR 25**
- 실행 기기: **Android 14(API 34) 이상**. 13 이하 기기에는 설치되지 않는다.

공용 서버를 쓰려면 `local.properties`의 `devServerHost` 줄을 제거하고 다시 빌드한다.
`sdk.dir`은 유지한다.

## Android Studio

1. 저장소의 **`APP` 폴더**를 열고 Gradle Sync를 완료한다.
2. Device Manager에서 API 34 이상 기기를 켠다. 준비된 Mac 기기는
   **`JIT_API_34`(Pixel 6a, Android 14)**다. 새 Apple Silicon 기기는 ARM64 이미지를 사용한다.
   실기기는 USB 디버깅을 켜고 연결한 뒤 폰의 "USB 디버깅 허용"을 눌러야 목록에 뜬다([기기 설정](../docs/device-setup.md)).
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

## 소셜 로그인

카카오·Google 버튼은 SDK로 ID 토큰을 받아 서버로 보낸다([계약](../docs/iter3/social-login-api.md)).
앱에 넣는 값은 `gradle.properties`의 `kakaoNativeAppKey`·`googleWebClientId`이고, 둘 다 APK에
들어가는 공개 식별자다. 다른 콘솔 앱으로 시험할 때는 `local.properties`에 같은 이름으로 적는다.

**서명 키마다 콘솔 등록이 필요하다.** 디버그 APK는 PC마다 다른 `~/.android/debug.keystore`로
서명되므로, 등록하지 않은 PC에서 만든 APK는 카카오가 "키 해시" 오류를, Google이
"개발자 콘솔 설정" 오류를 낸다.

| 콘솔 | 등록할 것 |
| --- | --- |
| Kakao Developers → 앱 → 플랫폼 → Android | 패키지 `com.swpp.wakeup`(QA는 `com.swpp.wakeup.qa`)와 키 해시. 카카오 로그인 ON, **OpenID Connect ON** |
| Google Cloud → 사용자 인증 정보 | 패키지마다 Android OAuth 클라이언트(패키지 이름 + SHA-1). 서버용 웹 클라이언트 ID가 `googleWebClientId` |

키 해시와 SHA-1은 같은 인증서에서 나온다(Windows PowerShell).

```powershell
$kt = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
& $kt -exportcert -alias androiddebugkey -keystore "$env:USERPROFILE\.android\debug.keystore" -storepass android -file debug.der
$sha1 = [Security.Cryptography.SHA1]::Create().ComputeHash([IO.File]::ReadAllBytes("debug.der"))
[Convert]::ToBase64String($sha1)                              # 카카오 키 해시
($sha1 | ForEach-Object { $_.ToString("X2") }) -join ":"       # Google SHA-1
```

서버(Render)에도 같은 값의 `KAKAO_NATIVE_APP_KEY`·`GOOGLE_WEB_CLIENT_ID`가 있어야 한다.
없으면 앱이 "지금은 … 로그인을 쓸 수 없어요"를 보인다. `GET /api/health`의 `social`로 확인한다.

## 검증

`APP` 폴더에서 실행한다. Windows는 `.\gradlew.bat`을 사용한다.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

front-iter3 기준 JVM 테스트 863건이 통과한다. 에뮬레이터 UI·E2E 결과는 [MVP 체크리스트](../docs/demo-checklist.md)에 기록한다. Firebase 개인 설정 없이 QA 변형을 검증하려면 다음을 실행한다.

```bash
./gradlew :app:testE2eUnitTest :app:lintE2e :app:assembleE2e :app:assembleE2eAndroidTest -PjitE2e=true
```

`-PjitE2e=true`는 `debug` 대신 `e2e` 변형을 사용한다. 기본 QA 주소는 `http://10.0.2.2:8765/`이며 실제 화면 검증은 아래 E2E 러너가 서버·설치·데이터를 준비한다.

에뮬레이터에서 로그인부터 실제 알람·GPS 도착·리포트까지 자동 검증하려면
[데모 E2E QA](../qa/demo/README.md)를 따른다. 별도 QA 앱과 일회용 DB를 사용한다.
