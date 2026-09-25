package com.swpp.wakeup.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.domain.model.MapCameraMath
import com.swpp.wakeup.domain.model.StaticMapScale
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.KakaoMapAttribution
import com.swpp.wakeup.ui.common.mapGestures
import com.swpp.wakeup.ui.common.rememberMapGestureState
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 하단 시트 목록의 최대 높이.
 *
 * 이 값이 시트 전체 높이를 사실상 결정하고, 그만큼이 지도에서 빠진다. 헤더·버튼·
 * 여백을 더하면 시트는 약 340dp 가 되므로, 가장 작은 흔한 화면(약 640dp)에서도
 * 지도가 250dp 이상 남는다. 키우면 지도가 그만큼 줄어든다.
 */
private val SHEET_LIST_MAX_HEIGHT = 210.dp

/**
 * 지도에서 장소 고르기 (Figma ⑰).
 *
 * ## 지도 SDK 를 쓰지 않는 이유
 *
 * 카카오지도 안드로이드 SDK 는 네이티브 앱 키를 APK 에 넣고 서명 키 해시를
 * 카카오 개발자 사이트에 등록해야 한다. 서명 키가 바뀌면 지도가 죽고, 팀원마다
 * 디버그 키가 달라 각자 등록해야 한다.
 *
 * 카카오 **정적 지도 REST API** 는 같은 지도를 이미지로 준다. 서버가 가진 키로
 * 부르므로 앱에는 키가 없고 등록할 것도 없다. 다른 카카오 호출과 같은 규정
 * (back-spec.md 5.3 프록시)이기도 하다.
 *
 * 배경은 화면보다 크게 받은 정적 지도 한 장이지만, pan·pinch 중에는 그 장과
 * 모든 표식을 같은 변환으로 즉시 움직인다. 손을 뗐을 때만 새 카메라를 확정해
 * API를 호출하므로 조작감과 호출량을 함께 지킨다.
 *
 * 장소 핀은 PNG에 굽지 않고 앱이 직접 그린다. 그래야 아래 목록과 정확히 같은
 * 다섯 곳만 표시할 수 있고, 고른 핀 하나만 보라색으로 바꾸거나 핀 자체를 눌러
 * 선택할 수 있다.
 */
