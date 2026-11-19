// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 화면에 보이는 시각 표기는 여기 한 곳에서 만든다(11-18 사용자: 12시간제 · 24시간제 · 영어 AM/PM 이 섞여 있었다).
 *
 * 앱의 표기는 **"오전 8:13" / "오후 5:50"** 한 가지다. 시는 12시간제(0시 → "오전 12:00", 12시 → "오후 12:00"),
 * 분은 두 자리. 기기 설정이 24시간제여도 이 표기로 간다.
 *
 * 데이터 안에서는 서버 · 기기 알람이 쓰는 "AM"/"PM" 코드를 그대로 들고 다닐 수 있다. 화면에 낼 때 [meridiemOf] 로 바꾼다.
 */
object ClockText {

    /** "오전" / "오후" */
    fun meridiem(hour24: Int): String = if (hour24 < 12) "오전" else "오후"

    /** "AM"/"PM" 코드 → "오전"/"오후". 모르면 null */
    fun meridiemOf(code: String?): String? = when (code?.trim()?.uppercase()) {
        "AM" -> "오전"
        "PM" -> "오후"
        else -> null
    }

    /** 오전/오후 없이 12시간제 숫자만. "8:05", "12:00". 큰 숫자 옆에 오전/오후를 따로 둘 때 쓴다 */
    fun clock(hour24: Int, minute: Int): String {
        val h = (hour24 % 12).let { if (it == 0) 12 else it }
        return "$h:${"%02d".format(minute)}"
    }

    fun clock(time: LocalTime): String = clock(time.hour, time.minute)

    /** "오전 8:05" */
    fun label(hour24: Int, minute: Int): String = "${meridiem(hour24)} ${clock(hour24, minute)}"

    fun label(time: LocalTime): String = label(time.hour, time.minute)

    fun label(time: ZonedDateTime): String = label(time.hour, time.minute)

    /** 하루의 분(0~1439, 넘으면 하루로 접는다) → "오전 7:40" */
    fun label(minutesOfDay: Int): String {
        val m = ((minutesOfDay % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        return label(m / 60, m % 60)
    }

    fun label(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        label(Instant.ofEpochMilli(millis).atZone(zone))

    /**
     * 시각 문자열 + "AM"/"PM" 코드 → "오후 5:50". 시각이 12시간제("5:50")든 24시간제("17:50")든 같은 결과다.
     * 읽을 수 없으면 null.
     */
    fun label(clock: String?, code: String?): String? = split(clock, code)?.let { (word, digits) -> "$word $digits" }

    /**
     * 큰 숫자 옆에 오전/오후를 따로 그릴 때. ("오후", "5:50"). 기기에 예전 표기("17:50" + "PM")로 저장된 알람도 같은 결과다.
     * 읽을 수 없으면 null.
     */
    fun split(clock: String?, code: String?): Pair<String, String>? {
        val parts = clock?.trim()?.split(':') ?: return null
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        val hour24 = when (code?.trim()?.uppercase()) {
            "AM" -> if (hour == 12) 0 else hour
            "PM" -> if (hour >= 12) hour else hour + 12
            else -> hour
        }
        if (hour24 !in 0..23) return null
        return meridiem(hour24) to clock(hour24, minute)
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
