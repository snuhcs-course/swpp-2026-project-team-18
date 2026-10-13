// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.CalibrationDto
import com.swpp.wakeup.data.remote.WeeklyReportDto
import com.swpp.wakeup.domain.model.DayStatus
import com.swpp.wakeup.domain.model.OutcomeResult
import com.swpp.wakeup.domain.model.RowKind
import com.swpp.wakeup.domain.model.Tone
import com.swpp.wakeup.domain.model.WeeklyReportView
import com.swpp.wakeup.domain.model.halfUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 주간 리포트 응답 → ⑧·⑨ 화면 값.
 *
 * 설계 문서의 예시 주(2026-10-05 월 ~ 10-11 일: 일정 7 · 도착 기록 6 · 정시 5 ·
 * 화요일 10분 지각, 지난주 3/5)를 **실제 Gson** 으로 읽어 문구까지 고정한다.
 * 시각은 서울 시간대로 읽는다(기기 시간대를 주입).
 */
class ReportMappingTest {

    private val gson = Gson()
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun map(json: String): WeeklyReportView =
        gson.fromJson(json, WeeklyReportDto::class.java).toView(seoul)

    private val tuesday = """
        {"event": 412, "title": "자료구조", "start_at": "2026-10-06T00:00:00Z",
         "date": "2026-10-06", "weekday": 1, "result": "late", "slack_minutes": -10.0,
         "on_time_probability": 90, "tau": 0.9, "tau_bucket_lower": 0.9, "prep_applies": true,
         "alarm_on": true,
         "planned": {"alarm_at": "2026-10-05T22:40:00Z", "depart_by": "2026-10-05T23:08:00Z",
                     "arrive_at": "2026-10-05T23:50:00Z", "prep_minutes": 28, "travel_minutes": 42,
                     "buffer_minutes": 10},
         "actual": {"depart_at": "2026-10-05T23:15:00Z", "arrive_at": "2026-10-06T00:10:00Z",
                    "prep_minutes": 34.0, "travel_minutes": 55.0},
         "deviation": {"prep_over": 6.0, "depart_late": 7.0, "travel_over": 13.0},
         "cause": "travel_over", "unmeasured": []}
    """.trimIndent()

    private val friday = """
        {"event": 106, "title": "실험", "start_at": "2026-10-09T01:00:00Z",
         "date": "2026-10-09", "weekday": 4, "result": "unobserved", "slack_minutes": null,
         "on_time_probability": null, "tau": 0.9, "tau_bucket_lower": 0.9, "prep_applies": true,
         "alarm_on": true,
         "planned": {"alarm_at": "2026-10-08T23:35:00Z", "depart_by": "2026-10-09T00:15:00Z",
                     "arrive_at": "2026-10-09T00:50:00Z", "prep_minutes": 40, "travel_minutes": 35,
                     "buffer_minutes": 10},
         "actual": {"depart_at": null, "arrive_at": null, "prep_minutes": null,
                    "travel_minutes": null},
         "deviation": {"prep_over": null, "depart_late": null, "travel_over": null},
         "cause": null, "unmeasured": []}
    """.trimIndent()

    private fun onTime(id: Int, date: String, startUtc: String, slack: Double) = """
        {"event": $id, "title": "수업", "start_at": "$startUtc", "date": "$date",
         "result": "on_time", "slack_minutes": $slack, "alarm_on": true}
    """.trimIndent()