@Composable
fun MapPickScreen(
    state: HomeViewModel.MapPickState,
    /** 화면 크기를 알았을 때 지도 이미지를 받으라고 알린다 */
    onViewport: (widthDp: Int, heightDp: Int) -> Unit,
    onGestureEnd: (pan: Offset, zoom: Float, metersPerPixel: Double) -> Unit,
    onRecenter: () -> Unit,
    onResearch: (widthPx: Int, heightPx: Int, metersPerPixel: Double) -> Unit,
    onSelect: (PlaceSearchItem) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlaceUrl: ((String) -> Unit)? = null,
) {
    val gestureState = rememberMapGestureState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg),
    ) {
        MapTopBar(query = state.selected?.name ?: "지도에서 고르기", onBack = onBack)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .background(JitColor.Surface),
        ) {
            val density = LocalDensity.current
            val widthDp = maxWidth.value.roundToInt()
            val heightDp = maxHeight.value.roundToInt()
            val widthPx = with(density) { maxWidth.roundToPx() }
            val heightPx = with(density) { maxHeight.roundToPx() }

            // 서버에는 dp 로 크기를 보내고, 끌기·재검색은 화면 픽셀로 일어난다.
            // 밀도가 1이 아니면 둘이 다르므로 환산한다.
            val metersPerPixel = StaticMapScale.metersPerPixel(
                level = state.level,
                requestUnits = widthDp,
                viewPixels = widthPx,
            )

            LaunchedEffect(widthDp, heightDp, state.center, state.level, state.markers) {
                onViewport(widthDp, heightDp)
            }

            val bitmap = state.image
            val frameCenter = state.imageCenter ?: state.center
            val frameLevel = state.imageLevel ?: state.level
            val frameWidthDp = state.imageWidthDp.takeIf { it > 0 } ?: widthDp
            val frameHeightDp = state.imageHeightDp.takeIf { it > 0 } ?: heightDp
            val frameWidthPx = with(density) { frameWidthDp.dp.toPx() }
            val frameHeightPx = with(density) { frameHeightDp.dp.toPx() }
            val frameCenterOffset = MapCameraMath.offsetPx(
                point = frameCenter,
                center = state.center,
                metersPerPixel = metersPerPixel,
            )
            val persistentFrameScale = MapCameraMath.frameScale(frameLevel, state.level)
            val visualTransform = mapVisualTransform(
                pan = gestureState.pan,
                zoom = gestureState.zoom,
                frameCenterOffset = frameCenterOffset,
                persistentFrameScale = persistentFrameScale,
                frameWidthPx = frameWidthPx,
                frameHeightPx = frameHeightPx,
                viewportWidthPx = widthPx.toFloat(),
                viewportHeightPx = heightPx.toFloat(),
            )

            // 터치 영역은 움직이는 이미지가 아니라 viewport 전체다. 큰 이미지를
            // 밀었을 때 드러난 가장자리에서도 다음 손짓을 바로 이어 갈 수 있다.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .mapGestures(
                        state = gestureState,
                        enabled = bitmap != null,
                        onGestureEnd = { pan, zoom ->
                            val end = mapVisualTransform(
                                pan = pan,
                                zoom = zoom,
                                frameCenterOffset = frameCenterOffset,
                                persistentFrameScale = persistentFrameScale,
                                frameWidthPx = frameWidthPx,
                                frameHeightPx = frameHeightPx,
                                viewportWidthPx = widthPx.toFloat(),
                                viewportHeightPx = heightPx.toFloat(),
                            )
                            onGestureEnd(
                                end.committedPan,
                                end.committedZoom,
                                metersPerPixel,
                            )
                        },
                    ),
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "장소 선택 지도",
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .requiredSize(frameWidthDp.dp, frameHeightDp.dp)
                            .graphicsLayer {
                                translationX = visualTransform.frameTranslation.x
                                translationY = visualTransform.frameTranslation.y
                                scaleX = visualTransform.frameScale
                                scaleY = visualTransform.frameScale
                            },
                    )
                }

                // 선택 핀을 마지막에 그려 겹친 장소가 있어도 항상 위에 보인다.
                state.markers
                    .withIndex()
                    .sortedBy { indexed -> state.isSelected(indexed.value) }
                    .forEach { indexed ->
                        val place = indexed.value
                        val picked = state.isSelected(place)
                        key(placeStableKey(place, indexed.index)) {
                            val pointOffset = MapCameraMath.offsetPx(
                                point = com.swpp.wakeup.sensing.GeoPoint(place.lat, place.lng),
                                center = state.center,
                                metersPerPixel = metersPerPixel,
                            )
                            PlaceMarker(
                                number = indexed.index + 1,
                                name = place.name,
                                selected = picked,
                                offsetPx = pointOffset * visualTransform.overlayZoom +
                                    visualTransform.overlayPan,
                                onClick = { onSelect(place) },
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }

                state.currentLocation?.let { fix ->
                    val pointOffset = MapCameraMath.offsetPx(
                        point = fix.point,
                        center = state.center,
                        metersPerPixel = metersPerPixel,
                    ) * visualTransform.overlayZoom + visualTransform.overlayPan
                    // 일부 기기는 accuracy를 보고하지 않는다. 그때 ViewModel은
                    // Float.MAX_VALUE로 표시하므로 크기를 그대로 dp로 바꾸면 레이아웃이
                    // 넘친다. 실제 원이 화면보다 크면 어차피 viewport 전체를 덮으므로
                    // 화면 긴 변까지만 그려도 같은 정보를 전달한다.
                    val reportedAccuracyM = fix.accuracyM
                        .takeIf { it.isFinite() && it in 0f..100_000f }
                    val accuracyM = reportedAccuracyM ?: 0f
                    val accuracyRadiusPx = (
                        (accuracyM / metersPerPixel).toFloat() * visualTransform.overlayZoom
                    ).coerceAtMost(max(widthPx, heightPx).toFloat())
                    if (abs(pointOffset.x) <= widthPx / 2f + accuracyRadiusPx &&
                        abs(pointOffset.y) <= heightPx / 2f + accuracyRadiusPx
                    ) {
                        CurrentLocationMarker(
                            offsetPx = pointOffset,
                            accuracyRadiusPx = accuracyRadiusPx,
                            accuracyM = reportedAccuracyM,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
            }

            if (state.imageLoading && bitmap == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(22.dp),
                    color = JitColor.Accent,
                    strokeWidth = 2.dp,
                )
            }

            bitmap?.let {
                KakaoMapAttribution(
                    bitmap = it,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 6.dp, bottom = 6.dp),
                )
            }

            if (state.imageLoading && bitmap != null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(14.dp)
                        .size(16.dp),
                    color = JitColor.Blue,
                    strokeWidth = 2.dp,
                )
            }

            // 지도를 못 받아도 화면을 막지 않는다. 아래 목록으로 계속 고른다.
            if (bitmap == null && !state.imageLoading) {
                Text(
                    text = state.imageError?.let { "$it · 탭하여 재시도" }
                        ?: "지도를 불러오는 중",
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clickable(enabled = state.imageError != null) {
                            onViewport(widthDp, heightDp)
                        }
                        .padding(horizontal = 32.dp),
                )
            }

            // 새 지도를 못 받았어도 마지막 정상 bitmap과 목록 조작은 그대로 둔다.
            if (bitmap != null && state.imageError != null) {
                Text(
                    text = "이전 지도를 표시 중 · 탭하여 재시도",
                    color = JitColor.TextSecondary,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .clip(CircleShape)
                        .background(JitColor.Bg.copy(alpha = 0.9f))
                        .clickable { onViewport(widthDp, heightDp) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            // 지도를 옮긴 뒤에만 보여 준다. 늘 떠 있으면 무엇이 달라졌는지
            // 모르는 채로 누르게 되고, 그만큼 카카오 호출이 늘어난다.
            if (state.moved) {
                MapPill(
                    text = "이 지역 재검색",
                    icon = "↻",
                    loading = state.searching,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 14.dp),
                    onClick = { onResearch(widthPx, heightPx, metersPerPixel) },
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 14.dp),
            ) {
                LocationFab(locating = state.locating, onClick = onRecenter)
            }

            state.error?.let {
                Text(
                    text = it,
                    color = JitColor.Red,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(14.dp)
                        .clip(RoundedCornerShape(JitRadius.Hint))
                        .background(JitColor.Bg)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        MapSheet(
            state = state,
            onSelect = onSelect,
            onConfirm = onConfirm,
            onOpenPlaceUrl = onOpenPlaceUrl,
        )
    }
}

// ---------------------------------------------------------------------------

/** 마지막 bitmap과 현재 카메라 사이의 차이 및 손짓을 한 변환으로 합친 값. */
private data class MapVisualTransform(
    val frameScale: Float,
    val frameTranslation: Offset,
    val overlayZoom: Float,
    val overlayPan: Offset,
    /** overscan 경계 보정은 빼고 실제 사용자가 움직인 만큼만 남긴 값. */
    val committedPan: Offset,
    /** 자동 빈 면 보정을 제외한 실제 사용자 pinch 배율. */
    val committedZoom: Float,
)

private fun mapVisualTransform(
    pan: Offset,
    zoom: Float,
    frameCenterOffset: Offset,
    persistentFrameScale: Float,
    frameWidthPx: Float,
    frameHeightPx: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
): MapVisualTransform {
    // 두 배 overscan도 한 번에 아주 크게 축소하면 viewport보다 작아질 수 있다.
    // 배경만 작아져 빈 테두리가 생기지 않도록 보이는 동안만 최소 배율을 둔다.
    val minimumCoverScale = max(
        viewportWidthPx / frameWidthPx.coerceAtLeast(1f),
        viewportHeightPx / frameHeightPx.coerceAtLeast(1f),
    )
    val frameZoom = MapCameraMath.resolveFrameZoom(
        persistentFrameScale = persistentFrameScale,
        gestureScale = zoom,
        minimumCoverScale = minimumCoverScale,
    )
    val totalFrameScale = frameZoom.displayScale
    val overlayZoom = frameZoom.overlayScale
    val scaledFrameCenterOffset = frameCenterOffset * overlayZoom
    val displayedPan = MapCameraMath.clampPan(
        requested = pan,
        frameCenterOffset = scaledFrameCenterOffset,
        frameWidthPx = frameWidthPx,
        frameHeightPx = frameHeightPx,
        frameScale = totalFrameScale,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
    )
    // 마지막 frame이 새 카메라에서 너무 멀면 빈 면을 막기 위해 기본 보정이
    // 생긴다. 그 보정까지 새 camera pan으로 넘기면 손을 떼는 순간 지도가 튄다.
    val coverageCorrection = MapCameraMath.clampPan(
        requested = Offset.Zero,
        frameCenterOffset = scaledFrameCenterOffset,
        frameWidthPx = frameWidthPx,
        frameHeightPx = frameHeightPx,
        frameScale = totalFrameScale,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
    )
    return MapVisualTransform(
        frameScale = totalFrameScale,
        frameTranslation = scaledFrameCenterOffset + displayedPan,
        overlayZoom = overlayZoom,
        overlayPan = displayedPan,
        committedPan = displayedPan - coverageCorrection,
        // overlayZoom에는 오래된 frame이 빈 면을 만들지 않게 하는 자동 확대가
        // 들어 있다. 카메라에는 실제 pinch만 확정해야 연속 손짓이 역전되지 않는다.
        committedZoom = frameZoom.committedGestureScale,
    )
}

@Composable
private fun MapTopBar(query: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(JitColor.Bg)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "‹",
            color = JitColor.TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(horizontal = 8.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = query,
            color = JitColor.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 목록과 같은 번호를 가진 장소 핀. 핀 끝이 실제 좌표에 닿는다. */
@Composable
private fun PlaceMarker(
    number: Int,
    name: String,
    selected: Boolean,
    offsetPx: Offset,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val halfHitTargetPx = with(density) { 24.dp.toPx() }
    val color = if (selected) JitColor.Purple else JitColor.Blue
    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    x = offsetPx.x.roundToInt(),
                    y = (offsetPx.y - halfHitTargetPx).roundToInt(),
                )
            }
            .zIndex(if (selected) 3f else 1f)
            .size(48.dp)
            .semantics {
                contentDescription = "${number}번 $name${if (selected) ", 선택됨" else ""}"
                role = Role.Button
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(Modifier.width(32.dp).height(40.dp)) {
            val centerX = size.width / 2f
            val headCenterY = size.width * 0.45f
            val path = Path().apply {
                moveTo(centerX, size.height)
                cubicTo(
                    size.width * 0.40f, size.height * 0.78f,
                    size.width * 0.08f, size.height * 0.61f,
                    size.width * 0.08f, headCenterY,
                )
                cubicTo(
                    size.width * 0.08f, size.height * 0.13f,
                    size.width * 0.28f, 0f,
                    centerX, 0f,
                )
                cubicTo(
                    size.width * 0.72f, 0f,
                    size.width * 0.92f, size.height * 0.13f,
                    size.width * 0.92f, headCenterY,
                )
                cubicTo(
                    size.width * 0.92f, size.height * 0.61f,
                    size.width * 0.60f, size.height * 0.78f,
                    centerX, size.height,
                )
                close()
            }
            drawPath(path, color)
            drawCircle(
                color = Color.White,
                radius = size.width * 0.20f,
                center = Offset(centerX, headCenterY),
            )
        }
        Text(
            text = number.toString(),
            color = color,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-2).dp),
        )
    }
}

/**
 * 내 위치.
 *
 * 결과 마커와 **다른 모양이어야 한다.** 같은 크기의 점으로 두면 검색 결과
 * 하나로 읽힌다. 지도 앱들이 쓰는 방식대로 점은 작게, 정확도 원은 넓게 둔다.
 */
@Composable
private fun CurrentLocationMarker(
    offsetPx: Offset,
    accuracyRadiusPx: Float,
    accuracyM: Float?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haloDiameter = with(density) {
        (accuracyRadiusPx * 2f)
            .coerceAtLeast(28.dp.toPx())
            .toDp()
    }
    Box(
        modifier = modifier
            .offset { IntOffset(offsetPx.x.roundToInt(), offsetPx.y.roundToInt()) }
            .requiredSize(haloDiameter)
            .zIndex(2f)
            .semantics {
                contentDescription = accuracyM?.let {
                    "현재 위치, 오차 약 ${it.roundToInt()}미터"
                } ?: "현재 위치, 정확도 정보 없음"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(JitColor.Blue.copy(alpha = 0.16f))
        )
        Box(
            Modifier
                .size(18.dp)
                .clip(CircleShape)
                .border(2.dp, Color.White, CircleShape)
                .background(JitColor.Blue)
        )
    }
}

@Composable
private fun MapPill(
    text: String,
    icon: String,
    loading: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(JitColor.Bg)
            .clickable(enabled = !loading, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = JitColor.Accent,
                strokeWidth = 2.dp,
            )
        } else {
            Text(text = icon, color = JitColor.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(6.dp))
        Text(text = text, color = JitColor.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LocationFab(locating: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(JitColor.Bg)
            .semantics {
                contentDescription = if (locating) "현재 위치 확인 중" else "현재 위치로 지도 이동"
                role = Role.Button
            }
            .clickable(enabled = !locating, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (locating) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = JitColor.Blue,
                strokeWidth = 2.dp,
            )
        } else {
            // 글꼴마다 모양이 달라지는 특수문자 대신 직접 그린 표준 위치 표식.
            Canvas(Modifier.size(24.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    color = JitColor.Blue,
                    radius = size.minDimension * 0.30f,
                    center = center,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = size.minDimension * 0.08f,
                    ),
                )
                drawCircle(
                    color = JitColor.Blue,
                    radius = size.minDimension * 0.08f,
                    center = center,
                )
                val arm = size.minDimension * 0.16f
                val stroke = size.minDimension * 0.08f
                drawLine(JitColor.Blue, Offset(center.x, 0f), Offset(center.x, arm), stroke)
                drawLine(
                    JitColor.Blue,
                    Offset(center.x, size.height - arm),
                    Offset(center.x, size.height),
                    stroke,
                )
                drawLine(JitColor.Blue, Offset(0f, center.y), Offset(arm, center.y), stroke)
                drawLine(
                    JitColor.Blue,
                    Offset(size.width - arm, center.y),
                    Offset(size.width, center.y),
                    stroke,
                )
            }
        }
    }
}

