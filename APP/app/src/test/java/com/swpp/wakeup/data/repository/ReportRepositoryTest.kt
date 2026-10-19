package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.CalibrationDto
import com.swpp.wakeup.data.remote.WeeklyReportDto
import com.swpp.wakeup.data.repository.ReportRepository.ReportResult
import com.swpp.wakeup.domain.model.CalibrationView
import com.swpp.wakeup.domain.model.WeeklyReportView
import com.swpp.wakeup.testing.FakeReportsApi
import com.swpp.wakeup.testing.errorJson
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * 주간 리포트 저장소.
 *
 * 응답 → 화면 문구 매핑은 `ReportMapping.kt` 에 있고 [ReportMappingTest] 가 예시 주로
 * 고정한다. 여기서는 **저장소가 맡은 것**만 본다 — 요청 파라미터, 실제 Gson 을 거친
 * 응답이 매핑까지 이어지는지, 실패를 예외 대신 문구로 돌려주는지.
 *
 * ## 이 테스트가 지키는 것
 *
 * 리포트의 목적은 **앱이 틀렸는지 보여주는 것**이다. 그래서 가장 위험한 실패는
 * 숫자가 좋게 보이는 것이다.
 *
 * - 도착 기록이 없는 주를 "0%" 로 그리지 않는다. 0% 는 "전부 지각" 으로 읽힌다.
 * - 날짜를 못 읽으면 범위를 지어내지 않는다.
 * - 표본이 적은 구간은 과신으로 낙인찍지 않는다.
 */
class ReportRepositoryTest {

    private val weeklyJson = """{
        "week_start":"2026-10-12","week_end":"2026-10-18",
        "event_count":5,"arrived_count":4,"unobserved_count":1,
        "on_time_count":3,"late_count":1,"on_time_rate":0.75,
        "median_slack_minutes":6.5,"tightest_slack_minutes":-3.0,
        "week_complete":true,"has_next_week":true
    }"""

    private fun weeklyApi(json: String = weeklyJson, onWeek: (String?) -> Unit = {}) =
        object : FakeReportsApi() {
            override suspend fun weekly(week: String?): Response<WeeklyReportDto> {
                onWeek(week)
                return ok(fromJson(json))
            }
        }

    private suspend fun ReportRepository.weeklyView(week: LocalDate? = null): WeeklyReportView {
        val result = weekly(week)
        assertTrue("성공이어야 한다: $result", result is ReportResult.Success)
        return (result as ReportResult.Success).data
    }

    // --- 주간 요약 ------------------------------------------------------------

    @Test
    fun `서버 응답을 주 범위와 정시율 문구로 만든다`() = runTest {
        val view = ReportRepository(weeklyApi()).weeklyView()

        assertEquals("10월 12일 ~ 10월 18일", view.rangeLabel)
        assertEquals("정시 도착 75%", view.onTimeLabel)
        assertEquals("3/4", view.onTimeCountLabel)
        assertEquals(5, view.eventCount)
        assertEquals(1, view.unobservedCount)
    }

    @Test
    fun `도착 기록이 없는 주는 0퍼센트가 아니라 기록 없음이다`() = runTest {
        // 0% 로 그리면 "전부 지각" 으로 읽힌다. 실제로는 도착 기록이 없었다.
        val empty = """{"week_start":"2026-10-12","week_end":"2026-10-18",
            "event_count":2,"arrived_count":0,"unobserved_count":2,"on_time_rate":0.0}"""

        val view = ReportRepository(weeklyApi(empty)).weeklyView()

        assertEquals("정시 도착 · 기록 없음", view.onTimeLabel)
        assertFalse(view.onTimeLabel.contains("0%"))
    }

    @Test
    fun `날짜를 읽지 못하면 범위를 지어내지 않는다`() = runTest {
        val odd = weeklyJson.replace("\"2026-10-12\"", "\"week-42\"")

        val view = ReportRepository(weeklyApi(odd)).weeklyView()

        assertEquals("—", view.rangeLabel)
    }