    private val example = """
        {"week_start": "2026-10-05", "week_end": "2026-10-11",
         "event_count": 7, "arrived_count": 6, "unobserved_count": 1, "on_time_count": 5,
         "late_count": 1, "on_time_rate": 0.8333, "median_slack_minutes": 9.0,
         "tightest_slack_minutes": -10.0,
         "week_complete": true, "has_next_week": true, "target_rate": 0.9, "target_met": false,
         "previous_week": {"week_start": "2026-09-28", "event_count": 5, "arrived_count": 5,
                           "on_time_count": 3, "on_time_rate": 0.6, "on_time_rate_change_pp": 23},
         "deviation": {"prep_over": {"count": 2, "mean_minutes": 4.0, "sd_minutes": 1.0},
                       "depart_late": {"count": 5, "mean_minutes": 3.0, "sd_minutes": 2.0},
                       "travel_over": {"count": 5, "mean_minutes": -1.0, "sd_minutes": 7.0}},
         "learning": {"plan_count": 7, "prep_from_records": 2, "travel_from_records": 3},
         "outcomes": [
           ${onTime(101, "2026-10-05", "2026-10-05T00:00:00Z", 12.0)},
           ${onTime(102, "2026-10-05", "2026-10-05T06:00:00Z", 15.0)},
           $tuesday,
           ${onTime(103, "2026-10-07", "2026-10-07T01:00:00Z", 3.0)},
           ${onTime(104, "2026-10-08", "2026-10-08T00:00:00Z", 9.0)},
           ${onTime(105, "2026-10-08", "2026-10-08T04:00:00Z", 9.0)},
           $friday
         ],
         "summary": "  이 주 일정 7건 중 도착을 기록한 6번에서 5번 정시 도착함.  ",
         "summary_source": "llm"}
    """.trimIndent()

    // --- ⑧ ------------------------------------------------------------------

    @Test
    fun `예시 주의 머리 타일과 요약`() {
        val view = map(example)

        assertEquals("10월 5일 ~ 10월 11일", view.rangeLabel)
        assertEquals(LocalDate.of(2026, 10, 5), view.weekStart)
        assertTrue(view.canGoNext)
        assertFalse(view.inProgress)

        assertEquals("이 주 일정 7건 중 도착을 기록한 6번에서 5번 정시 도착함.", view.summary?.text)
        assertTrue(view.summary!!.isAi)

        assertEquals("5/6", view.onTimeCountLabel)
        assertEquals(Tone.WARN, view.onTimeTone)
        assertEquals("정시 도착 83%", view.onTimeLabel)
        assertEquals("목표 90% · 지난주 3/5", view.onTimeSubLabel)

        assertEquals("9분 일찍", view.slackValueLabel)
        assertEquals(Tone.GOOD, view.slackTone)
        assertEquals("평소 도착 (중간값)", view.slackLabel)
        assertEquals("가장 늦은 날 10분 늦음", view.slackSubLabel)

        assertNull("7건 중 1건 미기록은 공백 안내 대상이 아니다", view.gapNotice)
        assertTrue(view.showTiles && view.showDays && view.showLateDays && view.showDeviations)
    }

    @Test
    fun `요일 일곱 칸은 date 로만 정한다`() {
        val days = map(example).days

        assertEquals(listOf("월", "화", "수", "목", "금", "토", "일"), days.map { it.label })
        assertEquals(
            listOf("+12", "\u221210", "+3", "+9", "—", "", ""),
            days.map { it.valueLabel },
        )
        assertEquals(
            listOf(
                DayStatus.ON_TIME, DayStatus.LATE, DayStatus.ON_TIME, DayStatus.ON_TIME,
                DayStatus.UNOBSERVED, DayStatus.NONE, DayStatus.NONE,
            ),
            days.map { it.status },
        )
        assertEquals(listOf(12, -10, 3, 9, null, null, null), days.map { it.slackMinutes })
        assertEquals("화요일, 10분 늦게 도착", days[1].description)
        assertEquals("월요일, 12분 일찍 도착", days[0].description)
        assertEquals("금요일, 도착 기록 없음", days[4].description)
        assertEquals("일요일, 일정 없음", days[6].description)
        // 대표 = 그날 가장 작은 여유. 월요일 12분·15분 중 12분.
        assertEquals(101L, days[0].representative?.eventId)
        assertEquals(2, days[0].outcomes.size)
        assertTrue(days[4].clickable)
        assertFalse(days[5].clickable)
        assertEquals("기록 6/7건", map(example).daysCoverageLabel)
    }

