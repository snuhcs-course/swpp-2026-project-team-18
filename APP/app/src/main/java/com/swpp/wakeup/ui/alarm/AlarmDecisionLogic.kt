// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.alarm

import com.swpp.wakeup.domain.model.AlarmPlanView
import com.swpp.wakeup.domain.model.AlarmTimeChoice
import com.swpp.wakeup.domain.model.PlanRow
import com.swpp.wakeup.domain.model.RiskOptionView
import com.swpp.wakeup.ui.events.AlarmConfirmText
import com.swpp.wakeup.ui.events.AlarmConfirmView
import com.swpp.wakeup.ui.help.OnTimeSheetText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * ④ 알람 결정 다시 그림(iter4 H2-3 · H2-6, Figma `545:920`)의 문구와 판단. 화면이 없는 순수 함수만 둔다.
 */
object AlarmDecisionLogic {

    // ---------------------------------------------------------------- 알람 카드의 알약

    enum class PillKind {
        /** 적용 칸이 추천 칸이다(주황) */
        RECOMMENDED,

        /** 추천이 아닌 칸(여유 있게 · 빠듯하게)을 골랐다(주황) */
        PRESET,
    }

    data class Pill(val text: String, val kind: PillKind)

    private val PRESET_TAUS = mapOf(
        RiskOptionView.KEY_SAFE to .97,
        RiskOptionView.KEY_NORMAL to .90,
        RiskOptionView.KEY_GAMBLE to .65,
    )

    /**
     * 알람 줄 옆 알약. 사용자가 세 칸 중 하나를 골랐으면 그 칸 이름, 아니면(일정 종류 · 프로필 기본값을 그대로
     * 쓰는 중) "추천". 직접 정한 시각이면 알약을 달지 않는다(null) — 11-18 사용자: "직접 정한 시각" 문구는 필요 없다.
     * 추천 칸이 무엇인지 앱이 짐작하지 않는다 — 시각 바꾸기 시트의 "추천" 표시는 서버 `recommended_key` 를 쓴다(BE-H3e).
     */
    fun pill(plan: AlarmPlanView): Pill? {
        if (plan.userAlarm) return null
        val appliedKey = plan.tauUsed?.let { tau -> PRESET_TAUS.entries.firstOrNull { kotlin.math.abs(it.value - tau) < 1e-6 }?.key }
        val picked = plan.tauSourceLabel == AlarmTimeChoice.MANUAL_TAU_SOURCE
        return if (picked && appliedKey != null) Pill(AlarmTimeChoice.quickLabel(appliedKey, appliedKey), PillKind.PRESET)
        else Pill("추천", PillKind.RECOMMENDED)
    }

    // ---------------------------------------------------------------- 문구

    /** 일정 카드 윗줄. "오늘 오전 9:00" */
    fun whenLine(startAtMillis: Long?, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val millis = startAtMillis ?: return null
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        return "${AlarmConfirmText.dayWord(date, today)} ${OnTimeSheetText.clock(millis, zone)}"
    }

    /** 장소 줄. "302동 105호 · 지하철·버스 42분" */
    fun placeLine(placeName: String?, travelMode: String?, travelMinutes: Int?): String {
        val name = placeName?.trim()?.takeIf { it.isNotEmpty() } ?: "장소 없음"
        val mode = modeWord(travelMode)
        val travel = when {
            mode != null && travelMinutes != null -> "$mode ${travelMinutes}분"
            travelMinutes != null -> "이동 ${travelMinutes}분"
            else -> null
        }
        return listOfNotNull(name, travel).joinToString(" · ")
    }

    /** 서버 수단 문자열을 화면 말로. "지하철+버스" → "지하철·버스" */
    fun modeWord(travelMode: String?): String? =
        travelMode?.trim()?.takeIf { it.isNotEmpty() }?.replace("+", "·")?.replace(" · ", "·")

    /** 알람 카드 아랫줄. "1시간 28분 뒤 · 9:00 일정" */
    fun remainingLine(alarmAtMillis: Long?, startAtMillis: Long?, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
        val start = startAtMillis?.let { "${shortClock(it, zone)} 일정" }
        val left = alarmAtMillis?.let { at ->
            val minutes = (at - nowMillis) / 60_000
            if (minutes <= 0) "지난 알람" else "${AlarmTimeChoice.minutesLabel(minutes.toInt())} 뒤"
        }
        return listOfNotNull(left, start).joinToString(" · ").takeIf { it.isNotEmpty() }
    }

    /** "왜 오전 7:40인가요?" */
    fun whyTitle(alarmAtMillis: Long?, zone: ZoneId = ZoneId.systemDefault()): String =
        alarmAtMillis?.let { "왜 ${OnTimeSheetText.clock(it, zone)}인가요?" } ?: "왜 이 시각인가요?"

    const val PREP_NOTE = "아침 기록이 쌓일수록 더 정확해져요"

    /** 이동 줄 설명. 수단을 모르면 고른 경로라고만 한다 */
    fun travelNote(travelMode: String?): String =
        modeWord(travelMode)?.let { "$it 경로로 정했어요" } ?: "고른 경로로 정했어요"

    /** 여유 줄 설명. 서버 `buffer_source`: `learned` 면 지각 기록으로 조정, 그 밖(`fixed`)은 기본값 */
    fun bufferNote(bufferSource: String?): String =
        if (bufferSource == "learned") "지난 지각 기록을 보고 정했어요" else "기본 여유 시간이에요"

