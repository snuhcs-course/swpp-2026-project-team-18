package com.swpp.wakeup.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
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

    /**
     * 서울대 대표 남색 #003380 을 어두운 카드 위에서 읽히게 밝힌 테두리색.
     * 원색은 Surface(#1B2232)와 대비가 너무 낮아 선택 여부가 보이지 않는다.
     * Figma 77:20 과 같은 값이다.
     */
    val SnuNavyBorder = Color(0xFF3E6FD1)

    /** 다음 차량 도착정보. 즉시 봐야 하는 값이라 따뜻한 밝은 노랑이다. */
    val ArrivalNext = Color(0xFFFFD9A0)

    /** 그다음 차량 도착정보. 첫 번째보다 시각적 우선순위를 낮춘다. */
    val ArrivalLater = Color(0xFF6B7793)

    /** 경고 */
    val Amber = Color(0xFFFBBF4A)

    /** 지각·실패 */
    val Red = Color(0xFFF87171)

    // 소셜 브랜드
    val Kakao = Color(0xFFFEE500)
    val KakaoSymbol = Color(0xFF3D210D)
    val GoogleSymbol = Color(0xFF4285F5)
}

/** 글자 배치 토큰. 색처럼 화면 코드에서 직접 만들지 않는다. */
object JitTextStyle {

    /**
     * 작은 라벨을 좁은 색 상자 안에서 **눈에 보이는 대로** 중앙에 놓는다.
     *
     * Compose 의 기본 `Text` 는 폰트 메트릭에서 온 여백을 줄 상자에 넣는다.
     * 그 여백은 위쪽이 더 커서, 부모에 `Alignment.Center` 를 줘도 글리프가
     * 아래로 내려간다. 실기기에서 18dp 구간 막대 안의 9sp 라벨이 중앙보다
     * **3.2dp 아래**였다(`jit-tools/measure_bar.py` 로 픽셀 측정).
     *
     * Figma 는 텍스트 프레임을 수직 중앙으로 놓으므로(77:20 의 segbar 는
     * 높이 18 에 텍스트 11 이 y=3.5), 폰트 패딩을 끄고 줄 높이를 글리프에
     * 맞춰 잘라야 디자인과 같은 결과가 된다.
     *
     * 단락이 아니라 **한 줄 라벨**에만 쓴다. 여러 줄 본문에 쓰면 줄 간격이
     * 좁아져 읽기 어려워진다.
     */
    val TightCentered = TextStyle(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.Both,
        ),
    )
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