    @Test
    fun `지각한 날 줄과 계획 대비 실제 평균과 기록 반영`() {
        val view = map(example)

        val line = view.lateDays.single()
        assertEquals(412L, line.eventId)
        assertEquals(LocalDate.of(2026, 10, 6), line.date)
        assertEquals("화 10월 6일", line.dateLabel)
        assertEquals("10분 늦음", line.lateLabel)
        assertEquals("이동이 13분 더 걸렸음", line.causeTitle)
        assertEquals("화요일 10월 6일, 10분 늦음, 이동이 13분 더 걸렸음", line.description)
        assertFalse(view.hasUnknownCause)

        assertEquals(
            listOf(
                Triple("준비", "평균 4분 더 걸림 · 2회", Tone.WARN),
                Triple("출발", "평균 3분 늦게 나섬 · 5회", Tone.WARN),
                Triple("이동", "평균 1분 덜 걸림 · 5회", Tone.GOOD),
            ),
            view.deviations.map { Triple(it.label, it.valueLabel, it.tone) },
        )
        assertEquals(
            "이번 주 일정 7건의 계획 중 2건은 내 준비 기록으로, 3건은 이동 기록 보정으로 계산함",
            view.learningLabel,
        )
    }

    // --- ⑨ ------------------------------------------------------------------

    @Test
    fun `지각한 화요일 상세`() {
        val morning = map(example).days[1].outcomes.single()

        assertEquals(OutcomeResult.LATE, morning.result)
        assertEquals("10분 지각", morning.headline)
        assertNull(morning.headlineNote)
        assertEquals("09:00 자료구조", morning.chipLabel)
        assertEquals("09:00 자료구조 · 알람 7:40", morning.contextLine)
        assertEquals("앱이 본 정시 확률 90%", morning.confidenceLine)
        assertTrue("이 주 지각이 이것 하나뿐이다", morning.isOnlyLateOfWeek)
        assertEquals(0.9, morning.tauBucketLower!!, 1e-9)

        val (prep, depart, travel) = morning.rows
        assertEquals("준비", prep.label)
        assertEquals(RowKind.DURATION, prep.kind)
        assertEquals("+6분", prep.deltaLabel)
        assertEquals(Tone.BAD, prep.deltaTone)
        assertEquals("계획 28분", prep.plannedLabel)
        assertEquals("실제 34분", prep.actualLabel)
        assertEquals(28f / 34f, prep.plannedFraction!!, 1e-4f)
        assertEquals(1f, prep.actualFraction!!, 1e-4f)
        assertEquals(Tone.BAD, prep.actualTone)

        assertEquals("출발", depart.label)
        assertEquals(RowKind.TIME, depart.kind)
        assertEquals("계획 8:08", depart.plannedLabel)
        assertEquals("실제 8:15", depart.actualLabel)
        assertEquals("+7분", depart.deltaLabel)

        assertEquals("이동", travel.label)
        assertEquals("+13분", travel.deltaLabel)
        assertEquals(42f / 55f, travel.plannedFraction!!, 1e-4f)
        assertEquals(1f, travel.actualFraction!!, 1e-4f)

        val arrival = morning.arrival!!
        assertEquals("계획 8:50", arrival.plannedLabel)
        assertEquals("실제 9:10", arrival.actualLabel)
        assertEquals(Tone.BAD, arrival.actualTone)
        assertEquals("", arrival.deltaLabel)

        val cause = morning.cause!!
        assertEquals("이동이 13분 더 걸렸음", cause.title)
        assertEquals("준비·출발·이동 중 계획보다 가장 크게 늘어난 단계임", cause.body)
        assertEquals("다른 경로를 골라 보거나 몇 분 일찍 나서 볼 것", cause.action)
    }

