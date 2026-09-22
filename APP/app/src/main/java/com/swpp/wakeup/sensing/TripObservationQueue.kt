package com.swpp.wakeup.sensing

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.swpp.wakeup.background.JitWork
import com.swpp.wakeup.data.local.discardIfForeign
import com.swpp.wakeup.data.local.stampOwner
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.ObservationBatchRequest
import com.swpp.wakeup.data.remote.ObservationsApi
import com.swpp.wakeup.data.remote.TripObservationInput
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * 관측 업로드 큐.
 *
 * **왜 큐가 필요한가.** 판정은 이동 중에 일어난다. 지하철 안이면 네트워크가
 * 없다. 그때 업로드가 실패하면 그 관측은 영영 사라지고, 관측이 사라지면
 * 학습할 재료가 없다. 그래서 판정 즉시 디스크에 적고, 올라가면 지운다.
 *
 * 멱등성은 서버가 보장한다 — 같은 `client_uuid` 는 무시된다. 그래서 "보냈는지
 * 확실하지 않으면 다시 보낸다" 를 안심하고 할 수 있다. UUID 는 판정 시점에
 * 만들어 큐에 함께 저장한다. 재전송할 때 새로 만들면 멱등성이 깨진다.
 *
 * WorkManager 를 쓰지 않는다. 재시도 지점이 이미 두 곳 있고(추적 서비스가
 * 판정할 때, 홈 화면이 새로고침할 때) 그 사이 지연은 문제가 되지 않는다 —
 * 학습은 실시간이 아니다. WorkManager 는 FE-P1 에서 다른 필요와 함께 들인다.
 */
class TripObservationQueue(
    context: Context,
    private val api: ObservationsApi = ApiClient.observations,
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    val pendingCount: Int get() = pending().size

    fun pending(): List<TripObservationInput> {
        // 앞 사용자의 큐를 지금 로그인한 계정의 토큰으로 올리지 않는다.
        // 근거는 [com.swpp.wakeup.data.local.isForeignOwner] 에 있다.
        if (prefs.discardIfForeign(appContext)) return emptyList()

        val raw = prefs.getString(KEY_PENDING, null) ?: return emptyList()
        return try {
            gson.fromJson<List<TripObservationInput>>(raw, TYPE).orEmpty()
        } catch (e: JsonSyntaxException) {
            Log.w(TAG, "큐를 읽을 수 없어 버린다", e)
            prefs.edit { remove(KEY_PENDING) }
            emptyList()
        }
    }

    /**
     * 판정 즉시 호출한다. 업로드보다 저장이 먼저다.
     *
     * 저장한 뒤 **업로드 작업을 예약한다.** 지금 네트워크가 없어도 된다 —
     * WorkManager 가 연결되는 순간 실행한다. 이것이 없으면 큐가 비는 계기가
     * 앱을 여는 것뿐이어서, 앱을 며칠 열지 않은 사용자의 아침이 학습에
     * 들어가지 않는다.
     */
    fun enqueue(observation: TripObservationInput) {
        val next = pending().filterNot { it.clientUuid == observation.clientUuid } +
            observation
        write(next)
        Log.i(TAG, "관측 적재: ${observation.kind} (대기 ${next.size}건)")
        JitWork.requestObservationUpload(appContext)
    }

    /**
     * 쌓인 관측을 올린다.
     *
     * 성공하면 큐를 비운다. **중복(`duplicated`)도 성공으로 본다** — 서버에
     * 이미 있다는 확인이므로 계속 들고 있을 이유가 없다.
     *
     * 400 은 재시도해도 계속 400 이다(형식이 틀렸거나 오차가 너무 크다).
     * 그 관측은 버린다. 안 버리면 큐가 영구히 막혀 뒤의 정상 관측도 못 올린다.
     *
     * @return 서버가 받은 건수. 올릴 것이 없으면 0.
     */
    suspend fun flush(): Int {
        if (!ApiClient.isReady) return 0

        val items = pending()
        if (items.isEmpty()) return 0

        return try {
            val response = api.batch(ObservationBatchRequest(items))
            when {
                response.isSuccessful -> {
                    val body = response.body()
                    val handled = (body?.accepted ?: 0) + (body?.duplicated ?: 0)
                    write(emptyList())
                    Log.i(
                        TAG,
                        "관측 업로드 완료: 신규 ${body?.accepted ?: 0}건 " +
                            "중복 ${body?.duplicated ?: 0}건",
                    )
                    handled
                }

                response.code() == 400 || response.code() == 404 -> {
                    // 형식이 틀렸거나 일정이 사라졌다. 재시도해도 같다.
                    Log.w(
                        TAG,
                        "서버가 관측 ${items.size}건을 거부했다(${response.code()}). 버린다: " +
                            ApiClient.parseErrorMessage(response.errorBody()?.string()),
                    )
                    write(emptyList())
                    0
                }

                else -> {
                    // 401·5xx 는 일시적일 수 있다. 들고 있는다.
                    Log.w(TAG, "관측 업로드 실패(${response.code()}). 큐에 남긴다")
                    0
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "네트워크가 없어 관측 ${items.size}건을 큐에 남긴다")
            0
        } catch (e: CancellationException) {
            // **정상 경로다.** 도착 판정이 나면 서비스가 바로 멈추면서 이
            // 업로드를 취소한다. 큐에 그대로 남으므로 서비스 종료 직전의
            // 마지막 flush 가 다시 보낸다(서버가 중복을 무시한다).
            // 여기서 ERROR 로 남기면 실패처럼 보인다.
            Log.i(TAG, "업로드가 취소됐다. 관측 ${items.size}건은 큐에 남아 재전송된다")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "관측 업로드 중 예상하지 못한 실패. 큐에 남긴다", e)
            0
        }
    }

    private fun write(items: List<TripObservationInput>) {
        prefs.edit {
            if (items.isEmpty()) remove(KEY_PENDING)
            else putString(KEY_PENDING, gson.toJson(items.takeLast(MAX_PENDING)))
        }
        prefs.stampOwner(appContext)
    }

    /** 큐를 통째로 비운다. **로그아웃·계정 전환에서 부른다.** */
    fun wipe() {
        prefs.edit { clear() }
    }

    private companion object {
        const val TAG = "TripObservationQueue"
        const val FILE = "jit_observation_queue"
        const val KEY_PENDING = "pending"

        /**
         * 큐 상한. 서버 배치 상한(100)과 맞춘다. 넘치면 오래된 것을 버린다 —
         * 최근 관측이 학습에 더 유용하다.
         */
        const val MAX_PENDING = 100

        val TYPE = object : TypeToken<List<TripObservationInput>>() {}.type
    }
}
