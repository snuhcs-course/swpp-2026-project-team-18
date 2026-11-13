// AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 알람 시각 바꾸기 바텀시트(iter4 H2-3)의 계산과 문구 */
class AlarmTimeChoiceTest {

    @Test
    fun `세 칸 라벨은 여유 있게 보통 빠듯하게`() {
        assertEquals("여유 있게", AlarmTimeChoice.quickLabel(RiskOptionView.KEY_SAFE, "안전"))
        assertEquals("보통", AlarmTimeChoice.quickLabel(RiskOptionView.KEY_NORMAL, "보통"))
        assertEquals("빠듯하게", AlarmTimeChoice.quickLabel(RiskOptionView.KEY_GAMBLE, "도박"))
    }

    @Test
    fun `모르는 key 는 서버 라벨을 그대로 쓴다`() {
        assertEquals("아주 안전", AlarmTimeChoice.quickLabel("paranoid", "아주 안전"))
    }

    @Test
    fun `시각 문자열을 분으로 읽는다`() {
        assertEquals(7 * 60 + 20, AlarmTimeChoice.minutesOfDay("7:20", "AM"))
        assertEquals(19 * 60 + 5, AlarmTimeChoice.minutesOfDay("7:05", "PM"))
        assertEquals(0, AlarmTimeChoice.minutesOfDay("12:00", "AM"))
        assertEquals(12 * 60, AlarmTimeChoice.minutesOfDay("12:00", "PM"))
    }

    @Test
    fun `읽지 못하는 시각은 null`() {
        assertNull(AlarmTimeChoice.minutesOfDay("", "AM"))
        assertNull(AlarmTimeChoice.minutesOfDay("7시 20분", "AM"))
        assertNull(AlarmTimeChoice.minutesOfDay("7:99", "AM"))
    }

    @Test
    fun `분을 오전 오후 시각으로 적는다`() {
        assertEquals("오전 7:50", AlarmTimeChoice.clockLabel(7 * 60 + 50))
        assertEquals("오후 12:05", AlarmTimeChoice.clockLabel(12 * 60 + 5))
        assertEquals("오전 12:00", AlarmTimeChoice.clockLabel(0))
        assertEquals("오후 11:30", AlarmTimeChoice.clockLabel(23 * 60 + 30))
    }

    @Test
    fun `길이는 시간과 분으로 적는다`() {
        assertEquals("10분", AlarmTimeChoice.minutesLabel(10))
        assertEquals("1시간", AlarmTimeChoice.minutesLabel(60))
        assertEquals("1시간 10분", AlarmTimeChoice.minutesLabel(70))
        assertEquals("10분", AlarmTimeChoice.minutesLabel(-10))
    }

    @Test
    fun `버튼 조사는 끝자리를 읽은 소리를 따른다`() {
        assertEquals("오전 7:50으로 맞추기", AlarmTimeChoice.confirmLabel("오전 7:50"))
        assertEquals("오전 7:45로 맞추기", AlarmTimeChoice.confirmLabel("오전 7:45"))
    }

    @Test
    fun `추천과의 차이를 적는다`() {
        val recommended = 7 * 60 + 40
        assertEquals("추천하는 시각이에요", AlarmTimeChoice.gapLine(recommended, recommended))
        assertEquals(
            "추천(오전 7:40)보다 10분 늦은 알람이에요",
            AlarmTimeChoice.gapLine(7 * 60 + 50, recommended),
        )
        assertEquals(
            "추천(오전 7:40)보다 20분 이른 알람이에요",
            AlarmTimeChoice.gapLine(7 * 60 + 20, recommended),
        )
        assertNull(AlarmTimeChoice.gapLine(7 * 60 + 20, null))
    }

    @Test
    fun `알람부터 일정 시작까지 쓸 수 있는 분`() {
        assertEquals(80, AlarmTimeChoice.availableMinutes(7 * 60 + 40, 9 * 60))
        // 전날 밤에 울리는 알람
        assertEquals(10 * 60, AlarmTimeChoice.availableMinutes(23 * 60, 9 * 60))
    }

    @Test
    fun `분포의 누적값으로 확률을 낸다`() {
        val distribution = DurationDistributionView(
            bins = listOf(
                DurationDistributionView.Bin(60, 70, 0.5),
                DurationDistributionView.Bin(70, 80, 0.5),
            ),
            p50Minutes = 70,
            p90Minutes = 79,
            windowDays = null,
            source = "model",
        )
        assertEquals(0, AlarmTimeChoice.estimate(distribution, 60))
        assertEquals(50, AlarmTimeChoice.estimate(distribution, 70))
        assertEquals(75, AlarmTimeChoice.estimate(distribution, 75))
        assertEquals(100, AlarmTimeChoice.estimate(distribution, 90))
    }

    @Test
    fun `분포가 없으면 확률을 지어내지 않는다`() {
        assertNull(AlarmTimeChoice.estimate(null, 80))
        val empty = DurationDistributionView(
            bins = emptyList(),
            p50Minutes = 0,
            p90Minutes = 0,
            windowDays = null,
            source = "model",
        )
        assertNull(AlarmTimeChoice.estimate(empty, 80))
    }

    @Test
    fun `추천 칸은 지금 적용 중인 칸이다`() {
        assertEquals(
            RiskOptionView.KEY_SAFE,
            AlarmTimeChoice.recommendedKey(RiskOptionView.KEY_SAFE, "수업 일정 기본값"),
        )
    }

    @Test
    fun `직접 고른 값이면 종류 기본값을 알 수 없어 보통에 붙인다`() {
        assertEquals(
            RiskOptionView.KEY_NORMAL,
            AlarmTimeChoice.recommendedKey(RiskOptionView.KEY_SAFE, AlarmTimeChoice.MANUAL_TAU_SOURCE),
        )
        assertEquals(RiskOptionView.KEY_NORMAL, AlarmTimeChoice.recommendedKey(null, null))
    }
}
