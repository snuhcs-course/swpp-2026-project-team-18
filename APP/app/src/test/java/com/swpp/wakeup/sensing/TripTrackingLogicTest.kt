package com.swpp.wakeup.sensing

// Partly AI-generated (Claude)

import com.google.gson.Gson
import com.swpp.wakeup.alarm.AlarmNotifications.TripPhaseLabel
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.sensing.TripGeofence.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TripTrackingService] 에서 떼어 낸 값 판단. 서비스는 기기에서만 뜨지만 알림 문구와
 * 기준점 · 다시 전달 판단은 틀려도 화면에 조용히 남는 종류라 여기서 고정한다.
 */
class TripTrackingLogicTest {

    private val redelivery = 1 // Service.START_FLAG_REDELIVERY 와 같은 비트

    private fun schedule(
        homeLat: Double? = 37.0,
        homeLng: Double? = 127.0,
        destLat: Double? = 37.46,
        destLng: Double? = 126.95,
        placeName: String? = "302동 105호",
    ) = AlarmSchedule(
        eventId = 3,
        alarmAtMillis = 1_000,
        departByMillis = 2_000,
        arriveAtMillis = null,
        startAtMillis = 3_000,
        alarmLabel = "7:00",
        meridiem = "AM",
        eventLine = "09:30 해석개론",
        placeName = placeName,
        arrivalLine = null,
        homeLat = homeLat,
        homeLng = homeLng,
        destLat = destLat,
        destLng = destLng,
    )

    @Test
    fun `위치 주기는 이동 중이면 10초 아니면 30초다`() {
        assertEquals(10_000L, TripTrackingLogic.updateIntervalMillis(highAccuracy = true))
        assertEquals(30_000L, TripTrackingLogic.updateIntervalMillis(highAccuracy = false))
    }

    @Test
    fun `재전달 플래그나 이어 가기 액션이면 이어 간다`() {
        assertTrue(TripTrackingLogic.isRedelivery(redelivery, redelivery, TripTrackingService.ACTION_START))
        assertTrue(TripTrackingLogic.isRedelivery(0, redelivery, TripTrackingService.ACTION_RESUME))
        assertFalse(TripTrackingLogic.isRedelivery(0, redelivery, TripTrackingService.ACTION_START))
        assertFalse(TripTrackingLogic.isRedelivery(2, redelivery, null))
    }

    @Test
    fun `인텐트의 일정 JSON 을 읽고 없거나 깨지면 null 이다`() {
        val gson = Gson()
        val parsed = TripTrackingLogic.parseSchedule(gson, gson.toJson(schedule()))

        assertEquals(3L, parsed?.eventId)
        assertNull(TripTrackingLogic.parseSchedule(gson, null))
        assertNull(TripTrackingLogic.parseSchedule(gson, "{broken"))
    }

    @Test
    fun `기준점은 위도 경도가 다 있을 때만 있다`() {
        assertEquals(GeoPoint(37.0, 127.0), TripTrackingLogic.homeOf(schedule()))
        assertEquals(GeoPoint(37.46, 126.95), TripTrackingLogic.destinationOf(schedule()))
        assertNull(TripTrackingLogic.homeOf(schedule(homeLng = null)))
        assertNull(TripTrackingLogic.homeOf(schedule(homeLat = null)))
        assertNull(TripTrackingLogic.destinationOf(schedule(destLat = null)))
        assertNull(TripTrackingLogic.destinationOf(schedule(destLng = null)))
    }

    @Test
    fun `오차를 보고하지 않은 위치는 최악으로 본다`() {
        val fix = TripTrackingLogic.fixOf(1.0, 2.0, hasAccuracy = false, accuracyM = 5f, timeMillis = 10, nowMillis = 99)
        assertEquals(Float.MAX_VALUE, fix.accuracyM)
        assertEquals(10L, fix.atMillis)
    }

    @Test
    fun `시각이 없는 위치는 받은 시각을 쓴다`() {
        val fix = TripTrackingLogic.fixOf(1.0, 2.0, hasAccuracy = true, accuracyM = 5f, timeMillis = 0, nowMillis = 99)
        assertEquals(5f, fix.accuracyM)
        assertEquals(99L, fix.atMillis)
        assertEquals(GeoPoint(1.0, 2.0), fix.point)
    }

    @Test
    fun `집 단계를 벗어나는 순간만 출발 전환이다`() {
        assertTrue(TripTrackingLogic.leftHome(Phase.BEFORE_DEPARTURE, Phase.IN_TRANSIT))
        assertTrue(TripTrackingLogic.leftHome(Phase.BEFORE_DEPARTURE, Phase.ARRIVED))
        assertFalse(TripTrackingLogic.leftHome(Phase.BEFORE_DEPARTURE, Phase.BEFORE_DEPARTURE))
        assertFalse(TripTrackingLogic.leftHome(Phase.IN_TRANSIT, Phase.ARRIVED))
    }

