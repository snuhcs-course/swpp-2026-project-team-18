// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.trip

import com.swpp.wakeup.domain.model.EventSection
import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.UpcomingEvent
import com.swpp.wakeup.ui.home.HomeLabels
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 이동 탭(iter4 H2-2, Figma `545:1080`)의 규칙. 순수 함수만 둔다(JVM 테스트 대상).
 *
 * ## 단계 시각은 임시 계산이다
 *
 * Figma 캡션대로 "출발 시각 + 구간 시간" 이다. 정류장별 시각은 서버가 도착 목표에 맞춰 줘야
 * 정확하다(task.md BE-11). 그 전까지는 카카오 구간 시간을 차례로 더한다. 기다리는 시간은 타는
 * 시각에 넣는다 — 정류장에 닿는 시각이 아니라 차에 오르는 시각을 쓴다.
 */
internal object TripTabLogic {

    /** 저장소가 알람을 계산했다는 [UpcomingEvent.planStatus] 값 */
    private const val PLAN_STATUS_OK = "ok"

    /**
     * 이동 탭이 보여 줄 일정. 홈 목록(지금 시각으로 거른 것)에서 **경로가 정해진** 가장 이른 일정이다.
     * 이미 시작했지만 아직 도착하지 않은 일정도 목록에 남아 있으므로 이동 중인 일정이 먼저 잡힌다.
     * 안 간 일정과 알람을 계산하지 못한 일정(집·장소·경로 없음)은 건너뛴다.
     */
    fun pick(sections: List<EventSection>): UpcomingEvent? =
        sections.asSequence()
            .flatMap { it.events.asSequence() }
            .filter { !it.skipped && it.hasAlarm && it.planStatus == PLAN_STATUS_OK }
            .minByOrNull { it.startAtEpochSecond }

    /** 빈 상태. [Kind.LATER] 는 오늘 갈 곳은 없고 다음 이동이 내일 이후에 있을 때다. */
    data class Empty(val kind: Kind, val title: String, val body: String, val action: String) {
        enum class Kind { LATER, NO_EVENTS, NO_ROUTE }
    }

    // Partly AI-generated (Claude)
    /**
     * 이동 카드를 그리지 않을 때의 문구(Figma 캡션 `545:1304` "빈 상태 제안"). 보여 줄 일정이 오늘이면 null.
     *
     * @param hasEvents 목록에 일정이 하나라도 있는가(경로가 없는 일정 포함)
     */
    fun empty(next: UpcomingEvent?, hasEvents: Boolean, today: LocalDate): Empty? = when {
        next != null && !next.startDate.isAfter(today) -> null
        next != null -> {
            val tomorrow = next.startDate == today.plusDays(1)
            val clock = koreanClock(next.startClock)
            Empty(
                kind = Empty.Kind.LATER,
                title = "오늘은 더 갈 곳이 없어요",
                body = if (tomorrow) {
                    "내일 첫 일정은 $clock ${next.title}${copula(next.title)}"
                } else {
                    "다음 일정은 ${HomeLabels.alarmDayWord(next.startDate, today)} $clock ${next.title}${copula(next.title)}"
                },
                action = if (tomorrow) "내일 가는 길 보기" else "가는 길 보기",
            )
        }
        hasEvents -> Empty(
            kind = Empty.Kind.NO_ROUTE,
            title = "가는 길을 정한 일정이 없어요",
            body = "일정에 장소를 넣으면 출발 시각과 가는 길을 여기서 보여 드려요",
            action = "일정 추가",
        )
        else -> Empty(
            kind = Empty.Kind.NO_EVENTS,
            title = "다가오는 일정이 없어요",
            body = "일정을 넣으면 출발 시각과 가는 길을 여기서 보여 드려요",
            action = "일정 추가",
        )
    }

    /** "10:00AM" → "오전 10:00". 붙은 말이 없으면 그대로 */
    fun koreanClock(startClock: String): String {
        val (clock, ampm) = HomeLabels.splitClock(startClock)
        return HomeLabels.koreanMeridiem(ampm)?.let { "$it $clock" } ?: clock
    }

    /** "…예요" / "…이에요". 받침이 있는 한글로 끝나면 "이에요" */
    fun copula(word: String): String {
        val last = word.trimEnd().lastOrNull() ?: return "예요"
        val hasFinal = last in '가'..'힣' && (last - '가') % 28 != 0
        return if (hasFinal) "이에요" else "예요"
    }

    /** "가는 길" 한 줄의 종류. 점의 모양과 색을 고른다. */
    enum class StepKind { START, BOARD, ALIGHT, END }

