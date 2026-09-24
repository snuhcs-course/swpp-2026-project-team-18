package com.swpp.wakeup.ui.auth

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 토큰 없는 2xx 응답을 성공으로 넘기지 않는지 소스 감사.
 *
 * `AuthRepository.call` 은 Android 프레임워크(SharedPreferences)에 묶여 있어
 * 기기 없이 호출할 수 없다. 대신 **끊는 분기가 있는지**를 소스에서 고정한다.
 * 이 분기가 사라지면 "가입했는데 다시 로그인 화면" 이 조용히 되돌아온다.
 */
class AuthTokenGuardTest {

    private val source by lazy { readSource("data/repository/AuthRepository.kt") }

    @Test
    fun `토큰이 비면 저장하지 않고 실패로 돌린다`() {
        val save = source.indexOf("tokenStore.save(")
        val guard = source.indexOf("MESSAGE_NO_TOKEN")
        assertTrue("토큰 저장 지점을 찾지 못했다", save > 0)
        assertTrue("토큰 없는 응답을 끊는 분기가 없다", guard > 0)
        assertTrue(
            "검사가 저장보다 뒤에 있으면 이미 널을 저장한 뒤다",
            guard < save,
        )
    }

    @Test
    fun `응답 필드를 직접 쓰지 않고 검사한 값을 저장한다`() {
        assertTrue(
            "body.access 를 그대로 저장하면 널 검사를 우회한다",
            !source.contains("access = body.access"),
        )
        assertTrue(
            "body.user 를 그대로 쓰면 널일 때 터진다",
            !source.contains("body.user.email"),
        )
    }

    @Test
    fun `앞 사용자 흔적을 지운 뒤에 토큰을 저장한다`() {
        val wipe = source.indexOf("onBeforeAuthenticated()")
        val save = source.indexOf("tokenStore.save(")
        assertTrue("정리 호출이 없다", wipe > 0)
        assertTrue(
            "순서가 반대면 새 사용자의 첫 캐시와 경쟁한다",
            wipe < save,
        )
    }

    private fun readSource(relative: String): String {
        val path = "src/main/java/com/swpp/wakeup/$relative"
        val candidates = listOf(File(path), File("app/$path"), File("../app/$path"), File("APP/app/$path"))
        return candidates.firstOrNull(File::exists)?.readText()
            ?: error("$relative 을 찾지 못했다: ${candidates.joinToString { it.absolutePath }}")
    }
}
