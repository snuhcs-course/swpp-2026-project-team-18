package com.swpp.wakeup.integration

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.RoomCreateInput
import com.swpp.wakeup.data.repository.RoomsRepository
import com.swpp.wakeup.data.repository.RoomsResult
import com.swpp.wakeup.domain.model.AppointmentRoom
import com.swpp.wakeup.domain.model.RoomDetail
import com.swpp.wakeup.domain.model.RoomMember
import com.swpp.wakeup.domain.model.RoomWakeSuggestion
import com.swpp.wakeup.ui.rooms.RoomDetailScreen
import com.swpp.wakeup.ui.rooms.RoomsListScreen
import com.swpp.wakeup.ui.rooms.RoomsScreen
import com.swpp.wakeup.ui.rooms.RoomsViewModel
import com.swpp.wakeup.ui.rooms.RoomPlaceSearcher
import com.swpp.wakeup.ui.theme.JitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.io.File
import org.junit.Assert.assertTrue

/** Fake server values; UI composition test. Live room creation and actual alarm delivery require BE/device QA. */
@RunWith(AndroidJUnit4::class)
class RoomsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun 방_보기를_누르면_멤버별_기상_시각과_서버_조정_안내가_보인다() {
        val room = AppointmentRoom(12, "금요일 모임", OffsetDateTime.parse("2026-12-04T09:00:00+09:00"), "서울대입구역", .9, 2)
        val detail = RoomDetail(room, "ABC123", listOf(
            RoomMember("김진호", true, OffsetDateTime.parse("2026-12-04T07:10:00+09:00"), 0),
            RoomMember("박준하", false, OffsetDateTime.parse("2026-12-04T06:55:00+09:00"), 5),
        ), RoomWakeSuggestion("박준하", 5))
        val initial = RoomsViewModel.UiState(rooms = listOf(room), loaded = true,
            create = RoomsViewModel.CreateDraft(startAt = LocalDateTime.of(2026, 12, 4, 9, 0)))
        compose.setContent {
            JitTheme {
                var opened by remember { mutableStateOf(false) }
                if (opened) RoomDetailScreen(initial.copy(page = RoomsViewModel.Page.DETAIL, detail = detail), {}, { opened = false })
                else RoomsListScreen(initial, {}, {}, { opened = true }, {}, {})
            }
        }
        compose.onNodeWithTag("room_open_12").performClick()
        compose.onNodeWithTag("room_members").assertIsDisplayed()
        compose.onNodeWithText("김진호 · 나").assertIsDisplayed()
        compose.onNodeWithText("박준하").assertIsDisplayed()
        compose.onNodeWithText("5분 더 일찍 일어나면 좋아요").assertExists()
        compose.onNodeWithText("박준하 님의 알람을 5분 앞당기면 목표에 가까워져요").assertExists()
        compose.onNodeWithText("집 주소와 이동 경로는 서로 공유하지 않아요").assertExists()
        compose.onNodeWithTag("rooms_back").performClick()
        compose.onNodeWithTag("rooms_list").assertIsDisplayed()
    }

    /** No account/network: exercise the real host and ViewModel against a fake repository, saving all four pages. */
    @Test fun 약속방_네_화면을_가짜_서버로_이동하고_검토용_이미지를_남긴다() {
        val room = AppointmentRoom(12, "금요일 모임", OffsetDateTime.parse("2026-12-04T09:00:00+09:00"), "서울대입구역", .9, 2)
        val detail = RoomDetail(room, "ABC123", listOf(
            RoomMember("김진호", true, OffsetDateTime.parse("2026-12-04T07:10:00+09:00"), 0),
            RoomMember("박준하", false, OffsetDateTime.parse("2026-12-04T06:55:00+09:00"), 5),
        ), RoomWakeSuggestion("박준하", 5))
        val fake = object : RoomsRepository {
            override suspend fun list(): RoomsResult<List<AppointmentRoom>> = RoomsResult.Success(listOf(room))
            override suspend fun create(input: RoomCreateInput): RoomsResult<RoomDetail> = RoomsResult.Success(detail)
            override suspend fun join(code: String): RoomsResult<RoomDetail> = RoomsResult.Success(detail)
            override suspend fun detail(id: Long): RoomsResult<RoomDetail> = RoomsResult.Success(detail)
        }
        val place = PlaceSearchItem("123", "서울대입구역", "서울 관악구 남부순환로 1822", 37.4813, 126.9527, null)
        val vm = RoomsViewModel(fake, RoomPlaceSearcher { RoomsResult.Success(listOf(place)) },
            now = { LocalDateTime.of(2026, 11, 27, 12, 0) }, zone = ZoneId.of("Asia/Seoul"))
        compose.setContent { JitTheme { RoomsScreen(vm, onExit = {}) } }
        compose.onNodeWithTag("room_open_12").assertIsDisplayed()
        save("rooms-list")

        compose.onNodeWithTag("rooms_create").performClick()
        compose.onNodeWithTag("room_title").performTextInput("금요일 모임")
        compose.onNodeWithTag("room_place_query").performTextInput("서울대입구역")
        compose.onNodeWithTag("room_place_search").performScrollTo().performClick()
        compose.onNodeWithTag("room_place_0").performScrollTo().performClick()
        compose.onNodeWithTag("rooms_back").performScrollTo()
        save("rooms-create")
        compose.onNodeWithTag("room_create_cancel").performScrollTo().performClick()

        compose.onNodeWithTag("rooms_join").performClick()
        // Set the ViewModel input with no keyboard covering the review image.
        compose.runOnIdle { vm.onCodeChange("ABC123") }
        compose.onNodeWithTag("room_code").assertIsDisplayed()
        save("rooms-join")
        compose.onNodeWithTag("room_join_submit").performClick()
        compose.onNodeWithTag("room_members").assertIsDisplayed()
        compose.onNodeWithText("5분 더 일찍 일어나면 좋아요").assertExists()
        save("rooms-detail")
    }

    private fun save(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.externalCacheDir ?: context.filesDir, "iter5-rooms").apply { mkdirs() }
        val file = File(directory, "$name.png")
        val compressed = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("PNG capture must succeed", compressed)
        assertTrue("PNG must be nonempty", file.length() > 0)
    }
}
