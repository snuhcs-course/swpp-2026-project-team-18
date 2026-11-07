package com.swpp.wakeup.domain.model

import com.swpp.wakeup.data.remote.SavedPlaceDto
import com.swpp.wakeup.data.remote.SavedPlaceInput
import com.swpp.wakeup.data.remote.SavedPlacesListDto

// Partly AI-generated (Claude)
/*
 * 저장한 집을 서버와 맞추는 순수 함수들(B2, docs/iter3/saved-places-api.md). 네트워크도 저장소도
 * 건드리지 않아 JVM 단위 테스트로 규칙을 고정한다.
 */

/** 서버가 한 번에 받는 항목 수 */
const val SAVED_PLACES_BULK_MAX = 20

/** 서버 `label` 한도. 앱 입력 한도([SavedHomes.LABEL_MAX])보다 넓다 */
const val SAVED_PLACE_LABEL_MAX = 40

/** 서버가 한도 밖이라 올리지 못한 항목의 `skipped.reason` */
const val SKIP_REASON_LIMIT = "limit"

/**
 * 서버 행을 앱 모델로. 필수 값(id·좌표)이 없거나 쓸 수 없는 값이면 null.
 *
 * - UI 키 `id` 는 `client_uuid` 가 있으면 그것(이 기기에서 올린 행은 예전 id 를 그대로 가진다), 없으면
 *   `"server-<id>"`.
 * - 이름과 label 은 한쪽이 비면 다른 쪽으로 채운다.
 */
fun SavedPlaceDto.toSavedHome(): SavedHome? {
    val serverId = id ?: return null
    val la = lat ?: return null
    val ln = lng ?: return null
    val shownLabel = label?.trim().orEmpty()
    val shownName = name?.trim().orEmpty()
    val home = SavedHome(
        id = clientUuid?.takeIf { it.isNotBlank() } ?: "server-$serverId",
        label = shownLabel.ifEmpty { shownName },
        name = shownName.ifEmpty { shownLabel },
        address = address?.takeIf { it.isNotBlank() },
        lat = la,
        lng = ln,
        serverId = serverId,
    )
    return home.takeIf { runCatching { it.isUsable }.getOrDefault(false) }
}

/** 목록 응답의 `results` 를 앱 모델로. 쓸 수 없는 행은 버린다 (`default` 는 프로필 집이라 넣지 않는다) */
fun SavedPlacesListDto.toSavedHomes(): List<SavedHome> = results.orEmpty().mapNotNull { it?.toSavedHome() }

/**
 * `POST /bulk/` 에 보낼 항목. 한 번에 [SAVED_PLACES_BULK_MAX] 개까지, 이름은 [SAVED_PLACE_LABEL_MAX] 자까지.
 * `client_uuid` 는 기기 id 를 그대로 써서, 같은 요청을 다시 보내도 서버에서 늘지 않는다.
 */
fun bulkItemsOf(homes: List<SavedHome>): List<SavedPlaceInput> = homes.take(SAVED_PLACES_BULK_MAX).map {
    SavedPlaceInput(
        label = it.label.trim().ifEmpty { it.name }.take(SAVED_PLACE_LABEL_MAX),
        lat = it.lat,
        lng = it.lng,
        name = it.name,
        address = it.address,
        clientUuid = it.id,
    )
}

/**
 * bulk 응답에서 **한도 때문에** 올리지 못한 항목 수. `same_as_default`(이미 기본 집)는 잃은 것이 아니라
 * 세지 않는다.
 */
fun skippedOverLimit(dto: SavedPlacesListDto): Int = dto.skipped.orEmpty().count { it?.reason == SKIP_REASON_LIMIT }

// Partly AI-generated (Claude)
/**
 * 처음 올리는 중인가. 동기화 작업이 도는 동안 서버가 아직 "확인함" 표시를 만들지 않았으면 true. 이때 집을
 * 더하거나 지우면 업로드 응답(`replaceSynced`)이 그 변경을 덮어쓰므로 막는다(code-review m1).
 */
fun savedHomesFirstUploadRunning(syncJobActive: Boolean, synced: Boolean): Boolean = syncJobActive && !synced

/** 처음 올리는 중에 집을 더하거나 지우거나 기본으로 바꾸려 할 때의 안내 */
const val SAVED_HOMES_UPLOADING_NOTICE = "저장한 집을 서버에 올리는 중이에요. 잠시 뒤 다시 해 주세요"
