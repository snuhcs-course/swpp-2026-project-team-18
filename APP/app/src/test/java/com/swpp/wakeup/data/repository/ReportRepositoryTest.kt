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
 * 주간 리포트 저장소(서버 응답 → 화면 문구).
 *
 * ## 이 테스트가 지키는 것
 *
 * 리포트의 목적은 **앱이 틀렸는지 보여주는 것**이다. 그래서 가장 위험한 실패는
 * 숫자가 좋게 보이는 것이다.
 *
 * - 측정하지 못한 지각 요인은 0 이 아니라 "측정 안 됨" 으로 적는다. 0 으로 적으면
 *   "그 요인은 괜찮았다" 로 읽혀 엉뚱한 곳을 고친다.
 * - 표본이 적은 구간은 과신으로 낙인찍지 않는다.
 * - 일정이 없는 요일도 칸을 채운다(요일 축이 비지 않게).
 */
class ReportRepositoryTest {

    private val weeklyJson = """{
        "week_start":"2026-10-12","week_end":"2026-10-18",
        "event_count":5,"arrived_count":4,"unobserved_count":1,
        "on_time_count":3,"late_count":1,"on_time_rate":0.75,
        "median_slack_minutes":6.5,"tightest_slack_minutes":-3.0,
        "by_weekday":[{"weekday":0,"total":2,"late":1},{"weekday":4,"total":1,"late":0}],
        "late_causes":[
          {"event":11,"late_minutes":4,"prep_over":7.0,"travel_over":-2.0,"depart_late":null,
           "primary":"prep_over","label":"prep_over","unmeasured":["depart_late"]},
          {"event":12,"late_minutes":2,"label":"unknown","unmeasured":["prep_over","travel_over","depart_late"]}
        ],
        "primary_counts":{"prep_over":1},
        "calibration":null
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
    fun `주 범위와 정시율을 문구로 만든다`() = runTest {
        val view = ReportRepository(weeklyApi()).weeklyView()

        assertEquals("10월 12일 ~ 10월 18일", view.rangeLabel)
        assertEquals("정시 도착 75%", view.onTimeLabel)
        assertEquals(0.75f, view.onTimeFraction!!, 1e-6f)
        assertEquals(5, view.eventCount)
        assertEquals(1, view.unobservedCount)
    }

    @Test
    fun `여유 시간은 부호에 따라 일찍 도착과 늦음으로 나뉜다`() = runTest {
        val view = ReportRepository(weeklyApi()).weeklyView()

        assertEquals("중간 여유 6.5분 전 도착", view.medianSlackLabel)
        assertEquals("가장 아슬아슬했던 아침 3분 늦음", view.tightestSlackLabel)
    }

    @Test
    fun `정시율이 없으면 문구도 비율도 만들지 않는다`() = runTest {
        // 0% 로 그리면 "전부 지각" 으로 읽힌다. 실제로는 도착 기록이 없었다.
        val empty = """{"week_start":"2026-10-12","week_end":"2026-10-18",
            "by_weekday":[],"late_causes":[],"primary_counts":{}}"""

        val view = ReportRepository(weeklyApi(empty)).weeklyView()

        assertNull(view.onTimeLabel)
        assertNull(view.onTimeFraction)
        assertNull(view.medianSlackLabel)
        assertTrue(view.causes.isEmpty())
    }

    @Test
    fun `요일은 일정이 없는 날까지 월요일부터 일곱 칸을 채운다`() = runTest {
        val view = ReportRepository(weeklyApi()).weeklyView()

        assertEquals(listOf("월", "화", "수", "목", "금", "토", "일"), view.weekdays.map { it.label })
        assertEquals(2, view.weekdays[0].total)
        assertEquals(1, view.weekdays[0].late)
        assertEquals(0, view.weekdays[1].total)
        assertEquals(1, view.weekdays[4].total)
    }

    @Test
    fun `날짜를 읽지 못하면 서버 문자열을 그대로 보여준다`() = runTest {
        val odd = weeklyJson.replace("\"2026-10-12\"", "\"week-42\"")

        val view = ReportRepository(weeklyApi(odd)).weeklyView()

        assertEquals("week-42 ~ 2026-10-18", view.rangeLabel)
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

    // --- 지각 원인 -------------------------------------------------------------

    @Test
    fun `지각 원인은 주된 요인을 제목으로 하고 측정 못 한 요인은 측정 안 됨으로 적는다`() = runTest {
        val cause = ReportRepository(weeklyApi()).weeklyView().causes[0]

        assertEquals(11L, cause.eventId)
        assertEquals("4분 늦음", cause.lateLabel)
        assertEquals("준비가 7분 더 걸렸음", cause.headline)
        assertEquals("준비 +7분 · 출발 측정 안 됨 · 이동 -2분", cause.detail)
        assertFalse(cause.unknown)
    }

    @Test
    fun `추적이 안 된 아침은 앱 계산이 틀렸다고 하지 않고 측정하지 못했다고 한다`() = runTest {
        val cause = ReportRepository(weeklyApi()).weeklyView().causes[1]

        assertEquals("원인을 측정하지 못했음", cause.headline)
        assertEquals("준비 측정 안 됨 · 출발 측정 안 됨 · 이동 측정 안 됨", cause.detail)
        assertTrue(cause.unknown)
    }

    @Test
    fun `원인 종류마다 제목이 다르다`() = runTest {
        val causes = """[
            {"event":1,"late_minutes":3,"travel_over":5.0,"label":"travel_over"},
            {"event":2,"late_minutes":3,"depart_late":2.5,"label":"depart_late"},
            {"event":3,"late_minutes":1,"prep_over":0.0,"travel_over":0.0,"depart_late":0.0,"label":"plan_too_tight"}
        ]"""
        val json = weeklyJson.replace(Regex("\"late_causes\":\\[.*?]\\s*,\\s*\"primary_counts\"", RegexOption.DOT_MATCHES_ALL),
            "\"late_causes\":$causes,\"primary_counts\"")

        val headlines = ReportRepository(weeklyApi(json)).weeklyView().causes.map { it.headline }

        assertEquals(
            listOf("이동이 5분 더 걸렸음", "집에서 2.5분 늦게 나섰음", "계획대로 했는데 늦었음 · 여유가 부족했음"),
            headlines,
        )
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

    @Test
    fun `주간 응답에 보정이 없으면 표본 부족으로 채운다`() = runTest {
        val view = ReportRepository(weeklyApi()).weeklyView()

        assertEquals("표본 부족", view.calibration.verdictLabel)
        assertFalse(view.calibration.hasData)
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
