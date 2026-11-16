// AI-generated (Claude)
package com.swpp.wakeup.ui.home

import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * 일정 추가 · 수정의 시작 시각 규칙. `HomeViewModel` 은 AndroidViewModel 이라 JVM 단위 테스트로 만들 수
 * 없어서 판정만 여기로 뺐다(`AddTimeRulesTest`).
 */

/**
 * 폼의 날짜 · 시각([date] · [hour] · [minute])을 [zone] 의 그 순간으로 바꾼다. 오프셋은 그날 그 시각의
 * 것을 쓴다(서머타임이 있는 곳에서도 맞다).
 */
internal fun startAtOf(date: LocalDate, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): OffsetDateTime =
    OffsetDateTime.of(date, LocalTime.of(hour, minute), zone.rules.getOffset(date.atTime(hour, minute)))

/** 지난 시작 시각을 시각 줄 아래에 알릴 때의 문구 */
internal const val PAST_START_LINE = "이미 지난 시각이에요. 날짜나 시각을 바꿔 주세요"

/**
 * 시작 시각 [startAt] 이 [now] 에 이미 지났으면 시각 줄에 붙일 문구, 아니면 null.
 *
 * 한 번뿐인 일정을 지난 시각으로 만들면 서버가 받아 알람을 지난 시각으로 계산한다. 그 알람은 울리지 않고,
 * 일정은 홈에서 곧 사라지며, 그날 첫 알람 자리를 차지해 같은 날 뒤 일정의 알람까지 꺼진다. 그래서 보내기
 * 전에 막는다. 달력은 지난 날짜만 막아서 오늘의 지난 시각(오전 · 오후를 잘못 고른 경우)은 그대로 들어갔다.
 *
 * @param repeating 반복 일정인가. 반복 시작은 서버가 따로 거절하고 이유를 시각 줄에 돌려준다. 여기서 막지 않는다
 * @param editBase 수정이면 고치기 전 시작 시각, 추가면 null. 시각을 그대로 두었으면 막지 않는다 — 이미 시작한
 *   일정도 제목 같은 다른 값은 고칠 수 있어야 한다. 폼은 분까지만 다루므로 분 단위로 비교한다
 */
internal fun pastStartError(
    startAt: OffsetDateTime,
    now: OffsetDateTime,
    repeating: Boolean,
    editBase: OffsetDateTime?,
): String? {
    if (repeating || startAt.isAfter(now)) return null
    if (editBase != null && editBase.truncatedTo(ChronoUnit.MINUTES).isEqual(startAt)) return null
    return PAST_START_LINE
}
