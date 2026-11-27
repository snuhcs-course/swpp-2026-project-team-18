package com.swpp.wakeup.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/** Proposed Iteration 5 contract. See docs/iter5/rooms-api.md; no local success fallback. */
interface RoomsApi {
    @GET("api/rooms/")
    suspend fun list(): Response<RoomsListDto>

    @POST("api/rooms/")
    suspend fun create(@Body input: RoomCreateInput): Response<RoomDto>

    @POST("api/rooms/join/")
    suspend fun join(@Body input: RoomJoinInput): Response<RoomDto>

    @GET("api/rooms/{id}/")
    suspend fun detail(@Path("id") id: Long): Response<RoomDto>
}

data class RoomsListDto(val results: List<RoomDto>? = null)

/** Only the shared appointment and public member fields are read. No member home/route DTO. */
data class RoomDto(
    val id: Long? = null,
    val title: String? = null,
    @SerializedName("start_at") val startAt: String? = null,
    @SerializedName("destination_name") val destinationName: String? = null,
    @SerializedName("group_target_probability") val groupTargetProbability: Double? = null,
    @SerializedName("member_count") val memberCount: Int? = null,
    @SerializedName("invite_code") val inviteCode: String? = null,
    val members: List<RoomMemberDto>? = null,
    @SerializedName("wake_suggestion") val wakeSuggestion: RoomWakeSuggestionDto? = null,
)

data class RoomMemberDto(
    val nickname: String? = null,
    @SerializedName("is_me") val isMe: Boolean? = null,
    @SerializedName("alarm_at") val alarmAt: String? = null,
    /** Null while the server has no recommendation. Zero means keep the current alarm. */
    @SerializedName("earlier_minutes") val earlierMinutes: Int? = null,
)

data class RoomWakeSuggestionDto(
    val nickname: String? = null,
    @SerializedName("earlier_minutes") val earlierMinutes: Int? = null,
)

data class RoomCreateInput(
    val title: String,
    @SerializedName("start_at") val startAt: String,
    val destination: RoomDestinationInput,
    @SerializedName("group_target_probability") val groupTargetProbability: Double,
    @SerializedName("client_uuid") val clientUuid: String,
)

/** These are a user's explicit search selection, never a measured device location. */
data class RoomDestinationInput(
    val name: String,
    val address: String?,
    val lat: Double,
    val lng: Double,
    @SerializedName("kakao_place_id") val kakaoPlaceId: String?,
)

data class RoomJoinInput(val code: String)
