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
 * 경계는 임의의 숫자가 아니라 안전 버퍼다. `도착 예정 = 약속 시각 − 버퍼` 라서
 * 버퍼 안에서 늦는 것은 여유를 깎는 것이고, 버퍼를 넘기면 약속에 늦는다.
 */
class ArrivalOutlookTest {

    private val minute = 60_000L

    /** 8:00 출발 → 8:40 도착 예정, 이동 40분, 버퍼 10분. 약속은 8:50. */
    private val departBy = 8 * 60 * minute
    private val arriveAt = departBy + 40 * minute
    private val travel = 40
    private val buffer = 10

    private fun at(minutesFromDepart: Long, ratio: Float) = ArrivalOutlook.of(
        departByMillis = departBy,
        arriveAtMillis = arriveAt,
        travelMinutes = travel,
        bufferMinutes = buffer,
        ratio = ratio,
        nowMillis = departBy + minutesFromDepart * minute,
    )!!

    // --- 판정 ---------------------------------------------------------------

    @Test
    fun `계획대로 가고 있으면 정시다`() {
        // 출발 20분 뒤에 절반을 왔다. 계획과 정확히 같다.
        val o = at(minutesFromDepart = 20, ratio = 0.5f)
        assertEquals(0L, o.deltaMinutes)
        assertEquals(ArrivalOutlook.Verdict.ON_TIME, o.verdict)
        assertEquals("정시", o.label)
    }

    @Test
    fun `계획보다 앞서 있으면 정시다`() {
        val o = at(minutesFromDepart = 10, ratio = 0.5f)
        assertEquals(-10L, o.deltaMinutes)
        assertEquals(ArrivalOutlook.Verdict.ON_TIME, o.verdict)
        // 일찍 도착하는 것을 "-10분" 이라 적지 않는다. 읽는 사람이 지각으로
        // 착각할 여지를 남기지 않는다.
        assertEquals("정시", o.label)
    }

    @Test
    fun `버퍼 안에서 늦으면 여유만 깎인다`() {
        // 출발이 5분 늦었고 아직 안 떴다.
        val o = at(minutesFromDepart = 5, ratio = 0f)
        assertEquals(5L, o.deltaMinutes)
        assertEquals(ArrivalOutlook.Verdict.TIGHT, o.verdict)
        assertEquals("+5분", o.label)
    }

    @Test
    fun `버퍼만큼 늦으면 약속 시각에 딱 닿는다`() {
        // 도착 예정 = 약속 − 버퍼 이므로 버퍼만큼 늦어도 약속은 지킨다.
        val o = at(minutesFromDepart = 10, ratio = 0f)
        assertEquals(ArrivalOutlook.Verdict.TIGHT, o.verdict)
    }

    @Test
    fun `버퍼를 넘기면 지각이다`() {
        val o = at(minutesFromDepart = 11, ratio = 0f)
        assertEquals(ArrivalOutlook.Verdict.LATE, o.verdict)
        assertEquals("+11분", o.label)
    }

    @Test
    fun `많이 늦으면 시간 단위로 적는다`() {
        val o = at(minutesFromDepart = 66, ratio = 0f)
        assertEquals(ArrivalOutlook.Verdict.LATE, o.verdict)
        assertEquals("+1시간 6분", o.label)
        assertEquals("+2시간", at(minutesFromDepart = 120, ratio = 0f).label)
    }

    // --- 이동이 만회한다 ----------------------------------------------------

    @Test
    fun `늦게 떠났어도 경로를 앞서가면 만회된다`() {
        // 10분 늦게 나섰지만 15분 만에 절반(계획 20분 구간)을 왔다.
        val o = at(minutesFromDepart = 25, ratio = 0.5f)
        assertEquals(5L, o.deltaMinutes)
        assertEquals(ArrivalOutlook.Verdict.TIGHT, o.verdict)
    }

