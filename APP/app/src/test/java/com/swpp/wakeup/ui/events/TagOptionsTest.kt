// AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.data.remote.EventTagDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 일정 추가의 종류 목록은 서버 `GET /api/events/tags` 로 만든다.
 * 이름과 τ 는 서버 값 그대로다. 맨 끝의 "+ 추가" 는 드롭다운이 따로 그린다.
 */
class TagOptionsTest {

    private fun tag(key: String, label: String, tau: Double?) =
        EventTagDto(id = null, key = key, label = label, defaultTau = tau, penaltyShape = null)

    @Test
    fun `서버 순서와 이름 τ 를 그대로 쓴다`() {
        val options = tagOptionsOf(listOf(tag("class", "수업", 0.9), tag("exam", "시험", 0.99)))
        assertEquals(listOf("class", "exam"), options.map { it.key })
        assertEquals(listOf("수업", "시험"), options.map { it.label })
        assertEquals("τ 0.90", options[0].tau)
        assertEquals("τ 0.99", options[1].tau)
    }

    @Test
    fun `기타는 더 이상 붙이지 않는다`() {
        assertTrue(tagOptionsOf(emptyList()).isEmpty())
    }

    @Test
    fun `τ 가 없으면 비워 둔다`() {
        assertEquals("", tagOptionsOf(listOf(tag("etc", "모임", null)))[0].tau)
    }
}
