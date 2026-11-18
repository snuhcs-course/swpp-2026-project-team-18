// Partly AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.ui.auth.LoginScreen
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 로그인 잠금(C2): 잠기면 카운트다운 · [로그인] 꺼짐 · [비밀번호 찾기] 켜짐, 401 이면 남은 횟수 */
@RunWith(AndroidJUnit4::class)
class LoginLockScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lockedShowsCountdownAndKeepsResetOpen() {
        val now = 1_000_000_000L
        compose.setContent {
            JitTheme {
                LoginScreen(
                    email = "a@b.com", password = "x", loading = false, error = null, canSubmit = false,
                    onEmailChange = {}, onPasswordChange = {}, onLoginClick = {}, onSignupClick = {},
                    onGoogleClick = {}, onKakaoClick = {}, onForgotClick = {},
                    lockedUntilMillis = now + 872_000L, nowMillis = now,
                )
            }
        }
        compose.onNodeWithText("비밀번호를 여러 번 틀려서 잠시 잠겼어요. 14분 32초 뒤에 다시 로그인할 수 있어요").assertExists()
        compose.onNodeWithText("비밀번호를 재설정하면 바로 풀려요").assertExists()
        compose.onNodeWithTag("login_submit").assertIsNotEnabled()
        compose.onNodeWithTag("login_forgot").assertIsEnabled()
    }

    @Test
    fun remainingAttemptsLine() {
        compose.setContent {
            JitTheme {
                LoginScreen(
                    email = "a@b.com", password = "x", loading = false, error = "이메일 또는 비밀번호가 맞지 않아요.", canSubmit = true,
                    onEmailChange = {}, onPasswordChange = {}, onLoginClick = {}, onSignupClick = {},
                    onGoogleClick = {}, onKakaoClick = {}, onForgotClick = {},
                    attemptsHint = "3번 더 틀리면 15분 동안 잠겨요",
                )
            }
        }
        compose.onNodeWithTag("login_attempts").assertExists()
        compose.onNodeWithTag("login_locked").assertDoesNotExist()
    }
}
