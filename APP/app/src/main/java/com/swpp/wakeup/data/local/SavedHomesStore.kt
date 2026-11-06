package com.swpp.wakeup.data.local

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import com.swpp.wakeup.domain.model.SavedHome

// Partly AI-generated (Claude)
/**
 * 기본 집 말고 이 기기에 저장한 다른 집 목록([SavedHome], todo §2 "집 여러 개").
 *
 * 서버에 집 여러 개(`SavedPlace`)가 생기기 전까지 쓰는 기기 사본이다. 집 좌표는 민감하므로 다른
 * 저장소처럼 소유자를 붙이고([editOwned], [discardIfForeign]) [LocalStores.wipeAll] 이 지운다.
 * 백업에서도 뺀다 — 다른 기기로 옮겨 가면 그 기기의 계정과 섞인다.
 */
class SavedHomesStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun all(): List<SavedHome> {
        if (prefs.discardIfForeign(appContext)) return emptyList()
        val raw = prefs.getString(KEY_HOMES, null) ?: return emptyList()
        val parsed: List<SavedHome?>? = try {
            gson.fromJson(raw, object : TypeToken<List<SavedHome?>>() {}.type)
        } catch (e: JsonParseException) {
            null
        }
        // Gson 은 Kotlin 의 non-null 을 검사하지 않는다. 깨진 항목은 버린다.
        return parsed.orEmpty().filterNotNull().filter { runCatching { it.isUsable }.getOrDefault(false) }
    }

    /** 목록을 통째로 바꾼다. 로그아웃 상태면 쓰지 않고 false 다 */
    fun replace(homes: List<SavedHome>): Boolean =
        prefs.editOwned(appContext) { putString(KEY_HOMES, gson.toJson(homes)) }

    // Partly AI-generated (Claude)
    /**
     * 서버가 이 목록을 확인했는가(B2). false 인 동안 목록은 **서버에 올릴 기기 사본**이고, true 가 된 뒤에는
     * 서버 목록의 사본이다. 다른 계정의 저장소면 false 다.
     */
    fun synced(): Boolean {
        if (prefs.discardIfForeign(appContext)) return false
        return prefs.getBoolean(KEY_SYNCED, false)
    }

    /** 서버가 확인한 목록으로 바꾸고 [synced] 를 true 로 한다. 같은 트랜잭션이라 둘이 어긋나지 않는다 */
    fun replaceSynced(homes: List<SavedHome>): Boolean =
        prefs.editOwned(appContext) {
            putString(KEY_HOMES, gson.toJson(homes))
            putBoolean(KEY_SYNCED, true)
        }

    /** 소유자 표시까지 전부 지운다. **로그아웃·계정 전환에서 부른다.** */
    fun wipe() {
        prefs.edit { clear() }
    }

    private companion object {
        const val FILE = "jit_saved_homes"
        const val KEY_HOMES = "homes"
        const val KEY_SYNCED = "synced"
    }
}
