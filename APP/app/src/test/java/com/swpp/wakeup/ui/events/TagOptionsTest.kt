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

    private fun tag(key: String, label: String, tau: Double?, custom: Boolean? = null) =
        EventTagDto(id = null, key = key, label = label, defaultTau = tau, penaltyShape = null, custom = custom)

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
    fun `τ 가 없으면 프로필 기본이라고 쓴다`() {
        assertEquals("프로필 기본", tagOptionsOf(listOf(tag("etc", "모임", null)))[0].tau)
    }

    // Partly AI-generated (Claude)
    @Test
    fun `만든 종류는 목록 끝에 붙는다`() {
        val base = listOf(tag("class", "수업", 0.9))
        val made = tag("u0123456789", "Lab", null)
        assertEquals(listOf("class", "u0123456789"), tagsWith(base, made).map { it.key })
    }

    // Partly AI-generated (Claude)
    @Test
    fun `이미 있는 key 는 다시 붙이지 않는다`() {
        val made = tag("u0123456789", "Lab", null)
        val base = listOf(tag("class", "수업", 0.9), made)
        assertEquals(base, tagsWith(base, made))
    }

    // Partly AI-generated (Claude)
    @Test
    fun `내가 만든 종류만 편집할 수 있다`() {
        val options = tagOptionsOf(
            listOf(tag("class", "수업", 0.9, custom = false), tag("u0123456789", "Lab", null, custom = true), tag("exam", "시험", 0.99))
        )
        assertEquals(listOf(false, true, false), options.map { it.custom })
    }

    // Partly AI-generated (Claude)
    @Test
    fun `이름을 바꾼 종류는 같은 자리에 들어간다`() {
        val base = listOf(tag("class", "수업", 0.9), tag("u0123456789", "Lab", null, true), tag("u9999999999", "알바2", null, true))
        val renamed = tag("u0123456789", "동아리", null, true)
        assertEquals(listOf("수업", "동아리", "알바2"), tagsRenamed(base, renamed).map { it.label })
    }

    // Partly AI-generated (Claude)
    @Test
    fun `목록에 없는 종류의 새 이름은 붙이지 않는다`() {
        val base = listOf(tag("class", "수업", 0.9))
        assertEquals(base, tagsRenamed(base, tag("u0123456789", "동아리", null, true)))
    }

    // Partly AI-generated (Claude)
    @Test
    fun `지운 종류는 목록에서 빠진다`() {
        val base = listOf(tag("class", "수업", 0.9), tag("u0123456789", "Lab", null, true))
        assertEquals(listOf("class"), tagsWithout(base, "u0123456789").map { it.key })
        assertEquals(base, tagsWithout(base, "nope"))
    }
}
