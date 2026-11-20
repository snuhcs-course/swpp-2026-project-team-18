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
    const val OFFLINE = "인터넷에 연결되지 않아 문의를 보내지 못했어요"

    enum class Outcome { DONE, UNSUPPORTED, LIMITED, FAILED, OFFLINE }

    // Partly AI-generated (Claude)
    /**
     * 시트를 어디서 열었나(PM A-7). 시트는 하나다 — 도움말 [문의하기] 는 [CONTACT], 내 정보 "문제 신고" 는
     * [PROBLEM_REPORT]. 신고면 보내는 글 첫 줄 앞에 [REPORT_PREFIX] 를 붙인다(앱 버전 · 기기는 원래 함께 간다).
     */
    enum class Source { CONTACT, PROBLEM_REPORT }

    const val REPORT_PREFIX = "[문제 신고]"
    const val REPORT_TITLE = "문제 신고"
    const val REPORT_SUBTITLE = "어떤 화면에서 무엇이 잘못됐는지 적어 주세요. 답은 이메일로 보내 드려요."
    const val REPORT_ROW_VALUE = "앱 버전과 기기 종류가 함께 전달돼요"

    fun title(source: Source): String = if (source == Source.PROBLEM_REPORT) REPORT_TITLE else TITLE
    fun subtitle(source: Source): String = if (source == Source.PROBLEM_REPORT) REPORT_SUBTITLE else SUBTITLE

    /** 앞에 붙는 "[문제 신고] " 까지 서버 1000자에 들어가야 한다. 신고는 그만큼 덜 쓸 수 있다 */
    fun maxFor(source: Source): Int =
        if (source == Source.PROBLEM_REPORT) MAX - length("$REPORT_PREFIX ") - 1 else MAX

    /** 서버로 보낼 글. 신고면 첫 줄 앞에 "[문제 신고] " */
    fun bodyFor(message: String, source: Source): String {
        val text = message.trim()
        return if (source == Source.PROBLEM_REPORT) "$REPORT_PREFIX $text" else text
    }

    /** 요청 본문. 글은 [bodyFor] 를 거친다 — 신고 표시가 붙는 곳은 여기 한 곳이다 */
    fun requestOf(
        message: String,
        email: String,
        source: Source,
        appVersion: String,
        device: String,
    ): com.swpp.wakeup.data.remote.InquiryRequest = com.swpp.wakeup.data.remote.InquiryRequest(
        message = bodyFor(message, source),
        replyEmail = email.trim(),
        appVersion = appVersion,
        device = device,
    )

    /** 앞뒤 공백을 뺀 글자 수(코드 포인트). 서버도 같은 기준으로 센다 */
    fun length(message: String): Int = message.trim().let { it.codePointCount(0, it.length) }

    fun counter(message: String, source: Source = Source.CONTACT): String = "${length(message)}/${maxFor(source)}"

    /** 글 칸 아래 오류. 아직 쓰는 중(짧음)은 한 번 보내려 한 뒤([tried])에만 말한다. 넘친 것은 바로 말한다 */
    fun messageError(message: String, tried: Boolean, source: Source = Source.CONTACT): String? = when {
        length(message) > maxFor(source) -> if (source == Source.CONTACT) TOO_LONG else "${maxFor(source)}자까지 쓸 수 있어요"
        tried && length(message) < MIN -> TOO_SHORT
        else -> null
    }

    /** 답 받을 이메일 최대 길이(RFC 5321 경로 길이). 서버 계약 `server-inquiry.md` 와 같다 */
    const val REPLY_EMAIL_MAX = 254

    // Partly AI-generated (Claude)
    /**
     * 답 받을 이메일 검사(C5, 서버 계약 `server-inquiry.md` 5절). 로그인 · 가입의 [EmailRule] 위에 더 좁힌다:
     * - 254자까지(코드 포인트, 서버 `len` 과 같다). [EmailRule] 은 계정 이메일용이라 320자까지 받는다.
     * - 제어 문자 · 서식 문자(폭 없는 공백 등) · 공백이 하나라도 있으면 거절 — 메일 `Reply-To` 에 그대로 들어간다.
     * - 로컬 부분이 따옴표(`"..."@`)면 거절 — 서버는 dot-atom 만 받는다.
     * 앞뒤 공백은 보내기 전에 자르므로([com.swpp.wakeup.ui.help.ContactSheet]) 여기서도 자르고 본다.
     */
    fun isReplyEmailValid(raw: String): Boolean {
        val value = raw.trim()
        if (value.codePointCount(0, value.length) > REPLY_EMAIL_MAX) return false
        val hidden = value.codePoints().anyMatch { cp ->
            val type = Character.getType(cp)
            type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt() || Character.isWhitespace(cp) ||
                Character.isSpaceChar(cp)
        }
        if (hidden) return false
        if (value.substringBeforeLast('@', "").startsWith('"')) return false
        return EmailRule.isValid(value)
    }

    /** 이메일 칸 아래 오류. 비었거나 틀린 것은 한 번 보내려 한 뒤에만 말한다 */
    fun emailError(email: String, tried: Boolean): String? = when {
        !tried -> null
        email.isBlank() -> EMAIL_REQUIRED
        !isReplyEmailValid(email) -> EMAIL_INVALID
        else -> null
    }

    fun canSend(message: String, email: String, source: Source = Source.CONTACT): Boolean =
        length(message) in MIN..maxFor(source) && email.isNotBlank() && isReplyEmailValid(email)

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
