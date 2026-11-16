// AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.calendar.CalendarEvent
import com.swpp.wakeup.domain.model.CalendarImportState
import com.swpp.wakeup.domain.model.ImportCandidate
import com.swpp.wakeup.ui.calendar.CalendarImportScreen
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 캘린더 가져오기 화면의 버튼 둘레.
 *
 * - 보내기 실패는 목록 읽기 실패("불러오지 못했음" 카드)와 다르다. 버튼 바로 위에 보이고, 버튼이 같은 선택을 다시
 *   보낸다 — 카드의 다시 시도는 목록을 다시 읽어 고른 것을 되돌렸다.
 * - 고른 줄의 장소를 찾는 중이면 버튼이 꺼지고 이유가 보인다.
 * - 권한 안내의 "읽기만" 은 굵게 보이고 별표가 없다.
 */
@RunWith(AndroidJUnit4::class)
class CalendarImportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private var imports = 0
    private var reloads = 0

    private fun candidate(id: String, selected: Boolean = true, resolving: Boolean = false) = ImportCandidate(
        source = CalendarEvent(
            externalId = id,
            title = "일정 $id",
            startAtMillis = 4_102_444_800_000L, // 2100-01-01. 보낼 때 시작 전이다
            startAtIso = "2100-01-01T09:00:00+09:00",
            location = if (resolving) "302동" else null,
            calendarName = null,
        ),
        selected = selected,
        alreadyImported = false,
        resolving = resolving,
        whenLabel = "1월 1일 금 09:00",
    )

    private fun show(state: CalendarImportState) {
        compose.setContent {
            JitTheme {
                CalendarImportScreen(
                    state = state,
                    onBack = {},
                    onToggle = {},
                    onRequestPermission = {},
                    onRetry = { reloads++ },
                    onImport = { imports++ },
                )
            }
        }
    }

    @Test
    fun `가져오기 실패는 버튼 위에 보이고 버튼이 같은 선택을 다시 보낸다`() {
        // Given 하나를 끄고 보냈다가 통신 문제로 실패했다
        show(
            CalendarImportState(
                candidates = listOf(candidate("a"), candidate("b", selected = false)),
                linkedCount = 1,
                importError = "서버에 연결할 수 없다.",
            ),
        )

        // When 버튼 둘레로 내려가 다시 누른다
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("import_error"))
        compose.onNodeWithTag("import_error").assertIsDisplayed()
        compose.onNodeWithText("1건 다시 가져오기").performClick()

        // Then 목록 읽기 실패 카드는 없고, 목록을 다시 읽지 않고 보내기만 다시 한다
        compose.onNodeWithText("불러오지 못했음").assertDoesNotExist()
        assertEquals(1, imports)
        assertEquals(0, reloads)
    }

    @Test
    fun `고른 줄의 장소를 찾는 중이면 버튼이 꺼지고 이유가 보인다`() {
        // Given 고른 줄 하나가 아직 장소를 찾는 중이다
        show(
            CalendarImportState(
                candidates = listOf(candidate("a"), candidate("b", resolving = true)),
                linkedCount = 1,
            ),
        )

        // When 버튼 둘레로 내려간다
        compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("import_wait_hint"))

        // Then 이유가 보이고 버튼은 꺼져 있다
        compose.onNodeWithTag("import_wait_hint").assertIsDisplayed()
        compose.onNodeWithText("2건 가져오기").assertIsNotEnabled()
    }

    @Test
    fun `권한 안내에 별표가 보이지 않는다`() {
        // Given 캘린더 권한이 없다
        show(CalendarImportState(permissionDenied = true))

        // When / Then "읽기만" 은 있고 마크다운 별표는 없다
        compose.onNodeWithText("읽기만", substring = true).assertExists()
        compose.onNodeWithText("**", substring = true).assertDoesNotExist()
    }
}
