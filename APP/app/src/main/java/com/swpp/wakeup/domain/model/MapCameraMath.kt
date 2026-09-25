package com.swpp.wakeup.domain.model

import androidx.compose.ui.geometry.Offset
import com.swpp.wakeup.sensing.GeoPoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 정적 지도 위에서 손짓을 카메라 좌표로 바꾸는 순수 계산.
 *
 * 화면 17과 4-a가 같은 부호·축척 규칙을 써야 배경 지도, 마커, 경로가 서로
 * 어긋나지 않는다. 네트워크나 Android 상태를 넣지 않아 JVM 테스트로 고정한다.
 */
object MapCameraMath {

    data class Camera(
        val center: GeoPoint,
        val level: Int,
    )

    /**
     * 아직 새 bitmap이 도착하지 않았을 때 사용할 frame 배율들.
     *
     * [displayScale]은 빈 가장자리를 막기 위한 자동 확대까지 포함하고,
     * [overlayScale]은 좌표 overlay를 그 frame과 맞추기 위한 값이다. 반면
     * [committedGestureScale]은 오직 사용자의 pinch만 담는다. 자동 확대를
     * 카메라에 확정하면 오래된 frame을 표시하는 동안 손짓하지 않아도 줌 단계가
     * 반대로 되돌아간다.
     */
    data class FrameZoom(
        val displayScale: Float,
        val overlayScale: Float,
        val committedGestureScale: Float,
    )

    fun resolveFrameZoom(
        persistentFrameScale: Float,
        gestureScale: Float,
        minimumCoverScale: Float,
    ): FrameZoom {
        val frame = persistentFrameScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val gesture = gestureScale.takeIf { it.isFinite() && it > 0f } ?: 1f
        val cover = minimumCoverScale.takeIf { it.isFinite() && it > 0f } ?: 0f
        val display = maxOf(frame * gesture, cover)
        return FrameZoom(
            displayScale = display,
            overlayScale = display / frame,
            committedGestureScale = gesture,
        )
    }

    /**
     * 손가락을 벌리면 확대(level 감소), 모으면 축소(level 증가)한다.
     *
     * 정적 지도는 연속 줌을 받지 못하므로 손짓이 끝날 때 가까운 단계로 맞춘다.
     * 작은 떨림은 무시하고, 한 번의 큰 손짓도 세 단계까지만 움직여 방향 감각을
     * 잃지 않게 한다.
     */
    fun zoomLevelDelta(scale: Float): Int {
        if (!scale.isFinite() || scale <= 0f) return 0
        return when {
            scale >= 1.12f -> -log2(scale.toDouble()).roundToInt().coerceIn(1, 3)
            scale <= 0.89f -> log2(1.0 / scale).roundToInt().coerceIn(1, 3)
            else -> 0
        }
    }

    /**
     * 끝난 pan/pinch를 새 카메라로 확정한다.
     *
     * 이동 거리는 **새 줌 레벨의** 축척으로 계산한다. 그래야 pan과 pinch를 함께
     * 했을 때 손을 떼는 순간 지도가 옆으로 튀지 않는다.
     */
    fun finishGesture(
        center: GeoPoint,
        level: Int,
        panPx: Offset,
        zoomScale: Float,
        metersPerPixel: Double,
        minLevel: Int,
        maxLevel: Int,
    ): Camera {
        val delta = zoomLevelDelta(zoomScale)
        val nextLevel = (level + delta).coerceIn(minLevel, maxLevel)
        val actualDelta = nextLevel - level
        val nextMetersPerPixel = metersPerPixel * 2.0.pow(actualDelta)

        // 지도를 오른쪽으로 끌면 카메라는 서쪽으로 이동한다.
        val dLat = (panPx.y * nextMetersPerPixel) / StaticMapScale.METERS_PER_DEGREE
        val longitudeMeters = StaticMapScale.METERS_PER_DEGREE *
            cos(Math.toRadians(center.lat)).coerceAtLeast(0.01)
        val dLng = (-panPx.x * nextMetersPerPixel) / longitudeMeters

        return Camera(
            center = GeoPoint(
                lat = (center.lat + dLat).coerceIn(-89.0, 89.0),
                lng = normalizeLongitude(center.lng + dLng),
            ),
            level = nextLevel,
        )
    }

    /** 좌표를 현재 카메라 중심 기준 화면 px 오프셋으로 바꾼다. */
    fun offsetPx(
        point: GeoPoint,
        center: GeoPoint,
        metersPerPixel: Double,
    ): Offset {
        if (metersPerPixel <= 0.0 || !metersPerPixel.isFinite()) return Offset.Zero
        val latitudeMeters = (point.lat - center.lat) * StaticMapScale.METERS_PER_DEGREE
        val longitudeMeters = shortestLongitudeDelta(point.lng, center.lng) *
            StaticMapScale.METERS_PER_DEGREE * cos(Math.toRadians(center.lat))
        return Offset(
            x = (longitudeMeters / metersPerPixel).toFloat(),
            y = (-latitudeMeters / metersPerPixel).toFloat(),
        )
    }

    /** 이전에 받은 배경을 새 줌 레벨에서 유지할 때 필요한 배율. */
    fun frameScale(frameLevel: Int, cameraLevel: Int): Float =
        2.0.pow(frameLevel - cameraLevel).toFloat()

    /** pinch 중심 아래의 장소가 손가락 아래에 머물도록 translation을 누적한다. */
    fun panAroundCentroid(
        currentPan: Offset,
        panChange: Offset,
        zoomChange: Float,
        centroidFromCenter: Offset,
    ): Offset {
        if (!zoomChange.isFinite() || zoomChange <= 0f) return currentPan + panChange
        return currentPan * zoomChange +
            centroidFromCenter * (1f - zoomChange) + panChange
    }

    /**
     * 화면 바깥에 한 화면만큼 더 받아 pan과 한 단계 zoom-out 중 빈 면을 막는다.
     * 카카오 정적 지도의 서버 상한을 넘지 않는다.
     */
    fun bufferedRequestUnits(viewportUnits: Int, maxUnits: Int): Int =
        (viewportUnits.coerceAtLeast(1) * 2).coerceAtMost(maxUnits)

    /** overscan 배경의 실제 여유 안으로 pan을 제한한다. */
    fun clampPan(
        requested: Offset,
        frameCenterOffset: Offset,
        frameWidthPx: Float,
        frameHeightPx: Float,
        frameScale: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
    ): Offset {
        val roomX = ((frameWidthPx * frameScale - viewportWidthPx) / 2f).coerceAtLeast(0f)
        val roomY = ((frameHeightPx * frameScale - viewportHeightPx) / 2f).coerceAtLeast(0f)
        return Offset(
            x = requested.x.coerceIn(-roomX - frameCenterOffset.x, roomX - frameCenterOffset.x),
            y = requested.y.coerceIn(-roomY - frameCenterOffset.y, roomY - frameCenterOffset.y),
        )
    }

    private fun log2(value: Double): Double = ln(value) / ln(2.0)

    private fun normalizeLongitude(value: Double): Double {
        var normalized = value
        while (normalized > 180.0) normalized -= 360.0
        while (normalized < -180.0) normalized += 360.0
        return normalized
    }

    private fun shortestLongitudeDelta(value: Double, center: Double): Double {
        var delta = value - center
        if (abs(delta) > 180.0) delta -= 360.0 * kotlin.math.sign(delta)
        return delta
    }
}
