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
