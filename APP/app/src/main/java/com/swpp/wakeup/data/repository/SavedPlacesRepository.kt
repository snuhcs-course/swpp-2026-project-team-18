package com.swpp.wakeup.data.repository

import android.util.Log
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.SavedPlaceInput
import com.swpp.wakeup.data.remote.SavedPlacesApi
import com.swpp.wakeup.data.remote.SavedPlacesBulkRequest
import com.swpp.wakeup.domain.model.SavedHome
import com.swpp.wakeup.domain.model.bulkItemsOf
import com.swpp.wakeup.domain.model.skippedOverLimit
import com.swpp.wakeup.domain.model.toSavedHome
import com.swpp.wakeup.domain.model.toSavedHomes
import retrofit2.Response
import java.io.IOException

// Partly AI-generated (Claude)
/**
 * 저장한 집 서버 API 의 결과(B2, docs/iter3/saved-places-api.md).
 *
 * - [Ok]: [homes] 는 서버가 확인한 행. 목록·bulk·기본으로는 전체 목록, 추가는 만든(바뀐) 행 하나, 삭제는 비어 있다.
 *   [skippedOverLimit] 는 bulk 에서 한도 때문에 올리지 못한 항목 수.
 * - [Unsupported]: 서버가 이 경로를 모른다(404·405 = 이전 서버). 기기 목록을 그대로 쓴다.
 * - [Failure]: [network] 는 통신 자체의 실패, [status]·[code] 는 서버 응답에서 온 것.
 */
sealed interface SavedPlacesResult {
    data class Ok(val homes: List<SavedHome>, val skippedOverLimit: Int = 0) : SavedPlacesResult
    data object Unsupported : SavedPlacesResult
    data class Failure(
        val message: String,
        val code: String? = null,
        val status: Int? = null,
        val fields: Map<String, String> = emptyMap(),
        val network: Boolean = false,
    ) : SavedPlacesResult
}

/**
 * 저장한 집 저장소. [EventRepository] 처럼 API 를 생성자로 받아 JVM 에서 가짜로 시험한다.
 *
 * 규칙(5곳 한도, 기본 집과 50m, 중복)은 서버가 소유한다. 여기서는 응답을 앱 모델로 바꾸고
 * 404·405 를 [SavedPlacesResult.Unsupported] 로 모을 뿐이다.
 */
class SavedPlacesRepository(private val api: SavedPlacesApi = ApiClient.savedPlaces) {
    /** 서버의 저장한 집 목록 */
    suspend fun list(): SavedPlacesResult = guard {
        val response = api.list()
        val dto = response.body()?.takeIf { response.isSuccessful }
            ?: return@guard unsupportedOrFailure(response)
        SavedPlacesResult.Ok(dto.toSavedHomes())
    }

    /** 기기 목록을 올린다. 한 번에 20개까지 보낸다(앱은 5개까지 둔다) */
    suspend fun bulk(homes: List<SavedHome>): SavedPlacesResult = guard {
        val response = api.bulk(SavedPlacesBulkRequest(bulkItemsOf(homes)))
        val dto = response.body()?.takeIf { response.isSuccessful }
            ?: return@guard unsupportedOrFailure(response)
        SavedPlacesResult.Ok(dto.toSavedHomes(), skippedOverLimit(dto))
    }

    /** 집을 하나 더한다. 201 과 200(같은 자리라 이름만 바뀜) 모두 성공이다 */
    suspend fun create(input: SavedPlaceInput): SavedPlacesResult = guard {
        val response = api.create(input)
        val row = response.body()?.takeIf { response.isSuccessful }
            ?: return@guard unsupportedOrFailure(response)
        val home = row.toSavedHome() ?: return@guard SavedPlacesResult.Failure(MESSAGE_BAD_ROW)
        SavedPlacesResult.Ok(listOf(home))
    }

