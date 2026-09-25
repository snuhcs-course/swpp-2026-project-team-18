package com.swpp.wakeup.ui.alarm

import com.swpp.wakeup.domain.model.ArrivalOutlook
import com.swpp.wakeup.domain.model.RouteProgress
import com.swpp.wakeup.domain.model.TripStage
import com.swpp.wakeup.ui.theme.JitColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 진행 바의 색과 두 줄.
 *
 * 바 하나에 단계·근거·지각 전망을 모두 얹었으므로 이 파일이 지키는 것은 둘이다.
 *
 * 1. **없는 값을 있는 것처럼 쓰지 않는다.** "이동 중 · 0km 이동" 은 측정하지
 *    못한 것을 측정해서 0 이 나온 것처럼 보이게 한다
 * 2. **판단할 수 없으면 색을 쓰지 않는다.** 숫자는 의심하지만 색은 그냥 믿는다
 */
class TripProgressCardTest {

    private fun onRoute(traveled: Int, total: Int) = RouteProgress(
        ratio = traveled.toFloat() / total,
        traveledM = traveled,
        totalM = total,
        offRouteM = 20,
    )

    private fun offRoute() = RouteProgress(
        ratio = 0.46f,
        traveledM = 4600,
        totalM = 10000,
        offRouteM = 2100,
    )

    private fun outlook(verdict: ArrivalOutlook.Verdict, delta: Long) =
        ArrivalOutlook(deltaMinutes = delta, predictedMillis = 0L, verdict = verdict)

    private val onTime = outlook(ArrivalOutlook.Verdict.ON_TIME, 0)
    private val tight = outlook(ArrivalOutlook.Verdict.TIGHT, 5)
    private val late = outlook(ArrivalOutlook.Verdict.LATE, 26)

    // --- 색 -----------------------------------------------------------------

    @Test
    fun `전망이 색을 정한다`() {
        assertEquals(JitColor.Green, rowColor(TripStage.IN_TRANSIT, onRoute(4600, 10000), onTime))
        assertEquals(JitColor.Amber, rowColor(TripStage.IN_TRANSIT, onRoute(4600, 10000), tight))
        assertEquals(JitColor.Red, rowColor(TripStage.IN_TRANSIT, onRoute(4600, 10000), late))
    }

    @Test
    fun `색은 단계가 아니라 전망을 따른다`() {
        // 같은 단계인데 전망이 다르면 색이 달라야 한다. 단계에 색을 쓰면
        // 가장 급한 정보가 색을 잃는다.
        val p = onRoute(4600, 10000)
        assertNotEquals(
            rowColor(TripStage.IN_TRANSIT, p, onTime),
            rowColor(TripStage.IN_TRANSIT, p, late),
        )
        // 다른 단계인데 전망이 같으면 색이 같아야 한다.
        assertEquals(
            rowColor(TripStage.PREPARING, null, late),
            rowColor(TripStage.IN_TRANSIT, p, late),
        )
    }

    @Test
    fun `전망을 못 내면 색을 쓰지 않는다`() {
        assertEquals(JitColor.Track, rowColor(TripStage.PREPARING, null, null))
        assertEquals(JitColor.Track, rowColor(TripStage.IN_TRANSIT, onRoute(1, 100), null))
    }

    @Test
    fun `알람 전과 지난 일정은 전망이 있어도 흐리다`() {
        // 시작하지 않은 여정과 기록 없는 여정에 "정시" 라고 초록을 줄 수 없다.
        assertEquals(JitColor.Track, rowColor(TripStage.BEFORE_ALARM, null, onTime))
        assertEquals(JitColor.Track, rowColor(TripStage.PAST, null, late))
    }

    @Test
    fun `경로를 벗어나면 전망보다 이탈이 먼저다`() {
        // 다른 길을 가는 중이면 계획 속도로 환산한 값이 아무 뜻이 없다.
        // 여기서 초록을 주면 2km 벗어난 사람이 "정시" 를 보고 안심한다.
        assertEquals(JitColor.Amber, rowColor(TripStage.IN_TRANSIT, offRoute(), onTime))
    }

