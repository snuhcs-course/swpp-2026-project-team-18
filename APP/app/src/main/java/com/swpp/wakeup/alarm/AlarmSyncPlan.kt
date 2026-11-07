package com.swpp.wakeup.alarm

import com.swpp.wakeup.domain.model.AlarmSchedule

/**
 * [AlarmScheduler.sync] 가 등록할 목록을 정한다. 순수 함수(JVM 테스트 대상).
 *
 * 서버가 정본이라 받은 목록으로 갈아 끼운다. 단 하나 예외가 **미룬 알람**이다. 미루면
 * 기기 사본만 "지금 + 5분" 으로 바뀌고 서버 시각은 이미 지났다. 갈아 끼우면 그 알람이
 * 조용히 사라진다. 홈이 화면 복귀마다 새로 받게 되면서(todo P0 "다가오는 일정 갱신")
 * "미뤄 두고 앱을 열면 알람이 안 울린다" 가 흔한 일이 되므로 여기서 지킨다.
 */
internal object AlarmSyncPlan {

    // Partly AI-generated (Claude)
    /**
     * @param before 동기화 전 기기 사본
     * @param schedules 서버가 준 켜진 알람
     * @return 등록할 목록. 알람 시각 순, 최대 [max]개
     */
    fun upcoming(
        before: List<AlarmSchedule>,
        schedules: List<AlarmSchedule>,
        nowMillis: Long,
        horizonMillis: Long,
        max: Int,
    ): List<AlarmSchedule> {
        fun inWindow(at: Long) = at > nowMillis && at - nowMillis <= horizonMillis

        val fresh = schedules.filter { inWindow(it.alarmAtMillis) }
        val serverById = schedules.associateBy { it.eventId }

        // 서버 시각은 지났는데 기기 사본이 아직 앞에 있으면 미룬 알람이다. 서버가 그
        // 일정의 알람을 아직 켜 두었을 때만 지킨다(안 감·삭제·끔이면 서버 목록에 없다).
        // 서버가 시각을 앞으로 옮겼으면 서버 시각이 이긴다.
        val kept = before.mapNotNull { b ->
            if (!inWindow(b.alarmAtMillis)) return@mapNotNull null
            val server = serverById[b.eventId] ?: return@mapNotNull null
            if (server.alarmAtMillis > nowMillis) return@mapNotNull null
            server.copy(
                alarmAtMillis = b.alarmAtMillis,
                alarmLabel = b.alarmLabel,
                meridiem = b.meridiem,
                // 미룬 알람의 기록은 계획의 알람 시각으로 보낸다. 다시 울릴 시각만 지키면 둘로 셈된다.
                firstAlarmAtMillis = b.firstAlarmAtMillis,
            )
        }

        return (fresh + kept).sortedBy { it.alarmAtMillis }.take(max)
    }
}
