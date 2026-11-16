// AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.ui.settings.ChangePasswordScreen
import com.swpp.wakeup.ui.settings.PasswordChangeState
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 설정 > 비밀번호 변경 화면(`ChangePasswordScreen`).
 *
 * 화면 상태 클래스(`PasswordChangeState`)를 들고 그린다. 버튼을 켤지 정하는 규칙(`canSubmit` · `sameAsCurrent`)은
 * 실제 코드를 탄다. 서버 비밀번호 검사 결과는 상태에 넣어 주고, 서버는 부르지 않는다.
 *
 * ## 이 테스트가 지키는 것
 *
 * - 새 비밀번호가 지금 것과 같으면 버튼이 꺼진 이유가 새 비밀번호 칸 아래에 보인다. 칸이 가려져 있어 같은 줄 모르고,
 *   버튼이 꺼져 있으니 서버의 거절 문구도 나올 수 없었다.
 * - 두 규칙 점이 다 초록인데 서버가 거절한 이유(흔한 비밀번호)가 규칙 아래에 보인다.
 * - 이유가 없으면 줄도 없고 버튼이 켜진다. 위 두 경우의 꺼짐이 이유 때문이라는 것을 보인다.
 */
@RunWith(AndroidJUnit4::class)
class ChangePasswordScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val passed = fromJson<PasswordCheckResponse>(
        """{"min_length":true,"letters_and_digits":true,"messages":[]}"""
    )

    /** 지금 것과 다르고 서버 규칙을 통과한 새 비밀번호. 버튼이 켜지는 상태 */
    private val ready = PasswordChangeState(
        current = "Oldpass123",
        newPassword = "Newpass456",
        confirm = "Newpass456",
        passwordCheck = passed,
    )

    private val state = mutableStateOf(ready)

    /** 뷰 모델의 입력 처리처럼 상태만 바꾼다 */
    private fun show(initial: PasswordChangeState) {
        state.value = initial
        compose.setContent {
            JitTheme {
                ChangePasswordScreen(
                    state = state.value,
                    onCurrentChange = { v -> state.value = state.value.copy(current = v) },
                    onNewChange = { v -> state.value = state.value.copy(newPassword = v) },
                    onConfirmChange = { v -> state.value = state.value.copy(confirm = v) },
                    onSubmit = {},
                    onBack = {},
                )
            }
        }
    }

    private fun submit() = compose.onNodeWithTag("pw_submit").performScrollTo()

    @Test
    fun `새 비밀번호가 지금 것과 같으면 칸 아래에 이유를 보이고 버튼이 꺼진다`() {
        // Given 지금 비밀번호를 새 칸과 확인 칸에 다시 넣었다. 서버 규칙 검사는 통과했다
        show(ready.copy(newPassword = "Oldpass123", confirm = "Oldpass123"))

        // Then 새 비밀번호 칸 아래에 이유가 보이고 버튼은 꺼져 있다
        compose.onNodeWithTag("pw_new_same").assertTextEquals("지금 비밀번호와 같아요. 다른 비밀번호를 써 주세요")
        submit().assertIsNotEnabled()

        // When 새 비밀번호와 확인을 다른 값으로 고친다
        compose.onNodeWithTag("pw_new").performTextReplacement("Newpass456")
        compose.onNodeWithTag("pw_new_confirm").performTextReplacement("Newpass456")

        // Then 이유가 사라지고 버튼이 켜진다
        compose.onNodeWithTag("pw_new_same").assertDoesNotExist()
        submit().assertIsEnabled()
    }

    @Test
    fun `두 점을 다 통과했는데 서버가 거절하면 그 이유를 규칙 아래에 보이고 버튼이 꺼진다`() {
        // Given "password1": 두 점은 초록이지만 흔한 비밀번호라 서버가 통과시키지 않는다
        val common = fromJson<PasswordCheckResponse>(
            """{"min_length":true,"letters_and_digits":true,"messages":["너무 흔히 사용되는 비밀번호입니다."]}"""
        )
        show(ready.copy(newPassword = "password1", confirm = "password1", passwordCheck = common))

        // Then 버튼이 꺼진 이유가 화면에 있다. 지금 것과 같다는 줄은 아니다
        compose.onNodeWithTag("pw_new_reason").performScrollTo()
            .assertTextEquals("너무 흔히 사용되는 비밀번호입니다.")
        compose.onNodeWithTag("pw_new_same").assertDoesNotExist()
        submit().assertIsNotEnabled()
    }

    @Test
    fun `이유가 없으면 줄이 없고 버튼이 켜진다`() {
        // Given 지금 것과 다르고 서버 검사를 통과한 새 비밀번호
        show(ready)

        // Then 이유 줄이 하나도 없고 버튼이 켜진다
        compose.onNodeWithTag("pw_new_same").assertDoesNotExist()
        compose.onNodeWithTag("pw_new_reason").assertDoesNotExist()
        submit().assertIsEnabled()
    }
}
