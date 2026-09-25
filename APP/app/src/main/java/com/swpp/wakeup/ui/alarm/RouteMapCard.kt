package com.swpp.wakeup.ui.alarm

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.MapCameraMath
import com.swpp.wakeup.domain.model.RouteMapProjection
import com.swpp.wakeup.domain.model.RouteProgress
import com.swpp.wakeup.domain.model.StaticMapScale
import com.swpp.wakeup.sensing.GeoPoint
import com.swpp.wakeup.ui.common.mapGestures
import com.swpp.wakeup.ui.common.rememberMapGestureState
import com.swpp.wakeup.ui.common.KakaoMapAttribution
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import kotlin.math.max
import kotlin.math.roundToInt

/** 지도 높이. 피그마 ④-a 의 260 을 그대로 쓴다 */
private val MAP_HEIGHT = 260.dp

private data class MapLayerTransform(
    val scale: Float,
    val translation: Offset,
    /** 빈 가장자리를 내보이지 않는 범위에서 실제로 화면에 적용한 pan. */
    val committedPan: Offset,
    /** 마지막 frame의 자동 경계 보정을 제외한 실제 사용자 pinch 배율. */
    val committedZoom: Float,
)

/**
 * 선택한 경로와 실시간 위치를 띄우는 지도 (Figma ④-a / 이동 중 ④-d).
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
    moving: Boolean,
    onViewport: (widthDp: Int, heightDp: Int) -> Unit,
    onFitRoute: () -> Unit,
    onGestureEnd: (pan: Offset, zoom: Float, metersPerPixel: Double) -> Unit,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = if (moving) "이동 중 · 현재 위치부터 가장 빠른 길" else "경로",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )

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
            val gesture = rememberMapGestureState()

            // 카메라는 즉시 바뀌지만 PNG는 네트워크를 돌아 나중에 도착한다.
            // 이미지가 담고 있는 카메라를 따로 사용해야 그 사이에도 배경·경로·
            // 현재 위치가 같은 도로 위에 머문다.
            val frameCenter = state.imageCenter ?: state.center
            val frameLevel = state.imageLevel ?: state.level
            val frameWidthDp = state.imageWidthDp.takeIf { it > 0 } ?: widthDp
            val frameHeightDp = state.imageHeightDp.takeIf { it > 0 } ?: heightDp
            val frameWidthPx = with(density) { frameWidthDp.dp.roundToPx() }
            val frameHeightPx = with(density) { frameHeightDp.dp.roundToPx() }

            val desiredMetersPerPixel = StaticMapScale.metersPerPixel(
                level = state.level,
                requestUnits = widthDp,
                viewPixels = widthPx,
            )
            val frameViewport = RouteMapProjection.Viewport(
                center = frameCenter,
                level = frameLevel,
                requestUnits = frameWidthDp,
                viewPx = frameWidthPx,
                viewHeightPx = frameHeightPx,
            )

            fun layerTransform(pan: Offset, zoom: Float): MapLayerTransform {
                val persistentFrameScale = MapCameraMath.frameScale(frameLevel, state.level)
                // overscan 가장자리보다 멀리 끌거나 빠르게 축소해도 빈 바탕을
                // 드러내지 않는다. 다음 frame이 오면 자연스럽게 1배로 돌아온다.
                val coverScale = max(
                    widthPx.toFloat() / frameWidthPx.coerceAtLeast(1),
                    heightPx.toFloat() / frameHeightPx.coerceAtLeast(1),
                )
                val frameZoom = MapCameraMath.resolveFrameZoom(
                    persistentFrameScale = persistentFrameScale,
                    gestureScale = zoom,
                    minimumCoverScale = coverScale,
                )
                val scale = frameZoom.displayScale
                val shownMetersPerPixel = frameViewport.metersPerPixel / scale
                val frameCenterOffset = MapCameraMath.offsetPx(
                    point = frameCenter,
                    center = state.center,
                    metersPerPixel = shownMetersPerPixel,
                )
                val safePan = MapCameraMath.clampPan(
                    requested = pan,
                    frameCenterOffset = frameCenterOffset,
                    frameWidthPx = frameWidthPx.toFloat(),
                    frameHeightPx = frameHeightPx.toFloat(),
                    frameScale = scale,
                    viewportWidthPx = widthPx.toFloat(),
                    viewportHeightPx = heightPx.toFloat(),
                )
                val coverageCorrection = MapCameraMath.clampPan(
                    requested = Offset.Zero,
                    frameCenterOffset = frameCenterOffset,
                    frameWidthPx = frameWidthPx.toFloat(),
                    frameHeightPx = frameHeightPx.toFloat(),
                    frameScale = scale,
                    viewportWidthPx = widthPx.toFloat(),
                    viewportHeightPx = heightPx.toFloat(),
                )
                return MapLayerTransform(
                    scale = scale,
                    translation = frameCenterOffset + safePan,
                    // 마지막 frame을 화면에 덮기 위한 자동 보정은 사용자의
                    // 손짓이 아니다. 카메라로 넘기면 손을 떼는 순간 옆으로 튄다.
                    committedPan = safePan - coverageCorrection,
                    // 화면을 덮기 위한 자동 배율은 제외하고 실제 pinch만 확정한다.
                    committedZoom = frameZoom.committedGestureScale,
                )
            }

            val transform = layerTransform(gesture.pan, gesture.zoom)

            LaunchedEffect(widthDp, heightDp, state.center, state.level) {
                onViewport(widthDp, heightDp)
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .mapGestures(
                        state = gesture,
                        enabled = state.image != null,
                    ) { pan, zoom ->
                        val committed = layerTransform(pan, zoom)
                        onGestureEnd(
                            committed.committedPan,
                            committed.committedZoom,
                            desiredMetersPerPixel,
                        )
                    },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .requiredSize(frameWidthDp.dp, frameHeightDp.dp)
                        .graphicsLayer {
                            transformOrigin = TransformOrigin.Center
                            translationX = transform.translation.x
                            translationY = transform.translation.y
                            scaleX = transform.scale
                            scaleY = transform.scale
                        },
                ) {
                    state.image?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "경로 지도",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    if (state.path.size >= 2) {
                        RouteOverlay(
                            path = state.path,
                            altPath = if (state.hasAltPath) state.altPath else emptyList(),
                            viewport = frameViewport,
                            travelRatio = if (state.pathFromCurrent) 0f
                            else progress?.takeIf { it.onRoute }?.ratio ?: 0f,
                            pathFromCurrent = state.pathFromCurrent,
                        )
                    }

                    state.currentLocation?.let { fix ->
                        CurrentLocationOverlay(fix = fix, viewport = frameViewport)
                    }
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

                // 마지막 정상 지도는 그대로 둔 채 오류만 비차단 안내로 얹는다.
                // 하단 왼쪽의 카카오 CI를 가리지 않도록 우상단을 쓴다.
                if (state.image != null) {
                    (state.locationError ?: state.imageError)?.let { message ->
                        MapStatusMessage(
                            text = "$message · 탭하여 재시도",
                            onClick = if (state.locationError != null) onRecenter
                                else ({ onViewport(widthDp, heightDp) }),
                            modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
                        )
                    }
                }

                // 확대해서 들여다본 뒤 되돌아올 방법이 없으면 갇힌다.
                MapPill(
                    text = "경로 전체 보기",
                    icon = "⤢",
                    onClick = onFitRoute,
                    modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
                )

                CurrentLocationButton(
                    loading = state.locating,
                    onClick = onRecenter,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 14.dp),
                )
                state.image?.let {
                    KakaoMapAttribution(
                        bitmap = it,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 6.dp, bottom = 6.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.summary?.let {
                Text(
                    it,
                    color = if (state.pathFromCurrent) JitColor.Purple else JitColor.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
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

        // 보라 선의 뜻을 밝히는 줄. 선 견본을 앞에 두어 어느 선을 말하는지 잇는다.
        //
        // 이동 중이면 문구가 "여기서부터" 로 시작한다. 출발지 기준 대안과 같은
        // 자리에 같은 색으로 나오므로, 기준점을 글씨가 말해야 한다.
        if (state.hasAltPath) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LineSwatch()
                Spacer(Modifier.width(7.dp))
                Text(
                    text = state.altSummary.orEmpty(),
                    color = JitColor.Purple,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** 지도의 보라 실선과 같은 모양의 작은 견본. */
