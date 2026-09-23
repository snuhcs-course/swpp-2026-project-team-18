package com.swpp.wakeup.ui.events

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 선택 카드가 Figma 77:20 의 남색 테두리·도착정보 우선순위를 따르는지 소스 감사.
 * Compose UI 는 기기 없이 픽셀 테스트하기 어려워, 다시 주황 점으로 돌아가기 쉬운
 * 배선만 확인한다. 계산·색 값 자체는 다른 단위 테스트가 본다.
 */
class RouteChoiceSelectionStyleTest {

    private val source by lazy { readSource("ui/events/RouteChoiceScreen.kt") }

    @Test
    fun `선택 경로는 주황 점 없이 서울대 남색 테두리만 쓴다`() {
        val start = source.indexOf("private fun RouteCard")
        val end = source.indexOf("private fun SegmentBar", start)
        assertTrue("RouteCard 를 찾지 못했다", start >= 0 && end > start)
        val card = source.substring(start, end)

        assertTrue("서울대 남색 테두리를 쓰지 않는다", card.contains("JitColor.SnuNavyBorder"))
        assertTrue("Figma 의 2dp 테두리와 다르다", card.contains("width = 2.dp"))
        assertFalse(
            "선택 카드에 주황 점이 다시 생겼다",
            card.contains("if (selected) {\n                Spacer"),
        )
    }

    @Test
    fun `상세 체크포인트는 선택한 카드에만 펼친다`() {
        assertTrue(
            "모든 카드가 펼쳐지면 경로 비교가 불가능해진다",
            source.contains("if (selected && option.hasCheckpoints)"),
        )
    }

    @Test
    fun `다음 차량과 그다음 차량은 서로 다른 색이다`() {
        val start = source.indexOf("private fun CheckpointArrivalRow")
        assertTrue("CheckpointArrivalRow 를 찾지 못했다", start >= 0)
        val body = source.substring(start).take(1000)

        assertTrue(body.contains("JitColor.ArrivalNext"))
        assertTrue(body.contains("JitColor.ArrivalLater"))
        assertTrue(body.indexOf("ArrivalNext") < body.indexOf("ArrivalLater"))
    }

    @Test
    fun `차량 도착정보는 승차 행이 아니라 별도 행이다`() {
        assertTrue(source.contains("is RouteCheckpoint.VehicleArrival -> CheckpointArrivalRow"))
        assertTrue(source.contains("text = checkpoint.vehicleLabel"))
        assertTrue(source.contains("text = checkpoint.arrival.displayText"))
    }

    private fun readSource(relative: String): String {
        val path = "src/main/java/com/swpp/wakeup/$relative"
        val candidates = listOf(File(path), File("app/$path"), File("../app/$path"), File("APP/app/$path"))
        return candidates.firstOrNull(File::exists)?.readText()
            ?: error("$relative 을 찾지 못했다: ${candidates.joinToString { it.absolutePath }}")
    }
}
