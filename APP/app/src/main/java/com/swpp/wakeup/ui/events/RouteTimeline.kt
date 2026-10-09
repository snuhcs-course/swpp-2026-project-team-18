// AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.RouteSegments
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 경로 카드의 출발·도착 시각과 고른 카드의 정류장 타임라인(Figma 13-b, node 232:265).
 *
 * ## 시각은 임시 계산이다
 *
 * 원래는 서버가 일정 시작 시각을 도착 목표로 받아 후보별 출발·도착 시각과
 * 정류장별 시각을 계산해 줘야 한다(task.md B-4 `arrive_by`). 그 전까지는 화면에
 * 보이도록 **일정 시작 시각 − 소요시간** 을 출발로, 구간 소요시간을 차례로 더해
 * 정류장 시각을 만든다. 카카오가 주는 소요시간이 배차를 반영하지 않은
 * 추정치라서 이 시각도 "대략 이때" 라는 뜻이다. B-4 가 생기면 서버 값으로 바꾼다.
 */
internal data class TimelineRow(
    val name: String,
    /** 이름 아래 작은 글자. "출발" / "승차" / "환승" / "하차" / "도착" */
    val sub: String,
    /** 노선 칩을 붙일 구간. 승차·환승 행에만 */
    val line: RouteSegment?,
    /** 이 지점에 닿는 시각. 계산할 수 없으면 null */
    val time: LocalTime?,
    /** 시각 아래 글자. "출발" / "○○ 도착" / "도착" */
    val caption: String,
    val kind: Kind,
) {
    enum class Kind { START, STOP, END }
}

/** 도착 목표 시각에 맞춘 출발 시각. 목표가 없으면 null. */
internal fun departAt(arriveBy: LocalDateTime?, minutes: Int): LocalDateTime? =
    arriveBy?.minusMinutes(minutes.toLong())

private val CLOCK = DateTimeFormatter.ofPattern("h:mm")

private fun meridiem(time: LocalTime) = if (time.hour < 12) "오전" else "오후"

/** "오전 8:31" */
internal fun clockLabel(time: LocalTime): String = "${meridiem(time)} ${time.format(CLOCK)}"

/** "오전 8:31 - 9:00", 오전·오후가 바뀌면 "오전 11:40 - 오후 12:09". */
internal fun timeRangeLabel(depart: LocalTime, arrive: LocalTime): String =
    if (meridiem(depart) == meridiem(arrive)) {
        "${clockLabel(depart)} - ${arrive.format(CLOCK)}"
    } else {
        "${clockLabel(depart)} - ${clockLabel(arrive)}"
    }

/**
 * 정류장 타임라인. 출발 → (승차 → 하차/환승)… → 도착.
 *
 * - 하차한 곳에서 바로 다음 차를 타면(이름이 같으면) 한 행으로 합쳐 "환승" 으로 쓴다.
 * - 승차 행의 시각은 **정류장에 닿는 시각**이다(차를 기다리는 시간 전).
 * - 버스·지하철이 없는 경로(도보·자전거·자동차)는 빈 목록이다.
 */
internal fun timelineRows(
    segments: RouteSegments,
    origin: String,
    destination: String,
    depart: LocalDateTime?,
): List<TimelineRow> {
    val items = segments.items
    if (items.none { it.isTransit() && it.stops.isNotEmpty() }) return emptyList()

    val rows = mutableListOf<TimelineRow>()
    var t = depart
    rows += TimelineRow(origin, "출발", null, t?.toLocalTime(), "출발", TimelineRow.Kind.START)

    // 대기 칸 앞에서 정류장에 닿는다. 그 시각을 승차 행에 쓴다.
    var arrivedAtStop: LocalDateTime? = null
    items.forEach { segment ->
        when {
            segment.kind == RouteSegment.Kind.WAIT -> {
                arrivedAtStop = t
                t = t?.plusSeconds(segment.seconds.toLong())
            }

            segment.isTransit() && segment.stops.isNotEmpty() -> {
                val board = segment.stops.first().trim()
                val boardTime = (arrivedAtStop ?: t)?.toLocalTime()
                arrivedAtStop = null
                val last = rows.lastOrNull()
                if (last != null && last.kind == TimelineRow.Kind.STOP && last.sub == "하차" &&
                    sameStop(last.name, board)
                ) {
                    // 내린 곳에서 바로 갈아탄다: 하차 행을 환승 행으로 바꾼다.
                    rows[rows.lastIndex] = last.copy(sub = "환승", line = segment)
                } else {
                    rows += TimelineRow(board, "승차", segment, boardTime, "$board 도착", TimelineRow.Kind.STOP)
                }
                t = t?.plusSeconds(segment.seconds.toLong())
                val alight = segment.stops.last().trim()
                if (segment.stops.size >= 2 && alight.isNotEmpty()) {
                    rows += TimelineRow(alight, "하차", null, t?.toLocalTime(), "$alight 도착", TimelineRow.Kind.STOP)
                }
            }

            else -> t = t?.plusSeconds(segment.seconds.toLong())
        }
    }

    rows += TimelineRow(destination, "도착", null, t?.toLocalTime(), "도착", TimelineRow.Kind.END)
    return rows
}

private fun RouteSegment.isTransit() =
    kind == RouteSegment.Kind.BUS || kind == RouteSegment.Kind.SUBWAY

/** "신림역" 과 "신림" 처럼 역 접미사만 다른 경우도 같은 곳으로 본다. */
private fun sameStop(a: String, b: String): Boolean =
    a.removeSuffix("역").trim() == b.removeSuffix("역").trim()
