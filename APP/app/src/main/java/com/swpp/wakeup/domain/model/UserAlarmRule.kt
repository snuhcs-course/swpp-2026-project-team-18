// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import com.swpp.wakeup.data.remote.ServerVersion
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 알람 시각 바꾸기 시트(iter4 H2-3)의 [맞추기]가 어느 길로 저장하는지와, 휠 시각을 서버에 보낼 시각으로
 * 만드는 규칙. 서버 계약 `docs/iter4/server-alarm-control.md` 3 · 8절(BE-H3b).
 *
 * - 세 칸(빠르게 고르기)과 같은 시각 → 지금처럼 `PATCH tau_override`([Save.Preset])
 * - 그 밖의 시각 → `PUT /api/events/{id}/alarm/time`([Save.Custom]). 서버 0.21.0 부터 받는다
 * - 서버가 그보다 낮으면 → 저장하지 않고 이유 한 줄([Save.Locked], [LOCKED_NOTE])
 */
object UserAlarmRule {

    /** 직접 정한 알람 시각을 서버가 받기 시작한 버전(back-iter4, BE-H3b) */
    const val SINCE_SERVER = "0.21.0"

    /** 서버가 아직 직접 정한 시각을 받지 않을 때 시트에 보이는 한 줄 */
    const val LOCKED_NOTE = "지금은 위 세 가지 시각만 맞출 수 있어요. 곧 원하는 시각으로도 맞출 수 있게 돼요"

    /**
     * 휠로 고른 시각이 일정 시작과 같거나 그 뒤라 맞추지 않을 때의 한 줄(11-18 사용자: "저장 자체를 막아 줘").
     * 지난 시각 규칙([com.swpp.wakeup.ui.events.PastTimeRule])처럼 왜 안 되는지와 무엇을 하면 되는지를 말한다.
     */
    const val AFTER_START_NOTE = "일정이 시작한 뒤에 울리는 시각이라 맞출 수 없어요. 더 이른 시각을 골라 주세요"

    /** 직접 정한 알람일 때 시트 · ④ 에 붙이는 말 */
    const val USER_LABEL = "직접 정한 시각"

    fun supported(serverVersion: String?): Boolean = ServerVersion.isAtLeast(serverVersion, SINCE_SERVER)

    sealed interface Save {
        /** 세 칸 중 하나와 같은 시각. 그 칸의 τ 로 `PATCH` */
        data class Preset(val key: String) : Save

        /** 세 칸 밖의 시각(0~1439 분). `PUT …/alarm/time` */
        data class Custom(val minutesOfDay: Int) : Save

        /** 서버가 아직 받지 않는다. 저장하지 않는다 */
        data object Locked : Save

        /** 휠 시각이 일정 시작과 같거나 그 뒤다(같은 날 기준). 저장하지 않는다 */
        data object AfterStart : Save
    }

    /**
     * [picked] 를 어떻게 저장할지. [presets] 는 칸 key → 그 칸의 시각(분)이다.
     * 세 칸과 같은 시각이면 서버 버전과 상관없이 칸으로 저장한다 — 예전과 같은 길이다.
     */
    // Partly AI-generated (Claude)
    /**
     * @param startMinutes 일정 시작(하루의 분). 주면 휠 시각이 시작과 같거나 그 뒤(12시간 안쪽, [AlarmTimeChoice.availableMinutes])
     * 일 때 [Save.AfterStart] 다 — 세 칸은 서버가 정한 시각이라 이 규칙을 걸지 않는다(칸이 먼저다).
     */
    fun choose(
        picked: Int,
        presets: Map<String, Int?>,
        supported: Boolean,
        startMinutes: Int? = null,
        preferredPresetKey: String? = null,
    ): Save {
        // Equal clocks do not imply equal risk choices. Retain an explicitly tapped card's key.
        preferredPresetKey?.takeIf { presets[it] == picked }?.let { return Save.Preset(it) }
        presets.entries.firstOrNull { it.value == picked }?.let { return Save.Preset(it.key) }
        if (startMinutes != null && isAfterStart(picked, startMinutes)) return Save.AfterStart
        return if (supported) Save.Custom(picked) else Save.Locked
    }

    /**
     * 휠 시각이 일정 시작과 같은 분이거나 그 뒤인가. 시작보다 12시간 넘게 "늦은" 시각은 전날 밤 알람으로 본다
     * ([AlarmTimeChoice.availableMinutes] 와 같은 기준) — 9:00 일정에 23:00 은 전날 밤 23:00 이라 맞출 수 있다.
     */
    fun isAfterStart(picked: Int, startMinutes: Int): Boolean =
        AlarmTimeChoice.availableMinutes(picked, startMinutes) <= 0

    /**
     * 휠 시각(하루의 분)을 실제 알람 날짜·시각으로. 일정 시작([start])보다 **앞선 가장 가까운** 그 시각이다 —
     * 9:00 일정에 23:30 을 고르면 전날 밤 23:30 이다. 시작과 같은 시각이면 하루 전으로 본다.
     * 너무 이르거나(24시간 전) 늦은(시작 5분 전 이후) 시각은 서버가 400 으로 알려 준다(문구는 서버 것을 그대로).
     */
    fun alarmDateTime(pickedMinutes: Int, start: ZonedDateTime): ZonedDateTime {
        val minutes = ((pickedMinutes % AlarmTimeChoice.MINUTES_PER_DAY) + AlarmTimeChoice.MINUTES_PER_DAY) %
            AlarmTimeChoice.MINUTES_PER_DAY
        val sameDay = start.toLocalDate()
            .atTime(minutes / 60, minutes % 60)
            .atZone(start.zone)
        return if (sameDay.isBefore(start)) sameDay else sameDay.minusDays(1)
    }

    private val WIRE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    /**
     * 서버에 보낼 문자열. **오프셋을 꼭 붙인다** — 없으면 서버가 프로필 시간대의 벽시계 시각으로 읽는다.
     * 초는 버린다(서버도 버린다). 예: `2026-11-17T08:10:00+09:00`
     */
    fun wire(at: ZonedDateTime): String = at.truncatedTo(ChronoUnit.MINUTES).format(WIRE)
}
