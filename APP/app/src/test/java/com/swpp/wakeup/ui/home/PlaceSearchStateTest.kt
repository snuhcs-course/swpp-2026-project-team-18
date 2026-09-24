package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.PlaceSearchResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 장소 검색 상태의 판단들.
 *
 * "더 보기" 를 그릴지, 건수를 어떻게 적을지가 여기서 갈린다. 둘 다 틀리면
 * 조용히 이상해진다 — 없는 다음 페이지를 계속 부르거나, 45건에서 끝나는
 * 목록 옆에 14만이 적힌다.
 */
class PlaceSearchStateTest {

    private fun place(name: String, distance: Int? = null) = PlaceSearchItem(
        kakaoPlaceId = name,
        name = name,
        address = "주소",
        lat = 37.0,
        lng = 127.0,
        category = null,
        distanceM = distance,
    )

    private fun state(
        results: Int = 0,
        isEnd: Boolean = true,
        reachable: Int = 0,
        searched: Boolean = true,
        searching: Boolean = false,
        loadingMore: Boolean = false,
    ) = HomeViewModel.PlaceSearch(
        query = "카페",
        results = List(results) { place("결과$it") },
        isEnd = isEnd,
        reachableCount = reachable,
        searched = searched,
        searching = searching,
        loadingMore = loadingMore,
    )

    // --- 더 보기 ----------------------------------------------------------

    @Test
    fun `마지막 페이지면 더 보기가 없다`() {
        assertFalse(state(results = 45, isEnd = true).canLoadMore)
    }

    @Test
    fun `다음 페이지가 있으면 더 보기가 있다`() {
        assertTrue(state(results = 15, isEnd = false).canLoadMore)
    }

    @Test
    fun `이미 받는 중이면 더 보기를 막는다`() {
        // 막지 않으면 연달아 눌러 같은 페이지를 여러 번 붙인다.
        assertFalse(state(results = 15, isEnd = false, searching = true).canLoadMore)
        assertFalse(state(results = 15, isEnd = false, loadingMore = true).canLoadMore)
    }

    // --- 건수 문구 --------------------------------------------------------

    @Test
    fun `검색 전에는 건수를 적지 않는다`() {
        assertNull(state(searched = false).countLabel)
    }

    @Test
    fun `결과가 없으면 건수를 적지 않는다`() {
        assertNull(state(results = 0).countLabel)
    }

    @Test
    fun `일부만 받았으면 받아 볼 수 있는 수와 함께 적는다`() {
        // **total_count 를 쓰지 않는다.** 카카오는 "카페" 에 14만을 주는데
        // 실제로 받아 볼 수 있는 것은 45건이다.
        val s = state(results = 15, isEnd = false, reachable = 45)
        assertEquals("45건 중 15건", s.countLabel)
    }

    @Test
    fun `전부 받았으면 받은 수만 적는다`() {
        assertEquals("45건", state(results = 45, reachable = 45).countLabel)
    }

    @Test
    fun `서버가 도달 가능 수를 안 주면 받은 수로 적는다`() {
        // 옛 서버는 reachable_count 가 없다. 0건이라고 적으면 거짓말이다.
        assertEquals("3건", state(results = 3, reachable = 0).countLabel)
    }

    // --- 검색어 변경 ------------------------------------------------------

    @Test
    fun `검색어만 바꾸고 결과는 남긴다`() {
        // 타이핑하는 동안 목록이 사라지면 화면이 깜빡인다. 실제 갱신은 검색을
        // 눌렀을 때 일어난다.
        val before = state(results = 5, isEnd = false, reachable = 45)
        val after = before.withQuery("레드포스")

        assertEquals("레드포스", after.query)
        assertEquals(5, after.results.size)
    }

    // --- 기본값 ----------------------------------------------------------

    @Test
    fun `처음 상태는 더 보기도 건수도 없다`() {
        val fresh = HomeViewModel.PlaceSearch()
        assertFalse(fresh.canLoadMore)
        assertNull(fresh.countLabel)
        assertFalse("검색하기 전에 결과 없음을 띄우면 안 된다", fresh.searched)
        assertEquals(PlaceSearchResponse.SORT_ACCURACY, fresh.sort)
        assertFalse(fresh.hasDistances)
    }
}
