package com.swpp.wakeup.data.repository

import android.util.Log
import com.swpp.wakeup.BuildConfig
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.ReportsApi
import com.swpp.wakeup.domain.model.CalibrationView
import com.swpp.wakeup.domain.model.WeeklyReportView
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate

/**
 * 리포트 저장소.
 *
 * **캐시하지 않는다.** 리포트는 지난 주를 되돌아보는 화면이라 아침에 급히
 * 열지 않는다. 오프라인에서 열리지 않아도 손실이 작고, 대신 오래된 정시율을
 * 지금 값처럼 보여주는 위험을 피한다 — 이 화면의 숫자가 사용자의 여유 설정을
 * 바꾸므로 낡은 값이 더 위험하다.
 *
 * 응답 → 화면 값 매핑은 `ReportMapping.kt` 에 있다(안드로이드 의존 없음, JVM 테스트).
 * (Partly AI-generated (Claude))
 */
class ReportRepository(
    private val api: ReportsApi = ApiClient.reports,
) {

    sealed interface ReportResult<out T> {
        data class Success<T>(val data: T) : ReportResult<T>
        data class Failure(val message: String) : ReportResult<Nothing>
    }

    suspend fun weekly(week: LocalDate? = null): ReportResult<WeeklyReportView> = guard {
        val response = api.weekly(week?.toString())
        val body = unwrap(response) ?: return@guard failure(response)
        ReportResult.Success(body.toView())
    }

    suspend fun calibration(): ReportResult<CalibrationView> = guard {
        val response = api.calibration()
        val body = unwrap(response) ?: return@guard failure(response)
        ReportResult.Success(body.toView())
    }

    // --- 내부 -------------------------------------------------------------

    private inline fun <T> guard(block: () -> ReportResult<T>): ReportResult<T> = try {
        block()
    } catch (e: IOException) {
        Log.w(TAG, "network failure", e)
        ReportResult.Failure(MESSAGE_NETWORK)
    } catch (e: Exception) {
        Log.e(TAG, "unexpected failure", e)
        ReportResult.Failure(
            if (BuildConfig.DEV_TOOLS) "$MESSAGE_UNKNOWN (${e.javaClass.simpleName})"
            else MESSAGE_UNKNOWN
        )
    }

    private fun <T> unwrap(response: Response<T>): T? =
        if (response.isSuccessful) response.body() else null

    private fun failure(response: Response<*>): ReportResult.Failure {
        val message = ApiClient.parseErrorMessage(response.errorBody()?.string())
            ?: when (response.code()) {
                401 -> "다시 로그인해야 한다."
                in 500..599 -> "서버에 문제가 생겼다."
                else -> MESSAGE_UNKNOWN
            }
        return ReportResult.Failure(message)
    }

    private companion object {
        const val TAG = "ReportRepository"
        const val MESSAGE_NETWORK = "서버에 연결할 수 없다. 네트워크와 서버 상태를 확인한다."
        const val MESSAGE_UNKNOWN = "알 수 없는 오류가 발생했다."
    }
}
