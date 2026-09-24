package com.swpp.wakeup.ui.places

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.domain.model.StaticMapScale
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.home.HomeViewModel
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
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
 * ## 이미지 지도로 어떻게 움직이는가
 *
 * 끄는 동안은 **이미지를 밀어 보여 주기만** 한다. 손가락을 떼는 순간 중심을
 * 옮겨 새 이미지를 받는다. 끄는 중에 매번 받으면 초당 몇 번씩 호출해 하루
 * 한도(1,000건)를 몇 분에 태운다.
 *
 * 핀을 눌러 고르는 방식은 쓸 수 없다 — 마커는 카카오가 이미지에 그려 넣으므로
 * 어느 픽셀이 어느 장소인지 앱이 알 수 없다. 대신 **아래 목록에서 고르면 지도가
 * 그 자리로 옮겨 간다.** 고른 것이 항상 화면 가운데에 온다.
 */
@Composable
fun MapPickScreen(
    state: HomeViewModel.MapPickState,
    /** 화면 크기를 알았을 때 지도 이미지를 받으라고 알린다 */
    onViewport: (widthDp: Int, heightDp: Int) -> Unit,
    onDrag: (androidx.compose.ui.geometry.Offset) -> Unit,
    onDragEnd: (metersPerPixel: Double) -> Unit,
    onZoom: (Int) -> Unit,
    onRecenter: () -> Unit,
    onResearch: (widthPx: Int, heightPx: Int, metersPerPixel: Double) -> Unit,
    onSelect: (PlaceSearchItem) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    /** 현재 위치. 모르면 내 위치 점을 그리지 않는다 */
    currentPoint: com.swpp.wakeup.sensing.GeoPoint?,
    modifier: Modifier = Modifier,
    onOpenPlaceUrl: ((String) -> Unit)? = null,
) {
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
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "지도",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        // 끄는 동안은 이미지를 그만큼 밀어 둔다. 새 이미지를 받기
                        // 전까지 화면이 손가락을 따라오게 하는 것이 목적이다.
                        .offset {
                            IntOffset(
                                state.pendingShift.x.roundToInt(),
                                state.pendingShift.y.roundToInt(),
                            )
                        }
                        .pointerInput(state.level, state.center) {
                            detectDragGestures(
                                onDragEnd = { onDragEnd(metersPerPixel) },
                                onDragCancel = { onDragEnd(metersPerPixel) },
                            ) { _, delta -> onDrag(delta) }
                        },
                )
            }

            if (state.imageLoading && bitmap == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(22.dp),
                    color = JitColor.Accent,
                    strokeWidth = 2.dp,
                )
            }

            // 지도를 못 받아도 화면을 막지 않는다. 아래 목록으로 계속 고른다.
            if (bitmap == null && !state.imageLoading) {
                Text(
                    text = state.imageError ?: "지도를 불러오는 중",
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 32.dp),
                )
            }

            // 카카오가 그린 마커는 눌러도 어느 장소인지 알 수 없으므로, 가운데
            // 표식으로 "이것을 골랐다" 를 가리킨다. 단 **실제로 중앙에 있을
            // 때만** 그린다 — 끌기·줌·재검색은 중심을 그대로 두고 선택만 바꾸므로,
            // 그때도 그리면 빈 자리를 가리키며 거짓을 말한다.
            if (state.selectedAtCenter) {
                CenterPin(Modifier.align(Alignment.Center))
            }

            // 내 위치. 중심에서 얼마나 떨어졌는지를 실측 축척으로 계산해 찍는다.
            if (currentPoint != null) {
                MyLocationDot(
                    offsetPx = StaticMapScale.let {
                        val dLat = currentPoint.lat - state.center.lat
                        val dLng = currentPoint.lng - state.center.lng
                        val cos = kotlin.math.cos(Math.toRadians(state.center.lat))
                        val dyPx = -(dLat * StaticMapScale.METERS_PER_DEGREE) / metersPerPixel
                        val dxPx = (dLng * StaticMapScale.METERS_PER_DEGREE * cos) / metersPerPixel
                        androidx.compose.ui.geometry.Offset(dxPx.toFloat(), dyPx.toFloat())
                    },
                    visible = { dx, dy -> kotlin.math.abs(dx) < widthPx / 2f && kotlin.math.abs(dy) < heightPx / 2f },
                    modifier = Modifier.align(Alignment.Center),
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

            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MapFab(glyph = "＋", enabled = state.canZoomIn) { onZoom(-1) }
                MapFab(glyph = "－", enabled = state.canZoomOut) { onZoom(1) }
                MapFab(glyph = "◎", enabled = true, onClick = onRecenter)
            }

            state.error?.let {
                Text(
                    text = it,
                    color = JitColor.Red,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
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

/** 고른 장소를 가리키는 중앙 표식. */
@Composable
private fun CenterPin(modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(34.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(JitColor.Accent.copy(alpha = 0.22f))
        )
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(JitColor.Accent)
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
private fun MyLocationDot(
    offsetPx: androidx.compose.ui.geometry.Offset,
    visible: (Float, Float) -> Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible(offsetPx.x, offsetPx.y)) return
    Box(
        modifier = modifier
            .offset { IntOffset(offsetPx.x.roundToInt(), offsetPx.y.roundToInt()) }
            .size(44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(JitColor.Blue.copy(alpha = 0.16f))
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
private fun MapFab(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(JitColor.Bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = glyph,
            color = if (enabled) JitColor.Accent else JitColor.TextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 하단 시트.
 *
 * 핀을 눌러 고르는 대신 여기서 고른다 — 마커는 카카오가 이미지에 그려 넣어
 * 앱이 좌표를 알 수 없다. 고르면 지도가 그 자리로 옮겨 간다.
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
                MapSheetRow(
                    place = place,
                    picked = place === state.selected ||
                        (place.kakaoPlaceId != null && place.kakaoPlaceId == state.selected?.kakaoPlaceId),
                    onClick = { onSelect(place) },
                    onOpenPlaceUrl = onOpenPlaceUrl,
                )
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
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = place.name,
                    color = if (picked) JitColor.Accent else JitColor.TextPrimary,
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
                color = JitColor.Accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
