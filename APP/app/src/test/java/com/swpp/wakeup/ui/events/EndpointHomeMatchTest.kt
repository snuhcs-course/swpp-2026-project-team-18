package com.swpp.wakeup.ui.events

import com.swpp.wakeup.data.remote.PlaceSearchItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 도착지 줄의 집 버튼은 고른 장소가 저장된 집이면 주황이다.
 *
 * 검색 결과와 프로필의 집은 객체가 다르고 이름도 다를 수 있어(예 "우리집" 과
 * "관악구 ○○아파트") 좌표로 비교한다.
 */
class EndpointHomeMatchTest {

    private fun place(name: String, lat: Double, lng: Double) =
        PlaceSearchItem(kakaoPlaceId = null, name = name, address = null, lat = lat, lng = lng, category = null)

    @Test
    fun `좌표가 같으면 이름이 달라도 같은 곳`() {
        assertTrue(place("우리집", 37.4812, 126.9527).isSameSpot(place("관악아파트", 37.4812, 126.9527)))
    }

    @Test
    fun `좌표 반올림 차이 정도는 같은 곳`() {
        assertTrue(place("a", 37.48120, 126.95270).isSameSpot(place("b", 37.48124, 126.95266)))
    }

    @Test
    fun `몇백 미터 떨어지면 다른 곳`() {
        assertFalse(place("집", 37.4812, 126.9527).isSameSpot(place("학교", 37.4600, 126.9520)))
    }
}
