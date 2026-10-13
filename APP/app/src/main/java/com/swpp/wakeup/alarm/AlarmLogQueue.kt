package com.swpp.wakeup.alarm

// Partly AI-generated (Claude)

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.swpp.wakeup.background.JitWork
import com.swpp.wakeup.data.local.discardIfForeign
import com.swpp.wakeup.data.local.stampOwner
import com.swpp.wakeup.data.remote.AlarmLogBatchRequest
import com.swpp.wakeup.data.remote.AlarmLogInput
import com.swpp.wakeup.data.remote.AlarmLogsApi
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.sensing.isoAt
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * 알람 기록 업로드 큐(db-schema.md A3). [com.swpp.wakeup.sensing.TripObservationQueue] 와
 * 같은 방식이다.
 *
 * 알람은 네트워크와 무관하게 울린다. 울린 순간 업로드가 실패하면 그 사건은 사라지므로
 * 일어난 즉시 디스크에 적고, 올라가면 지운다. 멱등성은 서버가 보장한다 — 같은
 * `client_uuid` 는 저장하지 않는다. 키는 사건이 일어날 때 만들어 함께 적는다.
 *
 * 기록이 실패해도 알람은 그대로 동작해야 한다. 그래서 적는 쪽([record])은 예외를 밖으로
 * 내지 않는다 — 이 큐는 학습 재료이고, 알람은 사용자가 지금 기다리는 것이다.
 */
class AlarmLogQueue(
    context: Context,
    private val api: () -> AlarmLogsApi = { ApiClient.alarmLogs },
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    val pendingCount: Int get() = pending().size

    fun pending(): List<AlarmLogInput> {
        // 앞 사용자의 기록을 지금 계정의 토큰으로 올리지 않는다([com.swpp.wakeup.data.local.isForeignOwner]).
        if (prefs.discardIfForeign(appContext)) return emptyList()

        val raw = prefs.getString(KEY_PENDING, null) ?: return emptyList()
        return try {
            gson.fromJson<List<AlarmLogInput>>(raw, TYPE).orEmpty()
        } catch (e: JsonSyntaxException) {
            Log.w(TAG, "큐를 읽을 수 없어 버린다", e)
            prefs.edit { remove(KEY_PENDING) }
            emptyList()
        }
    }

    /** 사건 하나를 적는다. 알람 동작을 막지 않게 실패는 삼킨다. */
    fun record(
        kind: String,
        schedule: AlarmSchedule,
        snoozeMinutes: Int? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        recordAll(listOf(alarmLogOf(kind, schedule, snoozeMinutes, nowMillis)))
    }

    /** 여러 건을 한 번에 적는다(등록 동기화). 비어 있으면 아무것도 하지 않는다. */
    fun recordAll(logs: List<AlarmLogInput>) {
        if (logs.isEmpty()) return
        runCatching {
            val keys = logs.map { it.clientUuid }.toSet()
            val next = pending().filterNot { it.clientUuid in keys } + logs
            write(next)
            Log.i(TAG, "알람 기록 적재: ${logs.joinToString { it.kind }} (대기 ${next.size}건)")
            JitWork.requestObservationUpload(appContext)
        }.onFailure { Log.w(TAG, "알람 기록을 적지 못했다. 알람은 그대로 동작한다", it) }
    }

    /**
     * 쌓인 기록을 올린다. 성공(중복 포함)하면 **보낸 것만** 지운다 — 올리는 동안 새로 울린
     * 기록이 있을 수 있다. 400·404 는 다시 보내도 같다(형식 오류·남의 일정·구버전 서버).
     * 그 배치를 버린다. 안 버리면 큐가 영구히 막힌다. 올리기 전에 지운 일정의 기록은
     * 서버가 일정 없이 저장하고 2xx 로 답한다(배치가 버려지지 않는다).
     *
     * @return 서버가 처리한 건수(신규 + 중복). 올릴 것이 없으면 0.
     */
    suspend fun flush(): Int {
        if (!ApiClient.isReady) return 0

        val items = pending()
        if (items.isEmpty()) return 0

        return try {
            val response = api().batch(AlarmLogBatchRequest(items))
            when {
                response.isSuccessful -> {
                    val body = response.body()
                    drop(items)
                    Log.i(
                        TAG,
                        "알람 기록 업로드 완료: 신규 ${body?.accepted ?: 0}건 중복 ${body?.duplicated ?: 0}건",
                    )
                    (body?.accepted ?: 0) + (body?.duplicated ?: 0)
                }

                response.code() == 400 || response.code() == 404 -> {
                    Log.w(TAG, "서버가 알람 기록 ${items.size}건을 거부했다(${response.code()}). 버린다")
                    drop(items)
                    0
                }

                else -> {
                    Log.w(TAG, "알람 기록 업로드 실패(${response.code()}). 큐에 남긴다")
                    0
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "네트워크가 없어 알람 기록 ${items.size}건을 큐에 남긴다")
            0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "알람 기록 업로드 중 예상하지 못한 실패. 큐에 남긴다", e)
            0
        }
    }

    /** 큐를 통째로 비운다. **로그아웃·계정 전환에서 부른다.** */
    fun wipe() {
        prefs.edit { clear() }
    }

    private fun drop(sent: List<AlarmLogInput>) {
        val keys = sent.map { it.clientUuid }.toSet()
        write(pending().filterNot { it.clientUuid in keys })
    }

    private fun write(items: List<AlarmLogInput>) {
        prefs.edit {
            if (items.isEmpty()) remove(KEY_PENDING)
            else putString(KEY_PENDING, gson.toJson(items.takeLast(MAX_PENDING)))
        }
        prefs.stampOwner(appContext)
    }

    private companion object {
        const val TAG = "AlarmLogQueue"
        const val FILE = "jit_alarm_log_queue"
        const val KEY_PENDING = "pending"

        /** 서버 배치 상한(100)과 맞춘다. 넘치면 오래된 것을 버린다. */
        const val MAX_PENDING = 100

        val TYPE = object : TypeToken<List<AlarmLogInput>>() {}.type
    }
}

