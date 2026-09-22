package com.swpp.wakeup.data.local

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.domain.model.MorningBlock
import com.swpp.wakeup.domain.model.MorningSession

/**
 * 진행 중인 아침 기록을 디스크에 둔다.
 *
 * ## 왜 Room 이 아니라 SharedPreferences 인가
 *
 * 한 번에 **한 세션**만 존재한다. 질의할 것이 없고 조인할 것도 없다. Room 캐시
 * ([JitDatabase])는 파괴적 재생성을 쓰는데, 그 판단은 "서버 사본만 담는다" 에
 * 기대고 있다. 아침 기록은 **앱에서 생성되고 잃으면 복구할 수 없는** 데이터라
 * 같은 파일에 두면 그 판단이 데이터 손실 버그가 된다.
 *
 * [com.swpp.wakeup.sensing.TripObservationQueue] 와 같은 이유로 같은 방식이다.
 */
class MorningSessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * 진행 중인 세션. 없거나 너무 오래됐으면 null.
     *
     * 오래된 세션은 읽는 김에 지운다. 남겨 두면 다음 아침에 "어제 기록 계속하기"
     * 가 뜨고, 거기서 탭하면 소요가 18시간으로 올라가 학습을 망친다.
     */
    fun current(): MorningSession? {
        val raw = prefs.getString(KEY_SESSION, null) ?: return null
        val session = try {
            // withDiskDefaults 를 지우지 말 것. Gson 은 JSON 에 없는 키를 Kotlin
            // 기본값이 아니라 null 로 남긴다 — 이유는 [withDiskDefaults] 에 있다.
            gson.fromJson(raw, MorningSession::class.java)?.withDiskDefaults()
        } catch (e: JsonSyntaxException) {
            Log.w(TAG, "세션을 읽을 수 없어 버린다", e)
            clear()
            return null
        } ?: return null

        if (session.blocks.isEmpty()) {
            // start() 가 블록 없는 세션을 만들지 않으므로 이건 깨진 기록이다.
            // 빈 세션을 돌려주면 화면이 "할 일 없음" 을 띄워 사용자가 기록이
            // 끝난 줄 안다. 없는 것으로 다룬다.
            Log.w(TAG, "세션에 블록이 없다. 깨진 기록으로 보고 버린다")
            clear()
            return null
        }

        if (session.isStale()) {
            Log.i(TAG, "세션이 오래됐다(${session.eventId}). 버린다")
            clear()
            return null
        }
        return session
    }

    /**
     * 알람 해제 시점에 세션을 시작한다.
     *
     * 블록이 없으면 **세션을 만들지 않는다** — 기록할 것이 없는데 화면을 띄우면
     * 아침에 쓸데없는 단계가 하나 늘어난다.
     *
     * 이미 같은 일정의 세션이 있으면 그대로 둔다. 알람을 두 번 해제하는 경로
     * (미루기 뒤 재발화)에서 진행한 기록이 지워지면 안 된다.
     */
    fun start(schedule: AlarmSchedule, nowMillis: Long = System.currentTimeMillis()): MorningSession? {
        if (!schedule.canLogBlocks) {
            Log.i(TAG, "블록이 없어 아침 기록을 시작하지 않는다")
            return null
        }

        val existing = current()
        if (existing != null && existing.eventId == schedule.eventId) {
            Log.i(TAG, "이미 진행 중인 세션이 있다(${schedule.eventId}). 유지한다")
            return existing
        }

        val session = MorningSession(
            eventId = schedule.eventId,
            startedAtMillis = nowMillis,
            departByMillis = schedule.departByMillis,
            plannedPrepMinutes = schedule.prepMinutes,
            blocks = schedule.prepBlocks.map {
                MorningBlock(
                    blockId = it.blockId,
                    name = it.name,
                    plannedMinutes = it.plannedMinutes,
                    parallelizable = it.parallelizable,
                )
            },
        )
        save(session)
        Log.i(TAG, "아침 기록 시작: 일정 ${session.eventId}, 블록 ${session.blocks.size}개")
        return session
    }

    fun save(session: MorningSession) {
        prefs.edit { putString(KEY_SESSION, gson.toJson(session)) }
    }

    fun clear() {
        prefs.edit { remove(KEY_SESSION) }
    }

    private companion object {
        const val TAG = "MorningSessionStore"
        const val FILE = "jit_morning_session"
        const val KEY_SESSION = "session"
    }
}
