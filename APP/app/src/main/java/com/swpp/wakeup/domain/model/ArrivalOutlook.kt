package com.swpp.wakeup.domain.model

import kotlin.math.roundToLong

/**
 * 약속 시각보다 얼마나 늦게 도착할 것 같은가.
 *
 * ## 색이 이것을 나타낸다
 *
 * 진행 바의 색은 단계(준비 중·이동 중)가 아니라 **이 판정**이다. 단계는 글씨로
 * 읽으면 되지만, "지각하겠는가" 는 한눈에 보여야 하는 값이다. 색을 단계에 쓰면
 * 가장 급한 정보가 색을 잃는다.
 *
 * ## 기준은 약속 시각이다
 *
 * `도착 예정` 이 아니다. 도착 예정은 `약속 − 안전 버퍼` 라 이미 버퍼를 뺀 값이고,
 * 그것을 기준으로 지각을 재면 **버퍼를 두 번 쓴다.** 실기기에서 이렇게 나왔다.
 *
 * ```
 * 약속 11:57 · 도착 예정 11:47 · 예상 도착 11:51  →  "+4분" 노랑
 * ```
 *
 * 11:51 에 도착하면 약속보다 6분 이르다. 지각이 아니다. 버퍼는 "이만큼 일찍
 * 도착하자" 는 목표이고 지각은 "약속에 늦었는가" 다 — 다른 질문이다.
 *
 * 앱의 도착 판정 알림([ArrivalVerdict])은 처음부터 약속 시각 기준이었다. 진행
 * 바만 다른 기준을 쓰고 있었고, 이제 둘이 같은 말을 한다.
 */
data class ArrivalOutlook(
    /** 약속보다 늦는 분. 음수면 일찍 도착한다 */
    val deltaMinutes: Long,
    /** 예상 도착 시각(epoch ms) */
    val predictedMillis: Long,
    val verdict: Verdict,
) {
    enum class Verdict {
        /** 약속 시각 안에 도착한다 */
        ON_TIME,

        /** 늦지만 10분 미만이다 */
        TIGHT,

        /** 10분 이상 늦는다 */
        LATE,
    }

    /** "정시" / "+5분" / "+1시간 6분" */
    val label: String
        get() = when {
            deltaMinutes <= 0L -> "정시"
            deltaMinutes < 60L -> "+${deltaMinutes}분"
            deltaMinutes % 60L == 0L -> "+${deltaMinutes / 60}시간"
            else -> "+${deltaMinutes / 60}시간 ${deltaMinutes % 60}분"
        }

    companion object {
        /**
         * 이만큼 넘게 늦으면 빨강이다.
         *
         * **안전 버퍼를 쓰지 않는다.** 기준을 약속 시각으로 옮긴 순간 버퍼는
         * 경계에서 할 일이 없어졌다. 버퍼를 경계로 쓰면 서버가 버퍼 정책을 바꿀
         * 때 "지각" 의 뜻이 함께 흔들린다.
         */
        const val LATE_LIMIT_MINUTES = 10L

        /**
         * 지금 상태로 도착 전망을 낸다. 필요한 값이 없으면 null.
         *
         * null 이면 화면은 색을 쓰지 않는다. 지어낸 색은 지어낸 숫자보다 나쁘다 —
         * 숫자는 의심하지만 색은 그냥 믿는다.
         *
         * @param appointmentMillis 약속 시각(`Event.start_at`). 도착 예정이 아니다
         * @param remainingMinutes 지금부터 목적지까지 남은 분. [TripEta] 가 낸다
         */
        fun of(
            appointmentMillis: Long?,
            remainingMinutes: Double?,
            nowMillis: Long,
        ): ArrivalOutlook? {
            if (appointmentMillis == null || remainingMinutes == null) return null
            if (remainingMinutes < 0.0 || !remainingMinutes.isFinite()) return null
            val predicted = nowMillis + (remainingMinutes * 60_000L).roundToLong()
            return between(predicted, appointmentMillis)
        }

        /**
         * 이미 도착한 여정의 결과. [arrivedMillis] 는 도착을 판정한 시각이다.
         *
         * 예측과 따로 두는 이유는 도착한 뒤에는 **예측할 것이 없기** 때문이다.
         * 같은 함수로 계산하면 남은 거리 0 에 지금 시각을 넣게 되는데, 화면을
         * 늦게 열면 그만큼 더 늦게 도착한 것으로 나온다.
         */
        fun arrived(arrivedMillis: Long, appointmentMillis: Long?): ArrivalOutlook? {
            if (appointmentMillis == null) return null
            return between(arrivedMillis, appointmentMillis)
        }

        private fun between(actualMillis: Long, appointmentMillis: Long): ArrivalOutlook {
            // 초 단위를 분으로 내릴 때 버린다. 30초 늦은 것을 "+1분" 이라 하면
            // 약속을 지켰는데도 노란 바를 보게 된다.
            val delta = (actualMillis - appointmentMillis) / 60_000L
            return ArrivalOutlook(
                deltaMinutes = delta,
                predictedMillis = actualMillis,
                verdict = when {
                    delta <= 0L -> Verdict.ON_TIME
                    delta < LATE_LIMIT_MINUTES -> Verdict.TIGHT
                    else -> Verdict.LATE
                },
            )
        }
    }
}
