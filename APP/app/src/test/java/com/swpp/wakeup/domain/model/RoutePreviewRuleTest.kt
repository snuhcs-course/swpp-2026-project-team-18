// AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⑬ 카드를 눌러 지도 미리보기를 열 수 있는지(A1-3).
 *
 * 서버는 도보·자전거 후보에만 좌표를 준다(BE-23). 좌표가 없는 구버전 응답이나
 * 대중교통·자동차 카드에서 미리보기를 열면 빈 지도가 뜬다.
 */
class RoutePreviewRuleTest {

    private val line = listOf(GeoPoint(37.48, 126.95), GeoPoint(37.46, 126.95))

    private fun option(key: String, path: List<GeoPoint>) =
        RouteOption(key, key, 10, "10분", "", null, routePath = path)

    @Test
    fun `도보와 자전거는 두 점 이상이면 미리보기를 연다`() {
        assertTrue(option("walk", line).canPreviewMap)
        assertTrue(option("bicycle", line).canPreviewMap)
    }

    @Test
    fun `좌표가 한 점이거나 없으면 열지 않는다`() {
        assertFalse(option("walk", line.take(1)).canPreviewMap)
        assertFalse(option("bicycle", emptyList()).canPreviewMap)
    }

    @Test
    fun `대중교통과 자동차는 좌표가 있어도 열지 않는다`() {
        assertFalse(option("transit:2호선>5513", line).canPreviewMap)
        assertFalse(option("car", line).canPreviewMap)
    }
}
