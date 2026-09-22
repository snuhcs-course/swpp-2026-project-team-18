package com.swpp.wakeup.sensing

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.swpp.wakeup.background.JitWork
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.BlockObservationBatchRequest
import com.swpp.wakeup.data.remote.BlockObservationInput
import com.swpp.wakeup.data.remote.RoutinesApi
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * 블록 관측 업로드 큐.
 *
 * ## 왜 이것이 필요한가
 *
 * **준비 시간 학습의 유일한 재료다.** 신고 범위("샤워 12~18분")는 분포의
 * 사전값일 뿐이고, 실제 소요가 쌓여야 평균이 이동한다. 이 큐가 없으면
 * `confidence_basis` 가 영원히 `declared_range` 에 머물고, 리포트의
 * `prep_over` 는 전부 "측정 안 됨" 이 된다.
 *
 * ## 왜 큐인가
 *
 * 기록은 아침 7시에 일어난다. 그때 네트워크가 없을 수 있고, 있어도 사용자는
 * 씻으러 가서 앱이 백그라운드로 내려간다. 업로드보다 **디스크 기록이 먼저**다.
 *
 * 멱등성은 서버가 보장한다 — 같은 `client_uuid` 는 무시된다. UUID 는 기록
 * 시점에 만들어 큐에 함께 저장한다. **재전송할 때 새로 만들면 멱등성이 깨진다.**
 *
 * 구조는 [TripObservationQueue] 와 같다. 합치지 않은 이유는 DTO 와 엔드포인트가
 * 다르고, 하나를 일반화하면 "어느 큐가 막혔나" 를 로그에서 구분할 수 없어지기
 * 때문이다.
 */
class BlockObservationQueue(
    context: Context,
    private val api: RoutinesApi = ApiClient.routines,
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    val pendingCount: Int get() = pending().size

    fun pending(): List<BlockObservationInput> {
        val raw = prefs.getString(KEY_PENDING, null) ?: return emptyList()
        return try {
            gson.fromJson<List<BlockObservationInput>>(raw, TYPE).orEmpty()
        } catch (e: JsonSyntaxException) {
            Log.w(TAG, "큐를 읽을 수 없어 버린다", e)
            prefs.edit { remove(KEY_PENDING) }
            emptyList()
        }
    }

    /**
     * 블록을 마쳤을 때 호출한다. 업로드보다 저장이 먼저다.
     *
     * 저장 뒤 업로드 작업을 예약한다 — 지금 네트워크가 없어도 연결되는 순간
     * 올라간다. 아침에 기록하고 저녁에 앱을 열지 않는 사용자의 데이터가
     * 사라지지 않게 하려는 것이다.
     */
    fun enqueue(observation: BlockObservationInput) {
        val next = pending().filterNot { it.clientUuid == observation.clientUuid } +
            observation
        write(next)
        Log.i(TAG, "블록 관측 적재: block=${observation.block} (대기 ${next.size}건)")
        JitWork.requestObservationUpload(appContext)
    }

    /**
     * 쌓인 관측을 올린다.
     *
     * 성공하면 큐를 비운다. **중복도 성공으로 본다** — 서버에 이미 있다는
     * 확인이므로 계속 들고 있을 이유가 없다.
     *
     * 400·404 는 재시도해도 같다(형식이 틀렸거나 블록이 지워졌다). 그 건은
     * 버린다. 안 버리면 큐가 영구히 막혀 뒤의 정상 관측도 못 올린다.
     *
     * @return 서버가 처리한 건수. 올릴 것이 없으면 0.
     */
    suspend fun flush(): Int {
        if (!ApiClient.isReady) return 0

        val items = pending()
        if (items.isEmpty()) return 0

        return try {
            val response = api.uploadObservations(
                BlockObservationBatchRequest(items.take(BlockObservationBatchRequest.MAX_ITEMS))
            )
            when {
                response.isSuccessful -> {
                    val body = response.body()
                    val handled = (body?.accepted ?: 0) + (body?.duplicated ?: 0)
                    write(emptyList())
                    Log.i(
                        TAG,
                        "블록 관측 업로드 완료: 신규 ${body?.accepted ?: 0}건 " +
                            "중복 ${body?.duplicated ?: 0}건",
                    )
                    handled
                }

                response.code() == 400 || response.code() == 404 -> {
                    Log.w(
                        TAG,
                        "서버가 블록 관측 ${items.size}건을 거부했다(${response.code()}). 버린다: " +
                            ApiClient.parseErrorMessage(response.errorBody()?.string()),
                    )
                    write(emptyList())
                    0
                }

                else -> {
                    // 401·5xx 는 일시적일 수 있다. 들고 있는다.
                    Log.w(TAG, "블록 관측 업로드 실패(${response.code()}). 큐에 남긴다")
                    0
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "네트워크가 없어 블록 관측 ${items.size}건을 큐에 남긴다")
            0
        } catch (e: CancellationException) {
            Log.i(TAG, "업로드가 취소됐다. 블록 관측 ${items.size}건은 큐에 남아 재전송된다")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "블록 관측 업로드 중 예상하지 못한 실패. 큐에 남긴다", e)
            0
        }
    }

    private fun write(items: List<BlockObservationInput>) {
        prefs.edit {
            if (items.isEmpty()) remove(KEY_PENDING)
            else putString(KEY_PENDING, gson.toJson(items.takeLast(MAX_PENDING)))
        }
    }

    private companion object {
        const val TAG = "BlockObservationQueue"
        const val FILE = "jit_block_observation_queue"
        const val KEY_PENDING = "pending"

        /**
         * 큐 상한. 서버 배치 상한(200)과 맞춘다. 넘치면 오래된 것을 버린다 —
         * 최근 관측이 학습에 더 유용하다.
         */
        const val MAX_PENDING = 200

        val TYPE = object : TypeToken<List<BlockObservationInput>>() {}.type
    }
}
