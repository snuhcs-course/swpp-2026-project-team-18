// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.alarm

import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.AlarmTimeChoice
import com.swpp.wakeup.domain.model.ConfidenceView
import com.swpp.wakeup.domain.model.PlanRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** ④ 알람 결정 다시 그림(Figma `545:920`)의 문구 · 판단 */
class AlarmDecisionLogicTest {

    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(h: Int, m: Int, day: Int = 17) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private fun plan(
        tau: Double? = .90,
        tauSource: String? = "기본값",
        userAlarm: Boolean = false,
        confirmed: Boolean? = false,
        prep: Int = 28,
        travel: Int = 42,
        buffer: Int = 25,
        prepApplies: Boolean = true,
        alarmOn: Boolean = true,
        skipped: Boolean = false,
        alarmPassed: Boolean = false,
        status: String = "ok",
    ) = AlarmPlanView(
        eventId = 1,
        whenLabel = "내일",
        dateLabel = "11월 17일",
        eventTitle = "10:00 전공 발표",
        eventPlace = "302동 105호",
        sensitivityTag = null,
        alarmAt = "7:40",
        meridiem = "AM",
        remaining = null,
        onTimeProbability = 90,
        tauUsed = tau,
        tauSourceLabel = tauSource,
        confidence = ConfidenceView(percent = 90, headline = "정시 도착 확률 90%", reason = null, action = null),
        breakdown = listOf(
            PlanRow("준비 시간", prep, null, PlanRow.Kind.PREP),
            PlanRow("이동", travel, null, PlanRow.Kind.TRAVEL),
            PlanRow("안전 버퍼", buffer, null, PlanRow.Kind.BUFFER),
        ),
        totalMinutes = prep + travel + buffer,
        arrivalLine = null,
        startAtMillis = at(10, 0),
        alarmAtMillis = at(8, 25),
        departByMillis = at(8, 25) + prep * 60_000L,
        arriveAtMillis = at(8, 25) + (prep + travel) * 60_000L,
        prepApplies = prepApplies,
        status = status,
        statusLabel = null,
        userAlarm = userAlarm,
        alarmConfirmed = confirmed,
        alarmOn = alarmOn,
        skipped = skipped,
        alarmPassed = alarmPassed,
        travelMode = "지하철+버스",
        bufferSource = "fixed",
        placeName = "302동 105호",
        travelMinutes = travel,
    )

    @Test
    fun `알약 글자 - 추천 칸 · 다른 칸 · 직접 정한 시각`() {
        assertEquals(AlarmDecisionLogic.Pill("추천", AlarmDecisionLogic.PillKind.RECOMMENDED), AlarmDecisionLogic.pill(plan()))
        // 사용자가 τ 를 직접 골랐으면 추천은 보통 — 여유 있게를 골랐으면 그 이름
        assertEquals(
            AlarmDecisionLogic.Pill("여유 있게", AlarmDecisionLogic.PillKind.PRESET),
            AlarmDecisionLogic.pill(plan(tau = .97, tauSource = AlarmTimeChoice.MANUAL_TAU_SOURCE)),
        )
        assertEquals("빠듯하게", AlarmDecisionLogic.pill(plan(tau = .65, tauSource = AlarmTimeChoice.MANUAL_TAU_SOURCE)).text)
        // 종류 기본값(세 칸에 없는 τ)은 그 일정의 추천이다
        assertEquals("추천", AlarmDecisionLogic.pill(plan(tau = .95, tauSource = "시험 일정 기본값")).text)
        assertEquals(
            AlarmDecisionLogic.Pill("직접 정한 시각", AlarmDecisionLogic.PillKind.USER),
            AlarmDecisionLogic.pill(plan(userAlarm = true)),
        )
    }

