package com.swpp.wakeup.sensing

// Partly AI-generated (Claude)

import com.swpp.wakeup.data.remote.BlockObservationBatchRequest
import com.swpp.wakeup.data.remote.BlockObservationBatchResponse
import com.swpp.wakeup.data.remote.BlockObservationInput
import com.swpp.wakeup.data.remote.ObservationBatchRequest
import com.swpp.wakeup.data.remote.ObservationBatchResponse
import com.swpp.wakeup.data.remote.ObservationsApi
import com.swpp.wakeup.data.remote.Paged
import com.swpp.wakeup.data.remote.TripObservationDto
import com.swpp.wakeup.data.remote.TripObservationInput
import com.swpp.wakeup.testing.FakeRoutinesApi
import com.swpp.wakeup.testing.InMemoryPrefs
import com.swpp.wakeup.testing.errorJson
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import com.swpp.wakeup.testing.prefsContext
import com.swpp.wakeup.testing.unexpected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * 이동 관측 큐와 준비 블록 관측 큐. 지하철 안에서 판정해도 관측이 사라지지 않게
 * 디스크에 먼저 적고, 서버가 받으면(중복 포함) 비운다. 400·404 는 버려 큐가 막히지 않게 한다.
 */
class ObservationQueuesTest {

    private val prefs = InMemoryPrefs()
    private val context = prefsContext(prefs)

    // --- 이동 관측 ------------------------------------------------------------

    private var tripSent = mutableListOf<ObservationBatchRequest>()

    private fun tripApi(respond: suspend () -> Response<ObservationBatchResponse>) = object : ObservationsApi {
        override suspend fun batch(body: ObservationBatchRequest): Response<ObservationBatchResponse> {
            tripSent += body
            return respond()
        }

        override suspend fun list(eventId: Long?, kind: String?, limit: Int): Response<Paged<TripObservationDto>> =
            unexpected("list")
    }

    private fun tripQueue(
        ready: Boolean = true,
        respond: suspend () -> Response<ObservationBatchResponse> = { ok(ObservationBatchResponse()) },
    ) = TripObservationQueue(context, tripApi(respond), isReady = { ready })

    private fun trip(uuid: String, observedAt: String = "2026-11-20T08:00:00+09:00") = TripObservationInput(
        event = 1,
        kind = TripObservationInput.KIND_DEPART,
        observedAt = observedAt,
        lat = 37.0,
        lng = 127.0,
        accuracyM = 10.0,
        distanceM = 120.0,
        clientUuid = uuid,
    )

    @Test
    fun `이동 관측은 적재 즉시 디스크에 남고 같은 키는 하나로 합친다`() {
        val q = tripQueue()
        q.enqueue(trip("a"))
        q.enqueue(trip("b"))
        q.enqueue(trip("a"))

        assertEquals(listOf("b", "a"), TripObservationQueue(context, tripApi { error("x") }).pending().map { it.clientUuid })
        assertEquals(2, q.pendingCount)
    }

    @Test
    fun `이동 관측 큐는 최근 100건까지만 둔다`() {
        val q = tripQueue()
        repeat(102) { q.enqueue(trip("u$it")) }
        assertEquals(100, q.pendingCount)
        assertEquals("u2", q.pending().first().clientUuid)
    }

    @Test
    fun `깨진 이동 관측 큐는 버린다`() {
        prefs.edit().putString("pending", "]]").apply()
        assertTrue(tripQueue().pending().isEmpty())
        assertNull(prefs.getString("pending", null))
    }

    @Test
    fun `이전 앱의 큐 좌표는 디스크와 재전송에서 제거하고 판정 결과는 보존한다`() = runBlocking {
        prefs.edit().putString("pending", """[{"event":1,"kind":"depart","detector":"gps","observed_at":"2026-11-20T08:00:00+09:00","lat":37.4,"lng":126.9,"accuracy_m":10.0,"distance_m":120.0,"client_uuid":"old"}]""").apply()
        val q = tripQueue { ok(ObservationBatchResponse(accepted = 1)) }
        val pending = q.pending().single()
        assertNull(pending.lat)
        assertNull(pending.lng)
        val disk = com.google.gson.JsonParser.parseString(prefs.getString("pending", null)).asJsonArray.single().asJsonObject
        assertTrue(!disk.has("lat") && !disk.has("lng"))
        assertEquals("old", disk["client_uuid"].asString)
        assertEquals(1, q.flush())
        val sent = tripSent.single().observations.single()
        assertNull(sent.lat)
        assertNull(sent.lng)
        assertEquals(120.0, sent.distanceM!!, 0.0)
        assertEquals(10.0, sent.accuracyM!!, 0.0)
    }

