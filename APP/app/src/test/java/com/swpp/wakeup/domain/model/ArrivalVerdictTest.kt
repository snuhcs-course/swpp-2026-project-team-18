package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 도착 판정 계산.
 *
 * 이 계산은 아침에 딱 한 번, 목적지 50m 안에서만 일어난다. 실기기에서
 * 확인하려면 매번 학교까지 가야 하므로 경계는 전부 여기서 고정한다.
 */
class ArrivalVerdictTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    /** 2026-09-23 09:30 KST — 스크린샷의 그 수업. */
    private val appointment = ZonedDateTime
        .of(2026, 9, 23, 9, 30, 0, 0, seoul)
        .toInstant()
        .toEpochMilli()

    private fun arriveAt(hour: Int, minute: Int, second: Int = 0): ArrivalVerdict =
        ArrivalVerdict(
            arrivedAtMillis = ZonedDateTime
                .of(2026, 9, 23, hour, minute, second, 0, seoul)
                .toInstant()
                .toEpochMilli(),
            appointmentMillis = appointment,
        )

    @Test
    fun `약속보다 먼저 닿으면 일찍이다`() {
        val v = arriveAt(9, 25)

        assertEquals(5, v.marginMinutes)
        assertEquals(ArrivalVerdict.Status.EARLY, v.status)
        assertEquals("5분 일찍 도착했습니다", v.headline)
    }

    @Test
    fun `약속보다 늦게 닿으면 늦게다`() {
        val v = arriveAt(9, 42)

        assertEquals(-12, v.marginMinutes)
        assertEquals(ArrivalVerdict.Status.LATE, v.status)
        assertEquals("12분 늦게 도착했습니다", v.headline)
    }

    @Test
    fun `분 단위로 같으면 정시다`() {
        val v = arriveAt(9, 30)

        assertEquals(0, v.marginMinutes)
        assertEquals(ArrivalVerdict.Status.ON_TIME, v.status)
        assertEquals("정시에 도착했습니다", v.headline)
    }

    @Test
    fun `0분 일찍이라는 말을 만들지 않는다`() {
        // 반올림해서 0이 되는 구간. "0분 일찍 도착했습니다" 는 말이 안 된다.
        listOf(arriveAt(9, 29, 40), arriveAt(9, 30, 20)).forEach { v ->
            assertEquals("정시에 도착했습니다", v.headline)
            assertEquals(ArrivalVerdict.Status.ON_TIME, v.status)
        }
    }

    @Test
    fun `한 시간을 넘으면 시간과 분으로 읽는다`() {
        // 95분 일찍을 "95분" 으로 쓰면 한 번 더 계산해야 읽힌다.
        assertEquals("1시간 35분 일찍 도착했습니다", arriveAt(7, 55).headline)
        assertEquals("1시간 일찍 도착했습니다", arriveAt(8, 30).headline)
        assertEquals("2시간 일찍 도착했습니다", arriveAt(7, 30).headline)
    }

    @Test
    fun `59분은 그대로 분이다`() {
        assertEquals("59분 일찍 도착했습니다", arriveAt(8, 31).headline)
    }

    @Test
    fun `근거 줄에 실제 도착과 약속 시각이 함께 있다`() {
        // 제목만 있으면 사용자가 판정을 검산할 수 없다. 앱이 엉뚱한 시각을
        // 잡았을 때 이 줄에서 드러나야 한다.
        assertEquals("9:25 도착 · 약속 9:30", arriveAt(9, 25).detail(seoul))
    }

    @Test
    fun `근거 줄은 24시간제로 적는다`() {
        val afternoon = ArrivalVerdict(
            arrivedAtMillis = ZonedDateTime
                .of(2026, 9, 23, 14, 5, 0, 0, seoul).toInstant().toEpochMilli(),
            appointmentMillis = ZonedDateTime
                .of(2026, 9, 23, 14, 0, 0, 0, seoul).toInstant().toEpochMilli(),
        )

        // 오전·오후를 붙이지 않으므로 14:05 가 2:05 로 보이면 안 된다.
        assertEquals("14:05 도착 · 약속 14:00", afternoon.detail(seoul))
        assertEquals("5분 늦게 도착했습니다", afternoon.headline)
    }

    @Test
    fun `계획된 도착 예정이 아니라 약속 시각과 비교한다`() {
        // 계획이 "9:20 도착 예정" 이라고 했는데 9:25 에 닿은 경우.
        // 계획 기준이면 "5분 늦음" 이지만 9:30 수업에는 늦지 않았다.
        // 사용자에게 지각이라고 말하면 사실이 아니고 앱을 믿지 못하게 된다.
        val v = arriveAt(9, 25)

        assertEquals(ArrivalVerdict.Status.EARLY, v.status)
        assertEquals("5분 일찍 도착했습니다", v.headline)
    }

    @Test
    fun `자정을 넘는 약속도 계산이 맞는다`() {
        // 날짜가 넘어가도 epoch 차이로 계산하므로 영향이 없어야 한다.
        val midnight = ArrivalVerdict(
            arrivedAtMillis = ZonedDateTime
                .of(2026, 9, 23, 23, 50, 0, 0, seoul).toInstant().toEpochMilli(),
            appointmentMillis = ZonedDateTime
                .of(2026, 9, 24, 0, 10, 0, 0, seoul).toInstant().toEpochMilli(),
        )

        assertEquals(20, midnight.marginMinutes)
        assertEquals("20분 일찍 도착했습니다", midnight.headline)
        assertEquals("23:50 도착 · 약속 0:10", midnight.detail(seoul))
    }

    @Test
    fun `추적 마감 한 시간까지의 지각을 표현할 수 있다`() {
        // trackingDeadlineMillis 가 일정 시작 + 1시간이라 실제로 나올 수 있는
        // 가장 늦은 판정이 이 근처다.
        assertEquals("58분 늦게 도착했습니다", arriveAt(10, 28).headline)
    }

    @Test
    fun `분 형식은 경계에서 흔들리지 않는다`() {
        assertEquals("0분", ArrivalVerdict.formatSpan(0))
        assertEquals("1분", ArrivalVerdict.formatSpan(1))
        assertEquals("59분", ArrivalVerdict.formatSpan(59))
        assertEquals("1시간", ArrivalVerdict.formatSpan(60))
        assertEquals("1시간 1분", ArrivalVerdict.formatSpan(61))
        assertEquals("1시간 59분", ArrivalVerdict.formatSpan(119))
        assertEquals("2시간", ArrivalVerdict.formatSpan(120))
    }
}
