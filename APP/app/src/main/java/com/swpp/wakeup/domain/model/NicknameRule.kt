// AI-generated (Claude)
package com.swpp.wakeup.domain.model

/**
 * 가입 닉네임 규칙(iter4 H2-11, Figma `549:2029`). 서버 `RegisterSerializer.nickname` 과 같다:
 * 앞뒤 공백을 자른 뒤(`trim_whitespace`) 1~20자. 글자 수는 서버(파이썬)처럼 **코드 포인트**로 센다 —
 * Kotlin `length` 는 이모지 하나를 2로 세서 서버보다 일찍 막는다.
 *
 * 칸 아래 줄(왼쪽 안내 · 오른쪽 글자 수)의 문구와 색도 여기서 정한다. 이메일 칸(`505:14`)과 같이
 * "비었음" 은 칸을 벗어난 뒤에만, "너무 김" 은 치는 동안 바로 보인다.
 */
object NicknameRule {

    const val MAX = SocialLogin.NICKNAME_MAX

    const val HINT = "다른 사람에게 보이는 이름이에요 · ${MAX}자까지"
    const val TOO_LONG = "닉네임은 ${MAX}자까지 쓸 수 있어요"
    const val EMPTY = "닉네임을 입력해 주세요"

    /** 서버가 세는 글자 수 */
    fun length(raw: String): Int = raw.trim().let { it.codePointCount(0, it.length) }

    fun isValid(raw: String): Boolean = length(raw) in 1..MAX

    fun tooLong(raw: String): Boolean = length(raw) > MAX

    /** 오른쪽 글자 수 "7/20" */
    fun counter(raw: String): String = "${length(raw)}/$MAX"

    /**
     * 칸 아래 왼쪽 줄. [message] 가 null 이 아니면 빨간 오류다.
     *
     * @param blurred 칸에 들어갔다가 벗어난 적이 있는가
     * @param serverError 서버가 400 `details.nickname` 으로 준 이유. 고칠 때까지 보인다
     */
    fun error(raw: String, blurred: Boolean, serverError: String?): String? = when {
        tooLong(raw) -> TOO_LONG
        blurred && length(raw) == 0 -> EMPTY
        serverError != null -> serverError
        else -> null
    }
}
