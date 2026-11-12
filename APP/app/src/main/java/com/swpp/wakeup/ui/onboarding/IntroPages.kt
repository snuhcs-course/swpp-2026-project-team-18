// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.onboarding

/**
 * 첫 실행 안내 세 장(iter4 H2-4)의 글. 화면([IntroScreen])과 떼어 두어 JVM 테스트로 문구를 고정한다
 * (`FirstRunGuideTest`). 문구는 Figma `545:1305` · `546:2214` · `547:2009` 그대로다.
 */
internal object IntroPages {

    const val COUNT = 3

    private val titles = listOf(
        "도착 시각만 알려 주세요",
        "알람 시각은 직접 바꿀 수 있어요",
        "쓸수록 나에게 맞춰져요",
    )

    private val bodies = listOf(
        "어디로 몇 시까지 가는지 알려 주시면,\n준비·이동·여유 시간을 거꾸로 더해 알람을 맞춰요.",
        "추천 시각이 마음에 들지 않으면 시각 바꾸기로\n직접 고르세요. 바꾸면 정시 도착 확률이\n어떻게 달라지는지 바로 보여 드려요.",
        // 이동은 GPS 로 저절로 기록되므로 "이동을 기록하면" 이 아니라 "집을 나서면" 이다(Figma 캡션).
        "아침 준비 항목을 체크하고 집을 나서면,\n내 준비 속도와 길 사정을 배워 알람이 점점 정확해져요.",
    )

    /** 2장 본문에서 굵게 쓰는 말. 홈 카드의 버튼 이름과 같다 */
    const val BODY_EMPHASIS = "시각 바꾸기"

    fun title(page: Int): String = titles[page.coerceIn(0, COUNT - 1)]

    fun body(page: Int): String = bodies[page.coerceIn(0, COUNT - 1)]

    /** 1·2장은 [다음], 3장은 [시작하기] */
    fun cta(page: Int): String = if (page >= COUNT - 1) "시작하기" else "다음"
}
