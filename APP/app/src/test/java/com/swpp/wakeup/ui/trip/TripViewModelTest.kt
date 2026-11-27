package com.swpp.wakeup.ui.trip

import com.swpp.wakeup.data.repository.EventRepository.Result
import com.swpp.wakeup.data.repository.TripPlanRepository
import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.ConfidenceView
import com.swpp.wakeup.domain.model.RouteSegment
import com.swpp.wakeup.domain.model.RouteSegments
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripViewModelTest {
    private class FakeRepository : TripPlanRepository {
        override val changes = MutableSharedFlow<TripPlanRepository.Change>(extraBufferCapacity = 10)
        val requestedPlans = mutableListOf<Long>()
        val requestedSegments = mutableListOf<Pair<Long, String>>()
        var planAnswer: suspend (Long) -> Result<AlarmPlanView> = { Result.Failure("offline") }
        var segmentAnswer: suspend (Long, String) -> Result<RouteSegments?> = { _, _ -> Result.Failure("offline") }
        override suspend fun loadPlan(eventId: Long): Result<AlarmPlanView> {
            requestedPlans += eventId
            return planAnswer(eventId)
        }
        override suspend fun baseRouteSegments(eventId: Long, routeKey: String): Result<RouteSegments?> {
            requestedSegments += eventId to routeKey
            return segmentAnswer(eventId, routeKey)
        }
        override fun changed(eventId: Long, plan: AlarmPlanView?) {
            check(changes.tryEmit(TripPlanRepository.Change(eventId, plan)))
        }
    }

    private class FakeCache : TripViewModel.CheckpointCache {
        val saved = mutableMapOf<Pair<Long, String>, TripViewModel.CheckpointCache.Saved>()
        val writes = mutableListOf<Pair<Long, String>>()
        val removed = mutableListOf<Long>()
        override fun get(eventId: Long, routeKey: String) = saved[eventId to routeKey]
        override fun save(eventId: Long, routeKey: String, segments: RouteSegments) {
            writes += eventId to routeKey
            saved[eventId to routeKey] = TripViewModel.CheckpointCache.Saved(segments, 999L)
        }
        override fun remove(eventId: Long) {
            removed += eventId
            saved.keys.removeAll { it.first == eventId }
        }
    }

    private fun plan(id: Long, title: String = "Class") = AlarmPlanView(
        eventId = id, whenLabel = "Tomorrow", dateLabel = "11/28", eventTitle = title,
        eventPlace = "Campus", sensitivityTag = null, alarmAt = "7:00", meridiem = "AM",
        remaining = null, onTimeProbability = null, tauUsed = .9,
        confidence = ConfidenceView(null, "No observations"), breakdown = emptyList(),
        totalMinutes = 50, arrivalLine = null, status = "ok", statusLabel = null,
    )

    private val segments = RouteSegments(listOf(RouteSegment(RouteSegment.Kind.BUS, 300, "5511")))

    @Test fun `same event and revision is loaded once while version change refreshes`() = runTest {
        val repo = FakeRepository().apply { planAnswer = { Result.Success(plan(it)) } }
        val vm = TripViewModel(repo, FakeCache(), backgroundScope)
        vm.loadPlan(1, 1)
        vm.loadPlan(1, 1)
        runCurrent()
        vm.loadPlan(1, 1)
        assertEquals(listOf(1L), repo.requestedPlans)
        vm.loadPlan(1, 2)
        assertEquals(plan(1), vm.plan.value.plan)
        assertTrue(vm.plan.value.loading)
        runCurrent()
        assertEquals(listOf(1L, 1L), repo.requestedPlans)
        assertFalse(vm.plan.value.failed)
    }

    @Test fun `failed refresh retains plan but first failure asks retry`() = runTest {
        val repo = FakeRepository()
        val vm = TripViewModel(repo, FakeCache(), backgroundScope)
        vm.loadPlan(1, 1)
        runCurrent()
        assertTrue(vm.plan.value.failed)
        repo.planAnswer = { Result.Success(plan(it)) }
        vm.loadPlan(1, 1)
        runCurrent()
        repo.planAnswer = { Result.Failure("offline") }
        vm.loadPlan(1, 2)
        runCurrent()
        assertEquals(plan(1), vm.plan.value.plan)
        assertFalse(vm.plan.value.failed)
        assertFalse(vm.plan.value.loading)
    }

    @Test fun `forced same revision request rejects late old response`() = runTest {
        val old = CompletableDeferred<Result<AlarmPlanView>>()
        val repo = FakeRepository().apply {
            planAnswer = { withContext(NonCancellable) { old.await() } }
        }
        val vm = TripViewModel(repo, FakeCache(), backgroundScope)
        vm.loadPlan(1, 1)
        runCurrent()
        repo.planAnswer = { Result.Success(plan(it, "New route")) }
        vm.loadPlan(1, 1, force = true)
        runCurrent()
        old.complete(Result.Success(plan(1, "Old route")))
        runCurrent()
        assertEquals("New route", vm.plan.value.plan?.eventTitle)
    }

    @Test fun `clearing selected event rejects a noncancellable old response`() = runTest {
        val response = CompletableDeferred<Result<AlarmPlanView>>()
        val repo = FakeRepository().apply { planAnswer = { withContext(NonCancellable) { response.await() } } }
        val vm = TripViewModel(repo, FakeCache(), backgroundScope)
        vm.loadPlan(1, 1)
        runCurrent()
        vm.loadPlan(null, null)
        response.complete(Result.Success(plan(1)))
        runCurrent()
        assertNull(vm.plan.value.eventId)
        assertNull(vm.plan.value.plan)
    }

    @Test fun `cached stops remain visible with age after offline failure and retry is explicit`() = runTest {
        val repo = FakeRepository()
        val cache = FakeCache().apply {
            saved[1L to "transit:bus"] = TripViewModel.CheckpointCache.Saved(segments, 123L)
        }
        val vm = TripViewModel(repo, cache, backgroundScope)
        vm.loadCheckpoints(1, "transit:bus")
        assertEquals(segments, vm.checkpoints.value?.segments)
        runCurrent()
        assertEquals(123L, vm.checkpoints.value?.savedAtMillis)
        assertEquals(false, vm.checkpoints.value?.failed)
        vm.loadCheckpoints(1, "transit:bus")
        runCurrent()
        assertEquals(1, repo.requestedSegments.size)
        repo.segmentAnswer = { _, _ -> Result.Success(segments) }
        vm.retryCheckpoints()
        vm.retryCheckpoints()
        runCurrent()
        assertEquals(2, repo.requestedSegments.size)
        assertEquals(listOf(1L to "transit:bus"), cache.writes)
        assertNull(vm.checkpoints.value?.savedAtMillis)
    }

    @Test fun `no cache failure retries and walking has no stops request`() = runTest {
        val repo = FakeRepository()
        val vm = TripViewModel(repo, FakeCache(), backgroundScope)
        vm.loadCheckpoints(1, null)
        vm.loadCheckpoints(1, "walk:short")
        runCurrent()
        assertTrue(repo.requestedSegments.isEmpty())
        vm.loadCheckpoints(1, "transit:bus")
        runCurrent()
        assertEquals(true, vm.checkpoints.value?.failed)
        vm.retryCheckpoints()
        runCurrent()
        assertEquals(2, repo.requestedSegments.size)
    }

    @Test fun `fresh response confirming missing route removes stale offline stops`() = runTest {
        val repo = FakeRepository().apply { segmentAnswer = { _, _ -> Result.Success(null) } }
        val cache = FakeCache().apply {
            saved[1L to "transit:bus"] = TripViewModel.CheckpointCache.Saved(segments, 123L)
        }
        val vm = TripViewModel(repo, cache, backgroundScope)
        vm.loadCheckpoints(1, "transit:bus")
        runCurrent()
        assertNull(vm.checkpoints.value?.segments)
        assertFalse(vm.checkpoints.value!!.failed)
        assertEquals(listOf(1L), cache.removed)
        assertTrue(cache.saved.isEmpty())
    }

    @Test fun `route change updates selected plan invalidates stops and ignores late cache write`() = runTest {
        val old = CompletableDeferred<Result<RouteSegments?>>()
        val repo = FakeRepository().apply {
            planAnswer = { Result.Success(plan(it)) }
            segmentAnswer = { _, _ -> withContext(NonCancellable) { old.await() } }
        }
        val cache = FakeCache()
        val vm = TripViewModel(repo, cache, backgroundScope)
        vm.loadPlan(1, 1)
        vm.loadCheckpoints(1, "transit:bus")
        runCurrent()
        repo.changed(1, plan(1, "New route"))
        runCurrent()
        old.complete(Result.Success(segments))
        runCurrent()
        assertEquals("New route", vm.plan.value.plan?.eventTitle)
        assertNull(vm.checkpoints.value)
        assertTrue(cache.writes.isEmpty())
        assertEquals(listOf(1L), cache.removed)
    }

    @Test fun `change without returned plan reloads selected event only`() = runTest {
        val repo = FakeRepository().apply { planAnswer = { Result.Success(plan(it)) } }
        val vm = TripViewModel(repo, FakeCache(), backgroundScope)
        vm.loadPlan(1, 1)
        runCurrent()
        repo.changed(2, null)
        runCurrent()
        assertEquals(listOf(1L), repo.requestedPlans)
        repo.changed(1, null)
        runCurrent()
        assertEquals(listOf(1L, 1L), repo.requestedPlans)
    }

    @Test fun `logout clears state and prevents old account requests and changes`() = runTest {
        val repo = FakeRepository().apply { planAnswer = { Result.Success(plan(it)) } }
        val cache = FakeCache()
        val vm = TripViewModel(repo, cache, backgroundScope)
        vm.loadPlan(1, 1)
        runCurrent()
        vm.close()
        vm.loadPlan(2, 1)
        vm.loadCheckpoints(2, "transit:bus")
        repo.changed(1, plan(1))
        runCurrent()
        assertEquals(listOf(1L), repo.requestedPlans)
        assertNull(vm.plan.value.plan)
        assertNull(vm.checkpoints.value)
        assertTrue(cache.removed.isEmpty())
    }

    @Test fun `logout rejects in flight responses even if transport ignores cancellation`() = runTest {
        val pendingPlan = CompletableDeferred<Result<AlarmPlanView>>()
        val pendingStops = CompletableDeferred<Result<RouteSegments?>>()
        val repo = FakeRepository().apply {
            planAnswer = { withContext(NonCancellable) { pendingPlan.await() } }
            segmentAnswer = { _, _ -> withContext(NonCancellable) { pendingStops.await() } }
        }
        val cache = FakeCache()
        val vm = TripViewModel(repo, cache, backgroundScope)
        vm.loadPlan(1, 1)
        vm.loadCheckpoints(1, "transit:bus")
        runCurrent()
        vm.close()
        pendingPlan.complete(Result.Success(plan(1)))
        pendingStops.complete(Result.Success(segments))
        runCurrent()
        assertNull(vm.plan.value.plan)
        assertNull(vm.checkpoints.value)
        assertTrue(cache.writes.isEmpty())
    }
}
