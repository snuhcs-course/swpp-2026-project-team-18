// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.repository.EventEditPatch
import com.swpp.wakeup.data.repository.EventEditValues
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.SavedHome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * 일정 추가 · 수정의 출발지 · 도착지 바꾸기. 끝점이 실제로 바뀔 때만 고른 경로를 버린다.
 *
 * 같은 끝점을 다시 고르면(이미 주황인 [집]) 폼이 그대로여야 한다. 그래야 일정 수정이 제목만 고친 저장에
 * `route_key: ""` 를 붙이지 않는다 — 빈 PATCH 는 `EventEditPatchTest` 가 본다.
 */
class AddEndpointsTest {

    /** 계정의 집. 앱이 만든 장소라 카카오 id 가 없다(`toHomePlace`) */
    private val home = PlaceSearchItem(
        kakaoPlaceId = null,
        name = "우리집",
        address = null,
        lat = 37.4781,
        lng = 126.9515,
        category = null,
    )

    private val campus = PlaceSearchItem(
        kakaoPlaceId = "8123",
        name = "서울대학교 제2공학관",
        address = "관악로 1",
        lat = 37.4488,
        lng = 126.9527,
        category = "대학교",
    )

    /** 다른 집(본가). 저장한 집도 카카오 id 가 없다(`SavedHome.toPlace`) */
    private val parents = PlaceSearchItem(
        kakaoPlaceId = null,
        name = "본가",
        address = "수원시",
        lat = 37.2636,
        lng = 127.0286,
        category = null,
    )

    /** 검색한 출발지. 검색 결과라 카카오 id 가 있다 */
    private val sillim = PlaceSearchItem(
        kakaoPlaceId = "21160",
        name = "신림역 2호선",
        address = "서울 관악구 신림동",
        lat = 37.4842,
        lng = 126.9297,
        category = "지하철역",
    )

    /** 서버가 돌려준 일정의 출발지. 서버는 좌표와 이름만 두어 카카오 id 가 없다(`EventEditValues.from`) */
    private val sillimFromServer = sillim.copy(kakaoPlaceId = null, address = null, category = null)

    private val distanceMessage = "출발지와 도착지는 50m 이상 떨어져 있어야 한다."

    /** 경로를 고른 폼. 도착지 · 출발지 줄에 서버 오류가 붙어 있다 */
    private fun routed() = HomeViewModel.AddState().copy(
        title = "수업",
        routeKey = "transit:2호선",
        routeLabel = "23분 · 지하철+도보",
        fieldErrors = mapOf(AddField.DESTINATION to distanceMessage, AddField.TITLE to "제목 오류"),
    )

    // --- 도착지 -------------------------------------------------------------

    @Test
    fun `이미 집인 도착지에 집을 다시 누르면 아무것도 바꾸지 않는다`() {
        // Given 도착지가 집이고 경로를 골랐다. 도착지 검색에는 앞서 찾은 결과가 남아 있다
        val state = routed().copy(
            selectedPlace = home,
            destinationIsHome = true,
            place = HomeViewModel.PlaceSearch(query = "제2공", results = listOf(campus)),
        )

        // When 주황인 [집] 을 다시 누른다
        val next = state.withDestination(home, isHome = true)

        // Then 고른 경로 · 오류 줄 · 검색이 그대로다
        assertSame(state, next)
        assertTrue(state.sameDestination(home, isHome = true))
    }

    @Test
    fun `같은 장소를 다시 검색해 고르면 경로를 그대로 둔다`() {
        // Given 검색으로 고른 도착지. 다시 검색하면 같은 카카오 장소가 거리만 달리 온다
        val state = routed().copy(selectedPlace = campus)
        val again = campus.copy(distanceM = 640)

        // When / Then
        assertSame(state, state.withDestination(again, isHome = false))
    }

    @Test
    fun `도착지가 바뀌면 고른 경로와 끝점 오류를 버린다`() {
        // Given 검색한 장소가 도착지다
        val state = routed().copy(selectedPlace = campus)

        // When 집으로 바꾼다
        val next = state.withDestination(home, isHome = true)

        // Then 경로 · 도착지 오류를 지우고 검색 칸은 새 이름이다. 끝점과 무관한 제목 오류는 남는다
        assertEquals(home, next.selectedPlace)
        assertTrue(next.destinationIsHome)
        assertNull(next.routeKey)
        assertNull(next.routeLabel)
        assertEquals(mapOf(AddField.TITLE to "제목 오류"), next.fieldErrors)
        assertEquals("우리집", next.place.query)
        assertTrue(next.place.results.isEmpty())
    }

