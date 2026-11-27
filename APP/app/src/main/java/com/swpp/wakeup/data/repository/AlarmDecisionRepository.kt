package com.swpp.wakeup.data.repository

import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.RiskOptionsView

/** 알람 시각 시트가 필요한 읽기·쓰기 계약. 저장 성공 후 홈·기기 동기화는 호스트가 연결한다. */
interface AlarmDecisionRepository {
    suspend fun plan(eventId: Long): EventRepository.Result<AlarmPlanView>
    suspend fun options(eventId: Long): EventRepository.Result<RiskOptionsView>
    suspend fun choose(eventId: Long, tau: Double): EventRepository.Result<AlarmPlanView>
    suspend fun custom(eventId: Long, alarmAt: String): EventRepository.Result<AlarmPlanView>
    suspend fun revert(eventId: Long): EventRepository.Result<AlarmPlanView>
}

class EventAlarmDecisionRepository(private val events: EventRepository) : AlarmDecisionRepository {
    override suspend fun plan(eventId: Long) = events.loadPlan(eventId)
    override suspend fun options(eventId: Long) = events.loadRiskOptions(eventId)
    override suspend fun choose(eventId: Long, tau: Double) = events.saveRiskOption(eventId, tau)
    override suspend fun custom(eventId: Long, alarmAt: String) = events.setUserAlarm(eventId, alarmAt)
    override suspend fun revert(eventId: Long) = events.clearUserAlarm(eventId)
}
