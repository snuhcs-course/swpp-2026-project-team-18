// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.data.remote.ringProbability
import com.swpp.wakeup.data.remote.ringAt
import com.swpp.wakeup.data.remote.AlarmPlanDto
import com.swpp.wakeup.ui.help.OnTimeSheetText
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 알람 확인 시트(H2-8 `547:2095`)가 그리는 값. 새 일정을 만든 직후 한 번 보여 준다.
 *
 * @property headline "내일 오전 8:25에 깨워 드릴게요" — [alarmClock] 만 주황으로 칠한다
 * @property subline "오전 10:00 전공 발표 · 302동 105호"
 * @property cardTitle "내일 아침"
 * @property probability 정시 도착 확률(%). null 이면 칩을 그리지 않는다
 * @property segments 0분이 아닌 구간만(준비 · 이동 · 여유). 폭은 분에 비례한다
 * @property points 기상 · 출발 · 도착 · 시작. 시작 시각을 모르면 뺀다. 0분 구간으로 겹친 점은 하나로 합친다
 *   ("9:35 도착·시작")
 * @property footer "집에서 출발 · 가장 빠른 길 · 여유 보통"
 */
data class AlarmConfirmView(
    val eventId: Long,
    /** 울릴 시각(서버가 준 ISO 그대로). [이 시각으로 알람 맞추기] 가 `POST …/alarm/confirm` 에 싣는다 */
    val ringAt: String? = null,
    val dayWord: String,
    val alarmClock: String,
    val subline: String,
    val cardTitle: String,
    val probability: Int?,
    val tau: Double?,
    val segments: List<Segment>,
    val points: List<Point>,
    val footer: String,
    /** 만들기 응답의 계획이 알려 준 더 빠른 경로(`alt_route_*`). 없으면 카드가 없다 */
    val faster: com.swpp.wakeup.domain.model.FasterRouteOffer? = null,
    /** 일정에 저장한 경로 key. 더 빠른 경로로 바꾼 뒤 [되돌리기] 에 쓴다 */
    val routeKey: String? = null,
) {
    val headline: String get() = "$dayWord ${alarmClock}에 깨워 드릴게요"

    enum class Kind(val label: String) { PREP("준비"), TRAVEL("이동"), BUFFER("여유") }

    data class Segment(val kind: Kind, val minutes: Int) {
        val label: String get() = "${kind.label} ${minutes}분"
    }

    /**
     * @property clock "8:25" — 오전/오후 없이 짧게(Figma 의 점 아래 글자)
     * @property at 줄 위의 가로 위치(0 = 기상, 1 = 끝). 구간 분에 비례한다
     */
    data class Point(val clock: String, val name: String, val at: Float = 0f)
}

object AlarmConfirmText {

    /** 종류를 고르지 않았을 때 맨 아래 줄에 쓰는 말. 프로필 기본 여유다 */
    const val DEFAULT_TAG = "여유 보통"
    const val DEFAULT_ROUTE = "가장 빠른 길"

    private val MONTH_DAY = DateTimeFormatter.ofPattern("M월 d일")

    /**
     * 만들기 응답으로 시트 값을 만든다. 알람 시각이 없으면(`route_failed` 등) null — 시트를 띄우지 않고
     * 지금의 "알람 시각을 정하지 못했어요" 흐름을 탄다.
     */
    fun build(
        eventId: Long,
        title: String,
        placeName: String?,
        startAt: String,
        plan: AlarmPlanDto?,
        originLabel: String?,
        routeLabel: String?,
        tagLabel: String?,
        routeKey: String? = null,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        nowMillis: Long = System.currentTimeMillis(),
    ): AlarmConfirmView? {
        if (plan == null || plan.status == AlarmPlanDto.STATUS_ROUTE_FAILED) return null
        // 울릴 시각(직접 정한 시각이면 그것). 이미 지났으면 깨워 줄 수 없으니 시트를 띄우지 않는다(11-16).
        val alarm = parse(plan.ringAt, zone) ?: return null
        if (!alarm.toInstant().isAfter(java.time.Instant.ofEpochMilli(nowMillis))) return null
        val start = parse(startAt, zone)

        val prep = plan.prepMinutes?.coerceAtLeast(0) ?: 0
        val travel = plan.travelMinutes?.coerceAtLeast(0) ?: 0
        val buffer = plan.bufferMinutes?.coerceAtLeast(0) ?: 0
        val depart = parse(plan.departBy, zone) ?: alarm.plusMinutes(prep.toLong())
        val arrive = parse(plan.arriveAt, zone) ?: depart.plusMinutes(travel.toLong())

        val day = dayWord(alarm.toLocalDate(), today)
        val startLine = start?.let { OnTimeSheetText.clock(it.toInstant().toEpochMilli(), zone) }
        return AlarmConfirmView(
            eventId = eventId,
            ringAt = plan.ringAt,
            dayWord = day,
            alarmClock = OnTimeSheetText.clock(alarm.toInstant().toEpochMilli(), zone),
            subline = listOfNotNull(
                listOfNotNull(startLine, title.trim().takeIf { it.isNotEmpty() }).joinToString(" ").takeIf { it.isNotEmpty() },
                placeName?.trim()?.takeIf { it.isNotEmpty() && it != title.trim() },
            ).joinToString(" · "),
            cardTitle = "$day ${partOfDay(alarm.hour)}",
            probability = plan.ringProbability,
            tau = plan.tauUsed,
            segments = segments(prep, travel, if (start != null) buffer else 0),
            points = points(
                listOfNotNull(
                    AlarmConfirmView.Point(short(alarm), "기상"),
                    AlarmConfirmView.Point(short(depart), "출발"),
                    AlarmConfirmView.Point(short(arrive), "도착"),
                    start?.let { AlarmConfirmView.Point(short(it), "시작") },
                ),
                listOf(prep, travel, buffer),
            ),
            footer = footer(originLabel, routeLabel, tagLabel),
            // 만들기 응답만으로 판단한다 — 따로 경로를 다시 찾지 않고, 서버가 대안을 주지 않았으면 카드도 없다.
            faster = com.swpp.wakeup.domain.model.FasterRoute.offer(
                isComputed = plan.status == AlarmPlanDto.STATUS_OK,
                skipped = false,
                alarmOn = true,
                alarmAtMillis = alarm.toInstant().toEpochMilli(),
                nowMillis = nowMillis,
                routeKey = routeKey,
                altRouteKey = plan.altRouteKey,
                altLabel = plan.altRouteLabel,
                altFasterMinutes = plan.altFasterMinutes,
                currentTravelMinutes = plan.travelMinutes,
            ),
            routeKey = routeKey,
        )
    }

