// AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.AlarmPlanDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 확률이 없을 때의 문구(A3-2). 서버 동작과 맞아야 한다.
 *
 * - "학습 중" 을 쓰지 않는다. 숫자도 만들지 않는다.
 * - 이동 변동성은 이동 기록이 쌓여(전체 사용자 보정, 학습 배치) 생긴다.
 *   "같은 경로를 몇 번 다니면" 은 서버 동작과 다르다.
 * - `travel_time_source` 에 `+observed_*` 가 있으면 소요시간은 보정됐지만 변동 폭은 없다.
 *
 * DTO 는 Gson 으로 만든다. 손으로 만들면 키가 없을 때의 null 을 검증하지 못한다.
 */
class ConfidenceCopyTest {

    private val gson = com.google.gson.Gson()

    private fun confidence(basis: String?, source: String?, probability: Int? = null) =
        gson.fromJson(
            buildString {
                append("""{"status":"ok"""")
                basis?.let { append(""","confidence_basis":"$it"""") }
                source?.let { append(""","travel_time_source":"$it"""") }
                probability?.let { append(""","on_time_probability":$it""") }
                append("}")
            },
            AlarmPlanDto::class.java,
        ).toConfidence()

    @Test
    fun `확률이 없으면 숫자도 이유 문구도 만들지 않는다`() {
        // 11-17 사용자: "아직 계산할 수 없어요 · …" 이유 · 할 일 문구를 빼 달라고 함 — 자리만 비운다
        listOf("point_estimate", "travel_variance_unknown", "prep_variance_unknown", "travel_only", null).forEach { basis ->
            val view = confidence(basis, "kakao_transit")
            assertNull(view.percent)
            assertTrue(view.isUnavailable)
        }
    }

    @Test
    fun `확률이 있으면 그 값만 쓴다`() {
        val view = confidence("observed", "kakao_transit", probability = 92)
        assertEquals(92, view.percent)
        assertEquals("정시 도착 확률 92%", view.headline)
    }
}
