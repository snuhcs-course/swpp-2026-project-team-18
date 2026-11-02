package com.swpp.wakeup.domain.model

import androidx.compose.ui.geometry.Offset
import com.swpp.wakeup.sensing.GeoPoint
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
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
     * 이만큼(로그2 배율) 이상 손가락을 벌리거나 모아야 단계를 바꾼다. 약 1.34배 확대·0.75배 축소다.
     *
     * 정적 지도는 정수 단계만 받아서 한 단계가 곧 두 배다. 예전에는 12% 만 벌려도 두 배로
     * 튀었다(todo §2 "작은 핀치에 한 단계 줌"). 가까운 단계(로그2 0.5)보다 조금 앞에서 바꿔,
     * 분명히 벌린 손짓은 놓치지 않고 살짝 움직인 손가락은 제자리로 돌린다. (Partly AI-generated (Claude))
     */
    const val ZOOM_STEP_THRESHOLD_LOG2 = 0.42

    /**
     * 손가락을 벌리면 확대(level 감소), 모으면 축소(level 증가)한다.
     *
     * 정적 지도는 연속 줌을 받지 못하므로 손짓이 끝날 때 가까운 단계로 맞춘다.
     * [ZOOM_STEP_THRESHOLD_LOG2] 보다 작은 손짓은 무시하고, 한 번의 큰 손짓도 세 단계까지만
     * 움직여 방향 감각을 잃지 않게 한다.
     */
    fun zoomLevelDelta(scale: Float): Int {
        if (!scale.isFinite() || scale <= 0f) return 0
        val steps = log2(scale.toDouble())
        if (abs(steps) < ZOOM_STEP_THRESHOLD_LOG2) return 0
        val levels = abs(steps).roundToInt().coerceIn(1, 3)
        return if (steps > 0) -levels else levels
    }

    /**
     * 끝난 pan/pinch를 새 카메라로 확정한다.
     *
     * 새 중심은 **손을 떼는 순간 화면 가운데에 보이던 곳**이다. [metersPerPixel] 은 그때 화면에
     * 보이던 축척(손짓 배율·남은 옛 frame 배율 포함, [Layer.commitMetersPerPixel])이어야 한다.
     * 예전에는 새 줌 단계의 축척으로 옮겨서, 줌 단계가 손짓 배율과 다르면 손을 뗀 뒤 지도가
     * 옆으로 튀었다 — 축소하면서 끌면 두 배 가까이 더 갔다(todo §2 "지도 연속 이동 거리 두 배").
     * 끈 거리는 지도 좌표([KakaoMapProjection])에서 옮긴다 — 화면에 보이는 그림이 그 좌표로
     * 그려져 있다.
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
        val shown = metersPerPixel.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

        // 지도를 오른쪽으로 끌면 카메라는 서쪽으로, 아래로 끌면 북쪽으로 이동한다.
        val from = KakaoMapProjection.forward(center)
        val moved = KakaoMapProjection.inverse(
            KakaoMapProjection.Tm(
                x = from.x - panPx.x * shown,
                y = from.y + panPx.y * shown,
            )
        )

        return Camera(
            center = GeoPoint(
                lat = moved.lat.coerceIn(-89.0, 89.0),
                lng = moved.lng,
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
        val p = KakaoMapProjection.forward(point)
        val c = KakaoMapProjection.forward(center)
        return Offset(
            x = ((p.x - c.x) / metersPerPixel).toFloat(),
            y = (-(p.y - c.y) / metersPerPixel).toFloat(),
        )
    }

    /** 이전에 받은 배경을 새 줌 레벨에서 유지할 때 필요한 배율. */
    fun frameScale(frameLevel: Int, cameraLevel: Int): Float =
        2.0.pow(frameLevel - cameraLevel).toFloat()

    // Partly AI-generated (Claude)
    /**
     * 마지막으로 받은 배경(frame)과 지금 카메라·손짓을 한 화면 변환으로 합친 것. 경로 지도(④-a)와
     * 장소 고르기 지도(⑰)가 같이 쓴다 — 두 곳이 따로 셈하면 한쪽만 고쳐진다.
     *
     * 새 배경은 네트워크를 돌아 늦게 온다. 그동안 옛 배경을 [scale]·[translation] 으로 옮겨 보여
     * 주고, 손을 떼면 그때 **보이던 그대로**를 카메라로 넘긴다([commitPan]·[commitMetersPerPixel]).
     * 옛 배경이 화면을 덮도록 자동으로 당긴 만큼도 화면에 보인 것이므로 함께 넘긴다 — 빼면 새
     * 배경이 왔을 때 지도가 그만큼 튄다.
     *
     * @property scale 배경에 줄 배율(빈 면을 막는 자동 확대 포함)
     * @property translation 배경 중심의 화면 위치(viewport 중심 기준 px)
     * @property overlayZoom 카메라 기준 px([offsetPx])로 그린 표식에 곱할 배율
     * @property overlayPan 그 표식에 더할 이동
     * @property commitPan 손을 뗄 때 카메라로 넘길 이동(px)
     * @property commitZoom 손을 뗄 때 넘길 사용자 pinch 배율(자동 확대 제외)
     * @property commitMetersPerPixel [commitPan] 1px 이 지도에서 몇 m 인가
     */
    data class Layer(
        val scale: Float,
        val translation: Offset,
        val overlayZoom: Float,
        val overlayPan: Offset,
        val commitPan: Offset,
        val commitZoom: Float,
        val commitMetersPerPixel: Double,
    )

    /**
     * @param cameraMetersPerPixel 지금 카메라 단계에서 화면 1px 의 m
     * @param frameWidthPx 배경을 원래 크기로 그렸을 때의 px(overscan 포함)
     */
    fun layer(
        pan: Offset,
        zoom: Float,
        frameCenter: GeoPoint,
        frameLevel: Int,
        cameraCenter: GeoPoint,
        cameraLevel: Int,
        cameraMetersPerPixel: Double,
        frameWidthPx: Float,
        frameHeightPx: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float,
    ): Layer {
        val coverScale = max(
            viewportWidthPx / frameWidthPx.coerceAtLeast(1f),
            viewportHeightPx / frameHeightPx.coerceAtLeast(1f),
        )
        val frameZoom = resolveFrameZoom(
            persistentFrameScale = frameScale(frameLevel, cameraLevel),
            gestureScale = zoom,
            minimumCoverScale = coverScale,
        )
        val overlayZoom = frameZoom.overlayScale
        val frameCenterOffset = offsetPx(frameCenter, cameraCenter, cameraMetersPerPixel) * overlayZoom
        val shownPan = clampPan(
            requested = pan,
            frameCenterOffset = frameCenterOffset,
            frameWidthPx = frameWidthPx,
            frameHeightPx = frameHeightPx,
            frameScale = frameZoom.displayScale,
            viewportWidthPx = viewportWidthPx,
            viewportHeightPx = viewportHeightPx,
        )
        return Layer(
            scale = frameZoom.displayScale,
            translation = frameCenterOffset + shownPan,
            overlayZoom = overlayZoom,
            overlayPan = shownPan,
            commitPan = shownPan,
            commitZoom = frameZoom.committedGestureScale,
            commitMetersPerPixel = if (overlayZoom > 0f) cameraMetersPerPixel / overlayZoom else cameraMetersPerPixel,
        )
    }

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
}