    @Test
    fun `좌표가 같아도 집으로 고른 것과 검색한 장소는 다른 도착지다`() {
        // Given 집으로 고른 도착지
        val state = routed().copy(selectedPlace = home, destinationIsHome = true)
        val searchedHere = campus.copy(lat = home.lat, lng = home.lng)

        // When 같은 좌표의 검색 결과를 고른다
        val next = state.withDestination(searchedHere, isHome = false)

        // Then 일반 목적지로 바뀌고 경로를 다시 고른다
        assertFalse(next.destinationIsHome)
        assertNull(next.routeKey)
    }

    // --- 출발지 -------------------------------------------------------------

    @Test
    fun `이미 집인 출발지에 집을 다시 누르면 경로를 그대로 둔다`() {
        // Given 출발지가 집(null)이고 경로를 골랐다
        val state = routed().copy(fieldErrors = mapOf(AddField.ORIGIN to distanceMessage))

        // When 주황인 [집] 을 다시 누른다
        val next = state.withOrigin(null)

        // Then 그대로다
        assertSame(state, next)
    }

    @Test
    fun `같은 출발지를 다시 고르면 현재 위치 안내만 지운다`() {
        // Given 현재 위치를 못 구했다는 안내가 남아 있다
        val state = routed().copy(originNotice = "현재 위치를 확인할 수 없음")

        // When 집을 다시 고른다
        val next = state.withOrigin(null)

        // Then 안내만 사라지고 경로는 남는다
        assertNull(next.originNotice)
        assertEquals("transit:2호선", next.routeKey)
        assertEquals("23분 · 지하철+도보", next.routeLabel)
    }

    @Test
    fun `출발지가 바뀌면 경로를 버리고 집으로 되돌리면 준비 시간 스위치를 끈다`() {
        // Given 본가에서 준비 시간을 넣고 출발한다
        val state = routed().copy(origin = parents, includePrep = true, originPlaceId = 7L)

        // When 집으로 되돌린다
        val next = state.withOrigin(null)

        // Then
        assertNull(next.origin)
        assertNull(next.originPlaceId)
        assertFalse(next.includePrep)
        assertNull(next.routeKey)
        assertNull(next.routeLabel)
    }

    @Test
    fun `같은 좌표라도 저장한 집 연결이 생기면 바뀐 출발지다`() {
        // Given 기기에만 있던 본가를 출발지로 골랐다(서버 id 없음)
        val state = routed().copy(origin = parents)

        // When 서버에 올라간 같은 본가를 고른다
        val next = state.withOrigin(parents, placeId = 7L, includePrep = true)

        // Then 서버의 그 집에 연결하고 경로를 다시 고른다
        assertFalse(state.sameOrigin(parents, placeId = 7L))
        assertEquals(7L, next.originPlaceId)
        assertTrue(next.includePrep)
        assertNull(next.routeKey)
    }

    @Test
    fun `같은 저장한 집을 다시 고르면 꺼 둔 스위치와 경로를 그대로 둔다`() {
        // Given 본가(서버 id 7)에서 준비 시간 스위치를 끄고 경로를 골랐다
        val state = routed().copy(origin = parents, originPlaceId = 7L, includePrep = false)

        // When 출발지 검색 맨 위의 본가를 다시 누른다
        val next = state.withOrigin(parents, placeId = 7L, includePrep = true)

        // Then 그대로다
        assertSame(state, next)
    }

    // --- 카카오 id 가 없는 끝점 (PLACE-1, AI-generated (Claude)) -----------------

