package com.swpp.wakeup.ui.alarm

import com.swpp.wakeup.data.repository.AlarmDecisionRepository
import com.swpp.wakeup.data.repository.EventRepository.Result
import com.swpp.wakeup.domain.model.*
import com.swpp.wakeup.ui.home.HomeViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class AlarmDecisionViewModelTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val start = ZonedDateTime.of(2026, 11, 28, 9, 0, 0, 0, zone)
    private fun plan(id: Long) = AlarmPlanView(
        eventId = id, whenLabel = "Tomorrow", dateLabel = "11/28", eventTitle = "Class", eventPlace = "Campus",
        sensitivityTag = null, alarmAt = "7:00", meridiem = "AM", remaining = null,
        onTimeProbability = null, tauUsed = .9, confidence = ConfidenceView(null, "No observations"),
        breakdown = emptyList(), totalMinutes = 120, arrivalLine = null, status = "ok", statusLabel = null,
        startAtMillis = start.toInstant().toEpochMilli(), alarmAtMillis = start.minusHours(2).toInstant().toEpochMilli(),
    )
    private val options = RiskOptionsView(
        options = listOf(RiskOptionView("safe", "Safe", "6:50", "AM", 99, null, .987)),
        selectedKey = null, autoAdjusted = false, autoReason = null, distribution = null,
    )

    private inner class Repository : AlarmDecisionRepository {
        val planCalls = mutableListOf<Long>()
        val optionCalls = mutableListOf<Long>()
        val choices = mutableListOf<Pair<Long, Double>>()
        val customs = mutableListOf<Pair<Long, String>>()
        val reverts = mutableListOf<Long>()
        var planAnswer: suspend (Long) -> Result<AlarmPlanView> = { Result.Success(this@AlarmDecisionViewModelTest.plan(it)) }
        var optionAnswer: suspend (Long) -> Result<RiskOptionsView> = { Result.Success(this@AlarmDecisionViewModelTest.options) }
        var saveAnswer: suspend (Long) -> Result<AlarmPlanView> = { Result.Success(this@AlarmDecisionViewModelTest.plan(it)) }
        override suspend fun plan(eventId: Long): Result<AlarmPlanView> {
            planCalls += eventId
            return planAnswer(eventId)
        }
        override suspend fun options(eventId: Long): Result<RiskOptionsView> {
            optionCalls += eventId
            return optionAnswer(eventId)
        }
        override suspend fun choose(eventId: Long, tau: Double): Result<AlarmPlanView> {
            choices += eventId to tau
            return saveAnswer(eventId)
        }
        override suspend fun custom(eventId: Long, alarmAt: String): Result<AlarmPlanView> {
            customs += eventId to alarmAt
            return saveAnswer(eventId)
        }
        override suspend fun revert(eventId: Long): Result<AlarmPlanView> {
            reverts += eventId
            return saveAnswer(eventId)
        }
    }

    private val saved = mutableListOf<AlarmPlanView>()
    private val refreshed = mutableListOf<AlarmPlanView>()
    private val notices = mutableListOf<Pair<String, HomeViewModel.AlarmUndo?>>()
    private var version: String? = "0.22.0"
    private var syncError: String? = null
    private var refreshError: String? = null
    private var known: AlarmPlanView? = null

    private fun TestScope.vm(
        repo: Repository,
        onBeforeRead: suspend () -> String? = { null },
        onRefreshed: suspend (AlarmPlanView) -> String? = { refreshed += it; refreshError },
    ) = AlarmDecisionViewModel(
        repo, backgroundScope, { version }, { known },
        onSaved = { saved += it; syncError }, notify = { text, undo -> notices += text to undo }, zone = zone,
        onRefreshed = onRefreshed,
        onBeforeRead = onBeforeRead,
    )

    @Test fun `known plan is an immediate preview but opening still reads and synchronizes the fresh plan`() = runTest {
        val repo = Repository()
        known = plan(1).copy(alarmAt = "7:30")
        val vm = vm(repo)
        vm.open(1)
        vm.open(1)
        assertEquals(known, vm.state.value.plan)
        assertTrue(vm.state.value.loading)
        runCurrent()
        assertEquals(listOf(1L), repo.planCalls)
        assertEquals(listOf(1L), repo.optionCalls)
        assertEquals(plan(1), vm.state.value.plan)
        assertEquals(listOf(plan(1)), refreshed)
        assertTrue(saved.isEmpty())
        assertEquals(options, vm.state.value.options)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `choose uses server tau saves and syncs before closing and notifying`() = runTest {
        val repo = Repository()
        val vm = vm(repo)
        vm.open(1)
        vm.choose(1, "safe") // Loading is not selectable.
        runCurrent()
        vm.choose(1, "missing")
        vm.choose(2, "safe")
        assertTrue(repo.choices.isEmpty())
        vm.choose(1, "safe")
        vm.choose(1, "safe")
        vm.loadOptions(1)
        assertTrue(vm.state.value.saving)
        runCurrent()
        assertEquals(listOf(1L to .987), repo.choices)
        assertEquals(listOf(plan(1)), saved)
        assertNull(vm.state.value.eventId)
        assertTrue(notices.single().first.contains("6:50"))
    }

    @Test fun `save failure keeps options and exposes retry without syncing`() = runTest {
        val repo = Repository().apply { saveAnswer = { Result.Failure("offline") } }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.choose(1, "safe")
        runCurrent()
        assertEquals("offline", vm.state.value.error)
        assertEquals(options, vm.state.value.options)
        assertEquals(1L, vm.state.value.eventId)
        assertFalse(vm.state.value.saving)
        assertTrue(saved.isEmpty())
        repo.saveAnswer = { Result.Success(plan(it)) }
        vm.choose(1, "safe")
        runCurrent()
        assertNull(vm.state.value.eventId)
    }

    @Test fun `successful save with failed home sync leaves actionable sheet open`() = runTest {
        val repo = Repository()
        syncError = "Saved but home is offline"
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.choose(1, "safe")
        runCurrent()
        assertEquals(1, saved.size)
        assertEquals(syncError, vm.state.value.error)
        assertFalse(vm.state.value.saving)
        assertTrue(notices.isEmpty())
    }

    @Test fun `custom time uses prior day and original alarm for undo`() = runTest {
        val repo = Repository()
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.custom(1, 23 * 60 + 30)
        runCurrent()
        assertEquals(listOf(1L to "2026-11-27T23:30:00+09:00"), repo.customs)
        assertEquals(HomeViewModel.AlarmUndo(1, plan(1).alarmAtMillis!!), notices.single().second)
    }

    @Test fun `custom after start explains next action without a write`() = runTest {
        val repo = Repository()
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.custom(1, 9 * 60)
        assertEquals(UserAlarmRule.AFTER_START_NOTE, notices.single().first)
        assertTrue(repo.customs.isEmpty())
        assertFalse(vm.state.value.saving)
    }

    @Test fun `unsupported server and missing plan prevent custom and revert writes`() = runTest {
        val repo = Repository()
        version = "0.20.0"
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.custom(1, 8 * 60)
        vm.revert(1)
        assertTrue(repo.customs.isEmpty())
        assertTrue(repo.reverts.isEmpty())
        version = "0.22.0"
        repo.planAnswer = { Result.Failure("Plan unavailable") }
        vm.open(2)
        runCurrent()
        vm.custom(2, 8 * 60)
        assertTrue(repo.customs.isEmpty())
    }

    @Test fun `revert restores server recommendation once and closes after sync`() = runTest {
        val repo = Repository()
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.revert(2)
        vm.revert(1)
        vm.revert(1)
        runCurrent()
        assertEquals(listOf(1L), repo.reverts)
        assertEquals(listOf(plan(1)), saved)
        assertNull(vm.state.value.eventId)
        assertEquals("추천 시각으로 되돌렸어요", notices.single().first)
    }

    @Test fun `retry restarts both reads after its upload barrier`() = runTest {
        val pending = CompletableDeferred<Result<AlarmPlanView>>()
        val repo = Repository().apply {
            planAnswer = { pending.await() }
            optionAnswer = { Result.Failure("Try again") }
        }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        assertEquals("Try again", vm.state.value.error)
        repo.optionAnswer = { Result.Success(options) }
        vm.loadOptions(1)
        runCurrent()
        pending.complete(Result.Success(plan(1)))
        runCurrent()
        assertEquals(listOf(1L, 1L), repo.planCalls)
        assertEquals(plan(1), vm.state.value.plan)
        assertNull(vm.state.value.error)
    }

    @Test fun `dismissed lookup cannot repopulate reopened same event`() = runTest {
        val pending = CompletableDeferred<Result<RiskOptionsView>>()
        val repo = Repository().apply { optionAnswer = { withContext(NonCancellable) { pending.await() } } }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.dismiss()
        repo.optionAnswer = { Result.Success(options.copy(selectedKey = "safe")) }
        vm.open(1)
        runCurrent()
        pending.complete(Result.Success(options))
        runCurrent()
        assertEquals("safe", vm.state.value.options?.selectedKey)
    }

    @Test fun `closing sheet during save still syncs saved alarm without stale toast`() = runTest {
        val pending = CompletableDeferred<Result<AlarmPlanView>>()
        val repo = Repository().apply { saveAnswer = { pending.await() } }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.choose(1, "safe")
        runCurrent()
        vm.dismiss()
        pending.complete(Result.Success(plan(1)))
        runCurrent()
        assertEquals(listOf(plan(1)), saved)
        assertTrue(notices.isEmpty())
        assertNull(vm.state.value.eventId)
    }

    @Test fun `logout blocks old save from touching home or new account`() = runTest {
        val pending = CompletableDeferred<Result<AlarmPlanView>>()
        val repo = Repository().apply { saveAnswer = { withContext(NonCancellable) { pending.await() } } }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.choose(1, "safe")
        runCurrent()
        vm.close()
        pending.complete(Result.Success(plan(1)))
        runCurrent()
        vm.open(2)
        vm.loadOptions(2)
        vm.choose(2, "safe")
        vm.custom(2, 8 * 60)
        vm.revert(2)
        runCurrent()
        assertTrue(saved.isEmpty())
        assertTrue(notices.isEmpty())
        assertNull(vm.state.value.eventId)
        assertEquals(listOf(1L), repo.optionCalls)
    }

    @Test fun `reopening same event serializes writes so reversed save replies cannot occur`() = runTest {
        val first = CompletableDeferred<Result<AlarmPlanView>>()
        val repo = Repository().apply { saveAnswer = { first.await() } }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.choose(1, "safe")
        runCurrent()
        vm.dismiss()
        vm.open(1)
        runCurrent()
        assertTrue(vm.state.value.saving)
        vm.choose(1, "safe")
        vm.custom(1, 8 * 60)
        vm.revert(1)
        runCurrent()
        assertEquals(1, repo.choices.size)
        assertTrue(repo.customs.isEmpty())
        assertTrue(repo.reverts.isEmpty())

        // The server now stores the completed write; reopening reads that state again.
        repo.planAnswer = { Result.Success(plan(it).copy(alarmAt = "6:50")) }
        first.complete(Result.Success(plan(1).copy(alarmAt = "6:50")))
        runCurrent()
        assertFalse(vm.state.value.saving)
        assertEquals("6:50", vm.state.value.plan?.alarmAt)
        assertTrue(notices.isEmpty())
        repo.saveAnswer = { Result.Success(plan(it).copy(alarmAt = "6:40")) }
        vm.choose(1, "safe")
        runCurrent()
        assertEquals(listOf("6:50", "6:40"), saved.map { it.alarmAt })
        assertNull(vm.state.value.eventId)
    }

    @Test fun `plan failure has a reason and options retry also reloads missing plan`() = runTest {
        val repo = Repository().apply { planAnswer = { Result.Failure("계획을 받지 못했어요. 다시 시도해 주세요") } }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        assertNotNull(vm.state.value.error)
        assertNotNull(vm.state.value.options)
        vm.custom(1, 8 * 60)
        assertTrue(repo.customs.isEmpty())
        repo.planAnswer = { Result.Success(plan(it)) }
        vm.loadOptions(1)
        runCurrent()
        assertEquals(2, repo.planCalls.size)
        assertEquals(plan(1), vm.state.value.plan)
        assertNull(vm.state.value.error)
    }

    @Test fun `reopened sheet does not read over a pending write or hide its failure`() = runTest {
        val pendingSave = CompletableDeferred<Result<AlarmPlanView>>()
        val repo = Repository().apply { saveAnswer = { pendingSave.await() } }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.choose(1, "safe")
        runCurrent()
        vm.dismiss()
        vm.open(1)
        runCurrent()
        assertEquals(listOf(1L), repo.optionCalls)
        assertEquals(listOf(1L), repo.planCalls)
        pendingSave.complete(Result.Failure("저장하지 못했어요"))
        runCurrent()
        assertEquals("저장하지 못했어요", vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.saving)
    }

    @Test fun `fresh options cannot enable writes while cached plan is being refreshed and synchronized`() = runTest {
        val read = CompletableDeferred<Result<AlarmPlanView>>()
        val sync = CompletableDeferred<String?>()
        val fresh = plan(1).copy(alarmAt = "6:35")
        known = plan(1).copy(alarmAt = "7:30")
        val repo = Repository().apply { planAnswer = { read.await() } }
        val vm = vm(repo) { refreshed += it; sync.await() }
        vm.open(1)
        runCurrent()
        assertEquals("7:30", vm.state.value.plan?.alarmAt)
        assertNotNull(vm.state.value.options)
        assertTrue(vm.state.value.loading)
        vm.choose(1, "safe")
        vm.custom(1, 8 * 60)
        vm.revert(1)
        assertTrue(repo.choices.isEmpty() && repo.customs.isEmpty() && repo.reverts.isEmpty())

        read.complete(Result.Success(fresh))
        runCurrent()
        assertEquals(fresh, vm.state.value.plan)
        assertEquals(listOf(fresh), refreshed)
        assertTrue(vm.state.value.loading)
        vm.choose(1, "safe")
        assertTrue(repo.choices.isEmpty())
        sync.complete(null)
        runCurrent()
        assertFalse(vm.state.value.loading)
        vm.choose(1, "safe")
        runCurrent()
        assertEquals(listOf(1L to .987), repo.choices)
    }

    @Test fun `failed plan refresh keeps cached preview but blocks all writes until retry succeeds`() = runTest {
        known = plan(1).copy(alarmAt = "7:30")
        val repo = Repository().apply { planAnswer = { Result.Failure("최신 알람을 받지 못했어요") } }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        assertEquals("7:30", vm.state.value.plan?.alarmAt)
        assertFalse(vm.state.value.loading)
        assertEquals("최신 알람을 받지 못했어요", vm.state.value.error)
        vm.choose(1, "safe")
        vm.custom(1, 8 * 60)
        vm.revert(1)
        assertTrue(repo.choices.isEmpty() && repo.customs.isEmpty() && repo.reverts.isEmpty())
        assertTrue(refreshed.isEmpty())

        val fresh = plan(1).copy(alarmAt = "6:40")
        repo.planAnswer = { Result.Success(fresh) }
        vm.loadOptions(1)
        runCurrent()
        assertEquals(listOf(1L, 1L), repo.planCalls)
        assertEquals(fresh, vm.state.value.plan)
        assertNull(vm.state.value.error)
        assertEquals(listOf(fresh), refreshed)
    }

    @Test fun `retry refreshes an existing plan instead of combining it with new options`() = runTest {
        val repo = Repository()
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        val nextPlan = CompletableDeferred<Result<AlarmPlanView>>()
        repo.planAnswer = { nextPlan.await() }
        repo.optionAnswer = { Result.Success(options.copy(selectedKey = "safe")) }
        vm.loadOptions(1)
        runCurrent()
        assertEquals(listOf(1L, 1L), repo.planCalls)
        assertEquals("safe", vm.state.value.options?.selectedKey)
        assertTrue(vm.state.value.loading)
        vm.choose(1, "safe")
        assertTrue(repo.choices.isEmpty())
        val fresh = plan(1).copy(alarmAt = "6:25")
        nextPlan.complete(Result.Success(fresh))
        runCurrent()
        assertEquals(fresh, vm.state.value.plan)
        assertEquals(listOf(plan(1), fresh), refreshed)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `a stale read from a closed sheet never synchronizes over a newer saved selection`() = runTest {
        val stale = CompletableDeferred<Result<AlarmPlanView>>()
        val repo = Repository().apply { planAnswer = { withContext(NonCancellable) { stale.await() } } }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.dismiss()
        val fresh = plan(1).copy(alarmAt = "6:50")
        repo.planAnswer = { Result.Success(fresh) }
        vm.open(1)
        runCurrent()
        val selected = plan(1).copy(alarmAt = "6:20")
        repo.saveAnswer = { Result.Success(selected) }
        vm.choose(1, "safe")
        runCurrent()
        stale.complete(Result.Success(plan(1).copy(alarmAt = "8:00")))
        runCurrent()
        assertEquals(listOf(fresh), refreshed)
        assertEquals(listOf(selected), saved)
        assertNull(vm.state.value.eventId)
    }

    @Test fun `logout prevents a late uncancellable plan read from synchronizing the old account`() = runTest {
        val read = CompletableDeferred<Result<AlarmPlanView>>()
        val repo = Repository().apply { planAnswer = { withContext(NonCancellable) { read.await() } } }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.close()
        read.complete(Result.Success(plan(1)))
        runCurrent()
        assertTrue(refreshed.isEmpty())
        assertTrue(saved.isEmpty())
        assertNull(vm.state.value.eventId)
    }

    @Test fun `late refresh synchronization cannot overwrite the next sheet loading or errors`() = runTest {
        val oldSync = CompletableDeferred<String?>()
        val repo = Repository()
        val vm = vm(repo) {
            refreshed += it
            if (it.eventId == 1L) withContext(NonCancellable) { oldSync.await() } else null
        }
        vm.open(1)
        runCurrent()
        assertTrue(vm.state.value.loading)
        vm.dismiss()
        vm.open(2)
        runCurrent()
        oldSync.complete("옛 시트의 동기화 실패")
        runCurrent()
        assertEquals(2L, vm.state.value.eventId)
        assertEquals(plan(2), vm.state.value.plan)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.error)
    }

    @Test fun `read synchronization failure stays a read error without saved notification or enabled writes`() = runTest {
        val repo = Repository()
        refreshError = "기기 알람을 새 시각으로 맞추지 못했어요. 다시 시도해 주세요."
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        assertEquals(refreshError, vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertTrue(saved.isEmpty())
        assertTrue(notices.isEmpty())
        vm.choose(1, "safe")
        vm.custom(1, 8 * 60)
        vm.revert(1)
        assertTrue(repo.choices.isEmpty() && repo.customs.isEmpty() && repo.reverts.isEmpty())
        refreshError = null
        vm.loadOptions(1)
        runCurrent()
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.loading)
    }

    @Test fun `a response for another event is rejected before host synchronization`() = runTest {
        known = plan(1)
        val repo = Repository().apply {
            planAnswer = { Result.Success(this@AlarmDecisionViewModelTest.plan(2)) }
        }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        assertEquals(plan(1), vm.state.value.plan)
        assertNotNull(vm.state.value.error)
        assertTrue(refreshed.isEmpty())
        vm.choose(1, "safe")
        assertTrue(repo.choices.isEmpty())
    }

    @Test fun `both server reads wait for pending uploads and only a synchronized pair can write`() = runTest {
        val barrier = CompletableDeferred<String?>()
        val order = mutableListOf<String>()
        val repo = Repository().apply {
            planAnswer = { id -> order += "plan"; Result.Success(this@AlarmDecisionViewModelTest.plan(id)) }
            optionAnswer = { order += "options"; Result.Success(options) }
        }
        val vm = vm(repo, onBeforeRead = { order += "uploads"; barrier.await() })
        vm.open(1)
        runCurrent()
        assertEquals(listOf("uploads"), order)
        assertTrue(repo.planCalls.isEmpty() && repo.optionCalls.isEmpty())
        assertTrue(vm.state.value.loading)
        assertFalse(vm.state.value.canWrite)
        barrier.complete(null)
        runCurrent()
        assertEquals(listOf("uploads", "options", "plan"), order)
        assertTrue(vm.state.value.canWrite)
    }

    @Test fun `uploads that cannot drain block reads until an explicit successful retry`() = runTest {
        var pending = true
        val repo = Repository()
        val vm = vm(repo, onBeforeRead = { "아직 올리지 못한 기록이 있어요".takeIf { pending } })
        vm.open(1)
        runCurrent()
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.canWrite)
        assertNotNull(vm.state.value.error)
        assertTrue(repo.planCalls.isEmpty() && repo.optionCalls.isEmpty())
        pending = false
        vm.loadOptions(1)
        runCurrent()
        assertEquals(listOf(1L), repo.planCalls)
        assertEquals(listOf(1L), repo.optionCalls)
        assertTrue(vm.state.value.canWrite)
        assertNull(vm.state.value.error)
    }

    @Test fun `a closed upload barrier cannot start reads for a reopened sheet`() = runTest {
        val oldBarrier = CompletableDeferred<String?>()
        var attempts = 0
        val repo = Repository()
        val vm = vm(repo, onBeforeRead = {
            if (++attempts == 1) withContext(NonCancellable) { oldBarrier.await() } else null
        })
        vm.open(1)
        runCurrent()
        vm.dismiss()
        vm.open(2)
        runCurrent()
        oldBarrier.complete(null)
        runCurrent()
        assertEquals(listOf(2L), repo.planCalls)
        assertEquals(listOf(2L), repo.optionCalls)
        assertEquals(2L, vm.state.value.eventId)
        assertTrue(vm.state.value.canWrite)
    }

    @Test fun `logout while waiting for uploads never starts either read`() = runTest {
        val barrier = CompletableDeferred<String?>()
        val repo = Repository()
        val vm = vm(repo, onBeforeRead = { withContext(NonCancellable) { barrier.await() } })
        vm.open(1)
        runCurrent()
        vm.close()
        barrier.complete(null)
        runCurrent()
        assertTrue(repo.planCalls.isEmpty() && repo.optionCalls.isEmpty())
        assertFalse(vm.state.value.canWrite)
    }

    @Test fun `failed options block manual and revert too but successful empty options allow manual time`() = runTest {
        val repo = Repository().apply { optionAnswer = { Result.Failure("선택지를 받지 못했어요") } }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        assertFalse(vm.state.value.canWrite)
        vm.custom(1, 8 * 60)
        vm.revert(1)
        assertTrue(repo.customs.isEmpty() && repo.reverts.isEmpty())
        repo.optionAnswer = { Result.Success(options.copy(options = emptyList())) }
        vm.loadOptions(1)
        runCurrent()
        assertTrue(vm.state.value.canWrite)
        vm.custom(1, 8 * 60)
        runCurrent()
        assertEquals(1, repo.customs.size)
    }

    @Test fun `a failed save can retry while a saved but unsynchronized response cannot write again`() = runTest {
        val repo = Repository().apply { saveAnswer = { Result.Failure("다시 저장해 주세요") } }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.choose(1, "safe")
        assertFalse(vm.state.value.canWrite)
        runCurrent()
        assertTrue(vm.state.value.canWrite)
        syncError = "저장했지만 기기 알람을 맞추지 못했어요"
        repo.saveAnswer = { id -> Result.Success(this@AlarmDecisionViewModelTest.plan(id)) }
        vm.choose(1, "safe")
        runCurrent()
        assertFalse(vm.state.value.canWrite)
        vm.custom(1, 8 * 60)
        vm.revert(1)
        assertTrue(repo.customs.isEmpty() && repo.reverts.isEmpty())
    }
}
