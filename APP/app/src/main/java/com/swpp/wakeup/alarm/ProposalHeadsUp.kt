// Partly AI-generated (Claude)
package com.swpp.wakeup.alarm

import android.content.Context
import com.swpp.wakeup.domain.model.AlarmProposalRule
import com.swpp.wakeup.domain.model.UpcomingEvent

/**
 * 앱이 꺼져 있을 때 새 "바꿀까요" 제안을 헤드업으로 알린다(BE-H3d, 서버 계약 8절 "경로 갱신 워커").
 * 같은 제안(일정 + `created_at`)은 한 번만. 앱이 화면에 있으면 홈 · ④ 카드가 보이므로 띄우지 않는다.
 */
object ProposalHeadsUp {

    /** 앱 화면이 보이는가. HomeViewModel.onForeground/onBackground 가 맞춘다 */
    @Volatile
    var appVisible: Boolean = false

    private const val FILE = "jit_proposal_seen"
    private const val KEY = "seen"
    private const val MAX_KEPT = 50

    fun notifyNew(context: Context, events: List<UpcomingEvent>) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
        val fresh = events.mapNotNull { e -> e.proposal?.let { e to it } }
            .filter { (e, p) -> AlarmProposalRule.key(e.id, p) !in seen }
        if (fresh.isEmpty()) return
        fresh.forEach { (e, p) ->
            seen += AlarmProposalRule.key(e.id, p)
            if (appVisible) return@forEach
            AlarmNotifications.postProposal(
                context = app,
                eventId = e.id,
                title = AlarmProposalRule.TITLE,
                text = listOfNotNull(
                    "${e.startClock} ${e.title}",
                    p.reasonText,
                    "눌러서 바꿀지 골라 주세요",
                ).joinToString(" · "),
            )
        }
        prefs.edit().putStringSet(KEY, seen.toList().takeLast(MAX_KEPT).toSet()).apply()
    }

    /** 로그아웃 · 계정 전환 */
    fun wipe(context: Context) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
