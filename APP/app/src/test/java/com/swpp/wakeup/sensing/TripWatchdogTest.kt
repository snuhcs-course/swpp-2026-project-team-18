package com.swpp.wakeup.sensing

// Partly AI-generated (Claude)

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import com.swpp.wakeup.data.local.ActiveTripStore
import com.swpp.wakeup.data.local.ArrivalStore
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.testing.InMemoryPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 끊긴 추적을 잇는 점검([TripWatchdog])과 도착 사본([ArrivalRecords]).
 *
 * 서비스를 실제로 다시 띄우는 마지막 한 줄은 기기에서만 볼 수 있다. 여기서는 그 앞의
 * **잇지 말아야 할 경우**(사본 없음 · 마감 지남 · 이미 도착 · 권한 없음)를 본다. 잘못 이으면
 * 끝난 아침에 위치 추적 알림이 다시 뜬다.
 */
class TripWatchdogTest {

    private val prefs = InMemoryPrefs()
    private var fine = true
    private var background = true

    /** SharedPreferences 와 위치 권한만 답하는 Context. 나머지는 안드로이드 스텁 기본값이다 */
    private val context: Context = object : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
            val granted = when (permission) {
                Manifest.permission.ACCESS_FINE_LOCATION -> fine
                Manifest.permission.ACCESS_BACKGROUND_LOCATION -> background
                else -> true
            }
            return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
        }
    }

    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L

    private fun schedule(
        eventId: Long = 9_101,
        alarmAt: Long = now - hour,
        startAt: Long = now + hour,
    ) = AlarmSchedule(
        eventId = eventId,
        alarmAtMillis = alarmAt,
        departByMillis = null,
        arriveAtMillis = null,
        startAtMillis = startAt,
        alarmLabel = "7:00",
        meridiem = "AM",
        eventLine = "수업",
        placeName = null,
        arrivalLine = null,
        homeLat = 37.0,
        homeLng = 127.0,
        destLat = 37.4,
        destLng = 126.9,
    )

    @Before
    fun signIn() {
        prefs.edit().putString("email", "me@snu.ac.kr").apply()
        TripLiveState.clear()
    }

    @After
    fun reset() {
        TripLiveState.clear()
    }

    @Test
    fun `남은 사본이 없으면 잇지 않는다`() {
        assertNull(TripWatchdog.resumeIfCut(context, "테스트"))
    }

    @Test
    fun `마감이 지난 사본은 지우고 잇지 않는다`() {
        val store = ActiveTripStore(context)
        store.save(schedule(startAt = now - 2 * hour))

        assertNull(TripWatchdog.resumeIfCut(context, "테스트"))
        assertNull(store.current())
    }

    @Test
    fun `이미 도착한 일정의 사본은 지우고 잇지 않는다`() {
        val store = ActiveTripStore(context)
        store.save(schedule())
        ArrivalStore(context).record(9_101, now - 60_000)

        assertNull(TripWatchdog.resumeIfCut(context, "테스트"))
        assertNull(store.current())
    }

    @Test
    fun `메모리의 도착도 이미 도착으로 본다`() {
        val store = ActiveTripStore(context)
        store.save(schedule())
        TripLiveState.recordArrival(9_101, now - 60_000)

        assertNull(TripWatchdog.resumeIfCut(context, "테스트"))
        assertNull(store.current())
    }

    @Test
    fun `지금 추적 중인 일정은 다시 띄우지 않고 사본은 남긴다`() {
        val store = ActiveTripStore(context)
        store.save(schedule())
        TripLiveState.beginTracking(9_101)

        assertNull(TripWatchdog.resumeIfCut(context, "테스트"))
        assertNotNull(store.current())
    }

    @Test
    fun `알람 전이면 잇지 않는다`() {
        ActiveTripStore(context).save(schedule(alarmAt = now + hour, startAt = now + 2 * hour))

        assertNull(TripWatchdog.resumeIfCut(context, "테스트"))
    }

    @Test
    fun `위치 권한이 없으면 잇지 않는다`() {
        fine = false
        val store = ActiveTripStore(context)
        store.save(schedule())

        assertNull(TripWatchdog.resumeIfCut(context, "테스트"))
        assertEquals(9_101L, store.current()?.eventId)
    }

    @Test
    fun `백그라운드 위치가 없으면 앱을 열 때까지 기다린다`() {
        background = false
        ActiveTripStore(context).save(schedule())

        assertNull(TripWatchdog.resumeIfCut(context, "테스트", foreground = false))
    }

    @Test
    fun `점검 예약과 취소는 AlarmManager 가 없어도 죽지 않는다`() {
        TripWatchdog.schedule(context, deadlineMillis = now + hour, nowMillis = now)
        TripWatchdog.schedule(context, deadlineMillis = now + 1_000, nowMillis = now)
        TripWatchdog.cancel(context)
    }

    // --- 도착 사본 -------------------------------------------------------------

    @Test
    fun `도착은 메모리와 디스크에 함께 남는다`() {
        ArrivalRecords.record(context, 9_201, now)

        assertEquals(now, TripLiveState.arrivalFor(9_201)?.atMillis)
        assertEquals(now, ArrivalStore(context).all()[9_201])
    }

    @Test
    fun `프로세스가 다시 뜨면 디스크의 도착을 되살린다`() {
        ArrivalRecords.record(context, 9_202, now)
        TripLiveState.clear()

        ArrivalRecords.restore(context)

        assertEquals(now, TripLiveState.arrivalFor(9_202)?.atMillis)
    }

    @Test
    fun `서버 도착은 메모리에 없을 때만 쓴다`() {
        ArrivalRecords.record(context, 9_203, now)
        ArrivalRecords.restoreFromServer(context, 9_203, now - 5_000)
        assertEquals(now, TripLiveState.arrivalFor(9_203)?.atMillis)

        ArrivalRecords.restoreFromServer(context, 9_204, now - 5_000)
        assertEquals(now - 5_000, TripLiveState.arrivalFor(9_204)?.atMillis)
        assertEquals(now - 5_000, ArrivalStore(context).all()[9_204])
    }

    @Test
    fun `같은 일정을 새로 추적하면 그 도착을 잊는다`() {
        ArrivalRecords.record(context, 9_205, now)

        ArrivalRecords.forget(context, 9_205)

        assertNull(TripLiveState.arrivalFor(9_205))
        assertNull(ArrivalStore(context).all()[9_205])
    }
}
