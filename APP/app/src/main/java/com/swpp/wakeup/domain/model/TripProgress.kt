package com.swpp.wakeup.domain.model

// Partly AI-generated (Claude)
/**
 * 이동 중인 추적이 프로세스와 함께 죽었을 때 되살릴 것(todo §2 "추적 서비스 재시작 복원").
 *
 * 추적 서비스는 `START_REDELIVER_INTENT` 라 메모리 압박으로 죽어도 같은 일정으로 다시 시작한다.
 * 그런데 단계·이동 시작 시각은 메모리에만 있어서 다시 "출발 전" 부터 시작했다. 그러면 밖에서
 * 받은 첫 위치를 "처음부터 집 밖" 으로 읽어 출발을 놓친 것으로 남기고, 실시간 도착 예정은 지금
 * 출발한 것처럼 낙관적으로 나온다. 이미 알린 경로 바꾸기 제안도 다시 뜬다.
 *
 * 집을 나선 뒤([com.swpp.wakeup.sensing.TripGeofence.Phase.IN_TRANSIT])에만 남긴다. 좌표는
 * 담지 않는다. Gson 으로 디스크에 쓰므로 모든 칸이 null 일 수 있다고 보고 읽는다.
 */
data class TripProgress(
    val eventId: Long,
    /** 이동이 시작된 것으로 판정한 시각(벽시계 ms). 실시간 도착 예정의 기준이다 */
    val movingSinceMillis: Long?,
    /** 이 추적이 출발지에서 사용자를 본 적이 있는가([com.swpp.wakeup.sensing.MorningClose]) */
    val sawOriginFix: Boolean,
    /** 출발 시각을 놓쳤는가. 되살린 뒤에도 출발 관측을 새로 만들지 않는다 */
    val departureMissed: Boolean,
    /** 이미 알린 경로 바꾸기 제안의 경로 key */
    val rerouteNotifiedKeys: List<String>?,
    /** 남긴 시각(벽시계 ms) */
    val savedAtMillis: Long,
) {
    /** [eventId] 의 추적을 이어 가는 데 써도 되는가. 다른 일정이거나 너무 오래됐으면 버린다 */
    fun usableFor(eventId: Long, nowMillis: Long): Boolean =
        this.eventId > 0L && this.eventId == eventId &&
            savedAtMillis > 0L && nowMillis - savedAtMillis in 0..MAX_AGE_MILLIS &&
            (movingSinceMillis == null || movingSinceMillis in 1..nowMillis)

    companion object {
        /** 추적 마감(일정 시작 + 1시간)보다 넉넉하다. 이보다 오래된 사본은 지난 이동이다 */
        const val MAX_AGE_MILLIS = 6 * 60 * 60_000L
    }
}
