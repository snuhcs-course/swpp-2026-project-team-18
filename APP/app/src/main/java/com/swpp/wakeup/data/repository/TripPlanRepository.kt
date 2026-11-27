package com.swpp.wakeup.data.repository

import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.RouteSegments
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 이동 화면이 필요한 조회와 변경 알림. UI는 Retrofit이나 저장소 구현을 알 필요가 없다. */
interface TripPlanRepository {
    data class Change(val eventId: Long, val plan: AlarmPlanView?)

    val changes: Flow<Change>
    suspend fun loadPlan(eventId: Long): EventRepository.Result<AlarmPlanView>
    suspend fun baseRouteSegments(eventId: Long, routeKey: String): EventRepository.Result<RouteSegments?>
    fun changed(eventId: Long, plan: AlarmPlanView?)
}

/** 계정 화면 수명 동안만 공유한다. 변경 알림은 디스크나 다음 로그인에 남기지 않는다. */
class EventTripPlanRepository(private val events: EventRepository) : TripPlanRepository {
    private val _changes = MutableSharedFlow<TripPlanRepository.Change>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val changes = _changes.asSharedFlow()

    override suspend fun loadPlan(eventId: Long) = events.loadPlan(eventId)
    override suspend fun baseRouteSegments(eventId: Long, routeKey: String) =
        events.baseRouteSegments(eventId, routeKey)

    override fun changed(eventId: Long, plan: AlarmPlanView?) {
        _changes.tryEmit(TripPlanRepository.Change(eventId, plan))
    }
}
