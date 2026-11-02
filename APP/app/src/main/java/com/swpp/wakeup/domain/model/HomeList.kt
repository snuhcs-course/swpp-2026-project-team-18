package com.swpp.wakeup.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 홈 목록의 규칙. 순수 함수만 둔다(JVM 테스트 대상).
 *
 * 예전에는 저장소가 불러올 때 한 번만 "시작 시각이 지난 일정" 을 걸렀다. 홈을 다시
 * 불러오지 않으면 지난 일정이 앱을 다시 켤 때까지 남았다(todo §3). 이제 저장소는
 * 거르지 않은 목록을 들고, 화면은 지금 시각으로 이 규칙을 다시 돌린다.
 *
 * - 보이는 일정: 시작 전이거나, 시작 뒤 [OPEN_TRIP_MILLIS] 안이면서 도착이 확인되지 않은 것
 * - "다음 알람": 아직 울리지 않은 켜진 알람 중 가장 이른 것. 미룬 알람은 미룬 시각으로 본다
 * - 다시 거를 시각: 위 두 규칙의 답이 바뀌는 가장 이른 시각(최대 5분 뒤)
 */
object HomeList {

    /** 서버에 요청하는 범위의 시작 = 지금 − 5시간(팀 결정 10-26) */
    const val SERVER_LOOKBACK_MILLIS = 5 * 60 * 60 * 1000L

    /** 시작 뒤 목록에 남기는 시간. 추적 마감과 같은 값이다 */
    const val OPEN_TRIP_MILLIS = AlarmSchedule.TRACKING_GRACE_MILLIS

    /** 시계·시간대가 바뀌어도 이만큼 안에 바로잡힌다 */
    const val MAX_RECHECK_MILLIS = 5 * 60 * 1000L

    /** 타이머가 경계 직후에 깨도록 더하는 여유. 경계 비교가 `<` 라서 필요하다 */
    const val BOUNDARY_SLACK_MILLIS = 500L

    /** 지금 시각으로 그린 홈 목록. */
    data class View(
        val sections: List<EventSection>,
        val nextAlarm: UpcomingEvent?,
        val totalCount: Int,
        val unplannedCount: Int,
        val today: LocalDate,
        /** 이 화면이 다시 걸러져야 하는 가장 이른 시각(epoch ms) */
        val nextChangeMillis: Long,
        /** 행에 "안 감으로 표시" 를 내놓는 일정 id. 오늘까지의 일정 중 도착하지 않은 것 */
        val markable: Set<Long>,
    )

    // Partly AI-generated (Claude)
    /** 목록에 보이는가. 시작 전이면 늘 보이고, 시작 뒤에는 도착 전이고 마감 전일 때만. */
    fun isVisible(e: UpcomingEvent, nowMillis: Long, arrived: Set<Long>): Boolean =
        nowMillis < e.startAtMillis ||
            (nowMillis < e.startAtMillis + OPEN_TRIP_MILLIS && e.id !in arrived)

    // Partly AI-generated (Claude)
    /** 미룬 알람의 기기 등록. 서버 시각은 지났는데 기기가 더 늦게 다시 울릴 때만 준다. */
    fun snoozeOf(e: UpcomingEvent, nowMillis: Long, device: Map<Long, AlarmSchedule>): AlarmSchedule? {
        val server = e.alarmAtMillis ?: return null
        if (server > nowMillis) return null
        return device[e.id]?.takeIf { it.alarmAtMillis > nowMillis }
    }

    // Partly AI-generated (Claude)
    /** 실제로 울릴 시각. 미룬 알람이면 미룬 시각, 아니면 서버 시각(계산 못 했으면 null). */
    fun effectiveAlarmMillis(e: UpcomingEvent, nowMillis: Long, device: Map<Long, AlarmSchedule>): Long? =
        snoozeOf(e, nowMillis, device)?.alarmAtMillis ?: e.alarmAtMillis

