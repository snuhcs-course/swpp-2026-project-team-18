// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import java.time.Instant
import java.time.ZoneId

/**
 * "바꿀까요" 제안(BE-H3d, 서버 계약 `docs/iter4/server-alarm-control.md` 2 · 6절). 화면 · 기기 알람이 함께 쓴다.
 *
 * @property proposedAt 서버가 준 제안 시각 문자열 그대로. 답할 때 다시 보낸다(바뀌었으면 서버가 409)
 * @property moveMinutes `proposed − current`(분). 음수 = 앞당기기
 * @property answerByMillis 답할 마감. 서버 값 그대로 — 앱이 계산하지 않는다
 */
data class AlarmProposal(
    val kind: String,
    val currentAtMillis: Long,
    val proposedAtMillis: Long,
    val proposedAt: String,
    val moveMinutes: Int,
    val reasonText: String?,
    val keepProbability: Int?,
    val changeProbability: Int?,
    val answerByMillis: Long?,
    val createdAt: String?,
) {
    val earlier: Boolean get() = moveMinutes < 0
}

/** 제안의 문구 · 판단. 순수 함수만 둔다(JVM 테스트 대상). */
object AlarmProposalRule {

    /** 이보다 적게 남은 시각으로는 바꾸지 않는다(서버 `SETTLE_MIN_AHEAD` = 1분) */
    const val SETTLE_MIN_AHEAD_MS = 60_000L

    const val TITLE = "알람을 바꿀까요?"
    const val KEEP_LABEL = "그대로 두기"

    /** 받아들이기 버튼. "오전 7:28로 앞당기기" / "오전 8:20으로 늦추기" */
    fun acceptLabel(p: AlarmProposal, zone: ZoneId = ZoneId.systemDefault()): String {
        val clock = clock(p.proposedAtMillis, zone)
        return "$clock${RerouteAlert.roParticle(clock)} ${if (p.earlier) "앞당기기" else "늦추기"}"
    }

    /** "그대로 두면 정시 도착 확률 46% · 바꾸면 90%". 둘 다 모르면 null. 90% 상한 규칙을 따른다 */
    fun probabilityLine(p: AlarmProposal): String? {
        val keep = p.keepProbability?.let { "그대로 두면 정시 도착 확률 ${ProbabilityRule.displayLabel(it)}" }
        val change = p.changeProbability?.let { "바꾸면 ${ProbabilityRule.displayLabel(it)}" }
        return listOfNotNull(keep, change).joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    /**
     * 답이 없이 마감이 됐을 때 기기가 울릴 시각(6절). **더 이른 쪽**으로 정하되, 그 시각이 이미 지났거나 1분도 남지
     * 않았으면 바꾸지 않고 지금 시각을 지킨다.
     */
    fun settledAlarm(currentAlarmMillis: Long, proposedAtMillis: Long, nowMillis: Long): Long {
        val earlier = minOf(currentAlarmMillis, proposedAtMillis)
        return if (earlier - nowMillis > SETTLE_MIN_AHEAD_MS) earlier else currentAlarmMillis
    }

    /** 마감에 기기가 시각을 바꿨을 때의 알림 본문. "알람을 오전 7:28로 바꿨어요. 이동 시간이 12분 늘었어요" */
    fun settledNotice(newAlarmMillis: Long, reasonText: String?, zone: ZoneId = ZoneId.systemDefault()): String {
        val clock = clock(newAlarmMillis, zone)
        val head = "답이 없어 알람을 $clock${RerouteAlert.roParticle(clock)} 바꿨어요"
        return listOfNotNull(head, reasonText?.trim()?.takeIf { it.isNotEmpty() }).joinToString(". ")
    }

    /** 헤드업 알림을 한 번만 띄우기 위한 열쇠. 같은 제안을 15분마다 다시 내도 `created_at` 이 같다 */
    fun key(eventId: Long, p: AlarmProposal): String = "$eventId:${p.createdAt ?: p.proposedAt}"

    private fun clock(millis: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(millis).atZone(zone)
        return AlarmTimeChoice.clockLabel(t.hour * 60 + t.minute)
    }
}