/**
 * 알람 사건 → 업로드할 기록 한 건. 값만 다뤄 JVM 테스트로 확인한다.
 *
 * `alarm_at` 은 **그때 기기에 등록돼 있던** 시각이다. 미룬 뒤 다시 울린 것은 미룬 시각이다.
 * 미룬 분은 미루기에만 붙인다 — 다른 종류에 붙으면 서버가 그 배치를 400 으로 막는다.
 */
internal fun alarmLogOf(
    kind: String,
    schedule: AlarmSchedule,
    snoozeMinutes: Int? = null,
    nowMillis: Long = System.currentTimeMillis(),
    clientUuid: String = UUID.randomUUID().toString(),
): AlarmLogInput = AlarmLogInput(
    event = schedule.eventId,
    kind = kind,
    occurredAt = isoAt(nowMillis),
    alarmAt = schedule.alarmAtMillis.takeIf { it > 0 }?.let { isoAt(it) },
    snoozeMinutes = snoozeMinutes?.takeIf { kind == AlarmLogInput.KIND_SNOOZED },
    clientUuid = clientUuid,
)

/**
 * 등록 시각이 새로 생기거나 바뀐 일정(db-schema.md A3 `scheduled`). 같은 시각으로 다시
 * 등록한 것은 뺀다 — 동기화는 매번 전부 다시 등록하므로 그대로 두면 같은 사실이 쌓인다.
 */
internal fun changedRegistrations(
    before: List<AlarmSchedule>,
    after: List<AlarmSchedule>,
): List<AlarmSchedule> {
    val previous = before.associate { it.eventId to it.alarmAtMillis }
    return after.filter { previous[it.eventId] != it.alarmAtMillis }
}
