// Partly AI-generated (Claude)
package com.swpp.wakeup.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.swpp.wakeup.domain.model.AlarmSchedule

/**
 * 지금 추적 중인 일정의 사본(11-17 "죽과이" 알람: 추적 중에 앱이 강제 종료되자 다시 열어도 추적이 이어지지 않았다).
 *
 * 추적을 시작할 때 쓰고, 추적이 **정상으로** 끝날 때(도착 · 마감 · 사용자 중지 · 로그아웃 — 모두
 * `TripTrackingService.onDestroy` 를 지난다) 지운다. 강제 종료는 `onDestroy` 없이 프로세스를 죽이고 시스템도
 * 다시 전달하지 않으므로 이 사본이 남는다. 앱이 다시 열리면 이것으로 추적을 잇는다([com.swpp.wakeup.domain.model.TripResume]).
 */
class ActiveTripStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 남은 사본. 없거나 읽을 수 없으면 null */
    fun current(): AlarmSchedule? {
        if (prefs.discardIfForeign(appContext)) return null
        val raw = prefs.getString(KEY_SCHEDULE, null) ?: return null
        return try {
            gson.fromJson(raw, AlarmSchedule::class.java)?.withDiskDefaults()
        } catch (e: JsonParseException) {
            null
        }
    }

    /** 로그아웃 상태면 쓰지 않고 false 다 */
    fun save(schedule: AlarmSchedule): Boolean =
        prefs.editOwned(appContext) { putString(KEY_SCHEDULE, gson.toJson(schedule)) }

    /** 추적이 끝났다. [eventId] 를 주면 그 일정의 사본일 때만 지운다 */
    fun clear(eventId: Long? = null) {
        if (eventId != null && current()?.eventId != eventId) return
        prefs.edit().remove(KEY_SCHEDULE).apply()
    }

    /** 로그아웃 · 계정 전환 */
    fun wipe() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "jit_active_trip"
        const val KEY_SCHEDULE = "schedule"
    }
}
