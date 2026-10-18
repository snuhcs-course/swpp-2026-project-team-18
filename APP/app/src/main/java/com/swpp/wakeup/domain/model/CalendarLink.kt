package com.swpp.wakeup.domain.model

import com.swpp.wakeup.calendar.DeviceCalendarSource

// Partly AI-generated (Claude)
/** 캘린더 읽기 권한. 화면이 할 일이 셋 다 달라서 둘(있음/없음)로 뭉치지 않는다. */
enum class CalendarPermission {
    /** 아직 확인하지 않았다. 잠깐 스피너를 보인다 */
    UNKNOWN,

    /** 권한이 있다 */
    GRANTED,

    /** 권한이 없고, 이번 실행에서 아직 요청하지 않았다 → "권한 허용하기" */
    MISSING,

    /** 요청했다가 거절됐다 → 기기 설정에서 켜야 한다고 함께 말한다 */
    DENIED,
}

// Partly AI-generated (Claude)
/**
 * 캘린더 연동 상태. ⑱ 캘린더 연동, ⑯ 설정, 캘린더 가져오기가 같이 쓴다.
 *
 * ## 연동의 뜻
 *
 * "이 캘린더의 일정을 앱이 읽어도 된다" 까지다. 연동한 캘린더의 일정만 가져오기
 * 화면에 나오고, 그중 **사용자가 고른 것만** 서버로 간다. 자동으로 올리지 않는다.
 *
 * [storedIds] 는 저장된 그대로다. 계정을 지워 기기에서 사라진 캘린더의 id 가 남아
 * 있을 수 있으므로, 화면과 읽기는 지금 있는 캘린더와 겹치는 [linkedIds] 만 쓴다.
 */
data class CalendarLinkState(
    val permission: CalendarPermission = CalendarPermission.UNKNOWN,
    val loading: Boolean = false,
    val calendars: List<DeviceCalendarSource> = emptyList(),
    val storedIds: Set<Long> = emptySet(),
) {
    /** 지금 기기에 있는 캘린더 중 연동한 것 */
    val linkedIds: Set<Long>
        get() = storedIds intersect calendars.mapTo(HashSet()) { it.id }

    val linkedCount: Int get() = linkedIds.size

    fun isLinked(id: Long): Boolean = id in linkedIds

    /** 화면에 보일 순서. 규칙은 [CalendarSources.ordered] */
    val ordered: List<DeviceCalendarSource> get() = CalendarSources.ordered(calendars)

    /** 권한은 있는데 기기에 캘린더가 하나도 없다. 권한 없음과는 할 일이 다르다 */
    val noCalendars: Boolean
        get() = permission == CalendarPermission.GRANTED && !loading && calendars.isEmpty()

    /** 목록을 그릴 수 있는가 */
    val showList: Boolean
        get() = permission == CalendarPermission.GRANTED && calendars.isNotEmpty()
}

// Partly AI-generated (Claude)
/**
 * ⑱ 캘린더 연동(가입 직후) 화면 상태.
 *
 * **고른 것은 "연동하고 시작하기" 를 눌러야 저장된다.** ⑯ 설정의 스위치는 바로
 * 저장되지만 여기는 체크하고 버튼으로 끝내는 화면이라, "나중에" 를 누르면 아무것도
 * 남기지 않아야 한다.
 */
data class CalendarOnboardingState(
    val selected: Set<Long> = emptySet(),
    /** 화면을 닫아야 한다 */
    val done: Boolean = false,
    /** 저장하고 닫았으면 연동한 개수. "나중에" 로 닫았으면 null 이다 */
    val savedCount: Int? = null,
) {
    /** 고른 것 중 지금 기기에 있는 것. 목록이 다시 읽히는 사이 사라진 캘린더를 뺀다 */
    fun chosen(link: CalendarLinkState): Set<Long> =
        selected intersect link.calendars.mapTo(HashSet()) { it.id }

    fun canSubmit(link: CalendarLinkState): Boolean = chosen(link).isNotEmpty()

    fun submitLabel(link: CalendarLinkState): String {
        val count = chosen(link).size
        return if (count > 0) "${count}개 연동하고 시작하기" else "연동할 캘린더를 고를 것"
    }
}

// Partly AI-generated (Claude)
/** 캘린더 목록의 표시 규칙. 화면 세 곳이 같은 순서·같은 줄을 보여야 한다. */
object CalendarSources {

    /**
     * 계정끼리 모으고, 계정 안에서는 기본 캘린더를 먼저, 나머지는 이름순이다.
     * 계정이 없는 기기 로컬 캘린더는 맨 뒤다.
     */
    fun ordered(calendars: List<DeviceCalendarSource>): List<DeviceCalendarSource> =
        calendars.sortedWith(
            compareBy<DeviceCalendarSource>(
                { it.accountName == null },
                { it.accountName?.lowercase() },
                { !it.primary },
                { it.name.lowercase() },
                { it.id },
            )
        )

    /** 이름 아래 작은 줄. "jinho@snu.ac.kr · 기본". 둘 다 없으면 null */
    fun subtitle(source: DeviceCalendarSource): String? =
        listOfNotNull(source.accountName, "기본".takeIf { source.primary })
            .joinToString(" · ")
            .takeIf { it.isNotEmpty() }
}