    /** 직접 정한 시각일 때 추천과의 차이. 추천 시각을 모르면 null */
    fun userGapLine(alarmAtMillis: Long?, recommendedAtMillis: Long?, zone: ZoneId = ZoneId.systemDefault()): String? {
        val at = alarmAtMillis ?: return null
        val rec = recommendedAtMillis ?: return null
        val diff = ((at - rec) / 60_000).toInt()
        val recLabel = OnTimeSheetText.clock(rec, zone)
        return when {
            diff == 0 -> "추천하는 시각이에요"
            diff > 0 -> "추천($recLabel)보다 ${AlarmTimeChoice.minutesLabel(diff)} 늦은 알람이에요"
            else -> "추천($recLabel)보다 ${AlarmTimeChoice.minutesLabel(-diff)} 이른 알람이에요"
        }
    }

    // ---------------------------------------------------------------- 막대 · 눈금

    data class Timeline(
        val segments: List<AlarmConfirmView.Segment>,
        val points: List<AlarmConfirmView.Point>,
    )

    /**
     * 막대와 눈금. 구간은 계획의 준비 · 이동 · 여유 분(0분은 빼고), 눈금은 기상 · 출발 · 도착 · 시작이다.
     * 준비 단계가 없는 일정은 준비 구간과 "출발" 눈금이 기상과 겹치므로 하나로 합친다(알람 확인 시트와 같은 규칙).
     */
    fun timeline(plan: AlarmPlanView, zone: ZoneId = ZoneId.systemDefault()): Timeline {
        fun minutes(kind: PlanRow.Kind) = plan.breakdown.filter { it.kind == kind }.sumOf { it.minutes }.coerceAtLeast(0)
        val prep = if (plan.prepApplies) minutes(PlanRow.Kind.PREP) else 0
        val travel = minutes(PlanRow.Kind.TRAVEL)
        val buffer = minutes(PlanRow.Kind.BUFFER)
        val raw = listOfNotNull(
            plan.alarmAtMillis?.let { AlarmConfirmView.Point(shortClock(it, zone), "기상") },
            plan.departByMillis?.let { AlarmConfirmView.Point(shortClock(it, zone), "출발") },
            plan.arriveAtMillis?.let { AlarmConfirmView.Point(shortClock(it, zone), "도착") },
            plan.startAtMillis?.let { AlarmConfirmView.Point(shortClock(it, zone), "시작") },
        )
        val points = if (raw.size == 4) {
            AlarmConfirmText.points(raw, listOf(prep, travel, buffer))
        } else {
            // 시각 하나를 모르면 경계를 맞출 수 없다. 고르게 놓는다.
            AlarmConfirmText.points(raw, emptyList())
        }
        return Timeline(AlarmConfirmText.segments(prep, travel, buffer), points)
    }

    /** "7:40" — 오전/오후 없이 짧게 */
    fun shortClock(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(millis).atZone(zone)
        val hour = (t.hour % 12).let { if (it == 0) 12 else it }
        return "$hour:%02d".format(t.minute)
    }

    // ---------------------------------------------------------------- 알람 상태 한 줄

    /**
     * 알람 카드 안의 작은 상태 한 줄. ④ 에서는 승인 버튼을 두지 않는다(사용자 피드백 11-16 — 화면 아래가 너무 컸다).
     * 승인은 알람 추가 뒤의 알람 확인 시트에서 한다.
     */
    data class AlarmStatus(val text: String)

    /**
     * 계산 전 · 안 가요 · 지난 알람이면 null(그 상태는 다른 카드가 말한다). 지금은 "알람이 꺼져 있어요" 한 가지다 —
     * 확인한 알람의 "알람이 맞춰져 있어요" 는 뺐다(11-18 사용자: 필요 없다). "오전 7:31에 알람이 울려요" 는 바로 위
     * 큰 시각과 같은 말이라 두지 않는다(11-17 사용자).
     */
    fun alarmStatus(plan: AlarmPlanView, zone: ZoneId = ZoneId.systemDefault()): AlarmStatus? {
        if (!plan.isComputed || plan.skipped || plan.alarmPassed) return null
        plan.alarmAtMillis?.let { OnTimeSheetText.clock(it, zone) } ?: return null
        if (!plan.alarmOn) return AlarmStatus("알람이 꺼져 있어요. 홈에서 켤 수 있어요")
        return null
    }

    // ---------------------------------------------------------------- 눈금 글자 자리

    /**
     * ④ 눈금의 원하는 왼쪽 위치. 첫 눈금(기상)은 왼쪽 끝에 왼쪽 정렬, 마지막(시작)은 오른쪽 끝에 오른쪽 정렬,
     * 가운데(출발 · 도착)는 경계에 오른쪽 정렬. [widths] 는 실제로 잰 글자 폭이다. 겹침은
     * [com.swpp.wakeup.ui.common.LabelLayout.place] 가 푼다.
     */
    fun decisionAnchors(stops: List<Float>, widths: List<Float>, width: Float): List<Float> =
        stops.mapIndexed { i, at ->
            when (i) {
                0 -> 0f
                stops.lastIndex -> width - widths[i]
                else -> at * width - widths[i]
            }
        }
}
