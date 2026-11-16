// AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.ui.home.HomeViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 일정 추가의 [집] 버튼과 출발지 줄. 집이 있는지는 `hasHome`(서버가 없다고 하기 전까지 true)으로 정하고,
 * 집 좌표(`homePlace`)는 첫 홈 불러오기가 끝나야 생긴다.
 */
class EndpointHomeTest {

    private val home = PlaceSearchItem(
        kakaoPlaceId = null,
        name = "우리집",
        address = null,
        lat = 37.4781,
        lng = 126.9515,
        category = null,
    )

    @Test
    fun `집 정보를 불러오는 동안 출발지는 집이고 집 주소 설정으로 보내지 않는다`() {
        // Given 서버가 깨어나는 중이라 집 좌표를 아직 모른다
        val hasHome = true
        val homePlace: PlaceSearchItem? = null

        // When / Then 출발지를 비워 두면 서버가 계정의 집을 쓰므로 그대로 고를 수 있다
        assertEquals(EndpointHome.PICK, endpointHomeOf(hasHome, homePlace, needsPlace = false))
        assertEquals("집", originLineOf(HomeViewModel.AddState(), hasHome, homePlace))
    }

    @Test
    fun `집 좌표를 모르는 동안 도착지의 집은 기다린다`() {
        // 도착지는 집 좌표로 경로를 받으므로 고를 수 없다. 집 주소 설정(계정의 집을 바꿈)으로도 보내지 않는다
        assertEquals(EndpointHome.WAIT, endpointHomeOf(hasHome = true, homePlace = null, needsPlace = true))
    }

    @Test
    fun `서버가 집이 없다고 했을 때만 집 주소 설정으로 보낸다`() {
        assertEquals(EndpointHome.SET_UP, endpointHomeOf(hasHome = false, homePlace = null, needsPlace = false))
        assertEquals(EndpointHome.SET_UP, endpointHomeOf(hasHome = false, homePlace = null, needsPlace = true))
        assertEquals("출발지를 정해 주세요", originLineOf(HomeViewModel.AddState(), hasHome = false, homePlace = null))
    }

    @Test
    fun `집을 알면 고르고 출발지 줄에 집 이름을 붙인다`() {
        assertEquals(EndpointHome.PICK, endpointHomeOf(hasHome = true, homePlace = home, needsPlace = true))
        assertEquals("집 · 우리집", originLineOf(HomeViewModel.AddState(), hasHome = true, homePlace = home))
    }

    @Test
    fun `고른 출발지와 현재 위치 재기는 집보다 먼저 보인다`() {
        val picked = home.copy(kakaoPlaceId = "21160", name = "신림역")
        val state = HomeViewModel.AddState().copy(origin = picked)
        assertEquals("신림역", originLineOf(state, hasHome = true, homePlace = home))
        assertEquals("현재 위치를 확인하는 중", originLineOf(state.copy(originLocating = true), true, home))
    }
}