    /**
     * "가는 길" 한 줄. 출발 → (타기 → 내리기)… → 도착.
     *
     * @property line 타는 줄의 노선(칩·점 색). 타기 줄에만 있다
     * @property below 이 줄 점 아래로 이어지는 선의 구간. 걷는 구간이면 회색, 타는 구간이면 노선색, 마지막 줄은 null
     */
    data class Step(
        val kind: StepKind,
        val time: LocalTime?,
        val title: String,
        val sub: String,
        val line: RouteSegment? = null,
        val below: RouteSegment.Kind? = null,
    )

    // Partly AI-generated (Claude)
    /**
     * 구간 목록을 "가는 길" 줄로 편다(Figma `card 가는 길`).
     *
     * - 출발·내리기 줄의 아랫글은 다음 걷는 구간("걸어서 6분 · 신림역까지").
     * - 타기 줄의 아랫글은 지하철이면 "2정거장 · 7분", 버스면 "17분 · 서울대정문까지".
     * - 버스·지하철이 없으면(도보·자전거·자동차, 또는 아직 구간을 못 받았으면) 빈 목록이다. 화면은
     *   [simpleSteps] 로 출발·도착 두 줄을 그린다.
     *
     * @param originTitle "집에서 출발"
     * @param destination "302동 105호". 마지막 줄 "302동 105호 도착" 과 마지막 걷는 구간의 "…까지"
     * @param endSub 마지막 줄 아랫글. 예 "수업 시작 10분 전"([endSub])
     */
    fun steps(
        segments: List<RouteSegment>,
        departAt: LocalDateTime?,
        originTitle: String,
        destination: String,
        endSub: String,
    ): List<Step> {
        val transit = segments.filter { it.isTransit() && it.stops.isNotEmpty() }
        if (transit.isEmpty()) return emptyList()

        val rows = mutableListOf<Step>()
        var t = departAt
        // 마지막으로 그린 줄 다음에 걷는 시간. 다음 줄을 만들 때 앞 줄의 아랫글을 채운다.
        var walkSeconds = 0
        // 아랫글을 아직 못 정한 줄(출발·내리기). 다음 정류장을 알아야 "…까지" 를 쓸 수 있다.
        var pending: Int? = null

        // 걷는 시간이 없으면 출발·도착 쪽은 아랫글을 비우고, 내린 곳에서 바로 타면 "바로 갈아타기".
        // 점 사이 선은 끊지 않는다(회색).
        fun closePending(target: String, toEnd: Boolean) {
            val index = pending ?: return
            val row = rows[index]
            val sub = when {
                walkSeconds > 0 -> "걸어서 ${minutesOf(walkSeconds)}분 · ${target}까지"
                row.kind == StepKind.ALIGHT && !toEnd -> "바로 갈아타기"
                else -> ""
            }
            rows[index] = row.copy(sub = sub, below = RouteSegment.Kind.WALK)
            pending = null
            walkSeconds = 0
        }

        rows += Step(StepKind.START, t?.toLocalTime(), originTitle, sub = "")
        pending = 0

        segments.forEach { segment ->
            when {
                segment.isTransit() && segment.stops.isNotEmpty() -> {
                    val board = stopName(segment.stops.first(), segment)
                    val previous = rows.lastOrNull()
                    // 내린 곳 바로 옆 정류장이면 정류장 이름 대신 무엇을 찾아갈지 쓴다("버스 정류장까지").
                    val target = if (previous?.kind == StepKind.ALIGHT && sameStop(previous.title, board)) {
                        if (segment.kind == RouteSegment.Kind.BUS) "버스 정류장" else "지하철역"
                    } else {
                        board
                    }
                    closePending(target, toEnd = false)
                    rows += Step(
                        kind = StepKind.BOARD,
                        time = t?.toLocalTime(),
                        title = "${board}에서 타기",
                        sub = rideSub(segment),
                        line = segment,
                        below = segment.kind,
                    )
                    t = t?.plusSeconds(segment.seconds.toLong())
                    val alight = stopName(segment.stops.last(), segment)
                    rows += Step(StepKind.ALIGHT, t?.toLocalTime(), "${alight}에서 내리기", sub = "")
                    pending = rows.lastIndex
                }

                segment.kind == RouteSegment.Kind.WAIT -> t = t?.plusSeconds(segment.seconds.toLong())

                else -> {
                    walkSeconds += segment.seconds
                    t = t?.plusSeconds(segment.seconds.toLong())
                }
            }
        }
        closePending(destination, toEnd = true)
        rows += Step(StepKind.END, t?.toLocalTime(), "$destination 도착", endSub)
        return rows
    }

