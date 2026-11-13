// AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.domain.model.LearningReset.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 내 정보 › 기록 초기화: 서버 응답 → 사용자에게 하는 말. 지워지지 않았는데 지웠다고 말하면 안 된다 */
class LearningResetTest {

    @Test
    fun `2xx 만 지운 것으로 본다`() {
        assertEquals(Outcome.DONE, LearningReset.outcome(200))
        assertEquals(Outcome.DONE, LearningReset.outcome(204))
    }

    @Test
    fun `서버에 아직 없으면 지원하지 않는다고 말한다`() {
        listOf(404, 405, 501).forEach { assertEquals(Outcome.UNSUPPORTED, LearningReset.outcome(it)) }
        assertEquals("아직 서버에서 지원하지 않아요. 기록은 그대로예요", LearningReset.message(Outcome.UNSUPPORTED))
    }

    @Test
    fun `서버에 닿지 못하면 연결 문구`() {
        assertEquals(Outcome.OFFLINE, LearningReset.outcome(null))
        assertEquals("서버에 연결되지 않아 기록을 지우지 못했어요", LearningReset.message(Outcome.OFFLINE))
    }

    @Test
    fun `로그인이 끝났으면 다시 로그인하라고 말한다`() {
        assertEquals(Outcome.SESSION, LearningReset.outcome(401))
        assertTrue(LearningReset.message(Outcome.SESSION).contains("다시 로그인"))
    }

    @Test
    fun `그 밖의 실패는 다시 시도 문구`() {
        listOf(400, 403, 429, 500, 503).forEach { assertEquals(Outcome.FAILED, LearningReset.outcome(it)) }
        assertEquals("기록을 지우지 못했어요. 잠시 후 다시 시도해 주세요", LearningReset.message(Outcome.FAILED))
    }

    @Test
    fun `지웠다는 말은 성공에서만 나온다`() {
        Outcome.entries.filter { it != Outcome.DONE }.forEach {
            assertTrue(it.name, !LearningReset.message(it).contains("지웠어요"))
        }
        assertTrue(LearningReset.message(Outcome.DONE).contains("지웠어요"))
    }
}
