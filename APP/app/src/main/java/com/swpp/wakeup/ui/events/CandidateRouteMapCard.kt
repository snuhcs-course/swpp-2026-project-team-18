package com.swpp.wakeup.ui.events

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.domain.model.MapCameraMath
import com.swpp.wakeup.domain.model.RouteMapProjection
import com.swpp.wakeup.domain.model.StaticMapScale
import com.swpp.wakeup.sensing.GeoPoint
import com.swpp.wakeup.ui.alarm.RouteMapCard
import com.swpp.wakeup.ui.home.HomeViewModel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

// Partly AI-generated (Claude)
/**
 * 화면을 떠나면 버리는 미리보기. 일정 지도·GPS 추적 상태를 변경하지 않는다.
 *
 * 지금은 전체 화면 미리보기([RoutePreviewScreen])만 쓴다. 그래서 기본값이 그쪽에
 * 맞춰져 있다 — [mapHeight] null 은 부모가 준 높이를 채운다는 뜻이고(호출부가
 * `Modifier.weight(1f)` 로 높이를 제한), 위의 "경로" 캡션은 그리지 않는다.
 */
@Composable
internal fun CandidateRouteMapCard(
    path: List<GeoPoint>,
    loadMap: suspend (GeoPoint, Int, Int, Int) -> EventRepository.Result<Bitmap>,
    modifier: Modifier = Modifier,
    mapHeight: Dp? = null,
    showCaption: Boolean = false,
) {
    val initialFit = remember(path) { RouteMapProjection.fit(path, 360, 260) } ?: return
    var map by remember(path) {
        mutableStateOf(
            HomeViewModel.RouteMapState(
                eventId = 0L, // 저장·추적하지 않는 후보 지도다.
                path = path,
                center = initialFit.first,
                level = initialFit.second,
            )
        )
    }
    var viewport by remember(path) { mutableStateOf(0 to 0) }
    var retry by remember(path) { mutableStateOf(0) }

    fun fitRoute() {
        val fit = RouteMapProjection.fit(path, viewport.first, viewport.second) ?: return
        map = map.copy(center = fit.first, level = fit.second, imageError = null)
    }

    LaunchedEffect(path, map.center, map.level, viewport, retry) {
        if (viewport.first <= 0 || viewport.second <= 0) return@LaunchedEffect
        val center = map.center
        val level = map.level
        // 같은 overscan 크기와 카메라 좌표를 사용해야 기존 렌더러의 손짓도 맞는다.
        val width = MapCameraMath.bufferedRequestUnits(viewport.first, 2048)
        val height = MapCameraMath.bufferedRequestUnits(viewport.second, 1024)
        map = map.copy(imageLoading = true, imageError = null)
        delay(180)
        val result = loadMap(center, level, width, height)
        currentCoroutineContext().ensureActive()
        map = when (result) {
            is EventRepository.Result.Success -> map.copy(
                image = result.data,
                imageCenter = center,
                imageLevel = level,
                imageWidthDp = width,
                imageHeightDp = height,
                imageLoading = false,
            )
            is EventRepository.Result.Failure -> map.copy(
                imageLoading = false,
                imageError = result.message,
            )
        }
    }

    RouteMapCard(
        state = map,
        progress = null,
        moving = false,
        onViewport = { width, height ->
            if (viewport != (width to height)) {
                viewport = width to height
                fitRoute()
            } else if (map.imageError != null) {
                retry++
            }
        },
        onFitRoute = {
            if (map.imageError != null) retry++
            fitRoute()
        },
        onGestureEnd = { pan, zoom, metersPerPixel ->
            val camera = MapCameraMath.finishGesture(
                center = map.center,
                level = map.level,
                panPx = pan,
                zoomScale = zoom,
                metersPerPixel = metersPerPixel,
                minLevel = StaticMapScale.MIN_LEVEL,
                maxLevel = StaticMapScale.ROUTE_MAX_LEVEL,
            )
            map = map.copy(center = camera.center, level = camera.level)
        },
        onRecenter = {},
        showCurrentLocation = false,
        modifier = modifier.testTag("candidate_route_map"),
        mapHeight = mapHeight,
        showCaption = showCaption,
    )
}
