package com.swpp.wakeup.alarm

import com.swpp.wakeup.domain.model.AlarmSchedule

// AI-generated (Claude)
/**
 * 알람 화면이 떠 있는 동안 들어온 알람으로 화면을 넘긴다. 판단만 여기 두는 순수 값이다(JVM 테스트 대상).
 *
 * ## 왜 필요한가
 *
 * 알람 화면은 `singleInstance` 라서, 화면이 떠 있는 동안 울린 두 번째 알람(직접 켠 다른 일정, 미뤘다가
 * 다시 울린 알람)이 같은 화면으로 들어온다([AlarmActivity.onNewIntent]). 화면이 앞 알람을 그대로 그리고
 * 해제·미루기만 새 알람에 적히면, 사용자가 본 알람과 꺼진 알람이 다르고 앞 알람의 응답은 어디에도 남지
 * 않는다. 그래서 화면·소리·해제를 함께 새 알람으로 넘기고, 앞 알람은 응답 없이 끝났다(`silenced`)고 적는다.
 *
 * ## 넘기지 않는 경우
 *
 * 같은 알람도 여러 번 들어온다. 수신기가 전체화면 알림과 직접 시작을 둘 다 쓰고([AlarmReceiver]), 사용자가
 * 알림을 눌러도 같은 인텐트가 온다. 이것까지 넘기면 자동으로 멈춘 소리가 알림을 누르는 순간 다시 울린다.
 * 인텐트에는 일정 id 만 있으므로 사본에서 찾은 알람의 키([MissedAlarms.key])로 가린다.
 *
 * @property next 화면·소리·해제·미루기가 이제 맡을 알람
 * @property unanswered 아무도 끄지 않은 채 넘겨준 앞 알람. 응답 없이 끝났다고 적는다. 소리를 자동으로
 *   멈출 때 이미 적었으면 null 이다 — 한 알람을 두 번 적지 않는다.
 */
internal data class AlarmHandover(
    val next: AlarmSchedule,
    val unanswered: AlarmSchedule?,
) {
    companion object {
        /**
         * @param showing 지금 화면이 맡은 알람
         * @param incoming 들어온 인텐트의 일정을 사본에서 찾은 알람. 사본에서 빠졌으면 null 이고 지금 알람을 지킨다
         * @param silencedKeys 이 화면이 이미 응답 없이 끝났다고 적은 알람의 키. 거의 같은 순간에 울린 두 알람은
         *   두 경로의 인텐트가 뒤섞여 들어온다. 넘겨준 알람의 인텐트가 늦게 오더라도 그 알람으로 되돌아가지 않는다
         * @param rungKeys 수신기가 울렸다고 표시한 알람의 키([ScheduledAlarmStore.handledKeys]).
         *   수신기는 화면을 띄우기 전에 표시한다
         * @return 넘길 때만 값이 있다. 같은 알람이 다시 들어왔으면 null
         */
        fun of(
            showing: AlarmSchedule,
            incoming: AlarmSchedule?,
            silencedKeys: Set<String>,
            rungKeys: Set<String>,
        ): AlarmHandover? {
            if (incoming == null) return null
            val key = MissedAlarms.key(incoming)
            if (key == MissedAlarms.key(showing) || key in silencedKeys) return null
            // 같은 일정은 다른 시각으로 **실제로 울렸을 때만** 넘긴다. 동기화가 사본의 시각만 뒤로 옮긴 뒤
            // 알림을 누르면, 아직 울리지 않은 시각이 들어온다.
            if (incoming.eventId == showing.eventId && key !in rungKeys) return null
            return AlarmHandover(
                next = incoming,
                unanswered = showing.takeUnless { MissedAlarms.key(it) in silencedKeys },
            )
        }
    }
}
