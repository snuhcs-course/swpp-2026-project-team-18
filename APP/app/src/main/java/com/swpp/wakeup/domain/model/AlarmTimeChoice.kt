// AI-generated (Claude)
package com.swpp.wakeup.domain.model

import kotlin.math.roundToInt

/**
 * 알람 시각 바꾸기 바텀시트(iter4 H2-3, Figma `546:1595`)의 계산과 문구.
 *
 * 화면이 없는 순수 함수만 둔다 — 단위 테스트로 묶어 두려는 것이다.
 */
object AlarmTimeChoice {
    /** 휠의 분 간격. 알람 시각은 5분 단위로만 고른다 */
    const val MINUTE_STEP = 5
    const val MINUTES_PER_DAY = 24 * 60

    /**
     * 빠르게 고르기 세 칸의 라벨. 서버 라벨("안전·보통·도박") 대신 쓴다 —
     * 사용자가 고르는 것은 "얼마나 여유를 둘까" 이지 위험의 이름이 아니다.
     * 서버가 모르는 key 를 주면 서버 라벨을 그대로 쓴다.
     */
    fun quickLabel(key: String, serverLabel: String): String = when (key) {
        RiskOptionView.KEY_SAFE -> "여유 있게"
        RiskOptionView.KEY_NORMAL -> "보통"
        RiskOptionView.KEY_GAMBLE -> "빠듯하게"
        else -> serverLabel
    }

    /** [AlarmPlanView.tauSourceLabel] 이 이것이면 사용자가 τ 를 직접 골랐다는 뜻이다 */
    const val MANUAL_TAU_SOURCE = "직접 고른 값"

    /**
     * "추천" 을 붙일 칸.
     *
     * 서버가 지금 적용 중인 칸([selectedKey])이 곧 일정 종류의 기본값이다 — 사용자가 직접
     * 고른 값이면([MANUAL_TAU_SOURCE]) 종류 기본값이 무엇이었는지 앱은 알 수 없어서 프로필
     * 기본값인 "보통" 에 붙인다.
     */
    fun recommendedKey(selectedKey: String?, tauSourceLabel: String?): String =
        if (selectedKey == null || tauSourceLabel == MANUAL_TAU_SOURCE) RiskOptionView.KEY_NORMAL
        else selectedKey

    /** "7:20" + "AM" → 0~1439 분. 읽지 못하면 null */
    fun minutesOfDay(clock: String, meridiem: String): Int? {
        val parts = clock.trim().split(":")
        if (parts.size != 2) return null
        val hour = parts[0].trim().toIntOrNull() ?: return null
        val minute = parts[1].trim().toIntOrNull() ?: return null
        if (minute !in 0..59) return null
        val hour24 = when (meridiem.trim().uppercase()) {
            "AM" -> if (hour == 12) 0 else hour
            "PM" -> hour % 12 + 12
            else -> hour
        }
        if (hour24 !in 0..23) return null
        return hour24 * 60 + minute
    }

    fun minutesOfDay(hour24: Int, minute: Int): Int =
        ((hour24 * 60 + minute) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY

    /** 0~1439 분 → "오전 7:50" */
    fun clockLabel(minutesOfDay: Int): String {
        val normalized = ((minutesOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val hour24 = normalized / 60
        val minute = normalized % 60
        val hour12 = (hour24 % 12).let { if (it == 0) 12 else it }
        return "${if (hour24 < 12) "오전" else "오후"} $hour12:${"%02d".format(minute)}"
    }

    /** 분 길이 → "10분" / "1시간 10분" */
    fun minutesLabel(minutes: Int): String {
        val total = if (minutes < 0) -minutes else minutes
        val hours = total / 60
        val rest = total % 60
        return when {
            hours == 0 -> "${rest}분"
            rest == 0 -> "${hours}시간"
            else -> "${hours}시간 ${rest}분"
        }
    }

    /**
     * 아래 버튼 문구. "오전 7:50으로 맞추기".
     *
     * 조사는 끝자리 숫자를 **읽은 소리**로 고른다. "오십" 은 받침이 있어 "으로" 지만 "오십오" 는
     * "로" 다. 5분 단위라 끝자리는 0 아니면 5 지만, 간격이 바뀌어도 맞게 두었다.
     */
    fun confirmLabel(clockLabel: String): String = "$clockLabel${digitJosaRo(clockLabel)} 맞추기"

    /** 받침이 있는 소리로 끝나는 숫자는 영(0) · 삼(3) · 육(6) 뿐이다. 일 · 칠 · 팔은 ㄹ 받침이라 "로" */
    private fun digitJosaRo(label: String): String =
        if (label.lastOrNull() in listOf('0', '3', '6')) "으로" else "로"

    /** 결과 줄 앞부분. 뒤에 확률만 색을 달리해 붙인다 */
    fun resultPrefix(clockLabel: String): String = "${clockLabel}에 일어나면 정시 도착 확률 "

    /** 확률을 낼 수 없을 때의 결과 줄 */
    fun resultUnknown(clockLabel: String): String = "${clockLabel}에 일어났을 때의 정시 도착 확률은 아직 계산할 수 없어요"

    /** 추천 시각과의 차이 한 줄. 추천을 모르면 null */
    fun gapLine(pickedMinutes: Int, recommendedMinutes: Int?): String? {
        if (recommendedMinutes == null) return null
        val diff = pickedMinutes - recommendedMinutes
        val recommended = clockLabel(recommendedMinutes)
        return when {
            diff == 0 -> "추천하는 시각이에요"
            diff > 0 -> "추천($recommended)보다 ${minutesLabel(diff)} 늦은 알람이에요"
            else -> "추천($recommended)보다 ${minutesLabel(diff)} 이른 알람이에요"
        }
    }

    /**
     * 알람 [alarmMinutes] 부터 일정 시작 [startMinutes] 까지 쓸 수 있는 분.
     * 시작이 알람보다 이르게 보이면 전날 밤에 울리는 알람이다(하루를 더한다).
     */
    fun availableMinutes(alarmMinutes: Int, startMinutes: Int): Int {
        val raw = startMinutes - alarmMinutes
        return if (raw >= 0) raw else raw + MINUTES_PER_DAY
    }

    /**
     * 서버가 준 총 소요시간 분포로 "[available] 분 안에 준비·이동을 마칠 확률"(%).
     *
     * 서버는 세 단계의 확률만 주고 그 사이 시각의 확률은 주지 않는다(백엔드 BE-H3c).
     * 그동안 휠로 고른 시각의 확률은 **같은 분포**에서 읽어 낸다 — 숫자를 지어내는 것이
     * 아니라 서버가 옵션 확률을 낼 때 쓴 분포의 누적값이다. 구간 안은 균등하다고 본다.
     * 분포가 없으면 null 이고, 화면은 확률 자리를 비운다.
     */
    fun estimate(distribution: DurationDistributionView?, available: Int): Int? {
        val bins = distribution?.bins?.filter { it.mass > 0 && it.maxMinutes > it.minMinutes }
        if (bins.isNullOrEmpty()) return null
        val total = bins.sumOf { it.mass }
        if (total <= 0.0) return null
        val below = bins.sumOf { bin ->
            when {
                available >= bin.maxMinutes -> bin.mass
                available <= bin.minMinutes -> 0.0
                else -> bin.mass * (available - bin.minMinutes) / (bin.maxMinutes - bin.minMinutes)
            }
        }
        return ((below / total) * 100).roundToInt().coerceIn(0, 100)
    }
}
