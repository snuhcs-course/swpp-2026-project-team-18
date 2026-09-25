package com.swpp.wakeup.ui.alarm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.RouteMapProjection
import com.swpp.wakeup.domain.model.RouteProgress
import com.swpp.wakeup.sensing.GeoPoint
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import kotlin.math.roundToInt

/** 지도 높이. 피그마 ④-a 의 260 을 그대로 쓴다 */
private val MAP_HEIGHT = 260.dp

/**
 * 선택한 경로와 실시간 위치를 띄우는 지도 (Figma ④-a).
 *
 * ## 정적 지도 위에 선을 직접 그린다
 *
 * 카카오 정적 지도에는 **선을 그리는 파라미터가 없다.** `path`·`polyline`·
 * `line`·`paths`·`route` 를 모두 시험했고 다섯 응답의 MD5 가 같았다 — 조용히
 * 무시된다. 마커도 다섯 개까지다.
 *
 * 그래서 지도는 이미지로 받고 경로는 [Canvas] 로 그 위에 그린다. 좌표 → 픽셀
 * 변환은 [RouteMapProjection] 이 맡고, 같은 식으로 피그마 시안도 그렸다.
 *
 * ## 지나온 길과 남은 길을 색으로 나눈다
 *
 * 점 하나로 "어디까지 왔는지" 를 보여 주면 잘 읽히지 않는다. 색이 바뀌는
 * 지점이 현재 위치이므로 선 자체가 진행을 말한다.
 *
 * 남은 구간은 **어두운 색**이다. 카카오 정적 지도는 밝은 테마여서, 앱 배경색
 * 기준으로 흰 반투명 선을 쓰면 지도 위에서 사라진다(실기기에서 확인).
 */