    @Test
    fun `도착 기록이 없는 금요일 상세`() {
        val morning = map(example).days[4].outcomes.single()

        assertEquals(OutcomeResult.UNOBSERVED, morning.result)
        assertEquals("도착 기록 없음", morning.headline)
        assertEquals("도착을 기록하지 못해 채점하지 않았음", morning.headlineNote)
        assertEquals("10:00 실험 · 알람 8:35", morning.contextLine)
        assertEquals("적용 τ 0.90", morning.confidenceLine)
        assertNull(morning.cause)
        assertFalse(morning.isOnlyLateOfWeek)

        val (prep, depart, travel) = morning.rows
        assertEquals("계획 40분", prep.plannedLabel)
        assertNull(prep.actualLabel)
        assertNull("값이 없으면 막대를 그리지 않는다", prep.actualFraction)
        assertEquals(1f, prep.plannedFraction!!, 1e-4f)
        assertEquals("측정 안 됨", prep.deltaLabel)
        assertEquals(Tone.UNKNOWN, prep.deltaTone)
        assertEquals("계획 9:15", depart.plannedLabel)
        assertNull(depart.actualLabel)
        assertEquals("계획 35분", travel.plannedLabel)
        assertEquals("계획 9:50", morning.arrival!!.plannedLabel)
        assertNull(morning.arrival!!.actualLabel)
    }

    @Test
    fun `정시 도착 상세와 정각`() {
        val week = """
            {"week_start": "2026-10-05", "week_end": "2026-10-11", "event_count": 2,
             "arrived_count": 2, "on_time_count": 2, "late_count": 0, "outcomes": [
               {"event": 1, "title": "아주 긴 일정 제목입니다", "start_at": "2026-10-08T00:00:00Z",
                "date": "2026-10-08", "result": "on_time", "slack_minutes": 9.0,
                "on_time_probability": 91, "prep_applies": true, "alarm_on": true,
                "planned": {"alarm_at": "2026-10-07T22:42:00Z", "prep_minutes": 28,
                            "travel_minutes": 40, "depart_by": "2026-10-07T23:10:00Z"},
                "actual": {"prep_minutes": 30.0, "travel_minutes": 39.0,
                           "depart_at": "2026-10-07T23:12:00Z"},
                "deviation": {"prep_over": 2.0, "depart_late": 2.0, "travel_over": -1.0}},
               {"event": 2, "title": "정각", "start_at": "2026-10-09T00:00:00Z",
                "date": "2026-10-09", "result": "on_time", "slack_minutes": 0.3}
             ]}
        """.trimIndent()
        val days = map(week).days

        val thursday = days[3].outcomes.single()
        assertEquals("9분 전 도착", thursday.headline)
        assertEquals("09:00 아주 긴 일정…", thursday.chipLabel)
        val travel = thursday.rows[2]
        assertEquals("${MINUS}1분", travel.deltaLabel)
        assertEquals(Tone.GOOD, travel.deltaTone)
        assertEquals(Tone.GOOD, travel.actualTone)
        assertEquals(1f, travel.plannedFraction!!, 1e-4f)
        assertEquals(39f / 40f, travel.actualFraction!!, 1e-4f)

        assertEquals("정각 도착", days[4].outcomes.single().headline)
        assertEquals("0", days[4].valueLabel)
        assertEquals("금요일, 정각 도착", days[4].description)
    }

    // --- 규칙 ---------------------------------------------------------------

    @Test
    fun `반올림은 서버와 같다 - 절댓값 0점5 올림`() {
        assertEquals(63, halfUp(62.5))
        assertEquals(93, halfUp(92.5))
        assertEquals(-23, halfUp(-22.5))
        assertEquals(23, halfUp((0.6 - 0.375) * 100))
        assertEquals(63, percent(0.625))
        assertEquals(93, percent(0.925))

        val view = map(
            """{"week_start":"2026-10-05","week_end":"2026-10-11","event_count":8,
                "arrived_count":8,"on_time_count":5,"late_count":3,"on_time_rate":0.625,
                "target_rate":0.925,"median_slack_minutes":-0.4,"tightest_slack_minutes":-12.5}"""
        )
        assertEquals("정시 도착 63%", view.onTimeLabel)
        assertEquals("목표 93%", view.onTimeSubLabel)
        assertEquals("정각", view.slackValueLabel)
        assertEquals("가장 늦은 날 13분 늦음", view.slackSubLabel)
    }