    @Test
    fun `시작 알림은 기준점에 맞춰 무엇을 기록하는지 적는다`() {
        assertEquals(
            "09:30 해석개론 · 집을 나서는 시각과 도착 시각을 기록해요",
            TripTrackingLogic.initialText(schedule()),
        )
        assertEquals(
            "09:30 해석개론 · 도착 시각을 기록해요",
            TripTrackingLogic.initialText(schedule(homeLat = null)),
        )
        assertEquals(
            "09:30 해석개론 · 집을 나서는 시각을 기록해요",
            TripTrackingLogic.initialText(schedule(destLat = null)),
        )
    }

    @Test
    fun `판정기 단계마다 알림 단계가 하나씩 대응한다`() {
        assertEquals(TripPhaseLabel.PREPARING, TripTrackingLogic.phaseLabel(Phase.BEFORE_DEPARTURE))
        assertEquals(TripPhaseLabel.IN_TRANSIT, TripTrackingLogic.phaseLabel(Phase.IN_TRANSIT))
        assertEquals(TripPhaseLabel.ARRIVED, TripTrackingLogic.phaseLabel(Phase.ARRIVED))
    }

    @Test
    fun `거리는 1km 부터 소수 한 자리 km 로 적는다`() {
        assertEquals("350m", TripTrackingLogic.formatDistance(349.6))
        assertEquals("999m", TripTrackingLogic.formatDistance(999.0))
        assertEquals(String.format("%.1fkm", 1.25), TripTrackingLogic.formatDistance(1_250.0))
    }

    @Test
    fun `남은 거리는 근거 위치가 낡지 않았을 때만 보여 준다`() {
        val now = 1_000_000L
        assertEquals(500.0, TripTrackingLogic.freshRemainingMeters(500.0, now - 1_000, now))
        assertEquals(
            500.0,
            TripTrackingLogic.freshRemainingMeters(500.0, now - TripGeofence.EVIDENCE_MAX_AGE_MS, now),
        )
        assertNull(TripTrackingLogic.freshRemainingMeters(500.0, now - TripGeofence.EVIDENCE_MAX_AGE_MS - 1, now))
        assertNull(TripTrackingLogic.freshRemainingMeters(500.0, null, now))
        assertNull(TripTrackingLogic.freshRemainingMeters(null, now, now))
    }

    @Test
    fun `집에 있다는 근거가 없으면 단계를 단정하지 않는다`() {
        val p = TripTrackingLogic.progress("수업", Phase.BEFORE_DEPARTURE, atHomeConfirmed = false, remainingM = 900.0)

        assertNull(p.phase)
        assertEquals(TripTrackingLogic.TITLE_TRACKING, p.title)
        assertEquals("수업 · 위치 확인 중", p.text)
    }

    @Test
    fun `준비 중에는 남은 거리 대신 아직 출발 전이라고 한다`() {
        val p = TripTrackingLogic.progress("수업", Phase.BEFORE_DEPARTURE, atHomeConfirmed = true, remainingM = 900.0)

        assertEquals(TripPhaseLabel.PREPARING, p.phase)
        assertEquals("준비 중", p.title)
        assertEquals("수업 · 아직 출발 전", p.text)
    }

    @Test
    fun `이동 중에는 남은 거리를 알면 적고 모르면 가는 중이라고 한다`() {
        assertEquals(
            "수업 · 목적지까지 420m",
            TripTrackingLogic.progress("수업", Phase.IN_TRANSIT, atHomeConfirmed = false, remainingM = 420.0).text,
        )
        val unknown = TripTrackingLogic.progress("수업", Phase.IN_TRANSIT, atHomeConfirmed = false, remainingM = null)
        assertEquals("수업 · 목적지로 가는 중", unknown.text)
        assertEquals("이동 중", unknown.title)
    }

    @Test
    fun `도착 단계는 도착을 기록했다고 한다`() {
        val p = TripTrackingLogic.progress("수업", Phase.ARRIVED, atHomeConfirmed = false, remainingM = 10.0)
        assertEquals("수업 · 도착을 기록했어요", p.text)
        assertEquals(TripPhaseLabel.ARRIVED, p.phase)
    }

    @Test
    fun `도착했어요 버튼은 이동 중이고 아직 도착이 없을 때만 단다`() {
        assertTrue(TripTrackingLogic.offersManualArrive(TripPhaseLabel.IN_TRANSIT, null, alreadyArrived = false))
        assertFalse(TripTrackingLogic.offersManualArrive(TripPhaseLabel.PREPARING, null, alreadyArrived = false))
        assertFalse(TripTrackingLogic.offersManualArrive(null, null, alreadyArrived = false))
        assertFalse(TripTrackingLogic.offersManualArrive(TripPhaseLabel.IN_TRANSIT, 5L, alreadyArrived = false))
        assertFalse(TripTrackingLogic.offersManualArrive(TripPhaseLabel.IN_TRANSIT, null, alreadyArrived = true))
    }

    @Test
    fun `도착 결과 줄은 일정과 장소를 잇고 장소가 없으면 일정만 쓴다`() {
        assertEquals("09:30 해석개론 · 302동 105호", TripTrackingLogic.arrivalLine(schedule()))
        assertEquals("09:30 해석개론", TripTrackingLogic.arrivalLine(schedule(placeName = null)))
        assertEquals("09:30 해석개론 · 도착을 기록했어요", TripTrackingLogic.manualArrivedText(schedule()))
    }
}
