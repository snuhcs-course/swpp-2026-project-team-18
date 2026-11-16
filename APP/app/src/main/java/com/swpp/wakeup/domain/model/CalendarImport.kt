package com.swpp.wakeup.domain.model

import com.swpp.wakeup.calendar.CalendarEvent
import com.swpp.wakeup.data.remote.PlaceSearchItem

/**
 * 가져오기 후보 한 줄.
 *
 * ## 왜 장소를 자동으로 확정하지 않는가
 *
 * 캘린더의 장소는 사람이 쓴 문자열이다("302동", "강남 스타벅스", "줌"). 검색
 * 결과 첫 항목을 말없이 쓰면 엉뚱한 좌표로 알람이 잡히고, 사용자는 그 사실을
 * **알람이 틀린 뒤에야** 안다. 그래서 찾아 주기는 하되 [resolvedPlace] 를
 * 화면에 보여 사용자가 확인·해제할 수 있게 한다.
 *
 * 장소를 못 찾아도 가져올 수 있다. 그때 서버 계획은 `no_place` 가 되고, 일정은
 * 목록에 남아 나중에 장소를 넣을 수 있다.
 */
data class ImportCandidate(
    val source: CalendarEvent,

    /** 가져올지. 기본값은 [alreadyImported] 가 아니면 true */
    val selected: Boolean,

    /**
     * 이미 서버에 있는 일정인가.
     *
     * 다시 보내도 서버가 갱신만 하므로 위험하지는 않다. 다만 기본 선택에서
     * 빼 준다 — 사용자가 앱에서 지운 일정이 다시 살아나는 것이 가장 짜증나는
     * 경우다.
     */
    val alreadyImported: Boolean,

    /** 장소 검색 결과. null 이면 못 찾았거나 아직 찾지 않았다 — [searched] · [lookupFailed] 가 가른다 */
    val resolvedPlace: PlaceSearchItem? = null,

    /** 장소 검색이 진행 중이거나 차례를 기다리는 중인가 */
    val resolving: Boolean = false,

    /**
     * "10월 5일 월 09:00". **저장소가 만든다.**
     *
     * 화면에서 날짜 형식을 만들면 같은 규칙이 두 곳에 생긴다. 이 앱은 표시
     * 문자열을 저장소가 조립하는 규칙을 지킨다.
     */
    val whenLabel: String = "",

    // Partly AI-generated (Claude)
    /**
     * 장소 검색이 답을 받았는가(찾았거나 결과가 없었다).
     *
     * "아직 찾지 않음" 과 "찾지 못함" 을 가른다. 뭉치면 찾아 보지도 않은 줄이 "찾지 못함" 으로 보이고, 그대로
     * 보내면 장소 없이 간다 — 서버는 이미 가져온 일정에 장소가 없이 오면 저장된 장소를 지운다.
     */
    val searched: Boolean = false,

    /** 마지막 장소 검색이 통신 · 서버 문제로 실패했다. 결과가 없었던 것과 다르다. 다시 켜면 다시 찾는다 */
    val lookupFailed: Boolean = false,
) {
    val externalId: String get() = source.externalId

    /**
     * 장소 상태 한 줄.
     *
     * 여섯 경우를 구분한다 — 찾는 중/찾음/못 찾음/검색 실패/아직 찾지 않음/캘린더에 아예 없음. 뭉치면 사용자가
     * "왜 알람이 안 잡히지" 의 원인을 알 수 없다. 검색 실패와 아직 찾지 않은 줄은 켜면 찾으므로 그 방법을 함께
     * 적는다. (Partly AI-generated (Claude))
     */
    val placeNote: String
        get() = when {
            resolving -> "장소 찾는 중…"
            resolvedPlace != null -> "장소 ${resolvedPlace.name}"
            source.location == null -> "캘린더에 장소가 없음 · 나중에 추가할 수 있음"
            lookupFailed -> "\"${source.location}\" · 장소를 검색하지 못했어요. " +
                if (selected) "껐다 켜면 다시 찾아요" else "켜면 다시 찾아요"
            searched -> "\"${source.location}\" 을 찾지 못함 · 장소 없이 가져옴"
            selected -> "\"${source.location}\" · 아직 찾지 않았어요. 껐다 켜면 찾아요"
            else -> "\"${source.location}\" · 켜면 장소를 찾아요"
        }

    /** 장소가 없으면 알람 시각을 계산할 수 없다. 화면이 색으로 구분한다 */
    val willHavePlan: Boolean get() = resolvedPlace != null

    // AI-generated (Claude)
    /**
     * 켤 때 장소를 찾아야 하는가. 장소 문자열이 있는데 아직 답을 받지 못했다(찾지 않았거나 실패했다).
     *
     * 찾는 중이면 또 찾지 않는다. 결과가 없었던 줄도 다시 찾지 않는다 — 같은 문자열은 같은 답을 낸다.
     */
    val needsLookup: Boolean
        get() = source.location != null && resolvedPlace == null && !resolving && !searched

    // AI-generated (Claude)
    /** 장소 검색의 답을 넣는다. [found] 가 null 이면 결과가 없었던 것이다("찾지 못함") */
    fun withLookup(found: PlaceSearchItem?): ImportCandidate =
        copy(resolvedPlace = found, resolving = false, searched = true, lookupFailed = false)

    // AI-generated (Claude)
    /** 장소 검색이 통신 · 서버 문제로 실패했다. "찾지 못함" 으로 적지 않고, 다시 켜면 다시 찾는다([needsLookup]) */
    fun withLookupFailed(): ImportCandidate =
        copy(resolvedPlace = null, resolving = false, searched = false, lookupFailed = true)
}

