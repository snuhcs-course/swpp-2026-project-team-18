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

    private val gson = Gson()

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

    private val plain = "kakao_transit"
    private val corrected = "kakao_transit+observed_global"

    /** basis × 출처 10칸. 기대 문자열은 phaseA-design.md "Review resolutions" 표와 같다. */
    private val table = listOf(
        Triple("point_estimate", plain, "준비·이동 둘 다 단일 추정값이라 분포가 없음") to
            "루틴 블록에 최소~최대 범위를 넣으면 준비 시간 변동이 반영됨",
        Triple("point_estimate", corrected, "준비·이동 둘 다 단일 추정값이라 분포가 없음") to
            "루틴 블록에 최소~최대 범위를 넣으면 준비 시간 변동이 반영됨",
        Triple("travel_variance_unknown", plain, "이동 시간은 경로 조회값 하나뿐임") to
            "이동 기록이 쌓이면 변동성이 생김",
        Triple("travel_variance_unknown", corrected, "이동 시간은 기록으로 보정됐지만 변동 폭은 아직 모름") to
            "이동 기록이 쌓이면 변동성이 생김",
        Triple("prep_variance_unknown", plain, "이동 시간은 변동 폭이 있지만 준비 시간이 고정값임") to
            "루틴 블록에 최소~최대 범위를 넣으면 됨",
        Triple("prep_variance_unknown", corrected, "이동 시간은 변동 폭이 있지만 준비 시간이 고정값임") to
            "루틴 블록에 최소~최대 범위를 넣으면 됨",
        Triple("travel_only", plain, "준비 단계가 없는 일정이고 이동 시간은 경로 조회값 하나뿐임") to
            "이동 기록이 쌓이면 변동성이 생김",
        Triple(
            "travel_only", corrected,
            "준비 단계가 없는 일정이고 이동 시간은 기록으로 보정됐지만 변동 폭은 아직 모름",
        ) to "이동 기록이 쌓이면 변동성이 생김",
        Triple("observed", plain, "아직 확률을 계산할 근거가 부족함") to null,
        Triple("observed", corrected, "아직 확률을 계산할 근거가 부족함") to null,
    )

    @Test
    fun `확률이 없을 때 이유와 할 일이 basis 와 출처별 표와 같다`() {
        table.forEach { (key, action) ->
            val (basis, source, reason) = key
            val view = confidence(basis, source)

            assertNull("$basis/$source: 숫자를 만들면 안 된다", view.percent)
            assertTrue(view.isUnavailable)
            assertEquals("$basis/$source 이유", reason, view.reason)
            assertEquals("$basis/$source 할 일", action, view.action)
        }
    }

    @Test
    fun `제목에 학습 중이 없다`() {
        table.forEach { (key, _) ->
            val headline = confidence(key.first, key.second).headline
            assertEquals("정시 도착 확률 · 아직 계산할 수 없음", headline)
            assertFalse(headline.contains("학습"))
        }
    }

    @Test
    fun `같은 경로를 여러 번 다니라고 하지 않는다`() {
        table.forEach { (key, _) ->
            val view = confidence(key.first, key.second)
            listOfNotNull(view.reason, view.action).forEach {
                assertFalse("서버 보정은 전체 이동 기록에서 나온다: $it", it.contains("같은 경로"))
            }
        }
    }

    @Test
    fun `basis 와 출처가 없는 구버전 응답도 터지지 않는다`() {
        val view = confidence(basis = null, source = null)

        assertNull(view.percent)
        assertEquals("아직 확률을 계산할 근거가 부족함", view.reason)
        assertNull(view.action)
    }

    @Test
    fun `확률이 있으면 그 값만 쓴다`() {
        val view = confidence("observed", plain, probability = 92)

        assertEquals(92, view.percent)
        assertEquals("정시 도착 확률 92%", view.headline)
        assertNull(view.reason)
        assertNull(view.action)
    }
}
