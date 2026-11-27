package com.swpp.wakeup.domain.model

import java.time.OffsetDateTime
import java.time.ZoneId
import java.math.BigDecimal
import java.math.RoundingMode

data class AppointmentRoom(
    val id: Long,
    val title: String,
    val startAt: OffsetDateTime,
    val destinationName: String,
    val targetProbability: Double,
    val memberCount: Int,
) {
    val targetLabel: String get() = BigDecimal.valueOf(targetProbability).movePointRight(2)
        .setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%"

    fun appointmentLabel(zone: ZoneId = ZoneId.systemDefault()): String {
        val local = startAt.atZoneSameInstant(zone)
        return "${local.monthValue}월 ${local.dayOfMonth}일 · ${ClockText.label(local)}"
    }
}

/** Members intentionally have no home, location, route, email, or personal Event fields. */
data class RoomMember(
    val nickname: String,
    val isMe: Boolean,
    val alarmAt: OffsetDateTime?,
    val earlierMinutes: Int?,
) {
    fun alarmLabel(zone: ZoneId = ZoneId.systemDefault()): String =
        alarmAt?.atZoneSameInstant(zone)?.let(ClockText::label) ?: "아직 계산 중이에요"

    val adjustmentLabel: String
        get() = when {
            earlierMinutes == null -> "알람 계산 결과를 기다리고 있어요"
            earlierMinutes > 0 -> "${earlierMinutes}분 더 일찍 일어나면 좋아요"
            else -> "현재 알람을 유지해요"
        }
}

data class RoomWakeSuggestion(val nickname: String, val earlierMinutes: Int) {
    val label: String
        get() = "$nickname 님의 알람을 ${earlierMinutes}분 앞당기면 목표에 가까워져요"
}

data class RoomDetail(
    val room: AppointmentRoom,
    val inviteCode: String,
    val members: List<RoomMember>,
    val wakeSuggestion: RoomWakeSuggestion?,
)
