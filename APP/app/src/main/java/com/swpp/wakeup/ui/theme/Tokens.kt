package com.swpp.wakeup.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * JustInTime 디자인 토큰.
 *
 * Figma "SWPP - 알람시간 자동 선정 앱 UI" 파일의 값을 그대로 옮긴 것이다.
 * **화면 코드에서 Color(0xFF...) 를 직접 쓰지 않는다.** 새 색이 필요하면
 * Figma 에서 확정한 뒤 여기에 추가한다.
 *
 * `res/values/colors.xml` 의 `jit_*` 와 같은 값을 가진다. XML 화면이 남아 있는
 * 동안은 두 곳을 함께 고쳐야 한다. XML 화면이 모두 사라지면 colors.xml 쪽을 지운다.
 */
object JitColor {
    /** 화면 배경 */
    val Bg = Color(0xFF0E1320)

    /** 기본 카드 */
    val Surface = Color(0xFF1B2232)

    /** 강조 카드, 보조 버튼 */
    val Surface2 = Color(0xFF262F43)

    /** 진행 바 트랙 */
    val Track = Color(0xFF334055)

    val TextPrimary = Color(0xFFF5F7FA)
    val TextSecondary = Color(0xFF8D97A8)

    /** 브랜드 강조색. 새벽 빛 계열 */
    val Accent = Color(0xFFFFB35C)

    /** 정시·성공 */
    val Green = Color(0xFF4ADE80)

    /** 정보·이동 */
    val Blue = Color(0xFF6B9EFA)

    /** 경고 */
    val Amber = Color(0xFFFBBF4A)

    /** 지각·실패 */
    val Red = Color(0xFFF87171)

    // 소셜 브랜드
    val Kakao = Color(0xFFFEE500)
    val KakaoSymbol = Color(0xFF3D210D)
    val GoogleSymbol = Color(0xFF4285F5)
}

/** 모서리 반경. Figma 에서 쓰인 값만 둔다. */
object JitRadius {
    val Card = 16.dp
    val Button = 14.dp
    val Hint = 12.dp
    val Panel = 14.dp
    val Screen = 28.dp
}

/** 화면 공통 여백. Figma 프레임 패딩과 itemSpacing 에서 온 값이다. */
object JitSpace {
    /** 화면 좌우 패딩 */
    val ScreenHorizontal = 18.dp

    /** 화면 하단 패딩 */
    val ScreenBottom = 22.dp

    /** 시스템 바 아래 추가 여백 */
    val ScreenTop = 12.dp

    /** 화면 최상위 요소 사이 간격 */
    val Section = 12.dp

    /** 카드 내부 패딩 */
    val CardPadding = 15.dp

    /** 카드 내부 항목 사이 간격 */
    val CardGap = 9.dp
}
