// Partly AI-generated (Claude)
package com.swpp.wakeup.integration

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.wakeup.domain.model.RouteChoice
import com.swpp.wakeup.domain.model.RouteOption
import com.swpp.wakeup.ui.events.RouteChoiceScreen
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 경로 선택(⑬) 대중교통 탭의 카드 일곱 장과 배지 세 개(todo §2 "대중교통 후보 7개 화면 확인",
 * transit-candidates.md §7). 서버를 부르지 않고 화면만 그린다.
 *
 * 화면 사진을 앱 전용 폴더에 남긴다(`files/route-choice-*.png`). 배지가 한 줄에 들어가는지는
 * 사람이 그 사진으로 본다 — 줄바꿈 대신 잘림(maxLines 1)이라 노드 검사로는 알 수 없다.
 */
@RunWith(AndroidJUnit4::class)
class RouteChoiceLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private fun transit(index: Int, badges: List<String>) = RouteOption(
        key = "transit:case$index",
        mode = if (index % 2 == 0) "지하철+도보" else "버스+지하철+도보",
        minutes = 28 + index * 3,
        minutesLabel = "${28 + index * 3}분",
        detailLine = "2호선 → 55${index}0 · ${5 + index}.2km · 환승 ${index % 3}회 · 1,${500 + index * 50}원",
        badge = badges.firstOrNull(),
        badges = badges,
    )

    private val options = listOf(
        transit(1, listOf("최단 시간", "최소 환승", "최소 도보")),
        transit(2, listOf("지하철만", "최소 비용")),
        transit(3, listOf("버스만")),
        transit(4, listOf("최소 도보")),
        transit(5, listOf("혼합")),
        transit(6, emptyList()),
        transit(7, emptyList()),
    )

    @Test
    fun `대중교통 카드 일곱 장이 모두 그려진다`() {
        compose.setContent {
            JitTheme {
                RouteChoiceScreen(
                    state = HomeViewModel.RouteState(
                        loading = false,
                        choice = RouteChoice(
                            originLabel = "집",
                            destination = "서울대 302동",
                            options = options,
                            selectedKey = options.first().key,
                        ),
                    ),
                    onSelect = {},
                    onConfirm = {},
                    onRetry = {},
                    onBack = {},
                )
            }
        }

        compose.onNodeWithTag("route_${options.first().key}").performScrollTo().assertIsDisplayed()
        save("route-choice-top")
        options.forEach { option ->
            compose.onNodeWithTag("route_${option.key}").performScrollTo().assertIsDisplayed()
        }
        save("route-choice-bottom")
    }

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val file = File(dir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }
}
