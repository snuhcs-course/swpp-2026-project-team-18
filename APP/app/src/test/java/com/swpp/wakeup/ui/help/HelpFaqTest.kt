// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.help

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 도움말(Figma `551:1820`)과 "정시 도착 확률이 뭐예요?" 시트(`548:1798`) — iter4 H2-4 */
class HelpFaqTest {

    @Test
    fun `질문 여덟 개가 Figma 순서와 문구 그대로다`() {
        assertEquals(
            listOf(
                "알람 시각은 어떻게 정해지나요?",
                "정시 도착 확률은 뭐예요?",
                "준비 시간은 어떻게 정하나요?",
                "쓸수록 알람이 어떻게 맞춰지나요?",
                "여유 있게·보통·빠듯하게는 뭐가 달라요?",
                "알람 시각을 직접 바꿀 수 있나요?",
                "캘린더 일정은 어떻게 가져오나요?",
                "알람이 울리지 않았어요",
            ),
            HelpFaq.items.map { it.question },
        )
        assertEquals(HelpTopic.entries.toList(), HelpFaq.items.map { it.topic })
        assertTrue(HelpFaq.items.all { it.answer.isNotBlank() })
        assertEquals("8개", HelpFaq.countLabel(HelpFaq.items.size))
    }

    @Test
    fun `답은 코드의 실제 값을 말한다`() {
        // planning/risk_options.py
        assertTrue(HelpFaq.item(HelpTopic.RISK_LEVELS).answer.containsAll("97번", "90번", "65번"))
        // planning/buffers.py MIN 4 · MAX 15 · 늘릴 때 5 · 줄일 때 2, train-models.yml 23:17, refresh 7일
        assertTrue(
            HelpFaq.item(HelpTopic.LEARNING).answer
                .containsAll("11시 17분", "4분에서 15분", "5분까지", "2분씩", "7일"),
        )
        // PrepOnboardingScreen 5~240분, prep_observations 0.5~1.5배
        assertTrue(HelpFaq.item(HelpTopic.PREP_TIME).answer.containsAll("5분~4시간", "절반", "1.5배"))
        // CalendarImportScreen 앞으로 2주 · 고른 것만
        assertTrue(HelpFaq.item(HelpTopic.CALENDAR).answer.containsAll("2주", "고른 것만", "하루 종일"))
        // AlarmScheduler 7일치 · MissedAlarms 강제 중지 · BootReceiver 재부팅
        assertTrue(HelpFaq.item(HelpTopic.ALARM_DIDNT_RING).answer.containsAll("강제 중지", "7일치", "다시 켜거나"))
        // 분 단위로 고르는 H2-3 시트는 아직 없다 — 없는 기능을 있다고 쓰지 않는다
        assertTrue(HelpFaq.item(HelpTopic.CHANGE_ALARM).answer.contains("준비 중"))
    }

    @Test
    fun `답에 내부 용어를 쓰지 않는다`() {
        val banned = listOf("τ", "타우", "버퍼", "분위수", "카카오", "API", "서버")
        HelpFaq.items.forEach { item ->
            banned.forEach { word -> assertFalse("${item.topic} 에 $word", item.answer.contains(word)) }
        }
    }

    @Test
    fun `처음에는 첫 질문, i 로 오면 그 질문을 펼친다`() {
        assertEquals(HelpTopic.ALARM_TIME, HelpFaq.initialExpanded(null))
        assertEquals(HelpTopic.CALENDAR, HelpFaq.initialExpanded(HelpTopic.CALENDAR))
    }

    @Test
    fun `하나만 펼치고 다시 누르면 접는다`() {
        assertEquals(HelpTopic.PREP_TIME, HelpFaq.toggle(HelpTopic.ALARM_TIME, HelpTopic.PREP_TIME))
        assertNull(HelpFaq.toggle(HelpTopic.PREP_TIME, HelpTopic.PREP_TIME))
        assertEquals(HelpTopic.ALARM_TIME, HelpFaq.toggle(null, HelpTopic.ALARM_TIME))
    }

    @Test
    fun `질문과 답의 낱말로 찾고 띄어쓰기는 보지 않는다`() {
        assertEquals(8, HelpFaq.search("  ").size)
        assertEquals(listOf(HelpTopic.ALARM_DIDNT_RING), HelpFaq.search("강제 중지").map { it.topic })
        // 답에만 있는 낱말
        assertTrue(HelpFaq.search("걷는 속도").any { it.topic == HelpTopic.LEARNING })
        // 띄어쓰기
        assertEquals(HelpFaq.search("준비 시간").map { it.topic }, HelpFaq.search("준비시간").map { it.topic })
        // 낱말이 모두 있어야 한다
        assertEquals(listOf(HelpTopic.CALENDAR), HelpFaq.search("캘린더 2주").map { it.topic })
        assertTrue(HelpFaq.search("없는낱말").isEmpty())
        // 계산식 칩은 화면에 있으니 찾힌다
        assertTrue(HelpFaq.search("7:40").any { it.topic == HelpTopic.ALARM_TIME })
    }