@Composable
fun RouteMapCard(
    state: HomeViewModel.RouteMapState,
    progress: RouteProgress?,
    here: GeoPoint?,
    onViewport: (widthDp: Int, heightDp: Int) -> Unit,
    onZoom: (Int) -> Unit,
    onFitRoute: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: (metersPerPixel: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("경로", color = JitColor.TextSecondary, fontSize = 11.sp)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(MAP_HEIGHT)
                .clip(RoundedCornerShape(JitRadius.Card))
                .background(JitColor.Surface),
        ) {
            val density = LocalDensity.current
            val widthDp = maxWidth.value.roundToInt()
            val heightDp = maxHeight.value.roundToInt()
            val widthPx = with(density) { maxWidth.roundToPx() }
            val heightPx = with(density) { maxHeight.roundToPx() }

            LaunchedEffect(widthDp, heightDp, state.center, state.level) {
                onViewport(widthDp, heightDp)
            }

            val viewport = RouteMapProjection.Viewport(
                center = state.center,
                level = state.level,
                requestUnits = widthDp,
                viewPx = widthPx,
                viewHeightPx = heightPx,
            )

            state.image?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "경로 지도",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset {
                            IntOffset(
                                state.pendingShift.x.roundToInt(),
                                state.pendingShift.y.roundToInt(),
                            )
                        }
                        .pointerInput(state.level, state.center) {
                            detectDragGestures(
                                onDragEnd = { onDragEnd(viewport.metersPerPixel) },
                                onDragCancel = { onDragEnd(viewport.metersPerPixel) },
                            ) { _, delta -> onDrag(delta) }
                        },
                )
            }

            if (state.imageLoading && state.image == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(22.dp),
                    color = JitColor.Accent,
                    strokeWidth = 2.dp,
                )
            }

            if (state.image == null && !state.imageLoading) {
                Text(
                    text = state.imageError ?: "지도를 불러오는 중",
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                )
            }

            // 경로선. 이미지와 같이 밀려야 하므로 같은 offset 을 쓴다.
            if (state.path.size >= 2) {
                RouteOverlay(
                    path = state.path,
                    viewport = viewport,
                    travelRatio = progress?.takeIf { it.onRoute }?.ratio ?: 0f,
                    shift = state.pendingShift,
                )
            }

            here?.let { point ->
                MeDot(
                    px = RouteMapProjection.toPx(point, viewport),
                    shift = state.pendingShift,
                    widthPx = widthPx,
                    heightPx = heightPx,
                )
            }

            // 확대해서 들여다본 뒤 되돌아올 방법이 없으면 갇힌다.
            MapPill(
                text = "경로 전체 보기",
                icon = "⤢",
                onClick = onFitRoute,
                modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MapFab("＋", state.canZoomIn) { onZoom(-1) }
                MapFab("－", state.canZoomOut) { onZoom(1) }
            }
            // 좌하단은 카카오 CI 로고 자리다. 로고는 제거할 수 없고 가리면
            // 이용 조건을 어기므로 어떤 것도 놓지 않는다.
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.summary?.let {
                Text(it, color = JitColor.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.weight(1f))
            progress?.takeIf { it.onRoute }?.let {
                Text(
                    text = "${formatKm(it.traveledM)} 이동",
                    color = JitColor.Blue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun formatKm(meters: Int): String =
    if (meters < 1000) "${meters}m" else "%.1fkm".format(meters / 1000.0)

/**
 * 경로선과 출발·도착 표식.
 *
 * 남은 구간을 먼저 그리고 지나온 구간을 위에 겹친다. 순서를 바꾸면 겹치는
 * 지점에서 지나온 선이 아래로 깔려 끊긴 것처럼 보인다.
 */
@Composable
private fun RouteOverlay(
    path: List<GeoPoint>,
    viewport: RouteMapProjection.Viewport,
    travelRatio: Float,
    shift: Offset,
) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(shift.x.roundToInt(), shift.y.roundToInt()) }
    ) {
        val points = path.map { RouteMapProjection.toPx(it, viewport) }
        // 진행 비율을 점 인덱스로 바꾼다. 거리 비례가 아니라 점 개수 비례라
        // 점 간격이 고른 경로에서는 거의 같고, 선 색이 바뀌는 위치가 몇 픽셀
        // 어긋나는 것은 눈에 띄지 않는다.
        val cut = (points.size * travelRatio).toInt().coerceIn(0, points.size - 1)

        fun pathOf(from: Int, to: Int): Path? {
            if (to - from < 1) return null
            return Path().apply {
                moveTo(points[from].x, points[from].y)
                for (i in from + 1..to) lineTo(points[i].x, points[i].y)
            }
        }

        pathOf(cut, points.lastIndex)?.let {
            drawPath(
                path = it,
                // 지도가 밝은 테마다. 앱 배경 기준의 흰 반투명은 여기서 사라진다.
                color = JitColor.Track,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        pathOf(0, cut)?.let {
            drawPath(
                path = it,
                color = JitColor.Blue,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // 출발·도착. 지도 배경색으로 속을 채운 링이라 밝은 지도에서도 보인다.
        //
        // **초록·주황을 쓰지 않는다.** 바로 위 진행 바가 초록을 "정시", 주황을
        // "여유 깎임" 으로 쓰고 있어서, 같은 화면에서 같은 색이 다른 뜻이 된다.
        // 초록 링을 보고 "정시라는 표시" 로 읽을 여지를 남기지 않는다.
        //
        // 대신 채움 여부로 가른다 — 출발은 속이 빈 링, 도착은 속을 채운 점이다.
        // 경로선이 출발에서 시작하므로 방향은 선으로도 읽힌다.
        val markerColor = JitColor.TextPrimary
        listOf(points.first() to false, points.last() to true)
            .forEach { (px, filled) ->
                drawCircle(
                    // 밝은 지도 위에서 흰 링이 사라지지 않게 속을 어둡게 깐다.
                    color = if (filled) markerColor else JitColor.Bg,
                    radius = 7.dp.toPx(),
                    center = Offset(px.x, px.y),
                )
                drawCircle(
                    color = if (filled) JitColor.Bg else markerColor,
                    radius = 7.dp.toPx(),
                    center = Offset(px.x, px.y),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
    }
}

/**
 * 내 위치.
 *
 * 경로를 잘 따르고 있으면 점이 선 위에 온다. 선에서 벗어나 있으면 그것 자체가
 * "경로를 벗어났다" 는 신호다 — 문구로 따로 말할 필요가 없다.
 */
@Composable
private fun MeDot(
    px: RouteMapProjection.Px,
    shift: Offset,
    widthPx: Int,
    heightPx: Int,
) {
    val x = px.x + shift.x
    val y = px.y + shift.y
    // 화면 밖이면 그리지 않는다. 테두리에 붙은 점은 실제 위치를 잘못 알린다.
    if (x < 0 || y < 0 || x > widthPx || y > heightPx) return

    Box(
        modifier = Modifier
            .offset { IntOffset((x - 22 * 3).roundToInt(), (y - 22 * 3).roundToInt()) }
            .size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(JitColor.Blue.copy(alpha = 0.18f))
        )
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(JitColor.Blue)
        )
    }
}

@Composable
private fun MapPill(
    text: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(JitColor.Bg.copy(alpha = 0.86f))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, color = JitColor.Accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(5.dp))
        Text(text, color = JitColor.TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MapFab(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(JitColor.Bg.copy(alpha = 0.86f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = if (enabled) JitColor.Accent else JitColor.TextSecondary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
