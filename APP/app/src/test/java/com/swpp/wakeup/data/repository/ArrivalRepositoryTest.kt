// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.ObservationBatchRequest
import com.swpp.wakeup.data.remote.ObservationBatchResponse
import com.swpp.wakeup.data.remote.ObservationsApi
import com.swpp.wakeup.data.remote.Paged
import com.swpp.wakeup.data.remote.TripObservationDto
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import com.swpp.wakeup.testing.unexpected
import java.io.IOException
import java.time.OffsetDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

/** 서버가 아는 도착으로 이 기기의 도착을 채운다(todo §2 "앱 재시작 후 도착 판정 복원"). */
class ArrivalRepositoryTest {

    private fun observation(
        event: Long = 7L,
        kind: String = "arrive",
        detector: String? = "gps",
        at: String = "2026-11-02T08:52:00+09:00",
    ): TripObservationDto = fromJson(
        """{"id":1,"event":$event,"kind":"$kind","detector":${detector?.let { "\"$it\"" } ?: "null"},
            "observed_at":"$at"}"""
    )

    private fun millis(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private class Api(private val respond: () -> Response<Paged<TripObservationDto>>) : ObservationsApi {
        val queries = mutableListOf<Triple<Long?, String?, Int>>()
        override suspend fun batch(body: ObservationBatchRequest): Response<ObservationBatchResponse> =
            unexpected("batch")

        override suspend fun list(eventId: Long?, kind: String?, limit: Int): Response<Paged<TripObservationDto>> {
            queries += Triple(eventId, kind, limit)
            return respond()
        }
    }

    @Test
    fun `이 일정의 도착 관측만 옮긴다`() {
        assertEquals(millis("2026-11-02T08:52:00+09:00"), observation().toObserved(7L)?.atMillis)
        assertEquals(true, observation(detector = "manual").toObserved(7L)?.manual)
        assertEquals("옛 관측은 판별기가 없다. GPS 로 본다", false, observation(detector = null).toObserved(7L)?.manual)
        assertNull("다른 일정", observation(event = 8L).toObserved(7L))
        assertNull("출발", observation(kind = "depart").toObserved(7L))
        assertNull("깨진 시각", observation(at = "어제").toObserved(7L))
    }

    @Test
    fun `GPS 도착을 먼저 쓴다`() = runTest {
        val api = Api {
            ok(
                Paged(
                    count = 3,
                    results = listOf(
                        observation(detector = "manual", at = "2026-11-02T08:50:00+09:00"),
                        observation(at = "2026-11-02T08:55:00+09:00"),
                        observation(kind = "depart", at = "2026-11-02T08:10:00+09:00"),
                    ),
                )
            )
        }
        assertEquals(millis("2026-11-02T08:55:00+09:00"), ArrivalRepository(api).serverArrival(7L))
        assertEquals(listOf(Triple<Long?, String?, Int>(7L, "arrive", 20)), api.queries)
    }

    @Test
    fun `없거나 확인하지 못하면 null 이다`() = runTest {
        assertNull(ArrivalRepository(Api { ok(Paged(count = 0, results = emptyList())) }).serverArrival(7L))
        assertNull(ArrivalRepository(Api { httpError(500) }).serverArrival(7L))
        assertNull(ArrivalRepository(Api { throw IOException("지하철") }).serverArrival(7L))
    }
}
