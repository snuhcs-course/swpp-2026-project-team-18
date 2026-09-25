package com.swpp.wakeup.domain.model

import kotlin.math.roundToLong

/**
 * 예정보다 얼마나 늦게 도착할 것 같은가.
 *
 * ## 색이 이것을 나타낸다
 *
 * 진행 바의 색은 단계(준비 중·이동 중)가 아니라 **이 판정**이다. 단계는 글씨로
 * 읽으면 되지만, "지각하겠는가" 는 한눈에 보여야 하는 값이다. 색을 단계에 쓰면
 * 가장 급한 정보가 색을 잃는다.
 *
 * ## 계산
 *
 * 속도 모델을 만들지 않는다. **계획한 속도로 남은 거리를 간다고 본다.**
 *
 * ```
 * 예상 도착 = 지금 + (1 − 진행률) × 이동 시간
 * 늦는 분   = 예상 도착 − 예정 도착
 *           = (지금 − 출발 예정) − 진행률 × 이동 시간
 * ```
 *
 * 그래서 늦는 원인이 **이미 흘려보낸 시간**으로 잡힌다. 출발이 20분 늦으면 아직
 * 한 걸음도 안 뗐어도 20분 늦는다고 말하고, 경로를 절반 왔으면 그만큼 되돌린다.
 *
 * 관측한 속도를 쓰지 않는 이유는 [TripLiveState] 가 마지막 위치 하나만 들고
 * 있어서다. 두 점으로 속도를 내면 신호가 튀는 도심에서 값이 요동친다. 계획
 * 속도는 틀릴 수 있지만 **틀리는 방향이 일정해서** 읽는 사람이 보정할 수 있다.
 *
 * ## 경계가 안전 버퍼인 이유
 *
 * 알람은 `도착 예정 = 약속 시각 − 버퍼` 로 잡혀 있다. 그래서 버퍼 안에서 늦는
 * 것은 **여유를 깎는** 것이고 약속에는 늦지 않는다. 버퍼를 넘기면 약속 시각
 * 자체를 넘긴다. 임의의 숫자 대신 이 구조를 그대로 경계로 쓴다.
 */
data class ArrivalOutlook(
    /** 예정보다 늦는 분. 음수면 일찍 도착한다 */
    val deltaMinutes: Long,
    /** 예상 도착 시각(epoch ms) */
    val predictedMillis: Long,
    val verdict: Verdict,
) {
    enum class Verdict {
        /** 예정 안에 도착한다 */
        ON_TIME,

        /** 늦지만 안전 버퍼 안이다. 약속 시각은 지킨다 */
        TIGHT,

        /** 버퍼를 넘긴다. 약속 시각에 늦는다 */
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
         * 지금 상태로 도착 전망을 낸다. 필요한 값이 없으면 null.
         *
         * null 이면 화면은 색을 쓰지 않는다. 지어낸 색은 지어낸 숫자보다 나쁘다 —
         * 숫자는 의심하지만 색은 그냥 믿는다.
         *
         * @param ratio 경로 진행률 0~1. 아직 안 떠났으면 0
         */
        fun of(
            departByMillis: Long?,
            arriveAtMillis: Long?,
            travelMinutes: Int?,
            bufferMinutes: Int?,
            ratio: Float,
            nowMillis: Long,
        ): ArrivalOutlook? {
            if (departByMillis == null || arriveAtMillis == null) return null
            if (travelMinutes == null || travelMinutes < 0) return null

            val remainingMinutes = travelMinutes * (1.0 - ratio.coerceIn(0f, 1f).toDouble())
            val predicted = nowMillis + (remainingMinutes * 60_000L).roundToLong()
            return between(predicted, arriveAtMillis, bufferMinutes)
        }

        /**
         * 이미 도착한 여정의 결과. [arrivedMillis] 는 도착을 판정한 시각이다.
         *
         * 예측과 따로 두는 이유는 도착한 뒤에는 **예측할 것이 없기** 때문이다.
         * 같은 함수로 계산하면 남은 거리 0 에 지금 시각을 넣게 되는데, 화면을
         * 늦게 열면 그만큼 더 늦게 도착한 것으로 나온다.
         */
        fun arrived(
            arrivedMillis: Long,
            arriveAtMillis: Long?,
            bufferMinutes: Int?,
        ): ArrivalOutlook? {
            if (arriveAtMillis == null) return null
            return between(arrivedMillis, arriveAtMillis, bufferMinutes)
        }

        private fun between(
            actualMillis: Long,
            arriveAtMillis: Long,
            bufferMinutes: Int?,
        ): ArrivalOutlook {
            // 초 단위를 분으로 내릴 때 버린다. 30초 늦은 것을 "+1분" 이라 하면
            // 정시인데도 노란 바를 보게 된다.
            val delta = (actualMillis - arriveAtMillis) / 60_000L
            // 버퍼를 모르면 0 으로 본다. 그러면 1분만 늦어도 빨강이 되는데,
            // 모르는 채로 "괜찮다" 고 하는 것보다 낫다.
            val buffer = (bufferMinutes ?: 0).coerceAtLeast(0)
            return ArrivalOutlook(
                deltaMinutes = delta,
                predictedMillis = actualMillis,
                verdict = when {
                    delta <= 0L -> Verdict.ON_TIME
                    // `도착 예정 = 약속 − 버퍼` 이므로 버퍼만큼 늦으면 약속
                    // 시각에 **딱** 닿는다. 그때까지는 약속을 지킨 것이다.
                    delta <= buffer -> Verdict.TIGHT
                    else -> Verdict.LATE
                },
            )
        }
    }
}
