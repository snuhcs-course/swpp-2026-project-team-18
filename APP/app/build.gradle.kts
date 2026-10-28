import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // app/google-services.json 을 읽어 리소스를 생성한다. 파일이 없으면 빌드 실패.
    alias(libs.plugins.google.services)
    // Room 의 DAO·DB 구현을 생성한다.
    alias(libs.plugins.ksp)
    // 커버리지 리포트(jacocoDebugUnitTestReport, jacocoDebugAndroidTestReport). 아래 정의 참고.
    jacoco
}

/**
 * 서버 주소 결정.
 *
 * 우선순위가 있고, 그 순서에 이유가 있다.
 *
 * 1. `local.properties` 의 `devServerHost` — **로컬 백엔드로 개발할 때.**
 *    이 파일은 커밋되지 않으므로 각자 설정이 서로를 건드리지 않는다.
 * 2. `gradle.properties` 의 `jitApiBaseUrl` — **팀 공용 서버.** 이 파일은
 *    커밋되므로 레포를 clone 한 사람이 아무 설정 없이 같은 서버에 붙는다.
 * 3. 둘 다 없으면 에뮬레이터 기본값(`10.0.2.2`).
 *
 * 1번이 2번보다 앞인 것이 핵심이다. 공용 서버가 기본값이어야 하지만, 백엔드를
 * 고치는 사람은 자기 변경을 자기 PC 에서 확인해야 한다. 공용이 우선이면 로컬
 * 서버를 띄워 놓고도 공용에 붙어 "왜 내 변경이 안 보이지" 가 된다.
 */
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

/** 로컬 백엔드를 쓸 때만 채운다. 비어 있으면 공용 서버로 간다. */
val devServerHost: String? =
    localProps.getProperty("devServerHost")?.trim()?.takeIf { it.isNotEmpty() }

/** 팀 공용 서버. `gradle.properties` 에 있고 커밋된다. */
val sharedApiBaseUrl: String? =
    (project.findProperty("jitApiBaseUrl") as String?)?.trim()?.takeIf { it.isNotEmpty() }

/**
 * 에뮬레이터가 호스트 PC 를 보는 주소. 고정값이다.
 *
 * 에뮬레이터는 자기 자신이 10.0.2.15 이고 호스트를 10.0.2.2 로 본다. 개발 PC 의
 * LAN IP 로는 닿지 못한다(에뮬레이터 NAT 밖이다).
 */
val EMULATOR_SERVER_URL = "http://10.0.2.2:8000/"

/** 실기기·릴리즈가 쓸 주소. */
val apiBaseUrl: String = when {
    devServerHost != null -> "http://$devServerHost:8000/"
    sharedApiBaseUrl != null -> sharedApiBaseUrl.removeSuffix("/") + "/"
    else -> EMULATOR_SERVER_URL
}

/**
 * 에뮬레이터가 쓸 주소.
 *
 * 로컬 백엔드 모드에서만 10.0.2.2 로 우회한다. 공용 서버를 쓸 때 우회하면
 * 에뮬레이터가 아무도 듣지 않는 호스트 포트를 찌른다.
 */
val emulatorBaseUrl: String =
    if (devServerHost != null) EMULATOR_SERVER_URL else apiBaseUrl

// 공용 서버(https)만 쓰는 빌드에서는 평문 트래픽이 필요 없다. 로컬 백엔드는
// http 라서 허용해야 한다. 매니페스트 플레이스홀더로 넘겨 빌드마다 결정한다.
val needsCleartext: Boolean = !apiBaseUrl.startsWith("https://")

logger.lifecycle(
    "[JustInTime] API=$apiBaseUrl (emulator=$emulatorBaseUrl, cleartext=$needsCleartext)" +
        when {
            devServerHost != null -> " ← local.properties devServerHost"
            sharedApiBaseUrl != null -> " ← gradle.properties jitApiBaseUrl"
            else -> " ← 기본값(에뮬레이터). 공용 서버 주소가 설정되지 않았다"
        }
)