@Composable
private fun LineSwatch() {
    Box(
        Modifier
            .width(16.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(1.5.dp))
            .background(JitColor.Purple)
    )
}

private fun formatKm(meters: Int): String =
    if (meters < 1000) "${meters}m" else "%.1fkm".format(meters / 1000.0)

/**
 * 경로선과 출발·도착 표식.
 *
 * 남은 구간을 먼저 그리고 지나온 구간을 위에 겹친다. 순서를 바꾸면 겹치는
 * 지점에서 지나온 선이 아래로 깔려 끊긴 것처럼 보인다.
 *
 * ## 더 빠른 길은 아래에 깔고 얇게 그린다
 *
 * [altPath] 는 출발 전의 대안만 나타낸다. 이동 중 현재 위치부터 다시 받은
 * 최단선은 [path] 자체이며 [pathFromCurrent]일 때 보라색 주 경로로 그린다.
 *
 * 색은 [JitColor.Purple] 이다. 처음에는 초록이었는데 같은 화면의 진행 바가
 * 초록을 "정시 도착" 으로 쓰고 있어서 한 색이 두 뜻이 됐다.
 */
@Composable
private fun RouteOverlay(
    path: List<GeoPoint>,
    altPath: List<GeoPoint>,
    viewport: RouteMapProjection.Viewport,
    travelRatio: Float,
    pathFromCurrent: Boolean,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // 고른 경로보다 먼저 그려서 아래에 깔린다.
        if (altPath.size >= 2) {
            val altPx = altPath.map { RouteMapProjection.toPx(it, viewport) }
            val line = Path().apply {
                moveTo(altPx.first().x, altPx.first().y)
                for (i in 1..altPx.lastIndex) lineTo(altPx[i].x, altPx[i].y)
            }
            drawPath(
                path = line,
                color = JitColor.Purple,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        val points = path.map { RouteMapProjection.toPx(it, viewport) }
        val segmentMeters = List(path.lastIndex) { index ->
            RouteProgress.distanceM(path[index], path[index + 1])
        }
        val totalMeters = segmentMeters.sum()
        val targetMeters = totalMeters * travelRatio.coerceIn(0f, 1f)
        val traveledLine = Path().apply { moveTo(points.first().x, points.first().y) }
        val remainingLine = Path()
        var hasTraveled = false
        var hasRemaining = false
        var reachedCut = targetMeters <= 0.0
        var walkedMeters = 0.0

        if (reachedCut) {
            remainingLine.moveTo(points.first().x, points.first().y)
        }
        for (index in segmentMeters.indices) {
            val from = points[index]
            val to = points[index + 1]
            val segment = segmentMeters[index]
            val nextMeters = walkedMeters + segment

            if (!reachedCut && (segment <= 0.0 || nextMeters <= targetMeters)) {
                traveledLine.lineTo(to.x, to.y)
                hasTraveled = true
            } else if (!reachedCut) {
                val fraction = ((targetMeters - walkedMeters) / segment)
                    .coerceIn(0.0, 1.0)
                    .toFloat()
                val cutX = from.x + (to.x - from.x) * fraction
                val cutY = from.y + (to.y - from.y) * fraction
                if (fraction > 0f) {
                    traveledLine.lineTo(cutX, cutY)
                    hasTraveled = true
                }
                remainingLine.moveTo(cutX, cutY)
                remainingLine.lineTo(to.x, to.y)
                hasRemaining = true
                reachedCut = true
            } else {
                remainingLine.lineTo(to.x, to.y)
                hasRemaining = true
            }
            walkedMeters = nextMeters
        }

        if (hasRemaining) {
            drawPath(
                path = remainingLine,
                color = if (pathFromCurrent) JitColor.Purple else JitColor.Track,
                style = Stroke(
                    width = if (pathFromCurrent) 4.5.dp.toPx() else 4.dp.toPx(),
                    cap = StrokeCap.Round,
                ),
            )
        }
        if (hasTraveled) {
            drawPath(
                path = traveledLine,
                color = JitColor.Blue,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // 출발·도착.
        //
        // **색으로 가르지 않는다.** 이 화면에서 색은 이미 세 가지 뜻을 지고 있다 —
        // 초록은 정시 도착, 주황은 여유 깎임, 보라는 더 빠른 길이다. 표식에 색을
        // 하나 더 얹으면 어느 것도 뜻이 남지 않는다.
        //
        // 대신 무게로 가른다 — 도착이 더 무겁게 보여야 한다. 목적지가 이 화면의
        // 목표이고, 지도 관례도 도착을 진한 표식으로 찍는다.
        //
        // **밝은 지도에서는 어두운 쪽이 "표식" 으로 읽힌다.** 흰 부분은 지도
        // 배경과 섞여 바탕으로 넘어간다. 그래서 "무거워 보이는 것" 은 속이 어두운
        // 원이고, "비어 보이는 것" 은 속이 흰 원이다 — 채움 여부를 그대로 쓰면
        // 의도와 반대로 보인다(실기기에서 확인했다. 출발이 꽉 찬 점, 도착이 빈
        // 링으로 보였다).
        val markerColor = JitColor.TextPrimary
        listOf(points.first() to false, points.last() to true)
            .forEach { (px, heavy) ->
                drawCircle(
                    // 어두운 속 = 무겁게 보인다. 흰 속 = 비어 보인다.
                    color = if (heavy) JitColor.Bg else markerColor,
                    radius = 7.dp.toPx(),
                    center = Offset(px.x, px.y),
                )
                drawCircle(
                    // 테두리는 속과 반대색이라 어느 쪽이든 밝은 지도에서 사라지지 않는다.
                    color = if (heavy) markerColor else JitColor.Bg,
                    radius = 7.dp.toPx(),
                    center = Offset(px.x, px.y),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
    }
}

/**
 * GPS가 보고한 내 위치와 오차 반경.
 *
 * 고정 크기 장식 원을 오차 반경처럼 쓰지 않는다. 실제 accuracy를 현재 축척으로
 * 바꿔 그리되, 정확도가 아주 좋을 때도 점이 지도 무늬에 묻히지 않을 최소 크기만
 * 둔다. Canvas 좌표로 직접 그리므로 화면 밀도를 3으로 가정하던 오차도 없다.
 */
@Composable
private fun CurrentLocationOverlay(
    fix: HomeViewModel.MapLocationFix,
    viewport: RouteMapProjection.Viewport,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val point = RouteMapProjection.toPx(fix.point, viewport)
        val center = Offset(point.x, point.y)
        val reportedRadius = (fix.accuracyM.toDouble() / viewport.metersPerPixel)
            .takeIf { it.isFinite() && it >= 0.0 }
            ?.toFloat()
            ?: 0f
        // accuracy가 없는 비정상 fix(Float.MAX_VALUE)가 GPU에 무한대에 가까운
        // 원을 요구하지 않게 한다. 화면을 모두 덮는 크기 이상은 시각적 의미도 같다.
        val accuracyRadius = max(18.dp.toPx(), reportedRadius)
            .coerceAtMost(max(size.width, size.height) * 2f)
        if (center.x < -accuracyRadius || center.y < -accuracyRadius ||
            center.x > size.width + accuracyRadius || center.y > size.height + accuracyRadius
        ) return@Canvas

        drawCircle(
            color = JitColor.Blue.copy(alpha = 0.16f),
            radius = accuracyRadius,
            center = center,
        )
        drawCircle(
            color = JitColor.TextPrimary,
            radius = 8.dp.toPx(),
            center = center,
        )
        drawCircle(color = JitColor.Blue, radius = 6.dp.toPx(), center = center)
    }
}

@Composable
private fun MapStatusMessage(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = JitColor.TextPrimary,
        fontSize = 10.sp,
        textAlign = TextAlign.Center,
        maxLines = 2,
        modifier = modifier
            .clip(RoundedCornerShape(JitRadius.Hint))
            .background(JitColor.Bg.copy(alpha = 0.9f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}

/** 현재 GPS 위치를 새로 확인한 뒤 그곳으로 지도를 옮긴다. */
@Composable
private fun CurrentLocationButton(
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .semantics {
                contentDescription = if (loading) "현재 위치 확인 중" else "현재 위치로 이동"
            }
            .clip(CircleShape)
            .background(JitColor.Bg.copy(alpha = 0.9f))
            .clickable(enabled = !loading, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = JitColor.Accent,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = "◎",
                color = JitColor.Accent,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
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