    @Test
    fun `이동 관측 - 준비 전이거나 비어 있으면 올리지 않는다`() = runBlocking {
        assertEquals(0, tripQueue().flush())
        val notReady = tripQueue(ready = false)
        notReady.enqueue(trip("a"))
        assertEquals(0, notReady.flush())
        assertTrue(tripSent.isEmpty())
    }

    @Test
    fun `이동 관측 - 성공하면 신규와 중복을 합쳐 돌려주고 비운다`() = runBlocking {
        val q = tripQueue { ok(ObservationBatchResponse(accepted = 1, duplicated = 1)) }
        q.enqueue(trip("a"))
        q.enqueue(trip("b"))

        assertEquals(2, q.flush())
        assertEquals(0, q.pendingCount)
    }

    @Test
    fun `이동 관측 - 400 404 는 버리고 그 밖의 실패는 남긴다`() = runBlocking {
        val rejected = tripQueue { httpError(400, errorJson("오차가 너무 커요")) }
        rejected.enqueue(trip("a"))
        assertEquals(0, rejected.flush())
        assertEquals(0, rejected.pendingCount)

        val gone = tripQueue { httpError(404) }
        gone.enqueue(trip("a"))
        assertEquals(0, gone.flush())
        assertEquals(0, gone.pendingCount)

        val down = tripQueue { httpError(503) }
        down.enqueue(trip("a"))
        assertEquals(0, down.flush())
        assertEquals(1, down.pendingCount)

        assertEquals(0, tripQueue { throw IOException("offline") }.flush())
        assertEquals(0, tripQueue { throw IllegalStateException("boom") }.flush())
        assertEquals(1, tripQueue().pendingCount)
    }

    @Test
    fun `이동 관측 - 취소는 다시 던지고 큐에 남긴다`() = runBlocking {
        val q = tripQueue { throw CancellationException("stop") }
        q.enqueue(trip("a"))
        try {
            q.flush()
            fail("취소가 삼켜졌다")
        } catch (e: CancellationException) {
            assertEquals(1, q.pendingCount)
        }
    }

    @Test
    fun `이동 관측 - 초기화 이전 것만 지우고 wipe 는 비운다`() {
        val q = tripQueue()
        q.enqueue(trip("old", "2026-11-01T08:00:00+09:00"))
        q.enqueue(trip("new", "2026-11-20T08:00:00+09:00"))

        assertEquals(1, q.dropBefore("2026-11-10T00:00:00+09:00"))
        assertEquals(listOf("new"), q.pending().map { it.clientUuid })
        assertEquals(0, q.dropBefore("2026-11-10T00:00:00+09:00"))

        q.wipe()
        assertEquals(0, q.pendingCount)
    }