android {
    // QA 앱은 개인 앱 데이터와 분리한다. 공용 서버 확인은 명시적으로 선택한다.
    testBuildType = if (providers.gradleProperty("jitE2e").orNull == "true") "e2e" else "debug"
    namespace = "com.swpp.wakeup"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.swpp.wakeup"
        minSdk = 34
        targetSdk = 37
        versionCode = 14
        // 서버 APP_VERSION 과 같은 자리수를 쓴다. 실기기에서 "어느 빌드인가" 를
        // 서버 버전과 나란히 읽을 수 있어야 원인을 좁힐 수 있다.
        //   0.1.0  P1 — 고정 규칙 알람
        //   0.2.0  P2 — 분포 기반 확신도 알람, 루틴 블록
        //   0.3.0  프로토타입 완성 — 근거 카드, 오프라인 캐시, 배경 동기화,
        //          캘린더 가져오기, 주간 리포트
        //   0.4.0  경로 구간·체크포인트와 버스·지하철 실시간 도착정보
        //   0.5.0  장소 검색·지도, 알람 결정 화면 개편 — 계산 방법 세그먼트 바,
        //          경로 거리 기준 진행률, 경로 지도
        //   0.6.0  진행률 고도화 — 약속 시각 기준 지각 색, 움직임 감지,
        //          추적 기반 도착 예정, 임박 경로 주기 갱신
        //   0.7.0  지금 더 빠른 대안 경로를 지도에 초록 점선으로 겹쳐 표시
        //   0.8.0  이동 중 현재 위치 기준 최단 경로를 백그라운드에서도 주기 갱신
        //   0.9.0  장소·경로 지도 핀치 줌, GPS 재중심, 마커/목록 동기화
        //   0.10.0 프론트 UI 통합·알람 켬/끔·비밀번호 검사·약관 동의
        //   0.11.0 주간 리포트 줄글 요약·요일별 도착·상세 리포트(⑨)
        //   0.12.0 서버 0.12.0 과 맞춘다. 앱 코드 변경은 없다(GPS 준비 학습·수동 도착·
        //          알람 재계산·경로 기본 선택은 서버 쪽)
        //   0.13.0 서버 0.13.0 과 맞춘다. 앱 코드 변경은 없다(버퍼 학습·일정 수정 재계산·
        //          소셜 로그인·준비 시간 포함·로그아웃 취소는 서버 쪽, 앱 연동은 할 일)
        //   0.14.0 서버 0.14.0 과 맞춘다. 앱 코드 변경은 없다("안 감" 표시·블록 기록 거르기는
        //          서버 쪽, 앱 버튼은 할 일)
        versionName = "0.14.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AndroidManifest 의 android:usesCleartextTraffic 값. 로컬 백엔드(http)
        // 를 쓸 때만 켠다.
        manifestPlaceholders["usesCleartextTraffic"] = needsCleartext.toString()
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
    }

    buildTypes {
        debug {
            // testDebugUnitTest 에 JaCoCo 에이전트를 붙인다.
            enableUnitTestCoverage = true
            // 디버그 APK 를 JaCoCo 로 계측해 기기 테스트(connectedDebugAndroidTest)의
            // 커버리지를 남긴다. 화면(View) 계층 목표는 이 숫자로 잰다. 디버그 APK 가
            // 조금 느려진다. e2e 빌드에는 켜지 않는다.
            enableAndroidTestCoverage = true
            // 공용 서버(gradle.properties jitApiBaseUrl)가 기본이고,
            // local.properties 에 devServerHost 를 적으면 로컬 백엔드로 바뀐다.
            buildConfigField("String", "BASE_URL", "\"$apiBaseUrl\"")
            // 에뮬레이터 전용 주소. 로컬 백엔드 모드에서만 10.0.2.2 로 우회하고,
            // 공용 서버를 쓸 때는 같은 주소를 쓴다. 한 APK 로 둘 다 되게
            // ApiClient 가 런타임에 고른다.
            buildConfigField("String", "EMULATOR_BASE_URL", "\"$emulatorBaseUrl\"")
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }
        release {
            buildConfigField("String", "BASE_URL", "\"$apiBaseUrl\"")
            buildConfigField("String", "EMULATOR_BASE_URL", "\"$apiBaseUrl\"")
            buildConfigField("boolean", "DEV_TOOLS", "false")
            optimization {
                enable = false
            }
        }
        create("e2e") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".qa"
            versionNameSuffix = "-qa"
            matchingFallbacks += listOf("debug")
            val qaUrl = if (providers.gradleProperty("jitPublicUi").orNull == "true")
                "https://justintime-api.onrender.com/" else "http://10.0.2.2:8765/"
            buildConfigField("String", "BASE_URL", "\"$qaUrl\"")
            buildConfigField("String", "EMULATOR_BASE_URL", "\"$qaUrl\"")
            manifestPlaceholders["usesCleartextTraffic"] = (!qaUrl.startsWith("https://")).toString()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // JDK 25(Gradle 데몬)에서도 도는 버전. 기본값은 AGP 가 정하는데 새 JDK 의
    // 클래스 파일을 못 읽는 옛 버전일 수 있다.
    testCoverage {
        jacocoVersion = libs.versions.jacoco.get()
    }

    testOptions {
        // 기기 테스트(connectedDebugAndroidTest) 동안 시스템 애니메이션을 끈다.
        // 켜 두면 화면 전환 중간 상태를 읽어 같은 테스트가 PC 마다 다르게 나온다.
        animationsDisabled = true
        unitTests {
            // JVM 단위 테스트에서 안드로이드 프레임워크 호출이 예외를 던지지
            // 않고 기본값을 돌려주게 한다.
            //
            // 켜지 않으면 `android.util.Log` 를 부르는 코드를 단위 테스트할 수
            // 없다 - "Method i in android.util.Log not mocked" 로 죽는다.
            // 로그를 지우거나 로거를 주입하는 방법도 있지만, 로그는 실기기에서
            // 원인을 찾는 유일한 창구라 빼고 싶지 않다.
            isReturnDefaultValues = true
        }
    }

    // JVM 단위 테스트와 기기 테스트가 같은 가짜 API·픽스처 도우미를 쓴다
    // (src/sharedTest/.../testing/Fakes.kt). 한쪽에만 두면 두 벌이 되고, 두 벌은
    // 서버 응답 모양이 바뀔 때 반드시 갈린다.
    sourceSets {
        getByName("test").kotlin.directories.add("src/sharedTest/java")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/java")
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // lifecycle / coroutines
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // network
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging.interceptor)

    // compose. BOM 이 나머지 compose 아티팩트 버전을 고정한다.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // firebase. BOM 이 나머지 firebase 아티팩트 버전을 고정한다.
    // 지금은 FCM(P4) 만 쓴다. analytics 는 명세에 없어 넣지 않는다.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    // 위치. 출발·도착 판별에 쓴다(sensing 패키지).
    implementation(libs.play.services.location)

    // Room. **서버 응답 사본만** 담는 오프라인 캐시다. 정본은 서버이고
    // 이 DB 를 잃어도 네트워크 왕복 한 번이면 복구된다 — 그래서 마이그레이션을
    // 쓰지 않고 파괴적 재생성을 택했다(JitDatabase 주석 참고).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager. 앱이 닫혀 있어도 관측을 올리고 알람 등록을 갱신한다.
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.uiautomator)
    // 화면 통합 테스트(src/androidTest/.../integration). 실행은 README "Verification".
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

