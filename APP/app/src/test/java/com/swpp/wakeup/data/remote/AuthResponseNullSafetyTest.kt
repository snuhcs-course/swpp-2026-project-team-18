package com.swpp.wakeup.data.remote

import com.google.gson.Gson
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 인증 응답 DTO 가 Gson 의 널을 견디는지.
 *
 * ## 왜 이것이 필요한가
 *
 * Gson 은 리플렉션으로 필드를 채우므로 **Kotlin 의 non-null 선언과 기본값을
 * 둘 다 무시한다.** `val access: String` 으로 선언해도 응답에 그 키가 없으면
 * null 이 들어간다.
 *
 * 그 상태로 진행하면 토큰을 null 로 저장하고도 "가입 성공" 으로 화면을 넘긴다.
 * `TokenStore.isLoggedIn` 은 false 라서 첫 인증 요청이 401 을 받고, 세션 만료
 * 처리가 사용자를 로그인 화면으로 되돌린다 — 화면에는 "가입했는데 다시 로그인
 * 화면" 으로만 보이고 원인은 어디에도 남지 않는다.
 *
 * 같은 부류를 `RouteSegmentDto` 에서 이미 겪었다(NetworkDtoNullSafetyTest).
 * 그래서 **DTO 를 손으로 만들지 않고 Gson 을 통과시켜** 검사한다. 손으로
 * 만들면 Kotlin 기본값이 적용되어 이 버그를 재현할 수 없다.
 */
class AuthResponseNullSafetyTest {

    private val gson = Gson()

    @Test
    fun `토큰 키가 없으면 널로 들어온다`() {
        val body = gson.fromJson(
            """{"user_id": 7, "user": {"id": 7, "email": "a@b.c", "nickname": "가"}}""",
            AuthResponse::class.java,
        )

        assertNull("access 가 널이어야 저장 전에 걸러낼 수 있다", body.access)
        assertNull("refresh 가 널이어야 저장 전에 걸러낼 수 있다", body.refresh)
    }

    @Test
    fun `명시적 널도 견딘다`() {
        val body = gson.fromJson(
            """{"access": null, "refresh": null, "user": null}""",
            AuthResponse::class.java,
        )

        assertNull(body.access)
        assertNull(body.refresh)
        assertNull(body.user)
    }

    @Test
    fun `빈 객체도 예외를 내지 않는다`() {
        val body = gson.fromJson("{}", AuthResponse::class.java)

        assertNull(body.access)
        assertNull(body.user)
    }

    @Test
    fun `정상 응답은 그대로 읽힌다`() {
        val body = gson.fromJson(
            """
            {"user_id": 7, "access": "AAA", "refresh": "RRR",
             "user": {"id": 7, "email": "a@b.c", "nickname": "가", "date_joined": "2026-09-24"}}
            """.trimIndent(),
            AuthResponse::class.java,
        )

        assertEquals("AAA", body.access)
        assertEquals("RRR", body.refresh)
        assertEquals("a@b.c", body.user?.email)
        assertEquals("가", body.user?.nickname)
    }
}
