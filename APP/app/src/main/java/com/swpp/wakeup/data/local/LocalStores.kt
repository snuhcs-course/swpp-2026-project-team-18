package com.swpp.wakeup.data.local

import android.content.Context
import android.util.Log
import com.swpp.wakeup.alarm.AlarmScheduler
import com.swpp.wakeup.alarm.ScheduledAlarmStore
import com.swpp.wakeup.sensing.BlockObservationQueue
import com.swpp.wakeup.sensing.TripObservationQueue

/**
 * 기기에 남는 앱 자체 저장소를 한자리에서 지운다.
 *
 * ## 왜 모아 두는가
 *
 * 로그아웃에서 지워야 하는 곳이 계속 늘어났다. Room 캐시, 토큰, 알람 사본, 관측
 * 큐 둘, 아침 기록. **새 저장소를 추가하면서 지우는 쪽을 빠뜨리는 것이 기본
 * 실수다** — 실제로 아침 기록과 블록 관측 큐가 그렇게 빠졌고, 계정을 바꾼
 * 사용자가 앞 사람의 블록 이름을 보게 되어 있었다.
 *
 * 한 함수로 모으면 목록이 눈에 보인다. 조회 쪽 소유자 격리
 * ([isForeignOwner])가 노출은 막지만, 그것은 **보이지 않게 하는 것**이고 기기에
 * 남는 것은 그대로다. 둘 다 필요하다.
 *
 * Room 캐시는 여기 넣지 않았다. 지우는 데 코루틴이 필요하고 화면보다 오래 살아야
 * 해서 [OfflineCache.wipeDetached] 가 따로 다룬다.
 */
object LocalStores {

    private const val TAG = "LocalStores"

    /**
     * 앱이 만든 로컬 데이터를 전부 지운다.
     *
     * **토큰을 지우기 전에 부른다.** 소유자 판정이 "지금 로그인한 계정" 을
     * 보므로, 토큰이 먼저 사라지면 소유자를 모르는 상태가 된다.
     *
     * 등록된 알람도 함께 취소한다. 사본만 지우고 `PendingIntent` 를 두면
     * **로그아웃한 계정의 알람이 그대로 울린다** — 사본이 없으니 그 알람이
     * 무엇인지 앱도 설명할 수 없다.
     */
    fun wipeAll(context: Context) {
        val app = context.applicationContext

        runCatching {
            // 해제가 먼저다. cancelAll 이 사본을 읽어 어떤 PendingIntent 를
            // 지울지 정하므로, 사본을 먼저 비우면 알람이 살아남는다.
            val cancelled = AlarmScheduler(app).cancelAll()
            ScheduledAlarmStore(app).wipe()
            Log.i(TAG, "로그아웃: 알람 ${cancelled}건 해제 + 사본 삭제")
        }.onFailure { Log.w(TAG, "알람 사본 삭제 실패", it) }

        runCatching { TripObservationQueue(app).wipe() }
            .onFailure { Log.w(TAG, "이동 관측 큐 삭제 실패", it) }

        runCatching { BlockObservationQueue(app).wipe() }
            .onFailure { Log.w(TAG, "블록 관측 큐 삭제 실패", it) }

        runCatching { MorningSessionStore(app).wipe() }
            .onFailure { Log.w(TAG, "아침 기록 삭제 실패", it) }
    }
}
