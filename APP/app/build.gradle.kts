import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // app/google-services.json 을 읽어 리소스를 생성한다. 파일이 없으면 빌드 실패.
    alias(libs.plugins.google.services)
    // Room 의 DAO·DB 구현을 생성한다.
    alias(libs.plugins.ksp)
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
    namespace = "com.swpp.wakeup"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.swpp.wakeup"
        minSdk = 34
        targetSdk = 37
        versionCode = 8
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
        versionName = "0.8.0"

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
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
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
}
