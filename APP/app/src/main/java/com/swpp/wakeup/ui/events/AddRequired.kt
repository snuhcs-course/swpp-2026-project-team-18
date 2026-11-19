// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.events

import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.ringAt
import com.swpp.wakeup.domain.model.RecentDestinations
import com.swpp.wakeup.ui.home.HomeViewModel
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * 알람 추가(H2-8 `545:192`)에서 **반드시** 정해야 하는 칸. 하나라도 비면 [알람 맞추기] 가 꺼진다.
 *
 * 화면이 아니라 여기에서 판단한다 — 꺼지는 규칙은 눈으로 확인하기 어렵고 가장 자주 틀리는 곳이라
 * 단위 테스트로 묶어 둔다([AddRequiredRule]).
 */
enum class AddRequired(val label: String) {
    /** 집이 저장돼 있지 않고 출발지도 고르지 않았다 */
    ORIGIN("출발지"),
    DESTINATION("도착지"),
    TIME("도착 시각"),
    ROUTE("이동 경로"),
}

object AddRequiredRule {

    /** 아직 비어 있는 필수 칸. 화면에 보이는 순서(출발지 → 도착지 → 시각 → 경로)대로 준다 */
    fun missing(state: HomeViewModel.AddState, hasHome: Boolean): List<AddRequired> = buildList {
        if (state.origin == null && !hasHome) add(AddRequired.ORIGIN)
        if (state.selectedPlace == null) add(AddRequired.DESTINATION)
        if (!state.timePicked) add(AddRequired.TIME)
        if (state.routeKey == null) add(AddRequired.ROUTE)
    }

    /** 출발지와 도착지가 같을 때 버튼 위 한 줄. 필수 칸 부족보다 먼저 보인다 */
    const val SAME_HINT = "출발지와 도착지가 달라야 알람을 맞출 수 있어요"

    /** [hint] 에 같은 곳 여부를 더한 것. 같은 곳이면 그 이유가 먼저다(H2-9) */
    fun hint(missing: List<AddRequired>, sameEndpoints: Boolean): String? =
        if (sameEndpoints) SAME_HINT else hint(missing)

    /** 지난 시각이 가장 먼저다 — 다른 칸을 다 채워도 저장되지 않는다 */
    fun hint(missing: List<AddRequired>, sameEndpoints: Boolean, pastTime: Boolean): String? =
        if (pastTime) PastTimeRule.ADD_MESSAGE else hint(missing, sameEndpoints)

    /**
     * 꺼진 버튼 위에 적을 한 줄. 왜 못 누르는지 말해 주지 않으면 사용자는 칸을 하나씩 짚어 봐야 한다
     * (H2-11 가입 버튼과 같은 생각). 다 찼으면 null 이다.
     */
    fun hint(missing: List<AddRequired>): String? = when {
        missing.isEmpty() -> null
        missing.size == 1 -> "${missing[0].label}만 정하면 돼요"
        else -> missing.joinToString(" · ") { it.label } + objectJosa(missing.last().label) + " 정해 주세요"
    }

    /** 받침이 있으면 "을", 없으면 "를" */
    private fun objectJosa(word: String): String {
        val last = word.lastOrNull() ?: return "를"
        if (last.code !in 0xAC00..0xD7A3) return "를"
        return if ((last.code - 0xAC00) % 28 == 0) "를" else "을"
    }
}

/**
 * 출발지와 도착지가 같은 곳인가(H2-9, Figma ⑫ 변형 `546:1822`). 서버도 50m 안이면 거절한다(347:14) —
 * 저장을 눌러 400 을 받기 전에 앱에서 먼저 막는다.
 */
object SameEndpoints {

    const val WARNING = "출발지와 도착지가 같아요. 도착지를 다시 골라 주세요"

    /**
     * 출발지는 고른 곳([HomeViewModel.AddState.origin]), 없으면 저장된 집([home])이다.
     * 도착지나 출발지가 아직 없으면 같다고 하지 않는다.
     */
    fun same(state: HomeViewModel.AddState, home: PlaceSearchItem?): Boolean {
        val destination = state.selectedPlace ?: return false
        // 둘 다 집: 집 버튼으로 도착지를 골랐고 출발지는 따로 고르지 않았다
        if (state.destinationIsHome && state.origin == null) return true
        val origin = state.origin ?: home ?: return false
        return RecentDestinations.samePlace(origin, destination)
    }
}

// Partly AI-generated (Claude)
/**
 * 이미 지난 시각을 막는다(11-16 버그: 지금보다 이른 도착 시각으로 [알람 맞추기] 를 누르면 서버가 일정을 만들지만
 * 알람은 걸리지 않고 홈에서도 곧 사라지는데, "추가했어요" · "깨워 드릴게요" 가 떴다).
 *
 * 서버는 한 번짜리 일정의 지난 시각을 거절하지 않으므로(반복만 거절) 앱이 저장 전에 막는다.
 */
