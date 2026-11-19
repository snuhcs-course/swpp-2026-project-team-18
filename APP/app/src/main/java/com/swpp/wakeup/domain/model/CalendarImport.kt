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

    /** 장소 검색 결과. null 이면 못 찾았거나 아직 찾지 않았다 */
    val resolvedPlace: PlaceSearchItem? = null,

    /** 장소 검색이 진행 중인가 */
    val resolving: Boolean = false,

    /**
     * "10월 5일 월 09:00". **저장소가 만든다.**
     *
     * 화면에서 날짜 형식을 만들면 같은 규칙이 두 곳에 생긴다. 이 앱은 표시
     * 문자열을 저장소가 조립하는 규칙을 지킨다.
     */
    val whenLabel: String = "",
) {
    val externalId: String get() = source.externalId

    /**
     * 장소 상태 한 줄.
     *
     * 세 경우를 구분한다 — 못 찾음/찾음/캘린더에 아예 없음. 뭉치면 사용자가
     * "왜 알람이 안 잡히지" 의 원인을 알 수 없다.
     */
    val placeNote: String
        get() = when {
            resolving -> "장소 찾는 중…"
            resolvedPlace != null -> "장소 ${resolvedPlace.name}"
            source.location != null -> "\"${source.location}\" 을 찾지 못해 장소 없이 가져와요"
            else -> "캘린더에 장소가 없어요 · 나중에 추가할 수 있어요"
        }

    /** 장소가 없으면 알람 시각을 계산할 수 없다. 화면이 색으로 구분한다 */
    val willHavePlan: Boolean get() = resolvedPlace != null
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
    val error: String? = null,
    /**
     * 가져오기(보내기)가 실패한 이유. 목록을 읽지 못한 [error] 와 나눈다 — 예전에는 같은 칸이라 목록 위 오류 카드에 떴고,
     * 그 카드의 [다시 시도] 는 가져오기가 아니라 목록 읽기를 다시 했다. 버튼 위에 한 줄로 둔다(fix/iter4-he-bugs 이식).
     */
    val importError: String? = null,
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

    // Partly AI-generated (Claude) — fix/iter4-he-bugs 이식(11-19)
    /**
     * 고른 줄 중 장소를 찾는 중인 것이 있는가. 그 사이에 보내면 그 줄은 장소 없이 가서 알람이 잡히지 않았다. 끈 줄은
     * 보내지 않으므로 기다리지 않는다.
     */
    val waitingForPlaces: Boolean get() = candidates.any { it.selected && it.resolving }

    val canImport: Boolean
        get() = !importing && !loading && selectedCount > 0 &&
            selectedCount <= MAX_PER_REQUEST && !waitingForPlaces

    /** 장소를 기다리느라 버튼이 꺼졌을 때 버튼 위 안내. null 이면 기다릴 것이 없다 */
    val waitingNote: String?
        get() = if (waitingForPlaces) "고른 일정의 장소를 찾고 있어요. 다 찾으면 가져올 수 있어요" else null

    /** 상한을 넘겼을 때의 안내. null 이면 문제없다 */
    val tooManyNote: String?
        get() = if (selectedCount > MAX_PER_REQUEST) {
            "한 번에 ${MAX_PER_REQUEST}건까지 가져올 수 있어요. ${selectedCount - MAX_PER_REQUEST}건을 해제해 주세요"
        } else {
            null
        }

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

// Partly AI-generated (Claude) — fix/iter4-he-bugs 이식(11-19)
/**
 * 다시 읽은 후보 [fresh] 에 앞 목록 [previous] 의 선택을 옮긴다. 같은 일정은 externalId 로 찾는다.
 *
 * 캘린더를 하나 더 켜거나 "다시 시도" 로 목록을 다시 읽으면 후보가 기본 선택으로 새로 만들어져, 사용자가 일부러 끈 일정이
 * 다시 켜진 채 가져와졌다. 앞 목록에 없던 일정은 기본 선택을 따른다. 끈 것은 늘 옮기고, 켠 것은 이미 가져왔는지가 그대로일
 * 때만 옮긴다(켜짐은 기본값일 수 있다).
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
