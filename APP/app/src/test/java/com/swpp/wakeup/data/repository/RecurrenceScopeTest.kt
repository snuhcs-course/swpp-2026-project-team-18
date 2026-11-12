// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 반복 일정 범위 선택(iter4 A2). 시트를 언제 띄우는지, 서버에 무엇을 싣는지.
 *
 * **구버전 서버에서는 "이후 일정 모두" 가 절대 보이면 안 된다.** 그 서버는 `scope` 를 모르고 무시해서,
 * 사용자는 앞으로의 일정을 다 바꿨다고 믿는데 실제로는 한 회차만 바뀐다.
 */
class RecurrenceScopeTest {

    // --- 서버 버전 ---------------------------------------------------------

    @Test
    fun `서버 버전을 모르거나 낮으면 범위 선택을 내놓지 않는다`() {
        assertFalse(RecurrenceScope.supported(null))
        assertFalse(RecurrenceScope.supported(""))
        assertFalse(RecurrenceScope.supported("dev"))
        // 지금 배포된 서버(0.20.0)는 범위를 모른다.
        assertFalse(RecurrenceScope.supported("0.20.0"))
        assertFalse(RecurrenceScope.supported("0.20.9"))
    }

    @Test
    fun `기준 버전 이상이면 범위 선택을 연다`() {
        assertTrue(RecurrenceScope.supported(RecurrenceScope.SINCE_SERVER))
        assertTrue(RecurrenceScope.supported("0.21.0"))
        assertTrue(RecurrenceScope.supported("0.21"))
        assertTrue(RecurrenceScope.supported("0.22.1"))
        assertTrue(RecurrenceScope.supported("1.0.0"))
        // 꼬리표는 떼고 본다.
        assertTrue(RecurrenceScope.supported("0.21.0-rc1"))
    }

    // --- 저장 시트 ---------------------------------------------------------

    @Test
    fun `반복이 아닌 일정은 시트 없이 바로 저장한다`() {
        assertEquals(emptyList<EventScope>(), RecurrenceScope.saveChoices(false, true, false))
        assertEquals(emptyList<EventScope>(), RecurrenceScope.saveChoices(false, true, true))
    }

    @Test
    fun `반복 회차는 이 일정만과 이후 일정 모두를 묻는다`() {
        assertEquals(
            listOf(EventScope.THIS, EventScope.FOLLOWING),
            RecurrenceScope.saveChoices(recurring = true, supported = true, weekdaysChanged = false),
        )
    }

    @Test
    fun `요일을 바꿨으면 이후 일정 모두만 남는다`() {
        assertEquals(
            listOf(EventScope.FOLLOWING),
            RecurrenceScope.saveChoices(recurring = true, supported = true, weekdaysChanged = true),
        )
    }

    @Test
    fun `구버전 서버면 반복 회차도 지금처럼 바로 저장한다`() {
        assertEquals(emptyList<EventScope>(), RecurrenceScope.saveChoices(true, false, false))
        assertEquals(emptyList<EventScope>(), RecurrenceScope.saveChoices(true, false, true))
    }

    // --- 삭제 시트 ---------------------------------------------------------

    @Test
    fun `삭제 시트는 반복 회차이고 서버가 받을 때만 뜬다`() {
        assertEquals(
            listOf(EventScope.THIS, EventScope.FOLLOWING),
            RecurrenceScope.deleteChoices(recurring = true, supported = true),
        )
        assertEquals(emptyList<EventScope>(), RecurrenceScope.deleteChoices(recurring = false, supported = true))
        assertEquals(emptyList<EventScope>(), RecurrenceScope.deleteChoices(recurring = true, supported = false))
    }

    // --- 본문 --------------------------------------------------------------

    private fun patch(): JsonObject = JsonParser.parseString("""{"title":"수업"}""").asJsonObject

    @Test
    fun `이 일정만은 지금 본문 그대로 보낸다`() {
        assertEquals("""{"title":"수업"}""", RecurrenceScope.withScope(patch(), EventScope.THIS).toString())
        assertEquals("""{"title":"수업"}""", RecurrenceScope.withScope(patch(), null).toString())
        // 요일은 "이 일정만" 에 싣지 않는다.
        assertEquals(
            """{"title":"수업"}""",
            RecurrenceScope.withScope(patch(), EventScope.THIS, setOf(1, 3)).toString(),
        )
    }

    @Test
    fun `이후 일정 모두는 scope 를 싣는다`() {
        assertEquals(
            """{"title":"수업","scope":"following"}""",
            RecurrenceScope.withScope(patch(), EventScope.FOLLOWING).toString(),
        )
    }

    @Test
    fun `요일은 오름차순으로 싣는다`() {
        assertEquals(
            """{"title":"수업","scope":"following","repeat_weekdays":[1,3]}""",
            RecurrenceScope.withScope(patch(), EventScope.FOLLOWING, setOf(3, 1)).toString(),
        )
    }

    @Test
    fun `받은 본문은 바꾸지 않는다`() {
        val original = patch()
        RecurrenceScope.withScope(original, EventScope.FOLLOWING, setOf(0))
        assertEquals("""{"title":"수업"}""", original.toString())
    }

    @Test
    fun `서버에 보내는 값`() {
        assertEquals("this", EventScope.THIS.wire)
        assertEquals("following", EventScope.FOLLOWING.wire)
    }
}
