package com.swpp.wakeup.integration

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.ui.report.WeeklyReportScreen
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// AI-generated (Claude)
/**
 * ⑧ 주간 리포트의 ‹ . 읽기만 하는 화면이라 불러오는 중에도 나갈 수 있어야 한다 — 서버가 깨어나는 동안
 * ‹ 만 막혀 있으면 누른 것이 그냥 사라지고, 시스템 뒤로와도 다르다.
 */
@RunWith(AndroidJUnit4::class)
class WeeklyReportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `불러오는 중에도 ‹ 를 누르면 나간다`() {
        // Given 첫 주를 받는 중이라 내용 없이 불러오는 중이다
        var backs = 0
        compose.setContent {
            JitTheme {
                WeeklyReportScreen(
                    report = null,
                    longTerm = null,
                    loading = true,
                    error = null,
                    onBack = { backs++ },
                    onPreviousWeek = {},
                    onNextWeek = {},
                    onRetry = {},
                    onOpenDay = { _, _ -> },
                )
            }
        }

        // When
        compose.onNodeWithText("‹").performClick()

        // Then
        assertEquals(1, backs)
    }
}