    @Test
    fun `일정 수정에서 같은 출발지를 다시 검색해 고르면 경로를 그대로 두고 아무것도 보내지 않는다`() {
        // Given 신림역에서 출발하고 경로를 고른 일정의 수정 폼. 출발지는 서버 값이라 카카오 id 가 없다
        val base = EventEditValues(
            title = "수업",
            startAt = OffsetDateTime.parse("2026-11-09T09:00:00+09:00"),
            tagKey = "class",
            place = campus,
            destinationIsHome = false,
            origin = sillimFromServer,
            routeKey = "transit:2호선",
            includePrep = null,
        )
        val form = routed().copy(
            selectedPlace = campus,
            origin = sillimFromServer,
            originLabel = sillimFromServer.name,
            editingEventId = 5L,
            editBase = base,
        )

        // When 출발지 검색에서 같은 신림역(카카오 id 가 있고 좌표가 같다)을 다시 고른다
        val next = form.withOrigin(sillim)

        // Then 폼이 그대로라 수정 저장이 route_key "" 를 보내지 않는다(서버가 고른 경로를 최단 경로로 바꾸지 않는다)
        assertTrue(form.sameOrigin(sillim))
        assertSame(form, next)
        val edited = base.copy(origin = next.origin, routeKey = next.routeKey)
        assertEquals(0, EventEditPatch.build(base, edited).size())
    }

    @Test
    fun `카카오 id 가 한쪽에만 있으면 이름은 보지 않고 좌표가 똑같은지로 본다`() {
        // Given 서버가 이름 없이 둔 출발지는 "출발지" 로 온다(EventEditValues.from)
        val unnamed = sillimFromServer.copy(name = "출발지")

        // When / Then 같은 좌표의 검색 결과는 같은 끝점이다. 좌표가 조금이라도 다르면 다른 곳이다
        assertTrue(sameEndpoint(unnamed, sillim))
        assertTrue(sameEndpoint(sillim, unnamed))
        assertFalse(sameEndpoint(unnamed, sillim.copy(lat = sillim.lat + 0.00001)))
    }

    @Test
    fun `카카오 id 가 둘 다 있으면 좌표가 같아도 id 로 가른다`() {
        // Given 한 건물의 다른 가게는 같은 좌표로 올 수 있다
        val cafe = campus.copy(kakaoPlaceId = "9001", name = "제2공학관 카페")

        // When / Then 다른 도착지다. 같은 id 면 이름 · 거리가 달라도 같은 곳이다
        assertFalse(sameEndpoint(campus, cafe))
        assertTrue(sameEndpoint(campus, campus.copy(name = "서울대 제2공학관", distanceM = 640)))
    }

    @Test
    fun `같은 자리의 검색한 장소를 골라도 저장한 집과의 연결이 끊기면 바뀐 출발지다`() {
        // Given 본가(서버 id 7)에서 출발한다. 본가는 그 아파트를 검색해 저장해서 좌표가 같다
        val state = routed().copy(origin = parents, originPlaceId = 7L, includePrep = true)
        val apartment = parents.copy(kakaoPlaceId = "5501", name = "인계동 아파트")

        // When 출발지 검색에서 그 아파트를 고른다
        val next = state.withOrigin(apartment)

        // Then 같은 끝점이지만 저장한 집이 아닌 출발지가 되어 경로를 다시 고른다
        assertTrue(sameEndpoint(parents, apartment))
        assertEquals(apartment, next.origin)
        assertNull(next.originPlaceId)
        assertNull(next.routeKey)
    }

    // --- 현재 위치 ----------------------------------------------------------

    @Test
    fun `현재 위치는 물어본 폼이 아니면 쓰지 않는다`() {
        // Given 위치를 재는 동안 저장해서 새 폼(새 client_request_id)이 열렸다
        val asked = HomeViewModel.AddState().copy(originLocating = true)
        val fresh = HomeViewModel.AddState()

        // When 늦게 위치가 왔다
        val next = fresh.withLocatedOrigin(asked.clientRequestId, here = campus, notice = "안내")

        // Then 새 폼은 그대로다(출발지가 생겨 쓰다 만 일정이 되지 않는다)
        assertSame(fresh, next)
        assertFalse(next.isUnsavedDraft)
    }

    @Test
    fun `물어본 폼이면 출발지를 현재 위치로 바꾸고 재는 표시를 내린다`() {
        // Given
        val state = routed().copy(originLocating = true)

        // When
        val next = state.withLocatedOrigin(state.clientRequestId, here = campus, notice = "안내")

        // Then
        assertEquals(campus, next.origin)
        assertFalse(next.originLocating)
        assertNull("출발지가 바뀌었으니 경로를 다시 고른다", next.routeKey)
    }

