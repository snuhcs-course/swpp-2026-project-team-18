// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.data.repository.failureFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 일정 저장 실패를 줄·빨간 줄·snackbar 로 나누는 규칙(A1-A3, A7).
 * 본문은 서버 `api_exception_handler` 가 만드는 모양 그대로 [failureFrom] 에 넣는다.
 */
class SaveErrorsTest {
    private fun invalid(vararg details: Pair<String, String>, message: String = "서버 문구"): EventRepository.Result.Failure {
        val d = details.joinToString(",") { (k, v) -> "\"$k\":[\"$v\"]" }
        return failureFrom(400, """{"error":{"code":"invalid","message":"$message","details":{$d}}}""")
    }

    @Test
    fun `400 필드 키는 각자의 줄로 가고 빨간 줄은 비운다`() {
        val e = saveErrorOf(
            invalid(
                "title" to "제목을 입력해야 한다.",
                "start_at" to "시각 형식이 맞지 않다.",
                "tag_key" to "알 수 없는 종류다.",
                "route_key" to "경로 형식이 맞지 않다.",
                "origin_lat" to "국내 좌표만 쓸 수 있다.",
                "destination" to "50m 이상 떨어져야 한다.",
            ),
            editing = false,
        )
        assertEquals(SaveErrorKind.INVALID, e.kind)
        assertEquals("제목을 입력해야 한다.", e.fields[AddField.TITLE])
        assertEquals("시각 형식이 맞지 않다.", e.fields[AddField.TIME])
        assertEquals("알 수 없는 종류다.", e.fields[AddField.TAG])
        assertEquals("경로 형식이 맞지 않다.", e.fields[AddField.ROUTE])
        assertEquals("국내 좌표만 쓸 수 있다.", e.fields[AddField.ORIGIN])
        assertEquals("50m 이상 떨어져야 한다.", e.fields[AddField.DESTINATION])
        assertNull("같은 말을 두 번 하지 않는다", e.line)
        assertFalse("입력을 고쳐야 풀린다", e.retry)
        assertEquals("일정을 저장하지 못했어요. 표시한 칸을 확인해 주세요", e.snackbar)
    }

    @Test
    fun `origin_place_id 와 origin_label 과 origin_lng 는 출발지 줄이다`() {
        assertEquals("x", saveErrorOf(invalid("origin_place_id" to "x"), false).fields[AddField.ORIGIN])
        assertEquals("y", saveErrorOf(invalid("origin_label" to "y"), false).fields[AddField.ORIGIN])
        assertEquals("z", saveErrorOf(invalid("origin_lng" to "z"), false).fields[AddField.ORIGIN])
    }

    @Test
    fun `같은 줄에 키가 둘이면 위의 것이 이긴다`() {
        val e = saveErrorOf(invalid("origin_lng" to "b", "origin_lat" to "a"), false)
        assertEquals("a", e.fields[AddField.ORIGIN])
    }

    @Test
    fun `도착지 집 오류도 도착지 줄이다`() {
        val e = saveErrorOf(invalid("destination_is_home" to "집 주소를 설정해야 한다."), false)
        assertEquals("집 주소를 설정해야 한다.", e.fields[AddField.DESTINATION])
    }

    @Test
    fun `줄이 없는 키는 빨간 줄로 가고 줄이 있는 키는 줄에 남는다`() {
        val e = saveErrorOf(
            invalid("include_prep" to "올바른 값이 아니다.", "title" to "제목을 입력해야 한다."),
            editing = false,
        )
        assertEquals("제목을 입력해야 한다.", e.fields[AddField.TITLE])
        assertEquals("올바른 값이 아니다.", e.line)
    }

    @Test
    fun `반복 요일 오류는 줄이 없어 빨간 줄이다`() {
        val e = saveErrorOf(invalid("repeat_weekdays" to "요일은 0-6 이다."), editing = true)
        assertTrue(e.fields.isEmpty())
        assertEquals("요일은 0-6 이다.", e.line)
    }