    @Test
    fun `1분 안쪽 지각도 1분이다`() {
        val view = map(
            """{"week_start":"2026-10-05","week_end":"2026-10-11","event_count":1,
                "arrived_count":1,"late_count":1,"on_time_rate":0.0,
                "median_slack_minutes":-0.4,"tightest_slack_minutes":-0.4,
                "outcomes":[{"event":7,"title":"수업","start_at":"2026-10-05T00:00:00Z",
                  "date":"2026-10-05","result":"late","slack_minutes":-0.4,
                  "cause":"unknown","unmeasured":["prep_over","travel_over","depart_late"],
                  "planned":{"depart_by":"2026-10-04T23:00:00Z"}}]}"""
        )
        assertEquals("1분 늦음", view.lateDays.single().lateLabel)
        assertEquals("${MINUS}1", view.days[0].valueLabel)
        assertEquals("1분 지각", view.days[0].outcomes.single().headline)
        assertEquals("가장 늦은 날 1분 늦음", view.slackSubLabel)
        assertEquals("원인 측정 못 함", view.lateDays.single().causeTitle)
        assertTrue(view.hasUnknownCause)
    }

    @Test
    fun `주 밖의 날짜와 못 읽는 날짜는 칸에서 뺀다`() {
        val view = map(
            """{"week_start":"2026-10-05","week_end":"2026-10-11","event_count":3,
                "arrived_count":3,"late_count":3,"outcomes":[
                  {"event":1,"date":"2026-10-12","result":"late","slack_minutes":-5.0,"weekday":0},
                  {"event":2,"date":"10월 6일","result":"late","slack_minutes":-5.0},
                  {"event":3,"result":"late","slack_minutes":-5.0}]}"""
        )
        assertEquals(7, view.days.size)
        assertTrue(view.days.all { it.status == DayStatus.NONE })
        assertTrue(view.lateDays.isEmpty())
    }

    @Test
    fun `id 가 없는 지각은 줄을 만들지 않는다`() {
        val view = map(
            """{"week_start":"2026-10-05","week_end":"2026-10-11","event_count":1,
                "arrived_count":1,"late_count":1,"outcomes":[
                  {"date":"2026-10-05","result":"late","slack_minutes":-5.0}]}"""
        )
        assertTrue(view.lateDays.isEmpty())
        assertEquals(DayStatus.LATE, view.days[0].status)
    }

    @Test
    fun `구버전 서버 응답은 새 카드를 숨기고 타일은 보인다`() {
        val view = map(
            """{"week_start":"2026-10-05","week_end":"2026-10-11","event_count":3,
                "arrived_count":2,"unobserved_count":1,"on_time_count":2,"late_count":0,
                "on_time_rate":1.0,"median_slack_minutes":7.5,"tightest_slack_minutes":5.0,
                "by_weekday":[{"weekday":0,"total":1,"late":0}],"late_causes":[],
                "primary_counts":{},"calibration":{"buckets":[],"verdict":"insufficient"}}"""
        )
        assertNull(view.summary)
        assertTrue(view.days.isEmpty())
        assertTrue(view.lateDays.isEmpty())
        assertTrue(view.deviations.isEmpty())
        assertNull(view.learningLabel)
        assertTrue("구버전은 has_next_week 가 없다 — 갈 수 있다고 본다", view.canGoNext)
        assertFalse(view.inProgress)
        assertTrue(view.showTiles)
        assertFalse(view.showDays)
        assertEquals("2/2", view.onTimeCountLabel)
        assertEquals(Tone.NEUTRAL, view.onTimeTone)
        assertNull(view.onTimeSubLabel)
        assertEquals("8분 일찍", view.slackValueLabel)
        assertEquals("가장 아슬아슬 5분 전", view.slackSubLabel)
    }

