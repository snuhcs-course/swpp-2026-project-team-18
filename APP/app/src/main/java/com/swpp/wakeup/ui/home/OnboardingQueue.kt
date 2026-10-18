package com.swpp.wakeup.ui.home

import com.swpp.wakeup.ui.nav.AppRoute

// Partly AI-generated (Claude)
/**
 * 가입 직후 온보딩 화면의 순서.
 *
 * 보이는 순서는 **집 주소(⑭) → 준비 시간(⑮) → 캘린더 연동(⑱) → 홈** 이다. 화면
 * 스택은 마지막에 넣은 것이 먼저 보이므로 [pushOrder] 는 그 역순을 준다.
 *
 * ## 캘린더 연동은 따로 묻지 않는다
 *
 * 집 주소·준비 시간은 알람 계산에 필요한 값이라 답할 때까지 매번 다시 묻는다.
 * 캘린더는 없어도 앱을 쓸 수 있다. 그래서 **다른 온보딩 화면이 뜰 때 그 끝에 한 번만**
 * 붙인다 — 이미 가입을 마친 사용자가 앱을 열 때마다 캘린더를 묻지 않는다. 연동은
 * ⑯ 설정에서 언제든 켜고 끈다.
 */
internal object OnboardingQueue {

    /**
     * 스택에 밀어 넣을 순서.
     *
     * @param askHome 집 주소가 없고 이번 실행에서 아직 묻지 않았다
     * @param askPrep 준비 시간이 없고 이번 실행에서 아직 묻지 않았다
     * @param askCalendar 이 기기·계정에서 ⑱ 을 아직 보여 주지 않았다
     */
    fun pushOrder(askHome: Boolean, askPrep: Boolean, askCalendar: Boolean): List<AppRoute> =
        buildList {
            if (askCalendar && (askHome || askPrep)) add(AppRoute.CalendarLink)
            if (askPrep) add(AppRoute.PrepOnboarding)
            if (askHome) add(AppRoute.HomeSetup)
        }
}