    @Test
    fun `찾은 뒤 펼친 질문이 빠지면 첫 결과를 펼친다`() {
        val ring = HelpFaq.search("강제 중지")
        assertEquals(HelpTopic.ALARM_DIDNT_RING, HelpFaq.expandAfterSearch(HelpTopic.ALARM_TIME, ring))
        val all = HelpFaq.search("")
        assertEquals("그대로 있으면 바꾸지 않는다", HelpTopic.PREP_TIME, HelpFaq.expandAfterSearch(HelpTopic.PREP_TIME, all))
        assertNull(HelpFaq.expandAfterSearch(HelpTopic.ALARM_TIME, emptyList()))
    }

    @Test
    fun `펼친 질문은 따로, 접힌 질문은 이어지는 것끼리 묶는다`() {
        val first = HelpFaq.groups(HelpFaq.items, HelpTopic.ALARM_TIME)
        assertEquals(2, first.size)
        assertTrue(first[0] is FaqGroup.Expanded)
        assertEquals(7, (first[1] as FaqGroup.Collapsed).items.size)

        val middle = HelpFaq.groups(HelpFaq.items, HelpTopic.RISK_LEVELS)
        assertEquals(listOf(4, 1, 3), middle.map { if (it is FaqGroup.Collapsed) it.items.size else 1 })

        val none = HelpFaq.groups(HelpFaq.items, null)
        assertEquals(1, none.size)
    }

    @Test
    fun `답은 문단과 글머리 줄로 나뉜다`() {
        val paragraphs = HelpFaq.paragraphs(HelpFaq.item(HelpTopic.ALARM_DIDNT_RING).answer)
        assertEquals(3, paragraphs.size)
        assertEquals(listOf(true, true, true), paragraphs[1].map { it.bullet })
        assertFalse(paragraphs[1][0].text.startsWith("•"))
    }

    @Test
    fun `계산식은 시작 빼기 여유 이동 준비가 알람이다`() {
        assertEquals(
            listOf(FormulaChip.Kind.START, FormulaChip.Kind.BUFFER, FormulaChip.Kind.TRAVEL, FormulaChip.Kind.PREP, FormulaChip.Kind.RESULT),
            HelpFaq.formula.map { it.kind },
        )
        // 9:00 − 10 − 42 − 28 = 7:40
        val minutes = HelpFaq.formula.filter { it.label != null }.sumOf { it.value.removeSuffix("분").toInt() }
        assertEquals(80, minutes)
    }

    // --- i 시트 -------------------------------------------------------------

    @Test
    fun `원은 확률을 10퍼센트 단위로 내린 수만큼 칠한다`() {
        assertEquals(9, OnTimeSheetText.timesOutOfTen(97))
        assertEquals(9, OnTimeSheetText.timesOutOfTen(90))
        assertEquals(8, OnTimeSheetText.timesOutOfTen(89))
        assertEquals(6, OnTimeSheetText.timesOutOfTen(65))
        assertEquals(10, OnTimeSheetText.timesOutOfTen(100))
        assertEquals(0, OnTimeSheetText.timesOutOfTen(9))
        assertEquals(0, OnTimeSheetText.timesOutOfTen(-3))
    }

    @Test
    fun `문장은 10번 중 n번이고 n번만 강조한다`() {
        val s = OnTimeSheetText.sentence(97, "오전 9:00")
        assertEquals("9번", s.emphasis)
        assertEquals("같은 아침이 10번 온다면\n9번은 오전 9:00 전에 도착한다는 뜻이에요.", s.text)
        assertEquals("오전 7:40에 일어나면", OnTimeSheetText.head("오전 7:40"))
        assertEquals("정시 도착 확률 90%", OnTimeSheetText.percentLabel(90))
        // 0번은 어색하다
        assertEquals("한 번도", OnTimeSheetText.sentence(5, "오전 9:00").emphasis)
    }

    @Test
    fun `시각은 오전 오후로 쓴다`() {
        val zone = ZoneId.of("Asia/Seoul")
        fun at(h: Int, m: Int) = LocalDateTime.of(2026, 11, 11, h, m).atZone(zone).toInstant().toEpochMilli()
        assertEquals("오전 7:40", OnTimeSheetText.clock(at(7, 40), zone))
        assertEquals("오후 12:05", OnTimeSheetText.clock(at(12, 5), zone))
        assertEquals("오전 12:00", OnTimeSheetText.clock(at(0, 0), zone))
        assertEquals("오후 11:59", OnTimeSheetText.clock(at(23, 59), zone))
    }

    private fun String.containsAll(vararg words: String) = words.all { contains(it) }
}
