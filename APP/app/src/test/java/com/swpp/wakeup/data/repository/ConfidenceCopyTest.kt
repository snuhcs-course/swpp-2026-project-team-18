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
        Triple("point_estimate", plain, "준비·이동 둘 다 한 가지 값뿐이라 얼마나 달라지는지 알 수 없어요") to
            "루틴 항목에 최소~최대 범위를 넣으면 준비 시간이 달라지는 폭도 계산해요",
        Triple("point_estimate", corrected, "준비·이동 둘 다 한 가지 값뿐이라 얼마나 달라지는지 알 수 없어요") to
            "루틴 항목에 최소~최대 범위를 넣으면 준비 시간이 달라지는 폭도 계산해요",
        Triple("travel_variance_unknown", plain, "이동 시간은 경로 예상값 하나뿐이에요") to
            "이동 기록이 쌓이면 계산할 수 있어요",
        Triple("travel_variance_unknown", corrected, "이동 시간은 기록으로 보정됐지만 변동 폭은 아직 모름") to
            "이동 기록이 쌓이면 계산할 수 있어요",
        Triple("prep_variance_unknown", plain, "이동 시간은 달라지는 폭이 있지만 준비 시간이 한 가지 값이에요") to
            "루틴 블록에 최소~최대 범위를 넣으면 됨",
        Triple("prep_variance_unknown", corrected, "이동 시간은 달라지는 폭이 있지만 준비 시간이 한 가지 값이에요") to
            "루틴 블록에 최소~최대 범위를 넣으면 됨",
        Triple("travel_only", plain, "준비 단계가 없는 일정이고 이동 시간은 경로 예상값 하나뿐이에요") to
            "이동 기록이 쌓이면 계산할 수 있어요",
        Triple(
            "travel_only", corrected,
            "준비 단계가 없는 일정이고 이동 시간은 기록으로 보정됐지만 변동 폭은 아직 모름",
        ) to "이동 기록이 쌓이면 계산할 수 있어요",
        Triple("observed", plain, "아직 확률을 계산할 근거가 부족해요") to null,
        Triple("observed", corrected, "아직 확률을 계산할 근거가 부족해요") to null,
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
            assertEquals("정시 도착 확률 · 아직 계산할 수 없어요", headline)
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
        assertEquals("아직 확률을 계산할 근거가 부족해요", view.reason)
        assertNull(view.action)
    }

    @Test
    fun `확률이 있으면 그 값만 쓴다`() {
        val view = confidence("observed", plain, probability = 92)

        assertEquals(92, view.percent)
        // 보이는 숫자는 90 을 넘지 않는다(사용자 결정 11-16). 판단에 쓰는 percent 는 실제 값이다
        assertEquals("정시 도착 확률 90%", view.headline)
        assertNull(view.reason)
        assertNull(view.action)
    }
}
