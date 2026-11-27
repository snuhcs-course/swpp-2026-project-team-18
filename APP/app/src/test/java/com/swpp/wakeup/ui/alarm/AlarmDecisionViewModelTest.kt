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
    private val notices = mutableListOf<Pair<String, HomeViewModel.AlarmUndo?>>()
    private var version: String? = "0.22.0"
    private var syncError: String? = null
    private var known: AlarmPlanView? = null

    private fun TestScope.vm(repo: Repository) = AlarmDecisionViewModel(
        repo, backgroundScope, { version }, { known },
        onSaved = { saved += it; syncError }, notify = { text, undo -> notices += text to undo }, zone = zone,
    )

    @Test fun `open loads options and plan once while known plan avoids extra lookup`() = runTest {
        val repo = Repository()
        known = plan(1)
        val vm = vm(repo)
        vm.open(1)
        vm.open(1)
        assertEquals(known, vm.state.value.plan)
        assertTrue(vm.state.value.loading)
        runCurrent()
        assertTrue(repo.planCalls.isEmpty())
        assertEquals(listOf(1L), repo.optionCalls)
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

    @Test fun `options retry does not invalidate pending plan lookup`() = runTest {
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

    @Test fun `late options from reopened sheet cannot hide an earlier save failure`() = runTest {
        val pendingSave = CompletableDeferred<Result<AlarmPlanView>>()
        val pendingOptions = CompletableDeferred<Result<RiskOptionsView>>()
        val repo = Repository().apply { saveAnswer = { pendingSave.await() } }
        val vm = vm(repo)
        vm.open(1)
        runCurrent()
        vm.choose(1, "safe")
        runCurrent()
        vm.dismiss()
        repo.optionAnswer = { withContext(NonCancellable) { pendingOptions.await() } }
        vm.open(1)
        runCurrent()
        pendingSave.complete(Result.Failure("저장하지 못했어요"))
        runCurrent()
        pendingOptions.complete(Result.Success(options))
        runCurrent()
        assertEquals("저장하지 못했어요", vm.state.value.error)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.saving)
    }
}
