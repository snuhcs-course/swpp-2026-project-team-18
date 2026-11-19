// Partly AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.ui.events.AddEventScreen
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** C8: 앱이 자동으로 채운 경로는 경로 칸 아래에 "지난번에 고른 경로" 로 드러난다 */
@RunWith(AndroidJUnit4::class)
class RouteAutoFieldTest {

    @get:Rule
    val compose = createComposeRule()

    private val place = PlaceSearchItem(
        kakaoPlaceId = "111", name = "관악캠퍼스 302동", address = "관악로 1", lat = 37.45, lng = 126.95, category = null,
    )
    private val home = PlaceSearchItem(
        kakaoPlaceId = null, name = "집", address = "봉천로", lat = 37.48, lng = 126.95, category = null,
    )

    private fun show(state: HomeViewModel.AddState) = compose.setContent {
        JitTheme {
            AddEventScreen(
                state = state, hasHome = true, homePlace = home, tags = emptyList(),
                onTitleChange = {}, onDateChange = {}, onTimeChange = { _, _ -> }, onRepeatDayToggle = {},
                onTagChange = {}, onQueryChange = {}, onSearch = {}, onPlaceSelect = {}, onDestinationHome = {},
                onLoadMore = {}, onSortChange = {}, onOpenMap = {}, onOpenPlaceUrl = {}, onOriginQueryChange = {},
                onOriginSearch = {}, onOriginSelect = {}, onOriginLoadMore = {}, onOriginSortChange = {},
                onOriginOpenMap = {}, onUseCurrentLocation = {}, onSetHome = {}, onPickRoute = {}, onSubmit = {},
                onBack = {},
            )
        }
    }

    @Test
    fun 자동으로_채운_경로는_표시가_붙는다() {
        show(
            HomeViewModel.AddState(
                selectedPlace = place, routeKey = "transit:5516", routeLabel = "27분 · 버스",
                routeAutoCaption = "지난번에 고른 경로",
            )
        )
        compose.onNodeWithText("27분 · 버스").assertExists()
        compose.onNodeWithTag("route_auto").performScrollTo().assertTextEquals("지난번에 고른 경로")
    }

    @Test
    fun 직접_고른_경로에는_표시가_없다() {
        show(HomeViewModel.AddState(selectedPlace = place, routeKey = "transit:5516", routeLabel = "27분 · 버스"))
        compose.onNodeWithText("27분 · 버스").assertExists()
        compose.onNodeWithTag("route_auto").assertDoesNotExist()
    }
}