    @Test
    fun `일정 카드 문구`() {
        assertEquals("내일 오전 10:00", AlarmDecisionLogic.whenLine(at(10, 0), LocalDate.of(2026, 11, 16), zone))
        assertEquals("302동 105호 · 지하철·버스 42분", AlarmDecisionLogic.placeLine("302동 105호", "지하철+버스", 42))
        assertEquals("장소 없음", AlarmDecisionLogic.placeLine(null, null, null))
        assertEquals("1시간 28분 뒤 · 9:00 일정", AlarmDecisionLogic.remainingLine(at(7, 40), at(9, 0), at(6, 12), zone))
        assertEquals("지난 알람 · 9:00 일정", AlarmDecisionLogic.remainingLine(at(7, 40), at(9, 0), at(7, 50), zone))
        assertEquals("왜 오전 7:40인가요?", AlarmDecisionLogic.whyTitle(at(7, 40), zone))
    }

    @Test
    fun `설명 줄 - 수단 · 여유 출처`() {
        assertEquals("지하철·버스 경로로 정했어요", AlarmDecisionLogic.travelNote("지하철+버스"))
        assertEquals("자동차 경로로 정했어요", AlarmDecisionLogic.travelNote("자동차"))
        assertEquals("고른 경로로 정했어요", AlarmDecisionLogic.travelNote(null))
        assertEquals("지난 지각 기록을 보고 정했어요", AlarmDecisionLogic.bufferNote("learned"))
        assertEquals("기본 여유 시간이에요", AlarmDecisionLogic.bufferNote("fixed"))
        assertEquals("기본 여유 시간이에요", AlarmDecisionLogic.bufferNote(null))
    }

    @Test
    fun `막대와 눈금은 분에 비례하고 준비가 없으면 출발이 기상과 합쳐진다`() {
        val t = AlarmDecisionLogic.timeline(plan(), zone)
        assertEquals(listOf("준비 28분", "이동 42분", "여유 25분"), t.segments.map { it.label })
        assertEquals(listOf("8:25", "8:53", "9:35", "10:00"), t.points.map { it.clock })
        assertEquals(28f / 95, t.points[1].at, 1e-4f)

        val noPrep = AlarmDecisionLogic.timeline(plan(prep = 0, prepApplies = false), zone)
        assertEquals(listOf("이동 42분", "여유 25분"), noPrep.segments.map { it.label })
        assertEquals(listOf("기상·출발", "도착", "시작"), noPrep.points.map { it.name })
    }

    @Test
    fun `직접 정한 시각과 추천의 차이`() {
        assertEquals("추천(오전 7:54)보다 16분 늦은 알람이에요", AlarmDecisionLogic.userGapLine(at(8, 10), at(7, 54), zone))
        assertEquals("추천(오전 7:54)보다 14분 이른 알람이에요", AlarmDecisionLogic.userGapLine(at(7, 40), at(7, 54), zone))
        assertNull(AlarmDecisionLogic.userGapLine(at(8, 10), null, zone))
    }

    @Test
    fun `알람 상태 한 줄 - 확인함 · 울림 · 꺼짐 · 해당 없음`() {
        assertEquals(AlarmDecisionLogic.AlarmStatus("알람이 맞춰져 있어요", confirmed = true), AlarmDecisionLogic.alarmStatus(plan(confirmed = true), zone))
        assertEquals(AlarmDecisionLogic.AlarmStatus("오전 8:25에 알람이 울려요"), AlarmDecisionLogic.alarmStatus(plan(confirmed = false), zone))
        // 확인을 모르는 서버(필드 없음)도 울리는 시각만 말한다
        assertEquals(AlarmDecisionLogic.AlarmStatus("오전 8:25에 알람이 울려요"), AlarmDecisionLogic.alarmStatus(plan(confirmed = null), zone))
        assertEquals("알람이 꺼져 있어요. 홈에서 켤 수 있어요", AlarmDecisionLogic.alarmStatus(plan(alarmOn = false), zone)?.text)
        assertNull(AlarmDecisionLogic.alarmStatus(plan(skipped = true), zone))
        assertNull(AlarmDecisionLogic.alarmStatus(plan(alarmPassed = true), zone))
        assertNull(AlarmDecisionLogic.alarmStatus(plan(status = "route_failed"), zone))
    }
}
