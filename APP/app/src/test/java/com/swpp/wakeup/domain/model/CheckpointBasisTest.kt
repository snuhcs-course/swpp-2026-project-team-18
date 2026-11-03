// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "경로 정류장" 카드가 따라갈 기준 경로. */
class CheckpointBasisTest {
    @Test
    fun `이번 이동에서 바꾼 경로가 우선`() {
        assertEquals("transit:5513", CheckpointBasis.baseRouteKey("transit:2호선", "transit:5513"))
    }

    @Test
    fun `바꾼 경로가 없으면 일정 경로`() {
        assertEquals("transit:2호선", CheckpointBasis.baseRouteKey("transit:2호선", null))
        assertEquals("transit:2호선", CheckpointBasis.baseRouteKey("transit:2호선", " "))
    }
    @Test
    fun `아무 key 도 없으면 null`() {
        assertNull(CheckpointBasis.baseRouteKey(null, " "))
    }

    @Test
    fun `정류장은 대중교통 key 에만 있다`() {
        assertTrue(CheckpointBasis.hasStops("transit:2호선>5513"))
        assertFalse(CheckpointBasis.hasStops("walk"))
        assertFalse(CheckpointBasis.hasStops(null))
    }
}
