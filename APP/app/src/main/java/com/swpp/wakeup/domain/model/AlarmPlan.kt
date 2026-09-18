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

    /** 준비·이동·버퍼 분해 */
    val breakdown: List<PlanRow>,
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
