package com.swpp.wakeup.ui.events

import com.swpp.wakeup.data.remote.EventTagDto
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.repository.EventEditorRepository
import com.swpp.wakeup.data.repository.EventRepository.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventEditorViewModelTest {
    private fun tag(key: String = "custom", label: String = "내 종류", custom: Boolean = true) =
        EventTagDto(1L, key, label, .9, null, custom)
    private fun place(name: String) = PlaceSearchItem(null, name, null, 37.5, 127.0, null)
    private class Fake : EventEditorRepository {
        var list: suspend () -> Result<List<EventTagDto>> = { Result.Success(emptyList()) }
        var create: suspend (String) -> Result<EventTagDto> = { Result.Failure("failed") }
        var rename: suspend (String, String) -> Result<EventTagDto> = { _, _ -> Result.Failure("failed") }
        var delete: suspend (String) -> Result<Unit> = { Result.Success(Unit) }
        var history: suspend (PlaceSearchItem?) -> List<PlaceSearchItem>? = { null }
        var listCalls = 0
        val creates = mutableListOf<String>()
        val renames = mutableListOf<Pair<String, String>>()
        val deletes = mutableListOf<String>()
        override suspend fun tags(): Result<List<EventTagDto>> { ++listCalls; return list() }
        override suspend fun createTag(label: String): Result<EventTagDto> { creates += label; return create(label) }
        override suspend fun renameTag(key: String, label: String): Result<EventTagDto> { renames += key to label; return rename(key, label) }
        override suspend fun deleteTag(key: String): Result<Unit> { deletes += key; return delete(key) }
        override suspend fun recentDestinations(home: PlaceSearchItem?) = history(home)
    }
    private class Hooks {
        var form = "first"
        val selected = mutableListOf<String?>()
        val deleted = mutableListOf<String>()
        val recent = mutableListOf<List<PlaceSearchItem>>()
        var changed = 0
        fun value() = EventEditorViewModel.Hooks(
            formId = { form }, selectTag = { selected += it }, deletedTag = { deleted += it },
            categoriesChanged = { ++changed }, recentChanged = { recent += it },
        )
    }

    @Test fun `load keeps prior categories on failure and deduplicates an active request`() = runTest {
        val pending = CompletableDeferred<Result<List<EventTagDto>>>()
        val fake = Fake().apply { list = { pending.await() } }
        val vm = EventEditorViewModel(fake, backgroundScope)
        vm.loadTags(); vm.loadTags(); runCurrent()
        assertEquals(1, fake.listCalls)
        pending.complete(Result.Success(listOf(tag())))
        runCurrent()
        fake.list = { Result.Failure("offline") }
        vm.loadTags(); runCurrent()
        assertEquals(listOf(tag()), vm.tags.value)
    }

    @Test fun `new forced category request wins when cancelled old request completes late`() = runTest {
        val old = CompletableDeferred<Result<List<EventTagDto>>>()
        val fake = Fake().apply { list = { withContext(NonCancellable) { old.await() } } }
        val vm = EventEditorViewModel(fake, backgroundScope)
        vm.loadTags(); runCurrent()
        fake.list = { Result.Success(listOf(tag(label = "new"))) }
        vm.loadTags(force = true); runCurrent()
        old.complete(Result.Success(listOf(tag(label = "old")))); runCurrent()
        assertEquals("new", vm.tags.value.single().label)
    }

    @Test fun `create selects server tag once and blocks repeated submission and dismissal while busy`() = runTest {
        val answer = CompletableDeferred<Result<EventTagDto>>()
        val fake = Fake().apply { create = { answer.await() } }
        val hooks = Hooks()
        val vm = EventEditorViewModel(fake, backgroundScope, hooks.value())
        vm.openAdd(); vm.onAddLabel("새 종류"); vm.submitAdd(); vm.submitAdd(); vm.dismissAdd(); runCurrent()
        assertTrue(vm.add.value.busy)
        assertEquals(listOf("새 종류"), fake.creates)
        answer.complete(Result.Success(tag())); runCurrent()
        assertEquals(listOf(tag()), vm.tags.value)
        assertEquals(listOf("custom"), hooks.selected)
        assertFalse(vm.add.value.open)
    }

    @Test fun `create error keeps input and server field explanation`() = runTest {
        val fake = Fake().apply { create = { Result.Failure("invalid", status = 400, fields = mapOf("label" to "이름을 확인해 주세요")) } }
        val vm = EventEditorViewModel(fake, backgroundScope)
        vm.openAdd(); vm.onAddLabel("입력"); vm.submitAdd(); runCurrent()
        assertEquals("입력", vm.add.value.label)
        assertEquals("이름을 확인해 주세요", vm.add.value.error)
        assertFalse(vm.add.value.busy)
    }

    @Test fun `late creation cannot select a tag in a different event draft`() = runTest {
        val answer = CompletableDeferred<Result<EventTagDto>>()
        val fake = Fake().apply { create = { answer.await() } }
        val hooks = Hooks()
        val vm = EventEditorViewModel(fake, backgroundScope, hooks.value())
        vm.openAdd(); vm.onAddLabel("새 종류"); vm.submitAdd(); runCurrent()
        hooks.form = "second"
        answer.complete(Result.Success(tag())); runCurrent()
        assertTrue(hooks.selected.isEmpty())
        assertEquals(listOf(tag()), vm.tags.value)
    }

    @Test fun `category GET started before creation cannot erase newly created tag`() = runTest {
        val old = CompletableDeferred<Result<List<EventTagDto>>>()
        val fake = Fake().apply {
            list = { withContext(NonCancellable) { old.await() } }
            create = { Result.Success(tag()) }
        }
        val vm = EventEditorViewModel(fake, backgroundScope)
        vm.loadTags(); runCurrent()
        vm.openAdd(); vm.onAddLabel("new"); vm.submitAdd(); runCurrent()
        old.complete(Result.Success(emptyList())); runCurrent()
        assertEquals(listOf(tag()), vm.tags.value)
    }

    @Test fun `only custom categories open edit and unchanged label closes without request`() = runTest {
        val fake = Fake().apply { list = { Result.Success(listOf(tag(), tag("global", custom = false))) } }
        val vm = EventEditorViewModel(fake, backgroundScope)
        vm.loadTags(); runCurrent()
        vm.openEdit("global"); assertFalse(vm.edit.value.open)
        vm.openEdit("custom"); vm.onEditLabel(" 내 종류 "); vm.submitRename(); runCurrent()
        assertFalse(vm.edit.value.open)
        assertTrue(fake.renames.isEmpty())
    }

    @Test fun `rename updates same position and requests dependent home refresh`() = runTest {
        val fake = Fake().apply {
            list = { Result.Success(listOf(tag(), tag("second", "다음"))) }
            rename = { key, label -> Result.Success(tag(key, label)) }
        }
        val hooks = Hooks()
        val vm = EventEditorViewModel(fake, backgroundScope, hooks.value())
        vm.loadTags(); runCurrent()
        vm.openEdit("custom"); vm.onEditLabel("새 이름"); vm.submitRename(); runCurrent()
        assertEquals(listOf("새 이름", "다음"), vm.tags.value.map { it.label })
        assertEquals(1, hooks.changed)
        assertFalse(vm.edit.value.open)
    }

    @Test fun `delete clears matching selection through hook and preserves dialog on failure`() = runTest {
        val fake = Fake().apply { list = { Result.Success(listOf(tag())) }; delete = { Result.Failure("gone", status = 404) } }
        val hooks = Hooks()
        val vm = EventEditorViewModel(fake, backgroundScope, hooks.value())
        vm.loadTags(); runCurrent()
        vm.openEdit("custom"); vm.askDelete(true); vm.confirmDelete(); runCurrent()
        assertTrue(vm.edit.value.confirmDelete)
        assertNotNull(vm.edit.value.error)
        fake.delete = { Result.Success(Unit) }
        vm.confirmDelete(); runCurrent()
        assertEquals(listOf("custom"), hooks.deleted)
        assertTrue(vm.tags.value.isEmpty())
        assertEquals(1, hooks.changed)
    }

    @Test fun `latest recent destination lookup wins and missing response preserves last list`() = runTest {
        val old = CompletableDeferred<List<PlaceSearchItem>?>()
        val fake = Fake().apply { history = { withContext(NonCancellable) { old.await() } } }
        val hooks = Hooks()
        val vm = EventEditorViewModel(fake, backgroundScope, hooks.value())
        vm.loadRecent(null); runCurrent()
        fake.history = { listOf(place("new")) }
        vm.loadRecent(null); runCurrent()
        old.complete(listOf(place("old"))); runCurrent()
        fake.history = { null }
        vm.loadRecent(null); runCurrent()
        assertEquals(listOf(place("new")), vm.recent.value)
        assertEquals(listOf(listOf(place("new"))), hooks.recent)
    }

    @Test fun `cancellation releases mutation busy state without converting cancellation to error`() = runTest {
        val fake = Fake().apply { create = { throw CancellationException("cancel") } }
        val vm = EventEditorViewModel(fake, backgroundScope)
        vm.openAdd(); vm.onAddLabel("입력"); vm.submitAdd(); runCurrent()
        assertFalse(vm.add.value.busy)
        assertNull(vm.add.value.error)
        assertEquals("입력", vm.add.value.label)
    }

    @Test fun `logout clears all editor state and ignores noncancellable old account responses`() = runTest {
        val old = CompletableDeferred<Result<EventTagDto>>()
        val fake = Fake().apply { create = { withContext(NonCancellable) { old.await() } } }
        val hooks = Hooks()
        val vm = EventEditorViewModel(fake, backgroundScope, hooks.value())
        vm.openAdd(); vm.onAddLabel("private"); vm.submitAdd(); runCurrent()
        vm.close()
        old.complete(Result.Success(tag())); runCurrent()
        vm.openAdd(); vm.loadTags(); runCurrent()
        assertTrue(vm.tags.value.isEmpty())
        assertFalse(vm.add.value.open)
        assertFalse(vm.edit.value.open)
        assertNull(vm.recent.value)
        assertTrue(hooks.selected.isEmpty())
        assertEquals(0, fake.listCalls)
    }
}
