package com.swpp.wakeup.data.remote

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 장소 검색 응답 파싱과 거리 표기.
 *
 * ## 왜 JSON 으로 검사하는가
 *
 * Gson 은 Kotlin 의 non-null 선언과 기본값을 **무시한다.** 응답에 키가 없으면
 * non-null 필드에도 null 이 박히고, 그 값을 읽는 순간 죽는다. 이 프로젝트에서
 * 실제로 두 번 그랬다 — `RouteSegmentDto` 와 `AuthResponse` 다.
 *
 * 그래서 "서버가 옛 버전이면" 을 JSON 으로 재현한다. 새 필드를 넣을 때마다
 * 구버전 서버에 붙을 수 있고, 그때 앱이 죽으면 안 된다.
 */
class PlaceSearchItemTest {

    private val gson = Gson()

    @Test
    fun `서버가 주는 전체 필드를 읽는다`() {
        val json = """
            {
              "kakao_place_id": "1234567890",
              "name": "레드포스PC",
              "address": "서울 관악구 봉천로 12",
              "jibun_address": "서울 관악구 봉천동 1685-1",
              "lat": 37.4800760249697,
              "lng": 126.952141877838,
              "category": "가정,생활 > 여가시설 > 게임방,PC방",
              "category_group": "게임방,PC방",
              "distance_m": 203,
              "phone": "070-8666-6554",
              "place_url": "http://place.map.kakao.com/1234567890"
            }
        """.trimIndent()

        val item = gson.fromJson(json, PlaceSearchItem::class.java)

        assertEquals("레드포스PC", item.name)
        assertEquals("게임방,PC방", item.categoryGroup)
        assertEquals(203, item.distanceM)
        assertEquals("서울 관악구 봉천동 1685-1", item.jibunAddress)
        assertEquals("070-8666-6554", item.phone)
        assertTrue(item.placeUrl!!.startsWith("http"))
    }

    @Test
    fun `새 필드가 없는 응답도 읽는다`() {
        // 배포 서버가 아직 옛 버전일 수 있다. 그때 앱이 죽으면 안 된다.
        val json = """
            {
              "kakao_place_id": "1",
              "name": "서울대학교",
              "address": "서울 관악구 관악로 1",
              "lat": 37.4601,
              "lng": 126.9520,
              "category": "교육,학문 > 학교 > 대학교"
            }
        """.trimIndent()

        val item = gson.fromJson(json, PlaceSearchItem::class.java)

        assertEquals("서울대학교", item.name)
        assertNull(item.categoryGroup)
        assertNull(item.distanceM)
        assertNull(item.placeUrl)
        assertNull("거리를 모르면 표기도 없다", item.distanceLabel)
    }

    @Test
    fun `거리를 미터와 킬로미터로 나눠 쓴다`() {
        fun label(m: Int?) = item(distanceM = m).distanceLabel

        assertEquals("0m", label(0))
        assertEquals("203m", label(203))
        assertEquals("999m", label(999))
        // 1km 부터는 소수 한 자리다. "1000m" 보다 "1.0km" 가 읽기 쉽다.
        assertEquals("1.0km", label(1000))
        assertEquals("1.1km", label(1090))
        assertEquals("12.5km", label(12_500))
        assertNull(label(null))
    }

    @Test
    fun `페이지 응답의 기본값이 안전하다`() {
        // 응답이 비어 있어도 화면이 "더 보기" 를 그리거나 널을 읽으면 안 된다.
        val page = gson.fromJson("{}", PlaceSearchResponse::class.java)

        assertTrue(page.results.isEmpty())
        assertEquals(1, page.page)
        assertTrue("다음 페이지가 있다고 하면 무한히 부른다", page.isEnd)
        assertEquals(PlaceSearchResponse.SORT_ACCURACY, page.sort)
        assertFalse(page.degraded)
    }

    @Test
    fun `페이지 응답의 건수 두 가지를 구분해 읽는다`() {
        val json = """
            {
              "results": [],
              "page": 2,
              "total_count": 142759,
              "reachable_count": 45,
              "is_end": false,
              "sort": "distance"
            }
        """.trimIndent()

        val page = gson.fromJson(json, PlaceSearchResponse::class.java)

        // 카카오가 말하는 전체와 실제로 받아 볼 수 있는 수가 다르다.
        // 화면에 total_count 를 쓰면 45건에서 끝나는 목록 옆에 14만이 적힌다.
        assertEquals(142_759, page.totalCount)
        assertEquals(45, page.reachableCount)
        assertFalse(page.isEnd)
        assertEquals(PlaceSearchResponse.SORT_DISTANCE, page.sort)
    }

    private fun item(distanceM: Int?) = PlaceSearchItem(
        kakaoPlaceId = "1",
        name = "어디",
        address = "주소",
        lat = 37.0,
        lng = 127.0,
        category = null,
        distanceM = distanceM,
    )
}
