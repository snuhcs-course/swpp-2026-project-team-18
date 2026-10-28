package com.swpp.wakeup.alarm

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.swpp.wakeup.data.local.discardIfForeign
import com.swpp.wakeup.data.local.stampOwner
import com.swpp.wakeup.data.local.withDiskDefaults
import com.swpp.wakeup.domain.model.AlarmSchedule

/**
 * 등록한 알람의 디스크 사본.
 *
 * **왜 필요한가.** `PendingIntent` 는 프로세스가 아니라 시스템에 남지만
 * 재부팅하면 전부 사라진다. 그때 알람을 다시 등록해야 하는데
 * [BootReceiver] 는 서버에 닿을 수 없다 — 네트워크가 아직 없고 토큰이
 * 만료됐을 수도 있다. 그래서 "무엇을 언제 울려야 하는지" 를 로컬에 적어 둔다.
 *
 * Room 을 쓰지 않는다. 저장할 것이 목록 하나이고 질의가 없다. 스키마·KSP·
 * 마이그레이션을 들이는 비용이 얻는 것보다 크다. Room 이 들어오면
 * (FE-P1-01) 이 클래스는 DAO 로 대체된다.
 */
class ScheduledAlarmStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 등록해 둔 알람 전부. 순서는 알람 시각 오름차순. */
    fun all(): List<AlarmSchedule> {
        // 다른 계정이 로그인해 있으면 앞 사용자의 알람을 울리지 않는다. 잠금화면
        // 위에 남의 일정 제목이 뜨는 일이 생긴다.
        //
        // **로그인 정보가 없을 때는 막지 않는다.** refresh 토큰이 만료되면
        // AuthInterceptor 가 토큰을 지우는데, 그때 막으면 세션이 끊긴 사용자의
        // 알람이 재부팅 뒤 울리지 않는다. 9시 수업은 그대로 있다.
        if (prefs.discardIfForeign(appContext)) return emptyList()

        val raw = prefs.getString(KEY_SCHEDULES, null) ?: return emptyList()
        return try {
            gson.fromJson<List<AlarmSchedule>>(raw, TYPE).orEmpty()
                // 지우지 말 것. 구버전이 저장한 JSON 에는 뒤에 추가된 필드의
                // 키가 없고, Gson 은 그 자리를 Kotlin 기본값이 아니라 null 로
                // 남긴다. 이유는 [withDiskDefaults] 에 적어 뒀다.
                .map { it.withDiskDefaults() }
                .sortedBy { it.alarmAtMillis }
        } catch (e: JsonSyntaxException) {
            // 모양이 바뀌었으면 버린다. 다음 서버 동기화가 다시 채운다.
            Log.w(TAG, "저장된 알람을 읽을 수 없어 버린다", e)
            prefs.edit { remove(KEY_SCHEDULES) }
            emptyList()
        }
    }

    fun replaceAll(schedules: List<AlarmSchedule>) {
        prefs.edit {
            putString(KEY_SCHEDULES, gson.toJson(schedules.sortedBy { it.alarmAtMillis }))
        }
        prefs.stampOwner(appContext)
    }

    /** 사본을 통째로 비운다. **로그아웃·계정 전환에서 부른다.** */
    fun wipe() {
        prefs.edit { clear() }
    }

    fun find(eventId: Long): AlarmSchedule? = all().firstOrNull { it.eventId == eventId }

    /**
     * 한 건만 갈아 끼운다. 미루기가 새 시각으로 다시 쓸 때 쓴다.
     *
     * 없던 일정이면 추가한다 — 미루기 중에 서버 동기화가 목록을 갈아
     * 치웠더라도 미룬 알람을 잃지 않는다.
     */
    fun upsert(schedule: AlarmSchedule) {
        val next = all().filterNot { it.eventId == schedule.eventId } + schedule
        replaceAll(next)
    }

    fun remove(eventId: Long) {
        replaceAll(all().filterNot { it.eventId == eventId })
    }

    // Partly AI-generated (Claude) — 놓친 알람 판별([MissedAlarms])에 쓰는 두 값.

    /**
     * 처리한 알람의 키([MissedAlarms.key]). 울렸거나 사용자가 "놓쳤다" 안내를 닫은 것.
     *
     * 사본만으로는 울린 알람과 사라진 알람을 구별할 수 없다. 둘 다 "시각이 지난
     * 항목" 으로 남는다.
     */
    fun handledKeys(): Set<String> {
        if (prefs.discardIfForeign(appContext)) return emptySet()
        // getStringSet 이 돌려준 집합은 고치면 안 된다. 복사해서 넘긴다.
        return prefs.getStringSet(KEY_HANDLED, null)?.toSet().orEmpty()
    }

    fun markHandled(schedule: AlarmSchedule, nowMillis: Long = System.currentTimeMillis()) {
        val next = MissedAlarms.pruneHandled(handledKeys(), nowMillis) + MissedAlarms.key(schedule)
        prefs.edit { putStringSet(KEY_HANDLED, next) }
        prefs.stampOwner(appContext)
    }

    /**
     * 사본에서 빠졌지만 아직 알려야 하는 놓친 알람.
     *
     * 서버 동기화와 재부팅 복원은 지난 알람을 사본에서 지운다. 놓친 알람을 그
     * 자리에 두면 안내를 띄우기 전에 사라지므로 여기로 옮겨 둔다.
     */
    fun missedCopies(): List<AlarmSchedule> {
        if (prefs.discardIfForeign(appContext)) return emptyList()
        val raw = prefs.getString(KEY_MISSED, null) ?: return emptyList()
        return try {
            gson.fromJson<List<AlarmSchedule>>(raw, TYPE).orEmpty().map { it.withDiskDefaults() }
        } catch (e: JsonSyntaxException) {
            Log.w(TAG, "놓친 알람 사본을 읽을 수 없어 버린다", e)
            prefs.edit { remove(KEY_MISSED) }
            emptyList()
        }
    }

    fun replaceMissed(schedules: List<AlarmSchedule>) {
        prefs.edit {
            if (schedules.isEmpty()) remove(KEY_MISSED)
            else putString(KEY_MISSED, gson.toJson(schedules.sortedBy { it.alarmAtMillis }))
        }
        prefs.stampOwner(appContext)
    }

    private companion object {
        const val TAG = "ScheduledAlarmStore"
        const val FILE = "jit_scheduled_alarms"
        const val KEY_SCHEDULES = "schedules"
        const val KEY_HANDLED = "handled"
        const val KEY_MISSED = "missed"
        val TYPE = object : TypeToken<List<AlarmSchedule>>() {}.type
    }
}
