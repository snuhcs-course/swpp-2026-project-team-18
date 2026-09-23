package com.swpp.wakeup.domain.model

/**
 * 이동 구간 하나. 경로 카드의 가로 막대 한 칸이다.
 *
 * 카카오맵이 "3분 | 버스 12분 | 6분" 을 가로로 보여 주는 것과 같은 것을
 * 그리려고 만들었다. 소요시간 숫자 하나만으로는 **무엇이 오래 걸리는지**
 * 알 수 없다. 25분 중 18분이 버스인 경로와 12분이 도보인 경로는 같은
 * 25분이지만 사용자의 선택이 갈린다.
 *
 * ## 색을 앱이 정한다
 *
 * 서버는 [kind] 와 [busType] 만 준다. hex 를 서버가 내리면 다크 모드 색을
 * 손볼 때마다 서버를 배포해야 한다. 색 결정은 `SegmentPalette` 에 있다.
 */
data class RouteSegment(
    val kind: Kind,
    val seconds: Int,
    /** "도보" / "대기" / "5511" / "2호선" */
    val label: String,
    /** 노선명. 지하철 색을 고르는 열쇠다. 도보·대기면 빈 문자열 */
    val lineName: String = "",
    /** 버스 종류. 버스 색을 고르는 열쇠다. 버스가 아니면 빈 문자열 */
    val busType: String = "",
    /** 도시철도 권역. 같은 "1호선" 을 서울·부산에서 다른 색으로 칠한다. */
    val region: String = "",
    /** 첫 항목이 승차역/정류장, 마지막이 하차역/정류장이다. */
    val stops: List<String> = emptyList(),
    /** 카카오가 준 수단 안내. 예: "2호선 (신림 > 강남)" */
    val guidance: String = "",
    /** 실시간 다음 차량과 그다음 차량. 최대 두 건이다. */
    val arrivals: List<RouteArrival> = emptyList(),
    /** 버스 배차간격. 실시간 도착이 없을 때 계획 정보로 쓸 수 있다. */
    val headwayMinutes: Int? = null,
) {

    enum class Kind {
        /** 걷는 구간. 회색이다. */
        WALK,

        /**
         * 차를 기다리는 시간.
         *
         * 카카오가 앞뒤 도보와 대기를 쪼개 주지 않아서 서버가 거리로 도보를
         * 환산하고 남는 시간을 여기에 담는다. 도보와 색을 나누는 이유는
         * "걸어서 9분" 과 "기다려서 9분" 이 다른 일이기 때문이다.
         */
        WAIT,

        BUS,
        SUBWAY,
        CAR,
        BICYCLE,

        /** 서버가 새 종류를 보냈다. 중립색으로 그린다. */
        UNKNOWN,
        ;

        companion object {
            /**
             * 서버 문자열을 종류로.
             *
             * 모르는 값에서 예외를 던지지 않는다 — 서버가 수단을 하나
             * 추가했을 때 구버전 앱이 경로 화면째로 죽으면 안 된다.
             */
            fun from(raw: String?): Kind = when (raw?.lowercase()) {
                "walk" -> WALK
                "wait" -> WAIT
                "bus" -> BUS
                "subway" -> SUBWAY
                "car" -> CAR
                "bicycle" -> BICYCLE
                else -> UNKNOWN
            }
        }
    }

    /** 막대 안에 쓸 글자. "12분" */
    val minutesLabel: String get() = "${minutes}분"

    /**
     * 분. 0분이라고 쓰지 않으려고 최소 1로 올린다.
     *
     * 막대 폭은 [seconds] 로 계산하므로 이 반올림이 폭을 왜곡하지 않는다.
     */
    val minutes: Int get() = maxOf(1, Math.round(seconds / 60.0).toInt())
}

/**
 * 역·정류장에 들어오는 차량 하나.
 *
 * [seconds] 는 사용자가 승차지점까지 가는 시간이 아니라 **차량이 그 지점에
 * 도착하기까지 남은 시간**이다. 둘을 섞으면 사용자가 3분 뒤에 출발하라는
 * 뜻으로 오해한다.
 */
data class RouteArrival(
    val seconds: Int,
    val message: String,
    val source: String = "",
    val crowding: String = "",
) {
    /** "3분 20초 뒤 도착 · 여유" */
    val displayText: String
        get() = listOf(message.ifBlank { formatSeconds(seconds) }, crowding)
            .filter { it.isNotBlank() }
            .joinToString(" · ")

    companion object {
        fun formatSeconds(rawSeconds: Int): String {
            val safe = rawSeconds.coerceAtLeast(0)
            if (safe == 0) return "곧 도착"
            val minutes = safe / 60
            val seconds = safe % 60
            return when {
                minutes > 0 && seconds > 0 -> "${minutes}분 ${seconds}초 뒤 도착"
                minutes > 0 -> "${minutes}분 뒤 도착"
                else -> "${seconds}초 뒤 도착"
            }
        }
    }
}

/**
 * 한 경로의 구간 묶음.
 *
 * 폭 계산을 여기에 둔다. 컴포저블에 두면 테스트할 수 없고, 이 계산은
 * 틀렸을 때 눈에 잘 띄지 않는다 — 막대가 살짝 짧은 것을 아무도 버그로
 * 신고하지 않는다.
 */
data class RouteSegments(val items: List<RouteSegment>) {

    val isEmpty: Boolean get() = items.isEmpty()

    val totalSeconds: Int get() = items.sumOf { it.seconds }

    /**
     * 각 구간이 차지할 비율. 합은 정확히 1.0 이다.
     *
     * 마지막 구간이 반올림 오차를 흡수한다. 흡수하지 않으면 막대 오른쪽에
     * 배경색이 1~2px 남아 선택 테두리처럼 보인다.
     */
    fun weights(): List<Float> {
        val total = totalSeconds
        if (total <= 0 || items.isEmpty()) return emptyList()

        val out = ArrayList<Float>(items.size)
        var used = 0f
        items.forEachIndexed { index, item ->
            val w = if (index == items.lastIndex) {
                1f - used
            } else {
                (item.seconds.toFloat() / total).coerceAtLeast(MIN_WEIGHT)
            }
            used += w
            out.add(w)
        }
        return out
    }

    companion object {
        /**
         * 구간 하나의 최소 폭 비율.
         *
         * 1분짜리 환승 도보가 25분 경로에서 4% 인데, 그대로 두면 1~2px 이
         * 되어 보이지 않는다. 있는 구간이 안 보이면 없는 것과 같다.
         */
        const val MIN_WEIGHT = 0.04f
    }
}