/**
 * 가져오기 화면 상태.
 *
 * [permissionDenied] 와 [candidates] 가 빈 것은 다르다. 권한이 없어서 못 읽은
 * 것과 앞으로 일정이 없는 것은 사용자가 할 일이 완전히 다르다.
 */
data class CalendarImportState(
    val loading: Boolean = false,
    val importing: Boolean = false,
    val permissionDenied: Boolean = false,
    val candidates: List<ImportCandidate> = emptyList(),
    /** 목록을 읽지 못한 이유. 화면 위 카드에 두고, 그 카드의 다시 시도는 목록을 다시 읽는다 */
    val error: String? = null,
    // Partly AI-generated (Claude)
    /**
     * 가져오기(보내기)가 실패한 이유. [error] 와 따로 둔다.
     *
     * 한 칸에 두면 보내기 실패가 "불러오지 못했음" 카드로 보이고, 그 카드의 다시 시도가 목록을 다시 읽어 사용자가
     * 고른 것이 기본값으로 돌아갔다. 버튼 바로 위에 두고, 버튼이 같은 선택으로 다시 보낸다.
     */
    val importError: String? = null,
    // Partly AI-generated (Claude)
    /**
     * 보내기 직전에 이미 시작해서 앱이 선택을 끈 일정의 id([deselectStarted]). 목록을 다시 읽을 때까지 모은다.
     *
     * 누를 때마다 센 수로 덮으면 앞 누름에서 이미 끈 줄을 다시 세지 않아, 실패한 뒤 다시 가져오기에서 안내와 스낵바의 뺀
     * 수가 사라졌다. id 로 모으므로 사용자가 다시 켰다가 또 빠진 줄도 한 번만 센다.
     */
    val startedSkippedIds: Set<String> = emptySet(),
    /** "3건 추가, 1건 갱신" */
    val resultLabel: String? = null,
    /** 가져오기가 끝나 화면을 닫아야 하는 상태 */
    val done: Boolean = false,
    // Partly AI-generated (Claude)
    /**
     * 읽은 캘린더 수(⑱ 에서 연동한 것). 0 이면 일정을 읽지 않았다 — "일정이 없음" 이
     * 아니라 "읽을 캘린더가 없음" 이다.
     */
    val linkedCount: Int = 0,
    /**
     * 화면 위에 캘린더 스위치를 보여 주는가.
     *
     * 연동한 캘린더 없이 이 화면을 열었을 때 켠다. 하나를 켜자마자 스위치가 사라지면
     * 두 번째를 켤 수 없으므로, 화면을 닫을 때까지 그대로 둔다.
     */
    val linkPicker: Boolean = false,
) {
    val selectedCount: Int get() = candidates.count { it.selected }

    // AI-generated (Claude)
    /** 보내기 직전에 뺀 이미 시작한 일정 수([startedSkippedIds]). 0 이면 뺀 것이 없다 */
    val startedSkipped: Int get() = startedSkippedIds.size

    // Partly AI-generated (Claude)
    /**
     * 고른 줄 중 장소를 찾는 중이거나 차례를 기다리는 것이 있는가.
     *
     * 그 사이에 보내면 그 줄은 장소 없이 가서 알람이 잡히지 않고, 늦게 온 검색 결과는 닫힌 화면에서 버려진다. 끈 줄은
     * 보내지 않으므로 기다리지 않는다.
     */
    val waitingForPlaces: Boolean get() = candidates.any { it.selected && it.resolving }

    val canImport: Boolean
        get() = !importing && !loading && selectedCount > 0 &&
            selectedCount <= MAX_PER_REQUEST && !waitingForPlaces

    /** 상한을 넘겼을 때의 안내. null 이면 문제없다 */
    val tooManyNote: String?
        get() = if (selectedCount > MAX_PER_REQUEST) {
            "한 번에 ${MAX_PER_REQUEST}건까지 가져올 수 있음. ${selectedCount - MAX_PER_REQUEST}건을 해제할 것"
        } else {
            null
        }

    // AI-generated (Claude)
    /** 장소를 기다리느라 버튼이 꺼졌을 때 버튼 위 안내. null 이면 기다릴 것이 없다 */
    val waitingNote: String?
        get() = if (waitingForPlaces) "고른 일정의 장소를 찾고 있어요. 다 찾으면 가져올 수 있어요" else null

    // AI-generated (Claude)
    /** 보내기 직전에 뺀 이미 시작한 일정 안내. 어느 줄이 왜 꺼졌는지 말한다 */
    val startedNote: String?
        get() = if (startedSkipped > 0) "이미 시작한 일정 ${startedSkipped}건은 뺐어요" else null

    // Partly AI-generated (Claude)
    /** 가져오기 버튼 글자. 보내기가 실패한 뒤에는 같은 선택으로 다시 보낸다는 것을 밝힌다 */
    val submitLabel: String
        get() = when {
            selectedCount == 0 -> "가져올 일정을 고를 것"
            importError != null -> "${selectedCount}건 다시 가져오기"
            else -> "${selectedCount}건 가져오기"
        }

    // AI-generated (Claude)
    /**
     * 보내기 직전에 이미 시작한 일정의 선택을 끄고 그 id 를 [startedSkippedIds] 에 더한다.
     *
     * 목록은 읽을 때 시각으로 걸렀지만 화면을 열어 둔 사이에 시작한 일정이 있을 수 있다. 서버는 지난 일정을 받지
     * 않고, 한 건이라도 섞이면 어느 줄인지 말하지 않은 채 전부 거절한다.
     *
     * 덮지 않고 더한다. 앞 누름에서 끈 줄은 이미 꺼져 있어 이번에는 걸리지 않는다 — 덮으면 실패 뒤 다시 가져오기에서 뺀
     * 수가 0 이 된다.
     */
    fun deselectStarted(nowMillis: Long): CalendarImportState {
        fun ImportCandidate.started() = selected && source.startAtMillis <= nowMillis
        val started = candidates.filter { it.started() }.map { it.externalId }
        return copy(
            candidates = if (started.isEmpty()) candidates else candidates.map {
                if (it.started()) it.copy(selected = false) else it
            },
            startedSkippedIds = startedSkippedIds + started,
        )
    }

    // AI-generated (Claude)
    /**
     * 가져오기(보내기)가 실패했다. 이유는 [importError] 에만 두고 고른 것은 그대로 둔다.
     *
     * [error] 에 두면 "불러오지 못했음" 카드로 보이고, 그 카드의 다시 시도가 목록을 다시 읽어 고른 것을 기본값으로
     * 되돌렸다. 버튼이 "N건 다시 가져오기" 가 되어 같은 선택을 다시 보낸다.
     */
    fun afterImportFailure(message: String): CalendarImportState = copy(importing = false, importError = message)

    // AI-generated (Claude)
    /**
     * 화면을 열 때 저절로 장소를 찾을 줄. 고른 줄 중 아직 답이 없는 것이다([ImportCandidate.needsLookup]).
     *
     * 기본으로 고른 새 일정은 [max] 건까지만 찾는다(검색마다 카카오를 부른다). 이미 가져온 일정은 사용자가 켠
     * 것이라 상한과 관계없이 먼저 찾는다 — 찾지 않고 보내면 서버가 저장된 장소를 지운다.
     */
    fun lookupTargets(max: Int): List<String> {
        val (imported, fresh) = candidates.filter { it.selected && it.needsLookup }.partition { it.alreadyImported }
        return (imported + fresh.take(max)).map { it.externalId }
    }

    // AI-generated (Claude)
    /** [externalId] 줄만 [transform] 으로 바꾼다. 장소 검색 결과는 줄 id 로 찾아 넣는다 — 그 사이 목록 순서가 바뀔 수 있다 */
    fun withCandidate(externalId: String, transform: (ImportCandidate) -> ImportCandidate): CalendarImportState =
        copy(candidates = candidates.map { if (it.externalId == externalId) transform(it) else it })

    /** 연동한 캘린더를 읽었는데 일정이 없다. 연동한 캘린더가 없는 것과는 다르다 */
    val isEmpty: Boolean
        get() = !loading && !permissionDenied && linkedCount > 0 && candidates.isEmpty()

    /** 연동한 캘린더가 없어 아직 아무것도 읽지 않았다 */
    val needsLink: Boolean
        get() = !loading && !permissionDenied && linkedCount == 0

    companion object {
        /**
         * 한 요청의 상한. 서버 `CalendarImportSerializer.MAX_ITEMS` 와 같아야 한다.
         *
         * 서버가 400 으로 막지만, 화면에서 먼저 막아야 사용자가 고르는 도중에
         * 알 수 있다. 상한이 있는 이유는 새 일정마다 카카오 경로를 한 번 부르기
         * 때문이다.
         */
        const val MAX_PER_REQUEST = 50
    }
}

