package com.swpp.wakeup.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * 아침 루틴 블록 API. 서버 `apps/routines` 와 짝이다.
 *
 * ## 두 가지를 다룬다
 *
 * 1. **블록 정의** — "샤워는 12~18분" 같은 사용자별 설정. 여기를 고치면
 *    준비 시간 분포가 바뀐다. 서버는 이때 **재계산하지 않는다** — 설정 변경은
 *    다음 계산에 반영되면 되고, 매번 재계산하면 카카오 경로 쿼터를 수정
 *    횟수만큼 먹는다.
 * 2. **일정별 체크** — "이 아침에는 아침 식사를 건너뛴다". 서버가 **그 일정만
 *    즉시 재계산**하고 갱신된 일정을 그대로 돌려준다. 사용자가 즉시 결과를
 *    기대하는 행위라서다.
 *
 * ## 목록이 `Paged` 가 아니다
 *
 * `GET /api/routines/blocks` 는 서버에서 `pagination_class = None` 이라 **생
 * 배열**을 준다. 블록은 보통 5~10개이고 화면이 전체를 한 번에 그려야 준비 시간
 * 합계를 만들 수 있다. 다른 목록 API 와 모양이 달라 보이지만 의도된 것이다.
 */
interface RoutinesApi {

    @GET("api/routines/blocks")
    suspend fun blocks(): Response<List<RoutineBlockDto>>

    @POST("api/routines/blocks")
    suspend fun createBlock(@Body body: RoutineBlockWriteRequest): Response<RoutineBlockDto>

    /**
     * 부분 수정.
     *
     * **Gson 은 null 필드를 직렬화하지 않는다.** 그래서 [RoutineBlockWriteRequest]
     * 의 null 필드는 요청 본문에서 빠지고 서버가 기존 값을 유지한다. 이 성질에
     * 기대어 부분 수정을 만든다.
     *
     * 뒤집어 말하면 **null 로 "지우기" 를 표현할 수 없다.** `precondition` 을
     * 해제하는 경로가 앱에 아직 없는 이유다 — 지우기가 필요해지면 본문을 Map
     * 으로 바꾸거나 전용 필드를 서버에 추가해야 한다.
     */
    @PATCH("api/routines/blocks/{id}")
    suspend fun updateBlock(
        @Path("id") id: Long,
        @Body body: RoutineBlockWriteRequest,
    ): Response<RoutineBlockDto>

    @DELETE("api/routines/blocks/{id}")
    suspend fun deleteBlock(@Path("id") id: Long): Response<Unit>

    /** 이 일정에서 각 블록이 포함되는지. 블록 전체 + `checked` 를 함께 준다. */
    @GET("api/events/{id}/blocks")
    suspend fun eventBlocks(@Path("id") eventId: Long): Response<EventBlocksResponse>

    /**
     * 체크를 바꾸고 **알람을 즉시 재계산**한다.
     *
     * 응답은 갱신된 일정 전체다([EventDto]) — 새 `alarm_plan` 이 들어 있어서
     * 화면이 따로 다시 조회하지 않아도 된다. 보낸 블록만 갱신하므로 전체를
     * 들고 있을 필요도 없다.
     */
    @PUT("api/events/{id}/blocks")
    suspend fun setEventBlocks(
        @Path("id") eventId: Long,
        @Body body: BlockSelectionRequest,
    ): Response<EventDto>

    /**
     * 블록 관측 배치 업로드.
     *
     * **이것이 준비 시간 학습의 유일한 재료다.** 신고 범위만으로는 분포의
     * 사전값밖에 만들 수 없고, 실제 소요가 쌓여야 평균이 이동한다. 리포트의
     * `prep_over`(준비가 얼마나 초과됐나)도 이 데이터가 없으면 전부 "측정 안 됨"
     * 이 된다.
     *
     * 멱등하다 — 같은 `client_uuid` 는 서버가 무시한다. 아침에 판정하고
     * 네트워크가 없으면 큐에 남았다가 재전송되므로 필수다.
     */
    @POST("api/routines/observations/batch")
    suspend fun uploadObservations(
        @Body body: BlockObservationBatchRequest,
    ): Response<BlockObservationBatchResponse>
}

data class BlockObservationBatchRequest(
    val observations: List<BlockObservationInput>,
) {
    companion object {
        /** 서버 `BlockObservationBatchSerializer.MAX_ITEMS` 와 같아야 한다 */
        const val MAX_ITEMS = 200
    }
}

/**
 * 블록 관측 한 건. 서버 `BlockObservationSerializer` 와 필드가 1:1 이다.
 *
 * 이름이 어긋나면 400 이 난다. `scripts/check_app_contract.py` 가 지킨다.
 */
