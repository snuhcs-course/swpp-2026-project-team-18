# JustInTime — Android App

SWPP 팀 프로젝트 앱. 기획 문서는 `../Spec/` 에 있다.

> 디렉터리 구조. 수업용(`SWPP`)과 프로젝트용(`SWPP-PJ`)을 분리해 둔다.
>
> ```
> AndroidStudioProjects/
> ├── SWPP/          수업용 — Lab1~3, LectureNotes
> └── SWPP-PJ/       프로젝트용
>     ├── APP/       이 저장소 (Android 앱)
>     └── Spec/      명세 문서
> ```

| 문서 | 내용 |
| --- | --- |
| `../Spec/project-spec.md` | 기능 목록 F1~F13 |
| `../Spec/front-spec.md` | 화면 S1~S11, 아키텍처 |
| `../Spec/back-spec.md` | Django API, 데이터 모델 |
| `../Spec/dev-plan.md` | 페이즈별 작업(P0~P5), 역할 분담 |
| `../Spec/proposal-draft.md` | 제안서 초안 |

현재 상태는 **P0 스캐폴딩**이다. 앱이 에뮬레이터에서 뜨고 서버 연결을 확인하는 것까지만 되어 있다.

## 구현된 화면

| 화면 | 클래스 | 툴킷 | 상태 |
| --- | --- | --- | --- |
| 로그인 (Figma ⑦) | `ui/auth/LoginScreen` + `LoginActivity` | Compose | **UI만.** 인증 연동 없음 |
| P0 서버 확인 | `MainActivity` | Views | 동작함 |

로그인이 런처 진입 화면이다. 소셜 버튼은 스낵바로 미구현을 알리고, **로그인 없이 둘러보기**가 `MainActivity`로 넘긴다. 인증을 붙일 때 바뀌는 곳은 `LoginActivity.onGoogleClicked()` / `onKakaoClicked()` 두 메서드뿐이며 레이아웃은 그대로 재사용된다.

색은 `res/values/colors.xml` 의 `jit_*` 토큰만 쓴다. 화면에서 색을 직접 쓰지 않는다.

---

## 1. 실행 방법

### Android Studio

`SWPP-PJ/APP` 폴더를 열고 Run 을 누른다. 별도 JDK 설정이 필요 없다.

> Lab2 프로젝트는 Gradle 8.10.2 라서 Gradle JDK 를 21 로 바꿔야 했지만, **이 프로젝트는 Gradle 9.5.0 이라 Android Studio 내장 JDK 25 로 그대로 빌드된다.** 확인된 사항이다.

### 커맨드라인

```powershell
cd C:\Users\rlaji\AndroidStudioProjects\SWPP-PJ\APP
$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

`JAVA_HOME` 이 설정돼 있지 않으면 `ERROR: JAVA_HOME is not set` 이 뜬다. 시스템 PATH 에 java 가 없어서 그렇다.

### 에뮬레이터에 설치

```powershell
$adb="C:\Users\rlaji\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb shell am start -n com.swpp.wakeup/.MainActivity
```

---

## 2. 서버 연결

앱은 `BuildConfig.BASE_URL` 로 백엔드에 접근한다.

| 환경 | 값 |
| --- | --- |
| 에뮬레이터 | `http://10.0.2.2:8000/` (호스트 PC 의 127.0.0.1) |
| 실기기 | 개발 PC 의 LAN IP. `app/build.gradle.kts` 에서 수정 |

Django 는 반드시 `0.0.0.0` 으로 띄운다. `runserver` 기본값(127.0.0.1)으로는 에뮬레이터가 접근할 수 없다.

```powershell
python manage.py runserver 0.0.0.0:8000
```

그리고 Django `settings.py` 에 아래가 있어야 한다. `ALLOWED_HOSTS` 가 비어 있을 때만 DEBUG 모드에서 localhost 가 자동 허용되므로, `10.0.2.2` 를 넣는 순간 localhost 도 명시해야 한다.

```python
ALLOWED_HOSTS = ['10.0.2.2', 'localhost', '127.0.0.1']
```

앱 첫 화면의 **서버 연결 확인** 버튼이 `GET /api/health` 를 호출한다. 백엔드에 아직 이 엔드포인트가 없으면 `ConnectException` 또는 404 가 표시된다. 정상이다.

---

## 3. 현재 구성

### 툴체인 (검증됨)

| 항목 | 버전 |
| --- | --- |
| Gradle | 9.5.0 |
| AGP | 9.3.2 |
| JDK | Android Studio 내장 25 (21 도 가능) |
| compileSdk / targetSdk | 37 / 37 |
| minSdk | 34 |
| Kotlin | AGP 9 내장 (별도 플러그인 선언 없음) |

### 의존성

- AppCompat, Material, ConstraintLayout, Activity KTX, Core KTX
- Lifecycle runtime/viewmodel KTX, Coroutines
- Retrofit 2.11.0 + Gson converter, OkHttp logging interceptor 4.12.0

### 패키지 골격

`front-spec.md` 2절 구조를 미리 만들어 두었다. 빈 폴더에는 `.gitkeep` 이 있다.