// AI-generated (Claude)
/**
 * 다시 읽은 후보 [fresh] 에 앞 목록 [previous] 의 선택을 옮긴다. 같은 일정은 externalId 로 찾는다.
 *
 * 캘린더를 하나 더 켜거나 "다시 시도" 로 목록을 다시 읽으면 후보가 기본 선택(이미 가져온 것만 꺼짐)으로 새로 만들어져,
 * 사용자가 일부러 끈 일정이 다시 켜진 채 가져와졌다. 앞 목록에 없던 일정은 기본 선택을 따른다.
 *
 * 끈 것은 늘 옮긴다. 켠 것은 [ImportCandidate.alreadyImported] 가 그대로일 때만 옮긴다 — 켜짐은 기본값일 수 있고, 기본값은
 * 그 값으로 정해진다. 앞 읽기가 서버의 일정 목록을 받지 못하면(5xx) 이미 가져온 일정이 새 일정으로 보여 기본으로 켜진다.
 * 그 켜짐을 바로잡힌 목록에 옮기면 사용자가 켜지 않은 일정을 다시 보내, 서버가 그 일정의 장소와 태그를 덮어쓴다.
 * 반대로 바뀐 줄(이미 가져옴 → 새 일정)은 끈 것을 지켜야 앱에서 지운 일정이 살아나지 않는다.
 */