/**
 * 하단 시트.
 *
 * 지도 핀과 목록은 같은 순서·번호를 쓴다. 어느 쪽을 눌러도 같은 장소가
 * 선택되고, 지도에서는 그 핀 하나만 보라색으로 바뀐다.
 */
@Composable
private fun MapSheet(
    state: HomeViewModel.MapPickState,
    onSelect: (PlaceSearchItem) -> Unit,
    onConfirm: () -> Unit,
    onOpenPlaceUrl: ((String) -> Unit)?,
) {
    // **이 시트에는 높이 상한이 반드시 있어야 한다.**
    //
    // 위 지도는 `weight(1f)` 로 "남은 공간" 을 받는다. Compose 에서 가중치 없는
    // 형제가 먼저 측정되므로, 이 시트가 원하는 만큼 커지면 남는 공간이 0 이 되고
    // **지도가 소리 없이 사라진다.** 실기기에서 정확히 그렇게 됐다 — 5곳을 다
    // 나열해 시트가 650dp 를 차지하자 지도 영역이 0 이 되어 목록만 보였다.
    // 에러도 로그도 없다. 피그마 비율(지도 505 / 시트 186)이 지켜지지 않은 것도
    // 같은 원인이다.
    //
    // 목록만 상한을 두고 스크롤을 준다. 버튼은 스크롤 밖에 둬서 몇 곳이 나와도
    // 항상 보인다 — 스크롤 안에 넣으면 목록이 길 때 "이 장소로 선택" 이 밀려
    // 내려가 누를 수 없다.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(JitColor.Surface)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.markers.isEmpty()) {
            Text(
                text = "이 영역에는 결과가 없음. 지도를 옮기고 재검색할 것",
                color = JitColor.TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            return@Column
        }

        Text(
            text = "지도에 표시된 ${state.markers.size}곳",
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SHEET_LIST_MAX_HEIGHT)
                .verticalScroll(rememberScrollState()),
        ) {
            state.markers.forEachIndexed { index, place ->
                if (index > 0) HorizontalDivider(color = JitColor.Bg)
                key(placeStableKey(place, index)) {
                    MapSheetRow(
                        number = index + 1,
                        place = place,
                        picked = state.isSelected(place),
                        onClick = { onSelect(place) },
                        onOpenPlaceUrl = onOpenPlaceUrl,
                    )
                }
            }
        }

        JitPrimaryButton(
            label = "이 장소로 선택",
            onClick = onConfirm,
            enabled = state.selected != null,
        )
    }
}