    @Test
    fun `도착 지점에 닿으면 더 늦지 않는다`() {
        // 남은 거리가 0 이면 예상 도착이 지금이다.
        val o = at(minutesFromDepart = 45, ratio = 1f)
        assertEquals(5L, o.deltaMinutes)
        assertEquals(departBy + 45 * minute, o.predictedMillis)
    }

    @Test
    fun `가만히 있으면 시간이 갈수록 나빠진다`() {
        val early = at(minutesFromDepart = 2, ratio = 0f)
        val later = at(minutesFromDepart = 40, ratio = 0f)
        assertEquals(ArrivalOutlook.Verdict.ON_TIME, at(0, 0f).verdict)
        assertEquals(ArrivalOutlook.Verdict.TIGHT, early.verdict)
        assertEquals(ArrivalOutlook.Verdict.LATE, later.verdict)
    }

    // --- 반올림 -------------------------------------------------------------

    @Test
    fun `초 단위로 늦은 것은 지각이 아니다`() {
        // 30초 늦은 것을 "+1분" 이라 하면 정시인데도 노란 바를 본다.
        val o = ArrivalOutlook.of(
            departByMillis = departBy,
            arriveAtMillis = arriveAt,
            travelMinutes = travel,
            bufferMinutes = buffer,
            ratio = 0f,
            nowMillis = departBy + 30_000L,
        )!!
        assertEquals(0L, o.deltaMinutes)
        assertEquals(ArrivalOutlook.Verdict.ON_TIME, o.verdict)
    }

    // --- 낼 수 없는 경우 ----------------------------------------------------

    @Test
    fun `기준 시각이 없으면 내지 않는다`() {
        assertNull(ArrivalOutlook.of(null, arriveAt, travel, buffer, 0f, departBy))
        assertNull(ArrivalOutlook.of(departBy, null, travel, buffer, 0f, departBy))
    }

    @Test
    fun `이동 시간을 모르면 내지 않는다`() {
        // 남은 거리를 분으로 바꿀 환산율이 없다. 0 으로 치면 "지금 도착" 이 된다.
        assertNull(ArrivalOutlook.of(departBy, arriveAt, null, buffer, 0f, departBy))
        assertNull(ArrivalOutlook.of(departBy, arriveAt, -5, buffer, 0f, departBy))
    }

    @Test
    fun `버퍼를 모르면 늦는 즉시 지각으로 본다`() {
        // 모르는 채로 "괜찮다" 고 하는 것보다 낫다.
        val o = ArrivalOutlook.of(departBy, arriveAt, travel, null, 0f, departBy + 3 * minute)!!
        assertEquals(ArrivalOutlook.Verdict.LATE, o.verdict)
    }

    @Test
    fun `진행률이 범위를 벗어나도 계산이 깨지지 않는다`() {
        assertEquals(at(20, 5f).deltaMinutes, at(20, 1f).deltaMinutes)
        assertEquals(at(20, -3f).deltaMinutes, at(20, 0f).deltaMinutes)
    }

    // --- 도착한 뒤 ----------------------------------------------------------

    @Test
    fun `도착은 실제 시각으로 판정한다`() {
        // 예측과 따로 두는 이유: 같은 함수로 하면 화면을 늦게 열수록 더 늦게
        // 도착한 것으로 나온다.
        val onTime = ArrivalOutlook.arrived(arriveAt - 6 * minute, arriveAt, buffer)!!
        assertEquals(ArrivalOutlook.Verdict.ON_TIME, onTime.verdict)
        assertEquals("정시", onTime.label)

        val late = ArrivalOutlook.arrived(arriveAt + 26 * minute, arriveAt, buffer)!!
        assertEquals(ArrivalOutlook.Verdict.LATE, late.verdict)
        assertEquals("+26분", late.label)
    }

    @Test
    fun `도착 예정을 모르면 도착 판정도 없다`() {
        assertNull(ArrivalOutlook.arrived(arriveAt, null, buffer))
    }
}
