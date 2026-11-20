// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.alarm

import com.swpp.wakeup.domain.model.TripStage
import org.junit.Assert.assertEquals
import org.junit.Test

/** 위치 권한이 없으면 진행 카드가 추적을 약속하지 않는다(fix/iter4-he-bugs 이식, 11-19) */
class StageHintPermissionTest {

    @Test
    fun `권한이 없으면 아직 끝나지 않은 단계는 이유를 말한다`() {
        assertEquals("위치 권한이 없어 이동을 기록하지 않아요", stageHint(TripStage.BEFORE_ALARM, canTrack = false))
        assertEquals("정확한 위치 권한이 없어 이동을 기록하지 않아요", stageHint(TripStage.IN_TRANSIT, canTrack = false, coarseOnly = true))
    }

    @Test
    fun `기록과 상관없는 단계 · 권한이 있을 때는 그대로`() {
        assertEquals(stageHint(TripStage.ARRIVED), stageHint(TripStage.ARRIVED, canTrack = false))
        assertEquals(stageHint(TripStage.ALARM_OFF), stageHint(TripStage.ALARM_OFF, canTrack = false))
        assertEquals("알람이 울리면 이동 기록을 시작해요", stageHint(TripStage.BEFORE_ALARM))
    }
}