object PastTimeRule {

    const val ADD_MESSAGE = "이미 지난 시각이에요. 도착 시각을 다시 골라 주세요"
    const val EDIT_MESSAGE = "이미 지난 시각이에요. 시각을 다시 골라 주세요"

    /**
     * 울릴 시각이 이미 지났다 — 알람을 맞추지 않는다(11-16 사용자: "알람 세팅 자체가 안 돼야 해").
     * 새 일정은 만든 직후 지워 되돌리고, 수정은 이전 값으로 되돌린 뒤 이 문구를 보인다.
     */
    const val ADD_TOO_LATE = "지금 준비해도 늦는 시각이라 알람을 맞추지 않았어요. 도착 시각을 늦춰 주세요"
    const val EDIT_TOO_LATE = "지금 준비해도 늦는 시각이라 저장하지 않았어요. 시각을 늦춰 주세요"

    /** 되돌리기(새 일정 지우기)가 실패했다 — 일정이 남았다는 사실을 숨기지 않는다 */
    const val ADD_UNDO_FAILED = "지금 준비해도 늦는 시각인데 일정을 되돌리지 못했어요. 알람은 울리지 않으니 홈에서 그 일정을 지워 주세요"

    /** 수정을 이전 값으로 되돌리지 못했다 */
    const val EDIT_UNDO_FAILED = "지금 준비해도 늦는 시각인데 이전 값으로 되돌리지 못했어요. 알람은 울리지 않으니 시각을 다시 고쳐 주세요"

    /**
     * "이후 일정 모두" 로 고쳤는데 다음 알람이 이미 지났다. 여러 회차를 되돌리는 것은 위험해 되돌리지 않고 알린다.
     */
    const val EDIT_FOLLOWING_PASSED = "이후 일정까지 수정했지만 이번 회차는 지금 준비해도 늦어 알람이 울리지 않아요. 시각을 다시 고쳐 주세요"

    /** 고른 날짜·시각이 [now] 와 같거나 이르다 */
    fun isPast(date: java.time.LocalDate, hour: Int, minute: Int, now: LocalDateTime): Boolean =
        !LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).isAfter(now)

    /**
     * 화면의 시각이 지났는가. 새 일정은 시각을 고른 뒤에만 본다. 수정은 **시각을 바꿨을 때만** 본다 —
     * 이미 시작한 일정의 제목만 고치는 것까지 막으면 안 된다.
     */
    fun isPast(state: HomeViewModel.AddState, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (!state.isEditing && !state.timePicked) return false
        if (state.isEditing) {
            val base = state.editBase?.startAt?.atZoneSameInstant(ZoneId.systemDefault())?.toLocalDateTime()
            val picked = LocalDateTime.of(state.date, java.time.LocalTime.of(state.hour, state.minute))
            if (base != null && base.withSecond(0).withNano(0) == picked) return false
        }
        return isPast(state.date, state.hour, state.minute, now)
    }

    /**
     * 저장 응답의 계획으로 보아 **알람을 맞출 수 없는가**. 계획이 계산됐을 때(`status == ok`)만 본다 —
     * 계산 전이거나 실패면 시각을 모르니 막지 않는다. 반복 일정은 서버가 **다음 회차**의 알람을 주므로
     * 오늘 회차만 지난 경우를 잘못 막지 않는다.
     */
    fun tooLate(plan: com.swpp.wakeup.data.remote.AlarmPlanDto?, nowMillis: Long): Boolean =
        plan?.status == com.swpp.wakeup.data.remote.AlarmPlanDto.STATUS_OK &&
            alarmPassed(plan.ringAt, nowMillis)

    /**
     * 보내기 전 판단: 지금 바로 출발해도 고른 경로의 이동 시간만으로 [startAt] 에 늦는다. 준비 시간은 빼고 본다 —
     * 늦는 것이 확실한 경우만 막는다. 이동 시간을 모르면 false.
     */
    fun travelTooLate(startAt: OffsetDateTime, travelMinutes: Int?, now: Instant): Boolean {
        val minutes = travelMinutes ?: return false
        return now.plusSeconds(minutes * 60L).isAfter(startAt.toInstant())
    }

    /** 서버가 정한 알람 시각([ringAt], ISO)이 [nowMillis] 보다 이르거나 같다. 읽지 못하면 false */
    fun alarmPassed(ringAt: String?, nowMillis: Long): Boolean {
        val at = ringAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() } ?: return false
        return !at.toInstant().isAfter(Instant.ofEpochMilli(nowMillis))
    }
}
