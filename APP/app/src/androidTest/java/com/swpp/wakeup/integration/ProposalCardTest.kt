// Partly AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.domain.model.AlarmProposal
import com.swpp.wakeup.ui.alarm.ProposalCard
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/** "알람을 바꿀까요?" 카드(BE-H3d): 서버 문구 · 확률 · 두 버튼 */
@RunWith(AndroidJUnit4::class)
class ProposalCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private fun at(h: Int, m: Int) = ZonedDateTime.now(zone).plusDays(1).withHour(h).withMinute(m).withSecond(0).withNano(0)

    private val proposal = AlarmProposal(
        kind = "recommended_moved",
        currentAtMillis = at(7, 40).toInstant().toEpochMilli(),
        proposedAtMillis = at(7, 28).toInstant().toEpochMilli(),
        proposedAt = at(7, 28).toOffsetDateTime().toString(),
        moveMinutes = -12,
        reasonText = "이동 시간이 12분 늘었어요",
        keepProbability = 61,
        changeProbability = 95,
        answerByMillis = at(7, 18).toInstant().toEpochMilli(),
        createdAt = null,
    )

    @Test
    fun showsReasonProbabilityAndAnswers() {
        val answers = mutableListOf<Boolean>()
        compose.setContent {
            JitTheme { ProposalCard(proposal, busy = false, onAccept = { answers += true }, onKeep = { answers += false }) }
        }
        compose.onNodeWithText("이동 시간이 12분 늘었어요").assertExists()
        compose.onNodeWithText("그대로 두면 정시 도착 확률 61% · 바꾸면 95%").assertExists()
        compose.onNodeWithText("오전 7:28로 앞당기기").performClick()
        compose.onNodeWithText("그대로 두기").performClick()
        assertEquals(listOf(true, false), answers)
    }

    @Test
    fun busyDisablesBothButtons() {
        compose.setContent { JitTheme { ProposalCard(proposal, busy = true, onAccept = {}, onKeep = {}) } }
        compose.onNodeWithTag("alarm_proposal_accept").assertIsNotEnabled()
        compose.onNodeWithTag("alarm_proposal_keep").assertIsNotEnabled()
    }
}
