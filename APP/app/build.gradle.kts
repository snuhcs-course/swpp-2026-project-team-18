import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    // app/google-services.json 을 읽어 리소스를 생성한다. 파일이 없으면 빌드 실패.
    alias(libs.plugins.google.services)
}

/**
 * 개발 서버 호스트.
 *
 * 에뮬레이터는 호스트 PC 의 127.0.0.1 을 `10.0.2.2` 로 본다. **실기기는 그 주소를
 * 모른다** — 개발 PC 의 LAN IP 가 필요하다. 그 IP 는 사람마다 다르고 네트워크가
 * 바뀌면 같은 사람도 달라지므로 커밋되는 파일에 박아 두면 안 된다.
 *
 * `local.properties`(gitignore 대상) 에 아래처럼 적으면 그 값을 쓴다.
 *
 *     devServerHost=192.168.0.12
 *
 * 없으면 에뮬레이터 기본값을 쓴다. 팀원은 각자 자기 IP 만 적으면 되고 서로의
 * 설정이 충돌하지 않는다.
 */
val devServerHost: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("devServerHost")?.trim()?.takeIf { it.isNotEmpty() } ?: "10.0.2.2"

val devServerUrl = "http://$devServerHost:8000/"

android {
    namespace = "com.swpp.wakeup"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.swpp.wakeup"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
        compose = true
    }

    buildTypes {
        debug {
            // 기본값은 에뮬레이터용 10.0.2.2 다. 실기기는 local.properties 의
            // devServerHost 로 개발 PC 의 LAN IP 를 지정한다(위 주석 참고).
            buildConfigField("String", "BASE_URL", "\"$devServerUrl\"")
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }
        release {
            buildConfigField("String", "BASE_URL", "\"$devServerUrl\"")
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

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
