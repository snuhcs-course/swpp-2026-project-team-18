package com.swpp.wakeup.background

/**
 * 배경 동기화의 판단 규칙.
 *
 * 워커에서 떼어 낸 **순수 함수**들이다. 워커 자체는 기기나
 * `androidx.work:work-testing` 없이 돌릴 수 없는데, 여기 담긴 규칙들은
 * 틀리면 알람이 사라지거나 학습 자료가 유실되는 것들이다. 기기 없이 못 도는
 * 검사는 결국 돌지 않으므로 규칙만 따로 뽑아 단위 테스트로 박는다.
 */
object SyncDecision {

    /**
     * 알람 등록을 갱신해도 되는가.
     *
     * **`AlarmScheduler.sync` 는 받은 목록으로 등록 상태를 갈아 끼운다.** 이미
     * 걸린 알람을 지우고 준 것만 다시 넣는다. 그래서 두 경우에 부르면 안 된다.
     *
     * - [fromCache] — 오래된 사본이다. 지금 걸린 알람보다 낡았을 수 있고,
     *   사용자가 미뤄 둔 알람을 되돌린다.
     * - [networkSucceeded] 가 false — 서버에서 아무것도 못 받았다. 빈 목록으로
     *   부르면 **걸려 있던 알람이 전부 취소된다.** 이 앱에서 가장 나쁜 실패다.
     */
    fun shouldResyncAlarms(networkSucceeded: Boolean, fromCache: Boolean): Boolean =
        networkSucceeded && !fromCache

    /**
     * 관측 업로드를 다시 시도해야 하는가.
     *
     * 판단 기준은 "큐가 비었는가" 하나다. 큐 구현이 영구 실패(형식 오류·일정
     * 삭제)를 만나면 그 항목을 **버리고** 큐를 비운다. 그래서 큐가 남아 있다는
     * 것은 곧 일시적 실패라는 뜻이다.
     *
     * 시도 횟수 상한을 함께 본다. 무한히 재시도하면 서버가 계속 5xx 를 내는
     * 동안 배터리를 먹는다. 상한을 넘기면 포기하고, 다음 정기 실행이나 앱을
     * 열 때 다시 시도한다 — 관측은 실시간이 아니라 늦어도 된다.
     */
    fun shouldRetryUpload(pendingAfterFlush: Int, attemptCount: Int): Boolean =
        pendingAfterFlush > 0 && attemptCount < MAX_UPLOAD_ATTEMPTS

    /**
     * 업로드 재시도 상한.
     *
     * WorkManager 기본 지수 백오프(30초 시작)에서 5회면 대략 8분을 덮는다.
     * 그 안에 안 되면 서버나 망 문제이고, 정기 실행이 뒤를 받는다.
     */
    const val MAX_UPLOAD_ATTEMPTS = 5

    /**
     * 정기 동기화 주기(시간).
     *
     * 6시간마다다. 이유는 두 가지다.
     *
     * 1. 알람 등록 지평이 7일이다([com.swpp.wakeup.alarm.AlarmScheduler]).
     *    앱을 며칠 열지 않아도 지평이 계속 밀려야 8일 뒤 일정의 알람이 걸린다.
     * 2. 학습이 하루 한 번 돌아 알람 시각이 바뀐다. 그 변화가 기기에 닿아야
     *    한다 — 서버만 아는 알람은 울리지 않는다.
     *
     * 더 자주 할 이유는 없다. WorkManager 최소 주기는 15분이지만 그 빈도로
     * 네트워크를 깨우면 배터리만 쓴다.
     */
    const val PERIODIC_SYNC_HOURS = 6L
}

/**
 * 임박한 일정의 경로를 다시 계산할지 정하는 규칙.
 *
 * [SyncDecision] 과 같은 이유로 순수 함수다 — 틀리면 카카오 하루 쿼터를 태우거나
 * 이동 중에 진행률이 뒤로 물러난다. 둘 다 워커를 돌려서는 잡기 어렵다.
 *
 * ## 왜 필요한가
 *
 * 배차와 교통 상황이 바뀌면 가장 빠른 경로도 바뀐다. 알람 시각과 경로선이 그것을
 * 따라가야 한다. 그런데 재계산은 **사용자가 버튼을 누를 때만** 일어났다
 * ([PlanSyncWorker] 는 6시간마다 돌지만 조회와 알람 등록만 한다).
 *
 * ## 무엇을 막는가
 *
 * 재계산 한 번이 카카오 경로 API 를 1~2회 부른다. 대상과 주기를 좁히지 않으면
 * 하루 1,000건 한도를 태운다.
 */
object RouteRefreshDecision {

    /**
     * 갱신 주기(분). WorkManager 최소 주기다.
     *
     * 배차 간격보다 짧을 필요가 없다. 임박한 일정이 없으면 네트워크를 쓰지
     * 않으므로([shouldRefresh] 가 로컬 목록만 본다) 이 빈도가 비싸지 않다.
     */
    const val REFRESH_PERIOD_MINUTES = 15L

    /**
     * 알람이 이 시간 안에 있는 일정만 갱신한다.
     *
     * 더 먼 일정을 지금 갱신해도 출발 전에 또 바뀐다. 3시간이면 아침 준비가
     * 시작되기 전부터 덮는다.
     */
    const val REFRESH_HORIZON_HOURS = 3L

    /**
     * 한 번에 갱신할 일정 수 상한.
     *
     * 임박한 일정이 셋 이상인 아침은 드물다. 상한이 없으면 캘린더를 대량으로
     * 가져온 계정이 한 번에 쿼터를 태운다.
     */
    const val REFRESH_MAX_EVENTS = 2

    /**
     * 지금 갱신해야 하는 일정 id.
     *
     * **네트워크를 쓰지 않는 판단이다.** 기기에 등록해 둔 알람 목록만 본다. 임박한
     * 일정이 없으면 워커가 아무것도 하지 않고 끝나므로 15분 주기가 비싸지 않다.
     *
     * @param alarmAtMillis 등록된 알람 시각 목록. id 와 짝이다
     * @param trackingEventId 지금 추적 중인 일정. 있으면 그 일정은 갱신하지 않는다
     */
    fun dueEventIds(
        alarms: List<Pair<Long, Long>>,
        nowMillis: Long,
        trackingEventId: Long? = null,
    ): List<Long> {
        val horizon = nowMillis + REFRESH_HORIZON_HOURS * 60 * 60 * 1000
        return alarms
            .asSequence()
            // 이미 지난 알람은 갱신해도 의미가 없다. 그 아침은 끝났다.
            .filter { (_, alarmAt) -> alarmAt > nowMillis && alarmAt <= horizon }
            // **이동 중이면 건드리지 않는다.** 경로가 바뀌면 진행률의 분모가
            // 바뀌어 바가 뒤로 물러나고, 이미 타고 있는 사람에게 다른 경로를
            // 제안하는 것은 의미도 없다.
            .filter { (eventId, _) -> eventId != trackingEventId }
            .sortedBy { (_, alarmAt) -> alarmAt }
            .map { (eventId, _) -> eventId }
            .distinct()
            .take(REFRESH_MAX_EVENTS)
            .toList()
    }
}
