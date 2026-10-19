package com.swpp.wakeup

import android.app.NotificationManager
import android.content.Intent
import android.location.Location
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import com.swpp.wakeup.alarm.AlarmNotifications
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.domain.model.TripStage
import com.swpp.wakeup.sensing.GeoPoint
import com.swpp.wakeup.sensing.TripGeofence
import com.swpp.wakeup.sensing.TripLiveState
import com.swpp.wakeup.sensing.TripObservationQueue
import com.swpp.wakeup.sensing.TripTrackingService
import com.swpp.wakeup.ui.auth.LoginActivity
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Partly AI-generated (Claude)
/**
 * 집을 나선 뒤 흐린 위치만 올 때 추적 서비스가 "준비 중" 에 머물지 않는지 실제 서비스로 본다.
 *
 * 팀원 제보("집에서 벗어난 지 한참됐는데 계속 준비중")의 재현이다. 출발 전에는 저전력
 * 위치를 받는데, Wi-Fi 를 잃으면 오차가 수백 m 라 판정에서 전부 버려졌다.
 *
 * 판정 규칙 자체는 JVM 테스트(`TripGeofenceTest`)가 본다. 여기서는 그 규칙이
 * **실제 위치 공급(FusedLocation 모의 위치)·포그라운드 서비스·알림·화면 상태**까지
 * 이어지는지 본다. 모의 위치는 오차를 정할 수 있어 기지국 위치를 흉내 낼 수 있다
 * (에뮬레이터 `geo fix` 는 늘 정확하다).
 *
 * 실행(QA 앱만 쓴다. 개발 앱 데이터는 건드리지 않는다):
 * ```
 * ./gradlew :app:assembleE2e :app:assembleE2eAndroidTest -PjitE2e=true
 * adb install -r app/build/outputs/apk/e2e/app-e2e.apk
 * adb install -r app/build/outputs/apk/androidTest/e2e/app-e2e-androidTest.apk
 * adb shell am instrument -w -e class com.swpp.wakeup.TripCoarseFixTest \
 *   com.swpp.wakeup.qa.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * 약 3분 걸린다(출발 전 위치 주기 30초·2분 한도를 실제 시간으로 기다린다). CI 의
 * 기기 테스트(`integration` 패키지)에는 넣지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class TripCoarseFixTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val fused = LocationServices.getFusedLocationProviderClient(context)

    /** 신림역. 목적지는 3km 남동쪽(서울대) */
    private val home = GeoPoint(37.4842, 126.9295)
    private val destination = GeoPoint(37.4598, 126.9511)

    @Before
    fun setUp() {
        assertEquals("QA 전용 앱에서만 실행해야 함", "com.swpp.wakeup.qa", context.packageName)
        for (permission in listOf("ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "POST_NOTIFICATIONS")) {
            shell("pm grant ${context.packageName} android.permission.$permission")
        }
        shell("appops set ${context.packageName} android:mock_location allow")
        Tasks.await(fused.setMockMode(true), 10, TimeUnit.SECONDS)

        // 위치 권한을 쓰는 포그라운드 서비스는 앱이 보일 때 시작해야 한다.
        UiDevice.getInstance(instrumentation).wakeUp()
        context.startActivity(
            Intent(context, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        instrumentation.waitForIdleSync()
        TripLiveState.clear()
    }

    @After
    fun tearDown() {
        TripTrackingService.stop(context)
        runCatching { Tasks.await(fused.setMockMode(false), 10, TimeUnit.SECONDS) }
        shell("appops set ${context.packageName} android:mock_location default")
        TripLiveState.clear()
    }

    @Test
    fun coarseFixesAfterLeavingHomeMoveTheTripOutOfPreparing() {
        start(eventId = 9_001)

        // 집에서는 Wi-Fi 위치가 정확하다 → "준비 중".
        push(home, accuracyM = 8f)
        val atHome = awaitSnapshot("집에서 첫 판정") {
            it.phase == TripGeofence.Phase.BEFORE_DEPARTURE && it.phaseCheckedAtMillis != null
        }
        assertEquals(
            TripStage.PREPARING,
            TripStage.tracked(atHome.phase, atHome.phaseCheckedAtMillis, System.currentTimeMillis()),
        )

        // 나간 뒤에는 기지국 위치뿐이다(오차 600m, 집에서 2km·2.5km). 저전력 요청의
        // 최소 간격(15초)을 지켜 넣는다.
        SystemClock.sleep(PUSH_GAP_MS)
        push(north(home, 2_000.0), accuracyM = 600f)
        SystemClock.sleep(PUSH_GAP_MS)
        push(north(home, 2_500.0), accuracyM = 600f)

        val moving = awaitSnapshot("흐린 위치로 이동 중") { it.phase == TripGeofence.Phase.IN_TRANSIT }
        assertEquals(
            TripStage.IN_TRANSIT,
            TripStage.tracked(moving.phase, moving.phaseCheckedAtMillis, System.currentTimeMillis()),
        )
        // 언제 나갔는지는 모른다. 출발 관측을 지어내지 않는다.
        assertEquals(0, TripObservationQueue(context).pendingCount)
        awaitNotification("이동 중 알림") { title, _ -> title == "이동 중" }
    }

    @Test
    fun blindForTwoMinutesWithdrawsPreparingAndRaisesAccuracy() {
        start(eventId = 9_002)
        shell("logcat -c")

        push(home, accuracyM = 8f)
        awaitSnapshot("집에서 첫 판정") {
            it.phase == TripGeofence.Phase.BEFORE_DEPARTURE && it.phaseCheckedAtMillis != null
        }
        awaitNotification("준비 중 알림") { title, _ -> title == "준비 중" }

        // 집 근처라고도 멀다고도 할 수 없는 흐린 위치만 2분 넘게 온다.
        val until = SystemClock.elapsedRealtime() + TripGeofence.EVIDENCE_MAX_AGE_MS + 20_000L
        while (SystemClock.elapsedRealtime() < until) {
            SystemClock.sleep(PUSH_GAP_MS)
            push(north(home, 300.0), accuracyM = 800f)
        }

        val snapshot = TripLiveState.pointFor(9_002) ?: throw AssertionError("추적 상태가 없다")
        assertEquals("판정기는 마지막으로 확인한 집에 머문다", TripGeofence.Phase.BEFORE_DEPARTURE, snapshot.phase)
        assertNull(
            "근거가 낡았는데 준비 중이라고 한다",
            TripStage.tracked(snapshot.phase, snapshot.phaseCheckedAtMillis, System.currentTimeMillis()),
        )
        awaitNotification("위치 확인 중 알림") { title, text ->
            title == "이동 기록 중" && text.contains("위치 확인 중")
        }
        val log = shell("logcat -d -s TripTrackingService:I")
        assertTrue("고정확도로 올리지 않았다:\n$log", log.contains("고정확도로 올린다"))
    }

    // --- 도우미 -------------------------------------------------------------

    private fun start(eventId: Long) {
        val now = System.currentTimeMillis()
        TripTrackingService.start(
            context,
            AlarmSchedule(
                eventId = eventId,
                alarmAtMillis = now - 60_000L,
                departByMillis = now + 20 * 60_000L,
                arriveAtMillis = now + 50 * 60_000L,
                startAtMillis = now + 60 * 60_000L,
                alarmLabel = "7:40",
                meridiem = "AM",
                eventLine = "09:00 모의 위치 확인",
                placeName = "서울대",
                arrivalLine = null,
                homeLat = home.lat,
                homeLng = home.lng,
                destLat = destination.lat,
                destLng = destination.lng,
            ),
        )
        awaitNotification("추적 시작") { _, _ -> true }
        // 알림은 위치 요청 직전에 뜬다. 요청이 걸린 뒤에 위치를 넣는다.
        SystemClock.sleep(1_000)
    }

    /** 오차를 정한 모의 위치 하나. FusedLocation 은 모의 모드에서 이것만 준다 */
    private fun push(point: GeoPoint, accuracyM: Float) {
        val location = Location("fused").apply {
            latitude = point.lat
            longitude = point.lng
            accuracy = accuracyM
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        Tasks.await(fused.setMockLocation(location), 10, TimeUnit.SECONDS)
    }

    private fun north(from: GeoPoint, meters: Double) = GeoPoint(from.lat + meters / 111_320.0, from.lng)

    private fun awaitSnapshot(
        what: String,
        timeoutMs: Long = 60_000L,
        matches: (TripLiveState.Snapshot) -> Boolean,
    ): TripLiveState.Snapshot {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            TripLiveState.snapshot.value?.takeIf(matches)?.let { return it }
            SystemClock.sleep(500)
        }
        throw AssertionError("$what: ${timeoutMs / 1000}초 안에 오지 않음. 마지막 상태 ${TripLiveState.snapshot.value}")
    }

    private fun awaitNotification(
        what: String,
        timeoutMs: Long = 30_000L,
        matches: (title: String, text: String) -> Boolean,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last: Pair<String, String>? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            manager.activeNotifications
                .firstOrNull { it.id == AlarmNotifications.NOTIFICATION_TRIP }
                ?.notification?.extras
                ?.let { extras ->
                    val title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE)?.toString().orEmpty()
                    val text = extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString().orEmpty()
                    last = title to text
                    if (matches(title, text)) return
                }
            SystemClock.sleep(500)
        }
        fail("$what: ${timeoutMs / 1000}초 안에 알림이 바뀌지 않음. 마지막 알림 $last")
    }

    private fun shell(command: String): String =
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().readText()
        }

    private companion object {
        /** 저전력 요청의 최소 간격(15초)보다 조금 길게 */
        const val PUSH_GAP_MS = 16_000L
    }
}
