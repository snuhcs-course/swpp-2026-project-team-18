package com.swpp.wakeup.domain.model

import com.swpp.wakeup.calendar.CalendarEvent
import com.swpp.wakeup.data.remote.PlaceSearchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 캘린더 가져오기 화면 규칙.
 *
 * 여기 담긴 판단이 틀리면 **사용자가 보내지 않으려 한 일정이 서버로 간다.**
 * 캘린더 제목에는 병원 예약이나 사람 이름이 들어간다.
 */
class ImportCandidateTest {

    private fun event(location: String? = null) = CalendarEvent(
        externalId = "1:1000",
        title = "수업",
        startAtMillis = 1_000L,
        startAtIso = "2026-10-05T09:00:00+09:00",
        location = location,
        calendarName = "개인",
    )

    private val place = PlaceSearchItem(
        kakaoPlaceId = "1",
        name = "서울대 302동",
        address = "서울 관악구",
        lat = 37.45,
        lng = 126.95,
        category = null,
    )

    @Test
    fun `장소를 찾으면 알람이 계산된다고 표시한다`() {
        val candidate = ImportCandidate(
            source = event("302동"),
            selected = true,
            alreadyImported = false,
            resolvedPlace = place,
        )
        assertTrue(candidate.willHavePlan)
        assertEquals("장소 서울대 302동", candidate.placeNote)
    }

    @Test
    fun `장소 문자열이 있는데 못 찾은 경우를 따로 말한다`() {
        // "못 찾음" 과 "캘린더에 없음" 은 사용자가 할 일이 다르다. 전자는 장소
        // 이름을 고치면 되고 후자는 캘린더를 고치거나 앱에서 넣어야 한다.
        val candidate = ImportCandidate(
            source = event("줌 회의실"),
            selected = true,
            alreadyImported = false,
            resolvedPlace = null,
        )
        assertFalse(candidate.willHavePlan)
        assertTrue(candidate.placeNote.contains("찾지 못함"))
        assertTrue(candidate.placeNote.contains("줌 회의실"))
    }

    @Test
    fun `캘린더에 장소가 없는 경우를 따로 말한다`() {
        val candidate = ImportCandidate(
            source = event(location = null),
            selected = true,
            alreadyImported = false,
        )
        assertFalse(candidate.willHavePlan)
        assertTrue(candidate.placeNote.contains("캘린더에 장소가 없음"))
    }

    @Test
    fun `찾는 중이 다른 상태를 덮는다`() {
        // 검색 중에 "찾지 못함" 을 띄우면 사용자가 바로 해제해 버린다.
        val candidate = ImportCandidate(
            source = event("302동"),
            selected = true,
            alreadyImported = false,
            resolving = true,
        )
        assertEquals("장소 찾는 중…", candidate.placeNote)
    }

    @Test
    fun `외부 식별자는 원본에서 온다`() {
        val candidate = ImportCandidate(
            source = event(),
            selected = true,
            alreadyImported = false,
        )
        assertEquals("1:1000", candidate.externalId)
    }
}

class CalendarImportStateTest {

    private fun candidate(
        id: String,
        selected: Boolean = true,
        imported: Boolean = false,
        place: PlaceSearchItem? = null,
    ) = ImportCandidate(
        source = CalendarEvent(
            externalId = id,
            title = "일정 $id",
            startAtMillis = 0,
            startAtIso = "2026-10-05T09:00:00+09:00",
            location = null,
            calendarName = null,
        ),
        selected = selected,
        alreadyImported = imported,
        resolvedPlace = place,
    )

    @Test
    fun `고른 것이 없으면 보낼 수 없다`() {
        val state = CalendarImportState(
            candidates = listOf(candidate("a", selected = false))
        )
        assertEquals(0, state.selectedCount)
        assertFalse(state.canImport)
    }

    @Test
    fun `하나라도 고르면 보낼 수 있다`() {
        val state = CalendarImportState(candidates = listOf(candidate("a")))
        assertTrue(state.canImport)
        assertNull(state.tooManyNote)
    }

    @Test
    fun `보내는 중에는 다시 보낼 수 없다`() {
        val state = CalendarImportState(
            candidates = listOf(candidate("a")),
            importing = true,
        )
        assertFalse(state.canImport)
    }

    @Test
    fun `서버 배치 상한을 화면에서 먼저 막는다`() {
        // 서버가 400 으로 막지만, 고르는 도중에 알려야 사용자가 무엇을 해제할지
        // 판단할 수 있다. 상한이 있는 이유는 새 일정마다 카카오 경로를 부르기
        // 때문이다.
        val limit = CalendarImportState.MAX_PER_REQUEST
        val over = (1..limit + 3).map { candidate("c$it") }
        val state = CalendarImportState(candidates = over)

        assertEquals(limit + 3, state.selectedCount)
        assertFalse(state.canImport)
        assertEquals("한 번에 ${limit}건까지 가져올 수 있음. 3건을 해제할 것", state.tooManyNote)
    }

    @Test
    fun `상한까지는 보낼 수 있다`() {
        val exact = (1..CalendarImportState.MAX_PER_REQUEST).map { candidate("c$it") }
        val state = CalendarImportState(candidates = exact)
        assertTrue(state.canImport)
        assertNull(state.tooManyNote)
    }

    @Test
    fun `권한 없음과 일정 없음을 구분한다`() {
        // 사용자가 할 일이 완전히 다르다. 하나로 뭉치면 권한을 거절한 사용자가
        // "캘린더에 일정이 없다" 는 거짓 안내를 본다.
        val denied = CalendarImportState(permissionDenied = true)
        assertFalse(denied.isEmpty)

        val empty = CalendarImportState(candidates = emptyList())
        assertTrue(empty.isEmpty)
    }

    @Test
    fun `불러오는 중은 비어 있는 것이 아니다`() {
        val loading = CalendarImportState(loading = true)
        assertFalse(loading.isEmpty)
        assertFalse(loading.canImport)
    }

    @Test
    fun `앱 상한이 서버 상한과 같다`() {
        // 두 값이 어긋나면 화면이 통과시킨 요청을 서버가 400 으로 거절한다.
        assertEquals(
            com.swpp.wakeup.data.remote.CalendarImportRequest.MAX_ITEMS,
            CalendarImportState.MAX_PER_REQUEST,
        )
    }
}