    /** 삭제. 서버에 이미 없으면(404) 지운 것과 같으므로 성공으로 본다 */
    suspend fun delete(serverId: Long): SavedPlacesResult = guard {
        val response = api.delete(serverId)
        when {
            response.isSuccessful || response.code() == 404 -> SavedPlacesResult.Ok(emptyList())
            else -> failure(response)
        }
    }

    /** 기본 집과 자리를 바꾼다. 404 는 [SavedPlacesResult.Failure] 의 `code == "not_found"` 다(행이 사라짐) */
    suspend fun makeDefault(serverId: Long): SavedPlacesResult = guard {
        val response = api.makeDefault(serverId)
        val dto = response.body()?.takeIf { response.isSuccessful }
            ?: return@guard if (response.code() == 404) {
                failure(response).copy(code = CODE_NOT_FOUND)
            } else {
                failure(response)
            }
        SavedPlacesResult.Ok(dto.toSavedHomes())
    }

    private fun unsupportedOrFailure(response: Response<*>): SavedPlacesResult =
        if (response.code() == 404 || response.code() == 405) SavedPlacesResult.Unsupported else failure(response)

    private fun failure(response: Response<*>): SavedPlacesResult.Failure {
        val raw = response.errorBody()?.string()
        if (response.code() >= 500 || response.code() == 429) Log.w(TAG, "HTTP ${response.code()}")
        return SavedPlacesResult.Failure(
            message = ApiClient.parseErrorMessage(raw) ?: fallbackMessage(response.code()),
            code = ApiClient.parseErrorCode(raw),
            status = response.code(),
            fields = ApiClient.parseErrorFields(raw),
        )
    }

    private inline fun guard(block: () -> SavedPlacesResult): SavedPlacesResult = try {
        block()
    } catch (e: IOException) {
        Log.w(TAG, "network failure", e)
        SavedPlacesResult.Failure(MESSAGE_NETWORK, network = true)
    } catch (e: Exception) {
        Log.e(TAG, "unexpected failure", e)
        SavedPlacesResult.Failure(MESSAGE_UNKNOWN)
    }

    private fun fallbackMessage(status: Int): String = when {
        status == 401 -> "다시 로그인해야 합니다"
        status == 429 -> "요청이 너무 잦아요. 잠시 뒤 다시 시도해 주세요"
        status >= 500 -> "서버에 문제가 생겼어요"
        else -> MESSAGE_UNKNOWN
    }

    companion object {
        const val CODE_NOT_FOUND = "not_found"
        const val CODE_SAME_AS_DEFAULT = "same_as_default"
        const val CODE_LIMIT = "saved_place_limit"
        const val MESSAGE_NETWORK = "서버에 연결하지 못했어요"
        private const val MESSAGE_UNKNOWN = "알 수 없는 오류가 났어요"
        private const val MESSAGE_BAD_ROW = "서버 응답을 읽지 못했어요"
        private const val TAG = "SavedPlacesRepository"
    }
}

// Partly AI-generated (Claude)
/**
 * 다른 집을 서버에 추가하다 실패했을 때 입력 아래에 보일 문구(B2). 규칙 문구는 서버 코드로 가리고, 입력이 틀린
 * 400 은 좌표 칸의 서버 문구를 쓴다. 그 밖에는 서버 문구를 그대로 쓴다.
 */
internal fun savedPlaceAddMessage(failure: SavedPlacesResult.Failure): String = when {
    failure.code == SavedPlacesRepository.CODE_SAME_AS_DEFAULT -> "기본 집과 같은 곳이에요(50m 안). 다른 곳을 골라 주세요"
    failure.code == SavedPlacesRepository.CODE_LIMIT -> "다른 집은 5곳까지 저장할 수 있어요"
    failure.network -> "서버에 연결하지 못해 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요"
    failure.status == 400 -> failure.fields["lat"] ?: failure.fields["lng"] ?: failure.message
    else -> failure.message
}
