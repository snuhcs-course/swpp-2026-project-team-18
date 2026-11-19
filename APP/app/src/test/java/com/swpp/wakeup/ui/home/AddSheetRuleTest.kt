// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.domain.model.SavedHome
import com.swpp.wakeup.ui.events.ADD_PENDING_BUTTON
import com.swpp.wakeup.ui.events.OriginPick
import com.swpp.wakeup.ui.events.addPendingNote
import com.swpp.wakeup.ui.events.originPickOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 알람 추가 바텀시트(H2-8 `545:192`)를 열고 닫는 규칙과 "알람 정하는 중"(`546:766`) */
class AddSheetRuleTest {

    private val place = PlaceSearchItem(
        kakaoPlaceId = "1", name = "서울대 301동", address = "서울 관악구", lat = 37.45, lng = 126.95, category = "",
    )
    private val blank = HomeViewModel.AddState()

    @Test
    fun `빈 폼이면 새로 열고 쓰다 만 일정이 있으면 이어 쓸지 묻는다`() {
        assertEquals(AddSheetRule.OnAdd.OPEN_NEW, AddSheetRule.onAdd(blank, null))
        // 시트를 닫아도 입력은 남는다. 다음 "일정 추가" 는 이어 쓸지 묻는다(A4).
        val closedWithInput = blank.copy(selectedPlace = place)
        assertEquals(AddSheetRule.OnAdd.ASK_RESUME, AddSheetRule.onAdd(closedWithInput, null))
        // 수정 화면을 여느라 맡겨 둔 초안도 같다.
        assertEquals(AddSheetRule.OnAdd.ASK_RESUME, AddSheetRule.onAdd(blank, closedWithInput))
    }

    @Test
    fun `닫아 둔 채 저장 중이면 이어 쓰기를 묻지 않고 그 시트를 다시 연다`() {
        val pending = blank.copy(selectedPlace = place, submitting = true, submitSlow = true)
        assertEquals(AddSheetRule.OnAdd.SHOW_PENDING, AddSheetRule.onAdd(pending, null))
    }

    @Test
    fun `저장을 기다리는 10초 동안은 닫히지 않고 그 뒤에는 닫힌다`() {
        assertTrue(AddSheetRule.canDismiss(blank))
        assertFalse(AddSheetRule.canDismiss(blank.copy(submitting = true)))
        assertTrue(AddSheetRule.canDismiss(blank.copy(submitting = true, submitSlow = true)))
    }

    @Test
    fun `새 상태는 느림 표시가 꺼져 있다`() {
        assertFalse(blank.submitSlow)
    }

    @Test
    fun `알람 정하는 중 문구`() {
        assertEquals("알람 시각을 정하고 있어요…", ADD_PENDING_BUTTON)
        assertEquals("잠시만 기다려 주세요. 길과 준비 시간을 확인하고 있어요", addPendingNote(false))
        assertEquals("조금 더 걸리고 있어요. 닫아도 알람은 정해지는 대로 맞춰 둘게요", addPendingNote(true))
    }

    @Test
    fun `출발지 변경 시트는 지금 고른 줄을 안다`() {
        val home = SavedHome(id = "h1", label = "본가", name = "본가", address = "수원", lat = 37.26, lng = 127.03)
        assertEquals(OriginPick.HOME to null, originPickOf(null, listOf(home)))
        val atSaved = place.copy(lat = 37.26, lng = 127.03, name = "본가")
        assertEquals(OriginPick.SAVED to "h1", originPickOf(atSaved, listOf(home)))
        val (pick, id) = originPickOf(place, listOf(home))
        assertEquals(OriginPick.OTHER, pick)
        assertNull(id)
    }
}
