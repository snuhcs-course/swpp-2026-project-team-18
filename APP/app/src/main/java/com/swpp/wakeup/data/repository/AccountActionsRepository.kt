package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.AuthApi
import com.swpp.wakeup.data.remote.InquiryRequest
import com.swpp.wakeup.data.remote.InquiryResponse
import com.swpp.wakeup.data.remote.LearningResetResponse
import com.swpp.wakeup.data.remote.LogoutAllResponse
import com.swpp.wakeup.data.remote.RouteHistoryApi
import com.swpp.wakeup.data.remote.RouteHistoryResponse
import com.swpp.wakeup.data.remote.SupportApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response

/** 계정 작업의 네트워크 경계. HTTP 상태를 보존해 구버전·잠금·통신 실패 안내를 구분한다. */
interface AccountActions {
    suspend fun logoutAll(): Result<Response<LogoutAllResponse>>
    suspend fun resetLearning(): Result<Response<LearningResetResponse>>
    suspend fun sendInquiry(request: InquiryRequest): Result<Response<InquiryResponse>>
    suspend fun recentDestinations(): Result<Response<RouteHistoryResponse>>
}

class AccountActionsRepository(
    private val auth: AuthApi = ApiClient.auth,
    private val support: SupportApi = ApiClient.support,
    private val history: RouteHistoryApi = ApiClient.routeHistory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AccountActions {
    override suspend fun logoutAll() = execute { auth.logoutAll() }
    override suspend fun resetLearning() = execute { auth.resetLearning() }
    override suspend fun sendInquiry(request: InquiryRequest) = execute { support.sendInquiry(request) }
    override suspend fun recentDestinations() = execute { history.recent() }

    private suspend fun <T> execute(block: suspend () -> T): Result<T> = withContext(dispatcher) {
        try {
            Result.success(block())
        } catch (cancelled: CancellationException) {
            // 계정 전환·화면 종료 뒤의 요청이 실패 안내나 옛 계정 상태를 만들지 않는다.
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
}
