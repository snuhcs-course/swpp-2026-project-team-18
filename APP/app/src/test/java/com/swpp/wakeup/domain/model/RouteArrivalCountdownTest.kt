package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 도착정보가 화면에 머무는 동안 줄어드는 계산.
 *
 * 서버는 응답을 만든 순간의 남은 초를 준다. 그 값을 그대로 붙여 두면 사용자가
 * 경로를 비교하는 30초 동안 숫자가 멈춰 있어, 실제로는 이미 떠난 차를 "1분 뒤
 * 도착" 으로 보여 준다. 기준 시점에서 흐른 만큼 빼서 고친다.
 *
 * 계산을 컴포저블에 두면 테스트할 수 없어서 모델에 둔다.
 */
class RouteArrivalCountdownTest {

    private val base = 10_000L

    private fun arrival(seconds: Int, crowding: String = "") = RouteArrival(
        seconds = seconds,
        message = "",
        crowding = crowding,
        fetchedAtElapsedMs = base,
    )

    @Test
    fun `흐른 초만큼 줄어든다`() {
        val a = arrival(200)

        assertEquals(200, a.remainingSeconds(base))
        assertEquals(199, a.remainingSeconds(base + 1_000))
        assertEquals(170, a.remainingSeconds(base + 30_000))
    }

    @Test
    fun `1초가 지나기 전에는 줄지 않는다`() {
        val a = arrival(200)

        assertEquals(200, a.remainingSeconds(base + 999))
        assertEquals(199, a.remainingSeconds(base + 1_000))
    }

    @Test
    fun `0 아래로 내려가지 않는다`() {
        val a = arrival(5)

        assertEquals(0, a.remainingSeconds(base + 5_000))
        assertEquals(0, a.remainingSeconds(base + 60_000))
        assertEquals(0, a.remainingSeconds(base + 86_400_000))
    }

    @Test
    fun `기준 시점이 없으면 줄이지 않는다`() {
        val a = RouteArrival(200, "3분 20초 뒤 도착")

        assertEquals(200, a.remainingSeconds(base + 60_000))
        assertEquals("3분 20초 뒤 도착", a.displayTextAt(base + 60_000))
    }

    /**
     * elapsedRealtime 은 뒤로 가지 않지만, 잘못 전달된 기준 시점 때문에 now 가
     * 기준보다 작아질 수 있다. 음수 경과를 그대로 쓰면 남은 시간이 **늘어나서**
     * 사용자가 놓칠 차를 탈 수 있다고 믿게 된다.
     */
    @Test
    fun `기준보다 이른 시각이 들어와도 늘어나지 않는다`() {
        val a = arrival(200)

        assertEquals(200, a.remainingSeconds(base - 60_000))
    }

    @Test
    fun `문구를 남은 초에서 다시 만든다`() {
        val a = arrival(200)

        assertEquals("3분 20초 뒤 도착", a.displayTextAt(base))
        assertEquals("3분 뒤 도착", a.displayTextAt(base + 20_000))
        assertEquals("40초 뒤 도착", a.displayTextAt(base + 160_000))
        assertEquals("곧 도착", a.displayTextAt(base + 200_000))
    }

    @Test
    fun `혼잡도는 줄어든 문구에도 붙는다`() {
        val a = arrival(200, crowding = "보통")

        assertEquals("3분 20초 뒤 도착 · 보통", a.displayTextAt(base))
        assertEquals("1분 뒤 도착 · 보통", a.displayTextAt(base + 140_000))
    }

    /**
     * 서버와 앱이 같은 형식을 쓴다. 다르면 화면이 뜬 직후 숫자가 한 번 튀어
     * 사용자가 값을 의심한다.
     */
    @Test
    fun `받은 순간의 문구는 서버 문구와 같다`() {
        val a = RouteArrival(
            seconds = 110,
            message = "1분 50초 뒤 도착",
            fetchedAtElapsedMs = base,
        )

        assertEquals(a.displayText, a.displayTextAt(base))
    }
}
