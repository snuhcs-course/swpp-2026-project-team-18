// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기기에만 둔 다른 집(todo §2 "집 여러 개"). */
class SavedHomesTest {

    private fun home(id: String, lat: Double = 37.26, lng: Double = 127.03, label: String = "본가") =
        SavedHome(id = id, label = label, name = "인계동 아파트", address = "경기 수원시 팔달구 인계동", lat = lat, lng = lng)

    @Test
    fun `새 집이 앞에 오고 다섯 개까지만 둔다`() {
        var homes = emptyList<SavedHome>()
        (1..7).forEach { homes = SavedHomes.add(homes, home("h$it", lat = 37.0 + it * 0.1)) }
        assertEquals(SavedHomes.MAX, homes.size)
        assertEquals(listOf("h7", "h6", "h5", "h4", "h3"), homes.map { it.id })
    }

    @Test
    fun `같은 자리의 집은 새것으로 바꾼다`() {
        val first = home("a")
        val near = home("b", lat = first.lat + 0.0002, label = "본가(새)")
        val far = home("c", lat = first.lat + 0.01)
        val homes = SavedHomes.add(SavedHomes.add(listOf(first), near), far)
        assertEquals(listOf("c", "b"), homes.map { it.id })
        assertEquals("본가(새)", homes.last().label)
    }

    @Test
    fun `기본으로 바꾸면 그 집은 빠지고 예전 기본 집이 들어간다`() {
        val homes = listOf(home("a"), home("b", lat = 37.5))
        val previous = home("old", lat = 37.48, label = "이전 집")
        assertEquals(listOf("old", "b"), SavedHomes.promote(homes, "a", previous).map { it.id })
        assertEquals("예전 집이 없으면 빼기만 한다", listOf("b"), SavedHomes.promote(homes, "a", null).map { it.id })
    }

    @Test
    fun `이름이 비면 장소 이름을 쓰고 길면 자른다`() {
        assertEquals("본가", SavedHomes.labelOf("  본가 ", "인계동 아파트"))
        assertEquals("인계동 아파트", SavedHomes.labelOf("  ", "인계동 아파트"))
        assertEquals(SavedHomes.LABEL_MAX, SavedHomes.labelOf("가".repeat(30), "x").length)
    }

    @Test
    fun `50m 안이면 같은 자리다`() {
        val a = GeoPoint(37.5, 127.0)
        assertTrue(SavedHomes.samePlace(a, GeoPoint(37.5003, 127.0)))
        assertFalse(SavedHomes.samePlace(a, GeoPoint(37.5006, 127.0)))
        assertFalse(SavedHomes.samePlace(a, GeoPoint(37.5003, 127.0005)))
    }

    @Test
    fun `한 줄에는 이름과 주소를 쓴다`() {
        assertEquals("본가 · 경기 수원시 팔달구 인계동", home("a").line)
        assertEquals("주소가 없으면 장소 이름", "본가 · 인계동 아파트", home("a").copy(address = null).line)
        assertEquals("이름과 같으면 한 번만", "인계동 아파트", home("a").copy(label = "", address = null).line)
    }

    @Test
    fun `깨진 디스크 사본은 쓰지 않는다`() {
        val gson = Gson()
        val type = object : TypeToken<List<SavedHome?>>() {}.type
        val parsed: List<SavedHome?> = gson.fromJson(
            """[{"id":"a","label":"본가","name":"x","lat":37.2,"lng":127.0},
                {"label":"이름만"},
                {"id":"b","name":"0,0","lat":0.0,"lng":0.0},
                {"id":"c","name":"범위 밖","lat":95.0,"lng":127.0}]""",
            type,
        )
        val usable = parsed.filterNotNull().filter { runCatching { it.isUsable }.getOrDefault(false) }
        assertEquals(listOf("a"), usable.map { it.id })
    }
}
