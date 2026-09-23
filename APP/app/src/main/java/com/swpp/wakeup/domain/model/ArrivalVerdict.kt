package com.swpp.wakeup.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 도착이 약속 시각보다 이른지 늦은지.
 *
 * ## 무엇과 비교하는가
 *
 * **약속 시각([appointmentMillis]) 과 비교한다.** 계획이 내놓은 "8:50 도착
 * 예정"([AlarmSchedule.arriveAtMillis]) 이 아니다. 이 구분이 중요하다 —
 * 9시 수업에 8시 55분에 닿은 사람은 늦지 않았다. 계획 추정치로 재면 그 사람에게
 * "5분 늦었습니다" 라고 말하게 되는데, 그건 사실이 아니고 앱을 믿지 못하게 만든다.
 *
 * 계획 추정치는 앱이 스스로 얼마나 잘 맞혔는지를 보는 값이라 성격이 다르다.
 * 그건 서버의 학습 쪽에서 쓴다. 사용자에게 보여 주는 판정은 **지각했는지**다.
 *
 * ## 안드로이드에 의존하지 않는다
 *
 * [com.swpp.wakeup.sensing.TripGeofence] 와 같은 이유다. 이 앱에서 알람과
 * 위치는 손으로 확인하기 가장 어려운 부분이고, 도착 판정은 아침에 딱 한 번
 * 일어난다. 실기기에서 우연히 맞기를 기다릴 수 없으므로 계산은 단위 테스트로
 * 고정한다.
 */
data class ArrivalVerdict(
    /** 도착 판정에 쓴 fix 의 시각. */
    val arrivedAtMillis: Long,
    /** 약속 시각 = 일정 시작 시각. */
    val appointmentMillis: Long,
) {

    enum class Status {
        /** 약속보다 먼저 닿았다. */
        EARLY,

        /** 분 단위로 약속 시각과 같다. */
        ON_TIME,

        /** 약속보다 늦게 닿았다. */
        LATE,
    }

    /**
     * 약속 시각까지 남은 분. 양수면 일찍, 음수면 늦게.
     *
     * 분 단위로 반올림한다. 초를 보여 줄 이유가 없고, 도착 판정 반경이 50m 라
     * 애초에 초 단위 정밀도가 없다.
     */
    val marginMinutes: Int
        get() = Math.round((appointmentMillis - arrivedAtMillis) / 60_000.0).toInt()

    val status: Status
        get() = when {
            marginMinutes > 0 -> Status.EARLY
            marginMinutes < 0 -> Status.LATE
            else -> Status.ON_TIME
        }

    /**
     * 알림 제목. 사용자가 가장 먼저 읽는 한 줄이다.
     *
     * "0분 일찍" 같은 말을 만들지 않는다 — 반올림해서 0이면 정시다.
     */
    val headline: String
        get() = when (status) {
            Status.EARLY -> "${formatSpan(marginMinutes)} 일찍 도착했습니다"
            Status.LATE -> "${formatSpan(-marginMinutes)} 늦게 도착했습니다"
            Status.ON_TIME -> "정시에 도착했습니다"
        }

    /**
     * 근거 한 줄. "9:25 도착 · 약속 9:30"
     *
     * 제목만 있으면 사용자가 판정을 검산할 수 없다. 두 시각을 함께 보여 주면
     * 앱이 엉뚱한 시각을 잡았을 때 사용자가 바로 알아챈다.
     */
    fun detail(zone: ZoneId = ZoneId.systemDefault()): String =
        "${formatClock(arrivedAtMillis, zone)} 도착 · 약속 ${formatClock(appointmentMillis, zone)}"

    private fun formatClock(millis: Long, zone: ZoneId): String =
        CLOCK.format(Instant.ofEpochMilli(millis).atZone(zone))

    companion object {
        /** 앱의 다른 곳과 같은 모양. "9:05", "14:30" */
        private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")

        /**
         * 분을 사람이 읽는 길이로.
         *
         * 한 시간을 넘기면 "95분" 보다 "1시간 35분" 이 빠르게 읽힌다. 추적
         * 마감이 일정 시작 후 한 시간이라 늦은 쪽은 여기까지 가기 어렵지만,
         * 일찍 도착하는 쪽은 충분히 넘는다.
         */
        fun formatSpan(minutes: Int): String {
            if (minutes < 60) return "${minutes}분"
            val hours = minutes / 60
            val rest = minutes % 60
            return if (rest == 0) "${hours}시간" else "${hours}시간 ${rest}분"
        }
    }
}
