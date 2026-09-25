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

/**
 * epoch ms 를 진행 바에 쓰는 "8:50" 으로.
 *
 * 저장소가 만드는 표시 문자열(`arrivalAt`)은 계획한 시각이고, 이것은 **예상**
 * 도착 시각이다. 예상은 초마다 바뀌므로 저장소에서 만들 수 없다.
 *
 * 기기 시간대를 쓴다. 서버가 준 시각도 같은 시간대로 바꿔 보여 주므로 둘이
 * 어긋나지 않는다.
 */
internal fun arrivalClockLabel(
    millis: Long,
    zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
): String = java.time.Instant.ofEpochMilli(millis)
    .atZone(zone)
    .format(CLOCK_FORMAT)

private val CLOCK_FORMAT = java.time.format.DateTimeFormatter.ofPattern("H:mm")

/**
 * 이 계획을 계산한 지 얼마나 됐는지. "12분 전 계산"
 *
 * [freshnessLabel] 과 따로 두는 이유는 **말하는 대상이 다르기** 때문이다. 그쪽은
 * 마지막으로 받은 **위치**가 얼마나 낡았는지이고, 이쪽은 알람 시각과 이동 시간을
 * **언제 계산했는지**다. 둘은 독립적으로 낡는다 — 위치는 방금 받았는데 계획은
 * 어제 것일 수 있다.
 *
 * 백그라운드가 임박한 일정의 경로를 15분마다 다시 계산하므로
 * ([com.swpp.wakeup.background.RouteRefreshWorker]) 아침에는 이 값이 계속
 * 줄어든다. 그것이 곧 "배차 변화를 따라가고 있다" 는 증거다.
 *
 * 하루가 넘으면 날짜 대신 "오래됨" 으로 뭉갠다. 정확히 며칠인지는 판단에 쓸모가
 * 없고, 사용자가 해야 하는 일은 하나다 — 다시 계산하는 것.
 */
internal fun computedAgoLabel(
    computedAtMillis: Long?,
    nowMillis: Long = System.currentTimeMillis(),
): String? {
    if (computedAtMillis == null || computedAtMillis <= 0L) return null
    val seconds = (nowMillis - computedAtMillis) / 1000
    return when {
        // 미래 시각이면 기기와 서버 시계가 어긋난 것이다. 거짓을 적기보다 비운다.
        seconds < 0 -> null
        seconds < 90 -> "방금 계산"
        seconds < 3600 -> "${seconds / 60}분 전 계산"
        seconds < 24 * 3600 -> "${seconds / 3600}시간 전 계산"
        else -> "오래된 계산"
    }
}
