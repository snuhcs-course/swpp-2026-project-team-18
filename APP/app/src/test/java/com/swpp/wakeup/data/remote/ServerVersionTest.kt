package com.swpp.wakeup.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 앱·서버 버전 비교.
 *
 * **틀린 경고가 없는 경고보다 나쁘다.** 한 번 거짓 경고를 보면 다음 진짜 경고도
 * 무시된다. 그래서 흔한 실수 두 가지를 고정한다 — 문자열 비교(`"0.10.0" <
 * "0.9.0"`)와 자리 수 차이(`0.3` vs `0.3.0`).
 */
class ServerVersionTest {

    // --- 본래 목적 ---------------------------------------------------------

    @Test
    fun `서버가 낮으면 뒤처진 것이다`() {
        // 이 세션에 실제로 있던 상황이다. 서버 0.1.0 · 앱 0.3.0.
        assertTrue(ServerVersion.isServerBehind(appVersion = "0.3.0", serverVersion = "0.1.0"))
    }

    @Test
    fun `같으면 뒤처진 것이 아니다`() {
        assertFalse(ServerVersion.isServerBehind("0.3.0", "0.3.0"))
    }

    @Test
    fun `서버가 더 높으면 경고하지 않는다`() {
        // 정상 배포 순서다 — 서버를 먼저 올리고 앱을 배포한다. 여기서 경고하면
        // 배포할 때마다 거짓 경고가 뜬다.
        assertFalse(ServerVersion.isServerBehind("0.3.0", "0.4.0"))
    }

    // --- 흔한 실수 ---------------------------------------------------------

    @Test
    fun `두 자리 숫자를 문자열로 비교하지 않는다`() {
        // 문자열 비교면 "0.10.0" < "0.9.0" 이 되어 최신 서버를 구버전이라 한다.
        assertFalse(ServerVersion.isServerBehind(appVersion = "0.9.0", serverVersion = "0.10.0"))
        assertTrue(ServerVersion.isServerBehind(appVersion = "0.10.0", serverVersion = "0.9.0"))
    }

    @Test
    fun `자리 수가 달라도 같은 버전은 같다`() {
        // 길이로 먼저 비교하면 0.3 이 0.3.0 보다 오래된 것이 되어 없는 문제를 만든다.
        assertFalse(ServerVersion.isServerBehind("0.3.0", "0.3"))
        assertFalse(ServerVersion.isServerBehind("0.3", "0.3.0"))
        assertFalse(ServerVersion.isServerBehind("1", "1.0.0"))
    }

    @Test
    fun `꼬리표는 떼고 본다`() {
        assertFalse(ServerVersion.isServerBehind("0.3.0", "0.3.0-rc1"))
        assertFalse(ServerVersion.isServerBehind("0.3.0", "0.3.0+build7"))
        assertTrue(ServerVersion.isServerBehind("0.3.0", "0.2.0-rc1"))
    }

    // --- 판정할 수 없는 경우 -----------------------------------------------

    @Test
    fun `모르는 형식은 경고하지 않는다`() {
        // 개발 빌드나 커밋 해시에 대고 "배포하세요" 라고 하면 소음이 된다.
        assertFalse(ServerVersion.isServerBehind("0.3.0", null))
        assertFalse(ServerVersion.isServerBehind(null, "0.1.0"))
        assertFalse(ServerVersion.isServerBehind("0.3.0", ""))
        assertFalse(ServerVersion.isServerBehind("0.3.0", "dev"))
        assertFalse(ServerVersion.isServerBehind("0.3.0", "a1b2c3d"))
        assertFalse(ServerVersion.isServerBehind("0.3.0", "0.1.x"))
    }

    @Test
    fun `음수나 빈 자리는 읽지 않는다`() {
        assertNull(ServerVersion.parse("0..1"))
        assertNull(ServerVersion.parse("-1.0.0"))
        assertNull(ServerVersion.parse("."))
        assertNull(ServerVersion.parse("   "))
        assertNull(ServerVersion.parse(null))
    }

    @Test
    fun `자리가 너무 많으면 읽지 않는다`() {
        // 날짜나 해시를 점으로 이어 붙인 문자열을 버전으로 오인하지 않는다.
        assertNull(ServerVersion.parse("1.2.3.4.5"))
    }

    @Test
    fun `읽을 수 있는 형식은 숫자 목록으로 준다`() {
        assertEquals(listOf(0, 3, 0), ServerVersion.parse("0.3.0"))
        assertEquals(listOf(0, 3, 0), ServerVersion.parse(" 0.3.0 "))
        assertEquals(listOf(1, 0), ServerVersion.parse("1.0"))
        assertEquals(listOf(12), ServerVersion.parse("12"))
    }

    // --- 안내 문구 ---------------------------------------------------------

    @Test
    fun `안내 문구가 무엇을 해야 하는지 말한다`() {
        val message = ServerVersion.behindMessage("0.3.0", "0.1.0")

        // 버전 둘과 **행동**이 들어 있어야 한다. "버전이 다릅니다" 만으로는
        // 아무도 움직이지 않는다.
        assertTrue(message.contains("0.1.0"))
        assertTrue(message.contains("0.3.0"))
        assertTrue("고칠 방법이 문구에 없다", message.contains("Deploy latest commit"))
    }

    @Test
    fun `버전을 몰라도 문구가 깨지지 않는다`() {
        val message = ServerVersion.behindMessage(null, null)
        assertTrue(message.contains("알 수 없음"))
    }
}
