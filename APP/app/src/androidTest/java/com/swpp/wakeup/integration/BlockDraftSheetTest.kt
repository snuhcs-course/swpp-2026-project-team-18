// AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.domain.model.BlockDraft
import com.swpp.wakeup.ui.routines.BlockDraftSheet
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 루틴 항목 폼의 저장 · 삭제 실패. 폼은 목록 대신 그려지므로 목록의 오류 줄은 폼이 열려 있는 동안 보이지 않는다.
 * 칸에 붙지 않는 실패(통신 · 서버 · 지워진 항목)는 폼 안, 저장 버튼 위에 보여야 한다.
 */
@RunWith(AndroidJUnit4::class)
class BlockDraftSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val draft = BlockDraft(id = 1, name = "샤워", minText = "12", maxText = "18")

    private fun show(draft: BlockDraft) {
        compose.setContent {
            JitTheme {
                BlockDraftSheet(
                    draft = draft,
                    saving = false,
                    onName = {},
                    onMin = {},
                    onMax = {},
                    onDropCost = {},
                    onParallel = {},
                    onIncluded = {},
                    onSave = {},
                    onDelete = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun `칸에 붙지 않는 저장 실패가 폼 안에 보인다`() {
        // Given 저장이 통신 문제로 실패했다
        show(draft.withSaveFailure("서버에 연결할 수 없다."))

        // When 저장 버튼 둘레로 내려간다
        compose.onNodeWithTag("block_form_error").performScrollTo()

        // Then 무엇을 못 했는지와 이유가 폼 안에 보인다
        compose.onNodeWithTag("block_form_error").assertIsDisplayed()
        compose.onNodeWithText("저장하지 못했어요. 서버에 연결할 수 없다.").assertExists()
    }

    @Test
    fun `실패가 없으면 폼 오류 줄이 없다`() {
        show(draft)

        compose.onNodeWithTag("block_form_error").assertDoesNotExist()
    }
}
