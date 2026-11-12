// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.domain.model.EventSection
import com.swpp.wakeup.domain.model.UpcomingEvent
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 홈 다시 그림(iter4 H2-2, Figma `540:123`)에 쓰는 글자. 순수 함수만 둔다(JVM 테스트 대상).
 *
 * 시각 문자열은 저장소가 만든 값([UpcomingEvent.startClock] "9:00AM", [UpcomingEvent.alarmClock] "7:40",
 * [UpcomingEvent.alarmMeridiem] "AM")을 받아 모양만 바꾼다. 시간대 계산은 여기서 하지 않는다.
 */
internal object HomeLabels {

    private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

    /** 저장소가 알람을 계산했다는 [UpcomingEvent.planStatus] 값 */
    private const val PLAN_STATUS_OK = "ok"

    /** 머리. 예 "9월 17일 목요일" */
    fun headerDate(today: LocalDate): String =
        "${today.monthValue}월 ${today.dayOfMonth}일 ${DAY_NAMES[today.dayOfWeek.value - 1]}요일"

    /**
     * 묶음 머리 오른쪽. "오늘" 은 "일정 2개", "내일" 은 "9월 18일 금 · 일정 2개".
     * 머리가 이미 날짜("9월 20일 일")면 날짜를 두 번 쓰지 않는다.
     */
    fun sectionSummary(section: EventSection): String {
        val count = "일정 ${section.events.size}개"
        return if (section.label == "오늘" || section.label == section.dateLabel) count
        else "${section.dateLabel} · $count"
    }

    /** 카드 머리 "다음 알람 · 오늘" 의 뒷말. 알람이 울리는 날로 센다. */
    fun alarmDayWord(alarmDate: LocalDate, today: LocalDate): String =
        when (ChronoUnit.DAYS.between(today, alarmDate)) {
            0L -> "오늘"
            1L -> "내일"
            else -> "${alarmDate.monthValue}월 ${alarmDate.dayOfMonth}일 ${DAY_NAMES[alarmDate.dayOfWeek.value - 1]}"
        }

    /**
     * 알람까지 남은 시간. 시계의 분으로 센다 — 6:12:40 에 7:40 알람이면 "1시간 28분 뒤"(화면의 두 시각을
     * 빼서 나오는 값과 같다). 하루가 넘으면 분은 버린다("1일 3시간 뒤"). 같은 분이거나 지났으면 "곧".
     */
    fun untilLabel(alarmAtMillis: Long, nowMillis: Long): String {
        val minutes = Math.floorDiv(alarmAtMillis, MINUTE_MILLIS) - Math.floorDiv(nowMillis, MINUTE_MILLIS)
        if (minutes <= 0) return "곧"
        val days = minutes / (24 * 60)
        val hours = minutes % (24 * 60) / 60
        val mins = minutes % 60
        val parts = buildList {
            if (days > 0) add("${days}일")
            if (hours > 0) add("${hours}시간")
            if (days == 0L && mins > 0) add("${mins}분")
        }
        return parts.joinToString(" ") + " 뒤"
    }

    /** "AM"/"PM" → "오전"/"오후". 모르면 null */
    fun koreanMeridiem(ampm: String?): String? = when (ampm) {
        "AM" -> "오전"
        "PM" -> "오후"
        else -> null
    }

    /** "9:00AM" → ("9:00", "AM"). 붙은 말이 없으면("09:00") 두 번째가 null */
    fun splitClock(clock: String): Pair<String, String?> = when {
        clock.endsWith("AM") -> clock.removeSuffix("AM").trim() to "AM"
        clock.endsWith("PM") -> clock.removeSuffix("PM").trim() to "PM"
        else -> clock.trim() to null
    }

    /** 일정 줄 왼쪽의 시작 시각. "9:00AM" → "9:00 AM" */
    fun rowStartClock(startClock: String): String {
        val (clock, ampm) = splitClock(startClock)
        return if (ampm == null) clock else "$clock $ampm"
    }

    /**
     * 카드 안의 시작 시각. 큰 알람 시각과 같은 오전/오후면 숫자만("9:00"), 다르면 앞에 붙인다("오후 3:30").
     * Figma 는 "9:00" 만 쓰지만 오후 일정에 숫자만 쓰면 아침 알람 아래에서 오전으로 읽힌다.
     */
    fun heroStartClock(startClock: String, alarmMeridiem: String?): String {
        val (clock, ampm) = splitClock(startClock)
        val word = koreanMeridiem(ampm)
        return if (word == null || ampm == alarmMeridiem) clock else "$word $clock"
    }

    /** 카드 셋째 줄. "1시간 28분 뒤 · 9:00 일정" */
    fun heroWhenLine(until: String, startClock: String): String = "$until · $startClock 일정"

    /** 카드 넷째 줄. "302동 105호 · 9:00 자료구조 및 알고리즘". 장소가 없으면 시각과 제목만 */
    fun heroPlaceLine(placeName: String?, startClock: String, title: String): String =
        listOfNotNull(placeName, "$startClock $title").joinToString(" · ")

    /**
     * 일정 줄 오른쪽 위. 켠 알람은 시각("7:40"), 끈 알람은 "꺼짐". 안 간 일정은 "안 감",
     * 알람을 계산하지 못한 일정은 "알람 없음"(이유는 줄을 눌러 ④ 에서 본다).
     */
    fun rowAlarmLabel(event: UpcomingEvent): String = when {
        event.skipped -> "안 감"
        !event.hasAlarm || event.planStatus != PLAN_STATUS_OK -> "알람 없음"
        event.alarmOn -> event.alarmClock ?: event.alarmAt.orEmpty()
        else -> "꺼짐"
    }

    private const val MINUTE_MILLIS = 60_000L
}