    @Test
    fun `새 키가 전부 null 이거나 모르는 값이면 중립이다`() {
        val view = map(
            """{"week_start":"2026-10-05","week_end":"2026-10-11","event_count":2,
                "arrived_count":1,"unobserved_count":1,"late_count":1,
                "week_complete":null,"has_next_week":null,"target_rate":null,"target_met":null,
                "previous_week":null,"deviation":null,"learning":null,"summary":"   ",
                "summary_source":"gpt","outcomes":[
                  {"event":1,"date":"2026-10-05","result":"late","slack_minutes":-3.0,
                   "cause":"mystery"},
                  {"event":2,"date":"2026-10-06","result":"teleported"}]}"""
        )
        assertNull("빈 요약은 카드를 그리지 않는다", view.summary)
        assertTrue(view.canGoNext)
        assertNull(view.onTimeSubLabel)
        assertTrue(view.deviations.isEmpty())
        assertEquals("원인 측정 못 함", view.lateDays.single().causeTitle)
        assertEquals("원인을 나누지 못했음", view.days[0].outcomes.single().cause?.title)
        assertEquals(OutcomeResult.UNOBSERVED, view.days[1].outcomes.single().result)

        val ai = map("""{"week_start":"2026-10-05","summary":"요약","summary_source":"template"}""")
        assertFalse(ai.summary!!.isAi)
    }

    @Test
    fun `진행 중인 주는 다음 주로 가지 못한다`() {
        val view = map(
            """{"week_start":"2026-10-12","week_end":"2026-10-18","week_complete":false,
                "has_next_week":false,"summary":"진행 중인 주라 지금까지 기록만 요약함."}"""
        )
        assertTrue(view.inProgress)
        assertFalse(view.canGoNext)
        assertFalse("일정 0건이면 타일을 숨긴다(요약이 말한다)", view.showTiles)
    }

    @Test
    fun `측정 공백 안내는 알람이 없던 일정을 따로 센다`() {
        fun gap(rows: String, arrived: Int, unobserved: Int) = map(
            """{"week_start":"2026-10-05","week_end":"2026-10-11",
                "event_count":${arrived + unobserved},"arrived_count":$arrived,
                "unobserved_count":$unobserved,"outcomes":[$rows]}"""
        ).gapNotice

        val mixed = gap(
            """{"event":1,"date":"2026-10-05","result":"on_time","slack_minutes":5.0},
               {"event":2,"date":"2026-10-06","result":"unobserved","alarm_on":true},
               {"event":3,"date":"2026-10-07","result":"unobserved","alarm_on":false},
               {"event":4,"date":"2026-10-08","result":"unobserved","alarm_on":false}""",
            arrived = 1, unobserved = 3,
        )!!
        assertEquals("측정하지 못한 아침 1건", mixed.title)
        assertEquals("도착을 기록하지 못한 아침은 정시로 세지 않음. 위 비율은 1건만 반영한 값임", mixed.body)
        assertEquals("알람이 울리지 않은 일정 2건은 출발·도착을 기록하지 않음", mixed.caption)

        val noAlarms = gap(
            """{"event":2,"date":"2026-10-06","result":"unobserved","alarm_on":false},
               {"event":3,"date":"2026-10-07","result":"unobserved","alarm_on":false}""",
            arrived = 0, unobserved = 2,
        )!!
        assertEquals("알람이 울리지 않은 일정 2건", noAlarms.title)
        assertEquals("알람이 울리지 않은 일정은 출발·도착을 기록하지 않아 정시로 세지 않음", noAlarms.body)
        assertNull(noAlarms.caption)

        val legacy = map(
            """{"week_start":"2026-10-05","week_end":"2026-10-11","event_count":3,
                "arrived_count":1,"unobserved_count":2}"""
        ).gapNotice!!
        assertEquals("측정하지 못한 아침 2건", legacy.title)
    }