data class BlockObservationInput(
    /** 블록 id */
    val block: Long,
    /** 어느 아침이었나. 일정이 지워져도 관측은 남는다 */
    val event: Long? = null,
    /** `YYYY-MM-DD` */
    @SerializedName("observed_on") val observedOn: String,
    @SerializedName("duration_minutes") val durationMinutes: Double,
    /**
     * 그 블록을 시작할 때 쓸 수 있었던 여유(분). 음수면 이미 늦은 상태였다.
     *
     * 서버의 `slack_coef` 학습에 쓴다 — 여유가 많은 날은 준비가 느려진다.
     * 이 상관을 분리하지 않으면 "이 사람은 원래 느리다" 로 잘못 학습된다.
     */
    @SerializedName("slack_minutes") val slackMinutes: Double? = null,
    @SerializedName("was_parallel") val wasParallel: Boolean = false,
    /** 멱등 키. 판정 시점에 만들고 재전송해도 같은 값을 쓴다 */
    @SerializedName("client_uuid") val clientUuid: String,
    @SerializedName("client_recorded_at") val clientRecordedAt: String,
)

data class BlockObservationBatchResponse(
    val accepted: Int = 0,
    /** 이미 있어서 무시된 건수. 이것도 "서버에 있다" 는 확인이다 */
    val duplicated: Int = 0,
    val results: List<Long> = emptyList(),
)

/**
 * 블록 하나.
 *
 * [checked] 와 [explicit] 은 `GET /api/events/{id}/blocks` 응답에만 있다.
 * 블록 목록 API 에서는 null 이다 — 일정 문맥이 없으면 정의할 수 없는 값이다.
 */
data class RoutineBlockDto(
    val id: Long,
    val name: String,
    @SerializedName("default_min_minutes") val minMinutes: Int,
    @SerializedName("default_max_minutes") val maxMinutes: Int,
    /** 선행 블록 id. 앱에서 편집하지 않는다 */
    val precondition: Long? = null,
    @SerializedName("drop_cost") val dropCost: String? = null,
    val parallelizable: Boolean = false,
    @SerializedName("included_by_default") val includedByDefault: Boolean = true,
    val order: Int = 0,

    /** 이 블록에 쌓인 관측 수 */
    @SerializedName("observation_count") val observationCount: Int = 0,
    /** 실측 평균(분). 관측이 없으면 null — 0.0 이 아니다 */
    @SerializedName("observed_mean_minutes") val observedMeanMinutes: Double? = null,

    /** 이 일정에서 포함되는가. 일정 문맥이 없으면 null */
    val checked: Boolean? = null,
    /** 이 일정에서 사용자가 직접 바꿨는가. 아니면 기본값을 따르는 중이다 */
    val explicit: Boolean? = null,
) {
    companion object {
        /** `drop_cost` 값. 서버 `RoutineBlock.DropCost` 와 짝이다. */
        const val DROP_NONE = "none"
        const val DROP_SMALL = "small"
        const val DROP_MEDIUM = "medium"
        const val DROP_LARGE = "large"
        const val DROP_IMPOSSIBLE = "impossible"

        /** 블록 하나의 상한. 서버 `MAX_BLOCK_MINUTES` 와 같아야 한다 */
        const val MAX_MINUTES = 480
    }
}

/**
 * 블록 생성·수정 본문.
 *
 * 전부 nullable 이다. Gson 이 null 을 빼므로 생성에는 채워서 보내고 수정에는
 * 바꿀 것만 담는다. `user` 는 **보내지 않는다** — 보낼 수 있으면 남의 앞으로
 * 블록을 만들 수 있고, 서버도 본문의 `user` 를 무시한다.
 */
data class RoutineBlockWriteRequest(
    val name: String? = null,
    @SerializedName("default_min_minutes") val minMinutes: Int? = null,
    @SerializedName("default_max_minutes") val maxMinutes: Int? = null,
    @SerializedName("drop_cost") val dropCost: String? = null,
    val parallelizable: Boolean? = null,
    @SerializedName("included_by_default") val includedByDefault: Boolean? = null,
    val order: Int? = null,
)

data class EventBlocksResponse(
    val event: Long,
    val blocks: List<RoutineBlockDto> = emptyList(),
)

data class BlockSelectionRequest(
    val selections: List<BlockSelectionItem>,
)

data class BlockSelectionItem(
    /** 블록 id. 서버 필드 이름이 `block` 이다 */
    val block: Long,
    val checked: Boolean,
)
