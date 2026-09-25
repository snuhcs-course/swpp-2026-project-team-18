package com.swpp.wakeup.ui.alarm

import com.swpp.wakeup.domain.model.RouteProgress
import com.swpp.wakeup.domain.model.TripStage
import com.swpp.wakeup.ui.theme.JitColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 진행 바에 들어가는 두 줄.
 *
 * 바 하나에 단계·근거·남은 거리를 모두 얹었으므로, **없는 값을 있는 것처럼 쓰지
 * 않는지**가 이 파일이 지키는 것이다. "이동 중 · 0km 이동" 은 측정하지 못한 것을
 * 측정해서 0 이 나온 것처럼 보이게 한다.
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
    fun `측정 중이면 남은 거리를 적는다`() {
        assertEquals("5.4km 남음", trailingLine(TripStage.IN_TRANSIT, onRoute(4600, 10000)))
    }

    @Test
    fun `측정 전이면 시각이 무엇인지 밝힌다`() {
        // 위의 큰 숫자가 무슨 시각인지 말해 주는 자리다.
        assertEquals("도착 예정", trailingLine(TripStage.BEFORE_ALARM, null))
        assertEquals("도착 예정", trailingLine(TripStage.PREPARING, null))
        assertEquals("도착 예정", trailingLine(TripStage.PAST, null))
    }

    @Test
    fun `이탈 중에는 남은 거리를 말하지 않는다`() {
        assertEquals("도착 예정", trailingLine(TripStage.IN_TRANSIT, offRoute()))
    }

    @Test
    fun `도착했으면 예정이 아니다`() {
        assertEquals("도착", trailingLine(TripStage.ARRIVED, onRoute(10000, 10000)))
    }

    // --- 색 -----------------------------------------------------------------

    @Test
    fun `지금 일어나는 일이 아닌 단계는 흐린 색이다`() {
        assertEquals(JitColor.Track, stageColorFor(TripStage.BEFORE_ALARM))
        assertEquals(JitColor.Track, stageColorFor(TripStage.PAST))
    }

    @Test
    fun `진행 중인 단계는 서로 다른 색이다`() {
        val colors = listOf(
            stageColorFor(TripStage.PREPARING),
            stageColorFor(TripStage.IN_TRANSIT),
            stageColorFor(TripStage.ARRIVED),
        )
        assertEquals("단계가 색으로 구분되지 않는다", 3, colors.toSet().size)
        assertTrue(colors.none { it == JitColor.Track })
    }
}
