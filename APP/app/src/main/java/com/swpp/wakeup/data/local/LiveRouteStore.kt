package com.swpp.wakeup.data.local

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.swpp.wakeup.domain.model.LiveRoute
import com.swpp.wakeup.domain.model.LiveRouteDecision
import com.swpp.wakeup.sensing.GeoPoint

/**
 * 포그라운드 추적 서비스가 받은 최신 현재-위치 경로의 디스크 사본.
 *
 * 서비스와 알람 결정 화면의 수명은 다르다. 사용자가 다른 앱을 보는 동안 화면
 * ViewModel 이 정리돼도 서비스는 계속 경로를 갱신하고, 돌아온 화면은 이 사본을
 * 즉시 읽어 최신 성공 결과를 그린다. 한 번에 추적하는 여정이 하나뿐이라 단일
 * 슬롯이면 충분하다.
 *
 * 좌표와 이동 경로는 민감 정보다. 계정 소유자와 같은 트랜잭션으로 저장하고,
 * 로그아웃 때 지우며, Android 백업에서도 제외한다. 성공 뒤 오래 남지 않도록
 * 읽을 때 [LiveRouteDecision.MAX_USABLE_AGE_MILLIS]가 지난 사본도 폐기한다.
 */
class LiveRouteStore(context: Context) {

    data class Snapshot(
        val eventId: Long,
        /** 이 경로를 요청할 때의 현재 위치. */
        val origin: GeoPoint,
        /** 서버 응답을 받은 벽시계 시각. */
        val fetchedAtMillis: Long,
        val route: LiveRoute,
    ) {
        fun isFresh(nowMillis: Long = System.currentTimeMillis()): Boolean =
            nowMillis >= fetchedAtMillis &&
                nowMillis - fetchedAtMillis <= LiveRouteDecision.MAX_USABLE_AGE_MILLIS
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * 새 추적 세션의 쓰기를 연다. 인증 사용자가 없으면 열지 않는다.
     * [wipe] 뒤 늦게 끝난 옛 네트워크 응답이 사본을 되살리지 못하게 하는 장벽이다.
     */
    fun beginSession(): Boolean {
        synchronized(LOCK) {
            if (!TokenStore(appContext).isLoggedIn) return false
            return prefs.edit().remove(KEY_WRITES_BLOCKED).commit()
        }
    }

    fun current(
        eventId: Long? = null,
        nowMillis: Long = System.currentTimeMillis(),
    ): Snapshot? {
        synchronized(LOCK) {
            if (prefs.getBoolean(KEY_WRITES_BLOCKED, false)) return null
            return currentUnlocked(eventId, nowMillis)
        }
    }

    private fun currentUnlocked(eventId: Long?, nowMillis: Long): Snapshot? {
        if (prefs.discardIfForeign(appContext)) return null
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        val snapshot = try {
            gson.fromJson(raw, Snapshot::class.java)
        } catch (e: JsonSyntaxException) {
            Log.w(TAG, "실시간 경로 사본을 읽을 수 없어 버린다", e)
            clear()
            return null
        } ?: return null

        val valid = runCatching {
            snapshot.eventId > 0L &&
                snapshot.origin.lat.isFinite() && snapshot.origin.lng.isFinite() &&
                snapshot.fetchedAtMillis > 0L &&
                snapshot.route.minutes > 0 && snapshot.route.path.size >= 2 &&
                snapshot.route.path.all { it.lat.isFinite() && it.lng.isFinite() }
        }.getOrDefault(false)

        if (!valid) {
            Log.w(TAG, "실시간 경로 사본이 깨져 있어 버린다")
            clear()
            return null
        }
        if (!snapshot.isFresh(nowMillis)) {
            Log.i(TAG, "실시간 경로 사본이 오래되어 버린다")
            clear()
            return null
        }
        return snapshot.takeIf { eventId == null || it.eventId == eventId }
    }

    /**
     * 로그인 소유자와 함께 저장했으면 true. 로그아웃 상태면 쓰지 않고 false.
     *
     * [afterCommit]은 로그아웃 [wipe]와 같은 잠금 안에서 실행된다. 서비스가
     * 메모리 Flow를 게시하는 찰나에 wipe가 끼어, 지운 뒤 옛 경로가 다시
     * 나타나는 경합을 막기 위한 것이다. 콜백은 짧고 블로킹하지 않아야 한다.
     */
    fun save(snapshot: Snapshot, afterCommit: () -> Unit = {}): Boolean {
        synchronized(LOCK) {
            if (prefs.getBoolean(KEY_WRITES_BLOCKED, false)) return false
            // 좌표와 소유자 중 하나만 남는 창이 없도록 한 번에 commit 한다.
            val saved = prefs.editOwned(appContext, commit = true) {
                putString(KEY_SNAPSHOT, gson.toJson(snapshot))
            }
            if (saved) afterCommit()
            return saved
        }
    }

    /** 지정한 일정의 사본만 지운다. null 이면 현재 슬롯을 지운다. */
    fun clear(eventId: Long? = null) {
        synchronized(LOCK) {
            if (eventId != null && currentUnlocked(null, System.currentTimeMillis())?.eventId != eventId) {
                return
            }
            prefs.edit(commit = true) { remove(KEY_SNAPSHOT) }
        }
    }

    /** 로그아웃·계정 전환용. 소유자 표식까지 지운다. */
    fun wipe() {
        synchronized(LOCK) {
            // clear와 장벽을 한 트랜잭션으로 쓴다. 옛 서비스 응답은 이 뒤 save가
            // 호출돼도 KEY_WRITES_BLOCKED를 보고 거절된다.
            prefs.edit(commit = true) {
                clear()
                putBoolean(KEY_WRITES_BLOCKED, true)
            }
        }
    }

    private companion object {
        const val TAG = "LiveRouteStore"
        const val FILE = "jit_live_route"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_WRITES_BLOCKED = "writes_blocked"
        val LOCK = Any()
    }
}
