package com.swpp.wakeup.domain.model

/**
 * 아침 루틴 블록의 화면용 표현. back-spec 4.3 `RoutineBlock` 과 대응한다.
 *
 * ## 왜 블록으로 쪼개는가
 *
 * 준비 시간을 "30분" 한 덩어리로 받으면 두 가지를 못 한다. 왜 30분인지
 * 설명할 수 없고, 늦었을 때 무엇을 줄일지 고를 수 없다. 블록으로 쪼개면
 * 관측도 블록 단위로 쌓여서 "샤워는 원래 14분" 같은 개인 분포가 생긴다.
 *
 * ## null 이 정상인 값
 *
 * [observedMeanLabel] 은 관측이 없으면 null 이다. **0 분으로 채우지 않는다** —
 * "아직 모른다" 와 "0분으로 측정됐다" 는 다르다.
 */
data class RoutineBlockView(
    val id: Long,
    val name: String,
    val minMinutes: Int,
    val maxMinutes: Int,

    /** "12~18분" / 범위가 한 점이면 "5분" */
    val rangeLabel: String,

    val dropCost: DropCost,
    val parallelizable: Boolean,
    val includedByDefault: Boolean,
    val order: Int,

    val observationCount: Int,
    /** "실측 평균 14.2분". 관측이 없으면 null */
    val observedMeanLabel: String?,
    /**
     * 학습 상태 한 줄. 사용자가 범위를 스스로 교정하게 만드는 장치다.
     *
     * 관측이 쌓였는데 신고 범위를 벗어났으면 그 사실을 적는다 — front-spec S4
     * 가 정한 동작이다.
     */
    val learningNote: String,
    /**
     * 실측 평균이 신고 범위를 벗어났는가.
     *
     * [learningNote] 문구를 검사해서 알아내면 문구를 고칠 때마다 조건이 깨진다.
     * 판정은 매핑에서 한 번 하고 결과만 들고 다닌다.
     */
    val rangeMismatch: Boolean = false,

    /**
     * 이 일정에서 포함되는가.
     *
     * 일정 문맥 없이 정의만 볼 때는 [includedByDefault] 와 같은 값이 온다.
     */
    val checked: Boolean = includedByDefault,
    /**
     * 이 일정에서 사용자가 기본값을 덮어썼는가.
     *
     * "기본 포함인데 이번엔 뺐다" 를 화면이 구분해 보여주기 위한 값이다.
     */
    val explicit: Boolean = false,
) {
    /** 범위가 한 점이면 변동성이 없다고 신고한 것이다. 확률 계산이 시작되지 않는다 */
    val isPointRange: Boolean get() = minMinutes == maxMinutes

    val hasObservations: Boolean get() = observationCount > 0
}

/**
 * 포기했을 때의 손실.
 *
 * 늦었을 때 무엇을 버릴지 고르는 근거다. [IMPOSSIBLE] 은 절대 못 버리는 것이라
 * 제안 목록에서 아예 빼야 한다.
 */
enum class DropCost(val wire: String, val label: String, val hint: String) {
    NONE("none", "없음", "건너뛰어도 무관함"),
    SMALL("small", "작음", "아쉽지만 건너뛸 수 있음"),
    MEDIUM("medium", "보통", "가능하면 지키고 싶음"),
    LARGE("large", "큼", "건너뛰면 하루가 틀어짐"),
    IMPOSSIBLE("impossible", "불가", "반드시 해야 함");

    companion object {
        /**
         * 서버 문자열을 열거형으로. 모르는 값은 [MEDIUM] 으로 떨어뜨린다.
         *
         * 서버가 선택지를 늘렸을 때 앱이 죽지 않게 한다. 열거형 파싱 실패로
         * 화면 전체가 비는 것보다 하나가 기본값으로 보이는 편이 낫다.
         */
        fun from(wire: String?): DropCost =
            entries.firstOrNull { it.wire == wire } ?: MEDIUM
    }
}

/**
 * 블록 편집 화면이 필요한 것 전부.
 *
 * [simpleSumLabel] 은 **단순 합**이다. 실제 준비 시간은 병렬 블록을 max 로
 * 넣고 선행 관계를 따지므로 이 값과 다르다. 화면이 그 차이를 반드시 밝혀야
 * 한다 — 여기 숫자와 알람 근거 카드의 숫자가 달라 보이면 사용자가 둘 중
 * 하나를 고장으로 여긴다.
 */