    /**
     * 정류장 구간이 없을 때의 두 줄. 출발("걸어서 25분 · 302동 105호까지") → 도착.
     *
     * @param routeKey 일정에 저장된 경로 key. 걷기·자전거·자동차를 가린다
     * @param travelMinutes 계획 이동 시간(분). 모르면 수단만 쓴다
     */
    fun simpleSteps(
        routeKey: String?,
        travelMinutes: Int?,
        departAt: LocalDateTime?,
        arriveAt: LocalDateTime?,
        originTitle: String,
        destination: String,
        endSub: String,
    ): List<Step> {
        val mode = when {
            routeKey == "walk" -> "걸어서"
            routeKey == "bicycle" -> "자전거로"
            routeKey == "car" || routeKey?.startsWith("car:") == true -> "차로"
            else -> "대중교통으로"
        }
        val below = when (mode) {
            "걸어서" -> RouteSegment.Kind.WALK
            "자전거로" -> RouteSegment.Kind.BICYCLE
            "차로" -> RouteSegment.Kind.CAR
            else -> RouteSegment.Kind.UNKNOWN
        }
        val sub = listOfNotNull(travelMinutes?.let { "$mode ${it}분" } ?: mode, "${destination}까지").joinToString(" · ")
        return listOf(
            Step(StepKind.START, departAt?.toLocalTime(), originTitle, sub, below = below),
            Step(StepKind.END, arriveAt?.toLocalTime(), "$destination 도착", endSub),
        )
    }

    /** "가는 길" 머리 오른쪽. "42분 · 환승 1번" / "42분 · 환승 없음" / 정류장 구간이 없으면 "25분" */
    fun summary(totalMinutes: Int?, segments: List<RouteSegment>?): String? {
        val rides = segments?.count { it.isTransit() && it.stops.isNotEmpty() } ?: 0
        val transfers = when {
            rides >= 2 -> "환승 ${rides - 1}번"
            rides == 1 -> "환승 없음"
            else -> null
        }
        return listOfNotNull(totalMinutes?.let { "${it}분" }, transfers).joinToString(" · ").ifEmpty { null }
    }

    /** 마지막 줄 아랫글. "수업 시작 10분 전". 종류가 없으면 "일정", 늦게 닿으면 "수업 시작 3분 뒤" */
    fun endSub(tag: String?, startAtMillis: Long?, arriveAtMillis: Long?): String {
        val what = tag?.takeIf { it.isNotBlank() } ?: "일정"
        if (startAtMillis == null || arriveAtMillis == null) return "$what 시작"
        val minutes = Math.floorDiv(startAtMillis, MINUTE) - Math.floorDiv(arriveAtMillis, MINUTE)
        return when {
            minutes > 0 -> "$what 시작 ${minutes}분 전"
            minutes == 0L -> "$what 시작 시각"
            else -> "$what 시작 ${-minutes}분 뒤"
        }
    }

    /** 카드 셋째 줄. "1시간 56분 뒤 · 8:50 도착 예정". 출발 시각이 지났으면 "지금 출발할 시각" */
    fun whenLine(departAtMillis: Long?, nowMillis: Long, arrivalLine: String?): String? {
        val until = departAtMillis?.let {
            if (Math.floorDiv(it, MINUTE) > Math.floorDiv(nowMillis, MINUTE)) {
                HomeLabels.untilLabel(it, nowMillis)
            } else {
                "지금 출발할 시각"
            }
        }
        return listOfNotNull(until, arrivalLine).joinToString(" · ").ifEmpty { null }
    }

    private fun rideSub(segment: RouteSegment): String {
        val minutes = "${segment.minutes}분"
        val alight = stopName(segment.stops.last(), segment)
        return when {
            segment.kind == RouteSegment.Kind.SUBWAY && segment.stops.size >= 2 ->
                "${segment.stops.size - 1}정거장 · $minutes"
            segment.stops.size >= 2 -> "$minutes · ${alight}까지"
            else -> minutes
        }
    }

    /** 지하철역은 "신림" 처럼 오기도 한다. "신림역" 으로 맞춘다. 버스 정류장은 그대로 */
    private fun stopName(raw: String, segment: RouteSegment): String {
        val name = raw.trim()
        return if (segment.kind == RouteSegment.Kind.SUBWAY && !name.endsWith("역")) "${name}역" else name
    }

    /** "서울대입구역에서 내리기" 와 "서울대입구역" 처럼 같은 곳인가. 역 접미사는 무시한다 */
    private fun sameStop(title: String, stop: String): Boolean {
        val a = title.removeSuffix("에서 내리기").removeSuffix("역").trim()
        val b = stop.removeSuffix("역").trim()
        return a == b
    }

    private fun minutesOf(seconds: Int): Int = maxOf(1, Math.round(seconds / 60.0).toInt())

    private fun RouteSegment.isTransit() =
        kind == RouteSegment.Kind.BUS || kind == RouteSegment.Kind.SUBWAY

    private const val MINUTE = 60_000L
}
