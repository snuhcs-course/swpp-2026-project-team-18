package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 지각 전망.
 *
 * 진행 바의 **색이 이 판정에서 나온다.** 숫자가 틀리면 사용자가 의심하지만 색은
 * 그냥 믿으므로, 여기서 잘못 초록을 주면 지각한 사람이 안심한 채로 늦는다.
 *
 * ## 이 파일이 막는 회귀
 *
 * 기준을 `도착 예정`(약속 − 버퍼)으로 잡아 **약속보다 일찍 도착하는데도 지각으로
 * 표시**된 적이 있다. 실기기에서 약속 11:57 · 예상 도착 11:51 이 "+4분" 노랑으로
 * 나왔다. 기준은 약속 시각이어야 한다.
 */
class ArrivalOutlookTest {

    private val minute = 60_000L

    /** 약속 11:57. 계획은 준비 30 + 이동 20 + 버퍼 10 이라 도착 예정은 11:47 이다. */
    private val appointment = (11 * 60 + 57) * minute
    private val plannedArrival = appointment - 10 * minute

    // --- 기준이 약속 시각인가 -----------------------------------------------

    @Test
    fun `약속보다 일찍 도착하면 정시다`() {
        // 실기기에서 "+4분" 으로 나왔던 바로 그 상황이다. 11:51 도착은 약속
        // 11:57 보다 6분 이르다.
        val o = ArrivalOutlook.arrived(appointment - 6 * minute, appointment)!!
        assertEquals(-6L, o.deltaMinutes)
        assertEquals(ArrivalOutlook.Verdict.ON_TIME, o.verdict)
        assertEquals("정시", o.label)
    }

    @Test
    fun `도착 예정을 기준으로 쓰지 않는다`() {
        // 도착 예정(11:47) 기준이면 +4분이 되지만, 약속(11:57) 기준이면 정시다.
        val o = ArrivalOutlook.arrived(plannedArrival + 4 * minute, appointment)!!
        assertEquals(ArrivalOutlook.Verdict.ON_TIME, o.verdict)
    }

    @Test
    fun `약속 시각에 딱 닿으면 정시다`() {
        val o = ArrivalOutlook.arrived(appointment, appointment)!!
        assertEquals(0L, o.deltaMinutes)
        assertEquals(ArrivalOutlook.Verdict.ON_TIME, o.verdict)
    }

    // --- 경계 ---------------------------------------------------------------

    @Test
    fun `10분 미만 늦으면 노랑이다`() {
        for (late in 1L..9L) {
            val o = ArrivalOutlook.arrived(appointment + late * minute, appointment)!!
            assertEquals("$late 분 늦음", ArrivalOutlook.Verdict.TIGHT, o.verdict)
            assertEquals("+${late}분", o.label)
        }
    }

    @Test
    fun `10분 이상 늦으면 빨강이다`() {
        for (late in 10L..14L) {
            val o = ArrivalOutlook.arrived(appointment + late * minute, appointment)!!
            assertEquals("$late 분 늦음", ArrivalOutlook.Verdict.LATE, o.verdict)
        }
    }

    @Test
    fun `사용자가 든 예를 그대로 재현한다`() {
        // 약속 11:30 · 실시간 추적 결과 도착 예정 11:46 → 빨강 +16분
        val appt = (11 * 60 + 30) * minute
        val o = ArrivalOutlook.of(
            appointmentMillis = appt,
            remainingMinutes = 16.0,
            nowMillis = appt,
        )!!
        assertEquals(16L, o.deltaMinutes)
        assertEquals(ArrivalOutlook.Verdict.LATE, o.verdict)
        assertEquals("+16분", o.label)
        assertEquals((11 * 60 + 46) * minute, o.predictedMillis)
    }

    @Test
    fun `많이 늦으면 시간 단위로 적는다`() {
        assertEquals("+1시간 6분", ArrivalOutlook.arrived(appointment + 66 * minute, appointment)!!.label)
        assertEquals("+2시간", ArrivalOutlook.arrived(appointment + 120 * minute, appointment)!!.label)
    }

    @Test
    fun `초 단위로 늦은 것은 지각이 아니다`() {
        // 30초 늦은 것을 "+1분" 이라 하면 약속을 지켰는데도 노란 바를 본다.
        val o = ArrivalOutlook.arrived(appointment + 30_000L, appointment)!!
        assertEquals(0L, o.deltaMinutes)
        assertEquals(ArrivalOutlook.Verdict.ON_TIME, o.verdict)
    }

    // --- 예측 ---------------------------------------------------------------

    @Test
    fun `남은 시간을 지금에 더해 예상 도착을 낸다`() {
        val now = appointment - 30 * minute
        val o = ArrivalOutlook.of(appointment, remainingMinutes = 20.0, nowMillis = now)!!
        assertEquals(now + 20 * minute, o.predictedMillis)
        assertEquals(-10L, o.deltaMinutes)
        assertEquals(ArrivalOutlook.Verdict.ON_TIME, o.verdict)
    }

    @Test
    fun `출발이 늦으면 그만큼 늦는다`() {
        // 준비 중인데 이동 20분이 남았고 약속까지 5분뿐이다.
        val now = appointment - 5 * minute
        val o = ArrivalOutlook.of(appointment, remainingMinutes = 20.0, nowMillis = now)!!
        assertEquals(15L, o.deltaMinutes)
        assertEquals(ArrivalOutlook.Verdict.LATE, o.verdict)
    }

    // --- 낼 수 없는 경우 ----------------------------------------------------

    @Test
    fun `약속 시각이 없으면 내지 않는다`() {
        assertNull(ArrivalOutlook.of(null, 20.0, appointment))
        assertNull(ArrivalOutlook.arrived(appointment, null))
    }

    @Test
    fun `남은 시간을 모르면 내지 않는다`() {
        assertNull(ArrivalOutlook.of(appointment, null, appointment))
    }

    @Test
    fun `남은 시간이 음수나 무한이면 내지 않는다`() {
        // 계산이 어긋난 값을 받아 그럴싸한 색을 칠하지 않는다.
        assertNull(ArrivalOutlook.of(appointment, -1.0, appointment))
        assertNull(ArrivalOutlook.of(appointment, Double.POSITIVE_INFINITY, appointment))
        assertNull(ArrivalOutlook.of(appointment, Double.NaN, appointment))
    }

    @Test
    fun `경계 상수는 10분이다`() {
        assertEquals(10L, ArrivalOutlook.LATE_LIMIT_MINUTES)
    }
}
