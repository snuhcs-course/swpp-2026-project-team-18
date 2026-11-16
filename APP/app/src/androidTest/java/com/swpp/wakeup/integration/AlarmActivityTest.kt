// AI-generated (Claude)
package com.swpp.wakeup.integration

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.core.util.Consumer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.wakeup.alarm.AlarmActivity
import com.swpp.wakeup.alarm.AlarmLogQueue
import com.swpp.wakeup.alarm.AlarmRinger
import com.swpp.wakeup.alarm.ScheduledAlarmStore
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.AlarmLogInput
import com.swpp.wakeup.domain.model.AlarmSchedule
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 알람이 울리는 화면([AlarmActivity]). 실제 액티비티를 띄우고, 화면이 떠 있는 동안 다른 알람을 수신기의 직접 시작과
 * 같은 경로(`startActivity` + `FLAG_ACTIVITY_NEW_TASK`)로 보낸다. 화면은 `singleInstance` 라서 그 인텐트는 떠 있는
 * 화면의 `onNewIntent` 로 들어온다.
 *
 * ## 서버를 부르지 않는다
 *
 * 화면을 넘기면 앞 알람의 `silenced` 기록이 큐에 쌓이고 업로드 작업이 예약된다. 시작 전에 토큰을 지워 로그아웃
 * 상태로 두므로 업로드 작업은 아무것도 보내지 않고 끝난다(`ObservationUploadWorker`). 해제 · 미루기는 누르지 않는다.
 * 개발용 에뮬레이터에서 돌리면 디버그 앱이 로그아웃되고, 알람 사본과 알람 기록 큐가 비워진다.
 *
 * ## 이 테스트가 지키는 것
 *
 * - 화면이 떠 있는 동안 다른 알람이 울리면 화면이 그 알람으로 바뀐다(STAT-7). 예전에는 화면은 앞 알람을 그리는데
 *   해제 · 미루기 · 이동 기록은 새 알람에 적혔다. 앞 알람은 응답 없이 끝났다(`silenced`)고 한 번만 적는다.
 * - 같은 알람이 다시 들어오면(전체화면 알림과 직접 시작이 둘 다 옴, 알림을 누름, 넘겨준 알람의 인텐트가 늦게 옴)
 *   화면도 기록도 그대로이고, 자동으로 멈춘 소리도 다시 울리지 않는다.
 * - 넘겨받은 알람은 화면을 다시 만들어도(회전) 그대로다.
 * - 자동으로 멈춘 소리는 다른 알람이 들어오면 다시 울린다.
 * - 업데이트 전에 24시간제로 저장된 사본도 "5:50 PM" 으로 보인다(TIME-2).
 */
