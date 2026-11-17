// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.AlarmPlanDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 알람 확인 시트(H2-8 `547:2095`)의 문구와 숫자 */
class AlarmConfirmTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private val today = LocalDate.of(2026, 9, 17)

    private fun plan(
        alarm: String? = "2026-09-18T08:25:00+09:00",
        depart: String? = "2026-09-18T08:53:00+09:00",
        arrive: String? = "2026-09-18T09:35:00+09:00",
        prep: Int? = 28,
        travel: Int? = 42,
        buffer: Int? = 25,
        probability: Int? = 98,
        status: String = "ok",
    ): AlarmPlanDto {
        val json = buildString {
            append("{\"status\":\"$status\"")
            alarm?.let { append(",\"alarm_at\":\"$it\"") }
            depart?.let { append(",\"depart_by\":\"$it\"") }
            arrive?.let { append(",\"arrive_at\":\"$it\"") }
            prep?.let { append(",\"prep_minutes\":$it") }
            travel?.let { append(",\"travel_minutes\":$it") }
            buffer?.let { append(",\"buffer_minutes\":$it") }
            probability?.let { append(",\"on_time_probability\":$it") }
            append("}")
        }
        return Gson().fromJson(json, AlarmPlanDto::class.java)
    }

    private fun build(
        p: AlarmPlanDto?,
        start: String = "2026-09-18T10:00:00+09:00",
        origin: String? = null,
        route: String? = null,
        tag: String? = null,
    ) = AlarmConfirmText.build(
        eventId = 7, title = "전공 발표", placeName = "302동 105호", startAt = start, plan = p,
        originLabel = origin, routeLabel = route, tagLabel = tag, today = today, zone = zone,
        nowMillis = now,
    )

    /** 9월 17일 오전 9시. 그림의 알람(내일 8:25)이 아직 오지 않은 때 */
    private val now = java.time.ZonedDateTime.of(2026, 9, 17, 9, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `그림과 같은 값이면 그림과 같은 문구`() {
        val v = requireNotNull(build(plan()))
        assertEquals("내일 오전 8:25에 깨워 드릴게요", v.headline)
        assertEquals("오전 8:25", v.alarmClock)
        assertEquals("오전 10:00 전공 발표 · 302동 105호", v.subline)
        assertEquals("내일 아침", v.cardTitle)
        assertEquals(98, v.probability)
        assertEquals(listOf("준비 28분", "이동 42분", "여유 25분"), v.segments.map { it.label })
        assertEquals(listOf("8:25", "8:53", "9:35", "10:00"), v.points.map { it.clock })
        assertEquals(listOf("기상", "출발", "도착", "시작"), v.points.map { it.name })
        assertEquals("집에서 출발 · 가장 빠른 길 · 여유 보통", v.footer)
    }

    @Test
    fun `구간 폭과 점 자리는 분에 비례한다`() {
        val v = requireNotNull(build(plan()))
        val f = AlarmConfirmText.fractions(v.segments)
        assertEquals(1f, f.sum(), 1e-4f)
        assertEquals(28f / 95, f[0], 1e-4f)
        assertEquals(listOf(0f, 28f / 95, 70f / 95, 1f).map { "%.4f".format(it) }, v.points.map { "%.4f".format(it.at) })
    }

    @Test
    fun `0분 구간은 그리지 않고 겹친 점은 하나로 합친다`() {
        val v = requireNotNull(build(plan(buffer = 0, arrive = "2026-09-18T10:00:00+09:00")))
        assertEquals(listOf("준비 28분", "이동 42분"), v.segments.map { it.label })
        assertEquals(listOf("기상", "출발", "도착·시작"), v.points.map { it.name })
        assertEquals(1f, v.points.last().at, 1e-4f)
        assertTrue(AlarmConfirmText.segments(0, 0, 0).isEmpty())
        assertTrue(AlarmConfirmText.fractions(emptyList()).isEmpty())
    }

    @Test
    fun `확률이 없으면 칩이 없다`() {
        assertNull(requireNotNull(build(plan(probability = null))).probability)
    }

    @Test
    fun `알람 시각이 이미 지났으면 시트를 띄우지 않는다`() {
        // 11-16 버그: 지난 알람에도 "깨워 드릴게요" 가 떴다
        assertNull(build(plan(alarm = "2026-09-17T08:30:00+09:00")))
    }

    @Test
    fun `직접 정한 시각이 있으면 그 시각으로 말한다`() {
        val json = """{"status":"ok","alarm_at":"2026-09-18T08:25:00+09:00",
            "effective_alarm_at":"2026-09-18T08:10:00+09:00","alarm_source":"user",
            "prep_minutes":28,"travel_minutes":42,"buffer_minutes":25}"""
        val v = requireNotNull(build(Gson().fromJson(json, AlarmPlanDto::class.java)))
        assertEquals("내일 오전 8:10에 깨워 드릴게요", v.headline)
    }

    @Test
    fun `알람 시각이 없거나 경로 실패면 시트를 띄우지 않는다`() {
        assertNull(build(plan(alarm = null)))
        assertNull(build(plan(status = AlarmPlanDto.STATUS_ROUTE_FAILED)))
        assertNull(build(null))
    }

    @Test
    fun `날짜 말은 오늘 내일 그 밖`() {
        assertEquals("오늘", AlarmConfirmText.dayWord(today, today))
        assertEquals("내일", AlarmConfirmText.dayWord(today.plusDays(1), today))
        assertEquals("9월 20일", AlarmConfirmText.dayWord(LocalDate.of(2026, 9, 20), today))
    }

    @Test
    fun `자정을 넘는 일정은 알람 날짜로 말한다`() {
        // 내일 0:30 시작 일정의 알람이 오늘 밤 11:10 이다
        val v = requireNotNull(
            build(
                plan(
                    alarm = "2026-09-17T23:10:00+09:00",
                    depart = "2026-09-17T23:38:00+09:00",
                    arrive = "2026-09-18T00:20:00+09:00",
                    prep = 28, travel = 42, buffer = 10,
                ),
                start = "2026-09-18T00:30:00+09:00",
            )
        )
        assertEquals("오늘 오후 11:10에 깨워 드릴게요", v.headline)
        assertEquals("오늘 밤", v.cardTitle)
        assertEquals(listOf("11:10", "11:38", "12:20", "12:30"), v.points.map { it.clock })
    }

    @Test
    fun `맨 아래 줄은 실제로 고른 값을 쓴다`() {
        assertEquals(
            "친구 집에서 출발 · 23분 · 도보 · 시험",
            AlarmConfirmText.footer("친구 집", "23분 · 도보", "시험"),
        )
        assertEquals("집에서 출발 · 가장 빠른 길 · 여유 보통", AlarmConfirmText.footer(null, " ", null))
    }
}
