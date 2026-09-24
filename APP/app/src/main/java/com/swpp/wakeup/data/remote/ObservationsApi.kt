package com.swpp.wakeup.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 이동 관측 API.
 *
 * 앱이 GPS 로 판별한 실제 출발·도착 시각을 서버에 올린다. 이 값이 분포
 * 학습의 유일한 재료다 — 카카오 경로 API 는 점추정치만 주고 변동성 정보를
 * 주지 않는다.
 */
interface ObservationsApi {

    /**
     * 배치 업로드.
     *
     * **여러 건을 한 번에 보낸다.** 판별은 이동 중에 일어나고 그때 네트워크가
     * 없을 수 있다(지하철). 로컬 큐에 쌓아 두고 연결되면 한꺼번에 보낸다.
     *
     * 멱등하다 — 같은 `client_uuid` 는 서버가 무시한다. 응답이 유실돼 다시
     * 보내도 행이 늘지 않는다.
     */
    @POST("api/observations/batch")
    suspend fun batch(@Body body: ObservationBatchRequest): Response<ObservationBatchResponse>

    @GET("api/observations")
    suspend fun list(
        @Query("event") eventId: Long? = null,
        @Query("kind") kind: String? = null,
        @Query("limit") limit: Int = 100,
    ): Response<Paged<TripObservationDto>>
}

data class ObservationBatchRequest(
    val observations: List<TripObservationInput>,
)

/**
 * 관측 한 건.
 *
 * 서버 `apps/observations/serializers.TripObservationWriteSerializer` 와
 * 필드가 1:1 이다. 이름이 어긋나면 400 이 난다.
 */
data class TripObservationInput(
    /** 어느 일정의 이동인지. */
    val event: Long,
    /** `depart` / `arrive` */
    val kind: String,
    /** `gps` / `manual` */
    val detector: String = DETECTOR_GPS,
    /** 판정 시각. ISO 8601 (오프셋 포함) */
    @SerializedName("observed_at") val observedAt: String,
    val lat: Double,
    val lng: Double,
    /** 판정에 쓴 fix 의 오차 반경(m). 서버가 50m 초과를 거부한다 */
    @SerializedName("accuracy_m") val accuracyM: Double,
    /** 기준점까지 거리(m). 출발은 집, 도착은 목적지 기준 */
    @SerializedName("distance_m") val distanceM: Double,
    /**
     * 목적지 반경 안에서 머문 시간(초). 도착에만 있고 출발은 null.
     *
     * 판정 근거의 세기다. 기준 체류(2분)를 넘겨 판정한 것과 추적 마감에 밀려
     * 도중에 확정한 것을 이 값으로 구분한다. 나중에 "2분을 채운 관측만" 골라
     * 학습에 쓸 수 있다.
     */
    @SerializedName("dwell_seconds") val dwellSeconds: Int? = null,
    /** 멱등 키. 앱이 만들고 재전송해도 같은 값을 쓴다 */
    @SerializedName("client_uuid") val clientUuid: String,
) {
    companion object {
        const val DETECTOR_GPS = "gps"
        const val DETECTOR_MANUAL = "manual"
        const val KIND_DEPART = "depart"
        const val KIND_ARRIVE = "arrive"
    }
}

data class ObservationBatchResponse(
    /** 새로 저장된 건수. */
    val accepted: Int = 0,
    /** 이미 있어서 무시된 건수. 이것도 "서버에 있다" 는 확인이다 */
    val duplicated: Int = 0,
    val results: List<TripObservationDto> = emptyList(),
)

data class TripObservationDto(
    val id: Long,
    val event: Long,
    val kind: String,
    @SerializedName("kind_label") val kindLabel: String?,
    val detector: String?,
    @SerializedName("observed_at") val observedAt: String,
    val lat: Double,
    val lng: Double,
    @SerializedName("accuracy_m") val accuracyM: Double?,
    @SerializedName("distance_m") val distanceM: Double?,
    /** 반경 안에서 머문 시간(초). 도착이 아니거나 옛 관측이면 null */
    @SerializedName("dwell_seconds") val dwellSeconds: Int?,
    /** 계획된 시각. 계획이 없으면 null */
    @SerializedName("planned_at") val plannedAt: String?,
    /** 계획보다 늦은 분. 이르면 음수. 계획이 없으면 null */
    @SerializedName("delay_minutes") val delayMinutes: Int?,
    @SerializedName("client_uuid") val clientUuid: String?,
)
