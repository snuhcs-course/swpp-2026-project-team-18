package com.swpp.wakeup.ui.events

import androidx.lifecycle.ViewModel
import com.swpp.wakeup.data.remote.EventTagDto
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.repository.EventEditorRepository
import com.swpp.wakeup.data.repository.EventRepository.Result
import com.swpp.wakeup.ui.home.HomeViewModel.TagAddState
import com.swpp.wakeup.ui.home.HomeViewModel.TagEditState
import com.swpp.wakeup.ui.home.TAG_LABEL_MAX
import com.swpp.wakeup.ui.home.tagAddErrorOf
import com.swpp.wakeup.ui.home.tagEditErrorOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Owns editor categories, mutation dialogs and recent destinations independently of Home/navigation. */
class EventEditorViewModel(
    private val repository: EventEditorRepository,
    parentScope: CoroutineScope,
    private val hooks: Hooks = Hooks(),
) : ViewModel() {
    data class Hooks(
        val formId: () -> String? = { null },
        val selectTag: (String?) -> Unit = {},
        val deletedTag: (String) -> Unit = {},
        val categoriesChanged: () -> Unit = {},
        val recentChanged: (List<PlaceSearchItem>) -> Unit = {},
    )

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val _tags = MutableStateFlow<List<EventTagDto>>(emptyList())
    val tags = _tags.asStateFlow()
    private val _add = MutableStateFlow(TagAddState())
    val add = _add.asStateFlow()
    private val _edit = MutableStateFlow(TagEditState())
    val edit = _edit.asStateFlow()
    private val _recent = MutableStateFlow<List<PlaceSearchItem>?>(null)
    val recent = _recent.asStateFlow()
    private var listJob: Job? = null
    private var recentJob: Job? = null
    private var addJob: Job? = null
    private var editJob: Job? = null
    private var listGeneration = 0L
    private var recentGeneration = 0L
    private var addGeneration = 0L
    private var editGeneration = 0L
    private var closed = false
    private val mutating get() = _add.value.busy || _edit.value.busy

    fun loadTags(force: Boolean = false) {
        if (closed || mutating || (!force && listJob?.isActive == true)) return
        val generation = ++listGeneration
        listJob?.cancel()
        listJob = scope.launch {
            val result = repository.tags()
            if (!closed && generation == listGeneration && result is Result.Success) _tags.value = result.data
        }
    }

    fun loadRecent(home: PlaceSearchItem?) {
        if (closed) return
        val generation = ++recentGeneration
        recentJob?.cancel()
        recentJob = scope.launch {
            val result = repository.recentDestinations(home)
            if (closed || generation != recentGeneration || result == null) return@launch
            _recent.value = result
            hooks.recentChanged(result)
        }
    }

    fun openAdd() {
        if (closed || mutating) return
        ++addGeneration
        _add.value = TagAddState(open = true)
    }

    fun onAddLabel(value: String) {
        if (closed || _add.value.busy) return
        _add.update { it.copy(label = value.take(TAG_LABEL_MAX), error = null) }
    }

    fun dismissAdd() {
        if (closed || _add.value.busy) return
        ++addGeneration
        _add.value = TagAddState()
    }

    fun submitAdd() {
        val current = _add.value
        if (closed || mutating || !current.open || current.label.isBlank()) return
        invalidateList()
        val generation = ++addGeneration
        val form = hooks.formId()
        _add.update { it.copy(busy = true, error = null) }
        addJob = scope.launch {
            try {
                when (val result = repository.createTag(current.label)) {
                    is Result.Success -> if (!closed && generation == addGeneration) {
                        _tags.update { tagsWith(it, result.data) }
                        _add.value = TagAddState()
                        if (hooks.formId() == form) hooks.selectTag(result.data.key)
                    }
                    is Result.Failure -> if (!closed && generation == addGeneration) {
                        _add.update { it.copy(busy = false, error = tagAddErrorOf(result)) }
                    }
                }
            } catch (cancelled: CancellationException) {
                if (!closed && generation == addGeneration) _add.update { it.copy(busy = false) }
                throw cancelled
            }
        }
    }

    fun openEdit(key: String) {
        if (closed || mutating) return
        val tag = _tags.value.firstOrNull { it.key == key && it.custom == true } ?: return
        ++editGeneration
        _edit.value = TagEditState(key = tag.key, label = tag.label, original = tag.label)
    }

    fun onEditLabel(value: String) {
        if (closed || _edit.value.busy) return
        _edit.update { it.copy(label = value.take(TAG_LABEL_MAX), error = null) }
    }

    fun dismissEdit() {
        if (closed || _edit.value.busy) return
        ++editGeneration
        _edit.value = TagEditState()
    }

    fun askDelete(ask: Boolean) {
        if (closed || _edit.value.busy) return
        _edit.update { it.copy(confirmDelete = ask, error = null) }
    }

    fun submitRename() {
        val current = _edit.value
        val key = current.key ?: return
        if (closed || mutating || current.label.isBlank()) return
        if (current.label.trim() == current.original) {
            dismissEdit()
            return
        }
        mutateEdit(request = { repository.renameTag(key, current.label) }) { tag ->
            _tags.update { tagsRenamed(it, tag) }
        }
    }

    fun confirmDelete() {
        val current = _edit.value
        val key = current.key ?: return
        if (closed || mutating) return
        mutateEdit(request = { repository.deleteTag(key) }) {
            _tags.update { tagsWithout(it, key) }
            hooks.deletedTag(key)
        }
    }

    private fun <T> mutateEdit(request: suspend () -> Result<T>, apply: (T) -> Unit) {
        invalidateList()
        val generation = ++editGeneration
        _edit.update { it.copy(busy = true, error = null) }
        editJob = scope.launch {
            try {
                val result = request()
                if (closed || generation != editGeneration) return@launch
                when (result) {
                    is Result.Success -> {
                        apply(result.data)
                        _edit.value = TagEditState()
                        hooks.categoriesChanged()
                    }
                    is Result.Failure -> _edit.update { it.copy(busy = false, error = tagEditErrorOf(result)) }
                }
            } catch (cancelled: CancellationException) {
                if (!closed && generation == editGeneration) _edit.update { it.copy(busy = false) }
                throw cancelled
            }
        }
    }

    private fun invalidateList() {
        ++listGeneration
        listJob?.cancel()
    }

    /** Logout invalidates callbacks even when a repository ignores cancellation. */
    fun close() {
        closed = true
        ++listGeneration
        ++recentGeneration
        ++addGeneration
        ++editGeneration
        scope.cancel()
        _tags.value = emptyList()
        _add.value = TagAddState()
        _edit.value = TagEditState()
        _recent.value = null
    }

    override fun onCleared() = close()
}