    @Test
    fun `위치를 못 구하면 출발지는 그대로 두고 이유만 알린다`() {
        // Given
        val state = routed().copy(originLocating = true)

        // When
        val next = state.withLocatedOrigin(state.clientRequestId, here = null, notice = "현재 위치를 확인할 수 없음")

        // Then
        assertNull(next.origin)
        assertFalse(next.originLocating)
        assertEquals("현재 위치를 확인할 수 없음", next.originNotice)
        assertEquals("transit:2호선", next.routeKey)
    }

    // AI-generated (Claude)
    @Test
    fun `맡겼다 되살린 폼에는 늦게 온 현재 위치를 쓰지 않는다`() {
        // Given 위치를 재는 중에 홈으로 나갔다가 "이어서 쓰기" 로 되살렸다. 같은 폼(같은 client_request_id)이지만
        // 되살린 폼은 더 기다리지 않는다 — 출발지 줄 · 경로 고르기 · 일정 추가가 다시 켜진다
        val asked = routed().copy(originLocating = true)
        val resumed = asked.forStash()

        // When 앞서 물어본 위치가 늦게 왔다(찾았든 못 찾았든)
        val found = resumed.withLocatedOrigin(asked.clientRequestId, here = campus, notice = null)
        val failed = resumed.withLocatedOrigin(asked.clientRequestId, here = null, notice = "현재 위치를 확인할 수 없음")

        // Then 되살린 폼은 그대로다. 출발지와 고른 경로가 바뀌지 않고 안내도 붙지 않는다
        assertSame(resumed, found)
        assertSame(resumed, failed)
        assertEquals("transit:2호선", found.routeKey)
    }

    // AI-generated (Claude)
    @Test
    fun `재는 동안 출발지를 직접 고르면 늦게 온 현재 위치가 고른 출발지를 덮지 않는다`() {
        // Given 현재 위치를 묻는 중이다
        val asked = routed().copy(originLocating = true)

        // When 기다리지 않고 신림역을 골랐고, 그 뒤 위치가 왔다
        val picked = asked.withOrigin(sillim)
        val late = picked.withLocatedOrigin(asked.clientRequestId, here = campus, notice = null)

        // Then 고른 순간 기다리기를 끝냈다. 늦게 온 위치는 버리고 신림역이 남는다
        assertFalse(picked.originLocating)
        assertSame(picked, late)
        assertEquals(sillim, late.origin)

        // When 이미 고른 집을 다시 눌러도(바뀐 것 없음) 기다리기를 끝낸다
        val homeAgain = asked.withOrigin(null)

        // Then 고른 경로는 그대로고 늦게 온 위치가 집을 덮지 않는다
        assertFalse(homeAgain.originLocating)
        assertEquals("transit:2호선", homeAgain.routeKey)
        assertSame(homeAgain, homeAgain.withLocatedOrigin(asked.clientRequestId, here = campus, notice = null))
    }

    // --- 현재 위치를 못 구한 이유 (ERR-2) -------------------------------------

    @Test
    fun `주소 조회 결과로 현재 위치를 못 구한 이유를 가른다`() {
        // Given / When / Then 위치는 쟀다. 주소 조회가 장소 · 빈 결과 · 실패(연결 · 서버) 중 무엇이었나
        assertEquals(CurrentPlaceLookup.Found(campus), currentPlaceLookupOf(EventRepository.Result.Success(campus)))
        assertEquals(CurrentPlaceLookup.NoAddress, currentPlaceLookupOf(EventRepository.Result.Success(null)))
        assertEquals(
            CurrentPlaceLookup.LookupFailed,
            currentPlaceLookupOf(EventRepository.Result.Failure("서버에 연결할 수 없다.", network = true)),
        )
        assertEquals(
            CurrentPlaceLookup.LookupFailed,
            currentPlaceLookupOf(EventRepository.Result.Failure("현재 위치의 주소를 불러오지 못했다.")),
        )
    }

    @Test
    fun `주소를 받지 못했으면 위치 권한과 GPS 탓을 하지 않고 다시 누르게 한다`() {
        // Given 위치는 쟀지만 통신이 끊겨 주소를 받지 못했다
        val notice = currentPlaceNotice(CurrentPlaceLookup.LookupFailed)

        // Then
        assertEquals("현재 위치의 주소를 불러오지 못했어요. 잠시 뒤 다시 누르거나 직접 검색해 주세요", notice)
    }