    @Test
    fun `details 가 비었거나 중첩이면 서버 message 가 빨간 줄이다`() {
        val nested = failureFrom(
            400,
            """{"error":{"code":"invalid","message":"장소 좌표가 올바르지 않다.","details":{"place":{"lat":["범위 밖"]}}}}""",
        )
        val e = saveErrorOf(nested, editing = false)
        assertTrue(e.fields.isEmpty())
        assertEquals("장소 좌표가 올바르지 않다.", e.line)
        assertEquals("입력값을 확인해야 한다.", saveErrorOf(failureFrom(400, null), false).line)
    }

    @Test
    fun `통신 실패는 다시 시도를 둔다`() {
        val e = saveErrorOf(EventRepository.Result.Failure("연결 실패", network = true), editing = false)
        assertEquals(SaveErrorKind.NETWORK, e.kind)
        assertEquals("서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요", e.line)
        assertTrue(e.retry)
        assertEquals("일정을 저장하지 못했어요. 입력한 내용은 그대로 있어요", e.snackbar)
    }

    @Test
    fun `401 은 로그인 안내이고 다시 시도는 없다`() {
        val e = saveErrorOf(failureFrom(401, """{"error":{"code":"not_authenticated","message":"x","details":{}}}"""), false)
        assertEquals(SaveErrorKind.AUTH, e.kind)
        assertEquals("로그인이 끝났어요. 다시 로그인하면 입력한 내용이 그대로 있어요", e.line)
        assertFalse(e.retry)
    }

    @Test
    fun `404 는 수정이면 일정이 없는 것이고 추가면 서버가 모르는 것이다`() {
        val gone = saveErrorOf(failureFrom(404, null), editing = true)
        assertEquals(SaveErrorKind.GONE, gone.kind)
        assertEquals("이 일정을 찾지 못했어요. 다른 기기에서 지웠을 수 있어요", gone.line)
        assertFalse(gone.retry)

        val unsupported = saveErrorOf(failureFrom(404, null), editing = false)
        assertEquals(SaveErrorKind.UNSUPPORTED, unsupported.kind)
        assertEquals("아직 서버에서 지원하지 않아요", unsupported.line)
        assertEquals(SaveErrorKind.UNSUPPORTED, saveErrorOf(failureFrom(405, null), false).kind)
    }

    @Test
    fun `429 는 잠시 뒤 다시 시도`() {
        val e = saveErrorOf(failureFrom(429, null), editing = false)
        assertEquals(SaveErrorKind.SERVER, e.kind)
        assertEquals("요청이 너무 잦아요. 잠시 뒤 다시 시도해 주세요", e.line)
        assertTrue(e.retry)
    }

    @Test
    fun `500 JSON 은 서버 문구를 쓰고 다시 시도를 둔다`() {
        val body = """{"error":{"code":"server_error","message":"일정을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.","details":{}}}"""
        val e = saveErrorOf(failureFrom(500, body), editing = false)
        assertEquals(SaveErrorKind.SERVER, e.kind)
        assertEquals("일정을 만들지 못했어요. 잠시 뒤 다시 시도해 주세요.", e.line)
        assertTrue(e.retry)
    }

    @Test
    fun `500 HTML 은 일반 문구다`() {
        val e = saveErrorOf(failureFrom(500, "<html><body>Server Error (500)</body></html>"), editing = false)
        assertEquals("서버에 문제가 생겼어요. 잠시 뒤 다시 시도해 주세요", e.line)
        assertTrue(e.retry)
    }

    @Test
    fun `상태도 통신 실패 표시도 없는 실패는 일반 서버 문구다`() {
        val e = saveErrorOf(EventRepository.Result.Failure("알 수 없는 오류가 발생했다."), editing = false)
        assertEquals(SaveErrorKind.SERVER, e.kind)
        assertEquals("서버에 문제가 생겼어요. 잠시 뒤 다시 시도해 주세요", e.line)
    }

    @Test
    fun `수정이면 문구가 일정을 고치지 못했어요 로 바뀐다`() {
        val network = saveErrorOf(EventRepository.Result.Failure("x", network = true), editing = true)
        assertEquals("일정을 고치지 못했어요. 입력한 내용은 그대로 있어요", network.snackbar)
        assertEquals("일정을 고치지 못했어요. 표시한 칸을 확인해 주세요", saveErrorOf(invalid("title" to "a"), true).snackbar)
    }
}
