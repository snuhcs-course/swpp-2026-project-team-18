package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.domain.model.PlanRow
import com.swpp.wakeup.testing.FakeEventsApi
import com.swpp.wakeup.testing.FakeProfileApi
import org.junit.Assert.*
import org.junit.Test

class PlanEvidenceLabelTest {
    private fun rows(extra: String) = EventRepository(FakeEventsApi(), FakeProfileApi()).planFrom(
        Gson().fromJson("""{"id":1,"title":"수업","start_at":"2030-12-04T09:00:00+09:00",
            "alarm_plan":{"status":"ok","prep_minutes":28,"travel_minutes":42,"buffer_minutes":10,$extra}}""", EventDto::class.java),
    )!!.breakdown

    @Test fun `measured prep and corrected travel have separate concise evidence`() {
        val rows = rows(""""prep_source":"observed","travel_time_source":"kakao_transit+observed_route","travel_sample_count":12,"buffer_source":"learned"""")
        assertEquals("측정 · 준비 기록", rows.single { it.kind == PlanRow.Kind.PREP }.sourceLabel)
        assertEquals("고정 예상 · 기록 12회로 보정", rows.single { it.kind == PlanRow.Kind.TRAVEL }.sourceLabel)
        assertEquals("측정 · 늦은 기록으로 조정", rows.single { it.kind == PlanRow.Kind.BUFFER }.sourceLabel)
    }

    @Test fun `missing correction metadata is not reported as zero corrections`() {
        val row = rows(""""prep_source":"fixed"""").single { it.kind == PlanRow.Kind.TRAVEL }
        assertEquals("고정 예상 · 보정 횟수 미확인", row.sourceLabel)
    }

    @Test fun `uncorrected route says zero and fixed preparation remains fixed`() {
        val rows = rows(""""prep_source":"onboarding","travel_time_source":"kakao_walk"""")
        assertEquals("고정 · 설정한 준비 시간", rows.single { it.kind == PlanRow.Kind.PREP }.sourceLabel)
        assertEquals("고정 예상 · 보정 0회", rows.single { it.kind == PlanRow.Kind.TRAVEL }.sourceLabel)
    }
}
