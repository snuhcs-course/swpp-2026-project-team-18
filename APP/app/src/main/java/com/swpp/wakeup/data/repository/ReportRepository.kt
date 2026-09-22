package com.swpp.wakeup.data.repository

import android.util.Log
import com.swpp.wakeup.BuildConfig
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.CalibrationDto
import com.swpp.wakeup.data.remote.LateCauseDto
import com.swpp.wakeup.data.remote.ReportsApi
import com.swpp.wakeup.data.remote.WeeklyReportDto
import com.swpp.wakeup.domain.model.CalibrationPoint
import com.swpp.wakeup.domain.model.CalibrationView
import com.swpp.wakeup.domain.model.LateCauseLine
import com.swpp.wakeup.domain.model.WeekdayLoad
import com.swpp.wakeup.domain.model.WeeklyReportView
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 리포트 저장소.
 *
 * **캐시하지 않는다.** 리포트는 지난 주를 되돌아보는 화면이라 아침에 급히
 * 열지 않는다. 오프라인에서 열리지 않아도 손실이 작고, 대신 오래된 정시율을
 * 지금 값처럼 보여주는 위험을 피한다 — 이 화면의 숫자가 사용자의 여유 설정을
 * 바꾸므로 낡은 값이 더 위험하다.
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

// ---------------------------------------------------------------------------
// 매핑
// ---------------------------------------------------------------------------

private val RANGE_FORMAT = DateTimeFormatter.ofPattern("M월 d일")
private val WEEKDAY_NAMES = listOf("월", "화", "수", "목", "금", "토", "일")

private fun percent(value: Double?): Int? =
    value?.let { (it.coerceIn(0.0, 1.0) * 100).roundToInt() }

/** 소수 첫째 자리까지. 정수면 소수점을 뗀다. */
private fun minutes(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (abs(rounded - Math.floor(rounded)) < 1e-9) "${rounded.toInt()}분"
    else "${rounded}분"
}

private fun WeeklyReportDto.toView(): WeeklyReportView {
    val start = runCatching { LocalDate.parse(weekStart) }.getOrNull()
    val end = runCatching { LocalDate.parse(weekEnd) }.getOrNull()
    val range = if (start != null && end != null) {
        "${start.format(RANGE_FORMAT)} ~ ${end.format(RANGE_FORMAT)}"
    } else {
        "$weekStart ~ $weekEnd"
    }

    return WeeklyReportView(
        rangeLabel = range,
        eventCount = eventCount,
        arrivedCount = arrivedCount,
        unobservedCount = unobservedCount,
        onTimeCount = onTimeCount,
        lateCount = lateCount,
        onTimeLabel = percent(onTimeRate)?.let { "정시 도착 $it%" },
        onTimeFraction = onTimeRate?.toFloat()?.coerceIn(0f, 1f),
        medianSlackLabel = medianSlackMinutes?.let { slackPhrase(it, prefix = "중간 여유") },
        tightestSlackLabel = tightestSlackMinutes?.let {
            slackPhrase(it, prefix = "가장 아슬아슬했던 아침")
        },
        weekdays = buildWeekdays(byWeekday),
        causes = lateCauses.map { it.toLine() },
        calibration = (calibration ?: CalibrationDto()).toView(),
    )
}

/**
 * 여유를 사람이 읽는 말로.
 *
 * 음수를 "여유 -3분" 으로 쓰면 읽는 순간 멈칫한다. 지각은 지각이라고 적는다.
 */
private fun slackPhrase(value: Double, prefix: String): String =
    if (value < 0) "$prefix ${minutes(-value)} 늦음"
    else "$prefix ${minutes(value)} 전 도착"

/**
 * 요일 7칸을 항상 만든다.
 *
 * 서버가 7칸을 주지만 빠진 요일이 있어도 화면이 깨지지 않게 여기서 채운다.
 * 요일 칸이 하나 빠지면 막대 위치가 밀려 다른 요일을 잘못 읽게 된다.
 */
