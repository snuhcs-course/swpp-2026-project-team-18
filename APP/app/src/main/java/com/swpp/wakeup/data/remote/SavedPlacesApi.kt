package com.swpp.wakeup.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

// Partly AI-generated (Claude)
/**
 * 저장한 집 API(docs/iter3/saved-places-api.md, 서버 0.18.0). 모든 필드는 nullable 이고 기본값이 있다 —
 * 서버가 필드를 빼도 Gson 이 죽지 않게 하기 위해서다. 경로 끝의 `/` 는 서버 쪽 표기를 따른다.
 * 수정(`PATCH`)은 수정 화면이 없어 아직 쓰지 않는다.
 */
interface SavedPlacesApi {
    /** 기본 집(`default`)과 저장한 다른 집(`results`) */
    @GET("api/saved-places/")
    suspend fun list(): Response<SavedPlacesListDto>

    /** 201 새로 만듦, 200 같은 `client_uuid` 나 50m 안의 행이 있어 그것의 이름만 바꿈 */
    @POST("api/saved-places/")
    suspend fun create(@Body body: SavedPlaceInput): Response<SavedPlaceDto>

    @DELETE("api/saved-places/{id}/")
    suspend fun delete(@Path("id") id: Long): Response<Unit>

    /** 프로필 집과 이 집의 자리를 바꾼다. 응답은 목록 모양이다 */
    @POST("api/saved-places/{id}/make-default/")
    suspend fun makeDefault(@Path("id") id: Long): Response<SavedPlacesListDto>

    /** 기기 목록을 한 번에 올린다(최대 20개). 같은 `client_uuid` 를 다시 보내도 늘지 않는다 */
    @POST("api/saved-places/bulk/")
    suspend fun bulk(@Body body: SavedPlacesBulkRequest): Response<SavedPlacesListDto>
}

data class SavedPlaceDto(
    val id: Long? = null,
    @SerializedName("client_uuid") val clientUuid: String? = null,
    val label: String? = null,
    val name: String? = null,
    val address: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
)

data class SavedPlacesListDto(
    val default: SavedPlaceDto? = null,
    val results: List<SavedPlaceDto>? = null,
    val skipped: List<SavedPlaceSkippedDto>? = null,
)

data class SavedPlaceSkippedDto(
    @SerializedName("client_uuid") val clientUuid: String? = null,
    val reason: String? = null,
)

data class SavedPlaceInput(
    val label: String,
    val lat: Double,
    val lng: Double,
    val name: String? = null,
    val address: String? = null,
    @SerializedName("client_uuid") val clientUuid: String? = null,
)

data class SavedPlacesBulkRequest(val items: List<SavedPlaceInput>)
