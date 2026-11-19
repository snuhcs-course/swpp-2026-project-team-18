// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 저장한 집: 바꾸기 전 목록이 늦게 와서 덮지 않는다 · 이름은 이모지를 반으로 자르지 않는다(fix/iter4-he-bugs 이식, 11-19) */
class SavedHomesSyncRaceTest {

    @Test
    fun `받는 동안 기기에서 바꿨으면 그 목록은 버린다`() {
        assertTrue(savedHomesSyncApplies(startVersion = 2, currentVersion = 2))
        assertFalse(savedHomesSyncApplies(startVersion = 2, currentVersion = 3))
    }

    @Test
    fun `이름 한도는 코드 포인트로 자른다`() {
        val label = "가".repeat(SavedHomes.LABEL_MAX - 1) + "🏠🏠"
        val cut = SavedHomes.labelOf(label, "장소")
        assertEquals(SavedHomes.LABEL_MAX, cut.codePointLength())
        assertTrue("이모지가 반쪽만 남지 않는다", cut.endsWith("🏠"))
    }
}
