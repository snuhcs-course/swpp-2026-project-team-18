package com.swpp.wakeup.data.remote

// Partly AI-generated (Claude)

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 알람 기록 API(db-schema.md A3). 울림·끄기·미루기·자동 멈춤·등록을 올린다.
 *
 * 알람 → 끄기 → 출발 사이에서 "깨는 데 걸린 시간" 과 "실제 준비 시간" 을 나누려면
 * 울린 시각과 끈 시각이 있어야 한다. 지금까지는 기기에만 있었다. 조회 API 는 없다 —
 * 학습 배치와 admin 이 읽는다.
 *
 * 이동 관측과 같은 규칙이다. 같은 `client_uuid` 는 서버가 저장하지 않고 `duplicated`
 * 로 센다. 켬/끔은 서버가 일정 수정에서 직접 남기므로 앱이 보내지 않는다.
 */
interface AlarmLogsApi {

    @POST("api/alarm-logs/batch")
    suspend fun batch(@Body body: AlarmLogBatchRequest): Response<AlarmLogBatchResponse>
}

data class AlarmLogBatchRequest(
    val logs: List<AlarmLogInput>,
)

/**
 * 알람 사건 한 건. 서버 `AlarmLogWriteSerializer` 와 필드가 1:1 이다.
 *
 * 구버전 서버(0.11.0 이하)에는 이 경로가 없어 404 를 준다. 큐는 그 배치를 버린다
 * (다시 보내도 같다).
 */
data class AlarmLogInput(
    /** 어느 일정의 알람인지 */
    val event: Long,
    /** [KIND_SCHEDULED] · [KIND_FIRED] · [KIND_SNOOZED] · [KIND_DISMISSED] · [KIND_SILENCED] */
    val kind: String,
    /** 일어난 시각(기기 시계). ISO 8601, 오프셋 포함 */
    @SerializedName("occurred_at") val occurredAt: String,
    /** 그때 기기에 등록돼 있던 알람 시각. 모르면 null */
    @SerializedName("alarm_at") val alarmAt: String? = null,
    /** 미룬 분. 미루기에만 있다 */
    @SerializedName("snooze_minutes") val snoozeMinutes: Int? = null,
    /** 멱등 키. 일어난 순간 만들고 재전송해도 같은 값을 쓴다 */
    @SerializedName("client_uuid") val clientUuid: String,
) {
    companion object {
        const val KIND_SCHEDULED = "scheduled"
        const val KIND_FIRED = "fired"
        const val KIND_SNOOZED = "snoozed"
        const val KIND_DISMISSED = "dismissed"
        const val KIND_SILENCED = "silenced"
    }
}

data class AlarmLogBatchResponse(
    /** 새로 저장된 건수 */
    val accepted: Int = 0,
    /** 이미 있어서 저장하지 않은 건수. 이것도 "서버에 있다" 는 확인이다 */
    val duplicated: Int = 0,
)
