// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.data.repository.EventScope
import com.swpp.wakeup.ui.home.HomeViewModel.AddState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 반복 일정 범위 시트(iter4 A2)의 화면 쪽 규칙. 시트는 반복 회차를 고칠 때만 뜨고, 요일 칸은 범위 수정을
 * 받는 서버에서만 열린다.
 */
class RecurrenceScopeUiTest {

    private val recurringEdit = AddState(
        title = "수업",
        editingEventId = 1,
        recurring = true,
        repeatWeekdays = setOf(0, 2),
        baseWeekdays = setOf(0, 2),
        scopeSupported = true,
    )

    @Test
    fun `반복 회차 수정에서만 저장 시트 선택지가 있다`() {
        assertEquals(listOf(EventScope.THIS, EventScope.FOLLOWING), recurringEdit.saveScopeChoices)
        // 반복이 아닌 일정 수정
        assertEquals(emptyList<EventScope>(), recurringEdit.copy(recurring = false).saveScopeChoices)
        // 새 반복 일정 만들기(수정이 아님)
        assertEquals(emptyList<EventScope>(), recurringEdit.copy(editingEventId = null).saveScopeChoices)
        // 구버전 서버
        assertEquals(emptyList<EventScope>(), recurringEdit.copy(scopeSupported = false).saveScopeChoices)
    }

    @Test
    fun `요일을 바꾸면 이후 일정 모두만 남고 되돌리면 둘 다 보인다`() {
        val changed = recurringEdit.copy(repeatWeekdays = setOf(1, 3))
        assertTrue(changed.weekdaysChanged)
        assertEquals(listOf(EventScope.FOLLOWING), changed.saveScopeChoices)

        val back = changed.copy(repeatWeekdays = setOf(2, 0))
        assertFalse(back.weekdaysChanged)
        assertEquals(listOf(EventScope.THIS, EventScope.FOLLOWING), back.saveScopeChoices)
    }

    @Test
    fun `요일 칸은 범위 수정을 받는 서버의 반복 회차에서만 열린다`() {
        assertTrue(recurringEdit.canEditWeekdays)
        assertFalse(recurringEdit.copy(scopeSupported = false).canEditWeekdays)
        assertFalse(recurringEdit.copy(recurring = false).canEditWeekdays)
    }

    @Test
    fun `편집 안내 문구`() {
        assertEquals(
            "저장할 때 이 일정만 / 이후 일정 모두 중에 고를 수 있어요. 요일을 바꾸면 이후 일정에 적용돼요",
            editNoteOf(recurringEdit),
        )
        // 구버전 서버에서는 지금처럼 이 회차만 바뀐다고 말한다.
        assertEquals("반복 일정의 이 회차만 바뀜. 요일은 고칠 수 없음", editNoteOf(recurringEdit.copy(scopeSupported = false)))
        assertEquals(
            "바꾼 항목만 저장함. 장소·출발지를 바꾸면 경로를 다시 고름",
            editNoteOf(recurringEdit.copy(recurring = false)),
        )
    }

    @Test
    fun `시트 줄 이름`() {
        assertEquals("이 일정만 저장", scopeLabel(EventScope.THIS, ScopeAction.SAVE))
        assertEquals("이후 일정 모두 저장", scopeLabel(EventScope.FOLLOWING, ScopeAction.SAVE))
        assertEquals("이 일정만 삭제", scopeLabel(EventScope.THIS, ScopeAction.DELETE))
        assertEquals("이후 일정 모두 삭제", scopeLabel(EventScope.FOLLOWING, ScopeAction.DELETE))
    }
}
