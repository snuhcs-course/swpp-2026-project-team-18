package com.swpp.wakeup.alarm

// Partly AI-generated (Claude)

import com.swpp.wakeup.data.remote.AlarmLogBatchRequest
import com.swpp.wakeup.data.remote.AlarmLogBatchResponse
import com.swpp.wakeup.data.remote.AlarmLogInput
import com.swpp.wakeup.data.remote.AlarmLogsApi
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.testing.InMemoryPrefs
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import com.swpp.wakeup.testing.prefsContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * 알람 기록 업로드 큐. 울린 순간 네트워크가 없어도 기록은 남고, 올라가면 **보낸 것만** 지운다.
 */
class AlarmLogQueueTest {

    private val prefs = InMemoryPrefs()
    private val context = prefsContext(prefs)
    private var sent = mutableListOf<AlarmLogBatchRequest>()

    private fun api(respond: suspend (AlarmLogBatchRequest) -> Response<AlarmLogBatchResponse>) =
        object : AlarmLogsApi {
            override suspend fun batch(body: AlarmLogBatchRequest): Response<AlarmLogBatchResponse> {
                sent += body
                return respond(body)
            }
        }

    private fun queue(
        ready: Boolean = true,
        respond: suspend (AlarmLogBatchRequest) -> Response<AlarmLogBatchResponse> = { ok(AlarmLogBatchResponse()) },
    ) = AlarmLogQueue(context, api = { api(respond) }, isReady = { ready })

    private val schedule = AlarmSchedule(
        eventId = 42,
        alarmAtMillis = 1_000_000,
        departByMillis = null,
        arriveAtMillis = null,
        startAtMillis = 2_000_000,
        alarmLabel = "7:00",
        meridiem = "AM",
        eventLine = "수업",
        placeName = null,
        arrivalLine = null,
    )

    private fun log(uuid: String, occurredAt: String = "2026-11-20T07:00:00+09:00") = AlarmLogInput(
        event = 42,
        kind = AlarmLogInput.KIND_FIRED,
        occurredAt = occurredAt,
        clientUuid = uuid,
    )

    @Test
    fun `기록은 바로 디스크에 남는다`() {
        val q = queue()
        q.record(AlarmLogInput.KIND_FIRED, schedule, nowMillis = 1_000_000)

        assertEquals(1, q.pendingCount)
        assertEquals(AlarmLogInput.KIND_FIRED, AlarmLogQueue(context).pending().single().kind)
    }

    @Test
    fun `같은 client_uuid 는 한 건으로 합친다`() {
        val q = queue()
        q.recordAll(listOf(log("a"), log("b")))
        q.recordAll(listOf(log("a")))

        assertEquals(listOf("b", "a"), q.pending().map { it.clientUuid })
    }

    @Test
    fun `빈 목록은 아무것도 적지 않는다`() {
        queue().recordAll(emptyList())
        assertNull(prefs.getString("pending", null))
    }

    @Test
    fun `큐는 최근 100건까지만 둔다`() {
        val q = queue()
        q.recordAll((1..105).map { log("u$it") })

        val pending = q.pending()
        assertEquals(100, pending.size)
        assertEquals("u6", pending.first().clientUuid)
    }

    @Test
    fun `깨진 큐는 버린다`() {
        prefs.edit().putString("pending", "nope{").apply()
        assertTrue(queue().pending().isEmpty())
        assertNull(prefs.getString("pending", null))
    }

    @Test
    fun `준비 전이면 올리지 않는다`() = runBlocking {
        val q = queue(ready = false)
        q.recordAll(listOf(log("a")))

        assertEquals(0, q.flush())
        assertTrue(sent.isEmpty())
        assertEquals(1, q.pendingCount)
    }

    @Test
    fun `올릴 것이 없으면 0 이고 서버를 부르지 않는다`() = runBlocking {
        assertEquals(0, queue().flush())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `성공하면 신규와 중복을 합쳐 돌려주고 보낸 것을 지운다`() = runBlocking {
        val q = queue { ok(AlarmLogBatchResponse(accepted = 2, duplicated = 1)) }
        q.recordAll(listOf(log("a"), log("b"), log("c")))

        assertEquals(3, q.flush())
        assertEquals(0, q.pendingCount)
        assertEquals(3, sent.single().logs.size)
    }

    @Test
    fun `올리는 동안 새로 적힌 기록은 남긴다`() = runBlocking {
        lateinit var q: AlarmLogQueue
        q = queue {
            q.recordAll(listOf(log("late")))
            ok(AlarmLogBatchResponse(accepted = 1))
        }
        q.recordAll(listOf(log("a")))

        q.flush()

        assertEquals(listOf("late"), q.pending().map { it.clientUuid })
    }

    @Test
    fun `400 과 404 는 그 배치를 버린다`() = runBlocking {
        for (code in listOf(400, 404)) {
            val q = queue { httpError(code) }
            q.recordAll(listOf(log("a$code")))
            assertEquals(0, q.flush())
            assertEquals(0, q.pendingCount)
        }
    }

    @Test
    fun `5xx 와 401 은 큐에 남긴다`() = runBlocking {
        for (code in listOf(401, 500, 503)) {
            val q = queue { httpError(code) }
            q.recordAll(listOf(log("a")))
            assertEquals(0, q.flush())
            assertEquals(1, q.pendingCount)
        }
    }

    @Test
    fun `네트워크 오류와 예상하지 못한 실패는 큐에 남긴다`() = runBlocking {
        val offline = queue { throw IOException("offline") }
        offline.recordAll(listOf(log("a")))
        assertEquals(0, offline.flush())
        assertEquals(1, offline.pendingCount)

        val broken = queue { throw IllegalStateException("boom") }
        assertEquals(0, broken.flush())
        assertEquals(1, broken.pendingCount)
    }

    @Test
    fun `초기화 이전 기록만 지운다`() {
        val q = queue()
        q.recordAll(listOf(log("old", "2026-11-01T07:00:00+09:00"), log("new", "2026-11-20T07:00:00+09:00")))

        assertEquals(1, q.dropBefore("2026-11-10T00:00:00+09:00"))
        assertEquals(listOf("new"), q.pending().map { it.clientUuid })
        assertEquals(0, q.dropBefore("2026-11-10T00:00:00+09:00"))
    }

    @Test
    fun `wipe 는 큐를 비운다`() {
        val q = queue()
        q.recordAll(listOf(log("a")))
        q.wipe()
        assertEquals(0, q.pendingCount)
    }
}
