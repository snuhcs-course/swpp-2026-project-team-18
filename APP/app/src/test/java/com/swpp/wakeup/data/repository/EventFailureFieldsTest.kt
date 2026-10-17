// AI-generated (Claude)
package com.swpp.wakeup.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실패 응답 → [EventRepository.Result.Failure] (메시지 + 필드별 오류).
 *
 * 가까운 출발·도착(710545a)은 400 에 `details.destination` 을 붙인다. 앱이 그 필드를
 * 잃으면 도착지 줄 대신 맨 아래 공통 줄에 뜨고, 경로 선택에서는 "다시 시도" 만 반복된다.
 * 본문은 서버 `api_exception_handler` 가 만드는 모양 그대로다.
 */
class EventFailureFieldsTest {

    private val nearbyBody =
        """{"error":{"code":"invalid","message":"출발지와 도착지는 50m 이상 떨어져 있어야 한다.",""" +
            """"details":{"destination":["출발지와 도착지는 50m 이상 떨어져 있어야 한다."]}}}"""

    @Test
    fun `가까운 출발·도착 400 은 도착지 필드 오류를 살린다`() {
        val failure = failureFrom(400, nearbyBody)

        assertEquals("출발지와 도착지는 50m 이상 떨어져 있어야 한다.", failure.message)
        assertEquals("출발지와 도착지는 50m 이상 떨어져 있어야 한다.", failure.fields["destination"])
    }

    @Test
    fun `출발지 좌표 오류는 문자열 값으로도 온다`() {
        val body = """{"error":{"code":"invalid","message":"국내 좌표만 출발지로 쓸 수 있다.",""" +
            """"details":{"origin_lat":"국내 좌표만 출발지로 쓸 수 있다."}}}"""

        assertEquals("국내 좌표만 출발지로 쓸 수 있다.", failureFrom(400, body).fields["origin_lat"])
    }

    @Test
    fun `details 가 비면 필드 오류도 없다`() {
        val body = """{"error":{"code":"invalid_destination","message":"좌표 범위를 벗어났다.","details":{}}}"""
        val failure = failureFrom(400, body)

        assertEquals("좌표 범위를 벗어났다.", failure.message)
        assertTrue(failure.fields.isEmpty())
    }

    @Test
    fun `JSON 이 아닌 본문은 상태 코드별 문구로 떨어진다`() {
        val html = "<html><body>502 Bad Gateway</body></html>"

        assertEquals("서버에 문제가 생겼다.", failureFrom(502, html).message)
        assertTrue(failureFrom(502, html).fields.isEmpty())
        assertEquals("입력값을 확인해야 한다.", failureFrom(400, null).message)
    }

    @Test
    fun `429 는 서버 문구가 없으면 잠시 뒤 다시 시도하라고 한다`() {
        assertEquals("요청이 너무 잦음. 잠시 뒤 다시 시도", failureFrom(429, "").message)
    }

    @Test
    fun `서버 문구가 있으면 상태 코드 문구보다 앞선다`() {
        val body = """{"error":{"code":"throttled","message":"요청이 많다.","details":{}}}"""

        assertEquals("요청이 많다.", failureFrom(429, body).message)
    }
}
