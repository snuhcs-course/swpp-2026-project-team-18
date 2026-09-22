package com.swpp.wakeup.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * refresh 결과 분류.
 *
 * ## 이 테스트가 지키는 것
 *
 * **네트워크가 끊긴 것을 세션 만료로 오인하면 안 된다.** 예전 코드는
 * `runCatching { ... }.getOrNull()` 로 모든 실패를 한 종류로 보고 토큰을 지웠다.
 * 그래서 지하철에서 앱을 열었다는 이유로 로그아웃됐다. 아침 출근길에 쓰는 앱에서
 * 이건 기능이 없는 것과 같다.
 *
 * 반대 방향도 지켜야 한다. 서버가 진짜로 거절했는데 토큰을 들고 있으면 사용자가
 * 로그인 화면으로 갈 길을 못 찾고 "다시 로그인해야 한다" 만 반복해서 본다.
 *
 * 애매한 경우는 **유지 쪽으로** 기울인다. 잘못 지우면 재로그인이 필요하고, 잘못
 * 유지하면 다음 요청이 401 을 한 번 더 받을 뿐이다.
 */
class RefreshOutcomeTest {

    // --- 갱신 성공 ---------------------------------------------------------

    @Test
    fun `200 과 토큰이 오면 갱신이다`() {
        val outcome = RefreshOutcome.of(code = 200, access = "new-token")
        assertEquals(RefreshOutcome.Renewed("new-token"), outcome)
    }

    @Test
    fun `2xx 전체를 성공으로 본다`() {
        assertTrue(RefreshOutcome.of(201, "t") is RefreshOutcome.Renewed)
        assertTrue(RefreshOutcome.of(299, "t") is RefreshOutcome.Renewed)
    }

    // --- 토큰을 지워야 하는 경우 --------------------------------------------

    @Test
    fun `401 은 거절이다`() {
        // refresh 14일이 지난 경우. 다시 로그인해야 한다.
        assertTrue(RefreshOutcome.of(401, null) is RefreshOutcome.Rejected)
    }

    @Test
    fun `400 과 403 도 거절이다`() {
        // SimpleJWT 는 무효 토큰에 401 을 주지만 400 을 주는 구성도 있다.
        assertTrue(RefreshOutcome.of(400, null) is RefreshOutcome.Rejected)
        assertTrue(RefreshOutcome.of(403, null) is RefreshOutcome.Rejected)
    }

    // --- 토큰을 지키는 경우 (이쪽이 핵심) ------------------------------------

    @Test
    fun `네트워크가 끊기면 토큰을 지키지 않고 보류한다`() {
        // 이 테스트가 이 파일의 이유다. 여기서 Rejected 가 나오면 지하철에서
        // 로그아웃된다.
        val cases = listOf(
            IOException("broken pipe"),
            SocketTimeoutException("timeout"),
            UnknownHostException("no dns"),
        )
        cases.forEach { error ->
            val outcome = RefreshOutcome.of(code = null, access = null, error = error)
            assertTrue(
                "${error.javaClass.simpleName} 으로 토큰을 지우면 안 된다: $outcome",
                outcome is RefreshOutcome.Undecided,
            )
        }
    }

    @Test
    fun `5xx 는 서버 문제이므로 보류한다`() {
        // Render 무료 플랜이 깨어나는 중이거나 배포가 도는 중일 수 있다.
        // 토큰은 멀쩡하다.
        listOf(500, 502, 503, 504).forEach { code ->
            assertTrue(
                "$code 로 토큰을 지우면 배포마다 전원 로그아웃된다",
                RefreshOutcome.of(code, null) is RefreshOutcome.Undecided,
            )
        }
    }

    @Test
    fun `200 인데 토큰이 없으면 보류한다`() {
        // 서버 계약이 깨진 것이라 재로그인으로 몰 근거가 안 된다.
        assertTrue(RefreshOutcome.of(200, null) is RefreshOutcome.Undecided)
        assertTrue(RefreshOutcome.of(200, "") is RefreshOutcome.Undecided)
        assertTrue(RefreshOutcome.of(200, "   ") is RefreshOutcome.Undecided)
    }

    @Test
    fun `응답을 못 받았으면 보류한다`() {
        assertTrue(RefreshOutcome.of(code = null, access = null) is RefreshOutcome.Undecided)
    }

    @Test
    fun `예상하지 못한 예외도 보류한다`() {
        // 직렬화 오류 등. 서버의 판단이 아니므로 지우지 않는다.
        val outcome = RefreshOutcome.of(null, null, IllegalStateException("boom"))
        assertTrue(outcome is RefreshOutcome.Undecided)
    }

    @Test
    fun `404 나 429 도 보류한다`() {
        // 경로가 바뀌었거나 스로틀에 걸린 것이다. 세션 만료가 아니다.
        assertTrue(RefreshOutcome.of(404, null) is RefreshOutcome.Undecided)
        assertTrue(RefreshOutcome.of(429, null) is RefreshOutcome.Undecided)
    }

    // --- 이유 문구 ---------------------------------------------------------

    @Test
    fun `보류와 거절에 이유가 담긴다`() {
        // 로그에서 "왜 로그아웃됐나" 를 추적할 수 있어야 한다.
        val rejected = RefreshOutcome.of(401, null) as RefreshOutcome.Rejected
        assertTrue(rejected.reason.contains("401"))

        val undecided = RefreshOutcome.of(null, null, IOException("x")) as RefreshOutcome.Undecided
        assertTrue(undecided.reason.contains("네트워크"))
    }
}
