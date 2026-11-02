package com.swpp.wakeup.data.local

import android.content.Context
import androidx.core.content.edit
import com.swpp.wakeup.domain.model.ArrivalLedger

// Partly AI-generated (Claude)
/**
 * 이 기기가 판정·기록한 도착(일정 id → 도착 시각). todo §2 "앱 재시작 후 도착 판정 복원".
 *
 * 앱 프로세스가 다시 시작돼도 이미 도착한 일정이 홈에 남지 않고, 알람 결정 화면이 "도착" 에
 * 머물게 한다. 규칙(보관 3일·30건, 이른 도착 우선)은 [ArrivalLedger] 에 있다.
 *
 * 좌표는 남기지 않는다. 다른 저장소처럼 소유자를 붙이고([editOwned], [discardIfForeign])
 * 로그아웃·로그인에서 [LocalStores.wipeAll] 이 지운다. 백업에서도 뺀다.
 */
class ArrivalStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 남아 있는 도착. 보관 기간이 지난 것은 뺀다 */
    fun all(nowMillis: Long = System.currentTimeMillis()): Map<Long, Long> {
        if (prefs.discardIfForeign(appContext)) return emptyMap()
        return ArrivalLedger.prune(ArrivalLedger.decode(prefs.getString(KEY_ARRIVALS, null)), nowMillis)
    }

    /** 도착을 더한다. 로그아웃 상태면 쓰지 않고 false 다 */
    fun record(eventId: Long, atMillis: Long, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (eventId <= 0L || atMillis <= 0L) return false
        val next = ArrivalLedger.add(all(nowMillis), eventId, atMillis, nowMillis)
        return prefs.editOwned(appContext) { putString(KEY_ARRIVALS, ArrivalLedger.encode(next)) }
    }

    /** 한 일정의 도착을 잊는다. 같은 일정을 다시 추적하기 시작할 때 쓴다 */
    fun remove(eventId: Long, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val current = all(nowMillis)
        if (eventId !in current) return true
        return prefs.editOwned(appContext) { putString(KEY_ARRIVALS, ArrivalLedger.encode(current - eventId)) }
    }

    /** 소유자 표시까지 전부 지운다. **로그아웃·계정 전환에서 부른다.** */
    fun wipe() {
        prefs.edit { clear() }
    }

    private companion object {
        const val FILE = "jit_arrivals"
        const val KEY_ARRIVALS = "arrivals"
    }
}