/**
 * JVM 단위 테스트 커버리지 리포트.
 *
 *     ./gradlew jacocoDebugUnitTestReport
 *     → app/build/reports/jacoco/jacocoDebugUnitTestReport/html/index.html
 *     → app/build/reports/jacoco/jacocoDebugUnitTestReport/jacocoDebugUnitTestReport.xml
 *     python3 scripts/coverage_summary.py   # 계층별 표 (APP 에서)
 *
 * AGP 내장 createDebugUnitTestCoverageReport 를 쓰지 않는 이유: 제외 규칙을 줄 수
 * 없다. 생성 코드가 섞이면 숫자가 "우리가 쓴 코드 중 테스트가 닿은 비율" 이 아니게
 * 된다. 빼는 것은 아래 목록뿐이고, 화면(Composable)은 빼지 않는다 — 낮게 나오는
 * 것이 사실이고, 그 계층의 목표를 따로 잡는다.
 */
val coverageExcludes = listOf(
    // AGP 생성
    "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
    // Room(KSP) 생성 DAO·DB 구현
    "**/*_Impl.class", "**/*_Impl$*.class",
    // Compose 컴파일러가 만든 람다 보관 클래스
    "**/ComposableSingletons*.*",
    // 색·글꼴 토큰, 앱 진입점(내비게이션 그래프). 로직이 없다.
    "**/ui/theme/**", "**/MainActivity*.*", "**/JitApplication*.*",
)

