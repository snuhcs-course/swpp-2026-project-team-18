package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.RiskOptionDto
import com.swpp.wakeup.data.remote.RiskOptionsDto
import com.swpp.wakeup.data.repository.EventRepository.Result
import com.swpp.wakeup.testing.FakeEventsApi
import com.swpp.wakeup.testing.FakeProfileApi
import com.swpp.wakeup.testing.errorJson
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import com.swpp.wakeup.ui.alarm.AlarmDecisionViewModel
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** A failed detail GET must not certify an offline alarm as safe to edit. */
@OptIn(ExperimentalCoroutinesApi::class)
class EventAlarmDecisionRepositoryTest {
    private fun event(start: String, alarm: String): EventDto = fromJson(
        """{"id":1,"title":"Fixture event","start_at":"$start","source":"manual",
            "place":{"id":1,"name":"Fixture place","address":"","lat":37.46,"lng":126.95},
            "alarm_enabled":true,"alarm_on":true,"alarm_plan":{"status":"ok","alarm_at":"$alarm",
            "effective_alarm_at":"$alarm","prep_minutes":30,"travel_minutes":32,"tau_used":0.9}}"""
    )

    private val old = event("2030-11-29T09:00:00+09:00", "2030-11-29T07:53:00+09:00")
    private val fresh = event("2030-11-29T10:00:00+09:00", "2030-11-29T08:53:00+09:00")
    private fun millis(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test fun `detail reads retain their default offline fallback`() = runTest {
        var cacheReads = 0
        val api = object : FakeEventsApi() {
            override suspend fun get(id: Long): retrofit2.Response<EventDto> = throw IOException("offline")
        }
        val repository = EventRepository(api, FakeProfileApi(), cachedPlanEvent = {
            assertEquals(1L, it)
            cacheReads++
            old
        })

        val result = repository.loadPlan(1)

        assertTrue(result is Result.Success)
        val plan = (result as Result.Success).data
        assertEquals(millis(old.startAt), plan.startAtMillis)
        assertEquals(millis("2030-11-29T07:53:00+09:00"), plan.alarmAtMillis)
        assertEquals(1, cacheReads)
    }

    @Test fun `strict plan reads preserve network failure without consulting a populated cache`() = runTest {
        var cacheReads = 0
        val api = object : FakeEventsApi() {
            override suspend fun get(id: Long): retrofit2.Response<EventDto> = throw IOException("offline")
        }
        val repository = EventRepository(api, FakeProfileApi(), cachedPlanEvent = { cacheReads++; old })

        val result = repository.loadPlan(1, allowCached = false)

        assertTrue(result is Result.Failure)
        assertTrue((result as Result.Failure).network)
        assertEquals(0, cacheReads)
    }

    @Test fun `the sheet repository requires an online plan even when an offline copy exists`() = runTest {
        var cacheReads = 0
        val api = object : FakeEventsApi() {
            override suspend fun get(id: Long) = httpError<EventDto>(503, errorJson("잠시 후 다시 시도해 주세요"))
        }
        val events = EventRepository(api, FakeProfileApi(), cachedPlanEvent = { cacheReads++; old })

        val result = EventAlarmDecisionRepository(events).plan(1)

        assertTrue(result is Result.Failure)
        assertEquals("잠시 후 다시 시도해 주세요", (result as Result.Failure).message)
        assertEquals(0, cacheReads)
    }

    @Test fun `a strict successful read uses the server start and alarm without an older cache`() = runTest {
        var cacheReads = 0
        val api = object : FakeEventsApi() {
            override suspend fun get(id: Long) = ok(fresh)
        }
        val events = EventRepository(api, FakeProfileApi(), cachedPlanEvent = { cacheReads++; old })

        val result = EventAlarmDecisionRepository(events).plan(1)

        assertTrue(result is Result.Success)
        val plan = (result as Result.Success).data
        assertEquals(millis(fresh.startAt), plan.startAtMillis)
        assertEquals(millis("2030-11-29T08:53:00+09:00"), plan.alarmAtMillis)
        assertEquals(0, cacheReads)
    }

    @Test fun `a server event without an alarm never falls back to an old editable alarm`() = runTest {
        var cacheReads = 0
        val unplanned = fromJson<EventDto>(
            """{"id":1,"title":"Fixture event","start_at":"2030-11-29T10:00:00+09:00",
                "source":"manual","alarm_enabled":true,"alarm_on":false,"alarm_plan":null}"""
        )
        val api = object : FakeEventsApi() {
            override suspend fun get(id: Long) = ok(unplanned)
        }
        val events = EventRepository(api, FakeProfileApi(), cachedPlanEvent = { cacheReads++; old })

        val result = EventAlarmDecisionRepository(events).plan(1)

        assertEquals(Result.Failure("알람을 아직 계산하지 못했어요."), result)
        assertEquals(0, cacheReads)
    }

    @Test fun `fresh options and a failed event GET cannot certify or save a cached sheet plan`() = runTest {
        var cacheReads = 0
        var synchronized = false
        val api = object : FakeEventsApi() {
            override suspend fun get(id: Long): retrofit2.Response<EventDto> = throw IOException("event GET failed")
            override suspend fun riskOptions(id: Long) = ok(RiskOptionsDto(
                options = listOf(RiskOptionDto("normal", "보통", .9, "2030-11-29T08:53:00+09:00")),
                recommendedKey = "normal", selectedKey = "normal",
            ))
        }
        val events = EventRepository(api, FakeProfileApi(), cachedPlanEvent = { cacheReads++; old })
        val cached = requireNotNull(events.planFrom(old))
        val vm = AlarmDecisionViewModel(
            repository = EventAlarmDecisionRepository(events), parentScope = backgroundScope,
            serverVersion = { "0.22.0" }, knownPlan = { cached },
            onSaved = { fail("A failed fresh read must block saving"); null },
            notify = { _, _ -> }, zone = ZoneId.of("Asia/Seoul"),
            onRefreshed = { synchronized = true; null },
        )

        vm.open(1)
        runCurrent()

        assertEquals(cached, vm.state.value.plan)
        assertNotNull(vm.state.value.options)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertFalse(synchronized)
        assertEquals(0, cacheReads)
        // FakeEventsApi throws AssertionError if any write escapes these guards.
        vm.choose(1, "normal")
        vm.custom(1, 8 * 60)
        vm.revert(1)
        runCurrent()
        assertFalse(vm.state.value.saving)
    }
}