    /** 0분 구간은 뺀다 — 폭 0 인 조각은 보이지 않으면서 이름만 겹친다 */
    fun segments(prep: Int, travel: Int, buffer: Int): List<AlarmConfirmView.Segment> = listOf(
        AlarmConfirmView.Segment(AlarmConfirmView.Kind.PREP, prep),
        AlarmConfirmView.Segment(AlarmConfirmView.Kind.TRAVEL, travel),
        AlarmConfirmView.Segment(AlarmConfirmView.Kind.BUFFER, buffer),
    ).filter { it.minutes > 0 }

    /**
     * 점의 가로 위치를 구간 분에 비례해 매기고, 0분 구간으로 같은 자리에 선 점은 하나로 합친다.
     * 구간이 모두 0분이면 점을 고르게 놓는다.
     */
    fun points(raw: List<AlarmConfirmView.Point>, minutes: List<Int>): List<AlarmConfirmView.Point> {
        val gaps = minutes.take((raw.size - 1).coerceAtLeast(0)).map { it.coerceAtLeast(0) }
        val total = gaps.sum()
        val placed = raw.mapIndexed { i, p ->
            val at = when {
                raw.size <= 1 -> 0f
                total <= 0 -> i.toFloat() / (raw.size - 1)
                else -> gaps.take(i).sum().toFloat() / total
            }
            p.copy(at = at)
        }
        val merged = mutableListOf<AlarmConfirmView.Point>()
        for (p in placed) {
            val last = merged.lastOrNull()
            if (last != null && last.at == p.at && total > 0) {
                merged[merged.lastIndex] = last.copy(name = "${last.name}·${p.name}", clock = p.clock)
            } else {
                merged += p
            }
        }
        return merged
    }

    /** 구간 폭 비율(합 1). 구간이 없으면 빈 목록 */
    fun fractions(segments: List<AlarmConfirmView.Segment>): List<Float> {
        val total = segments.sumOf { it.minutes }
        if (total <= 0) return emptyList()
        return segments.map { it.minutes.toFloat() / total }
    }

    /** 알람 날짜를 오늘 기준으로: "오늘" · "내일" · 그 밖은 "9월 20일" */
    fun dayWord(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "오늘"
        today.plusDays(1) -> "내일"
        else -> date.format(MONTH_DAY)
    }

    /** 카드 제목의 때. 알람이 밤이면 "아침" 이 틀린 말이 된다 */
    fun partOfDay(hour: Int): String = when (hour) {
        in 4..11 -> "아침"
        in 12..17 -> "오후"
        else -> "밤"
    }

    /** 맨 아래 줄. 고르지 않은 칸은 빠른 추가의 기본값(집 · 가장 빠른 길 · 여유 보통)으로 적는다 */
    fun footer(originLabel: String?, routeLabel: String?, tagLabel: String?): String = listOf(
        "${originLabel?.trim()?.takeIf { it.isNotEmpty() } ?: "집"}에서 출발",
        routeLabel?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ROUTE,
        tagLabel?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TAG,
    ).joinToString(" · ")

    private fun short(time: ZonedDateTime): String {
        val hour = (time.hour % 12).let { if (it == 0) 12 else it }
        return "$hour:%02d".format(time.minute)
    }

    private fun parse(iso: String?, zone: ZoneId): ZonedDateTime? =
        iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone) }.getOrNull() }
}
