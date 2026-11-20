// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.calendar.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 캘린더 가져오기: 다시 읽어도 선택을 지키고, 장소를 찾는 동안에는 보내지 않는다(fix/iter4-he-bugs 이식, 11-19) */
class CalendarImportKeepTest {

    private fun cand(id: String, selected: Boolean, imported: Boolean = false, resolving: Boolean = false) = ImportCandidate(
        source = CalendarEvent(id, "일정 $id", 0L, "2030-01-01T09:00:00+09:00", null, null),
        selected = selected,
        alreadyImported = imported,
        resolving = resolving,
    )

    @Test
    fun `끈 일정은 다시 읽어도 꺼진 채 · 새 일정은 기본 선택`() {
        val previous = listOf(cand("a", selected = false), cand("b", selected = true))
        val fresh = listOf(cand("a", selected = true), cand("b", selected = true), cand("c", selected = true))
        assertEquals(listOf(false, true, true), keepSelections(fresh, previous).map { it.selected })
    }

    @Test
    fun `이미 가져왔는지가 바뀌면 켠 것은 옮기지 않는다`() {
        // 앞 읽기에서 서버 목록을 못 받아 새 일정처럼 켜졌던 것 → 바로잡힌 목록의 기본값(꺼짐)을 따른다
        val previous = listOf(cand("a", selected = true, imported = false))
        val fresh = listOf(cand("a", selected = false, imported = true))
        assertFalse(keepSelections(fresh, previous).single().selected)
    }

    @Test
    fun `고른 일정의 장소를 찾는 동안에는 가져오기를 막고 이유를 말한다`() {
        val waiting = CalendarImportState(candidates = listOf(cand("a", selected = true, resolving = true)))
        assertFalse(waiting.canImport)
        assertTrue(waiting.waitingNote!!.contains("장소를 찾고 있어요"))
        val unselected = CalendarImportState(candidates = listOf(cand("a", selected = false, resolving = true), cand("b", selected = true)))
        assertTrue(unselected.canImport)
        assertNull(unselected.waitingNote)
    }
}
