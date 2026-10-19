package com.swpp.wakeup.integration

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.testing.FakeEventsApi
import com.swpp.wakeup.testing.FakeProfileApi
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.ok
import com.swpp.wakeup.ui.alarm.AlarmDecisionScreen
import com.swpp.wakeup.ui.theme.JitTheme
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 알람 결정 화면(Figma ④) — 서버 응답 JSON → 저장소 매핑 → 화면.
 *
 * 화면 데이터를 생성자로 만들지 않고 **서버 응답을 저장소에 통과시켜** 만든다.
 * 그래야 필드 이름(`@SerializedName`)·매핑·화면이 한 번에 검증되고, 뷰 모델에
 * 필드가 늘어나도 이 테스트가 같이 고쳐질 필요가 없다.
 *
 * ## 이 테스트가 지키는 것
 *
 * - **근거를 함께 보여준다.** "7:00" 만 주면 믿을 이유가 없다. 준비·이동·버퍼와
 *   합계가 화면에 있어야 한다.
 * - 집에서 출발하지 않는 일정은 준비 줄을 그리지 않는다. 0분 줄은 "준비를 순식간에
 *   한다" 로 읽힌다.
 * - 계산되지 않은 알람은 이유를 말하고, 다시 계산·위험 바꾸기 같은 계산된 알람용
 *   버튼을 내놓지 않는다. 삭제는 어느 상태에서나 할 수 있다.
 * - 버튼이 맞는 동작으로 이어진다(다시 계산은 카카오 쿼터를 쓰므로 누를 때만).
 */
@RunWith(AndroidJUnit4::class)
class AlarmDecisionScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.systemDefault()

    /** 내일 09:00(기기 시간대). 화면은 기기 시간대로 그린다. */
    private val start: ZonedDateTime =
        ZonedDateTime.now(zone).plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)

    private fun eventJson(plan: String) =
        """{"id":1,"title":"1교시 수업","start_at":"${start.toOffsetDateTime()}","source":"manual",
            "place":{"id":1,"name":"서울대","address":"관악로 1","lat":37.46,"lng":126.95},
            "alarm_enabled":true,"alarm_on":true,"alarm_plan":$plan}"""

    private val computedPlan =
        """{"status":"ok","alarm_at":"${start.minusHours(2).toOffsetDateTime()}",
            "prep_minutes":30,"travel_minutes":40,"buffer_minutes":10,"total_minutes":80,
            "travel_mode":"지하철","route_summary":"2호선","tau_used":0.9,"on_time_probability":92}"""

    /** 서버 응답을 저장소에 통과시켜 화면 데이터를 만든다. */
    private fun planFrom(json: String): AlarmPlanView = runBlocking {
        val api = object : FakeEventsApi() {
            override suspend fun get(id: Long) = ok(fromJson<EventDto>(json))
        }
        val result = EventRepository(api, FakeProfileApi()).loadPlan(1)
        assertTrue("저장소가 화면 데이터를 만들어야 한다: $result", result is EventRepository.Result.Success)
        (result as EventRepository.Result.Success).data
    }

    private val calls = mutableListOf<String>()

    private fun show(plan: AlarmPlanView?) {
        compose.setContent {
            JitTheme {
                AlarmDecisionScreen(
                    plan = plan,
                    onBack = { calls += "back" },
                    onChangeRisk = { calls += "risk" },
                    onEditBlocks = { calls += "blocks" },
                    onRecompute = { calls += "recompute" },
                    onDelete = { calls += "delete" },
                )
            }
        }
    }

    @Test
    fun `계산된 알람은 시각과 함께 준비 이동 버퍼 근거와 합계를 보여준다`() {
        show(planFrom(eventJson(computedPlan)))

        compose.onNodeWithText("7:00").assertExists()
        compose.onNodeWithText("AM").assertExists()
        compose.onNodeWithText("09:00 1교시 수업").assertExists()
        compose.onNodeWithText("서울대 · 관악로 1").assertExists()

        compose.onNodeWithText("준비 시간").assertExists()
        compose.onNodeWithText("지하철 이동").assertExists()
        compose.onNodeWithText("안전 버퍼").assertExists()
        compose.onAllNodesWithText("30분").assertCountEquals(1)
        compose.onAllNodesWithText("40분").assertCountEquals(1)
        compose.onNodeWithText("합계 80분").performScrollTo()
    }

    @Test
    fun `집에서 출발하지 않는 일정은 준비 줄을 그리지 않는다`() {
        val plan = computedPlan.replace("\"status\":\"ok\"", "\"status\":\"ok\",\"prep_source\":\"not_from_home\"")

        show(planFrom(eventJson(plan)))

        compose.onNodeWithText("준비 시간").assertDoesNotExist()
        compose.onNodeWithText("지하철 이동").assertExists()
    }

    @Test
    fun `버튼은 각자의 동작으로 이어진다`() {
        show(planFrom(eventJson(computedPlan)))

        compose.onNodeWithText("다시 계산").performScrollTo().performClick()
        compose.onNodeWithText("지각 위험 바꾸기").performScrollTo().performClick()
        compose.onNodeWithText("일정 삭제").performScrollTo().performClick()
        compose.onNodeWithText("확인").performScrollTo().performClick()

        assertEquals(listOf("recompute", "risk", "delete", "back"), calls)
    }

    @Test
    fun `계산되지 않은 알람은 이유를 말하고 계산된 알람용 버튼을 숨긴다`() {
        val plan = """{"status":"no_home","status_label":"집 위치 필요","alarm_at":null}"""

        show(planFrom(eventJson(plan)))

        compose.onNodeWithText("집 위치 필요").assertExists()
        compose.onNodeWithText(
            "이동 시간을 구하려면 출발지가 필요함. 홈 화면 안내에서 집 위치를 설정하면 바로 계산됨"
        ).assertExists()
        compose.onNodeWithText("다시 계산").assertDoesNotExist()
        compose.onNodeWithText("지각 위험 바꾸기").assertDoesNotExist()
        compose.onNodeWithText("확인").assertDoesNotExist()

        compose.onNodeWithText("일정 삭제").performScrollTo().performClick()
        assertEquals(listOf("delete"), calls)
    }

    @Test
    fun `불러오는 중에는 제목만 두고 동작 버튼을 내놓지 않는다`() {
        show(plan = null)

        compose.onNodeWithText("알람 결정").assertExists()
        compose.onNodeWithText("일정 삭제").assertDoesNotExist()
        compose.onNodeWithText("확인").assertDoesNotExist()
    }
}
