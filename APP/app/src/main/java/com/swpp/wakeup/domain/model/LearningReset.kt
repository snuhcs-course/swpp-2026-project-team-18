// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

/**
 * 내 정보 › 계정 › "기록 초기화"(iter4, 백엔드 요청 BE-RESET, 계약 `docs/iter4/learning-reset-api.md`).
 *
 * 지우는 것은 **쓰면서 쌓인 기록**이다: 출발 · 도착 기록, 아침(알람 · 준비) 기록, 다시 잡은 경로 기록과 거기서
 * 배운 값(내 준비 시간 · 이동 시간 보정). 그래서 주간 리포트의 지난 결과도 함께 비워진다. 사용자가 **직접 넣은
 * 것**(일정 · 집 주소 · 준비 시간 · 아침 루틴 · 계정)은 그대로다.
 *
 * 기록은 서버에 있어 서버가 받아야 지워진다. 화면 문구와 응답 → 안내 문구를 여기 모아 JVM 테스트로 고정한다.
 */
object LearningReset {

    const val ROW_TITLE = "기록 초기화"
    const val ROW_VALUE = "쌓인 출발 · 도착 · 아침 기록을 지우고 처음부터 다시 맞춰요"

    const val DIALOG_TITLE = "쌓인 기록을 모두 지울까요?"
    const val DIALOG_ERASED = "출발 · 도착 기록, 아침 기록과 거기서 맞춰진 준비 · 이동 시간이 지워져요. 주간 리포트의 지난 결과도 비워져요."
    const val DIALOG_KEPT = "일정, 집 주소, 준비 시간, 아침 루틴, 계정은 그대로예요."
    const val DIALOG_WARNING = "지운 기록은 되돌릴 수 없어요."
    const val CONFIRM = "모두 지우기"
    const val CONFIRM_BUSY = "지우는 중…"
    const val CANCEL = "취소"

    const val DONE = "기록을 모두 지웠어요. 알람은 처음 기준으로 다시 맞춰요"
    const val UNSUPPORTED = "아직 서버에서 지원하지 않아요. 기록은 그대로예요"
    const val FAILED = "기록을 지우지 못했어요. 잠시 후 다시 시도해 주세요"
    const val OFFLINE = "서버에 연결되지 않아 기록을 지우지 못했어요"
    const val SESSION = "로그인이 끝나 기록을 지우지 못했어요. 다시 로그인해 주세요"

    enum class Outcome { DONE, UNSUPPORTED, SESSION, FAILED, OFFLINE }

    /**
     * 응답 코드 → 결과. [status] 가 null 이면 서버에 닿지 못한 것이다.
     * 404 · 405 · 501 은 서버에 아직 이 API 가 없다는 뜻이다(배포 전).
     */
    fun outcome(status: Int?): Outcome = when (status) {
        null -> Outcome.OFFLINE
        in 200..299 -> Outcome.DONE
        404, 405, 501 -> Outcome.UNSUPPORTED
        401 -> Outcome.SESSION
        else -> Outcome.FAILED
    }

    fun message(outcome: Outcome): String = when (outcome) {
        Outcome.DONE -> DONE
        Outcome.UNSUPPORTED -> UNSUPPORTED
        Outcome.SESSION -> SESSION
        Outcome.FAILED -> FAILED
        Outcome.OFFLINE -> OFFLINE
    }
}
