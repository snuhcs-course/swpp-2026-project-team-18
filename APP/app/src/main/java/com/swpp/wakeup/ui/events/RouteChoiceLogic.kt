// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.domain.model.RouteMode
import com.swpp.wakeup.domain.model.RouteOption
import com.swpp.wakeup.domain.model.RouteSegment
import java.time.LocalDateTime

/**
 * 경로 선택 화면(Figma 시안 11-18)의 판단 · 문구. 순수 함수만 둔다(JVM 테스트 대상).
 * 숫자는 모두 경로 후보 응답에서 온다 — 응답에 없는 값(요금 등)은 지어내지 않고 자리를 비운다.
 */
internal object RouteChoiceLogic {

    /** 대중교통 묶음 탭. 탄 수단으로 나눈다 */
    enum class Group(val label: String) {
        ALL("전체"),
        BUS("버스"),
        SUBWAY("지하철"),
        BUS_SUBWAY("버스+지하철"),
    }

    /** 탄 구간(버스 · 지하철)만 */
    fun rides(option: RouteOption): List<RouteSegment> =
        option.segments.items.filter { it.kind == RouteSegment.Kind.BUS || it.kind == RouteSegment.Kind.SUBWAY }

    /** 후보가 어느 묶음인가. 탄 구간이 없으면 null(묶음 탭에서는 "전체" 에만 보인다) */
    fun groupOf(option: RouteOption): Group? {
        val kinds = rides(option).map { it.kind }.toSet()
        return when {
            RouteSegment.Kind.BUS in kinds && RouteSegment.Kind.SUBWAY in kinds -> Group.BUS_SUBWAY
            RouteSegment.Kind.BUS in kinds -> Group.BUS
            RouteSegment.Kind.SUBWAY in kinds -> Group.SUBWAY
            else -> null
        }
    }

    /** 묶음 탭과 개수. "전체" 는 늘 맨 앞, 후보가 없는 묶음은 만들지 않는다 */
    fun groups(options: List<RouteOption>): List<Pair<Group, Int>> =
        listOf(Group.ALL to options.size) +
            listOf(Group.BUS, Group.SUBWAY, Group.BUS_SUBWAY)
                .map { g -> g to options.count { groupOf(it) == g } }
                .filter { it.second > 0 }

    fun filter(options: List<RouteOption>, group: Group): List<RouteOption> =
        if (group == Group.ALL) options else options.filter { groupOf(it) == group }

    /** 큰 소요 시간 오른쪽 설명. 환승이 있으면 "환승 1회", 없으면 탄 수단("버스" · "지하철"). 대중교통이 아니면 null */
    fun subtitle(option: RouteOption): String? {
        val r = rides(option)
        if (r.isEmpty()) return null
        if (r.size > 1) return "환승 ${r.size - 1}회"
        return if (r[0].kind == RouteSegment.Kind.BUS) "버스" else "지하철"
    }

    private val FARE = Regex("""([0-9][0-9,]*)\s*원""")

    /** 요금 "1,550원". 서버 요약 줄에 있을 때만(없으면 null — 지어내지 않는다) */
    fun fare(option: RouteOption): String? =
        FARE.find(option.detailLine)?.let { "${it.groupValues[1]}원" }

    /** "오전 8:08 - 8:50 · 1,550원". 도착 목표를 모르면 시각을 빼고, 요금이 없으면 요금을 뺀다 */
    fun timeLine(option: RouteOption, arriveBy: LocalDateTime?): String? {
        val time = departAt(arriveBy, option.minutes)?.let { d ->
            timeRangeLabel(d.toLocalTime(), d.plusMinutes(option.minutes.toLong()).toLocalTime())
        }
        return listOfNotNull(time, fare(option)).joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    /** 부제 "집 → 302동 105호 · 오전 9:00까지" */
    fun header(origin: String, destination: String, arriveBy: LocalDateTime?): String =
        listOfNotNull(
            "$origin → $destination",
            arriveBy?.let { "${com.swpp.wakeup.domain.model.AlarmTimeChoice.clockLabel(it.hour * 60 + it.minute)}까지" },
        ).joinToString(" · ")

    // ---------------------------------------------------------------- 구간 막대

    /** 막대 한 칸. [ride] 가 null 이면 도보 합계(앞) 또는 대기 합계(뒤) */
    data class BarCell(val kind: RouteSegment.Kind, val minutes: Int, val ride: RouteSegment? = null)

    private fun minutes(seconds: Int): Int = maxOf(1, Math.round(seconds / 60.0).toInt())

    /**
     * 시안의 막대: 도보 합계 · 탄 구간들(순서대로) · 대기 합계. 도보와 대기는 여러 번 나뉘어도 한 칸으로 모으고, 그 사실을
     * 막대 아래 한 줄([totalsNote])이 밝힌다.
     */
    fun barCells(option: RouteOption): List<BarCell> {
        val items = option.segments.items
        val walk = items.filter { it.kind == RouteSegment.Kind.WALK }.sumOf { it.seconds }
        val wait = items.filter { it.kind == RouteSegment.Kind.WAIT }.sumOf { it.seconds }
        return buildList {
            if (walk > 0) add(BarCell(RouteSegment.Kind.WALK, minutes(walk)))
            rides(option).forEach { add(BarCell(it.kind, minutes(it.seconds), it)) }
            if (wait > 0) add(BarCell(RouteSegment.Kind.WAIT, minutes(wait)))
        }
    }

    /** "도보 14분 · 대기 4분은 전체 합계". 둘 다 없으면 null */
    fun totalsNote(option: RouteOption): String? {
        val cells = barCells(option)
        val parts = listOfNotNull(
            cells.firstOrNull { it.kind == RouteSegment.Kind.WALK }?.let { "도보 ${it.minutes}분" },
            cells.firstOrNull { it.kind == RouteSegment.Kind.WAIT }?.let { "대기 ${it.minutes}분" },
        )
        return if (parts.isEmpty()) null else parts.joinToString(" · ") + "은 전체 합계"
    }

    // ---------------------------------------------------------------- 구간 목록

    /** 구간 목록 한 줄 */
    data class Step(val ride: RouteSegment, val name: String, val sub: String)

    /** 노선 이름. 버스는 종류를 앞에 붙인다("지선 5513"), 지하철은 노선명("2호선") */
    fun lineName(s: RouteSegment): String = when (s.kind) {
        RouteSegment.Kind.BUS -> listOf(s.busType, s.label).filter { it.isNotBlank() }.joinToString(" ")
        else -> s.lineName.ifBlank { s.label }
    }

    /** 탄 구간마다 한 줄. "지하철 · 7분 탑승" / 두 번째부터는 "버스로 환승 · 17분 탑승" */
    fun steps(option: RouteOption): List<Step> = rides(option).mapIndexed { i, s ->
        val word = if (s.kind == RouteSegment.Kind.BUS) "버스" else "지하철"
        val how = if (i == 0) word else "${word}로 환승"
        Step(s, lineName(s), "$how · ${minutes(s.seconds)}분 탑승")
    }

    /** 대중교통 탭인가 */
    fun isTransit(option: RouteOption): Boolean = option.travelMode == RouteMode.TRANSIT
}
