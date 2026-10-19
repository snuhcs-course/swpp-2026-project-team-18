package com.swpp.wakeup.integration

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.ui.auth.LoginActivity
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 로그인 화면 ↔ 회원가입 화면. 실제 [LoginActivity] 와 실제 `AuthViewModel` 을 띄운다.
 *
 * ## 서버를 부르지 않는다
 *
 * 로그인 버튼을 누르지 않고, 가입 화면에서는 비밀번호를 입력하지 않는다(입력하면
 * 서버 비밀번호 검사를 부른다). CI 의 디버그 빌드는 팀 공용 서버를 가리키므로
 * 여기서 요청이 나가면 공용 서버에 기록이 남는다. 서버가 필요한 흐름은 QA
 * 재생 서버를 쓰는 E2E(`qa/demo/run.py`)가 맡는다.
 *
 * ## 이 테스트가 지키는 것
 *
 * - 이메일·비밀번호가 다 있어야 로그인 버튼이 켜진다.
 * - 화면을 옮겨도 **이메일은 남고 비밀번호는 지워진다.** 가입 화면에 로그인
 *   비밀번호가 따라가면 확인 칸과 어긋난 채로 제출된다.
 * - 소셜 로그인은 아직 없다고 말하고 화면을 떠나지 않는다.
 *
 * 기기에 로그인 상태가 남아 있으면 이 화면을 건너뛰므로 시작 전에 토큰을 지운다.
 * 개발용 에뮬레이터에서 돌리면 디버그 앱이 로그아웃된다.
 */
@RunWith(AndroidJUnit4::class)
class AuthFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<LoginActivity>

    @Before
    fun launchLoggedOut() {
        TokenStore(InstrumentationRegistry.getInstrumentation().targetContext).clear()
        scenario = ActivityScenario.launch(LoginActivity::class.java)
    }

    @After
    fun close() = scenario.close()

    @Test
    fun `로그인 버튼은 이메일과 비밀번호를 모두 넣어야 켜진다`() {
        compose.onNodeWithTag("login_submit").assertIsNotEnabled()

        compose.onNodeWithTag("login_email").performTextInput("me@snu.ac.kr")
        compose.onNodeWithTag("login_submit").assertIsNotEnabled()

        compose.onNodeWithTag("login_password").performTextInput("pw12345678")
        compose.onNodeWithTag("login_submit").assertIsEnabled()
    }

    @Test
    fun `회원가입에 갔다 돌아와도 이메일은 남고 비밀번호는 지워진다`() {
        compose.onNodeWithTag("login_email").performTextInput("me@snu.ac.kr")
        compose.onNodeWithTag("login_password").performTextInput("pw12345678")

        compose.onNodeWithText("회원가입").performScrollTo().performClick()

        compose.onNodeWithTag("signup_email").assertTextContains("me@snu.ac.kr")
        compose.onNodeWithTag("signup_submit").performScrollTo().assertIsNotEnabled()

        compose.onNodeWithText("로그인").performScrollTo().performClick()

        compose.onNodeWithTag("login_email").assertTextContains("me@snu.ac.kr")
        compose.onNodeWithTag("login_submit").assertIsNotEnabled()
    }

    @Test
    fun `소셜 로그인은 아직 없다고 안내하고 로그인 화면에 머문다`() {
        compose.onNodeWithText("Google로 계속하기").performScrollTo().performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("소셜 로그인은 아직 없음. 이메일로 로그인한다.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("login_scroll").assertExists()
    }
}
