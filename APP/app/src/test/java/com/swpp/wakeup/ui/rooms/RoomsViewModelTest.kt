package com.swpp.wakeup.ui.rooms

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.RoomCreateInput
import com.swpp.wakeup.data.repository.RoomsRepository
import com.swpp.wakeup.data.repository.RoomsResult
import com.swpp.wakeup.domain.model.AppointmentRoom
import com.swpp.wakeup.domain.model.RoomDetail
import com.swpp.wakeup.domain.model.RoomMember
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class RoomsViewModelTest {
    private val now = LocalDateTime.of(2026, 11, 27, 12, 0)
    private val place = PlaceSearchItem("123", "서울대입구역", "서울 관악구", 37.4813, 126.9527, null)
    private fun detail(id: Long = 12) = RoomDetail(
        AppointmentRoom(id, "모임", OffsetDateTime.parse("2026-12-04T09:00:00+09:00"), "서울대입구역", .9, 1),
        "ABC123", listOf(RoomMember("진호", true, null, null)), null)

    private class Fake : RoomsRepository {
        var onList: suspend () -> RoomsResult<List<AppointmentRoom>> = { RoomsResult.Success(emptyList()) }
        var onCreate: suspend (RoomCreateInput) -> RoomsResult<RoomDetail> = { RoomsResult.Failure("실패했어요") }
        var onJoin: suspend (String) -> RoomsResult<RoomDetail> = { RoomsResult.Failure("코드를 확인해 주세요") }
        var onDetail: suspend (Long) -> RoomsResult<RoomDetail> = { RoomsResult.Failure("연결을 확인해 주세요") }
        val creates = mutableListOf<RoomCreateInput>()
        val joins = mutableListOf<String>()
        override suspend fun list() = onList()
        override suspend fun create(input: RoomCreateInput): RoomsResult<RoomDetail> { creates += input; return onCreate(input) }
        override suspend fun join(code: String): RoomsResult<RoomDetail> { joins += code; return onJoin(code) }
        override suspend fun detail(id: Long) = onDetail(id)
    }

    private fun TestScope.vm(repo: Fake, search: RoomPlaceSearcher = RoomPlaceSearcher { RoomsResult.Success(listOf(place)) }) =
        RoomsViewModel(repo, search, { now }, ZoneId.of("Asia/Seoul"), this)

    private fun RoomsViewModel.fill() {
        showCreate()
        onTitleChange("  모임  ")
        selectPlace(place)
        onDateChange(LocalDate.of(2026, 12, 4))
        onTimeChange(LocalTime.of(9, 0))
    }

    @Test fun `list unsupported is an honest state and refresh can recover`() = runTest {
        val repo = Fake().apply { onList = { RoomsResult.Unsupported } }
        val vm = vm(repo)
        vm.enter()
        advanceUntilIdle()
        assertTrue(vm.state.value.unsupported)
        assertTrue(vm.state.value.rooms.isEmpty())
        repo.onList = { RoomsResult.Success(listOf(detail().room)) }
        vm.refreshList()
        advanceUntilIdle()
        assertFalse(vm.state.value.unsupported)
        assertEquals(listOf(12L), vm.state.value.rooms.map { it.id })
    }

    @Test fun `create validates before calling the server and fields clear independently`() = runTest {
        val repo = Fake()
        val vm = vm(repo)
        vm.showCreate()
        vm.onDateChange(now.toLocalDate().minusDays(1))
        vm.onTargetChange("100")
        vm.createRoom()
        assertEquals(setOf("title", "destination", "start_at", "group_target_probability"), vm.state.value.create.fields.keys)
        assertTrue(repo.creates.isEmpty())
        vm.onTitleChange("모임")
        assertFalse("title" in vm.state.value.create.fields)
        assertTrue("start_at" in vm.state.value.create.fields)
    }

    @Test fun `create success opens real detail and announces personal event refresh`() = runTest {
        val repo = Fake().apply { onCreate = { RoomsResult.Success(detail()) } }
        val vm = vm(repo)
        vm.fill()
        vm.createRoom()
        advanceUntilIdle()
        assertEquals(RoomsViewModel.Page.DETAIL, vm.state.value.page)
        assertEquals(12L, vm.state.value.detailId)
        assertEquals(1L, vm.state.value.membershipChange)
        assertEquals("모임", repo.creates.single().title)
        assertEquals("2026-12-04T09:00+09:00", repo.creates.single().startAt)
        assertEquals(.9, repo.creates.single().groupTargetProbability, 0.0)
        assertEquals(place.lat, repo.creates.single().destination.lat, 0.0)
        assertEquals(place.kakaoPlaceId, repo.creates.single().destination.kakaoPlaceId)
    }

    @Test fun `network retry keeps input and UUID while editing starts a new request identity`() = runTest {
        val repo = Fake()
        val vm = vm(repo)
        vm.fill()
        vm.createRoom()
        advanceUntilIdle()
        assertEquals(RoomsViewModel.Page.CREATE, vm.state.value.page)
        assertEquals(place, vm.state.value.create.destination)
        vm.createRoom()
        advanceUntilIdle()
        assertEquals(repo.creates[0].clientUuid, repo.creates[1].clientUuid)
        vm.onTitleChange("다른 모임")
        vm.createRoom()
        advanceUntilIdle()
        assertNotEquals(repo.creates[1].clientUuid, repo.creates[2].clientUuid)
    }

    @Test fun `duplicate submit and back are blocked during write and reentry does not cancel it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = Fake().apply { onCreate = { gate.await(); RoomsResult.Success(detail()) } }
        val vm = vm(repo)
        vm.fill()
        vm.createRoom()
        vm.createRoom()
        runCurrent()
        assertEquals(1, repo.creates.size)
        assertTrue(vm.back())
        vm.enter()
        assertEquals(RoomsViewModel.Page.CREATE, vm.state.value.page)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(RoomsViewModel.Page.DETAIL, vm.state.value.page)
        assertFalse(vm.state.value.submitting)
    }

    @Test fun `server validation remains in the form until that field changes`() = runTest {
        val repo = Fake().apply { onCreate = { RoomsResult.Failure("날짜를 확인해 주세요", mapOf("start_at" to "너무 먼 약속이에요")) } }
        val vm = vm(repo)
        vm.fill()
        vm.createRoom()
        advanceUntilIdle()
        assertEquals("너무 먼 약속이에요", vm.state.value.create.fields["start_at"])
        vm.onTimeChange(LocalTime.of(10, 0))
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.create.fields.isEmpty())
    }

    @Test fun `join code normalizes and incomplete code is not sent`() = runTest {
        val repo = Fake()
        val vm = vm(repo)
        vm.showJoin()
        vm.onCodeChange("ab-12")
        vm.joinRoom()
        assertTrue(repo.joins.isEmpty())
        assertNotNull(vm.state.value.codeError)
        vm.onCodeChange(" abc123 ")
        vm.joinRoom()
        advanceUntilIdle()
        assertEquals(listOf("ABC123"), repo.joins)
        assertEquals("ABC123", vm.state.value.joinCode)
        assertEquals(RoomsViewModel.Page.JOIN, vm.state.value.page)
    }

    @Test fun `join unsupported preserves code and never announces success`() = runTest {
        val repo = Fake().apply { onJoin = { RoomsResult.Unsupported } }
        val vm = vm(repo)
        vm.showJoin()
        vm.onCodeChange("ABC123")
        vm.joinRoom()
        advanceUntilIdle()
        assertTrue(vm.state.value.unsupported)
        assertEquals("ABC123", vm.state.value.joinCode)
        assertEquals(0L, vm.state.value.membershipChange)
        assertNull(vm.state.value.detail)
    }

    @Test fun `join success refreshes membership and back returns to list`() = runTest {
        val repo = Fake().apply { onJoin = { RoomsResult.Success(detail()) } }
        val vm = vm(repo)
        vm.showJoin()
        vm.onCodeChange("ABC123")
        vm.joinRoom()
        advanceUntilIdle()
        assertEquals(RoomsViewModel.Page.DETAIL, vm.state.value.page)
        assertEquals(1L, vm.state.value.membershipChange)
        assertTrue(vm.back())
        assertEquals(RoomsViewModel.Page.LIST, vm.state.value.page)
        assertFalse(vm.back())
    }

    @Test fun `search results do not overwrite a changed query even if cancellation is ignored`() = runTest {
        val vm = vm(Fake(), RoomPlaceSearcher { query ->
            withContext(NonCancellable) { delay(100); RoomsResult.Success(listOf(place.copy(name = query))) }
        })
        vm.showCreate()
        vm.onPlaceQueryChange("old")
        vm.searchPlaces()
        runCurrent()
        vm.onPlaceQueryChange("new")
        vm.searchPlaces()
        advanceUntilIdle()
        assertEquals("new", vm.state.value.create.places.single().name)
        assertEquals("new", vm.state.value.create.placeQuery)
    }

    @Test fun `selection clears results and editing query clears selected place`() = runTest {
        val vm = vm(Fake())
        vm.showCreate()
        vm.onPlaceQueryChange("서울대")
        vm.searchPlaces()
        advanceUntilIdle()
        assertEquals(listOf(place), vm.state.value.create.places)
        vm.selectPlace(place)
        assertEquals(place, vm.state.value.create.destination)
        assertTrue(vm.state.value.create.places.isEmpty())
        vm.onPlaceQueryChange("다른 역")
        assertNull(vm.state.value.create.destination)
    }

    @Test fun `failed search is distinct from no results`() = runTest {
        val vm = vm(Fake(), RoomPlaceSearcher { RoomsResult.Failure("인터넷을 확인해 주세요") })
        vm.showCreate()
        vm.onPlaceQueryChange("서울대")
        vm.searchPlaces()
        advanceUntilIdle()
        assertEquals("인터넷을 확인해 주세요", vm.state.value.create.searchError)
        assertFalse(vm.state.value.create.searched)
    }

    @Test fun `detail 404 removes old member data but transient failure retains it`() = runTest {
        val repo = Fake().apply { onDetail = { RoomsResult.Success(detail()) } }
        val vm = vm(repo)
        vm.openRoom(12)
        advanceUntilIdle()
        assertNotNull(vm.state.value.detail)
        repo.onDetail = { RoomsResult.Failure("연결을 확인해 주세요") }
        vm.refreshRoom()
        advanceUntilIdle()
        assertNotNull(vm.state.value.detail)
        repo.onDetail = { RoomsResult.Failure("방을 찾을 수 없어요", status = 404) }
        vm.refreshRoom()
        advanceUntilIdle()
        assertNull(vm.state.value.detail)
    }

    @Test fun `old detail request cannot overwrite a subsequently opened room`() = runTest {
        val repo = Fake().apply { onDetail = { id ->
            withContext(NonCancellable) { delay(if (id == 1L) 200 else 10); RoomsResult.Success(this@RoomsViewModelTest.detail(id)) }
        } }
        val vm = vm(repo)
        vm.openRoom(1)
        runCurrent()
        vm.openRoom(2)
        advanceUntilIdle()
        assertEquals(2L, vm.state.value.detail!!.room.id)
    }
}
