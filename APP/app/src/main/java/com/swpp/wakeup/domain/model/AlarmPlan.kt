package com.swpp.wakeup.domain.model

import com.swpp.wakeup.sensing.GeoPoint

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
    /**
     * 도착 예정 시각만. "8:50".
     *
     * 진행 바가 오른쪽에 시각 하나만 크게 놓으므로 [arrivalLine] 에서 문구를
     * 잘라 쓰지 않는다. 문구를 바꾸면 자르는 코드가 조용히 깨진다.
     */
    val arrivalAt: String? = null,

    /**
     * 약속 시각(epoch ms). 지각 판정의 **유일한 기준**이다.
     *
     * 표시용이 아니라 계산용이다. `도착 예정`(약속 − 안전 버퍼)을 기준으로 쓰면
     * 버퍼를 두 번 쓰게 되어, 약속보다 일찍 도착하는데도 지각으로 표시된다
     * ([ArrivalOutlook] 의 설명).
     */
    val startAtMillis: Long? = null,
    /** 이동에 걸리는 계획 분. 남은 거리를 분으로 바꾸는 환산율이다 */
    val travelMinutes: Int? = null,
    /**
     * 준비 단계가 있는 일정인가.
     *
     * 집에서 출발하지 않는 일정(일정마다 출발지를 따로 고른 경우)은 준비 시간이
     * **해당되지 않는다.** 계산 방법에서 준비 항목을 빼고, 진행 단계에서도
     * "준비 중" 을 건너뛰어 알람 전 다음이 바로 이동 중이다.
     *
     * `prepMinutes == 0` 으로 판별하지 않는다 — 준비를 1분 미만으로 신고한
     * 사람과 구분되지 않는다.
     */
    val prepApplies: Boolean = true,
    /**
     * 이 계획을 계산한 시각(epoch ms). 모르면 null.
     *
     * 화면이 "N분 전 계산" 을 적는 데 쓴다. 임박한 일정은 백그라운드가 15분마다
     * 경로를 다시 조회해 이 값과 알람 시각이 바뀐다. 표시하지 않으면 사용자는
     * 숫자가 방금 받은 것인지 어제 것인지 모른 채로 움직인다.
     */
    val computedAtMillis: Long? = null,

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
    /**
     * 알람 시각이 지났는가.
     *
     * "알람 전" 과 "준비 중" 을 가르는 유일한 근거다. [remaining] 문구("지난
     * 알람")로 판별하면 표시 문자열을 바꿀 때마다 단계 판정이 깨진다.
     */
    val alarmPassed: Boolean = false,
    /**
     * 일정 시각까지 지났는가.
     *
     * [alarmPassed] 만으로는 "준비 중" 을 벗어날 수 없다. 이틀 전 일정을 열어도
     * 알람은 지났으니 계속 준비 중이 된다. 일정 자체가 끝났으면 단계를 말하지
     * 않는 쪽이 맞다([TripStage.PAST]).
     */
    val eventPassed: Boolean = false,
    /**
     * 사람이 읽는 경로 설명. "2호선 → 5513".
     *
     * 알람 카드가 "무엇을 기준으로 이 시각인가" 를 밝히는 데 쓴다. 서버는
     * 예전부터 `route_detail` 로 주고 있었지만 화면이 받지 않고 있었다.
     */
    val routeDetail: String? = null,
    /**
     * 경로 폴리라인. 비어 있으면 지도와 진행률을 그릴 수 없다.
     *
     * 서버가 알람을 계산할 때 함께 받아 보관한 좌표다. 화면을 열 때마다 경로
     * API 를 다시 부르지 않으므로 호출량이 늘지 않는다.
     */
    val routePath: List<GeoPoint> = emptyList(),
    /**
     * 서버가 계산한 경로 길이(m). 표시용이다.
     *
     * 진행률의 분모는 앱이 [routePath] 로 다시 센 값을 쓴다. 분자와 분모가 같은
     * 계산에서 나와야 목적지에 닿았을 때 정확히 100% 가 된다.
     */
    val routeDistanceM: Int? = null,
) {
    val isComputed: Boolean get() = status == "ok" && alarmAt != null

    /** 지도와 진행률을 그릴 수 있는가. 좌표가 둘 미만이면 선이 되지 않는다 */
    val hasRoutePath: Boolean get() = routePath.size >= 2

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
