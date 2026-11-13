// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

/**
 * 도움말 › [문의하기](iter4 H2-4, 백엔드 요청 BE-H4b, 계약 `docs/iter4/inquiry-api.md`).
 *
 * 앱 안에서 글을 써서 서버로 보낸다. 답은 이메일로 받으므로 답 받을 이메일을 함께 적는다(로그인 이메일을 미리
 * 채운다 — 이메일을 주지 않은 소셜 계정은 비어 있다). 규칙과 문구를 여기 모아 JVM 테스트로 고정한다.
 */
object Inquiry {

    /** 너무 짧은 글("안돼요")은 무엇을 물었는지 알 수 없어 받지 않는다 */
    const val MIN = 10
    const val MAX = 1000

    const val TITLE = "문의하기"
    const val SUBTITLE = "불편한 점이나 궁금한 점을 적어 주세요. 답은 이메일로 보내 드려요."
    const val MESSAGE_LABEL = "문의 내용"
    const val MESSAGE_HINT = "예: 알람이 예상보다 30분 일찍 울렸어요"
    const val EMAIL_LABEL = "답 받을 이메일"
    const val EMAIL_HINT = "name@example.com"
    const val SEND = "보내기"
    const val SENDING = "보내는 중…"
    const val INFO = "앱 버전과 기기 종류가 함께 전달돼요."

    const val TOO_SHORT = "${MIN}자 이상 적어 주세요"
    const val TOO_LONG = "${MAX}자까지 쓸 수 있어요"
    const val EMAIL_REQUIRED = "답 받을 이메일을 적어 주세요"
    const val EMAIL_INVALID = "이메일 형식을 확인해 주세요"

    const val DONE = "문의를 보냈어요. 답은 이메일로 보내 드릴게요"
    const val UNSUPPORTED = "아직 앱에서 문의를 받을 수 없어요. 적은 내용은 그대로 두었어요"
    const val LIMITED = "문의를 너무 자주 보냈어요. 잠시 후 다시 보내 주세요"
    const val FAILED = "문의를 보내지 못했어요. 잠시 후 다시 시도해 주세요"
    const val OFFLINE = "서버에 연결되지 않아 문의를 보내지 못했어요"

    enum class Outcome { DONE, UNSUPPORTED, LIMITED, FAILED, OFFLINE }

    /** 앞뒤 공백을 뺀 글자 수(코드 포인트). 서버도 같은 기준으로 센다 */
    fun length(message: String): Int = message.trim().let { it.codePointCount(0, it.length) }

    fun counter(message: String): String = "${length(message)}/$MAX"

    /** 글 칸 아래 오류. 아직 쓰는 중(짧음)은 한 번 보내려 한 뒤([tried])에만 말한다. 넘친 것은 바로 말한다 */
    fun messageError(message: String, tried: Boolean): String? = when {
        length(message) > MAX -> TOO_LONG
        tried && length(message) < MIN -> TOO_SHORT
        else -> null
    }

    /** 이메일 칸 아래 오류. 비었거나 틀린 것은 한 번 보내려 한 뒤에만 말한다 */
    fun emailError(email: String, tried: Boolean): String? = when {
        !tried -> null
        email.isBlank() -> EMAIL_REQUIRED
        !EmailRule.isValid(email.trim()) -> EMAIL_INVALID
        else -> null
    }

    fun canSend(message: String, email: String): Boolean =
        length(message) in MIN..MAX && email.isNotBlank() && EmailRule.isValid(email.trim())

    /** 응답 코드 → 결과. null 이면 서버에 닿지 못했다. 404 · 405 · 501 은 서버에 아직 API 가 없다 */
    fun outcome(status: Int?): Outcome = when (status) {
        null -> Outcome.OFFLINE
        in 200..299 -> Outcome.DONE
        404, 405, 501 -> Outcome.UNSUPPORTED
        429 -> Outcome.LIMITED
        else -> Outcome.FAILED
    }

    fun message(outcome: Outcome): String = when (outcome) {
        Outcome.DONE -> DONE
        Outcome.UNSUPPORTED -> UNSUPPORTED
        Outcome.LIMITED -> LIMITED
        Outcome.FAILED -> FAILED
        Outcome.OFFLINE -> OFFLINE
    }
}
