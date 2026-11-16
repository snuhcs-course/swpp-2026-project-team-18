// AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.ui.events.AddEventScreen
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 일정 추가(⑫)의 [집] 버튼과 저장 버튼 위의 이유 줄.
 *
 * - 집이 있는지는 `hasHome`(서버가 없다고 하기 전까지 true)으로 정한다. 서버가 깨어나는 동안 [집] 이 ⑭ 집 주소를
 *   열면, 그대로 저장한 사용자가 계정의 집을 바꾼다.
 * - 버튼이 꺼진 이유는 버튼 바로 위에 보인다. 제목 칸은 긴 폼의 맨 위에 있다.
 */
@RunWith(AndroidJUnit4::class)
class AddEventScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val home = PlaceSearchItem(
        kakaoPlaceId = null,
        name = "우리집",
        address = null,
        lat = 37.4781,
        lng = 126.9515,
        category = null,
    )

    private var setHomeCalls = 0
    private var originHomeCalls = 0
    private var destinationHomeCalls = 0

    private fun show(state: HomeViewModel.AddState, hasHome: Boolean, homePlace: PlaceSearchItem?) {
        compose.setContent {
            JitTheme {
                AddEventScreen(
                    state = state,
                    hasHome = hasHome,
                    homePlace = homePlace,
                    tags = emptyList(),
                    onTitleChange = {},
                    onDateChange = {},
                    onTimeChange = { _, _ -> },
                    onRepeatDayToggle = {},
                    onTagChange = {},
                    onQueryChange = {},
                    onSearch = {},
                    onPlaceSelect = {},
                    onDestinationHome = { destinationHomeCalls++ },
                    onLoadMore = {},
                    onSortChange = {},
                    onOpenMap = {},
                    onOpenPlaceUrl = {},
                    onOriginQueryChange = {},
                    onOriginSearch = {},
                    onOriginSelect = { if (it == null) originHomeCalls++ },
                    onOriginLoadMore = {},
                    onOriginSortChange = {},
                    onOriginOpenMap = {},
                    onUseCurrentLocation = {},
                    onSetHome = { setHomeCalls++ },
                    onPickRoute = {},
                    onSubmit = {},
                    onBack = {},
                )
            }
        }
    }

    @Test
    fun `집 정보를 불러오는 동안 집 버튼이 집 주소 설정을 열지 않는다`() {
        // Given 서버가 깨어나는 중이다. 집은 있다고 보지만 좌표를 아직 모른다
        show(HomeViewModel.AddState(), hasHome = true, homePlace = null)

        // When 출발지 · 도착지의 [집] 을 누른다
        compose.onNodeWithTag("origin_home").performScrollTo().performClick()
        compose.onNodeWithTag("destination_home").performScrollTo().performClick()

        // Then 출발지는 집(서버가 계정의 집을 쓴다)이고, 도착지 [집] 은 꺼져 있다. ⑭ 로 보내지 않는다
        assertEquals(0, setHomeCalls)
        assertEquals(1, originHomeCalls)
        assertEquals(0, destinationHomeCalls)
        compose.onNodeWithTag("destination_home").assertIsNotEnabled()
        compose.onNodeWithText("출발지를 정해 주세요").assertDoesNotExist()
    }

    @Test
    fun `서버가 집이 없다고 했으면 집 버튼이 집 주소 설정으로 보낸다`() {
        show(HomeViewModel.AddState(), hasHome = false, homePlace = null)

        compose.onNodeWithTag("origin_home").performScrollTo().performClick()

        assertEquals(1, setHomeCalls)
        compose.onNodeWithText("출발지를 정해 주세요").assertExists()
    }

    @Test
    fun `제목이 비면 저장 버튼 위에 이유가 보이고 버튼은 꺼져 있다`() {
        show(HomeViewModel.AddState(), hasHome = true, homePlace = home)

        compose.onNodeWithTag("add_submit_hint").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("일정 제목을 입력하면 추가할 수 있어요").assertExists()
        compose.onNodeWithTag("event_submit").assertIsNotEnabled()
    }

    @Test
    fun `제목이 있으면 이유 줄이 없다`() {
        show(HomeViewModel.AddState().copy(title = "회의"), hasHome = true, homePlace = home)

        compose.onNodeWithTag("add_submit_hint").assertDoesNotExist()
    }
}
