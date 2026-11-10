// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.home

import com.swpp.wakeup.data.repository.EventRepository

/*
 * 일정 추가·수정 저장 실패를 화면 어디에 어떤 말로 둘지 정하는 순수 함수.
 *
 * `HomeViewModel` 은 AndroidViewModel 이라 JVM 단위 테스트로 만들 수 없다. 판정만
 * 여기로 빼서 `SaveErrorsTest` 가 본다. 규칙(50m, 글자 수 등)은 서버가 소유한다.
 * 앱은 서버가 `error.details` 에 붙인 필드 이름으로 줄만 고르고 문구는 서버 것을 쓴다
 * (docs/iter3/event-create-errors.md §1-4).
 */

/** 추가·수정 화면에서 오류 문구를 붙일 수 있는 줄 */
/** [HomeViewModel.AddState.fieldErrors] 의 키라 공개한다 */
enum class AddField { TITLE, TIME, TAG, ROUTE, ORIGIN, DESTINATION }

internal enum class SaveErrorKind { INVALID, AUTH, SERVER, NETWORK, UNSUPPORTED, GONE }

/**
 * 저장 실패 한 건을 화면에 풀어 놓은 것.
 *
 * @property fields 각 줄 아래에 붙일 문구
 * @property line 버튼 위의 빨간 줄(`AddState.error`). 줄에 붙일 것만 있었으면 null
 * @property snackbar 눈에 띄게 한 번 보일 문구
 * @property retry snackbar 에 [다시 시도] 를 둘지 (입력 고침으로 풀리는 오류는 두지 않는다)
 */
internal data class SaveError(
    val kind: SaveErrorKind,
    val fields: Map<AddField, String>,
    val line: String?,
    val snackbar: String,
    val retry: Boolean,
)

internal const val SAVE_NETWORK_LINE = "서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요"
internal const val SAVE_AUTH_LINE = "로그인이 끝났어요. 다시 로그인하면 입력한 내용이 그대로 있어요"
internal const val SAVE_GONE_LINE = "이 일정을 찾지 못했어요. 다른 기기에서 지웠을 수 있어요"
internal const val SAVE_UNSUPPORTED_LINE = "아직 서버에서 지원하지 않아요"
internal const val SAVE_THROTTLED_LINE = "요청이 너무 잦아요. 잠시 뒤 다시 시도해 주세요"
internal const val SAVE_SERVER_LINE = "서버에 문제가 생겼어요. 잠시 뒤 다시 시도해 주세요"

private const val SAVE_FAILED = "일정을 저장하지 못했어요"
private const val EDIT_FAILED = "일정을 고치지 못했어요"

/** 서버 `details` 키 → 줄. 같은 줄에 키가 둘이면 위의 것이 이긴다. */
private val FIELD_ROWS: List<Pair<String, AddField>> = listOf(
    "title" to AddField.TITLE,
    "start_at" to AddField.TIME,
    "tag_key" to AddField.TAG,
    "route_key" to AddField.ROUTE,
    "origin_lat" to AddField.ORIGIN,
    "origin_lng" to AddField.ORIGIN,
    "origin_label" to AddField.ORIGIN,
    "origin_place_id" to AddField.ORIGIN,
    "destination" to AddField.DESTINATION,
    "destination_is_home" to AddField.DESTINATION,
)

/**
 * 저장 실패를 종류별로 나눈다. 위에서부터 먼저 맞는 것이 이긴다:
 * 통신 실패 → 400(입력 오류) → 401 → 404/405 → 429 → 그 밖(5xx·HTML·모름).
 *
 * @param editing 일정 수정(PATCH)인가. 404 의 뜻과 문구가 다르다.
 */
