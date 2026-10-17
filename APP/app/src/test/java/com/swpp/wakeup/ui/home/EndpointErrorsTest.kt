// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.data.repository.failureFrom
import com.swpp.wakeup.domain.model.RouteOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 가까운 출발·도착(400) 같은 실패를 화면 어디에 둘지(A2-2).
 *
 * 규칙(50m)은 서버가 소유한다. 여기서는 서버가 붙인 필드 이름으로 줄만 고른다.
 */
class EndpointErrorsTest {

    private val message = "출발지와 도착지는 50m 이상 떨어져 있어야 한다."

    private fun failure(fields: Map<String, String>) =
        EventRepository.Result.Failure("서버 문구", fields)

    @Test
    fun `도착지 필드는 도착지 줄로 가고 공통 줄은 비운다`() {
        val errors = endpointErrorsOf(failure(mapOf("destination" to message)))

        assertEquals(message, errors.destination)
        assertNull(errors.origin)
        assertNull("같은 말을 두 번 하지 않는다", errors.general)
    }

    @Test
    fun `출발지 좌표 필드는 출발지 줄로 간다`() {
        assertEquals("a", endpointErrorsOf(failure(mapOf("origin_lat" to "a"))).origin)
        assertEquals("b", endpointErrorsOf(failure(mapOf("origin_lng" to "b"))).origin)
    }

    @Test
    fun `둘 다 있으면 둘 다 줄에 붙인다`() {
        val errors = endpointErrorsOf(failure(mapOf("origin_lat" to "a", "destination" to "b")))

        assertEquals("a", errors.origin)
        assertEquals("b", errors.destination)
        assertNull(errors.general)
    }

    @Test
    fun `줄에 붙일 것이 없으면 지금처럼 공통 줄이다`() {
        val errors = endpointErrorsOf(failure(mapOf("title" to "제목이 비었다.")))

        assertNull(errors.origin)
        assertNull(errors.destination)
        assertEquals("서버 문구", errors.general)
    }

    @Test
    fun `710545a 실제 본문이 도착지 줄로 간다`() {
        val body = """{"error":{"code":"invalid","message":"$message",""" +
            """"details":{"destination":["$message"]}}}"""
        val errors = endpointErrorsOf(failureFrom(400, body))

        assertEquals(message, errors.destination)
        assertNull(errors.general)
    }

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