    // Partly AI-generated (Claude)
    fun view(
        events: List<UpcomingEvent>,
        nowMillis: Long,
        arrived: Set<Long>,
        device: Map<Long, AlarmSchedule>,
        zone: ZoneId,
    ): View {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val visible = events
            .filter { isVisible(it, nowMillis, arrived) }
            .sortedBy { it.startAtEpochSecond }

        // 울린 알람은 "다음 알람" 이 아니다. 미뤄서 다시 울릴 알람만 남긴다.
        val candidates = visible
            .filter { it.alarmOn && !it.skipped }
            .mapNotNull { e ->
                effectiveAlarmMillis(e, nowMillis, device)?.takeIf { it > nowMillis }?.let { e to it }
            }
        val pick = candidates.minByOrNull { it.second }?.first
        val next = pick?.let { e ->
            snoozeOf(e, nowMillis, device)?.let { e.copy(alarmAt = it.alarmLabel) } ?: e
        }

        val boundaries = buildList {
            visible.forEach { e ->
                if (e.id in arrived) {
                    if (e.startAtMillis > nowMillis) add(e.startAtMillis)
                } else {
                    add(e.startAtMillis + OPEN_TRIP_MILLIS)
                }
            }
            candidates.forEach { add(it.second) }
            add(nextMidnightMillis(nowMillis, zone))
        }

        return View(
            sections = groupByDay(visible, today),
            nextAlarm = next,
            totalCount = visible.size,
            unplannedCount = visible.count { !it.hasAlarm },
            today = today,
            nextChangeMillis = boundaries.min(),
            markable = visible
                .filter { !it.skipped && !it.startDate.isAfter(today) && it.id !in arrived }
                .map { it.id }
                .toSet(),
        )
    }

    /** 다음 경계까지 기다릴 시간. 최대 5분, 경계 직후에 깨도록 여유를 더한다. */
    fun recheckDelayMillis(nextChangeMillis: Long, nowMillis: Long): Long =
        (nextChangeMillis - nowMillis).coerceIn(0L, MAX_RECHECK_MILLIS) + BOUNDARY_SLACK_MILLIS

    /** `GET /api/events?from=` 값. 예 "2026-11-01T03:00:00Z" */
    fun serverFrom(nowMillis: Long): String =
        Instant.ofEpochMilli(nowMillis - SERVER_LOOKBACK_MILLIS).truncatedTo(ChronoUnit.SECONDS).toString()

    private fun nextMidnightMillis(nowMillis: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().plusDays(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /** 날짜별로 묶고 오늘·내일에 이름을 붙인다. */
    private fun groupByDay(events: List<UpcomingEvent>, today: LocalDate): List<EventSection> {
        if (events.isEmpty()) return emptyList()
        return events
            .groupBy { it.startDate }
            .toSortedMap()
            .map { (date, list) ->
                val dateLabel = "${date.format(DATE_FORMAT)} ${DAY_NAMES[date.dayOfWeek.value - 1]}"
                val label = when (ChronoUnit.DAYS.between(today, date)) {
                    0L -> "오늘"
                    1L -> "내일"
                    else -> dateLabel
                }
                EventSection(label = label, dateLabel = dateLabel, events = list)
            }
    }

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M월 d일")
    private val DAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")
}

/** 화면 복귀 때 서버에서 다시 받을지. 순수 규칙이다. */
object HomeRefresh {

    /** 마지막 요청 시작에서 이만큼 지나야 다시 받는다 */
    const val RESUME_MIN_INTERVAL_MILLIS = 60_000L

    // Partly AI-generated (Claude)
    fun shouldRefreshOnResume(
        nowElapsed: Long,
        lastStartElapsed: Long?,
        inFlight: Boolean,
        mutating: Boolean,
    ): Boolean =
        !inFlight && !mutating &&
            (lastStartElapsed == null || nowElapsed - lastStartElapsed >= RESUME_MIN_INTERVAL_MILLIS)
}