    @Test
    fun `기록 반영 문장`() {
        fun label(plan: Int, prep: Int, travel: Int) = map(
            """{"week_start":"2026-10-05","learning":{"plan_count":$plan,
                "prep_from_records":$prep,"travel_from_records":$travel}}"""
        ).learningLabel

        assertEquals(
            "이번 주 계획은 준비·이동 기록 없이 입력한 준비 시간과 경로 예상 시간으로 계산됨",
            label(3, 0, 0),
        )
        assertEquals("이번 주 일정 4건의 계획 중 2건은 이동 기록 보정으로 계산함", label(4, 0, 2))
        assertEquals("이번 주 일정 4건의 계획 중 1건은 내 준비 기록으로 계산함", label(4, 1, 0))
        assertNull("계획이 없던 주는 문장을 쓰지 않는다", label(0, 0, 0))
    }

    @Test
    fun `원인을 나누지 못한 지각은 실제 기록 방식대로 안내한다`() {
        fun cause(extra: String) = map(
            """{"week_start":"2026-10-05","late_count":1,"outcomes":[
                {"event":1,"date":"2026-10-05","result":"late","slack_minutes":-4.0,
                 "cause":"unknown",$extra}]}"""
        ).days[0].outcomes.single().cause!!

        val noPlan = cause(""""unmeasured":["prep_over","travel_over","depart_late"],"planned":{}""")
        assertEquals("그날은 알람 계획이 없어 계획과 비교하지 못했음", noPlan.body)
        assertEquals("집 주소와 일정 장소를 정해 두면 계획이 계산됨", noPlan.action)

        val notHome = cause(
            """"prep_applies":false,"unmeasured":["travel_over","depart_late"],
               "planned":{"depart_by":"2026-10-04T23:00:00Z"}"""
        )
        assertEquals("출발지가 집이 아닌 일정이라 출발 시각을 기록하지 않았음", notHome.body)
        assertNull(notHome.action)

        val noDeparture = cause(
            """"prep_applies":true,"unmeasured":["prep_over","travel_over","depart_late"],
               "planned":{"depart_by":"2026-10-04T23:00:00Z"}"""
        )
        assertEquals("원인을 나누지 못했음", noDeparture.title)
        assertEquals("집을 나선 시각이 기록되지 않아 출발·이동을 나눠 보지 못했음", noDeparture.body)
        assertEquals("집 안에서 알람을 해제하면 나서는 시각까지 기록됨", noDeparture.action)
        assertFalse("지각이 기록됐다면 위치 권한은 켜져 있었다", noDeparture.action!!.contains("위치 권한"))

        val prepOnly = cause(
            """"prep_applies":true,"unmeasured":["prep_over"],
               "planned":{"depart_by":"2026-10-04T23:00:00Z"}"""
        )
        assertEquals("측정 안 됨: 준비", prepOnly.body)
        assertEquals("준비 항목을 기록하면 준비 시간도 나눠 볼 수 있음", prepOnly.action)
    }

    @Test
    fun `늦은 출발의 제목은 출발지에 따라 다르다`() {
        fun cause(prepApplies: Boolean, unmeasured: String) = map(
            """{"week_start":"2026-10-05","late_count":1,"outcomes":[
                {"event":1,"date":"2026-10-05","result":"late","slack_minutes":-7.0,
                 "cause":"depart_late","prep_applies":$prepApplies,"unmeasured":$unmeasured,
                 "deviation":{"depart_late":7.4}}]}"""
        ).days[0].outcomes.single().cause!!

        val home = cause(true, """["prep_over"]""")
        assertEquals("집에서 7분 늦게 나섰음", home.title)
        assertEquals(
            "준비·출발·이동 중 계획보다 가장 크게 늘어난 단계임. " +
                "준비 항목을 기록하면 준비와 출발 중 어디서 늦었는지 나눠 볼 수 있음",
            home.body,
        )
        val away = cause(false, "[]")
        assertEquals("출발이 7분 늦었음", away.title)
        assertEquals("출발·이동 중 계획보다 가장 크게 늘어난 단계임", away.body)
    }

