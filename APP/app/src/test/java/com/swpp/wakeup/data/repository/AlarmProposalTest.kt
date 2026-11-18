// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.ProfileDto
import com.swpp.wakeup.data.remote.RiskOptionsDto
import com.swpp.wakeup.domain.model.AlarmProposalRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * "바꿀까요" 제안(C1-7 · 8)과 추천 칸(C1-5). 예시 JSON 은 서버 계약 `docs/iter4/server-alarm-control.md` 2절의
 * 실제 로컬 서버 응답이다.
 */
class AlarmProposalTest {

    private val gson = Gson()
    private val zone = ZoneId.of("Asia/Seoul")
    private val profile = gson.fromJson("""{"has_home":true,"home_lat":37.5,"home_lng":127.0}""", ProfileDto::class.java)
    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private val proposalJson = """"alarm_proposal": {"kind": "user_low_probability", "current_alarm_at": "2030-11-17T08:10:00+09:00",
      "proposed_alarm_at": "2030-11-17T07:54:00+09:00", "move_minutes": -16, "reason": "user_low_probability",
      "reason_text": "직접 정한 시각에 일어나면 정시 도착 확률이 46%예요", "keep_probability": 46,
      "change_probability": 97, "created_at": "2030-11-15T21:41:27.363226+09:00",
      "answer_by": "2030-11-17T07:44:00+09:00"}"""

    private fun event(proposal: Boolean) = gson.fromJson(
        """{"id":7,"title":"면접","start_at":"2030-11-17T10:00:00+09:00","source":"manual","alarm_enabled":true,"alarm_on":true,
        "place":{"name":"센터필드","lat":37.6,"lng":127.1},
        "alarm_plan":{"status":"ok","alarm_at":"2030-11-17T07:54:00+09:00","effective_alarm_at":"2030-11-17T08:10:00+09:00",
        "alarm_source":"user","prep_minutes":30${if (proposal) ",$proposalJson" else ""}}}""",
        EventDto::class.java,
    )

    @Test
    fun `서버 제안을 화면 · 기기용으로 읽는다`() {
        val p = event(true).toUpcoming(zone)!!.proposal!!
        assertEquals(ms("2030-11-17T08:10:00+09:00"), p.currentAtMillis)
        assertEquals(ms("2030-11-17T07:54:00+09:00"), p.proposedAtMillis)
        assertEquals("2030-11-17T07:54:00+09:00", p.proposedAt)
        assertEquals(-16, p.moveMinutes)
        assertEquals(ms("2030-11-17T07:44:00+09:00"), p.answerByMillis)
        assertEquals(46, p.keepProbability)
        assertEquals(event(true).toPlanView(zone)!!.proposal, p)
    }

    @Test
    fun `제안이 없거나 구버전 서버면 null 이고 기기 사본에도 없다`() {
        assertNull(event(false).toUpcoming(zone)!!.proposal)
        val s = event(false).toSchedule(zone, profile)!!
        assertNull(s.proposedAlarmAtMillis)
        assertNull(s.proposalAnswerByMillis)
    }

    @Test
    fun `기기 사본은 지금 울릴 시각에 걸고 제안 시각과 마감을 서버 값 그대로 함께 둔다`() {
        val s = event(true).toSchedule(zone, profile)!!
        assertEquals(ms("2030-11-17T08:10:00+09:00"), s.alarmAtMillis) // 답할 때까지 지금 시각에 울린다
        assertEquals(ms("2030-11-17T07:54:00+09:00"), s.proposedAlarmAtMillis)
        assertEquals(ms("2030-11-17T07:44:00+09:00"), s.proposalAnswerByMillis) // 앱이 계산하지 않는다
        assertEquals("직접 정한 시각에 일어나면 정시 도착 확률이 46%예요", s.proposalReason)
    }

    @Test
    fun `버튼 · 확률 문구`() {
        val p = event(true).toUpcoming(zone)!!.proposal!!
        assertEquals("오전 7:54로 앞당기기", AlarmProposalRule.acceptLabel(p, zone))
        assertEquals("오전 8:20으로 늦추기", AlarmProposalRule.acceptLabel(p.copy(moveMinutes = 10, proposedAtMillis = ms("2030-11-17T08:20:00+09:00")), zone))
        // 90% 상한 규칙
        assertEquals("그대로 두면 정시 도착 확률 46% · 바꾸면 90%", AlarmProposalRule.probabilityLine(p))
        assertNull(AlarmProposalRule.probabilityLine(p.copy(keepProbability = null, changeProbability = null)))
    }

    @Test
    fun `마감에 답이 없으면 더 이른 쪽으로 - 이미 지났거나 1분도 안 남았으면 지금 시각을 지킨다`() {
        val current = ms("2030-11-17T08:10:00+09:00")
        val earlier = ms("2030-11-17T07:54:00+09:00")
        val later = ms("2030-11-17T08:20:00+09:00")
        val deadline = ms("2030-11-17T07:44:00+09:00")
        assertEquals(earlier, AlarmProposalRule.settledAlarm(current, earlier, deadline))
        assertEquals("늦추는 제안은 지금 시각을 지킨다", current, AlarmProposalRule.settledAlarm(current, later, current - 10 * 60_000L))
        assertEquals("제안 시각이 1분도 안 남았으면 바꾸지 않는다", current, AlarmProposalRule.settledAlarm(current, earlier, earlier - 30_000L))
        assertEquals(current, AlarmProposalRule.settledAlarm(current, earlier, earlier + 60_000L))
    }

    @Test
    fun `마감 알림 문구`() {
        assertEquals(
            "답이 없어 알람을 오전 7:54로 바꿨어요. 이동 시간이 12분 늘었어요",
            AlarmProposalRule.settledNotice(ms("2030-11-17T07:54:00+09:00"), "이동 시간이 12분 늘었어요", zone),
        )
    }

    @Test
    fun `추천 칸은 서버 recommended_key 그대로 - 없거나 세 칸에 없으면 표시 안 함`() {
        fun view(extra: String) = gson.fromJson(
            """{"options":[{"key":"safe","label":"안전","tau":0.97,"alarm_at":"2030-11-17T07:40:00+09:00"},
            {"key":"normal","label":"보통","tau":0.9,"alarm_at":"2030-11-17T07:45:00+09:00"},
            {"key":"gamble","label":"도박","tau":0.65,"alarm_at":"2030-11-17T07:50:00+09:00"}],"selected_key":"normal"$extra}""",
            RiskOptionsDto::class.java,
        ).toView(zone)
        assertEquals("safe", view(""","recommended_key":"safe"""").recommendedKey)
        assertNull("구버전 서버(필드 없음)", view("").recommendedKey)
        assertNull(view(""","recommended_key":null""").recommendedKey)
        assertNull(view(""","recommended_key":"unknown"""").recommendedKey)
    }
}
