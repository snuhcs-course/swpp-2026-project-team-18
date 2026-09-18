package com.swpp.wakeup.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * 앱 테마.
 *
 * 이 앱은 알람·새벽 사용이 중심이라 **다크 전용**이다. 라이트 테마를 두지 않는다.
 * 시스템 다크모드 설정과 무관하게 항상 같은 색으로 보인다.
 *
 * [JitColor] 를 Material3 색 슬롯에 매핑해 두었기 때문에, Button 이나 Snackbar
 * 처럼 Material 컴포넌트를 그대로 써도 우리 색이 나온다.
 */
private val JitColorScheme = darkColorScheme(
    primary = JitColor.Accent,
    onPrimary = JitColor.Bg,
    secondary = JitColor.Blue,
    onSecondary = JitColor.Bg,
    tertiary = JitColor.Green,
    onTertiary = JitColor.Bg,
    background = JitColor.Bg,
    onBackground = JitColor.TextPrimary,
    surface = JitColor.Surface,
    onSurface = JitColor.TextPrimary,
    surfaceVariant = JitColor.Surface2,
    onSurfaceVariant = JitColor.TextSecondary,
    outline = JitColor.Track,
    error = JitColor.Red,
    onError = JitColor.Bg,
    inverseSurface = JitColor.Surface2,
    inverseOnSurface = JitColor.TextPrimary
)

@Composable
fun JitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JitColorScheme,
        content = content
    )
}
