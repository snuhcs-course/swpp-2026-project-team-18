package com.swpp.wakeup.domain.model

import androidx.compose.ui.geometry.Offset
import com.swpp.wakeup.sensing.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapCameraMathTest {

    @Test
    fun `손가락을 벌리면 확대하고 모으면 축소한다`() {
        assertEquals(-1, MapCameraMath.zoomLevelDelta(1.5f))
        assertEquals(1, MapCameraMath.zoomLevelDelta(0.7f))
        assertEquals(0, MapCameraMath.zoomLevelDelta(1.03f))
    }

    @Test
    fun `줌 단계는 지도 한계를 넘지 않는다`() {
        val min = MapCameraMath.finishGesture(
            GeoPoint(37.5, 127.0), 4, Offset.Zero, 2f, 1.0, 4, 10,
        )
        val max = MapCameraMath.finishGesture(
            GeoPoint(37.5, 127.0), 10, Offset.Zero, 0.5f, 64.0, 4, 10,
        )
        assertEquals(4, min.level)
        assertEquals(10, max.level)
    }

    @Test
    fun `오른쪽으로 끌면 카메라는 서쪽으로 이동한다`() {
        val camera = MapCameraMath.finishGesture(
            GeoPoint(37.5, 127.0), 6, Offset(100f, 0f), 1f, 1.0, 4, 10,
        )
        assertTrue(camera.center.lng < 127.0)
    }

    @Test
    fun `북쪽 좌표는 화면 위쪽으로 투영된다`() {
        val offset = MapCameraMath.offsetPx(
            point = GeoPoint(37.501, 127.0),
            center = GeoPoint(37.5, 127.0),
            metersPerPixel = 1.0,
        )
        assertTrue(offset.y < 0f)
        assertEquals(0f, offset.x, 0.01f)
    }

    @Test
    fun `버퍼 지도는 서버 상한까지 화면의 두 배를 받는다`() {
        assertEquals(720, MapCameraMath.bufferedRequestUnits(360, 2048))
        assertEquals(1024, MapCameraMath.bufferedRequestUnits(700, 1024))
    }

    @Test
    fun `pan은 overscan 안으로 제한해 빈 면을 드러내지 않는다`() {
        val clamped = MapCameraMath.clampPan(
            requested = Offset(500f, -500f),
            frameCenterOffset = Offset.Zero,
            frameWidthPx = 720f,
            frameHeightPx = 520f,
            frameScale = 1f,
            viewportWidthPx = 360f,
            viewportHeightPx = 260f,
        )
        assertEquals(180f, clamped.x, 0.01f)
        assertEquals(-130f, clamped.y, 0.01f)
    }

    @Test
    fun `화면 가장자리 pinch는 손가락 아래 좌표를 고정한다`() {
        val pan = MapCameraMath.panAroundCentroid(
            currentPan = Offset.Zero,
            panChange = Offset.Zero,
            zoomChange = 2f,
            centroidFromCenter = Offset(100f, -40f),
        )

        // 중심에서 (100,-40)에 있던 점을 2배로 키우면 (-100,+40)만큼
        // 되돌려야 같은 화면 좌표에 남는다.
        assertEquals(-100f, pan.x, 0.01f)
        assertEquals(40f, pan.y, 0.01f)
    }

    @Test
    fun `두 배를 조금 넘긴 pinch도 한 단계만 확대한다`() {
        assertEquals(-1, MapCameraMath.zoomLevelDelta(2.01f))
        assertEquals(1, MapCameraMath.zoomLevelDelta(0.49f))
    }

    @Test
    fun `오래된 frame의 빈 면 방지 배율을 사용자 줌으로 확정하지 않는다`() {
        // 두 단계 전 frame은 현재 카메라에서 0.25배여야 하지만, 2배 overscan이
        // viewport를 덮으려면 화면에서는 최소 0.5배로 강제 확대해야 한다.
        val unchangedGesture = MapCameraMath.resolveFrameZoom(
            persistentFrameScale = 0.25f,
            gestureScale = 1f,
            minimumCoverScale = 0.5f,
        )

        assertEquals(0.5f, unchangedGesture.displayScale, 0.001f)
        assertEquals(2f, unchangedGesture.overlayScale, 0.001f)
        assertEquals(1f, unchangedGesture.committedGestureScale, 0.001f)
        assertEquals(0, MapCameraMath.zoomLevelDelta(unchangedGesture.committedGestureScale))

        // frame이 여전히 경계를 덮어 시각 배율은 같아도, 사용자가 모은 손짓의
        // 방향은 보존되어 다음 카메라가 한 단계 축소된다.
        val zoomOutGesture = MapCameraMath.resolveFrameZoom(
            persistentFrameScale = 0.25f,
            gestureScale = 0.6f,
            minimumCoverScale = 0.5f,
        )
        assertEquals(0.5f, zoomOutGesture.displayScale, 0.001f)
        assertEquals(0.6f, zoomOutGesture.committedGestureScale, 0.001f)
        assertEquals(1, MapCameraMath.zoomLevelDelta(zoomOutGesture.committedGestureScale))
    }
}
