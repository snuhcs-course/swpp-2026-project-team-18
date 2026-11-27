package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.*
import com.swpp.wakeup.testing.FakeAuthApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AccountActionsRepositoryTest {
    private val unusedSupport = object : SupportApi {
        override suspend fun sendInquiry(body: InquiryRequest): Response<InquiryResponse> = error("Unexpected inquiry")
    }
    private val unusedHistory = object : RouteHistoryApi {
        override suspend fun recent(sort: String, limit: Int): Response<RouteHistoryResponse> = error("Unexpected history")
    }

    @Test fun `logout all preserves revoked count and status`() = runTest {
        val repository = AccountActionsRepository(
            auth = object : FakeAuthApi() {
                override suspend fun logoutAll() = Response.success(LogoutAllResponse(revoked = 3))
            }, support = unusedSupport, history = unusedHistory, dispatcher = StandardTestDispatcher(testScheduler),
        )
        val response = repository.logoutAll().getOrThrow()
        assertEquals(200, response.code())
        assertEquals(3, response.body()?.revoked)
    }

    @Test fun `unsupported server remains an HTTP response rather than network error`() = runTest {
        val repository = AccountActionsRepository(
            auth = object : FakeAuthApi() {
                override suspend fun resetLearning(): Response<LearningResetResponse> =
                    Response.error(404, "{}".toResponseBody())
            }, support = unusedSupport, history = unusedHistory, dispatcher = StandardTestDispatcher(testScheduler),
        )
        val response = repository.resetLearning().getOrThrow()
        assertEquals(404, response.code())
        assertNull(response.body())
    }

    @Test fun `inquiry passes complete body and rate limit response`() = runTest {
        val request = InquiryRequest("Alarm stopped", "reply@example.test", "0.16.0", "Test device")
        var sent: InquiryRequest? = null
        val repository = AccountActionsRepository(
            auth = FakeAuthApi(), support = object : SupportApi {
                override suspend fun sendInquiry(body: InquiryRequest): Response<InquiryResponse> {
                    sent = body
                    return Response.error(429, "{}".toResponseBody())
                }
            }, history = unusedHistory, dispatcher = StandardTestDispatcher(testScheduler),
        )
        assertEquals(429, repository.sendInquiry(request).getOrThrow().code())
        assertEquals(request, sent)
    }

    @Test fun `recent history uses defaults and preserves offline failure`() = runTest {
        var requested: Pair<String, Int>? = null
        val failure = IOException("offline")
        val repository = AccountActionsRepository(
            auth = FakeAuthApi(), support = unusedSupport,
            history = object : RouteHistoryApi {
                override suspend fun recent(sort: String, limit: Int): Response<RouteHistoryResponse> {
                    requested = sort to limit
                    throw failure
                }
            }, dispatcher = StandardTestDispatcher(testScheduler),
        )
        assertSame(failure, repository.recentDestinations().exceptionOrNull())
        assertEquals("recent" to 10, requested)
    }

    @Test fun `account cancellation escapes instead of becoming visible failure`() = runTest {
        val cancellation = CancellationException("Logged out")
        val repository = AccountActionsRepository(
            auth = object : FakeAuthApi() {
                override suspend fun logoutAll(): Response<LogoutAllResponse> = throw cancellation
            }, support = unusedSupport, history = unusedHistory, dispatcher = StandardTestDispatcher(testScheduler),
        )
        try {
            repository.logoutAll()
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            // Coroutine stack-trace recovery may copy the exception across withContext.
            assertEquals(cancellation.message, actual.message)
        }
    }
}
