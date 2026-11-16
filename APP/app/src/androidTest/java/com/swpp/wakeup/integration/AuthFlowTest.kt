package com.swpp.wakeup.integration

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.wakeup.data.local.LastEmailStore
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.ui.auth.LoginActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * - 가입 화면과 비밀번호 찾기 화면에서 시스템 뒤로는 앱을 닫지 않고 로그인으로 돌아온다.
 * - 카카오·Google 버튼이 켜져 있다(누르면 외부 앱·서버를 부르므로 누르지 않는다).
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
        LastEmailStore(InstrumentationRegistry.getInstrumentation().targetContext).clear()
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

    // AI-generated (Claude) — 시스템 뒤로가 가입 화면에서 로그인으로 돌아가지 않고 앱을 닫던 문제(HE 2-7).
    // 서버는 부르지 않는다. 가입 화면을 열고 뒤로만 누른다.
    @Test
    fun `회원가입에서 시스템 뒤로를 누르면 앱을 닫지 않고 로그인으로 돌아온다`() {
        // Given 로그인 화면에서 회원가입으로 갔다
        compose.onNodeWithText("회원가입").performScrollTo().performClick()
        compose.onNodeWithTag("signup_scroll").assertExists()

        // When 시스템 뒤로(제스처·뒤로 버튼과 같은 경로)를 누른다. 키보드가 떠 있으면 첫 뒤로는 키보드만
        // 닫으므로 먼저 닫는다. 앱이 뒤로를 받지 않으면 pressBack 이 NoActivityResumedException 으로 실패한다.
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()

        // Then 로그인 화면이 다시 보이고 액티비티는 살아 있다
        compose.onNodeWithTag("login_email").assertExists()
        compose.onNodeWithTag("signup_scroll").assertDoesNotExist()
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        scenario.onActivity { activity -> assertFalse("뒤로가 액티비티를 닫았다", activity.isFinishing) }
    }

    // AI-generated (Claude) — 비밀번호 찾기에서도 시스템 뒤로가 화면의 ‹ 처럼 로그인으로 돌아와야 한다(HE 2-7).
    // 서버는 부르지 않는다. 코드 요청 화면을 열고 뒤로만 누른다(확인 코드 받기를 누르지 않는다).
    @Test
    fun `비밀번호 찾기에서 시스템 뒤로를 누르면 앱을 닫지 않고 로그인으로 돌아온다`() {
        // Given 로그인 화면에서 이메일을 넣고 비밀번호 찾기로 갔다
        compose.onNodeWithTag("login_email").performTextInput("me@snu.ac.kr")
        compose.onNodeWithTag("login_forgot").performScrollTo().performClick()
        compose.onNodeWithTag("reset_request_scroll").assertExists()

        // When 시스템 뒤로를 누른다. 키보드가 떠 있으면 첫 뒤로는 키보드만 닫으므로 먼저 닫는다
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()

        // Then 로그인 화면이 이메일을 그대로 들고 다시 보이고 액티비티는 살아 있다
        compose.onNodeWithTag("login_email").assertTextContains("me@snu.ac.kr")
        compose.onNodeWithTag("reset_request_scroll").assertDoesNotExist()
        assertEquals(Lifecycle.State.RESUMED, scenario.state)
        scenario.onActivity { activity -> assertFalse("뒤로가 액티비티를 닫았다", activity.isFinishing) }
    }

    // Partly AI-generated (Claude) — 누르면 카카오톡·브라우저·구글 계정 창과 서버를 부르므로 누르지 않는다.
    @Test
    fun `카카오와 Google 버튼은 기다리는 요청이 없으면 켜져 있다`() {
        compose.onNodeWithTag("login_google").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("login_kakao").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("social_notice").assertDoesNotExist()
    }
}
