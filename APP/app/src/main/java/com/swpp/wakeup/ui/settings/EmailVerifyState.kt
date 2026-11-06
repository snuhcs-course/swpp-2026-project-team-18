package com.swpp.wakeup.ui.settings

// Partly AI-generated (Claude)
/**
 * 설정의 "이메일 인증" 화면 상태(Figma F-53 `530:9`). 인증은 선택이라 로그인을 막지 않는다.
 * 코드는 어디에도 저장하지 않고, 화면을 열 때마다 비운다.
 */
data class EmailVerifyState(
    /** 숫자만, 6자리까지 */
    val code: String = "",
    /** 코드를 확인하는 중 */
    val busy: Boolean = false,
    /** 코드를 보내는 중(다시 받기) */
    val sending: Boolean = false,
    /** 입력칸 아래 오류 줄 */
    val error: String? = null,
    /** 다시 받았다는 안내 한 줄 */
    val info: String? = null,
    /** 코드를 보낸 주소 */
    val sentTo: String? = null,
) {
    val canSubmit: Boolean get() = !busy && !sending && code.length == CODE_LENGTH

    companion object {
        const val CODE_LENGTH = 6
    }
}