    @Test
    fun `이동 업로드 성공과 영구 거절 모두 보내는 동안 새로 쌓인 관측을 보존한다`() = runBlocking {
        for (code in listOf(200, 400, 404)) {
            lateinit var queue: TripObservationQueue
            queue = tripQueue {
                queue.enqueue(trip("late-$code"))
                if (code == 200) ok(ObservationBatchResponse(accepted = 1)) else httpError(code)
            }
            queue.enqueue(trip("sent-$code"))
            queue.flush()
            assertEquals(listOf("late-$code"), queue.pending().map { it.clientUuid })
            queue.wipe()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `별도 큐 인스턴스의 flush는 진행 중인 업로드를 기다리고 새 관측만 이어 보낸다`() = runTest {
        val release = CompletableDeferred<Unit>()
        val first = tripQueue { release.await(); ok(ObservationBatchResponse(accepted = 1)) }
        val second = tripQueue { ok(ObservationBatchResponse(accepted = 1)) }
        first.enqueue(trip("first"))
        val firstUpload = async { first.flush() }
        runCurrent()
        val secondUpload = async { second.flush() }
        runCurrent()
        assertEquals(1, tripSent.size)
        second.enqueue(trip("during-upload"))
        release.complete(Unit)
        runCurrent()
        assertEquals(1, firstUpload.await())
        assertEquals(1, secondUpload.await())
        assertEquals(listOf(listOf("first"), listOf("during-upload")), tripSent.map { it.observations.map { row -> row.clientUuid } })
        assertEquals(0, second.pendingCount)
    }

    @Test
    fun `옛 계정 업로드 응답은 새 계정의 동일 UUID 관측을 지우지 않는다`() = runBlocking {
        prefs.edit().putString("email", "old@example.test").apply()
        lateinit var queue: TripObservationQueue
        queue = tripQueue {
            prefs.edit().clear().putString("email", "new@example.test").apply()
            queue.enqueue(trip("shared-key").copy(event = 2))
            ok(ObservationBatchResponse(accepted = 1))
        }
        queue.enqueue(trip("shared-key"))
        assertEquals(0, queue.flush())
        assertEquals(2L, queue.pending().single().event)
    }

    // --- 블록 관측 ------------------------------------------------------------

    private var blockSent = mutableListOf<BlockObservationBatchRequest>()

    private fun blockQueue(
        ready: Boolean = true,
        respond: suspend () -> Response<BlockObservationBatchResponse> = { ok(BlockObservationBatchResponse()) },
    ) = BlockObservationQueue(
        context,
        object : FakeRoutinesApi() {
            override suspend fun uploadObservations(
                body: BlockObservationBatchRequest,
            ): Response<BlockObservationBatchResponse> {
                blockSent += body
                return respond()
            }
        },
        isReady = { ready },
    )

    private fun block(uuid: String, recordedAt: String = "2026-11-20T07:30:00+09:00") = BlockObservationInput(
        block = 3,
        event = 1,
        observedOn = "2026-11-20",
        durationMinutes = 12.0,
        clientUuid = uuid,
        clientRecordedAt = recordedAt,
    )

    @Test
    fun `블록 업로드가 끝날 때 보낸 UUID만 지우고 새로 마친 항목은 남긴다`() = runBlocking {
        lateinit var queue: BlockObservationQueue
        queue = blockQueue {
            queue.enqueue(block("newly-completed"))
            ok(BlockObservationBatchResponse(accepted = 1))
        }
        queue.enqueue(block("sent"))
        assertEquals(1, queue.flush())
        assertEquals(listOf("newly-completed"), queue.pending().map { it.clientUuid })
    }

    @Test
    fun `블록 관측은 적재하고 같은 키는 하나로 합친다`() {
        val q = blockQueue()
        q.enqueue(block("a"))
        q.enqueue(block("a"))
        q.enqueue(block("b"))
        assertEquals(listOf("a", "b"), q.pending().map { it.clientUuid })
    }

    @Test
    fun `블록 관측 큐는 최근 200건까지만 둔다`() {
        val q = blockQueue()
        repeat(203) { q.enqueue(block("u$it")) }
        assertEquals(200, q.pendingCount)
    }

    @Test
    fun `깨진 블록 관측 큐는 버린다`() {
        prefs.edit().putString("pending", "{").apply()
        assertTrue(blockQueue().pending().isEmpty())
    }

    @Test
    fun `블록 관측 - 준비 전이거나 비어 있으면 올리지 않는다`() = runBlocking {
        assertEquals(0, blockQueue().flush())
        val q = blockQueue(ready = false)
        q.enqueue(block("a"))
        assertEquals(0, q.flush())
        assertTrue(blockSent.isEmpty())
    }

    @Test
    fun `블록 관측 - 성공하면 비우고 받은 건수를 돌려준다`() = runBlocking {
        val q = blockQueue { ok(BlockObservationBatchResponse(accepted = 2, duplicated = 0)) }
        q.enqueue(block("a"))
        q.enqueue(block("b"))

        assertEquals(2, q.flush())
        assertEquals(0, q.pendingCount)
        assertEquals(2, blockSent.single().observations.size)
    }

    @Test
    fun `블록 관측 - 400 404 는 버리고 그 밖의 실패는 남긴다`() = runBlocking {
        for (code in listOf(400, 404)) {
            val q = blockQueue { httpError(code) }
            q.enqueue(block("a"))
            assertEquals(0, q.flush())
            assertEquals(0, q.pendingCount)
        }
        val down = blockQueue { httpError(500) }
        down.enqueue(block("a"))
        assertEquals(0, down.flush())
        assertEquals(1, down.pendingCount)

        assertEquals(0, blockQueue { throw IOException("offline") }.flush())
        assertEquals(0, blockQueue { throw IllegalStateException("boom") }.flush())
        assertEquals(1, blockQueue().pendingCount)
    }

    @Test
    fun `블록 관측 - 취소는 다시 던진다`() = runBlocking {
        val q = blockQueue { throw CancellationException("stop") }
        q.enqueue(block("a"))
        try {
            q.flush()
            fail("취소가 삼켜졌다")
        } catch (e: CancellationException) {
            assertEquals(1, q.pendingCount)
        }
    }

    @Test
    fun `블록 관측 - 초기화 이전 것만 지우고 wipe 는 비운다`() {
        val q = blockQueue()
        q.enqueue(block("old", "2026-11-01T07:30:00+09:00"))
        q.enqueue(block("new", "2026-11-20T07:30:00+09:00"))

        assertEquals(1, q.dropBefore("2026-11-10T00:00:00+09:00"))
        assertEquals(0, q.dropBefore("2026-11-10T00:00:00+09:00"))
        q.wipe()
        assertEquals(0, q.pendingCount)
    }
}
