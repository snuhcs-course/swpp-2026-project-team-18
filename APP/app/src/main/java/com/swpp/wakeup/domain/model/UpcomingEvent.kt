package com.swpp.wakeup.domain.model

import java.time.LocalDate

/**
 * 홈 화면 목록에 뜨는 일정 하나.
 *
 * `/api/events` 응답을 표시용으로 옮긴 것이다. 시각 포매팅과 확률 계산은
 * 저장소(매퍼)의 책임이고 화면은 받은 값을 그리기만 한다.
 *
 * **[alarmAt] 과 [onTimeProbability] 는 null 일 수 있다.**
 * - `alarmAt = null` → 집 위치나 장소가 없어 서버가 알람을 계산하지 못했다
 * - `onTimeProbability = null` → 관측이 쌓이기 전이라 확률을 만들 수 없다
 *
 * 두 경우 모두 화면이 있는 그대로 알려준다. 임의값으로 채우지 않는다.
 */
data class UpcomingEvent(
    val id: Long,
    /** 일정 시각. 예 "09:00" */
    val startTime: String,
    /** 요일 한 글자. 예 "목" */
    val dayLabel: String,
    val title: String,
    /** 장소와 이동 수단을 합친 한 줄. 계산 불가 사유가 들어올 수도 있다 */
    val placeAndRoute: String,
    /** 계산된 알람 시각. 예 "7:40". 계산 못 했으면 null */
    val alarmAt: String?,
    /** 정시 도착 확률. 관측이 없으면 null */
    val onTimeProbability: Int?,
    /** 태그 칩. 예 "수업" */
    val tag: String?,
    /** `ok` / `no_home` / `no_place` / `route_failed` */
    val planStatus: String,
    /** 사람이 읽을 상태 설명. 예 "집 위치 미설정" */
    val planStatusLabel: String?,
    /** 정렬용 */
    val startAtEpochSecond: Long,
    /** 날짜 묶기용 */
    val startDate: LocalDate,
) {
    val hasAlarm: Boolean get() = alarmAt != null

    /**
     * 확률을 위험 등급으로 바꾼다. 색은 화면이 정하고 경계값만 여기서 관리한다.
     *
     * 확률이 없으면 [Risk.UNKNOWN] 이다. 등급을 억지로 정하지 않는다.
     * 경계는 back-spec 의 τ 기본값 0.90 을 기준으로 잡았다.
     */
    val risk: Risk
        get() = when {
            onTimeProbability == null -> Risk.UNKNOWN
            onTimeProbability >= 90 -> Risk.SAFE
            onTimeProbability >= 75 -> Risk.WARN
            else -> Risk.DANGER
        }

    enum class Risk { SAFE, WARN, DANGER, UNKNOWN }
}

/** 날짜별로 묶은 목록. 홈 화면의 "오늘" / "내일" 섹션이 된다. */
data class EventSection(
    /** 예 "오늘", "내일", "9월 20일 일" */
    val label: String,
    /** 예 "9월 17일 목" */
    val dateLabel: String,
    val events: List<UpcomingEvent>,
)
