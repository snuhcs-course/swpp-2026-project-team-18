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

// Partly AI-generated (Claude)
/**
 * 진행 카드 아래 줄의 위치 상태(11-18 사용자: 이동은 기록되는데 "위치를 아직 받지 못했어요" 가 오래 떠 있었다).
 *
 * 위치는 GPS 라 네트워크 없이도 잡히고, 기록은 기기에 쌓였다가 연결되면 올라간다 — 네트워크는 이 줄과 상관없다.
 * 예전에는 "2분 안 · 오차 50m 안" 의 위치만 보고, 그런 위치가 없으면(지하철 · 건물 안) 이 이동에서 위치를 한 번도
 * 못 받은 것처럼 "아직 받지 못했어요" 라고 했다. 이제 셋을 나눈다.
 * - 이 이동의 위치가 아직 하나도 없다 → null(화면은 단계 안내 "위치를 아직 받지 못했어요")
 * - 쓸 만한 새 위치가 있다 → "방금 갱신" / "40초 전 갱신"
 * - 받긴 했지만 오래됐거나 흐리다 → "5분 전 갱신 · 지하나 건물 안에서는 위치가 늦게 잡혀요"
 */
internal fun liveNote(lastFixAtMillis: Long?, usable: Boolean, nowMillis: Long = System.currentTimeMillis()): String? {
    val at = lastFixAtMillis ?: return null
    val age = freshnessLabel(at, nowMillis) ?: return null
    return if (usable) age else "$age · $WEAK_SIGNAL_NOTE"
}

internal const val WEAK_SIGNAL_NOTE = "지하나 건물 안에서는 위치가 늦게 잡혀요"

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
): String = com.swpp.wakeup.domain.model.ClockText.label(millis, zone)


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
