package com.swpp.wakeup.ui.events

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 선택 카드가 Figma 77:2(13-a)의 주황 테두리·도착정보 우선순위를 따르는지 소스 감사.
 * Compose UI 는 기기 없이 픽셀 테스트하기 어려워 배선만 확인한다. 계산·색 값 자체는
 * 다른 단위 테스트가 본다.
 *
 * 예전 Figma(77:20, 지금은 삭제됨)는 남색 테두리였다. 현재 Figma 는 주황 테두리 +
 * 주황 막대(고른 카드) / 초록 막대(나머지)다.
 */
class RouteChoiceSelectionStyleTest {

    private val source by lazy { readSource("ui/events/RouteChoiceScreen.kt") }

    // Partly AI-generated (Claude)
    @Test
    fun `선택 경로는 주황 점 없이 주황 테두리만 쓴다`() {
        val start = source.indexOf("private fun RouteCard")
        val end = source.indexOf("private fun SegmentBar", start)
        assertTrue("RouteCard 를 찾지 못했다", start >= 0 && end > start)
        val card = source.substring(start, end)

        assertTrue("주황 테두리를 쓰지 않는다", card.contains("color = JitColor.Accent,"))
        assertTrue("Figma 의 2dp 테두리와 다르다", card.contains("width = 2.dp"))
        assertFalse(
            "자동차·도보·자전거 빠르기 막대는 뺐다(사용자 결정 10-05)",
            card.contains("JitProgressBar"),
        )
        assertFalse(
            "선택 카드에 주황 점이 다시 생겼다",
            card.contains("if (selected) {\n                Spacer"),
        )
    }

    // Partly AI-generated (Claude)
    @Test
    fun `상세 체크포인트는 선택한 카드에만 펼친다`() {
        assertTrue(
            "모든 카드가 펼쳐지면 경로 비교가 불가능해진다",
            source.contains("if (selected) {\n            val rows = timelineRows(") ||
                source.contains("if (selected) {\r\n            val rows = timelineRows("),
        )
    }

    // Partly AI-generated (Claude)
    @Test
    fun `다음 차량 도착정보와 수단 이름은 그리지 않는다`() {
        assertFalse("차량 도착 행이 다시 생겼다", source.contains("CheckpointArrivalRow"))
        assertFalse("차량 도착정보를 다시 읽는다", source.contains("VehicleArrival"))
        val start = source.indexOf("private fun RouteCard")
        val end = source.indexOf("private fun SegmentBar", start)
        assertFalse("\"지하철+도보+버스\" 수단 이름이 다시 생겼다", source.substring(start, end).contains("text = option.mode"))
    }

    // Partly AI-generated (Claude)
    @Test
    fun `구간 막대 라벨은 글리프를 수직 중앙에 세운다`() {
        val start = source.indexOf("private fun SegmentBar")
        assertTrue("SegmentBar 를 찾지 못했다", start >= 0)
        val end = source.indexOf("internal fun barSegments", start)
        val bar = source.substring(start, if (end > start) end else source.length)

        assertTrue("칸 안에서 가운데 정렬이 빠졌다", bar.contains("Alignment.Center"))
        assertTrue(
            "폰트 여백 때문에 글리프가 아래로 3dp 밀린다. 측정 근거는 " +
                "jit-tools/measure_bar.py",
            bar.contains("JitTextStyle.TightCentered"),
        )
    }

    private fun readSource(relative: String): String {
        val path = "src/main/java/com/swpp/wakeup/$relative"
        val candidates = listOf(File(path), File("app/$path"), File("../app/$path"), File("APP/app/$path"))
        return candidates.firstOrNull(File::exists)?.readText()
            ?: error("$relative 을 찾지 못했다: ${candidates.joinToString { it.absolutePath }}")
    }
}
