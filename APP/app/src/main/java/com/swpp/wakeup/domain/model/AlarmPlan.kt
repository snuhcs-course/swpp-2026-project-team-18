package com.swpp.wakeup.domain.model

/**
 * 알람 결정 화면(Figma ④)이 필요한 값.
 *
 * back-spec.md 4.5 `AlarmPlan` 과 대응하지만 **화면용 표현**이다. 서버가
 * `/api/events` 안에 실어 주는 `alarm_plan` 을 표시 문자열까지 가공한 형태다.
 *
 * **null 이 정상인 필드가 많다.** 집 위치가 없거나 장소가 없으면 서버가 계산을
 * 하지 못하고, 관측이 없으면 확률을 만들 수 없다. [status] 로 이유를 구분한다.
 */
data class AlarmPlanView(
    val eventId: Long,

    /** "오늘" / "내일 아침" / "3일 뒤" */
    val whenLabel: String,
    /** "9월 18일 금" */
    val dateLabel: String,

    /** "09:00 자료구조 및 알고리즘" */
    val eventTitle: String,
    /** "302동 105호 · 서울 관악구 …" */
    val eventPlace: String,
    /** 태그 라벨. 예 "수업" */
    val sensitivityTag: String?,

    /** "7:40". 계산 못 했으면 null */
    val alarmAt: String?,
    /** "AM" / "PM" */
    val meridiem: String,
    /** "7시간 28분 남음". 계산 못 했으면 null */
    val remaining: String?,

    /** 관측이 없으면 null. 임의값을 넣지 않는다 */
    val onTimeProbability: Int?,
    /** 적용된 τ. 예 0.90 */
    val tauUsed: Double?,

    /**
     * 확률을 그리는 데 필요한 것 전부. 확률이 없을 때의 이유와 사용자가 할
     * 일까지 담는다. 화면이 `confidence_basis` 문자열을 직접 분기하지 않게
     * 저장소에서 문구로 바꿔 둔다.
     */
    val confidence: ConfidenceView,

    /** 준비·이동·버퍼 분해 */
    val breakdown: List<PlanRow>,

    /**
     * 준비 시간을 블록별로 쪼갠 내역. 루틴 블록이 없으면 빈 목록이다.
     *
     * [breakdown] 의 "준비 시간" 한 줄을 펼친 것이다. 합이 그 줄과 다를 수
     * 있다 — 병렬 블록은 max 로 들어간다.
     */
    val prepBlocks: List<PrepBlockLine> = emptyList(),
    val totalMinutes: Int?,
    /** "8:50 도착 예정" */
    val arrivalLine: String?,

    /** `ok` / `no_home` / `no_place` / `route_failed` */
    val status: String,
    val statusLabel: String?,

    /** 실제 계산에 쓴 경로 key. 경로 변경 화면의 초기 선택값으로 쓴다 */
    val routeKey: String? = null,
    /**
     * 고른 경로가 사라져 서버가 다른 경로로 대체했는지.
     *
     * 배차가 바뀌면 어제 고른 노선이 오늘 없을 수 있다. 그때 조용히 다른
     * 경로로 계산하면 사용자는 자기가 고른 경로대로라고 믿는다.
     */
    val routeFellBack: Boolean = false,
) {
    val isComputed: Boolean get() = status == "ok" && alarmAt != null

    /** 막대 길이를 상대 비율로 그리기 위한 최대값. */
    val maxRowMinutes: Int
        get() = breakdown.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
}

/**
 * 계산 근거 한 줄.
 *
 * [note] 가 이 앱의 핵심이다. "20분" 만 보여주면 왜 그 값인지 알 수 없다.
 * "카카오 실측 경로 · 20분 · 4.6km · 환승 1회" 처럼 근거를 함께 준다.
 */
data class PlanRow(
    val label: String,
    val minutes: Int,
    val note: String?,
    val kind: Kind,
) {
    enum class Kind { PREP, TRAVEL, BUFFER }
}

/**
 * 정시 도착 확률의 표시 형태.
 *
 * **[percent] 가 null 인 것이 정상 상태다.** 서버는 준비·이동 **양쪽** 모두
 * 변동성을 알 때만 확률을 만든다. 한쪽만 알 때 없는 쪽을 0 분산으로 치면
 * "92% 정시 도착" 이 실제로는 "이동이 예측대로라면 92%" 가 되는데 그 조건이
 * 화면에 없다. 그래서 서버가 비워 두고, 화면은 [reason] 과 [action] 으로
 * 왜 비었는지와 무엇을 하면 채워지는지를 밝힌다.
 */
data class ConfidenceView(
    /** 0~100. null 이면 아직 계산할 수 없다 */
    val percent: Int?,
    /** "정시 도착 확률 92%" / "정시 도착 확률 학습 중" */
    val headline: String,
    /** [percent] 가 null 일 때 그 이유. 있으면 null */
    val reason: String?,
    /** 사용자가 하면 확률이 생기는 일. 없거나 할 일이 없으면 null */
    val action: String?,
) {
    val isLearning: Boolean get() = percent == null

    /** 확률이 목표치를 넘겼는지. 초록/노랑 구분에 쓴다 */
    fun meets(tau: Double?): Boolean {
        val p = percent ?: return false
        val target = tau?.takeIf { it in 0.0..1.0 }?.let { (it * 100).toInt() } ?: 90
        return p >= target
    }
}

/**
 * 준비 블록 한 줄.
 *
 * [detail] 에 신고 범위와 관측 수를 함께 적는다. "샤워 14분" 만 보여주면
 * 사용자가 신고한 12~18분에서 왜 14분이 나왔는지 알 수 없다.
 */
data class PrepBlockLine(
    val blockId: Long?,
    val name: String,
    /** "14분" / "4.5분" — 소수 첫째 자리까지, 정수면 생략 */
    val minutesLabel: String,
    /** 막대 길이 계산용 */
    val minutes: Double,
    /** "신고 12~18분 · 관측 7회로 학습됨" */
    val detail: String,
    /** 관측 기반이면 true. 화면이 색으로 구분한다 */
    val learned: Boolean,
    /** 병렬 블록. 합계에 그대로 더해지지 않는다는 표시가 필요하다 */
    val parallelizable: Boolean,
)