private fun buildWeekdays(
    rows: List<com.swpp.wakeup.data.remote.WeekdayRowDto>,
): List<WeekdayLoad> {
    val byIndex = rows.associateBy { it.weekday }
    return (0..6).map { index ->
        val row = byIndex[index]
        WeekdayLoad(
            label = WEEKDAY_NAMES[index],
            total = row?.total ?: 0,
            late = row?.late ?: 0,
        )
    }
}

private fun LateCauseDto.toLine(): LateCauseLine {
    val unknown = label == LateCauseDto.LABEL_UNKNOWN

    val headline = when (label) {
        LateCauseDto.LABEL_PREP ->
            "준비가 ${minutes(prepOver ?: 0.0)} 더 걸렸음"

        LateCauseDto.LABEL_TRAVEL ->
            "이동이 ${minutes(travelOver ?: 0.0)} 더 걸렸음"

        LateCauseDto.LABEL_DEPART ->
            "집에서 ${minutes(departLate ?: 0.0)} 늦게 나섰음"

        LateCauseDto.LABEL_PLAN_TIGHT ->
            "계획대로 했는데 늦었음 · 여유가 부족했음"

        // 추적이 동작하지 않은 아침이다. **앱 계산이 틀렸다고 말하지 않는다.**
        else -> "원인을 측정하지 못했음"
    }

    // 측정된 값만 적는다. 측정 못 한 것은 "측정 안 됨" 으로 명시한다 —
    // 0 으로 적으면 "그 요인은 괜찮았다" 로 읽혀 엉뚱한 곳을 고치게 된다.
    val detail = listOf(
        "준비" to prepOver,
        "출발" to departLate,
        "이동" to travelOver,
    ).joinToString(" · ") { (name, value) ->
        if (value == null) "$name 측정 안 됨"
        else "$name ${if (value >= 0) "+" else ""}${minutes(value)}"
    }

    return LateCauseLine(
        eventId = event,
        lateLabel = "${lateMinutes}분 늦음",
        headline = headline,
        detail = detail,
        unknown = unknown,
    )
}

private fun CalibrationDto.toView(): CalibrationView {
    val points = buckets.map { bucket ->
        val promisedPercent = percent(bucket.center) ?: 0
        val actualPercent = percent(bucket.actualRate)
        CalibrationPoint(
            rangeLabel = "${percent(bucket.lower)}~${percent(bucket.upper)}%",
            promised = bucket.center.toFloat().coerceIn(0f, 1f),
            actual = bucket.actualRate?.toFloat()?.coerceIn(0f, 1f),
            total = bucket.total,
            reliable = bucket.reliable,
            countLabel = "${bucket.total}번 중 ${bucket.onTime}번 정시",
            // 신뢰할 수 없는 버킷은 과신으로 낙인찍지 않는다. 한 번 지각한 것과
            // 계통 오차는 다르다.
            overconfident = bucket.reliable &&
                actualPercent != null && actualPercent < promisedPercent,
        )
    }

    val (verdictLabel, verdictNote, action) = when (verdict) {
        CalibrationDto.VERDICT_CALIBRATED -> Triple(
            "잘 맞음",
            "앱이 말한 확률과 실제 정시율이 거의 같음",
            null,
        )

        CalibrationDto.VERDICT_OVERCONFIDENT -> Triple(
            "과신 중",
            "앱이 약속한 것보다 실제 정시율이 낮음. 알람이 늦게 잡히고 있음",
            "지각 위험을 낮추면 알람이 앞당겨짐",
        )

        CalibrationDto.VERDICT_CONSERVATIVE -> Triple(
            "지나치게 이름",
            "실제로는 거의 늘 정시임. 알람이 필요 이상으로 이름",
            "지각 위험을 높이면 더 잘 수 있음",
        )

        else -> Triple(
            "표본 부족",
            "판정하려면 같은 구간에서 ${minSamples}번 이상의 결과가 필요함",
            "아침에 이동 기록이 쌓이면 자동으로 채워짐",
        )
    }

    return CalibrationView(
        points = points,
        scoredCount = scoredCount,
        unscoredCount = unscoredCount,
        verdictLabel = verdictLabel,
        verdictNote = verdictNote,
        action = action,
        verdict = verdict,
    )
}
