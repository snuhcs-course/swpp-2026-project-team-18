// Partly AI-generated (Claude)
package com.swpp.wakeup.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.swpp.wakeup.domain.model.AlarmProposalRule

/**
 * "바꿀까요" 제안의 마감(서버 `answer_by`)에 깨어난다(BE-H3d, 서버 계약 6절). 답이 없었으면 기기 알람을 더 이른 쪽으로
 * 다시 걸고 이유를 알린다([AlarmScheduler.settleProposal]). 마감은 서버 값 그대로다 — 앱이 계산하지 않는다.
 */
class ProposalDeadlineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(AlarmReceiver.EXTRA_EVENT_ID, -1L).takeIf { it > 0L } ?: return
        val app = context.applicationContext
        val reason = ScheduledAlarmStore(app).find(eventId)?.proposalReason
        val moved = AlarmScheduler(app).settleProposal(eventId) ?: return
        AlarmNotifications.postProposal(
            context = app,
            eventId = eventId,
            title = "알람 시각을 바꿨어요",
            text = AlarmProposalRule.settledNotice(moved.alarmAtMillis, reason),
        )
    }
}
