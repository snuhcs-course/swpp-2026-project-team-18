// Partly AI-generated (Claude)
package com.swpp.wakeup.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/** 도움말 › 문의하기(제안 계약 BE-H4b, `docs/iter4/inquiry-api.md`). 서버에 아직 없으면 404/405 */
interface SupportApi {

    /** 문의 한 건을 보낸다. Bearer 필요. 201 `{id}`. 429 는 너무 잦음 */
    @POST("api/support/inquiries")
    suspend fun sendInquiry(@Body body: InquiryRequest): Response<InquiryResponse>
}

data class InquiryRequest(
    val message: String,
    @SerializedName("reply_email") val replyEmail: String,
    /** 앱 버전("0.21.0"). 답할 때 어느 버전의 문제인지 알려고 보낸다 */
    @SerializedName("app_version") val appVersion: String,
    /** 기기("samsung SM-S911N · Android 14"). 개인을 가리키는 값(기기 id 등)은 넣지 않는다 */
    val device: String,
)

data class InquiryResponse(val id: Long? = null)
