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
            source.location != null -> "\"${source.location}\" 을 찾지 못함 · 장소 없이 가져옴"
            else -> "캘린더에 장소가 없음 · 나중에 추가할 수 있음"
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
    /** "3건 추가, 1건 갱신" */
    val resultLabel: String? = null,
    /** 가져오기가 끝나 화면을 닫아야 하는 상태 */
    val done: Boolean = false,
) {
    val selectedCount: Int get() = candidates.count { it.selected }

    val canImport: Boolean
        get() = !importing && !loading && selectedCount > 0 &&
            selectedCount <= MAX_PER_REQUEST

    /** 상한을 넘겼을 때의 안내. null 이면 문제없다 */
    val tooManyNote: String?
        get() = if (selectedCount > MAX_PER_REQUEST) {
            "한 번에 ${MAX_PER_REQUEST}건까지 가져올 수 있음. ${selectedCount - MAX_PER_REQUEST}건을 해제할 것"
        } else {
            null
        }

    val isEmpty: Boolean
        get() = !loading && !permissionDenied && candidates.isEmpty()

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
