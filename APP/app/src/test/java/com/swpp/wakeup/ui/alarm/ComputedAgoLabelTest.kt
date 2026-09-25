package com.swpp.wakeup.ui.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "언제 계산했는지" 표기.
 *
 * ## 왜 필요한가
 *
 * 백그라운드가 임박한 일정의 경로를 15분마다 다시 조회한다
 * ([com.swpp.wakeup.background.RouteRefreshWorker]). 그때 알람 시각과 이동
 * 시간이 **조용히 바뀐다.** 계산 시각을 표시하지 않으면 사용자는 화면의 숫자가
 * 방금 받은 것인지 어제 계산한 것인지 알 수 없고, 배차가 바뀌었는데도 낡은 값을
 * 믿고 움직인다.
 *
 * 서버는 `computed_at` 을 들고 있었지만 **직렬화되지 않아** 앱이 볼 수 없었다.
 */
class ComputedAgoLabelTest {

    private val now = 1_700_000_000_000L
    private fun ago(seconds: Long) = computedAgoLabel(now - seconds * 1000, now)

    @Test
    fun `방금 계산한 것은 방금이라고 적는다`() {
        assertEquals("방금 계산", ago(0))
        assertEquals("방금 계산", ago(89))
    }

    @Test
    fun `분 단위로 적는다`() {
        assertEquals("2분 전 계산", ago(120))
        assertEquals("15분 전 계산", ago(15 * 60))
        assertEquals("59분 전 계산", ago(59 * 60))
    }

    @Test
    fun `시간 단위로 적는다`() {
        assertEquals("1시간 전 계산", ago(3600))
        assertEquals("7시간 전 계산", ago(7 * 3600))
    }

    @Test
    fun `하루를 넘으면 날짜를 세지 않는다`() {
        // 정확히 며칠인지는 판단에 쓸모가 없다. 할 일은 하나다 — 다시 계산.
        assertEquals("오래된 계산", ago(24 * 3600))
        assertEquals("오래된 계산", ago(30 * 24 * 3600))
    }

    @Test
    fun `계산 전이면 비운다`() {
        assertNull(computedAgoLabel(null, now))
        assertNull(computedAgoLabel(0L, now))
    }

    @Test
    fun `미래 시각이면 비운다`() {
        // 기기와 서버 시계가 어긋난 것이다. 거짓을 적기보다 비운다.
        assertNull(computedAgoLabel(now + 60_000, now))
    }

    @Test
    fun `위치 갱신 표기와 섞이지 않는다`() {
        // 둘은 독립적으로 낡는다 — 위치는 방금 받았는데 계획은 어제 것일 수 있다.
        assertEquals("방금 갱신", freshnessLabel(now, now))
        assertEquals("방금 계산", computedAgoLabel(now, now))
    }
}
