// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.EventRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 알람 결정 화면: 계획을 받지 못하면 끝없이 돌지 않고 이유를 보인다(fix/iter4-he-bugs 이식, 11-19) */
class AlarmDecisionRulesTest {

    @Test
    fun `통신 실패는 쉬운 말로 · 그 밖의 이유는 그대로`() {
        assertEquals(
            PLAN_LOAD_OFFLINE_REASON,
            planLoadReasonOf(EventRepository.Result.Failure("서버에 연결할 수 없다", network = true)),
        )
        assertEquals("알람이 아직 정해지지 않았어요", planLoadReasonOf(EventRepository.Result.Failure("알람이 아직 정해지지 않았어요")))
    }

    @Test
    fun `다른 일정의 실패 · 계획은 이 화면에 보이지 않는다`() {
        val error = PlanLoadError(eventId = 7, message = "이유")
        assertEquals("이유", error.messageFor(7))
        assertNull(error.messageFor(8))
        assertNull((null as PlanLoadError?).messageFor(7))
        assertNull((null as com.swpp.wakeup.domain.model.AlarmPlanView?).forEvent(7))
    }
}
