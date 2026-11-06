// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.data.remote.SavedPlaceDto
import com.swpp.wakeup.data.remote.SavedPlacesListDto
import com.swpp.wakeup.testing.fromJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 저장한 집의 서버 동기화 규칙(B2): 서버 행 → 앱 모델, bulk 항목, 목록 합치기. */
class SavedHomeSyncTest {
    private fun row(
        id: Long? = 7,
        uuid: String? = "u-7",
        label: String? = "본가",
        name: String? = "인계동 아파트",
        lat: Double? = 37.26,
        lng: Double? = 127.03,
    ) = SavedPlaceDto(id, uuid, label, name, "경기 수원시", lat, lng)

    private fun home(id: String, serverId: Long? = null, lat: Double = 37.26, label: String = "본가") =
        SavedHome(id = id, label = label, name = "인계동 아파트", address = null, lat = lat, lng = 127.03, serverId = serverId)

    @Test
    fun `서버 행은 client_uuid 를 화면 키로 쓰고 server id 를 남긴다`() {
        val h = row().toSavedHome()!!
        assertEquals("u-7", h.id)
        assertEquals(7L, h.serverId)
        assertEquals("본가", h.label)
        assertEquals("경기 수원시", h.address)
    }

    @Test
    fun `client_uuid 가 비면 server-id 를 키로 쓴다`() {
        assertEquals("server-7", row(uuid = null).toSavedHome()!!.id)
        assertEquals("server-7", row(uuid = "  ").toSavedHome()!!.id)
    }

    @Test
    fun `이름과 label 은 한쪽이 비면 다른 쪽으로 채운다`() {
        val noLabel = row(label = " ").toSavedHome()!!
        assertEquals("인계동 아파트", noLabel.label)
        val noName = row(name = null).toSavedHome()!!
        assertEquals("본가", noName.name)
    }

    @Test
    fun `id 나 좌표가 없거나 쓸 수 없는 행은 버린다`() {
        assertNull(row(id = null).toSavedHome())
        assertNull(row(lat = null).toSavedHome())
        assertNull(row(lng = null).toSavedHome())
        assertNull(row(label = null, name = null).toSavedHome())
        assertNull(row(lat = 0.0, lng = 0.0).toSavedHome())
    }

    @Test
    fun `목록 응답은 results 만 쓰고 깨진 행은 뺀다`() {
        val dto: SavedPlacesListDto = fromJson(
            """{"default":{"id":null,"label":"기본","lat":37.4,"lng":126.9},
                "results":[{"id":1,"client_uuid":"a","label":"본가","name":"x","lat":37.2,"lng":127.0},
                           {"id":2,"label":"좌표 없음"}]}""",
        )
        assertEquals(listOf("a"), dto.toSavedHomes().map { it.id })
        assertEquals(emptyList<SavedHome>(), SavedPlacesListDto().toSavedHomes())
    }

    @Test
    fun `bulk 항목은 20개까지, 이름은 40자까지 보내고 기기 id 를 client_uuid 로 쓴다`() {
        val homes = (1..25).map { home("h$it", label = "가".repeat(60)) }
        val items = bulkItemsOf(homes)
        assertEquals(SAVED_PLACES_BULK_MAX, items.size)
        assertEquals(SAVED_PLACE_LABEL_MAX, items.first().label.length)
        assertEquals("h1", items.first().clientUuid)
        assertEquals("인계동 아파트", items.first().name)
    }

    @Test
    fun `bulk 항목의 이름이 비면 장소 이름을 쓴다`() {
        assertEquals("인계동 아파트", bulkItemsOf(listOf(home("a", label = ""))).single().label)
    }

    @Test
    fun `한도 때문에 못 올린 항목만 센다`() {
        val dto: SavedPlacesListDto = fromJson(
            """{"results":[],"skipped":[{"client_uuid":"a","reason":"limit"},
                {"client_uuid":"b","reason":"same_as_default"},{"client_uuid":"c","reason":"limit"}]}""",
        )
        assertEquals(2, skippedOverLimit(dto))
        assertEquals(0, skippedOverLimit(SavedPlacesListDto()))
    }

    @Test
    fun `merge 는 같은 server id 를 바꾸고 새 행은 맨 앞에 넣는다`() {
        val list = listOf(home("a", serverId = 1), home("b", serverId = 2))
        val replaced = SavedHomes.merge(list, home("b2", serverId = 2, label = "새 이름"))
        assertEquals(listOf("a", "b2"), replaced.map { it.id })
        assertEquals("새 이름", replaced.last().label)
        val prepended = SavedHomes.merge(list, home("c", serverId = 3))
        assertEquals(listOf("c", "a", "b"), prepended.map { it.id })
        assertEquals(listOf("x", "a", "b"), SavedHomes.merge(list, home("x", serverId = null)).map { it.id })
    }
}