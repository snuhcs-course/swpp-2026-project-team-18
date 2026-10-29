package com.swpp.wakeup.alarm

import com.swpp.wakeup.domain.model.AlarmSchedule

// Partly AI-generated (Claude)
/**
 * 울리지 않고 지나간 알람을 가려낸다.
 *
 * ## 왜 필요한가
 *
 * 알람이 **등록된 채로 사라지는** 경로가 있다. 앱이 강제 중지되면(설정의 "강제 중지",
 * 제조사 정리 기능, Android Studio 의 Stop·Re-run) 시스템이 그 앱의 알람과 예약
 * 작업을 전부 지우고, 사용자가 앱을 다시 열 때까지 앱 코드가 한 줄도 돌지 않는다.
 * 10-27 실기기에서 08:25 알람이 이렇게 사라졌고, 알람 해제가 추적의 시작점이라
 * 이동 기록도 함께 사라졌다.
 *
 * 알람 자체는 되살릴 수 없다. 대신 사용자가 앱을 열었을 때 **아직 출발 전이면**
 * 그 사실을 알리고 추적을 시작할 기회를 준다. 그 아침 08:28 에 앱을 열었고 출발은
 * 09:25 였다 — 이 판단이 있었으면 이동 기록은 살릴 수 있었다.
 *
 * ## 무엇을 "놓쳤다" 고 보는가
 *
 * 저장소 사본([ScheduledAlarmStore])에 남아 있고, 시각이 지났고, [AlarmReceiver] 가
 * 처리했다는 표시(`handled`)가 없는 알람이다. 사본은 강제 중지로 지워지지 않는다.
 *
 * Context 없이 돌아가게 둔다. 판단이 틀리면 울린 알람에 "안 울렸다" 를 띄우거나
 * 놓친 알람을 조용히 넘기는데, 둘 다 기기를 돌려서는 잡기 어렵다.
 * `MissedAlarmsTest` 가 지킨다.
 */
internal object MissedAlarms {

    /**
     * 발화가 늦게 배달될 여유.
     *
     * 알람 시각과 [AlarmReceiver] 가 실제로 받는 시각 사이에는 틈이 있다. 그 사이에
     * 홈을 열면 곧 울릴 알람을 "놓쳤다" 고 잘못 띄운다.
     */
    const val GRACE_MILLIS = 2 * 60 * 1000L

    /** 처리 표시를 남겨 두는 기간. 그보다 오래된 알람은 어차피 마감이 지났다. */
    const val HANDLED_TTL_MILLIS = 24 * 60 * 60 * 1000L

    /**
     * 한 번의 알람을 가리키는 키.
     *
     * 일정 id 만 쓰지 않는다. 미루기와 재계산이 같은 일정의 알람 시각을 바꾸므로,
     * 09:00 알람을 처리한 표시가 09:05 알람까지 덮으면 안 된다.
     */
    fun key(schedule: AlarmSchedule): String = "${schedule.eventId}@${schedule.alarmAtMillis}"

    /** [key] 에서 알람 시각을 되읽는다. 모양이 다르면 null — 오래된 값으로 보고 버린다. */
    fun alarmAtOf(key: String): Long? = key.substringAfter('@', "").toLongOrNull()

    /**
     * 놓친 알람을 알려도 쓸모가 있는 끝. 계획된 출발 시각, 없으면 일정 시작.
     *
     * 출발한 뒤에 "알람이 안 울렸다" 를 띄우면 할 수 있는 일이 없다.
     */
    fun deadlineMillis(schedule: AlarmSchedule): Long =
        schedule.departByMillis ?: schedule.startAtMillis

    fun isMissed(schedule: AlarmSchedule, handled: Set<String>, nowMillis: Long): Boolean =
        schedule.alarmAtMillis + GRACE_MILLIS <= nowMillis &&
            nowMillis < deadlineMillis(schedule) &&
            key(schedule) !in handled

    /**
     * 지금 알릴 놓친 알람. 알람 시각 오름차순, 일정마다 하나.
     *
     * @param candidates 저장소 사본과 이전에 옮겨 둔 놓친 알람을 합친 것
     * @param upcomingEventIds 다시 등록돼 앞으로 울릴 알람이 있는 일정. 재계산이
     *   시각을 뒤로 민 경우다 — 곧 울릴 것이므로 "안 울렸다" 고 하지 않는다.
     */
    fun select(
        candidates: List<AlarmSchedule>,
        handled: Set<String>,
        upcomingEventIds: Set<Long>,
        nowMillis: Long,
    ): List<AlarmSchedule> =
        candidates
            .filter { it.eventId !in upcomingEventIds && isMissed(it, handled, nowMillis) }
            .groupBy { it.eventId }
            .map { (_, same) -> same.maxBy { it.alarmAtMillis } }
            .sortedBy { it.alarmAtMillis }

    /** 오래된 처리 표시를 버린다. 표시가 쌓여 저장소가 커지지 않게 한다. */
    fun pruneHandled(handled: Set<String>, nowMillis: Long): Set<String> =
        handled.filterTo(mutableSetOf()) { key ->
            val at = alarmAtOf(key) ?: return@filterTo false
            at > nowMillis - HANDLED_TTL_MILLIS
        }
}
