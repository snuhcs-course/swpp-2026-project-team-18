// AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⑬ 카드에서 지도 미리보기를 열 수 있는지(A1-3, 10-13 모든 수단으로 확대).
 *
 * 좌표가 있으면 수단과 상관없이 연다. 좌표가 없는 구버전 응답에서 열면 빈 지도가 뜬다.
 * 카드를 누르면 바로 지도로 가는 것은 경로가 하나뿐인 수단만이다 — 대중교통 카드는
 * 누르면 고르기(정류장 목록)이고 지도는 "지도로 보기" 로 연다.
 */
class RoutePreviewRuleTest {

    private val line = listOf(GeoPoint(37.48, 126.95), GeoPoint(37.46, 126.95))

    private fun option(key: String, path: List<GeoPoint>) =
        RouteOption(key, key, 10, "10분", "", null, routePath = path)

    @Test
    fun `좌표가 두 점 이상이면 모든 수단이 미리보기를 연다`() {
        for (key in listOf("walk", "bicycle", "car", "transit:2호선>5513")) {
            assertTrue(key, option(key, line).canPreviewMap)
        }
    }

    @Test
    fun `좌표가 한 점이거나 없으면 열지 않는다`() {
        assertFalse(option("walk", line.take(1)).canPreviewMap)
        assertFalse(option("bicycle", emptyList()).canPreviewMap)
        assertFalse("구버전 서버는 자동차 좌표를 주지 않는다", option("car", emptyList()).canPreviewMap)
        assertFalse(option("transit:5516", emptyList()).canPreviewMap)
    }

    @Test
    fun `경로가 하나뿐인 수단은 카드를 누르면 지도로 간다`() {
        assertTrue(option("car", line).opensPreviewOnTap)
        assertTrue(option("walk", line).opensPreviewOnTap)
        assertTrue(option("bicycle", line).opensPreviewOnTap)
    }

    @Test
    fun `대중교통 카드는 누르면 고르기만 한다`() {
        // 후보가 여럿이다. 누를 때마다 전체 화면 지도로 가면 정류장 목록을 비교할 수 없다.
        assertFalse(option("transit:2호선>5513", line).opensPreviewOnTap)
        assertTrue(option("transit:2호선>5513", line).canPreviewMap)
    }

    @Test
    fun `좌표가 없으면 누르면 고르기만 한다`() {
        assertFalse(option("car", emptyList()).opensPreviewOnTap)
    }
}
