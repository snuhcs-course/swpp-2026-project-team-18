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
            // 검색이 "결과 없음" 으로 답했다. 찾아 보지 않은 줄은 아래 테스트가 본다. (Partly AI-generated (Claude))
            searched = true,
        )
        assertFalse(candidate.willHavePlan)
        assertTrue(candidate.placeNote.contains("찾지 못함"))
        assertTrue(candidate.placeNote.contains("줌 회의실"))
    }

    // AI-generated (Claude)
    @Test
    fun `찾아 보지 않은 줄을 찾지 못했다고 하지 않는다`() {
        // Given 장소 문자열이 있지만 한 번도 찾지 않은 줄. 꺼져 있던 이미 가져온 일정과, 켜져 있지만 상한 밖이라 건너뛴 줄
        val off = ImportCandidate(source = event("302동"), selected = false, alreadyImported = true)
        val on = ImportCandidate(source = event("302동"), selected = true, alreadyImported = false)

        // When / Then "찾지 못함" 이 아니라 아직 찾지 않았다고 하고, 어떻게 하면 찾는지 알린다
        assertEquals("\"302동\" · 켜면 장소를 찾아요", off.placeNote)
        assertEquals("\"302동\" · 아직 찾지 않았어요. 껐다 켜면 찾아요", on.placeNote)
        assertFalse(off.placeNote.contains("찾지 못함"))
        assertFalse(on.placeNote.contains("찾지 못함"))
    }

    // AI-generated (Claude)
    @Test
    fun `검색이 실패하면 찾지 못함이 아니라 실패라고 한다`() {
        // Given 장소를 찾는 중에 요청이 통신 문제로 실패했다
        val searching = ImportCandidate(
            source = event("302동"),
            selected = true,
            alreadyImported = false,
            resolving = true,
        )

        // When 실패를 줄에 넣는다
        val failed = searching.withLookupFailed()

        // Then 결과가 없었던 것처럼 말하지 않고, 다시 켜면 다시 찾는다
        assertFalse(failed.resolving)
        assertFalse(failed.placeNote.contains("찾지 못함"))
        assertEquals("\"302동\" · 장소를 검색하지 못했어요. 껐다 켜면 다시 찾아요", failed.placeNote)
        assertEquals(
            "\"302동\" · 장소를 검색하지 못했어요. 켜면 다시 찾아요",
            failed.copy(selected = false).placeNote,
        )
        assertTrue(failed.needsLookup)
    }

    // AI-generated (Claude)
    @Test
    fun `검색 결과를 줄에 넣는다`() {
        // Given 찾는 중인 줄
        val searching = ImportCandidate(
            source = event("302동"),
            selected = true,
            alreadyImported = false,
            resolving = true,
        )

        // When 찾았다 / 결과가 없었다
        val found = searching.withLookup(place)
        val none = searching.withLookup(null)

        // Then 찾은 줄은 알람이 계산되고, 결과가 없던 줄만 "찾지 못함" 이다
        assertTrue(found.willHavePlan)
        assertFalse(found.resolving)
        assertEquals("장소 서울대 302동", found.placeNote)
        assertFalse(none.resolving)
        assertTrue(none.placeNote.contains("찾지 못함"))
    }

    // AI-generated (Claude)
    @Test
    fun `켤 때 장소를 찾아야 하는 줄은 아직 답이 없는 줄뿐이다`() {
        // Given 장소 문자열이 있고 한 번도 찾지 않은 줄
        val fresh = ImportCandidate(source = event("302동"), selected = false, alreadyImported = true)

        // When / Then 찾지 않은 줄과 실패한 줄만 찾는다. 찾는 중 · 찾음 · 결과 없음 · 장소 없음은 다시 찾지 않는다
        assertTrue(fresh.needsLookup)
        assertTrue(fresh.withLookupFailed().needsLookup)
        assertFalse(fresh.copy(resolving = true).needsLookup)
        assertFalse(fresh.withLookup(place).needsLookup)
        assertFalse(fresh.withLookup(null).needsLookup)
        assertFalse(fresh.copy(source = event(location = null)).needsLookup)
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

    // Partly AI-generated (Claude)
    private fun candidate(
        id: String,
        selected: Boolean = true,
        imported: Boolean = false,
        place: PlaceSearchItem? = null,
        location: String? = null,
        startAt: Long = 0,
        resolving: Boolean = false,
    ) = ImportCandidate(
        source = CalendarEvent(
            externalId = id,
            title = "일정 $id",
            startAtMillis = startAt,
            startAtIso = "2026-10-05T09:00:00+09:00",
            location = location,
            calendarName = null,
        ),
        selected = selected,
        alreadyImported = imported,
        resolvedPlace = place,
        resolving = resolving,
    )

    // AI-generated (Claude)
    @Test
    fun `고른 줄의 장소를 찾는 중이면 보낼 수 없다`() {
        // Given 고른 줄 하나가 아직 장소를 찾는 중이다
        val waiting = CalendarImportState(
            candidates = listOf(candidate("a"), candidate("b", location = "302동", resolving = true)),
        )

        // When / Then 지금 보내면 그 줄이 장소 없이 가므로 기다리고, 버튼 위에 이유를 적는다
        assertFalse(waiting.canImport)
        assertEquals("고른 일정의 장소를 찾고 있어요. 다 찾으면 가져올 수 있어요", waiting.waitingNote)

        // 찾는 줄을 끄면 그 줄은 보내지 않으므로 기다리지 않는다
        val off = waiting.withCandidate("b") { it.copy(selected = false) }
        assertTrue(off.canImport)
        assertNull(off.waitingNote)

        // 답이 오면 보낼 수 있다
        assertTrue(waiting.withCandidate("b") { it.withLookup(null) }.canImport)
    }

    // AI-generated (Claude)
    @Test
    fun `가져오기 실패는 불러오기 실패와 따로 두고 같은 선택으로 다시 보낸다`() {
        // Given 목록은 제대로 읽었고, 하나를 끈 채 2건을 보내는 중이다
        val sending = CalendarImportState(
            candidates = listOf(candidate("a"), candidate("b", selected = false), candidate("c")),
            linkedCount = 1,
            importing = true,
        )

        // When 보내기가 통신 문제로 실패한다
        val failed = sending.afterImportFailure("서버에 연결할 수 없다. 네트워크와 서버 상태를 확인한다.")

        // Then 목록 읽기 실패("불러오지 못했음" 카드)로 보이지 않고 버튼 위 줄에 가며, 끈 것은 꺼진 채 버튼이 같은 2건을 다시 보낸다
        assertNull(failed.error)
        assertEquals("서버에 연결할 수 없다. 네트워크와 서버 상태를 확인한다.", failed.importError)
        assertFalse(failed.importing)
        assertEquals(sending.candidates, failed.candidates)
        assertTrue(failed.canImport)
        assertEquals(2, failed.selectedCount)
        assertEquals("2건 다시 가져오기", failed.submitLabel)
        assertFalse(failed.isEmpty)
        assertFalse(failed.needsLink)
        assertEquals("2건 가져오기", failed.copy(importError = null).submitLabel)
    }

    // AI-generated (Claude)
    @Test
    fun `보내기 직전에 이미 시작한 일정의 선택을 끈다`() {
        // Given 9시 58분에 읽은 목록. 10시와 11시 일정을 골랐고, 끈 10시 일정이 하나 더 있다
        val tenAm = 10 * 60 * 60_000L
        val state = CalendarImportState(
            candidates = listOf(
                candidate("ten", startAt = tenAm),
                candidate("eleven", startAt = tenAm + 60 * 60_000L),
                candidate("ten-off", selected = false, startAt = tenAm),
            ),
        )

        // When 10시 1분에 가져오기를 누른다
        val sent = state.deselectStarted(nowMillis = tenAm + 60_000L)

        // Then 시작한 10시 일정만 끄고 몇 건 뺐는지 남긴다. 서버는 지난 일정이 하나라도 섞이면 전부 거절한다
        assertEquals(listOf("eleven"), sent.candidates.filter { it.selected }.map { it.externalId })
        assertEquals(1, sent.startedSkipped)
        assertEquals("이미 시작한 일정 1건은 뺐어요", sent.startedNote)
        assertTrue(sent.canImport)
    }

    // AI-generated (Claude)
    @Test
    fun `고른 일정이 모두 시작했으면 보낼 것이 없다`() {
        // Given 고른 일정 하나가 1초에 시작한다
        val state = CalendarImportState(candidates = listOf(candidate("a", startAt = 1_000L)))

        // When 시작한 뒤에 보내기 직전 거른다
        val late = state.deselectStarted(nowMillis = 2_000L)

        // Then 보내지 않고 안내만 남는다. 시작 전이면 아무것도 끄지 않는다
        assertFalse(late.canImport)
        assertEquals(1, late.startedSkipped)
        val early = state.deselectStarted(nowMillis = 500L)
        assertEquals(0, early.startedSkipped)
        assertNull(early.startedNote)
        assertTrue(early.canImport)
    }

    // AI-generated (Claude)
    @Test
    fun `다시 가져오기에서도 앞에서 뺀 이미 시작한 일정을 센다`() {
        // Given 10시 1분에 눌러 10시 일정 a 를 빼고 보냈는데 실패했다. b 는 10시 30분, c 는 11시 일정이다
        val tenAm = 10 * 60 * 60_000L
        val failed = CalendarImportState(
            candidates = listOf(
                candidate("a", startAt = tenAm),
                candidate("b", startAt = tenAm + 30 * 60_000L),
                candidate("c", startAt = tenAm + 60 * 60_000L),
            ),
        ).deselectStarted(nowMillis = tenAm + 60_000L).afterImportFailure("서버에 연결할 수 없다.")

        // When 10시 2분에 다시 가져오기를 누르고, a 를 다시 켜서 또 누르고, 10시 31분에 한 번 더 누른다
        val retry = failed.deselectStarted(nowMillis = tenAm + 2 * 60_000L)
        val reChecked = retry.withCandidate("a") { it.copy(selected = true) }
            .deselectStarted(nowMillis = tenAm + 3 * 60_000L)
        val later = reChecked.deselectStarted(nowMillis = tenAm + 31 * 60_000L)

        // Then 앞에서 끈 a 를 계속 세어 안내가 남고, 다시 켰다가 빠진 a 는 한 번만, 새로 시작한 b 는 더해 센다
        assertEquals(1, retry.startedSkipped)
        assertEquals("이미 시작한 일정 1건은 뺐어요", retry.startedNote)
        assertEquals(1, reChecked.startedSkipped)
        assertEquals(2, later.startedSkipped)
        assertEquals(listOf("c"), later.candidates.filter { it.selected }.map { it.externalId })
    }

    // AI-generated (Claude)
    @Test
    fun `목록을 다시 읽어도 사용자가 고른 것을 지킨다`() {
        // Given 새 일정 a 를 끄고 이미 가져온 일정 b 를 켰다
        val before = listOf(candidate("a", selected = false), candidate("b", imported = true))

        // When 캘린더를 하나 더 켜서 기본 선택으로 다시 읽었다(c 는 새로 나온 일정)
        val fresh = listOf(
            candidate("a"),
            candidate("b", selected = false, imported = true),
            candidate("c"),
        )
        val merged = keepSelections(fresh, before)

        // Then 고른 것은 그대로, 처음 보는 일정은 기본 선택을 따른다
        assertEquals(listOf(false, true, true), merged.map { it.selected })
        assertEquals(fresh, keepSelections(fresh, emptyList()))
    }

    // AI-generated (Claude)
    @Test
    fun `앞 목록이 이미 가져온 일정을 새 일정으로 보고 켰으면 그 켜짐은 옮기지 않는다`() {
        // Given 일정 목록을 받지 못한 읽기라 이미 가져온 x 가 새 일정으로 보여 기본으로 켜졌다
        val before = listOf(candidate("x"))

        // When 다시 읽은 목록에서는 x 가 이미 가져온 일정이라 기본으로 꺼진다
        val merged = keepSelections(listOf(candidate("x", selected = false, imported = true)), before)

        // Then 기본 선택을 따른다. 켠 채 옮기면 사용자가 켜지 않은 x 를 다시 보내 장소와 태그가 덮인다
        assertEquals(listOf(false), merged.map { it.selected })
    }

    // AI-generated (Claude)
    @Test
    fun `이미 가져온 일정이 새 일정으로 보여도 꺼 둔 것은 지킨다`() {
        // Given 제대로 읽은 목록에서 이미 가져온 y 는 꺼져 있다
        val before = listOf(candidate("y", selected = false, imported = true))

        // When 다시 읽을 때 일정 목록을 받지 못해 y 가 새 일정으로 보이고 기본으로 켜진다
        val merged = keepSelections(listOf(candidate("y")), before)

        // Then 꺼진 채 둔다. 켜면 앱에서 지운 일정이 살아나거나 저장된 장소가 덮인다
        assertEquals(listOf(false), merged.map { it.selected })
    }

    // AI-generated (Claude)
    @Test
    fun `저절로 찾는 줄은 고른 것 중 상한까지이고 이미 가져온 일정은 상한에 넣지 않는다`() {
        // Given 장소가 있는 새 일정 3건, 끈 일정, 장소 없는 일정, 사용자가 켠 이미 가져온 일정, 이미 찾은 일정
        val state = CalendarImportState(
            candidates = listOf(
                candidate("n1", location = "302동"),
                candidate("n2", location = "301동"),
                candidate("off", selected = false, location = "도서관"),
                candidate("none"),
                candidate("old", imported = true, location = "학생회관"),
                candidate("n3", location = "자하연"),
                candidate("done", location = "중앙도서관", place = place),
            ),
        )

        // When 상한 2 로 고른다
        val targets = state.lookupTargets(max = 2)

        // Then 이미 가져온 일정은 상한과 관계없이 찾는다 — 찾지 않고 보내면 서버가 저장된 장소를 지운다
        assertEquals(listOf("old", "n1", "n2"), targets)
    }

    // AI-generated (Claude)
    @Test
    fun `결과 스낵바에 보내기 전에 뺀 일정을 적는다`() {
        // Given / When / Then 추가 · 갱신은 그대로 적고, 뺀 일정이 있으면 덧붙인다
        assertEquals("3건 추가, 1건 갱신", importResultLabel(created = 3, updated = 1, unchanged = 0))
        assertEquals(
            "2건 추가, 1건 그대로, 이미 시작한 1건은 뺐어요",
            importResultLabel(created = 2, updated = 0, unchanged = 1, startedSkipped = 1),
        )
    }

    private val place = PlaceSearchItem(
        kakaoPlaceId = "2",
        name = "중앙도서관",
        address = "서울 관악구",
        lat = 37.45,
        lng = 126.95,
        category = null,
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
        assertFalse(denied.needsLink)

        val empty = CalendarImportState(candidates = emptyList(), linkedCount = 1)
        assertTrue(empty.isEmpty)
        assertFalse(empty.needsLink)
    }

    // Partly AI-generated (Claude)
    @Test
    fun `연동한 캘린더가 없으면 일정 없음이 아니라 연동 필요다`() {
        // 아무 캘린더도 읽지 않았는데 "가져올 일정이 없음" 을 보이면 사용자는 캘린더가
        // 비어 있다고 믿는다. 할 일은 캘린더를 켜는 것이다.
        val none = CalendarImportState(candidates = emptyList(), linkedCount = 0, linkPicker = true)
        assertFalse(none.isEmpty)
        assertTrue(none.needsLink)
        assertFalse(none.canImport)
    }

    @Test
    fun `읽는 중에는 연동 필요로 보지 않는다`() {
        // 화면을 처음 열 때는 연동 수를 아직 모른다. 그 사이에 스위치 안내가 번쩍이면 안 된다.
        val loading = CalendarImportState(loading = true)
        assertFalse(loading.needsLink)
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
