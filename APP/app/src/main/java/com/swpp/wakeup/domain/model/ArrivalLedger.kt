package com.swpp.wakeup.domain.model

// Partly AI-generated (Claude)
/**
 * 이 기기가 아는 도착(일정 id → 도착 시각). todo §2 "앱 재시작 후 도착 판정 복원".
 *
 * 도착은 추적 서비스가 메모리([com.swpp.wakeup.sensing.TripLiveState])에만 들고 있어서 앱
 * 프로세스가 다시 시작되면 사라졌다. 그러면 이미 도착한 일정이 홈에 남고 알람 결정 화면이
 * "출발 확인 안 됨" 으로 돌아간다. 그래서 짧게 디스크에 남긴다
 * ([com.swpp.wakeup.data.local.ArrivalStore]). 다른 기기·재설치 뒤에는 서버 관측
 * (`GET /api/observations?event=&kind=arrive`)으로 채운다.
 *
 * 이 파일은 순수 함수다(JVM 테스트 대상). 좌표는 담지 않는다 — 시각만 있으면 된다.
 */
object ArrivalLedger {

    /** 이보다 오래된 도착은 버린다. 홈은 시작 뒤 1시간까지만 일정을 보여 준다 */
    const val KEEP_MILLIS = 3 * 24 * 60 * 60_000L

    /** 이만큼만 남긴다. 하루 일정 몇 개 × 3일이면 넉넉하다 */
    const val MAX_ENTRIES = 30

    /** 기기 시계가 조금 어긋나 미래로 찍힌 도착은 받아 준다. 그보다 먼 미래는 깨진 값이다 */
    const val FUTURE_SLACK_MILLIS = 10 * 60_000L

    /** 서버 관측으로 도착을 다시 확인할 범위. 알람(없으면 시작 1시간 전)부터 시작 뒤 5시간까지 */
    const val SERVER_CHECK_BEFORE_START_MILLIS = 60 * 60_000L
    const val SERVER_CHECK_AFTER_START_MILLIS = 5 * 60 * 60_000L

    /** 같은 일정의 서버 확인 사이 최소 간격 */
    const val SERVER_CHECK_INTERVAL_MILLIS = 5 * 60_000L

    /** `"12:1730000000000;13:..."`. 순서는 뜻이 없다 */
    fun encode(arrivals: Map<Long, Long>): String =
        arrivals.entries.joinToString(";") { (id, at) -> "$id:$at" }

    /** [encode] 의 반대. 깨진 항목은 그 항목만 버린다 */
    fun decode(raw: String?): Map<Long, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(';').mapNotNull { part ->
            val pieces = part.split(':')
            if (pieces.size != 2) return@mapNotNull null
            val id = pieces[0].trim().toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
            val at = pieces[1].trim().toLongOrNull()?.takeIf { it > 0L } ?: return@mapNotNull null
            id to at
        }.toMap()
    }

    /** 오래됐거나 너무 먼 미래인 도착을 빼고, 최근 [MAX_ENTRIES] 개만 남긴다 */
    fun prune(arrivals: Map<Long, Long>, nowMillis: Long): Map<Long, Long> =
        arrivals.entries
            .filter { (_, at) -> nowMillis - at <= KEEP_MILLIS && at - nowMillis <= FUTURE_SLACK_MILLIS }
            .sortedByDescending { it.value }
            .take(MAX_ENTRIES)
            .associate { it.key to it.value }

    /**
     * 한 일정의 도착을 적는다. 새 판정이 앞 것을 덮는다 — "도착했어요" 는 도착이 없을 때만
     * 기록되므로 덮는 경우는 수동 뒤의 GPS 도착뿐이고, 서버도 GPS 를 먼저 쓴다([representative]).
     */
    fun add(arrivals: Map<Long, Long>, eventId: Long, atMillis: Long, nowMillis: Long): Map<Long, Long> =
        prune(arrivals + (eventId to atMillis), nowMillis)

    /**
     * 서버에 도착을 물어볼 일정인가. 이동이 있었을 법한 때(알람 뒤 ~ 시작 5시간 뒤)이고 아직
     * 이 기기가 도착을 모를 때만이다. 다른 일정까지 매번 부르면 홈을 열 때마다 호출이 는다.
     *
     * @param alarmAtMillis 알람 시각. 계산하지 못했으면 null
     */
    fun shouldAskServer(
        startAtMillis: Long,
        alarmAtMillis: Long?,
        skipped: Boolean,
        knownArrival: Boolean,
        nowMillis: Long,
        lastCheckedAtMillis: Long?,
    ): Boolean {
        if (skipped || knownArrival) return false
        val opensAt = minOf(alarmAtMillis ?: Long.MAX_VALUE, startAtMillis - SERVER_CHECK_BEFORE_START_MILLIS)
        if (nowMillis < opensAt) return false
        if (nowMillis > startAtMillis + SERVER_CHECK_AFTER_START_MILLIS) return false
        if (lastCheckedAtMillis != null && nowMillis - lastCheckedAtMillis in 0 until SERVER_CHECK_INTERVAL_MILLIS) {
            return false
        }
        return true
    }

    /** 서버 관측 한 건. [manual] 은 `detector=manual`("도착했어요") */
    data class Observed(val atMillis: Long, val manual: Boolean)

    /**
     * 서버 관측 중 이 일정의 도착 시각. GPS 중 가장 이른 것, 없으면 "도착했어요" 중 가장 이른
     * 것이다(서버 `apps/observations/selection.py` 와 같은 순서). 없으면 null.
     */
    fun representative(observed: List<Observed>): Long? =
        observed.filter { !it.manual }.minOfOrNull { it.atMillis }
            ?: observed.filter { it.manual }.minOfOrNull { it.atMillis }
}