    // Partly AI-generated (Claude)
    @Test
    fun `원인 제목의 분은 같은 화면 차이 줄과 같은 반올림이다`() {
        // 서버는 양수면 아무리 작아도 그 단계를 원인으로 고른다(버퍼가 0 에 가까울 때).
        fun late(cause: String, deviation: String) = map(
            """{"week_start":"2026-10-05","late_count":1,"outcomes":[
                {"event":1,"date":"2026-10-05","result":"late","slack_minutes":-0.2,
                 "cause":"$cause","prep_applies":true,"unmeasured":[],
                 "planned":{"prep_minutes":28,"travel_minutes":42,
                            "depart_by":"2026-10-04T23:00:00Z"},
                 "actual":{"prep_minutes":27.0,"travel_minutes":42.3,
                           "depart_at":"2026-10-04T23:00:24Z"},
                 "deviation":$deviation}]}"""
        )

        val travel = late("travel_over", """{"prep_over":-1.0,"depart_late":0.0,"travel_over":0.3}""")
        val travelMorning = travel.days[0].outcomes.single()
        assertEquals("±0분", travelMorning.rows.single { it.label == "이동" }.deltaLabel)
        assertEquals("이동이 더 걸렸음", travelMorning.cause!!.title)
        assertEquals("이동이 더 걸렸음", travel.lateDays.single().causeTitle)

        val depart = late("depart_late", """{"prep_over":-1.0,"depart_late":0.4,"travel_over":-0.2}""")
            .days[0].outcomes.single()
        assertEquals("±0분", depart.rows.single { it.label == "출발" }.deltaLabel)
        assertEquals("집에서 늦게 나섰음", depart.cause!!.title)

        // 반올림해 1분 이상이면 줄과 같은 숫자를 쓴다.
        val prep = late("prep_over", """{"prep_over":0.5,"depart_late":0.0,"travel_over":-0.4}""")
            .days[0].outcomes.single()
        assertEquals("+1분", prep.rows.single { it.label == "준비" }.deltaLabel)
        assertEquals("준비가 1분 더 걸렸음", prep.cause!!.title)
    }

    @Test
    fun `알람이 꺼진 일정의 상세`() {
        val morning = map(
            """{"week_start":"2026-10-05","outcomes":[
                {"event":1,"title":"동아리","start_at":"2026-10-05T06:00:00Z",
                 "date":"2026-10-05","result":"unobserved","alarm_on":false,
                 "planned":{"alarm_at":"2026-10-05T04:40:00Z"}}]}"""
        ).days[0].outcomes.single()
        assertEquals("15:00 동아리 · 알람 꺼짐", morning.contextLine)
        assertEquals("알람이 울리지 않은 일정이라 출발·도착을 기록하지 않았음", morning.headlineNote)
    }

    @Test
    fun `τ 표기는 둘째 자리까지 늘 쓴다`() {
        assertEquals("0.90", tauLabel(0.9))
        assertEquals("0.95", tauLabel(0.95))
        assertEquals("0.925", tauLabel(0.925))
        assertEquals("0.999", tauLabel(0.999))
    }

    @Test
    fun `예측 검증 표는 서버 구간 아래 끝으로 강조한다`() {
        val view = gson.fromJson(
            """{"buckets":[
                 {"lower":0.9,"upper":0.95,"center":0.925,"total":27,"on_time":24,
                  "actual_rate":0.8889,"gap":-0.0361,"reliable":true},
                 {"lower":0.95,"upper":1.0,"center":0.975,"total":2,"on_time":2,
                  "actual_rate":1.0,"gap":0.025,"reliable":false}],
                "scored_count":29,"unscored_count":0,"verdict":"calibrated"}""",
            CalibrationDto::class.java,
        ).toView()

        assertEquals("90~95%", view.points[0].rangeLabel)
        assertEquals("실제 89% · 27번", view.points[0].actualLabel)
        assertEquals("실제 100% · 2번 · 표본 부족", view.points[1].actualLabel)
        assertTrue(view.points[0].matches(0.9))
        assertFalse(view.points[1].matches(0.9))
        assertFalse(view.points[0].matches(null))
        assertEquals("채점 29건", view.countsLabel)
        assertEquals("잘 맞음", view.verdictLabel)
    }
}
