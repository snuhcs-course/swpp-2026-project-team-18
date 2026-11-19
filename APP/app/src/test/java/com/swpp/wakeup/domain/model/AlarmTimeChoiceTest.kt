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

    // AI-generated (Claude) — 11-19 세 화면 확률 통일
    @Test
    fun `시트의 확률은 서버 값만 쓴다`() {
        val start = 11 * 60
        val current = 9 * 60 + 21
        // 지금 알람이면 서버가 그 알람에 준 확률(홈 · 알람 화면과 같은 값)
        assertEquals(97, AlarmTimeChoice.shownProbability(current, current, 97, cellProbability = 90, startMinutes = start))
        // 세 칸 중 하나와 같은 시각이면 그 칸의 확률
        assertEquals(99, AlarmTimeChoice.shownProbability(8 * 60 + 50, current, 97, cellProbability = 99, startMinutes = start))
        // 서버가 확률을 주지 않은 시각은 비운다(앱이 계산하지 않는다)
        assertNull(AlarmTimeChoice.shownProbability(9 * 60 + 5, current, 97, cellProbability = null, startMinutes = start))
        // 지금 알람의 확률이 없으면 지어내지 않는다
        assertNull(AlarmTimeChoice.shownProbability(current, current, null, cellProbability = null, startMinutes = start))
    }

    // Partly AI-generated (Claude)
    @Test
    fun `세 칸이나 지금 알람이 5분 단위가 아니면 1분 휠로 연다`() {
        assertEquals(5, AlarmTimeChoice.wheelStep(listOf(7 * 60 + 20, 7 * 60 + 40, 8 * 60 + 5, null)))
        // 서버 칸이 7:43 이면 5분 휠은 7:40 으로 보이고 다른 시각을 저장할 수 있다
        assertEquals(1, AlarmTimeChoice.wheelStep(listOf(7 * 60 + 43, 7 * 60 + 50)))
        assertEquals(1, AlarmTimeChoice.wheelStep(listOf(7 * 60 + 20, 7 * 60 + 41)))
        assertEquals(5, AlarmTimeChoice.wheelStep(emptyList()))
    }
}
