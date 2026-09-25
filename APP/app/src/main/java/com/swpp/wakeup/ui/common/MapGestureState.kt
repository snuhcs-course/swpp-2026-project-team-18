package com.swpp.wakeup.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import com.swpp.wakeup.domain.model.MapCameraMath
import kotlin.math.abs

/** pan과 pinch의 임시 변환을 화면에서 즉시 보여 주고, 종료 시 한 번만 확정한다. */
@Stable
class MapGestureState internal constructor() {
    var pan by mutableStateOf(Offset.Zero)
        private set
    var zoom by mutableFloatStateOf(1f)
        private set

    internal fun update(
        panChange: Offset,
        zoomChange: Float,
        centroidFromCenter: Offset,
    ) {
        if (zoomChange.isFinite() && zoomChange > 0f) {
            val nextZoom = (zoom * zoomChange).coerceIn(0.45f, 2.5f)
            val effectiveZoomChange = nextZoom / zoom
            // 지도 앱의 pinch는 화면 중앙이 아니라 두 손가락 사이를 축으로 돈다.
            // 기존 translation까지 같은 축으로 확대해야 손가락 아래 장소가
            // 그대로 남는다.
            pan = MapCameraMath.panAroundCentroid(
                currentPan = pan,
                panChange = panChange,
                zoomChange = effectiveZoomChange,
                centroidFromCenter = centroidFromCenter,
            )
            zoom = nextZoom
        } else {
            pan += panChange
        }
    }

    internal fun setTransform(nextPan: Offset, nextZoom: Float) {
        pan = nextPan
        zoom = nextZoom.coerceIn(0.45f, 2.5f)
    }

    internal fun reset() {
        pan = Offset.Zero
        zoom = 1f
    }
}

@Composable
fun rememberMapGestureState(): MapGestureState = remember { MapGestureState() }

/**
 * 한 손가락 pan과 두 손가락 pinch를 함께 처리한다. 회전 값은 일부러 읽지 않아
 * 북쪽이 항상 위에 머문다.
 */
@Composable
fun Modifier.mapGestures(
    state: MapGestureState,
    enabled: Boolean = true,
    onGestureEnd: (pan: Offset, zoom: Float) -> Unit,
): Modifier {
    val latestOnEnd = rememberUpdatedState(onGestureEnd)
    return pointerInput(state, enabled) {
        if (!enabled) return@pointerInput
        val touchSlop = viewConfiguration.touchSlop
        val viewportCenter = Offset(size.width / 2f, size.height / 2f)
        awaitEachGesture {
            var active = false
            var pendingPan = Offset.Zero
            var pendingZoom = 1f
            do {
                val event = awaitPointerEvent()
                val panChange = event.calculatePan()
                val zoomChange = event.calculateZoom()
                val centroid = event.calculateCentroid(useCurrent = false)
                val centroidFromCenter = if (centroid.x.isFinite() && centroid.y.isFinite()) {
                    centroid - viewportCenter
                } else {
                    Offset.Zero
                }

                if (!active) {
                    if (zoomChange.isFinite() && zoomChange > 0f) {
                        val nextZoom = (pendingZoom * zoomChange).coerceIn(0.45f, 2.5f)
                        val effectiveZoomChange = nextZoom / pendingZoom
                        pendingPan = MapCameraMath.panAroundCentroid(
                            currentPan = pendingPan,
                            panChange = panChange,
                            zoomChange = effectiveZoomChange,
                            centroidFromCenter = centroidFromCenter,
                        )
                        pendingZoom = nextZoom
                    } else {
                        pendingPan += panChange
                    }
                    val centroidSize = event.calculateCentroidSize(useCurrent = false)
                        .coerceAtLeast(1f)
                    val zoomMotion = abs(1f - pendingZoom) * centroidSize
                    active = pendingPan.getDistance() > touchSlop || zoomMotion > touchSlop
                    if (active) state.setTransform(pendingPan, pendingZoom)
                } else {
                    state.update(panChange, zoomChange, centroidFromCenter)
                }

                if (active) {
                    event.changes.forEach { change ->
                        if (change.pressed) change.consume()
                    }
                }
            } while (event.changes.any { it.pressed })

            if (active) latestOnEnd.value(state.pan, state.zoom)
            state.reset()
        }
    }
}