fun keepSelections(fresh: List<ImportCandidate>, previous: List<ImportCandidate>): List<ImportCandidate> {
    if (previous.isEmpty()) return fresh
    val before = previous.associateBy { it.externalId }
    return fresh.map { candidate ->
        val prev = before[candidate.externalId]
        if (prev != null && (!prev.selected || prev.alreadyImported == candidate.alreadyImported)) {
            candidate.copy(selected = prev.selected)
        } else {
            candidate
        }
    }
}

// Partly AI-generated (Claude)
/**
 * 가져오기를 마친 뒤 스낵바 한 줄. "3건 추가, 1건 갱신".
 *
 * 보내기 직전에 뺀 이미 시작한 일정([CalendarImportState.startedSkipped])도 적는다. 화면은 끝나자마자 닫히므로 버튼 위
 * 안내는 거의 보이지 않는다 — 고른 수와 추가된 수가 다른 이유를 여기서 말한다.
 */
fun importResultLabel(created: Int, updated: Int, unchanged: Int, startedSkipped: Int = 0): String = buildString {
    append("${created}건 추가")
    if (updated > 0) append(", ${updated}건 갱신")
    if (unchanged > 0) append(", ${unchanged}건 그대로")
    if (startedSkipped > 0) append(", 이미 시작한 ${startedSkipped}건은 뺐어요")
}
