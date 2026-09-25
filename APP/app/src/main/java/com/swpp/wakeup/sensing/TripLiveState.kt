package com.swpp.wakeup.sensing

import com.swpp.wakeup.data.local.LiveRouteStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
 * 늘어난다. 프로세스가 죽으면 위치 Flow 는 사라지고, 재전달된 서비스가 다음
 * fix로 복구한다. 화면 복귀에 필요한 마지막 성공 경로만 [LiveRouteStore]에
 * 짧게 보존한다.
 *
 * ## 무엇을 담지 않는가
 *
 * 10초마다 오는 원시 위치와 이동 이력은 디스크에 쓰지 않는다. 이동 경로는
 * 민감 정보이므로 마지막 성공 결과 하나만 계정별로 최대 5분 보존하고,
 * 로그아웃·추적 종료 때 지우며 Android 백업에서도 제외한다.
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
        /**
         * 이동이 시작된 것으로 판정한 시각(벽시계 ms). 아직 준비 중이면 null.
         *
         * **실시간 도착 예정의 유일한 재료다.** 화면은 지금 위치를 경로에 투영해
         * 이동 거리를 얻고, 이 값으로 경과 시간을 얻어 관측 속도를 낸다
         * ([com.swpp.wakeup.domain.model.TripEta]).
         *
         * 서비스가 들고 있어야 하는 이유는 **화면이 닫혀 있어도 이동은 계속되기**
         * 때문이다. 화면에서 처음 본 좌표를 시작점으로 삼으면, 30분을 이동한
         * 뒤에 화면을 연 사용자가 "방금 출발했다" 로 계산된다.
         */
        val movingSinceMillis: Long? = null,
    )

    private val _snapshot = MutableStateFlow<Snapshot?>(null)

    /**
     * 추적 서비스가 백그라운드에서 마지막으로 성공한 현재-위치 경로.
     *
     * 위치와 별도 Flow 인 이유는 위치가 10초마다 오고 경로는 최대 1분마다
     * 바뀌기 때문이다. 한 Flow 로 합치면 위치가 올 때마다 같은 큰 경로 목록을
     * 다시 내보내고 화면도 불필요하게 전체 맞춤을 반복한다.
     */
    private val _liveRoute = MutableStateFlow<LiveRouteStore.Snapshot?>(null)

    /** null 이면 추적 중이 아니다. 화면은 그때 진행률 대신 안내를 띄운다 */
    val snapshot: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    val liveRoute: StateFlow<LiveRouteStore.Snapshot?> = _liveRoute.asStateFlow()

    /** 서비스가 위치를 받을 때마다 부른다. */
    fun publish(snapshot: Snapshot) {
        _snapshot.value = snapshot
    }

    /** 서비스가 디스크에 저장을 마친 성공 경로만 게시한다. */
    fun publishLiveRoute(snapshot: LiveRouteStore.Snapshot) {
        _liveRoute.value = snapshot
    }

    /** ViewModel 재생성 때 디스크 사본을 메모리 Flow 로 복원한다. */
    fun restoreLiveRoute(snapshot: LiveRouteStore.Snapshot?) {
        if (snapshot == null) return
        // 디스크를 읽은 직후 서비스가 더 새 결과를 게시할 수 있다. 단순한
        // read-compare-write 는 그 새 값을 오래된 디스크 사본으로 덮으므로,
        // StateFlow 자체에서 원자적으로 비교하고 교체한다.
        _liveRoute.update { current ->
            if (current == null || current.fetchedAtMillis < snapshot.fetchedAtMillis) {
                snapshot
            } else {
                current
            }
        }
    }

    fun liveRouteFor(eventId: Long): LiveRouteStore.Snapshot? =
        _liveRoute.value?.takeIf { it.eventId == eventId }

    fun clearLiveRoute(
        eventId: Long? = null,
        expectedFetchedAtMillis: Long? = null,
    ) {
        _liveRoute.update { current ->
            if (current == null) return@update null

            val eventMatches = eventId == null || current.eventId == eventId
            val snapshotMatches = expectedFetchedAtMillis == null ||
                current.fetchedAtMillis == expectedFetchedAtMillis
            if (eventMatches && snapshotMatches) null else current
        }
    }

    /**
     * 추적이 끝났다.
     *
     * 도착했든 마감이든 중지든 **반드시 부른다.** 남겨 두면 화면이 옛 위치를
     * 현재처럼 그리고, 사용자는 몇 시간 전 좌표를 보며 판단한다.
     */
    fun clear() {
        _snapshot.value = null
        _liveRoute.value = null
    }

    /** 이 일정을 추적하는 중인 경우에만 위치를 준다. */
    fun pointFor(eventId: Long): Snapshot? = _snapshot.value?.takeIf { it.eventId == eventId }
}
