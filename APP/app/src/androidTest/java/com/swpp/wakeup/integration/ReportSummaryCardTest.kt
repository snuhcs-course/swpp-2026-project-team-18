// Partly AI-generated (Claude)
package com.swpp.wakeup.integration

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.wakeup.ui.report.ReportPreviewData
import com.swpp.wakeup.ui.report.WeeklyReportScreen
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** 주간 리포트에 서버 요약 문장이 그대로 보인다(11-18). 요약이 없으면 그 자리만 비운다 */
@RunWith(AndroidJUnit4::class)
class ReportSummaryCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(report: com.swpp.wakeup.domain.model.WeeklyReportView) = compose.setContent {
        JitTheme {
            WeeklyReportScreen(
                report = report, loading = false, error = null,
                onPreviousWeek = {}, onNextWeek = {}, onRetry = {}, onOpenDay = { _, _ -> },
                today = LocalDate.of(2030, 1, 1),
            )
        }
    }

    @Test
    fun 서버_요약_문장을_그대로_보인다() {
        val report = ReportPreviewData.exampleWeek
        show(report)
        compose.onNodeWithTag("report_summary_text").performScrollTo().assertTextEquals(report.summary!!.text)
        compose.onNodeWithTag("report_probability").performScrollTo()
    }

    @Test
    fun 요약이_없으면_카드만_빠지고_나머지는_그린다() {
        show(ReportPreviewData.exampleWeek.copy(summary = null))
        compose.onNodeWithTag("report_summary").assertDoesNotExist()
        compose.onNodeWithTag("report_probability").performScrollTo()
    }
}
