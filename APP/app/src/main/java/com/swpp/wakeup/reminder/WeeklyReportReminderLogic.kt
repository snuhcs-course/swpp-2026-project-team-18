// Partly AI-generated (Claude)
package com.swpp.wakeup.reminder

import com.swpp.wakeup.domain.model.WeeklyReportView
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * 일요일 낮 12시 주간 리포트 알림(iter4 H2-2)의 시각과 문구. 안드로이드 의존이 없어 JVM 테스트로 고정한다
 * (`WeeklyReportReminderLogicTest`).
 *
 * 시각은 **기기 시간대**의 벽시계다. 주는 리포트와 같이 월~일이다.
 */
internal object WeeklyReportReminderLogic {

    /** 알림을 띄우는 시각(일요일) */
    val NOTIFY_AT: LocalTime = LocalTime.NOON

    /**
     * 이 시각까지 못 띄웠으면(기기가 꺼져 있었음 · 깊은 잠) 그 주는 건너뛴다. 저녁 늦게, 또는 월요일에 "이번 주
     * 리포트가 나왔어요" 를 받으면 어느 주인지 헷갈린다.
     */
    val CUTOFF: LocalTime = LocalTime.of(18, 0)

    /** 시스템이 알림 시각을 이만큼 늦출 수 있게 둔다(정확한 알람은 배터리를 쓴다) */
    const val WINDOW_MINUTES = 15L

    const val TITLE = "이번 주 리포트가 나왔어요"

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * 다음에 깨울 시각.
     *
     * - 이번 주 일요일 12:00 전이면 그 시각.
     * - 일요일 12:00 ~ 18:00 인데 이번 주 알림을 아직 안 띄웠으면 **지금**(앱을 그 사이에 열어 예약을 다시
     *   걸어도 오늘 것을 놓치지 않는다).
     * - 아니면 다음 주 일요일 12:00. 서머타임이 바뀌는 주에도 벽시계 12:00 이다.
     *
     * @param lastNotifiedWeek 마지막으로 처리한 주(월요일). 보내지 않고 건너뛴 주도 처리한 것이다
     */
    fun nextTrigger(now: ZonedDateTime, lastNotifiedWeek: LocalDate?): ZonedDateTime {
        val noon = ZonedDateTime.of(weekStart(now.toLocalDate()).plusDays(6), NOTIFY_AT, now.zone)
        return when {
            now.isBefore(noon) -> noon
            shouldNotify(now, lastNotifiedWeek) -> now
            else -> noon.plusWeeks(1)
        }
    }

    /** 지금 띄워도 되는가. 일요일 12:00 ~ 18:00 이고 이번 주를 아직 처리하지 않았을 때다 */
    fun shouldNotify(now: ZonedDateTime, lastNotifiedWeek: LocalDate?): Boolean {
        if (now.dayOfWeek != DayOfWeek.SUNDAY) return false
        val time = now.toLocalTime()
        if (time.isBefore(NOTIFY_AT) || !time.isBefore(CUTOFF)) return false
        return lastNotifiedWeek != weekStart(now.toLocalDate())
    }

    data class Message(val title: String, val text: String)

    /**
     * 알림 문구. [view] 가 null 이면 리포트를 받지 못한 것이다(네트워크 등) — 숫자 없이 보낸다.
     * 지난 일정이 하나도 없으면 볼 것이 없으므로 보내지 않는다(null).
     */
    fun message(view: WeeklyReportView?): Message? = when {
        view == null -> Message(TITLE, "이번 주 리포트를 확인해 보세요")
        view.arrivedCount > 0 -> Message(
            TITLE,
            "${view.arrivedCount}번 중 ${view.onTimeCount}번 제시간에 도착했어요 · 눌러서 자세히 보기",
        )
        view.eventCount > 0 -> Message(TITLE, "이번 주 도착 기록이 아직 없어요 · 눌러서 확인하기")
        else -> null
    }
}
