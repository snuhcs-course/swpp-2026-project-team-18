package com.swpp.wakeup.data.local

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.swpp.wakeup.domain.model.TripProgress

// Partly AI-generated (Claude)
/**
 * 이동 중인 추적의 단계·이동 시작 시각([TripProgress]). todo §2 "추적 서비스 재시작 복원".
 *
 * 추적 서비스만 쓰고 읽는다. 하나만 둔다 — 한 번에 하나의 이동만 추적한다. 서비스가 정상으로
 * 끝나면(도착·마감·중지·로그아웃) 지우고, 프로세스가 죽어 다시 전달된 시작에서만 읽는다.
 *
 * 다른 저장소처럼 소유자를 붙이고([editOwned], [discardIfForeign]) [LocalStores.wipeAll] 이
 * 지운다. 백업에서도 뺀다.
 */
class TripProgressStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** [eventId] 의 추적을 이어 갈 사본. 없거나 쓸 수 없으면 null */
    fun current(eventId: Long, nowMillis: Long = System.currentTimeMillis()): TripProgress? {
        if (prefs.discardIfForeign(appContext)) return null
        val raw = prefs.getString(KEY_PROGRESS, null) ?: return null
        val parsed = try {
            gson.fromJson(raw, TripProgress::class.java)
        } catch (e: JsonParseException) {
            null
        }
        return parsed?.takeIf { it.usableFor(eventId, nowMillis) }
    }

    /** 로그아웃 상태면 쓰지 않고 false 다 */
    fun save(progress: TripProgress): Boolean =
        prefs.editOwned(appContext) { putString(KEY_PROGRESS, gson.toJson(progress)) }

    /** 이동이 끝났다. 소유자 표시는 남긴다 */
    fun clear() {
        prefs.edit { remove(KEY_PROGRESS) }
    }

    /** 소유자 표시까지 전부 지운다. **로그아웃·계정 전환에서 부른다.** */
    fun wipe() {
        prefs.edit { clear() }
    }

    private companion object {
        const val FILE = "jit_trip_progress"
        const val KEY_PROGRESS = "progress"
    }
}
