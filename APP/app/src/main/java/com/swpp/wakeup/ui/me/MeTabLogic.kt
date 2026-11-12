// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.me

import com.swpp.wakeup.domain.model.CalendarLinkState
import com.swpp.wakeup.domain.model.CalendarPermission
import com.swpp.wakeup.ui.common.durationLabel

/**
 * 내 정보 탭(iter4 H2-2, Figma `547:1244`) 줄마다 제목 아래 "지금 값" 한 줄.
 *
 * 들어가 보지 않아도 무엇이 설정돼 있는지 보이게 한다. 사용자가 고쳐야 하는 상태(집이 없음 · 권한이 꺼짐)는
 * [Value.warn] 으로 주황 글자다. 안드로이드 의존이 없어 JVM 테스트로 문구를 고정한다(`MeTabLogicTest`).
 */
internal object MeTabLogic {

    data class Value(val text: String, val warn: Boolean = false)

    /**
     * 기기 권한. 화면이 돌아올 때마다 다시 읽는다(사용자가 기기 설정에서 바꾸고 올 수 있다).
     *
     * @param fullScreen 잠금 화면에서 알람 화면을 띄울 수 있는가(Android 14 `canUseFullScreenIntent`).
     *   꺼져 있으면 알람이 알림으로만 온다.
     * @param preciseLocation 정밀 위치. 대략 위치만으로는 도착 판정이 나지 않는다(`LocationPermissions`)
     * @param coarseOnly 대략 위치만 켠 상태
     */
    data class Permissions(
        val notifications: Boolean,
        val fullScreen: Boolean,
        val preciseLocation: Boolean,
        val coarseOnly: Boolean = false,
    ) {
        val allOn: Boolean get() = notifications && fullScreen && preciseLocation
    }

    /** "신림동 서원9길 · 다른 집 1곳". 집이 없으면 주황 */
    fun home(homeLabel: String?, otherHomes: Int): Value {
        val label = homeLabel?.takeIf { it.isNotBlank() } ?: return Value("아직 정하지 않았어요", warn = true)
        return Value(if (otherHomes > 0) "$label · 다른 집 ${otherHomes}곳" else label)
    }

    /** "일어나서 집을 나서기까지 · 평소 35분" */
    fun prep(minutes: Int?): Value = when (minutes) {
        null -> Value("일어나서 집을 나서기까지 · 아직 정하지 않았어요", warn = true)
        else -> Value("일어나서 집을 나서기까지 · 평소 ${durationLabel(minutes)}")
    }

    /**
     * "준비 항목 3개 · 샤워, 옷 입기, 아침 식사". [names] 는 블록 순서대로다. 아직 못 받았으면(null) 무엇을
     * 하는 곳인지만 말한다. 루틴은 없어도 알람이 울리므로 주황으로 칠하지 않는다.
     */
    fun routine(names: List<String>?): Value = when {
        names == null -> Value("준비 시간을 항목으로 나눠요")
        names.isEmpty() -> Value("아직 없어요 · 항목으로 나누면 알람 시각의 근거가 보여요")
        else -> Value("준비 항목 ${names.size}개 · ${names.joinToString(", ")}")
    }

    /**
     * "켜 둔 캘린더 2개 · 고른 일정만 가져와요". 캘린더는 쓰지 않아도 되는 기능이라 권한이 없어도 주황이 아니다.
     */
    fun calendar(link: CalendarLinkState): Value = when {
        link.permission == CalendarPermission.GRANTED && link.linkedCount > 0 ->
            Value("켜 둔 캘린더 ${link.linkedCount}개 · 고른 일정만 가져와요")
        else -> Value("앞으로 2주 일정을 보고 고른 것만 가져와요")
    }

    /**
     * "알림과 위치 모두 켜져 있어요". 하나라도 꺼져 있으면 무엇이 꺼졌는지 주황으로 말한다 — 이 줄이
     * "알람이 왜 안 울렸지" 의 첫 단서다.
     */
    fun permissions(p: Permissions): Value {
        val off = buildList {
            if (!p.notifications) add("알림")
            if (!p.fullScreen) add("잠금 화면 알람")
            if (!p.preciseLocation) add(if (p.coarseOnly) "정밀 위치" else "위치")
        }
        return when (off.size) {
            0 -> Value("알림과 위치 모두 켜져 있어요")
            1 -> Value(
                when (off.single()) {
                    "알림" -> "알림이 꺼져 있어요"
                    "잠금 화면 알람" -> "잠금 화면 알람이 꺼져 있어요"
                    "정밀 위치" -> "정밀 위치가 꺼져 있어요"
                    else -> "위치 권한이 꺼져 있어요"
                },
                warn = true,
            )
            else -> Value("${off.joinToString(" · ")} 권한이 꺼져 있어요", warn = true)
        }
    }

    /** 알림·권한 화면의 한 줄. [on] 이 false 면 값을 주황으로, 오른쪽에 [켜기] 를 둔다 */
    data class PermissionRow(val kind: PermissionKind, val label: String, val value: String, val on: Boolean)

    enum class PermissionKind { NOTIFICATIONS, FULL_SCREEN, LOCATION }

    fun permissionRows(p: Permissions): List<PermissionRow> = listOf(
        PermissionRow(
            PermissionKind.NOTIFICATIONS,
            "알림",
            if (p.notifications) "켜짐 · 알람과 출발 안내를 알림으로 보내요" else "꺼짐 · 알람과 출발 안내가 오지 않아요",
            p.notifications,
        ),
        PermissionRow(
            PermissionKind.FULL_SCREEN,
            "잠금 화면 알람",
            if (p.fullScreen) "켜짐 · 잠금 화면에서도 알람 화면이 떠요" else "꺼짐 · 알람이 알림으로만 와요",
            p.fullScreen,
        ),
        PermissionRow(
            PermissionKind.LOCATION,
            "위치",
            when {
                p.preciseLocation -> "정밀 위치 켜짐 · 출발과 도착을 기록해요"
                p.coarseOnly -> "대략 위치만 켜짐 · 정밀 위치가 있어야 도착이 기록돼요"
                else -> "꺼짐 · 출발과 도착이 기록되지 않아요"
            },
            p.preciseLocation,
        ),
    )

    /**
     * 알림·권한 › "주간 리포트 알림" 줄 값. 켜 두었어도 알림 권한이 꺼져 있으면 오지 않으므로 주황으로 말한다.
     */
    fun reminder(on: Boolean, notificationsOn: Boolean): Value = when {
        !on -> Value("꺼짐")
        !notificationsOn -> Value("켜짐 · 알림이 꺼져 있어 오지 않아요", warn = true)
        else -> Value("일요일 낮 12시에 이번 주 결과를 알려 줘요")
    }

    /**
     * "로그인 연결 · 비밀번호 · 로그아웃". 비밀번호 없이 소셜로만 가입한 계정이면 비밀번호를 뺀다.
     * 로그인 방법을 아직 모르면(null) Figma 그대로 둔다.
     */
    fun settings(loginMethods: List<String>?): String =
        if (loginMethods != null && "password" !in loginMethods) "로그인 연결 · 로그아웃"
        else "로그인 연결 · 비밀번호 · 로그아웃"
}