    @Test
    fun `세 전망은 서로 다른 색이다`() {
        val colors = ArrivalOutlook.Verdict.entries.map { outlookColor(it) }
        assertEquals("전망이 색으로 구분되지 않는다", 3, colors.toSet().size)
        assertTrue("전망 색에 흐린 회색을 쓰면 판단 불가와 섞인다", colors.none { it == JitColor.Track })
    }

    // --- 채움 ---------------------------------------------------------------

    @Test
    fun `채움은 경로 진행률이다`() {
        assertEquals(0.46f, fillRatio(TripStage.IN_TRANSIT, onRoute(4600, 10000)), 0.001f)
    }

    @Test
    fun `도착했으면 좌표가 없어도 가득 채운다`() {
        assertEquals(1f, fillRatio(TripStage.ARRIVED, null), 0.001f)
    }

    @Test
    fun `측정 전이면 채우지 않는다`() {
        assertEquals(0f, fillRatio(TripStage.PREPARING, null), 0.001f)
        assertEquals(0f, fillRatio(TripStage.BEFORE_ALARM, null), 0.001f)
    }

    @Test
    fun `이탈한 위치로는 채우지 않는다`() {
        // 이탈 지점을 경로에 투영한 46% 는 실제로 온 만큼이 아니다.
        assertEquals(0f, fillRatio(TripStage.IN_TRANSIT, offRoute()), 0.001f)
    }

    // --- 이름 아래 한 줄 ----------------------------------------------------

    @Test
    fun `이동 중이고 측정됐으면 이동 거리를 붙인다`() {
        assertEquals(
            "이동 중 · 4.6km 이동",
            statusLine(TripStage.IN_TRANSIT, onRoute(4600, 10000)),
        )
    }

    @Test
    fun `이동 중이지만 위치가 없으면 거리를 붙이지 않는다`() {
        // "0km 이동" 을 붙이면 한 걸음도 못 갔다는 뜻이 된다. 사실은 모른다.
        assertEquals("이동 중", statusLine(TripStage.IN_TRANSIT, null))
    }

    @Test
    fun `경로를 벗어나면 거리 대신 이탈을 적는다`() {
        val line = statusLine(TripStage.IN_TRANSIT, offRoute())
        assertEquals("이동 중 · 경로 이탈", line)
        assertFalse("이탈했는데 이동 거리를 말하고 있다", line.contains("4.6km"))
    }

    @Test
    fun `도착과 지난 일정은 단계 이름을 그대로 쓰지 않는다`() {
        assertEquals("도착 완료", statusLine(TripStage.ARRIVED, null))
        assertEquals("이동 기록 없음", statusLine(TripStage.PAST, null))
    }

    @Test
    fun `알람 전과 준비 중은 단계 이름만 쓴다`() {
        assertEquals("알람 전", statusLine(TripStage.BEFORE_ALARM, null))
        assertEquals("준비 중", statusLine(TripStage.PREPARING, null))
        // 아직 이동 전이므로 진행률이 있어도 붙이지 않는다.
        assertEquals("준비 중", statusLine(TripStage.PREPARING, onRoute(10, 10000)))
    }

    // --- 오른쪽 아래 한 줄 --------------------------------------------------

    @Test
    fun `전망이 있으면 늦는 분을 적는다`() {
        // 색만 보고 의아할 때 이 줄에서 이유를 읽는다.
        val p = onRoute(4600, 10000)
        assertEquals("정시", trailingLine(TripStage.IN_TRANSIT, p, onTime))
        assertEquals("+5분", trailingLine(TripStage.IN_TRANSIT, p, tight))
        assertEquals("+26분", trailingLine(TripStage.PREPARING, null, late))
    }

    @Test
    fun `전망이 없으면 시각이 무엇인지 밝힌다`() {
        // 위의 큰 숫자가 무슨 시각인지 말해 주는 자리다.
        assertEquals("도착 예정", trailingLine(TripStage.BEFORE_ALARM, null, null))
        assertEquals("기록 없음", trailingLine(TripStage.PAST, null, null))
        assertEquals("도착", trailingLine(TripStage.ARRIVED, null, null))
    }

    @Test
    fun `이탈 중에는 전망을 말하지 않는다`() {
        assertEquals("경로 이탈", trailingLine(TripStage.IN_TRANSIT, offRoute(), onTime))
    }
}
