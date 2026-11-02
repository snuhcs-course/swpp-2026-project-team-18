package com.swpp.wakeup.ui.settings

import com.swpp.wakeup.data.remote.PasswordCheckResponse

// Partly AI-generated (Claude)
/**
 * 설정의 "비밀번호 변경" 화면 상태(Figma F-41 `508:9`). 새 비밀번호의 규칙 검사는 가입 화면과 같이
 * 서버 `password/check` 결과를 쓴다.
 */
data class PasswordChangeState(
    val current: String = "",
    val newPassword: String = "",
    val confirm: String = "",
    val passwordCheck: PasswordCheckResponse? = null,
    val passwordCheckError: String? = null,
    val busy: Boolean = false,
    /** 화면 아래 오류 줄 */
    val error: String? = null,
    /** 서버가 "지금 비밀번호가 맞지 않음" 이라고 한 경우. 지금 비밀번호 칸 아래에 그린다 */
    val currentWrong: Boolean = false,
) {
    val passwordsMatch: Boolean
        get() = confirm.isNotEmpty() && newPassword == confirm

    /** 지금 비밀번호가 있고, 새 비밀번호가 확인과 같고 규칙을 지키며, 지금 것과 달라야 한다 */
    val canSubmit: Boolean
        get() = !busy && current.isNotBlank() && passwordsMatch &&
            passwordCheck?.valid == true && newPassword != current
}
