// AI-generated (Claude)
package com.swpp.wakeup.ui.auth

import com.swpp.wakeup.domain.model.SocialProvider
import com.swpp.wakeup.ui.auth.AuthViewModel.Screen
import com.swpp.wakeup.ui.auth.AuthViewModel.SystemBack
import com.swpp.wakeup.ui.auth.AuthViewModel.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 인증 화면에서 시스템 뒤로(제스처·뒤로 버튼)가 할 일([UiState.systemBack]). `AuthHost` 의 BackHandler 는 이 값이
 * [SystemBack.SYSTEM] 이 아닐 때 켜지고([UiState.handlesSystemBack]), `AuthViewModel.onSystemBack` 은 움직일 수 있을
 * 때([UiState.canGoBack]) 그 화면의 ‹ 와 같은 함수를 부른다. 켤지와 멈출지는 여기서 화면마다 값을 보고, BackHandler 와
 * ‹ 함수의 연결은 [AuthBackWiringTest] 가 소스에서 고정한다.
 *
 * ## 이 테스트가 지키는 것
 *
 * - 로그인 밖의 화면은 모두 뒤로를 받는다. 가입 화면은 받지 않아서 뒤로가 로그인으로 돌아가지 않고 앱을 닫았다.
 * - 서버를 기다리는 중에도 받되 움직이지 않는다. 그동안 놓아 두면 시스템 기본 동작이 앱을 닫아, 진행 중인
 *   요청의 결과(보낸 재설정 코드 등)까지 잃었다.
 * - 가는 곳은 화면의 ‹ 와 같다. 코드 확인 화면은 코드 요청 화면으로 한 칸만 돌아간다.
 * - 로그인 화면은 받지 않는다. 거기서 뒤로는 앱을 닫아야 한다.
 */
class AuthSystemBackTest {

    @Test
    fun `화면과 기다리는 요청마다 시스템 뒤로가 할 일이 정해져 있다`() {
        // Given 화면마다 (기다리는 요청 없음, 이메일 요청 중, 소셜 요청 중)에 바라는 값
        val expected = mapOf(
            Screen.LOGIN to listOf(SystemBack.SYSTEM, SystemBack.SYSTEM, SystemBack.SYSTEM),
            Screen.SIGNUP to listOf(SystemBack.TO_LOGIN, SystemBack.STAY, SystemBack.STAY),
            Screen.SOCIAL_TERMS to listOf(SystemBack.TO_LOGIN, SystemBack.STAY, SystemBack.STAY),
            Screen.RESET_REQUEST to listOf(SystemBack.TO_LOGIN, SystemBack.STAY, SystemBack.STAY),
            Screen.RESET_CONFIRM to listOf(SystemBack.TO_RESET_REQUEST, SystemBack.STAY, SystemBack.STAY),
        )
        // 화면을 빠짐없이 적었다. 화면이 늘면 여기서 걸린다
        assertEquals(Screen.entries.toSet(), expected.keys)

        expected.forEach { (screen, want) ->
            // When 각 상태에서 시스템 뒤로가 할 일을 본다
            val actual = listOf(
                UiState(screen = screen).systemBack,
                UiState(screen = screen, loading = true).systemBack,
                UiState(screen = screen, socialBusy = SocialProvider.KAKAO).systemBack,
            )

            // Then 바라는 값과 같다
            assertEquals("$screen (요청 없음, 이메일 요청 중, 소셜 요청 중)", want, actual)
        }
    }

    @Test
    fun `BackHandler 를 켜는 값과 한 칸 돌아가는 값은 같은 판단에서 나온다`() {
        Screen.entries.forEach { screen ->
            listOf(
                UiState(screen = screen),
                UiState(screen = screen, loading = true),
                UiState(screen = screen, socialBusy = SocialProvider.GOOGLE),
            ).forEach { state ->
                // Given 아무 화면 · 상태
                // When BackHandler 를 켤지, 눌렀을 때 움직일지 본다
                // Then 받지 않는 것은 SYSTEM 뿐이고, 움직이는 것은 갈 곳이 있을 때뿐이다
                val label = "$screen loading=${state.loading} social=${state.socialBusy}"
                assertEquals(label, state.systemBack != SystemBack.SYSTEM, state.handlesSystemBack)
                assertEquals(
                    label,
                    state.systemBack == SystemBack.TO_LOGIN || state.systemBack == SystemBack.TO_RESET_REQUEST,
                    state.canGoBack,
                )
            }
        }
    }

    @Test
    fun `가입 화면은 시스템 뒤로를 받아 로그인으로 돌아갈 수 있다`() {
        // Given 가입 화면, 기다리는 요청 없음
        val signup = UiState(screen = Screen.SIGNUP)

        // When BackHandler 가 켜질지, 눌렀을 때 움직일지 본다
        val handled = signup.handlesSystemBack
        val moves = signup.canGoBack

        // Then 앱이 받고 한 단계 돌아간다
        assertTrue("가입 화면이 뒤로를 받지 않으면 앱이 닫힌다", handled)
        assertTrue(moves)
    }

    @Test
    fun `서버를 기다리는 중에는 뒤로를 받기만 하고 움직이지 않는다`() {
        // Given 화면마다 서버를 기다리는 상태
        val waiting = listOf(
            UiState(screen = Screen.SIGNUP, loading = true),
            UiState(screen = Screen.SOCIAL_TERMS, socialBusy = SocialProvider.GOOGLE),
            UiState(screen = Screen.RESET_REQUEST, loading = true),
            UiState(screen = Screen.RESET_CONFIRM, loading = true),
        )

        waiting.forEach { state ->
            // Then 놓으면 시스템이 앱을 닫으므로 받는다. 다만 화면은 그대로 둔다
            assertTrue("${state.screen}: 기다리는 중에도 뒤로를 받아야 한다", state.handlesSystemBack)
            assertFalse("${state.screen}: 기다리는 중에는 움직이지 않는다", state.canGoBack)
        }
    }

    @Test
    fun `처음 가입과 비밀번호 찾기도 기다리는 요청이 없으면 한 단계 돌아간다`() {
        // Given 기다리는 요청이 없는 각 화면
        listOf(Screen.SOCIAL_TERMS, Screen.RESET_REQUEST, Screen.RESET_CONFIRM).forEach { screen ->
            val state = UiState(screen = screen)

            // Then 받고 돌아간다
            assertTrue("$screen", state.handlesSystemBack)
            assertTrue("$screen", state.canGoBack)
        }
    }

    @Test
    fun `로그인 화면은 시스템 뒤로를 받지 않아 앱을 닫는다`() {
        // Given 로그인 화면(요청을 기다리는 중이어도 같다)
        val idle = UiState(screen = Screen.LOGIN)
        val loggingIn = UiState(screen = Screen.LOGIN, loading = true)

        // Then BackHandler 가 꺼져 시스템 기본 동작이 돈다
        assertFalse(idle.handlesSystemBack)
        assertFalse(loggingIn.handlesSystemBack)
        assertFalse(idle.canGoBack)
    }
}