    @Test
    fun `위치를 재지 못했을 때만 위치 권한과 GPS 를 확인하라고 한다`() {
        assertEquals(
            "현재 위치를 확인할 수 없음. 위치 권한과 GPS 를 확인하거나 직접 검색할 것",
            currentPlaceNotice(CurrentPlaceLookup.NoFix),
        )
        assertEquals("이 위치의 주소를 찾지 못했어요. 직접 검색해 주세요", currentPlaceNotice(CurrentPlaceLookup.NoAddress))
        assertNull("구했으면 안내가 없다", currentPlaceNotice(CurrentPlaceLookup.Found(campus)))
    }

    @Test
    fun `주소를 받지 못한 안내는 출발지 줄에 남고 출발지는 그대로다`() {
        // Given 현재 위치를 묻는 중이다
        val state = routed().copy(originLocating = true)

        // When 주소 조회가 실패했다
        val lookup = currentPlaceLookupOf(EventRepository.Result.Failure("연결 실패", network = true))
        val next = state.withLocatedOrigin(state.clientRequestId, here = null, notice = currentPlaceNotice(lookup))

        // Then
        assertNull(next.origin)
        assertFalse(next.originLocating)
        assertEquals("현재 위치의 주소를 불러오지 못했어요. 잠시 뒤 다시 누르거나 직접 검색해 주세요", next.originNotice)
    }

    // --- 집이 옮겨졌을 때 (STAT-1 · STAT-2) ----------------------------------

    /** 설정에서 "변경" 으로 옮긴 새 집 */
    private val movedHome = home.copy(name = "새집", lat = 37.5172, lng = 127.0473)

    /** 서버가 본가(행 7)를 기본 집으로 바꾼 뒤의 계정의 집. 좌표가 본가와 같다 */
    private val parentsAsHome = home.copy(name = "본가", lat = parents.lat, lng = parents.lng)

    @Test
    fun `집에서 출발하는 초안은 집이 옮겨지면 고른 경로를 버린다`() {
        // Given 출발지가 집(null)이고 예전 집에서 경로를 골랐다. 출발 · 도착 줄에 서버 오류가 붙어 있다
        val draft = routed().copy(selectedPlace = campus)

        // When 설정에서 집 주소를 바꿨다
        val next = draft.afterHomeMoved(movedHome)

        // Then 경로와 그 줄 오류는 예전 집 기준이라 버린다. 도착지와 제목 오류는 그대로다
        assertNull(next.routeKey)
        assertNull(next.routeLabel)
        assertEquals(campus, next.selectedPlace)
        assertEquals(mapOf(AddField.TITLE to "제목 오류"), next.fieldErrors)
    }

    @Test
    fun `집 도착 초안은 새 집을 가리키고 경로를 버린다`() {
        // Given 본가에서 [집] 으로 가는 일정. 도착지는 예전 집의 사본이다
        val draft = routed().copy(origin = parents, originLabel = "본가", selectedPlace = home, destinationIsHome = true)

        // When
        val next = draft.afterHomeMoved(movedHome)

        // Then 도착지 줄은 새 집 이름이고 경로는 다시 고른다. 출발지는 그대로다
        assertEquals(movedHome, next.selectedPlace)
        assertTrue(next.destinationIsHome)
        assertEquals("새집", next.place.query)
        assertNull(next.routeKey)
        assertEquals(parents, next.origin)
    }

    @Test
    fun `집과 무관한 초안은 그대로다`() {
        // Given 검색한 출발지에서 다른 집으로 간다(저장한 집이 아니라 좌표만 있다)
        val draft = routed().copy(origin = campus, selectedPlace = parents)

        // When / Then
        assertSame(draft, draft.afterHomeMoved(movedHome))
    }

    @Test
    fun `기본으로 바꾼 저장한 집에서 출발하는 초안은 집 출발이 되고 경로를 둔다`() {
        // Given 본가(서버 id 7)에서 준비 시간을 넣고 출발한다. 경로는 본가에서 골랐다
        val draft = routed().copy(
            origin = parents,
            originLabel = "본가",
            originPlaceId = 7L,
            includePrep = true,
            selectedPlace = campus,
        )

        // When 서버가 본가를 기본 집으로 바꿨다. 행 7 은 이제 예전 집이다
        val next = draft.afterHomeMoved(parentsAsHome)

        // Then id 7 을 보내지 않고 집에서 출발한다. 좌표가 같아 고른 경로는 그대로다
        assertNull(next.origin)
        assertNull(next.originLabel)
        assertNull(next.originPlaceId)
        assertFalse(next.includePrep)
        assertEquals("transit:2호선", next.routeKey)
        assertEquals("23분 · 지하철+도보", next.routeLabel)
    }

