// AI-generated (Claude)
package com.swpp.wakeup.domain.model


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

    // Partly AI-generated (Claude)
    /**
     * 휠의 분 간격. 세 칸(서버가 정한 시각)이나 지금 알람이 5분 단위가 아니면 1분 간격으로 연다 —
     * 5분 휠에 7:43 을 올리면 7:40 으로 보이고, 그대로 [맞추기] 하면 다른 시각이 저장된다.
     */
    fun wheelStep(minutesOfDay: List<Int?>): Int =
        if (minutesOfDay.filterNotNull().all { it % MINUTE_STEP == 0 }) MINUTE_STEP else 1

    fun minutesOfDay(hour24: Int, minute: Int): Int =
        ((hour24 * 60 + minute) % MINUTES_PER_DAY + MINUTES_PER_DAY) % MINUTES_PER_DAY

    /** 0~1439 분 → "오전 7:50" */
    fun clockLabel(minutesOfDay: Int): String = ClockText.label(minutesOfDay)

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

    // Partly AI-generated (Claude)
    /**
     * 알람 [alarmMinutes] 부터 일정 시작 [startMinutes] 까지 쓸 수 있는 분.
     *
     * 시작이 알람보다 이르게 보이면 두 경우다(시트는 시각만 알고 날짜는 모른다):
     * - 알람이 시작보다 [LATE_WINDOW_MINUTES](12시간) 안쪽으로 늦다 → **시작 뒤에 일어나는 알람**이다. 음수를 그대로
     *   돌려준다(11-18 사용자: 9:00 일정에 9:30 알람이 "90%" 로 보였다 — 예전에는 하루를 더해 23시간 반으로 셌다).
     * - 그보다 더 늦다 → 전날 밤에 울리는 알람이다(하루를 더한다). 예: 9:00 일정에 23:00 알람 = 10시간.
     */
    fun availableMinutes(alarmMinutes: Int, startMinutes: Int): Int {
        val raw = startMinutes - alarmMinutes
        return when {
            raw >= 0 -> raw
            -raw < LATE_WINDOW_MINUTES -> raw
            else -> raw + MINUTES_PER_DAY
        }
    }

    /** 이만큼 안쪽으로 시작보다 늦은 알람은 "같은 날 늦게 일어난 것" 으로 본다 */
    const val LATE_WINDOW_MINUTES = 12 * 60

    // AI-generated (Claude) — 11-19 세 화면 확률 통일
    /**
     * 시트가 휠로 고른 시각 [picked] 에 보일 정시 도착 확률. **서버가 준 값만** 쓴다(11-18 사용자: 앱이 숫자를 만들지 않는다).
     *
     * - 시작 시각이 됐거나 지나서 일어나면 0 이다. 계산이 아니라 정시 도착이 불가능하다는 사실이다(저장도 막는다).
     * - 지금 알람 시각([current])이면 서버가 그 알람에 준 확률([currentProbability], 홈 카드 · 알람 화면과 같은 값).
     * - 서버의 세 칸 중 하나와 같은 시각이면 그 칸의 확률([cellProbability]).
     * - 그 밖의 시각은 서버의 1분 단위 표([table], `probability_table.whole`, BE-H3c)에서 그 칸을 그대로 읽는다.
     *   `i` = 일정 시작까지 남은 분, 표보다 길면 마지막 칸. 서버가 같은 식으로 만든 값이라 저장 뒤 확률과 같다.
     * - 표가 없으면(구버전 서버 0.21.0) null — 화면은 확률 줄을 비운다. 앱이 분포로 계산하지 않는다.
     */
    fun shownProbability(
        picked: Int,
        current: Int?,
        currentProbability: Int?,
        cellProbability: Int?,
        startMinutes: Int?,
        table: List<Int>? = null,
    ): Int? {
        val before = startMinutes?.let { availableMinutes(picked, it) }
        if (before != null && before <= 0) return 0
        if (current != null && picked == current && currentProbability != null) return currentProbability
        if (cellProbability != null) return cellProbability
        if (before == null || table.isNullOrEmpty()) return null
        return table[minOf(before, table.lastIndex)]
    }
}