// AGP 9 는 Kotlin 을 내장 컴파일러로 빌드해 클래스가 여기에 생긴다.
// 기기 테스트 리포트도 계측 전 원본인 이 클래스로 읽는다.
val debugClassDir = layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")

tasks.register<JacocoReport>("jacocoDebugUnitTestReport") {
    group = "verification"
    description = "testDebugUnitTest 를 돌리고 생성 코드를 뺀 줄 커버리지 리포트를 만든다."
    val unitTest = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn(unitTest)
    executionData(unitTest)
    classDirectories.setFrom(fileTree(debugClassDir) { exclude(coverageExcludes) })
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
}

/**
 * 기기 통합 테스트 커버리지 리포트. 에뮬레이터를 켠 상태에서:
 *
 *     ./gradlew jacocoDebugAndroidTestReport -Pandroid.testInstrumentationRunnerArguments.package=com.swpp.wakeup.integration
 *     → app/build/reports/jacoco/jacocoDebugAndroidTestReport/html/index.html
 *     python3 scripts/coverage_summary.py app/build/reports/jacoco/jacocoDebugAndroidTestReport/jacocoDebugAndroidTestReport.xml
 *
 * 단위 테스트 리포트와 같은 클래스·제외 규칙을 써서 두 숫자의 분모가 같다. AGP 내장
 * createDebugAndroidTestCoverageReport 를 쓰지 않는 이유도 위와 같다. 패키지 필터를
 * 빼면 QA 빌드가 필요한 E2E 테스트까지 돌아 실패한다.
 */
tasks.register<JacocoReport>("jacocoDebugAndroidTestReport") {
    group = "verification"
    description = "connectedDebugAndroidTest 를 돌리고 생성 코드를 뺀 줄 커버리지 리포트를 만든다."
    dependsOn("connectedDebugAndroidTest")
    // 기기마다 하위 폴더에 .ec 파일이 생긴다. 여러 기기면 합쳐서 센다.
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("outputs/code_coverage/debugAndroidTest/connected")) {
            include("**/*.ec")
        }
    )
    classDirectories.setFrom(fileTree(debugClassDir) { exclude(coverageExcludes) })
    sourceDirectories.setFrom(files("src/main/java", "src/main/kotlin"))
    reports {
        xml.required = true
        html.required = true
        csv.required = false
    }
}

// QA는 FCM을 사용하지 않는다. 개인 앱의 Firebase 등록 정보도 공유하지 않는다.
tasks.matching { it.name == "processE2eGoogleServices" }.configureEach { enabled = false }
