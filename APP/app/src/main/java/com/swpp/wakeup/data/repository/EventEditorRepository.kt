package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.EventTagDto
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.domain.model.RecentDestinations

/** The editor owns tag changes and destination history; the server owns tag defaults and validation. */
interface EventEditorRepository {
    suspend fun tags(): EventRepository.Result<List<EventTagDto>>
    suspend fun createTag(label: String): EventRepository.Result<EventTagDto>
    suspend fun renameTag(key: String, label: String): EventRepository.Result<EventTagDto>
    suspend fun deleteTag(key: String): EventRepository.Result<Unit>
    suspend fun recentDestinations(home: PlaceSearchItem?): List<PlaceSearchItem>?
}

class EventEditorDataRepository(
    private val events: EventRepository,
    private val accounts: AccountActions,
) : EventEditorRepository {
    override suspend fun tags() = events.tags()
    override suspend fun createTag(label: String) = events.createTag(label)
    override suspend fun renameTag(key: String, label: String) = events.renameTag(key, label)
    override suspend fun deleteTag(key: String) = events.deleteTag(key)
    override suspend fun recentDestinations(home: PlaceSearchItem?): List<PlaceSearchItem>? {
        val response = accounts.recentDestinations().getOrNull()
        return RecentDestinations.fromHistoryResponse(response?.code(), response?.body(), home)
    }
}