    @Test
    fun `특정 주를 고르면 ISO 날짜로 보낸다`() = runTest {
        var sent: String? = "unset"
        val repository = ReportRepository(weeklyApi(onWeek = { sent = it }))

        repository.weeklyView(LocalDate.of(2026, 10, 12))
        assertEquals("2026-10-12", sent)

        repository.weeklyView(null)
        assertNull("이번 주는 파라미터 없이 서버 기본값에 맡긴다", sent)
    }

    // --- 확률 보정 -------------------------------------------------------------

    private fun calibrationApi(json: String) = object : FakeReportsApi() {
        override suspend fun calibration(): Response<CalibrationDto> = ok(fromJson(json))
    }

    private suspend fun calibrationView(json: String): CalibrationView {
        val result = ReportRepository(calibrationApi(json)).calibration()
        return (result as ReportResult.Success).data
    }

    @Test
    fun `약속보다 실제 정시율이 낮은 구간은 표본이 충분할 때만 과신으로 표시한다`() = runTest {
        val view = calibrationView(
            """{"buckets":[
                {"lower":0.8,"upper":0.9,"center":0.85,"total":5,"on_time":3,"actual_rate":0.6,"reliable":true},
                {"lower":0.9,"upper":1.0,"center":0.95,"total":1,"on_time":0,"actual_rate":0.0,"reliable":false}
              ],
              "scored_count":6,"unscored_count":2,"verdict":"overconfident"}"""
        )

        val (enough, thin) = view.points
        assertEquals("80~90%", enough.rangeLabel)
        assertEquals("5번 중 3번 정시", enough.countLabel)
        assertTrue(enough.overconfident)
        assertFalse("한 번 지각한 것과 계통 오차는 다르다", thin.overconfident)

        assertEquals("과신 중", view.verdictLabel)
        assertEquals("지각 위험을 낮추면 알람이 앞당겨짐", view.action)
        assertEquals(6, view.scoredCount)
    }

    @Test
    fun `판정마다 다른 안내를 준다`() = runTest {
        val calibrated = calibrationView("""{"verdict":"calibrated"}""")
        assertEquals("잘 맞음", calibrated.verdictLabel)
        assertNull("맞는데 고칠 것을 권하지 않는다", calibrated.action)

        val conservative = calibrationView("""{"verdict":"conservative"}""")
        assertEquals("지나치게 이름", conservative.verdictLabel)
        assertEquals("지각 위험을 높이면 더 잘 수 있음", conservative.action)
    }

    @Test
    fun `표본이 부족하면 몇 번이 더 필요한지 알려준다`() = runTest {
        val view = calibrationView("""{"verdict":"insufficient","min_samples_for_reliable":4}""")

        assertEquals("표본 부족", view.verdictLabel)
        assertEquals("판정하려면 같은 구간에서 4번 이상의 결과가 필요함", view.verdictNote)
        assertFalse(view.isTrustworthy)
    }

    // --- 실패 ---------------------------------------------------------------

    @Test
    fun `실패는 예외 대신 문구로 돌려준다`() = runTest {
        fun failing(response: () -> Response<WeeklyReportDto>) = object : FakeReportsApi() {
            override suspend fun weekly(week: String?): Response<WeeklyReportDto> = response()
        }

        assertEquals(
            ReportResult.Failure("다시 로그인해야 한다."),
            ReportRepository(failing { httpError(401) }).weekly(),
        )
        assertEquals(
            ReportResult.Failure("서버에 문제가 생겼다."),
            ReportRepository(failing { httpError(500) }).weekly(),
        )
        assertEquals(
            ReportResult.Failure("이번 주 리포트가 아직 없다."),
            ReportRepository(failing { httpError(404, errorJson("이번 주 리포트가 아직 없다.")) }).weekly(),
        )
        val offline = ReportRepository(failing { throw IOException("offline") }).weekly()
        assertTrue((offline as ReportResult.Failure).message.startsWith("서버에 연결할 수 없다"))
    }
}
