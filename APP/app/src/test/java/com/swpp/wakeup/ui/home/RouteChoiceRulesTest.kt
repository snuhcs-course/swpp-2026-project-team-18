// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.RouteOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 경로 후보 실패의 다시 시도 여부와 미리보기 정리.
 *
 * 규칙(50m)은 서버가 소유한다. 여기서는 서버가 붙인 필드 이름으로 줄만 고른다.
 */
class RouteChoiceRulesTest {

    private val message = "출발지와 도착지는 50m 이상 떨어져 있어야 한다."

    private fun failure(fields: Map<String, String>) =
        EventRepository.Result.Failure("서버 문구", fields)

    @Test
    fun `가까운 출발·도착은 다시 시도로 풀리지 않는다`() {
        assertFalse(candidatesRetryable(failure(mapOf("destination" to message))))
        assertTrue("네트워크·5xx 등은 지금처럼 다시 시도", candidatesRetryable(failure(emptyMap())))
    }

    @Test
    fun `다시 받은 목록에 없는 후보의 미리보기는 닫는다`() {
        val walk = RouteOption("walk", "도보", 18, "18분", "1.2km", null)
        val car = RouteOption("car", "자동차", 9, "9분", "4.3km", null)

        assertEquals("walk", previewKeyAfterReload("walk", listOf(walk, car)))
        assertNull(previewKeyAfterReload("bicycle", listOf(walk, car)))
        assertNull(previewKeyAfterReload(null, listOf(walk)))
    }
}
