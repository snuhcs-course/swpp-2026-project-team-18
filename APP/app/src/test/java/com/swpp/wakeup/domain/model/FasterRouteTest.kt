// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 알람 전 "더 빠른 경로로 바꾸기" 제안의 조건과 문구. */
class FasterRouteTest {
    private val now = 1_800_000_000_000L
    private val alarmAt = now + 3_600_000L

    private fun offer(
        isComputed: Boolean = true,
        skipped: Boolean = false,
        alarmOn: Boolean = true,
        alarmAtMillis: Long? = alarmAt,
        nowMillis: Long = now,
        routeKey: String? = "transit:9호선",
        altRouteKey: String? = "transit:2호선",
        altLabel: String? = "9호선 → 2호선",
        faster: Int? = 6,
    ) = FasterRoute.offer(
        isComputed, skipped, alarmOn, alarmAtMillis, nowMillis, routeKey, altRouteKey, altLabel, faster,
    )

    @Test
    fun `5분 빠르면 제안한다`() {
        val offer = offer(faster = 5)
        assertNotNull(offer)
        assertEquals("transit:2호선", offer!!.routeKey)
        assertEquals(5, offer.fasterMinutes)
        assertEquals(alarmAt, offer.alarmAtMillis)
    }

    @Test
    fun `4분이하나 값이 없으면 제안하지 않는다`() {
        assertNull(offer(faster = 4))
        assertNull(offer(faster = 0))
        assertNull(offer(faster = null))
    }

    @Test
    fun `알람이 울렸으면 제안하지 않는다`() {
        assertNull("알람 시각과 같은 순간은 이미 울린 것", offer(nowMillis = alarmAt))
        assertNull(offer(nowMillis = alarmAt + 1))
        assertNull("알람 시각을 모르면 울렸는지 알 수 없다", offer(alarmAtMillis = null))
    }

    @Test
    fun `계산 안 됨 안 감 알람 꺼짐이면 제안하지 않는다`() {
        assertNull(offer(isComputed = false))
        assertNull(offer(skipped = true))
        assertNull(offer(alarmOn = false))
    }

    @Test
    fun `대안 key 가 비었거나 지금 경로와 같으면 제안하지 않는다`() {
        assertNull(offer(altRouteKey = null))
        assertNull(offer(altRouteKey = "  "))
        assertNull(offer(altRouteKey = "transit:9호선"))
    }

    @Test
    fun `제안은 알람 전에만 열려 있다`() {
        val offer = offer()!!
        assertTrue(offer.isOpen(alarmAt - 1))
        assertFalse(offer.isOpen(alarmAt))
    }

    @Test
    fun `문구는 노선 이름의 받침에 맞춰 조사를 고른다`() {
        assertEquals("9호선 → 2호선으로 가면 6분 빨라요", FasterRoute.line(offer()!!))
        assertEquals("2호선으로 가면 6분 빨라요", FasterRoute.line(offer(altLabel = "2호선")!!))
        assertEquals("다른 경로로 가면 6분 빨라요", FasterRoute.line(offer(altLabel = null)!!))
        assertEquals("다른 경로로 가면 6분 빨라요", FasterRoute.line(offer(altLabel = " ")!!))
    }
}