    @Test
    fun `준비 시간을 끈 저장한 집 출발은 좌표를 두고 그 집과의 연결만 끊는다`() {
        // Given 본가(서버 id 7)에서 준비 시간 없이 출발한다
        val draft = routed().copy(origin = parents, originPlaceId = 7L, includePrep = false, selectedPlace = campus)

        // When
        val next = draft.afterHomeMoved(parentsAsHome)

        // Then 집 출발로 바꾸면 준비 시간이 들어가 알람이 당겨진다. 좌표만 보내도록 둔다
        assertEquals(parents, next.origin)
        assertNull(next.originPlaceId)
        assertFalse(next.includePrep)
        assertEquals("transit:2호선", next.routeKey)
    }

    @Test
    fun `다른 자리의 저장한 집 출발은 집이 옮겨져도 그대로다`() {
        val draft = routed().copy(origin = parents, originPlaceId = 7L, includePrep = true, selectedPlace = campus)
        assertSame(draft, draft.afterHomeMoved(movedHome))
    }

    @Test
    fun `집이 옮겨졌는지는 좌표로 본다`() {
        assertTrue(homeMoved(home, movedHome))
        assertFalse("같은 집을 다시 받음", homeMoved(home, home.copy()))
        assertFalse("이름만 바뀜", homeMoved(home, home.copy(name = "우리 집")))
        assertFalse("앞의 집을 모름(처음 받음)", homeMoved(null, home))
        assertFalse("집 좌표가 없어짐", homeMoved(home, null))
    }

    // --- 보낼 저장한 집 id (STAT-1) -------------------------------------------

    /** 서버에 올라간 본가(행 7) */
    private val parentsRow = SavedHome(
        id = "u-7",
        label = "본가",
        name = "본가",
        address = "수원시",
        lat = parents.lat,
        lng = parents.lng,
        serverId = 7L,
    )

    @Test
    fun `저장한 집이 그 출발지에 있으면 id 를 보낸다`() {
        assertEquals(7L, originPlaceIdToSend(7L, parents, listOf(parentsRow)))
    }

    @Test
    fun `기본으로 바꿔 예전 집이 된 행이면 id 를 보내지 않는다`() {
        // Given 서버가 행 7 을 예전 기본 집으로 고쳐 썼다. 폼은 아직 본가 좌표와 id 7 을 들고 있다
        val swapped = parentsRow.copy(id = "server-7", label = "집", name = "우리집", address = null, lat = home.lat, lng = home.lng)

        // When / Then 좌표만 보낸다. id 를 보내면 서버가 출발지를 예전 집 좌표로 바꾼다
        assertNull(originPlaceIdToSend(7L, parents, listOf(swapped)))
    }

    @Test
    fun `지운 저장한 집이나 집 출발이면 id 를 보내지 않는다`() {
        assertNull("목록에 없음(지움)", originPlaceIdToSend(7L, parents, emptyList()))
        assertNull("출발지가 집", originPlaceIdToSend(7L, null, listOf(parentsRow)))
        assertNull("저장한 집이 아님", originPlaceIdToSend(null, parents, listOf(parentsRow)))
    }

    // --- 일정 수정의 경로 줄 ------------------------------------------------

    @Test
    fun `고른 경로가 없는 일정은 경로 요약을 두지 않는다`() {
        // 서버가 고른 최단 경로를 "선택함" 으로 보이지 않는다
        assertNull(editRouteLabel(routeKey = null, travelMinutes = 23, travelMode = "지하철+도보"))
    }

    @Test
    fun `고른 경로가 있으면 계획의 소요시간과 수단으로 요약한다`() {
        assertEquals("23분 · 지하철+도보", editRouteLabel("transit:2호선", 23, "지하철+도보"))
        assertEquals("18분", editRouteLabel("walk", 18, " "))
        assertNull("소요시간을 모르면 요약하지 않는다", editRouteLabel("walk", null, "도보"))
    }
}
