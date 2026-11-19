// Partly AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.domain.model.CalendarLinkState
import com.swpp.wakeup.domain.model.Inquiry
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.me.MeTabLogic
import com.swpp.wakeup.ui.me.MeTabScreen
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 내 정보 › "문제 신고"(PM A-7)가 도움말과 같은 문의 시트를 신고로 연다 */
@RunWith(AndroidJUnit4::class)
class ProblemReportRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun 문제_신고_줄이_신고_시트를_연다() {
        var sent: Pair<String, String>? = null
        compose.setContent {
            JitTheme {
                MeTabScreen(
                    state = HomeViewModel.UiState(nickname = "수연"),
                    routineNames = emptyList(),
                    calendarLink = CalendarLinkState(),
                    permissions = MeTabLogic.Permissions(notifications = true, fullScreen = true, preciseLocation = true),
                    onHomes = {}, onPrep = {}, onRoutine = {}, onCalendar = {}, onPermissions = {}, onSettings = {},
                    onHelp = {},
                    onSendReport = { message, email ->
                        sent = message to email
                        Inquiry.Outcome.DONE
                    },
                    contactEmail = "me@snu.ac.kr",
                )
            }
        }
        compose.onNodeWithTag("me_report").performScrollTo().performClick()
        compose.onNodeWithTag("contact_title").assertTextEquals("문제 신고")
        compose.onNodeWithTag("contact_message").performTextInput("알람 화면이 안 떴어요 오전 7시쯤")
        compose.onNodeWithTag("contact_send").performClick()
        compose.waitForIdle()
        // 시트는 적은 글 그대로 넘긴다. "[문제 신고]" 는 보내는 쪽(Inquiry.requestOf)이 붙인다 — 단위 테스트가 본다
        assertEquals("알람 화면이 안 떴어요 오전 7시쯤" to "me@snu.ac.kr", sent)
    }
}
