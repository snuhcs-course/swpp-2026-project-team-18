package com.swpp.wakeup.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캐시 신선도 표시.
 *
 * **이 문구가 없으면 오프라인 화면이 거짓말을 한다.** 세 시간 전 알람 시각을
 * 아무 표시 없이 보여주면 사용자는 그게 지금 값이라고 믿는다. 알람 시각은
 * 교통 상황에 따라 바뀌는 값이라 그 오해의 대가가 지각이다.
 */
class OfflineCacheAgeTest {

    private fun cached(ageMinutes: Long) = OfflineCache.Cached(
        items = listOf("x"),
        cachedAt = System.currentTimeMillis() - ageMinutes * 60_000L,
        hit = true,
    )

    @Test
    fun `캐시가 없으면 나이가 없다`() {
        val miss = OfflineCache.Cached.miss<String>()
        assertFalse(miss.hit)
        assertEquals(0L, miss.ageMinutes)
        assertNull(miss.ageLabel)
        assertTrue(miss.items.isEmpty())
    }

    @Test
    fun `1분 미만은 방금이다`() {
        assertEquals("방금 정보", cached(0).ageLabel)
    }

    @Test
    fun `한 시간 미만은 분으로 적는다`() {
        assertEquals("12분 전 정보", cached(12).ageLabel)
        assertEquals("59분 전 정보", cached(59).ageLabel)
    }

    @Test
    fun `한 시간부터는 시간으로 적는다`() {
        assertEquals("1시간 전 정보", cached(60).ageLabel)
        assertEquals("3시간 전 정보", cached(60 * 3 + 20).ageLabel)
        assertEquals("23시간 전 정보", cached(60 * 23).ageLabel)
    }

    @Test
    fun `하루부터는 일로 적는다`() {
        assertEquals("1일 전 정보", cached(60 * 24).ageLabel)
        assertEquals("2일 전 정보", cached(60 * 24 * 2 + 60).ageLabel)
    }

    @Test
    fun `시계가 거꾸로 가도 음수를 보여주지 않는다`() {
        // 기기 시계가 조정되면 cachedAt 이 미래가 될 수 있다. "-5분 전 정보" 는
        // 사용자에게 아무 의미가 없다.
        val future = OfflineCache.Cached(
            items = listOf("x"),
            cachedAt = System.currentTimeMillis() + 60_000L,
            hit = true,
        )
        assertEquals(0L, future.ageMinutes)
        assertEquals("방금 정보", future.ageLabel)
    }

    @Test
    fun `빈 목록이 담긴 캐시와 캐시 없음은 다르다`() {
        // 서버에 정말 일정이 0개인 계정도 있다. 그 둘을 섞으면 "일정 없음" 과
        // "아직 받아 본 적 없음" 을 구분할 수 없어 오프라인에서 빈 화면이
        // 정상인지 오류인지 알 수 없다.
        val emptyHit = OfflineCache.Cached(items = emptyList<String>(), cachedAt = 1L, hit = true)
        assertTrue(emptyHit.hit)
        assertFalse(OfflineCache.Cached.miss<String>().hit)
    }
}
