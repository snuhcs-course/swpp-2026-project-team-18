package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.PasswordChangeRequest
import com.swpp.wakeup.data.remote.PasswordChangeResponse
import com.swpp.wakeup.testing.FakeAuthApi
import com.swpp.wakeup.testing.FakeTokenCipher
import com.swpp.wakeup.testing.ok
import com.swpp.wakeup.testing.prefsContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class MePasswordCancellationTest {
    @Test fun `cancelled old password owner cannot rotate a later accounts tokens`() = runTest {
        val tokens = TokenStore(prefsContext(), FakeTokenCipher())
        tokens.save("first-access", "first-refresh", "first@snu.ac.kr", "첫 계정")
        val api = object : FakeAuthApi() {
            override suspend fun changePassword(body: PasswordChangeRequest): Response<PasswordChangeResponse> {
                // A deliberately uncooperative response after logout/account replacement.
                tokens.save("second-access", "second-refresh", "second@snu.ac.kr", "다음 계정")
                currentCoroutineContext().cancel()
                return ok(PasswordChangeResponse("old-owner-rotated-access", "old-owner-rotated-refresh", 1))
            }
        }
        var returnedSuccess = false
        val request = launch {
            AuthRepository(tokens, api).changePassword("old12345", "new12345", "new12345")
            returnedSuccess = true
        }
        runCurrent()
        assertTrue(request.isCancelled)
        assertFalse(returnedSuccess)
        assertEquals("second-access", tokens.accessToken)
        assertEquals("second-refresh", tokens.refreshToken)
        assertEquals("second@snu.ac.kr", tokens.email)
    }
}