```
com.swpp.wakeup
├── MainActivity.kt              P0 연결 확인 화면 (Views). P1 에서 S2 홈으로 대체
├── ui/
│   ├── theme/
│   │   ├── Tokens.kt            ★ 디자인 토큰. 색은 여기서만 정의
│   │   └── Theme.kt             JitTheme. 다크 전용
│   └── auth/
│       ├── LoginActivity.kt     진입 화면 호스트
│       └── LoginScreen.kt       로그인 UI (Figma ⑦)
├── domain/
│   ├── model/                   순수 Kotlin 모델
│   ├── engine/                  ★ 알람 산출 엔진. 안드로이드 의존성 금지
│   └── repository/              인터페이스만
├── data/
│   ├── local/                   Room DB, DAO, Entity
│   ├── remote/                  ApiClient.kt, HealthApi.kt
│   └── repository/              구현체
├── alarm/                       AlarmManager, Receiver, AlarmActivity
├── background/                  WorkManager Worker
├── sensing/                     Geofence, ActivityRecognition, DevInjector
├── ui/                          화면별 View + ViewModel
└── dev/                         개발자 메뉴 (debug only)
```

**`domain/engine` 규칙** — 안드로이드 API 를 import 하지 않는다. 순수 Kotlin 으로 유지해 JVM 단위 테스트로 전부 검증한다. 서버측 계산과 같은 수식을 구현하므로 계약 테스트 대상이다.

---

## 4. 아직 없는 것 (P1 이후)

| 항목 | 페이즈 |
| --- | --- |
| Room DB | P1 |
| 알람 실행 (AlarmManager, 전체화면 Activity) | P1 |
| 캘린더 읽기 | P1 |
| 관측 로깅 + 업로드 Worker | P1 |
| DevMenu | P1 |
| 분포 계산 엔진 | P2 |
| 루틴 블록 | P2 |
| DI 프레임워크 | 미정 |

---

## 5. 결정 사항과 논의 필요

### UI 툴킷: Jetpack Compose (결정됨)

**Compose 로 확정했다.** 검증을 거쳐 결정했고 로그인 화면을 Compose 로 옮겼다.

#### 검증 결과

`buildFeatures { compose = true }` 만으로는 되지 않는다. AGP 가 이렇게 막는다.

```
Starting in Kotlin 2.0, the Compose Compiler Gradle plugin is required
when compose is enabled.
```

**Compose 컴파일러 플러그인을 명시적으로 선언하고 버전을 AGP 내장 Kotlin 과 일치시켜야 한다.**

| 항목 | 값 | 확인 방법 |
| --- | --- | --- |
| AGP 내장 Kotlin | 2.3.20 | `gradlew buildEnvironment` 의 `kotlin-stdlib` 해결 결과 |
| Compose 컴파일러 플러그인 | 2.3.20 | 위와 반드시 동일 |
| Compose BOM | 2026.09.00 | Google Maven `maven-metadata.xml` |

```toml
# gradle/libs.versions.toml
kotlinBundled = "2.3.20"

[plugins]
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlinBundled" }
```

```kotlin
// app/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
android { buildFeatures { compose = true } }
```

> **AGP 를 올릴 때 `kotlinBundled` 도 함께 확인해야 한다.** 두 버전이 어긋나면 구성 단계에서 실패한다.

#### 함께 확인된 것

**Views 와 Compose 는 한 APK 에서 공존한다.** `viewBinding = true` 와 `compose = true` 를 동시에 켜고 빌드·실행이 확인됐다. `MainActivity`(XML)와 로그인(Compose)이 같이 동작한다. 남은 XML 화면을 급히 옮길 필요는 없다.

**약속방 패널 구현이 훨씬 단순해진다.** "패널 자체가 진척 바"인 구조를 Views 에서는 `layoutPositioning = ABSOLUTE` 자식을 깔고 패널 높이를 읽어 리사이즈해야 했지만, Compose 는 `Box` 안에서 `Modifier.fillMaxWidth(progress)` 한 줄이다.

#### 비용

| 항목 | 이전 (Views) | 이후 (Compose) |
| --- | --- | --- |
| 클린 빌드 | 약 50초 | 약 6분 |
| 에뮬레이터 첫 표시 | 즉시 | 13~29초 |

에뮬레이터 콜드 스타트가 느린 것은 Compose 런타임을 JIT 로 올리기 때문이고, 베이스라인 프로파일이 없어 더하다. 두 번째 실행부터는 빠르다. 실기기에서는 이만큼 나빠지지 않는다. 알람 신뢰성 검증을 위해 실기기가 어차피 필요하다.

#### 디자인 토큰

색·반경·여백은 `ui/theme/Tokens.kt` 의 `JitColor` / `JitRadius` / `JitSpace` 에만 둔다. **화면 코드에서 `Color(0xFF...)` 를 직접 쓰지 않는다.** 새 값이 필요하면 Figma 에서 확정한 뒤 토큰에 추가한다.

`res/values/colors.xml` 의 `jit_*` 는 XML 화면이 남아 있는 동안 같은 값을 유지한다. XML 화면이 모두 사라지면 제거한다.

### 그 외

| 항목 | 현재 | 대안 |
| --- | --- | --- |
| DI | 없음. `ApiClient` 는 object | Hilt (KSP 설정 필요) 또는 수동 AppContainer |
| `usesCleartextTraffic` | true (개발용) | 배포 시 제거 또는 network-security-config 로 좁힘 |
| namespace | `com.swpp.wakeup` | 앱 이름이 바뀌어도 유지 가능. `app_name` 만 수정 |

---

## 6. 주의사항

**Kotlin 블록 주석은 중첩된다.** 주석 안에 `/*` 가 들어가면 (예: 경로 표기 `foo/*.json`) 새 주석이 열려 `Unclosed comment` 오류가 난다. Java 와 다른 점이다. 이 프로젝트에서 이미 한 번 겪었다.

**에뮬레이터에서 동작하지 않는 것** — Geofencing 과 Activity Recognition 은 에뮬레이터에서 실질적으로 쓸 수 없다. `sensing/` 에 인터페이스를 두고 debug 빌드에서 주입 구현으로 교체하는 구조가 필요하다 (P1, `front-spec.md` 9.2).
