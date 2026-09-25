package com.swpp.wakeup.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * overscan 정적 지도의 원본 카카오 CI를 보이는 viewport 좌하단에 유지한다.
 *
 * 큰 이미지를 가운데 잘라 쓰면 원본의 좌하단 CI도 화면 밖으로 나간다. CI를
 * 새로 흉내 내지 않고 응답 bitmap의 해당 픽셀을 그대로 옮겨 표시한다.
 */
@Composable
fun KakaoMapAttribution(bitmap: Bitmap, modifier: Modifier = Modifier) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(modifier = modifier.width(64.dp).height(28.dp)) {
        // scale=2 응답의 CI와 주변 안전 여백. 작은 예외 응답도 범위 밖을 읽지 않는다.
        val sourceWidth = min(image.width, 128)
        val sourceHeight = min(image.height, 56)
        drawImage(
            image = image,
            srcOffset = IntOffset(0, image.height - sourceHeight),
            srcSize = IntSize(sourceWidth, sourceHeight),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        )
    }
}