@RunWith(AndroidJUnit4::class)
class AlarmActivityTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ScheduledAlarmStore(context)
    private val logs = AlarmLogQueue(context)
    private var scenario: ActivityScenario<AlarmActivity>? = null

    /** 막 울린 두 알람. 일정이 달라 일정 줄로 화면을 구별한다 */
    private val now = System.currentTimeMillis()
    private val first = alarm(eventId = 990_001, eventLine = "09:00 알람 넘김 시험 A", alarmAt = now - 60_000)
    private val second = alarm(eventId = 990_002, eventLine = "09:30 알람 넘김 시험 B", alarmAt = now - 30_000)

    @Before
    fun setUp() {
        TokenStore(context).clear()
        AlarmRinger.stop(context)
        store.wipe()
        logs.wipe()
    }

    @After
    fun tearDown() {
        scenario?.close()
        AlarmRinger.stop(context)
        store.wipe()
        logs.wipe()
    }

    private fun alarm(eventId: Long, eventLine: String, alarmAt: Long) = AlarmSchedule(
        eventId = eventId,
        alarmAtMillis = alarmAt,
        departByMillis = null,
        arriveAtMillis = null,
        startAtMillis = alarmAt + 60 * 60_000L,
        alarmLabel = "",
        meridiem = "",
        eventLine = eventLine,
        placeName = null,
        arrivalLine = null,
    ).withClockLabel(ZoneId.systemDefault())

    /** 수신기가 화면을 띄우기 전에 하는 일. 사본에 두고 울렸다고 표시한다 */
    private fun ring(schedule: AlarmSchedule) {
        store.upsert(schedule)
        store.markHandled(schedule)
    }

    /** 알람이 울려 화면이 처음 뜬다 */
    private fun launch(schedule: AlarmSchedule) {
        ring(schedule)
        scenario = ActivityScenario.launch<AlarmActivity>(AlarmActivity.intent(context, schedule.eventId))
    }

    /**
     * 수신기의 직접 시작처럼 알람 화면을 연다. 떠 있는 화면의 `onNewIntent` 처리가 끝날 때까지 기다린다 — 바뀌지
     * 않아야 하는 경우도 확인하려면 인텐트가 실제로 닿은 뒤에 봐야 한다.
     */
    private fun deliver(schedule: AlarmSchedule) {
        val active = checkNotNull(scenario) { "화면을 먼저 띄운다" }
        val arrived = CountDownLatch(1)
        val listener = Consumer<Intent> { arrived.countDown() }
        active.onActivity { it.addOnNewIntentListener(listener) }
        try {
            context.startActivity(
                AlarmActivity.intent(context, schedule.eventId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            assertTrue("알람 화면에 인텐트가 닿지 않았다", arrived.await(DELIVERY_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            // 듣는 쪽은 onNewIntent 의 super 호출 안에서 불린다. 이 호출은 메인 스레드가 한가해진 뒤에 돌므로,
            // 돌아오면 화면의 onNewIntent 처리(화면 넘김 · 기록 · 소리)가 끝나 있다.
            active.onActivity { it.removeOnNewIntentListener(listener) }
        }
    }

    private fun assertShowing(schedule: AlarmSchedule) {
        compose.onNodeWithText(schedule.eventLine).assertIsDisplayed()
    }

    private fun silencedCount(schedule: AlarmSchedule): Int =
        logs.pending().count { it.kind == AlarmLogInput.KIND_SILENCED && it.event == schedule.eventId }

    // --- 떠 있는 화면에 다른 알람이 들어옴 (STAT-7) ---------------------------------

    @Test
    fun `화면이 떠 있는 동안 다른 알람이 울리면 화면이 그 알람으로 바뀌고 앞 알람은 응답 없이 끝났다고 한 번 적는다`() {
        // Given 앞 알람의 화면이 떠 있다
        launch(first)
        assertShowing(first)

        // When 다른 일정의 알람이 울려 같은 화면으로 들어온다
        ring(second)
        deliver(second)

        // Then 화면이 새 알람을 그리고 앞 알람은 사라진다. 해제 · 미루기가 맡는 알람과 같다
        assertShowing(second)
        compose.onNodeWithText(first.eventLine).assertDoesNotExist()
        // 앞 알람은 아무도 끄지 않은 채 끝났다고 한 번 적는다. 넘겨받은 알람은 아직 적지 않는다
        assertEquals(1, silencedCount(first))
        assertEquals(0, silencedCount(second))
    }

    @Test
    fun `같은 알람이 다시 들어오면 화면도 기록도 그대로다`() {
        // Given 앞 알람에서 다른 알람으로 화면이 넘어갔다
        launch(first)
        ring(second)
        deliver(second)
        assertShowing(second)

        // When 넘겨받은 알람이 한 번 더 오고(전체화면 알림과 직접 시작, 알림 누름) 넘겨준 알람의 인텐트도 늦게 온다
        deliver(second)
        deliver(first)

        // Then 화면은 마지막에 울린 알람이고, 응답 없이 끝났다는 기록은 앞 알람의 한 건뿐이다
        assertShowing(second)
        compose.onNodeWithText(first.eventLine).assertDoesNotExist()
        assertEquals(1, silencedCount(first))
        assertEquals(0, silencedCount(second))
    }

    @Test
    fun `넘겨받은 알람은 화면을 다시 만들어도 그대로다`() {
        // Given 다른 알람으로 화면이 넘어갔다
        launch(first)
        ring(second)
        deliver(second)
        assertShowing(second)

        // When 회전처럼 화면을 다시 만든다(새 화면은 액티비티의 인텐트에서 알람을 찾는다)
        checkNotNull(scenario).recreate()

        // Then 다시 만든 화면도 넘겨받은 알람을 그린다
        assertShowing(second)
        compose.onNodeWithText(first.eventLine).assertDoesNotExist()
    }

    @Test
    fun `자동으로 멈춘 소리는 다른 알람이 들어오면 다시 울리고 같은 알람이 다시 들어와서는 울리지 않는다`() {
        // Given 앞 알람의 화면이 떠서 울린다
        launch(first)
        assumeTrue("이 기기는 알람음을 재생하지 못한다(오디오 없는 에뮬레이터)", AlarmRinger.isRinging)

        // When 5분 동안 응답이 없어 소리가 멈춘 것처럼 끄고, 다른 알람이 들어온다
        AlarmRinger.stop(context)
        ring(second)
        deliver(second)

        // Then 넘겨받은 알람이 다시 울린다
        assertTrue("넘겨받은 알람이 울리지 않는다", AlarmRinger.isRinging)

        // When 그 소리도 멈춘 뒤 사용자가 알림을 눌러 같은 알람이 다시 들어온다
        AlarmRinger.stop(context)
        deliver(second)

        // Then 멈춘 소리가 다시 울리지 않는다
        assertFalse("알림을 누르자 멈춘 소리가 다시 울린다", AlarmRinger.isRinging)
    }

    // --- 업데이트 전에 저장된 라벨 (TIME-2) -----------------------------------------

    @Test
    fun `업데이트 전에 24시간제로 저장된 오후 알람도 12시간제 숫자로 보인다`() {
        // Given 업데이트 전 버전이 오늘 17:50 알람의 라벨을 "17:50" 으로 저장했고, 동기화 전에 그 알람이 울린다
        val zone = ZoneId.systemDefault()
        val at = LocalDate.now(zone).atTime(17, 50).atZone(zone).toInstant().toEpochMilli()
        val stored = alarm(eventId = 990_003, eventLine = "19:00 라벨 시험", alarmAt = at)
            .copy(alarmLabel = "17:50", meridiem = "PM")

        // When 알람 화면이 뜬다
        launch(stored)

        // Then "17:50 PM" 이 아니라 계획을 받을 때와 같은 "5:50 PM" 이다
        compose.onNodeWithText("5:50").assertIsDisplayed()
        compose.onNodeWithText("PM").assertIsDisplayed()
        compose.onNodeWithText("17:50").assertDoesNotExist()
    }

    private companion object {
        /** 인텐트가 화면에 닿기까지 기다리는 상한. 보통 수십 밀리초다 */
        const val DELIVERY_TIMEOUT_SECONDS = 10L
    }
}
