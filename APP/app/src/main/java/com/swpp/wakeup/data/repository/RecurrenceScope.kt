// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.swpp.wakeup.data.remote.ServerVersion

/**
 * 반복 일정을 고치거나 지울 때의 범위(iOS 캘린더의 "이 이벤트만 / 이후 이벤트"). iter4 A2,
 * 서버 계약은 docs/iter4/recurrence-scope-api.md.
 *
 * @property wire 서버에 보내는 값. `PATCH` 본문의 `scope`, `DELETE` 의 `?scope=`
 */
enum class EventScope(val wire: String) {
    /** 이 회차만. 지금 서버 동작과 같아서 따로 보내지 않는다 */
    THIS("this"),

    /** 이 회차부터 앞으로의 회차 모두. 반복 규칙(요일)을 바꿀 수 있다 */
    FOLLOWING("following"),
}

/** 범위 선택을 화면에 내놓을지, 서버에 무엇을 실을지 정하는 순수 함수 모음. */
object RecurrenceScope {

    /**
     * 범위 수정·삭제를 받는 첫 서버 버전.
     *
     * 이보다 낮은 서버는 `scope` 를 모르고 **조용히 무시한다** — "이후 일정 모두" 를 골라도 그 회차만
     * 바뀐다. 그래서 버전을 확인하지 못하면 선택지를 아예 내놓지 않고 지금처럼 그 회차만 고친다.
     * 백엔드가 이 기능을 다른 버전으로 내면 이 값을 같이 바꾼다.
     */
    const val SINCE_SERVER = "0.21.0"

    fun supported(serverVersion: String?): Boolean =
        ServerVersion.isAtLeast(serverVersion, SINCE_SERVER)

    /**
     * 저장을 눌렀을 때 시트에 보일 선택지. 비어 있으면 시트 없이 바로 저장한다(반복이 아니거나 구버전 서버).
     *
     * 요일을 바꿨으면 "이 일정만" 을 뺀다. 하루치만 고치는 데 반복 요일은 뜻이 없다(아이폰 캘린더도
     * 반복 규칙을 바꾸면 "이후 이벤트에 대해 저장" 만 보인다).
     */
    fun saveChoices(recurring: Boolean, supported: Boolean, weekdaysChanged: Boolean): List<EventScope> = when {
        !recurring || !supported -> emptyList()
        weekdaysChanged -> listOf(EventScope.FOLLOWING)
        else -> listOf(EventScope.THIS, EventScope.FOLLOWING)
    }

    /** 삭제를 눌렀을 때 시트에 보일 선택지. 비어 있으면 지금처럼 바로 지운다. */
    fun deleteChoices(recurring: Boolean, supported: Boolean): List<EventScope> =
        if (recurring && supported) listOf(EventScope.THIS, EventScope.FOLLOWING) else emptyList()

    /**
     * 수정 본문([EventEditPatch.build] 결과)에 범위를 싣는다. 받은 객체는 건드리지 않고 사본을 돌려준다.
     *
     * - [EventScope.THIS]·null 은 아무것도 더하지 않는다. 구버전 서버와 같은 본문이다.
     * - [EventScope.FOLLOWING] 은 `"scope": "following"` 을 넣고, [weekdays] 가 있으면
     *   `repeat_weekdays`(0 = 월 … 6 = 일, 오름차순)도 넣는다.
     */
    fun withScope(patch: JsonObject, scope: EventScope?, weekdays: Set<Int>? = null): JsonObject {
        val body = patch.deepCopy()
        if (scope != EventScope.FOLLOWING) return body
        body.addProperty("scope", EventScope.FOLLOWING.wire)
        if (weekdays != null) {
            body.add("repeat_weekdays", JsonArray().apply { weekdays.sorted().forEach { add(it) } })
        }
        return body
    }
}