internal fun saveErrorOf(failure: EventRepository.Result.Failure, editing: Boolean): SaveError {
    val failed = if (editing) EDIT_FAILED else SAVE_FAILED
    val generic = "$failed. 입력한 내용은 그대로 있어요"
    return when {
        failure.network -> SaveError(SaveErrorKind.NETWORK, emptyMap(), SAVE_NETWORK_LINE, generic, retry = true)

        failure.status == 400 -> {
            val rows = LinkedHashMap<AddField, String>()
            val used = HashSet<String>()
            FIELD_ROWS.forEach { (key, row) ->
                val text = failure.fields[key] ?: return@forEach
                used += key
                rows.putIfAbsent(row, text)
            }
            val rest = failure.fields.filterKeys { it !in used }.values
            val line = when {
                rest.isNotEmpty() -> rest.joinToString(" ")
                failure.fields.isEmpty() -> failure.message
                else -> null
            }
            SaveError(
                kind = SaveErrorKind.INVALID,
                fields = rows,
                line = line,
                snackbar = "$failed. 표시한 칸을 확인해 주세요",
                retry = false,
            )
        }

        failure.status == 401 ->
            SaveError(SaveErrorKind.AUTH, emptyMap(), SAVE_AUTH_LINE, generic, retry = false)

        failure.status == 404 && editing ->
            SaveError(SaveErrorKind.GONE, emptyMap(), SAVE_GONE_LINE, SAVE_GONE_LINE, retry = false)

        failure.status == 404 || failure.status == 405 ->
            SaveError(SaveErrorKind.UNSUPPORTED, emptyMap(), SAVE_UNSUPPORTED_LINE, SAVE_UNSUPPORTED_LINE, retry = false)

        failure.status == 429 ->
            SaveError(SaveErrorKind.SERVER, emptyMap(), SAVE_THROTTLED_LINE, generic, retry = true)

        else -> {
            // 서버가 JSON 으로 준 문구(`server_error`)만 믿는다. HTML 500 은 일반 문구.
            val line = if (failure.errorCode != null) failure.message else SAVE_SERVER_LINE
            SaveError(SaveErrorKind.SERVER, emptyMap(), line, generic, retry = true)
        }
    }
}

// Partly AI-generated (Claude)
/** 종류 이름의 최대 글자 수(서버 20자, docs/iter3/event-tags-api.md §5) */
internal const val TAG_LABEL_MAX = 20

private const val TAG_LIMIT_LINE = "종류는 20개까지 만들 수 있어요"

// Partly AI-generated (Claude)
/**
 * "+ 추가" 로 종류를 만들다 실패했을 때 대화상자에 보일 한 줄.
 * 통신 실패 → 400(서버 문구) → 409 `tag_limit` → 404/405(구버전 서버) → 429 → 그 밖.
 */
internal fun tagAddErrorOf(failure: EventRepository.Result.Failure): String = when {
    failure.network -> SAVE_NETWORK_LINE
    failure.status == 400 -> failure.fields["label"] ?: failure.message
    failure.status == 409 && failure.errorCode == "tag_limit" -> TAG_LIMIT_LINE
    failure.status == 404 || failure.status == 405 -> SAVE_UNSUPPORTED_LINE
    failure.status == 429 -> SAVE_THROTTLED_LINE
    failure.status == 401 -> SAVE_AUTH_LINE
    failure.errorCode != null -> failure.message
    else -> SAVE_SERVER_LINE
}

// Partly AI-generated (Claude)
internal const val TAG_DUPLICATE_LINE = "같은 이름의 종류가 이미 있어요"

// Partly AI-generated (Claude)
internal const val TAG_GONE_LINE = "이 종류를 찾지 못했어요. 다른 기기에서 지웠을 수 있어요"

// Partly AI-generated (Claude)
/**
 * 내 종류 이름 바꾸기·지우기가 실패했을 때 대화상자에 보일 한 줄.
 * 통신 실패 → 400(서버 문구) → 409 `tag_duplicate` → 404(지워진 종류) → 405(구버전 서버) → 429 → 그 밖.
 */
internal fun tagEditErrorOf(failure: EventRepository.Result.Failure): String = when {
    failure.network -> SAVE_NETWORK_LINE
    failure.status == 400 -> failure.fields["label"] ?: failure.message
    failure.status == 409 && failure.errorCode == "tag_duplicate" -> TAG_DUPLICATE_LINE
    failure.status == 404 -> TAG_GONE_LINE
    failure.status == 405 -> SAVE_UNSUPPORTED_LINE
    failure.status == 429 -> SAVE_THROTTLED_LINE
    failure.status == 401 -> SAVE_AUTH_LINE
    failure.errorCode != null -> failure.message
    else -> SAVE_SERVER_LINE
}