data class RoutineEditorState(
    val blocks: List<RoutineBlockView> = emptyList(),
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    /** 실패가 아닌 안내. "다음 계산에 반영됨" 같은 것 */
    val notice: String? = null,

    /**
     * 일정별 체크를 편집하는 중이면 그 일정 id.
     *
     * null 이면 블록 **정의**를 편집하는 중이다. 두 모드가 같은 목록을 쓰지만
     * 저장 대상이 다르다.
     */
    val eventId: Long? = null,
    /** 일정별 모드에서 헤더에 쓸 일정 이름 */
    val eventTitle: String? = null,

    /**
     * 불러온 시점의 체크 상태. 저장할 때 **바뀐 것만** 보내기 위한 기준이다.
     *
     * 전부 보내면 손대지 않은 블록까지 `explicit` 로 표시되고, 그러면 나중에
     * 기본 포함값을 고쳐도 이 일정에는 반영되지 않는다.
     */
    val original: Map<Long, Boolean> = emptyMap(),

    /** 편집 중인 블록. null 이면 시트가 닫혀 있다 */
    val editing: BlockDraft? = null,
) {
    val includedCount: Int get() = blocks.count { it.checked }

    /** "단순 합 42~68분". 포함된 블록만 더한다 */
    val simpleSumLabel: String?
        get() {
            val included = blocks.filter { it.checked }
            if (included.isEmpty()) return null
            val lo = included.sumOf { it.minMinutes }
            val hi = included.sumOf { it.maxMinutes }
            return if (lo == hi) "단순 합 ${lo}분" else "단순 합 ${lo}~${hi}분"
        }

    val isEventMode: Boolean get() = eventId != null

    /** 불러온 뒤 바뀐 체크만. 저장 요청 본문이 된다 */
    val changes: Map<Long, Boolean>
        get() = blocks
            .filter { original[it.id] != it.checked }
            .associate { it.id to it.checked }

    val dirty: Boolean get() = changes.isNotEmpty()

    /** 관측이 쌓였는데 신고 범위를 벗어난 블록 수. 안내 배너에 쓴다 */
    val outOfRangeCount: Int get() = blocks.count { it.rangeMismatch }
}

/**
 * 블록 추가·수정 입력값.
 *
 * 문자열로 들고 있는다. 사용자가 지우는 중간 상태("1" → "" → "12")를 Int 로는
 * 표현할 수 없다. 저장 직전에 한 번 검증한다.
 */
data class BlockDraft(
    /** null 이면 새로 만드는 중이다 */
    val id: Long? = null,
    val name: String = "",
    val minText: String = "",
    val maxText: String = "",
    val dropCost: DropCost = DropCost.MEDIUM,
    val parallelizable: Boolean = false,
    val includedByDefault: Boolean = true,
    /** 필드별 오류. 키는 `name` / `min` / `max` */
    val errors: Map<String, String> = emptyMap(),
) {
    val isNew: Boolean get() = id == null

    val title: String get() = if (isNew) "블록 추가" else "블록 수정"

    /**
     * 저장 가능한지. 화면이 버튼을 잠그는 데 쓴다.
     *
     * 여기서 통과해도 서버가 거절할 수 있다(이름 중복). 그 오류는 응답으로
     * 받아서 [errors] 에 채운다 — 클라이언트가 중복 검사를 하려면 전체 목록이
     * 최신이어야 하고, 그 가정은 깨지기 쉽다.
     */
    fun validate(): BlockDraft {
        val found = mutableMapOf<String, String>()

        val trimmed = name.trim()
        if (trimmed.isEmpty()) found["name"] = "이름을 입력해야 한다."
        else if (trimmed.length > MAX_NAME) found["name"] = "${MAX_NAME}자 이내로 입력한다."

        val lo = minText.trim().toIntOrNull()
        val hi = maxText.trim().toIntOrNull()

        if (lo == null) found["min"] = "숫자를 입력해야 한다."
        else if (lo < 0) found["min"] = "0분 이상이어야 한다."
        else if (lo > MAX_MINUTES) found["min"] = "${MAX_MINUTES}분을 넘을 수 없다."

        if (hi == null) found["max"] = "숫자를 입력해야 한다."
        else if (hi > MAX_MINUTES) found["max"] = "${MAX_MINUTES}분을 넘을 수 없다."

        // 뒤집힌 범위는 표준편차를 음수로 만들어 서버의 분포 생성이 터진다.
        // 서버에도 DB 제약이 있지만 여기서 막아야 왕복 없이 알려줄 수 있다.
        if (lo != null && hi != null && lo > hi) {
            found["max"] = "최대가 최소보다 작을 수 없다."
        }

        return copy(name = trimmed, errors = found)
    }

    companion object {
        const val MAX_NAME = 40
        const val MAX_MINUTES = 480
    }
}
