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

    // Partly AI-generated (Claude)
    @Test
    fun `끈 거리만큼 지도 좌표에서 정확히 옮긴다`() {
        // 손가락 아래 장소가 손을 뗀 뒤에도 그 자리에 있어야 한다. 예전에는 축척을 두 배
        // 작게 알고 있어서 지도가 끈 거리의 절반만 따라와 손을 떼면 되돌아갔다.
        val start = GeoPoint(37.46, 126.952)
        val camera = MapCameraMath.finishGesture(
            start, 6, Offset(120f, -80f), 1f, 8.0, 3, 10,
        )
        val before = KakaoMapProjection.forward(start)
        val after = KakaoMapProjection.forward(camera.center)
        assertEquals(-120 * 8.0, after.x - before.x, 0.01)
        assertEquals(-80 * 8.0, after.y - before.y, 0.01)
    }

    @Test
    fun `투영과 손짓이 같은 좌표계다`() {
        // 중심을 어떤 점으로 끌어오면 그 점의 오프셋이 0 이 된다.
        val center = GeoPoint(35.1796, 129.0756)
        val target = GeoPoint(35.1900, 129.1100)
        val offset = MapCameraMath.offsetPx(target, center, 4.0)
        val camera = MapCameraMath.finishGesture(center, 5, -offset, 1f, 4.0, 3, 10)
        val after = MapCameraMath.offsetPx(target, camera.center, 4.0)
        assertEquals(0f, after.x, 0.01f)
        assertEquals(0f, after.y, 0.01f)
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

    // --- todo §2 "작은 핀치에 한 단계 줌" (Partly AI-generated (Claude)) -----------

    @Test
    fun `살짝 벌리거나 모은 손가락은 단계를 바꾸지 않는다`() {
        // 예전에는 12% 만 벌려도 두 배로 튀었다.
        assertEquals(0, MapCameraMath.zoomLevelDelta(1.12f))
        assertEquals(0, MapCameraMath.zoomLevelDelta(1.3f))
        assertEquals(0, MapCameraMath.zoomLevelDelta(0.89f))
        assertEquals(0, MapCameraMath.zoomLevelDelta(0.8f))
    }

    @Test
    fun `분명히 벌리거나 모으면 가까운 단계로 바꾼다`() {
        assertEquals(-1, MapCameraMath.zoomLevelDelta(1.35f))
        assertEquals(1, MapCameraMath.zoomLevelDelta(0.74f))
        assertEquals(-2, MapCameraMath.zoomLevelDelta(3f))
        assertEquals("세 단계까지만", 3, MapCameraMath.zoomLevelDelta(0.05f))
        assertEquals(0, MapCameraMath.zoomLevelDelta(Float.NaN))
    }

    // --- todo §2 "지도 연속 이동 거리 두 배" (Partly AI-generated (Claude)) ---------

    private val viewportW = 360f
    private val viewportH = 260f
    private val frameW = 720f
    private val frameH = 520f

    private fun layer(
        pan: Offset,
        zoom: Float,
        frame: GeoPoint,
        frameLevel: Int,
        camera: GeoPoint,
        level: Int,
        metersPerPixel: Double,
    ) = MapCameraMath.layer(
        pan = pan,
        zoom = zoom,
        frameCenter = frame,
        frameLevel = frameLevel,
        cameraCenter = camera,
        cameraLevel = level,
        cameraMetersPerPixel = metersPerPixel,
        frameWidthPx = frameW,
        frameHeightPx = frameH,
        viewportWidthPx = viewportW,
        viewportHeightPx = viewportH,
    )

    private fun commit(start: GeoPoint, level: Int, layer: MapCameraMath.Layer) =
        MapCameraMath.finishGesture(
            center = start,
            level = level,
            panPx = layer.commitPan,
            zoomScale = layer.commitZoom,
            metersPerPixel = layer.commitMetersPerPixel,
            minLevel = 1,
            maxLevel = 12,
        )

    /** [point] 가 손을 떼는 순간 화면 가운데에 보였는가 */
    private fun assertShownAtCenter(
        point: GeoPoint,
        camera: GeoPoint,
        metersPerPixel: Double,
        layer: MapCameraMath.Layer,
    ) {
        val shown = MapCameraMath.offsetPx(point, camera, metersPerPixel) * layer.overlayZoom + layer.overlayPan
        assertEquals("x", 0f, shown.x, 0.5f)
        assertEquals("y", 0f, shown.y, 0.5f)
    }

    @Test
    fun `축소하며 끈 손짓은 보이던 곳으로 간다`() {
        // 예전에는 새 단계(두 배 축척)로 옮겨서 끈 거리의 두 배 가까이 더 갔다.
        val start = GeoPoint(37.46, 126.952)
        val end = layer(Offset(40f, -10f), 0.6f, start, 6, start, 6, 2.0)
        val camera = commit(start, 6, end)

        assertEquals(7, camera.level)
        assertShownAtCenter(camera.center, start, 2.0, end)
    }

    @Test
    fun `새 지도를 기다리는 동안 이어 끈 손짓도 보이던 곳으로 간다`() {
        val start = GeoPoint(37.46, 126.952)
        // 첫 손짓: 끌기. 배경은 아직 start 의 것이다.
        val first = layer(Offset(120f, 40f), 1f, start, 6, start, 6, 2.0)
        val afterFirst = commit(start, 6, first)
        assertShownAtCenter(afterFirst.center, start, 2.0, first)

        // 손을 뗀 직후 화면이 그대로다(옛 배경을 옮긴 만큼 보여 준다).
        val resting = layer(Offset.Zero, 1f, start, 6, afterFirst.center, 6, 2.0)
        assertEquals(first.translation.x, resting.translation.x, 0.5f)
        assertEquals(first.translation.y, resting.translation.y, 0.5f)

        // 둘째 손짓: 같은 옛 배경 위에서 다시 끌기.
        val second = layer(Offset(-60f, 30f), 1f, start, 6, afterFirst.center, 6, 2.0)
        val afterSecond = commit(afterFirst.center, 6, second)
        assertShownAtCenter(afterSecond.center, afterFirst.center, 2.0, second)

        // 두 번 끈 거리의 합만큼만 옮겼다.
        val total = MapCameraMath.offsetPx(start, afterSecond.center, 2.0)
        assertEquals(60f, total.x, 0.5f)
        assertEquals(70f, total.y, 0.5f)
    }

    @Test
    fun `옛 배경이 화면을 덮도록 당긴 만큼도 카메라로 넘긴다`() {
        // 한 단계 축소한 카메라에 옛 배경(0.5배)이 꼭 맞아 움직일 틈이 없다. 끌어도 화면은
        // 그대로이므로 카메라는 화면 가운데(옛 배경의 중심)로 가야 새 지도가 와도 튀지 않는다.
        val frame = GeoPoint(37.46, 126.952)
        val camera = GeoPoint(37.462, 126.955)
        val end = layer(Offset(30f, 0f), 1f, frame, 6, camera, 7, 4.0)
        assertEquals(0f, end.translation.x, 0.5f)
        assertEquals(0f, end.translation.y, 0.5f)

        val next = commit(camera, 7, end)
        assertShownAtCenter(next.center, camera, 4.0, end)
        val fromFrame = MapCameraMath.offsetPx(frame, next.center, 4.0)
        assertEquals(0f, fromFrame.x, 0.5f)
        assertEquals(0f, fromFrame.y, 0.5f)
    }

    @Test
    fun `확대한 손짓은 손가락 배율로 옮긴다`() {
        // 1.6배로 벌리며 끌었다. 한 단계(두 배)로 맞추지만 이동은 보이던 1.6배 축척으로 셈한다.
        val start = GeoPoint(35.1796, 129.0756)
        val end = layer(Offset(-80f, 50f), 1.6f, start, 6, start, 6, 2.0)
        val camera = commit(start, 6, end)
        assertEquals(5, camera.level)
        assertEquals(2.0 / 1.6, end.commitMetersPerPixel, 1e-6)
        assertShownAtCenter(camera.center, start, 2.0, end)
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
