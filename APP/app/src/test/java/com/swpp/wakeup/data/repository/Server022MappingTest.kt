// AI-generated (Claude) — 서버 0.22.0(BE-H3c · LLM1 · PREF · 11 · H3f) 새 값을 앱이 그대로 읽는지, 구버전(0.21.0)이면 예전처럼인지(11-21)
package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.RiskOptionsDto
import com.swpp.wakeup.data.remote.RouteCandidateDto
import com.swpp.wakeup.domain.model.AlarmTimeChoice
import com.swpp.wakeup.ui.events.routeTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class Server022MappingTest {

    private val gson = Gson()
    private val seoul = ZoneId.of("Asia/Seoul")

    // --- a) BE-H3c 휠 확률 표 -----------------------------------------------------

    @Test
    fun `risk-options 의 1분 표를 읽고 구버전이면 비운다`() {
        val withTable = gson.fromJson(
            """{"options":[],"probability_table":{"unit":"minutes_before_start","whole":[0,1,5,40,87,99],"max_minutes":5}}""",
            RiskOptionsDto::class.java,
        ).toView(seoul)
        assertEquals(listOf(0, 1, 5, 40, 87, 99), withTable.probabilityByMinutesBefore)

        val old = gson.fromJson("""{"options":[]}""", RiskOptionsDto::class.java).toView(seoul)
        assertNull("0.21.0 은 표가 없다", old.probabilityByMinutesBefore)

        val unknownUnit = gson.fromJson(
            """{"options":[],"probability_table":{"unit":"seconds","whole":[1,2]}}""",
            RiskOptionsDto::class.java,
        ).toView(seoul)
        assertNull("모르는 단위는 읽지 않는다", unknownUnit.probabilityByMinutesBefore)
    }

    @Test
    fun `휠 시각 확률은 표의 칸 그대로 - 지금 알람과 세 칸이 먼저`() {
        val start = 9 * 60
        val table = listOf(0, 1, 5, 40, 87, 99)
        // 시작 3분 전 → table[3]
        assertEquals(40, AlarmTimeChoice.shownProbability(start - 3, null, null, null, start, table))
        // 표보다 길면 마지막 칸
        assertEquals(99, AlarmTimeChoice.shownProbability(start - 60, null, null, null, start, table))
        // 시작과 같거나 뒤면 0
        assertEquals(0, AlarmTimeChoice.shownProbability(start, null, null, null, start, table))
        // 지금 알람 · 세 칸이 있으면 그 값
        assertEquals(97, AlarmTimeChoice.shownProbability(start - 3, start - 3, 97, null, start, table))
        assertEquals(91, AlarmTimeChoice.shownProbability(start - 3, null, null, 91, start, table))
        // 표가 없으면(구버전) 비운다 — 앱이 계산하지 않는다
        assertNull(AlarmTimeChoice.shownProbability(start - 3, null, null, null, start, null))
    }

    // --- c) BE-PREF 근거 구분 -----------------------------------------------------

    @Test
    fun `같은 도착지 근거라도 같은 수단 대체면 지난번에 고른 경로라고 하지 않는다`() {
        assertEquals("지난번에 고른 경로", preferredCaptionOf("same_destination", "same_route"))
        assertEquals("지난번과 같은 수단", preferredCaptionOf("same_destination", "same_mode_fallback"))
        assertEquals("구버전 서버(구분 없음)는 예전처럼", "지난번에 고른 경로", preferredCaptionOf("same_destination", null))
        assertEquals("자주 쓰는 수단", preferredCaptionOf("recent_mode", null))
        assertNull(preferredCaptionOf(null, null))
    }

    // --- d) BE-11 경로 카드 시각 ---------------------------------------------------

    @Test
    fun `서버가 준 출발 도착 시각을 기기 시간대로 읽는다`() {
        val dto = gson.fromJson(
            """{"key":"transit:2호선","mode":"지하철","minutes":30,"distance_m":5000,"fare":1500,
               "depart_at":"2026-11-23T08:28:30+09:00","arrive_at":"2026-11-23T09:00:00+09:00"}""",
            RouteCandidateDto::class.java,
        )
        assertEquals("2026-11-23T08:28:30", localTimeOf(dto.departAt, seoul).toString())
        assertEquals("2026-11-23T09:00", localTimeOf(dto.arriveAt, seoul).toString())
        assertNull(localTimeOf(null, seoul))
        assertNull(localTimeOf("not a time", seoul))
    }

    @Test
    fun `카드 시각은 서버 값 - 없거나 일정 시각이 바뀌었으면 예전처럼`() {
        val arriveBy = LocalDateTime.of(2026, 11, 23, 9, 0)
        val serverDepart = LocalDateTime.of(2026, 11, 23, 8, 28, 30)

        assertEquals(
            serverDepart to arriveBy,
            routeTimes(serverDepart, arriveBy, arriveBy, minutes = 30),
        )
        // 구버전 서버: 일정 시작 − 소요 시간
        assertEquals(
            LocalDateTime.of(2026, 11, 23, 8, 30) to arriveBy,
            routeTimes(null, null, arriveBy, minutes = 30),
        )
        // 받은 뒤 일정 시각을 9:30 으로 바꿨으면 그 서버 값은 쓰지 않는다
        val moved = LocalDateTime.of(2026, 11, 23, 9, 30)
        assertEquals(
            LocalDateTime.of(2026, 11, 23, 9, 0) to moved,
            routeTimes(serverDepart, arriveBy, moved, minutes = 30),
        )
        assertNull(routeTimes(serverDepart, arriveBy, null, minutes = 30))
    }

    // --- e) BE-H3f 준비 시간 모자람 · 남음 -------------------------------------------

    @Test
    fun `직접 정한 시각은 서버의 준비 가능 분과 모자람 남음을 쓴다`() {
        assertEquals(13, preparedMinutes(planned = 28, effective = 13, userAlarm = true))
        assertEquals("추천 시각은 계획한 준비 시간", 28, preparedMinutes(planned = 28, effective = 13, userAlarm = false))
        assertEquals("구버전 서버", 28, preparedMinutes(planned = 28, effective = null, userAlarm = true))

        assertEquals("준비 시간이 15분 모자라요", prepMarginLabel(-15, userAlarm = true))
        assertEquals("준비 시간이 7분 남아요", prepMarginLabel(7, userAlarm = true))
        assertNull(prepMarginLabel(0, userAlarm = true))
        assertNull(prepMarginLabel(-15, userAlarm = false))
        assertNull(prepMarginLabel(null, userAlarm = true))
    }
}
