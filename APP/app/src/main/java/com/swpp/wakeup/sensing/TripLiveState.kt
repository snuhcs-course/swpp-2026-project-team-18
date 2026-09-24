package com.swpp.wakeup.sensing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 추적 중인 여정의 현재 상태. 서비스가 쓰고 화면이 읽는다.
 *
 * ## 왜 이런 것이 필요한가
 *
 * [TripTrackingService] 가 받는 위치는 **판정·알림·서버** 세 곳으로만 갔다.
 * `onBind` 가 null 이고 브로드캐스트도 Flow 도 없어서, 화면은 추적 중에도
 * 사용자가 어디쯤인지 알 방법이 없었다. 그래서 진행률을 그릴 수 없었다.
 *
 * ## 왜 싱글턴인가
 *
 * 서비스와 화면이 **같은 프로세스**에 있다. 바인더나 브로드캐스트는 프로세스
 * 경계를 넘기 위한 장치이고, 넘을 경계가 없는데 쓰면 직렬화와 생명주기 관리만
 * 늘어난다. 프로세스가 죽으면 추적도 함께 죽으므로 상태를 잃어도 맞다 —
 * 남겨야 하는 것은 이미 서버에 올라간 관측이다.
 *
 * ## 무엇을 담지 않는가
 *
 * 좌표를 디스크에 쓰지 않는다. 이동 경로는 민감 정보이고, 화면에 그리는 데는
 * 지금 위치 하나로 충분하다. 앱이 꺼지면 사라지는 것이 의도된 동작이다.
 */
object TripLiveState {

    /**
     * 추적 중인 여정 하나의 상태.
     *
     * [eventId] 를 함께 두는 이유는 화면이 **다른 일정**을 보고 있을 수 있어서다.
     * 일정 A 를 추적하는 중에 일정 B 의 알람 결정 화면을 열면, 그 화면은 B 의
     * 진행률을 그릴 근거가 없다. id 를 비교해 걸러야 한다.
     */
    data class Snapshot(
        val eventId: Long,
        val point: GeoPoint,
        /** 이 위치의 GPS 오차(m). 클수록 진행률을 덜 믿어야 한다 */
        val accuracyM: Float,
        /** 이 위치를 받은 시각(벽시계 ms). "몇 분 전 갱신" 에 쓴다 */
        val atMillis: Long,
        val phase: TripGeofence.Phase,
        /** 목적지 반경 안에서 체류를 채우는 중인가 */
        val awaitingDwell: Boolean = false,
    )

    private val _snapshot = MutableStateFlow<Snapshot?>(null)

    /** null 이면 추적 중이 아니다. 화면은 그때 진행률 대신 안내를 띄운다 */
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    /** 서비스가 위치를 받을 때마다 부른다. */
    fun publish(snapshot: Snapshot) {
        _snapshot.value = snapshot
    }

    /**
     * 추적이 끝났다.
     *
     * 도착했든 마감이든 중지든 **반드시 부른다.** 남겨 두면 화면이 옛 위치를
     * 현재처럼 그리고, 사용자는 몇 시간 전 좌표를 보며 판단한다.
     */
    fun clear() {
        _snapshot.value = null
    }

    /** 이 일정을 추적하는 중인 경우에만 위치를 준다. */
    fun pointFor(eventId: Long): Snapshot? = _snapshot.value?.takeIf { it.eventId == eventId }
}
