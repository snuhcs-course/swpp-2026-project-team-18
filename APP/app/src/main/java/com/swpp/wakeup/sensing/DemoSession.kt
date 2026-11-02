package com.swpp.wakeup.sensing

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.swpp.wakeup.domain.model.AlarmSchedule

// Partly AI-generated (Claude)
/**
 * HE(human evaluation) 시연 중인 일정 표시. **기기에만 둔다. main 에 넣지 않는다.**
 *
 * 시연 버튼으로 진행한 것은 서버 DB 와 학습에 닿지 않아야 한다. 그래서 두 가지를 한다.
 *
 * - **시간 빨리감기는 서버 일정을 옮기지 않는다.** 일정별로 당긴 차이만 여기에 적고,
 *   [com.swpp.wakeup.alarm.AlarmScheduler.sync] 가 서버 계획에 그 차이를 더해 알람을 건다.
 *   홈 동기화가 몇 번 돌아도 시연 알람이 원래 시각으로 돌아가지 않는다.
 * - **기록을 큐에 넣지 않는다.** 알람·이동·블록 기록 큐가 [isDemo] 인 일정의 것을 버린다.
 *   큐에 안 들어가면 서버로 갈 길이 없다.
 *
 * 표시는 도착하면 지운다([finish]). 도착까지 안 가면 [expiresAtMillis][Entry.expiresAtMillis]
 * 가 지나 저절로 사라진다. 그 뒤로는 원래 알람과 기록으로 돌아간다.
 */
class DemoSession(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 지금 시연 중인 일정인가. 일정이 없는 기록(null)은 시연이 아니다 */
    fun isDemo(eventId: Long?, nowMillis: Long = System.currentTimeMillis()): Boolean =
        eventId != null && entries(nowMillis).any { it.eventId == eventId }

    /**
     * [schedule] (서버 계획 그대로)의 알람이 [DemoTrip.FAST_FORWARD_LEAD_MILLIS] 뒤에 울리도록
     * 당긴다. 당긴 알람의 마감까지 시연으로 본다.
     */
    fun fastForward(schedule: AlarmSchedule, nowMillis: Long = System.currentTimeMillis()) {
        val delta = DemoTrip.fastForwardDelta(schedule.alarmAtMillis, nowMillis)
        val shifted = DemoTrip.shift(schedule, delta)
        put(Entry(schedule.eventId, delta, shifted.trackingDeadlineMillis), nowMillis)
    }

    /** 이동하기를 눌렀다. 빨리감기 없이 눌렀어도 이 일정의 기록은 올리지 않는다 */
    fun markMoving(eventId: Long, nowMillis: Long = System.currentTimeMillis()) {
        val existing = entries(nowMillis).firstOrNull { it.eventId == eventId }
        val expiresAt = maxOf(existing?.expiresAtMillis ?: 0L, nowMillis + MOVE_TTL_MILLIS)
        put(Entry(eventId, existing?.deltaMillis ?: 0L, expiresAt), nowMillis)
    }

    /** 서버 계획에 당긴 차이를 더한다. 시연 중이 아닌 일정은 그대로다 */
    fun applyTo(
        schedules: List<AlarmSchedule>,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<AlarmSchedule> {
        val deltas = entries(nowMillis).associate { it.eventId to it.deltaMillis }
        if (deltas.isEmpty()) return schedules
        return schedules.map { schedule ->
            deltas[schedule.eventId]?.let { DemoTrip.shift(schedule, it) } ?: schedule
        }
    }

    /** 시연이 끝났다. 다음 동기화부터 서버 계획대로 알람이 걸린다 */
    fun finish(eventId: Long, nowMillis: Long = System.currentTimeMillis()) {
        write(entries(nowMillis).filterNot { it.eventId == eventId })
    }

    fun wipe() {
        prefs.edit { remove(KEY_ENTRIES) }
    }

    private fun put(entry: Entry, nowMillis: Long) {
        write(entries(nowMillis).filterNot { it.eventId == entry.eventId } + entry)
    }

    private fun entries(nowMillis: Long): List<Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        val all = try {
            gson.fromJson(raw, Array<Entry>::class.java)?.toList().orEmpty()
        } catch (_: JsonSyntaxException) {
            emptyList()
        }
        return all.filter { it.expiresAtMillis > nowMillis }
    }

    private fun write(entries: List<Entry>) {
        prefs.edit { putString(KEY_ENTRIES, gson.toJson(entries)) }
    }

    private data class Entry(
        val eventId: Long,
        val deltaMillis: Long,
        val expiresAtMillis: Long,
    )

    private companion object {
        const val FILE = "jit_he_demo"
        const val KEY_ENTRIES = "entries"

        /** 빨리감기 없이 이동하기만 눌렀을 때 시연으로 보는 시간 */
        const val MOVE_TTL_MILLIS = 60 * 60 * 1000L
    }
}
