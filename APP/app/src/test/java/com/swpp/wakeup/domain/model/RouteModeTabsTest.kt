// AI-generated (Claude)
package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** 경로 선택 화면의 수단 탭 나누기와 처음 열 탭. */
class RouteModeTabsTest {

    private fun option(key: String, minutes: Int) =
        RouteOption(key, key, minutes, "${minutes}분", "", null)

    private fun choice(options: List<RouteOption>, selected: String? = null) =
        RouteChoice("신림역", "서울대", options, selected)

    @Test
    fun `key 로 수단을 나눈다`() {
        assertEquals(RouteMode.CAR, option("car", 14).travelMode)
        assertEquals(RouteMode.WALK, option("walk", 60).travelMode)
        assertEquals(RouteMode.BICYCLE, option("bicycle", 24).travelMode)
        assertEquals(RouteMode.TRANSIT, option("transit:2호선>5513", 23).travelMode)
    }

    @Test
    fun `탭 안의 순서는 서버 순서 그대로`() {
        val c = choice(listOf(option("transit:a", 23), option("car", 14), option("transit:b", 27)))
        assertEquals(listOf("transit:a", "transit:b"), c.optionsOf(RouteMode.TRANSIT).map { it.key })
        assertEquals(emptyList<String>(), c.optionsOf(RouteMode.WALK).map { it.key })
    }

    @Test
    fun `도보 자전거는 1개만 보여준다`() {
        val c = choice(listOf(option("walk", 60), option("walk", 65), option("bicycle", 24), option("bicycle", 30)))
        assertEquals(1, c.optionsOf(RouteMode.WALK).size)
        assertEquals(1, c.optionsOf(RouteMode.BICYCLE).size)
        assertEquals(60, c.optionsOf(RouteMode.WALK).single().minutes)
    }

    @Test
    fun `처음 탭은 고른 후보의 수단`() {
        val c = choice(listOf(option("car", 14), option("transit:a", 23)), selected = "transit:a")
        assertEquals(RouteMode.TRANSIT, c.defaultMode())
    }

    @Test
    fun `고른 게 없으면 가장 빠른 후보의 수단`() {
        val c = choice(listOf(option("transit:a", 23), option("car", 14), option("walk", 60)))
        assertEquals(RouteMode.CAR, c.defaultMode())
    }

    @Test
    fun `후보가 없으면 대중교통`() {
        assertEquals(RouteMode.TRANSIT, choice(emptyList()).defaultMode())
    }
}
