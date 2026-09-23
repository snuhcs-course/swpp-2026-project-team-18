package com.swpp.wakeup.sensing

import com.swpp.wakeup.alarm.AlarmNotifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 진행 단계 표시와 도착 결과 알림의 수명.
 *
 * ## 왜 소스를 읽는 검사가 섞여 있는가
 *
 * 도착 결과가 사라진 원인은 계산 오류가 아니라 **배선**이었다. 결과를
 * 포그라운드 서비스 알림에 써 넣고 곧바로 `stopSelf()` 를 불러서, 시스템이
 * 서비스 알림을 치울 때 결과가 함께 사라졌다. 계산은 전부 옳았다.
 *
 * 이런 종류는 값을 검사해서는 잡히지 않는다. 알림을 실제로 띄워 봐야 하는데
 * 그건 기기가 필요하고, 도착 판정은 목적지 50m 안에서만 일어나므로 매번
 * 학교까지 가야 한다. 그래서 **틀릴 수 있는 배선만** 소스에서 확인한다.
 */
class TripPhaseNotificationTest {

    // --- 단계 표시 ---------------------------------------------------------

    @Test
    fun `세 단계가 서로 다른 말로 구분된다`() {
        val labels = AlarmNotifications.TripPhaseLabel.entries.map { it.label }

        assertEquals(listOf("준비 중", "이동 중", "도착"), labels)
        assertEquals("겹치는 문구가 있다", labels.size, labels.toSet().size)
    }

    @Test
    fun `진행 표시가 지금 단계만 채워서 보여 준다`() {
        assertEquals(
            "● 준비 중 → ○ 이동 중 → ○ 도착",
            AlarmNotifications.TripPhaseLabel.PREPARING.breadcrumb(),
        )
        assertEquals(
            "○ 준비 중 → ● 이동 중 → ○ 도착",
            AlarmNotifications.TripPhaseLabel.IN_TRANSIT.breadcrumb(),
        )
        assertEquals(
            "○ 준비 중 → ○ 이동 중 → ● 도착",
            AlarmNotifications.TripPhaseLabel.ARRIVED.breadcrumb(),
        )
    }

    @Test
    fun `진행 표시에는 항상 채워진 칸이 하나뿐이다`() {
        AlarmNotifications.TripPhaseLabel.entries.forEach { phase ->
            val filled = phase.breadcrumb().count { it == '●' }
            assertEquals("${phase.label} 의 진행 표시가 이상하다", 1, filled)
        }
    }

    @Test
    fun `판정기 단계마다 대응하는 문구가 있다`() {
        // Phase 에 단계를 추가하고 문구를 잊으면 여기서 걸린다.
        assertEquals(
            TripGeofence.Phase.entries.size,
            AlarmNotifications.TripPhaseLabel.entries.size,
        )
    }

    // --- 도착 결과가 서비스와 함께 죽지 않는다 -----------------------------

    @Test
    fun `도착 결과 알림 id 가 서비스 알림 id 와 다르다`() {
        // 같으면 그 알림이 곧 포그라운드 서비스 알림이고, stopSelf 할 때
        // 시스템이 함께 치운다. 이것이 결과가 사라진 원인이었다.
        assertNotEquals(
            AlarmNotifications.NOTIFICATION_TRIP,
            AlarmNotifications.NOTIFICATION_ARRIVAL,
        )
        assertNotEquals(
            AlarmNotifications.NOTIFICATION_ALARM,
            AlarmNotifications.NOTIFICATION_ARRIVAL,
        )
    }

    @Test
    fun `도착 결과는 별도 채널을 쓴다`() {
        // 추적 채널은 IMPORTANCE_LOW 라서 조용하다. 그 아침의 답을 거기 띄우면
        // 사용자가 보지 못한 채 지나간다.
        assertNotEquals(
            AlarmNotifications.CHANNEL_TRIP,
            AlarmNotifications.CHANNEL_ARRIVAL,
        )
    }

    @Test
    fun `도착 결과를 멈추기 전에 띄운다`() {
        val source = readSource("sensing/TripTrackingService.kt")
        val arrived = source.indexOf("is TripEvent.Arrived ->")
        assertTrue("도착 분기를 찾지 못했다", arrived >= 0)

        val block = source.substring(arrived, source.indexOf("null -> Unit", arrived))
        val post = block.indexOf("postArrivalResult")
        val stop = block.indexOf("stopSelf()")

        assertTrue("도착 분기에서 결과 알림을 띄우지 않는다", post >= 0)
        assertTrue("도착 분기에서 stopSelf 를 부르지 않는다", stop >= 0)
        assertTrue(
            "stopSelf 를 먼저 부르면 서비스가 죽으면서 알림 기회를 잃는다.\n" +
                "postArrivalResult 를 stopSelf 앞에 둘 것.",
            post < stop,
        )
    }

    @Test
    fun `도착 알림이 스스로 사라지지 않는다`() {
        val source = readSource("alarm/AlarmNotifications.kt")
        val start = source.indexOf("fun arrivalNotification")
        assertTrue("arrivalNotification 을 찾지 못했다", start >= 0)
        val body = source.substring(start)

        assertTrue(
            "setAutoCancel(false) 가 없다. 누르면 사라져서 결과를 놓친다",
            body.contains("setAutoCancel(false)"),
        )
        assertTrue(
            "확인 버튼이 없다. 사용자가 닫을 정상 경로가 필요하다",
            body.contains("\"확인\""),
        )
        assertTrue(
            "setTimeoutAfter 가 있으면 시간이 지나 저절로 사라진다",
            !body.contains("setTimeoutAfter"),
        )
    }

    @Test
    fun `약속 시각을 일정 시작 시각으로 잰다`() {
        // 계획이 추정한 arriveAtMillis 로 재면 9시 수업에 8시 55분 도착한
        // 사람에게 "늦었다" 고 말하게 된다. 근거는 ArrivalVerdict 에 있다.
        val source = readSource("sensing/TripTrackingService.kt")
        val start = source.indexOf("ArrivalVerdict(")
        assertTrue("ArrivalVerdict 를 만드는 곳을 찾지 못했다", start >= 0)
        val block = source.substring(start, start + 400)

        assertTrue(
            "appointmentMillis 를 startAtMillis 로 주지 않는다",
            block.contains("appointmentMillis = current.startAtMillis"),
        )
    }

    // --- 내부 -------------------------------------------------------------

    private fun readSource(relative: String): String {
        val path = "src/main/java/com/swpp/wakeup/$relative"
        val candidates = listOf(
            File(path),
            File("app/$path"),
            File("../app/$path"),
            File("APP/app/$path"),
        )
        val found = candidates.firstOrNull { it.exists() }
        if (found == null) {
            fail(
                "$relative 을 찾지 못했다. 파일을 옮겼다면 이 검사도 고쳐야 한다.\n" +
                    candidates.joinToString("\n") { "  ${it.absolutePath}" }
            )
        }
        return found!!.readText()
    }
}
