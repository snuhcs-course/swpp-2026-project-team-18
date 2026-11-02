// Partly AI-generated (Claude)
package com.swpp.wakeup.data.local

import com.swpp.wakeup.data.remote.RefreshOutcome
import com.swpp.wakeup.testing.FakeTokenCipher
import com.swpp.wakeup.testing.InMemoryPrefs
import com.swpp.wakeup.testing.prefsContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 토큰 암호화 저장(todo §2 "토큰 암호화 저장")과 refresh 회전 저장(token-security-api.md §5). */
class TokenStoreTest {

    private val prefs = InMemoryPrefs()
    private val cipher = FakeTokenCipher()
    private val store = TokenStore(prefsContext(prefs), cipher)

    @Test
    fun `토큰은 암호문으로 저장하고 읽을 때 푼다`() {
        store.save(access = "a-token", refresh = "r-token", email = "me@snu.ac.kr", nickname = "진호")

        assertTrue(prefs.getString("access", null)!!.startsWith(TokenCipher.PREFIX))
        assertFalse(prefs.getString("refresh", null)!!.contains("r-token"))
        assertEquals("a-token", store.accessToken)
        assertEquals("r-token", store.refreshToken)
        assertEquals("표시 정보는 평문이다", "me@snu.ac.kr", prefs.getString("email", null))
        assertTrue(store.isLoggedIn)
    }

    @Test
    fun `암호화 전 평문 토큰은 그대로 읽히고 암호문으로 바뀐다`() {
        prefs.edit().putString("access", "old-a").putString("refresh", "old-r").apply()

        assertEquals("old-a", store.accessToken)
        assertEquals("old-r", store.refreshToken)
        assertTrue(prefs.getString("access", null)!!.startsWith(TokenCipher.PREFIX))
        assertEquals("old-a", TokenStore(prefsContext(prefs), cipher).accessToken)
    }

    @Test
    fun `풀지 못하면 토큰을 지우고 다시 로그인하게 한다`() {
        store.save(access = "a2", refresh = "r2", email = "e", nickname = "n")
        cipher.failDecrypt = true

        val fresh = TokenStore(prefsContext(prefs), cipher)

        assertNull(fresh.accessToken)
        assertFalse(fresh.isLoggedIn)
        assertNull(prefs.getString("refresh", null))
    }

    @Test
    fun `암호화를 못 하면 키를 새로 만들어 한 번 더 해 보고 그래도 안 되면 저장하지 않는다`() {
        cipher.failEncrypt = true

        assertThrows(TokenStorageException::class.java) {
            store.save(access = "a", refresh = "r", email = "e", nickname = "n")
        }
        assertEquals(1, cipher.resets)
        assertNull(prefs.getString("access", null))
        assertFalse(store.isLoggedIn)
    }

    @Test
    fun `회전된 refresh 가 오면 함께 바꾸고 없으면 그대로 둔다`() {
        store.save(access = "a1", refresh = "r1", email = "e", nickname = "n")

        store.updateTokens(access = "a2", refresh = null)
        assertEquals("a2", store.accessToken)
        assertEquals("r1", store.refreshToken)

        store.updateTokens(access = "a3", refresh = "r3")
        assertEquals("a3", store.accessToken)
        assertEquals("r3", store.refreshToken)
    }

    @Test
    fun `갱신 응답의 새 refresh 를 결과에 싣는다`() {
        assertEquals(RefreshOutcome.Renewed("a", "r"), RefreshOutcome.of(200, "a", refresh = "r"))
        assertEquals(RefreshOutcome.Renewed("a", null), RefreshOutcome.of(200, "a", refresh = " "))
        assertEquals(RefreshOutcome.Renewed("a", null), RefreshOutcome.of(200, "a"))
    }
}