@Composable
private fun MapSheetRow(
    number: Int,
    place: PlaceSearchItem,
    picked: Boolean,
    onClick: () -> Unit,
    onOpenPlaceUrl: ((String) -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (picked) JitColor.Purple else JitColor.Blue),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = place.name,
                    color = if (picked) JitColor.Purple else JitColor.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                place.categoryGroup?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = it,
                        color = JitColor.TextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(JitColor.Surface2)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                place.distanceLabel?.let {
                    Text(
                        text = it,
                        color = JitColor.Green,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(text = " · ", color = JitColor.TextSecondary, fontSize = 11.sp)
                }
                Text(
                    text = place.address.orEmpty(),
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
            val url = place.placeUrl
            if (onOpenPlaceUrl != null && !url.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "카카오맵에서 평점·사진 보기 ›",
                    color = JitColor.Blue,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onOpenPlaceUrl(url) },
                )
            }
        }
        if (picked) {
            Text(
                text = "✓",
                color = JitColor.Purple,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 카카오 ID가 없는 역지오코딩 장소도 좌표로 안정적으로 식별한다. */
private fun placeStableKey(place: PlaceSearchItem, index: Int): String =
    place.kakaoPlaceId?.takeIf { it.isNotBlank() }
        ?: "${place.lat.toBits()}:${place.lng.toBits()}:${place.name}:$index"
