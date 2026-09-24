package com.swpp.wakeup.ui.alarm

/**
 * 위치를 받은 지 얼마나 됐는지.
 *
 * 진행률 옆에 이것을 적는 이유는, 지하에서 신호가 끊기면 **마지막으로 받은
 * 위치가 그대로 남아** 화면이 몇 분 전 상태를 현재처럼 보여 주기 때문이다.
 * 사용자가 그 숫자를 근거로 여유를 판단하면 늦는다.
 *
 * 벽시계를 쓴다. 단조 시계가 더 안전하지만 위치의 `atMillis` 가 벽시계이므로
 * 기준을 섞을 수 없다. 시각 보정이 끼어들면 한 번 이상하게 보일 수 있고,
 * 그것이 잘못된 진행률보다 덜 해롭다.
 */
internal fun freshnessLabel(
    atMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
): String? {
    if (atMillis <= 0L) return null
    val seconds = (nowMillis - atMillis) / 1000
    return when {
        // 미래 시각은 시각 보정 중이라는 뜻이다. 거짓을 적기보다 비운다.
        seconds < 0 -> null
        seconds < 30 -> "방금 갱신"
        seconds < 60 -> "${seconds}초 전 갱신"
        seconds < 3600 -> "${seconds / 60}분 전 갱신"
        else -> "${seconds / 3600}시간 전 갱신"
    }
}
