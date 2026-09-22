package com.swpp.wakeup.domain.model

import kotlin.math.max

/**
 * 진행 중인 아침 기록.
 *
 * 알람을 해제한 순간 만들어지고, 블록을 하나씩 마칠 때마다 갱신된다. **디스크에
 * 저장된다** — 사용자는 씻으러 가고 앱은 백그라운드로 내려간다. 메모리에만 두면
 * 프로세스가 죽는 순간 그 아침의 기록이 사라진다.
 *
 * Gson 으로 직렬화하므로 평평한 모양을 유지한다.
 */
data class MorningSession(
    val eventId: Long,
    /** 알람을 해제한 시각. 첫 블록의 시작 시각이다 */
    val startedAtMillis: Long,
    /** 계획 출발마감. 남은 여유 계산의 기준이다. 없으면 여유를 말할 수 없다 */
    val departByMillis: Long?,
    /** 계획 준비 시간(분). 진행률 표시에 쓴다 */
    val plannedPrepMinutes: Int?,
    val blocks: List<MorningBlock>,
) {
    val done: List<MorningBlock> get() = blocks.filter { it.doneAtMillis != null }

    val remaining: List<MorningBlock> get() = blocks.filter { it.doneAtMillis == null }

    val isComplete: Boolean get() = blocks.isNotEmpty() && remaining.isEmpty()

    /** 다음에 마칠 블록. 전부 끝났으면 null */
    val current: MorningBlock? get() = remaining.firstOrNull()

    /**
     * 마지막으로 무언가 끝난 시각. 다음 블록의 **시작 시각**이다.
     *
     * 아직 아무것도 안 끝났으면 알람 해제 시각이다.
     */
    val lastMarkMillis: Long
        get() = done.maxOfOrNull { it.doneAtMillis ?: 0L } ?: startedAtMillis

    /**
     * 이 블록을 시작한 시각.
     *
     * 순서대로 마친다고 전제한다. 사용자가 순서를 건너뛰면 그 블록의 소요가
     * 앞 블록 시간까지 포함하게 되는데, 그 왜곡을 막으려면 시작 탭을 따로
     * 받아야 한다. 탭을 두 배로 늘리는 대신 **순서를 지키도록 화면이 다음 블록
     * 하나만 강조**하는 쪽을 택했다.
     */
    fun startOf(blockId: Long): Long {
        val index = blocks.indexOfFirst { it.blockId == blockId }
        if (index <= 0) return startedAtMillis
        return blocks[index - 1].doneAtMillis ?: startedAtMillis
    }

    /**
     * 남은 계획 소요(분). **병렬 블록은 제외한다.**
     *
     * 계획이 병렬을 합으로 더하지 않으므로(max 로 넣는다) 남은 시간에서도 빼야
     * 같은 기준이 된다. 넣으면 실제보다 빡빡하게 보여 사용자를 불필요하게
     * 재촉한다.
     */
    fun remainingPlannedMinutes(fromBlockId: Long? = null): Double {
        val start = fromBlockId?.let { id -> blocks.indexOfFirst { it.blockId == id } } ?: 0
        val from = if (start < 0) 0 else start
        return blocks.drop(from)
            .filter { it.doneAtMillis == null && !it.parallelizable }
            .sumOf { it.plannedMinutes }
    }

    /**
     * 그 블록을 **시작할 때** 쓸 수 있었던 여유(분).
     *
     * ## 왜 시작 시점인가
     *
     * 서버의 `slack_coef` 는 "여유가 많은 날은 준비가 느려진다" 를 학습한다.
     * 그 인과는 **블록을 하는 동안** 여유가 얼마였는지에 달려 있다. 끝난 뒤의
     * 여유로 재면 이미 그 블록이 쓴 시간이 반영돼 상관이 뒤집힌다 — 오래 걸린
     * 블록일수록 여유가 적게 나와 "여유가 적으면 느리다" 로 학습된다.
     *
     * 계산: (출발마감 − 블록 시작) − (이 블록 포함 남은 계획 소요)
     * 양수면 계획보다 시간이 남았던 것이고, 음수면 이미 늦은 상태였다.
     *
     * 출발마감을 모르면 null 이다. **0 으로 채우지 않는다** — "여유가 없었다" 와
     * "여유를 알 수 없다" 는 다르고, 0 을 넣으면 학습이 없는 상관을 만든다.
     */
    fun slackAtStartOf(blockId: Long): Double? {
        val departBy = departByMillis ?: return null
        val startedAt = startOf(blockId)
        val availableMinutes = (departBy - startedAt) / 60_000.0
        return round1(availableMinutes - remainingPlannedMinutes(blockId))
    }

    /** 마친 블록의 실제 소요(분). 순서대로 마쳤다는 전제다. */
    fun durationOf(blockId: Long, doneAtMillis: Long): Double =
        round1(max(0.0, (doneAtMillis - startOf(blockId)) / 60_000.0))

    /**
     * 세션이 너무 오래됐는가.
     *
     * 사용자가 기록을 시작만 하고 닫아 두면 다음 아침까지 남는다. 그 상태에서
     * 탭하면 소요가 "18시간" 으로 올라가 학습을 망친다.
     */
    fun isStale(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis - startedAtMillis > STALE_AFTER_MILLIS

    fun mark(blockId: Long, doneAtMillis: Long): MorningSession = copy(
        blocks = blocks.map {
            if (it.blockId == blockId) it.copy(doneAtMillis = doneAtMillis) else it
        }
    )

    /** 마지막 기록을 되돌린다. 잘못 누른 것을 고칠 길이 있어야 한다. */
    fun undoLast(): MorningSession {
        val last = done.maxByOrNull { it.doneAtMillis ?: 0L } ?: return this
        return copy(
            blocks = blocks.map {
                if (it.blockId == last.blockId) it.copy(doneAtMillis = null) else it
            }
        )
    }

    companion object {
        /**
         * 세션 유효 시간. 6시간이면 어떤 아침 루틴도 넉넉히 덮는다.
         *
         * 이보다 오래된 세션은 버린다 — 되살려 봤자 실제 소요를 알 수 없고,
         * 잘못된 값이 학습에 들어가는 것이 기록이 없는 것보다 나쁘다.
         */
        const val STALE_AFTER_MILLIS = 6L * 60 * 60 * 1000

        private fun round1(value: Double): Double = Math.round(value * 10) / 10.0
    }
}

/** 아침 기록 대상 블록 하나. */
data class MorningBlock(
    val blockId: Long,
    val name: String,
    val plannedMinutes: Double,
    val parallelizable: Boolean = false,
    /** 마친 시각. null 이면 아직 진행 중 */
    val doneAtMillis: Long? = null,
)
