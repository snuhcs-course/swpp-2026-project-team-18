package com.swpp.wakeup.sensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 출발·도착 판별 검증.
 *
 * **왜 단위 테스트인가.** 이 프로젝트에서 알람과 위치는 손으로 확인하기 가장
 * 어려운 부분이다. 출발 판정을 보려면 집에서 150m 를 걸어 나가야 하고, 흐린
 * fix 나 튀는 좌표는 원할 때 만들 수 없다. 실기기 확인은 "동작한다" 만 알려
 * 주고 "이런 경우에 오판하지 않는다" 는 알려 주지 않는다.
 *
 * 그래서 [TripGeofence] 를 안드로이드 의존 없이 만들었고, 여기서 실제로
 * 문제가 되는 상황을 재현한다.
 */
class TripGeofenceTest {

    private val home = GeoPoint(37.4842, 126.9295) // 신림역
    private val dest = GeoPoint(37.4598, 126.9511) // 서울대 관악캠퍼스

    /** 위도 1도 ≈ 111,320m. 북쪽으로 [meters] 만큼 옮긴 점. */
    private fun north(from: GeoPoint, meters: Double) =
        GeoPoint(from.lat + meters / 111_320.0, from.lng)

    private var clock = 1_700_000_000_000L

    private fun fix(point: GeoPoint, accuracy: Float = 10f): LocationFix {
        clock += 10_000
        return LocationFix(point.lat, point.lng, accuracy, clock)
    }

    private fun fence(
        home: GeoPoint? = this.home,
        destination: GeoPoint? = this.dest,
    ) = TripGeofence(home = home, destination = destination)

    // --- 거리 계산 --------------------------------------------------------

    @Test
    fun `하버사인이 짧은 거리를 맞춘다`() {
        val d = TripGeofence.distanceMeters(home, north(home, 111.0))
        assertTrue("111m 를 기대했는데 ${d}m", d in 110.0..112.5)
    }

    @Test
    fun `하버사인이 실제 두 지점 거리를 맞춘다`() {
        // 신림역 → 관악캠퍼스 직선거리는 약 3.3km 다.
        val d = TripGeofence.distanceMeters(home, dest)
        assertTrue("3.3km 를 기대했는데 ${d}m", d in 3_150.0..3_450.0)
    }

    @Test
    fun `같은 점은 거리가 0이다`() {
        assertEquals(0.0, TripGeofence.distanceMeters(home, home), 0.001)
    }

    // --- 출발 판정 --------------------------------------------------------

    @Test
    fun `집에 있으면 출발이 아니다`() {
        val f = fence()
        assertNull(f.offer(fix(home)))
        assertNull(f.offer(fix(north(home, 30.0))))
        assertNull(f.offer(fix(north(home, 120.0))))
        assertEquals(TripGeofence.Phase.BEFORE_DEPARTURE, f.phase)
    }

    @Test
    fun `한 번만 반경을 벗어나면 출발이 아니다`() {
        // 튀는 좌표 한 점으로 출발을 선언하면 집에 있는 사람이 나간 것으로 기록된다.
        val f = fence()
        assertNull(f.offer(fix(home)))
        assertNull(f.offer(fix(north(home, 400.0))))
        assertEquals(TripGeofence.Phase.BEFORE_DEPARTURE, f.phase)
    }

    @Test
    fun `두 번 연속 벗어나면 출발이다`() {
        val f = fence()
        assertNull(f.offer(fix(home)))
        assertNull(f.offer(fix(north(home, 400.0))))

        val event = f.offer(fix(north(home, 500.0)))
        assertTrue("출발 판정을 기대했는데 $event", event is TripEvent.Departed)
        assertEquals(TripGeofence.Phase.IN_TRANSIT, f.phase)
        assertFalse(f.departureMissed)

        val departed = event as TripEvent.Departed
        assertTrue("거리 ${departed.distanceM}m", departed.distanceM > 150.0)
    }

    @Test
    fun `반경 안으로 돌아오면 연속 기록이 초기화된다`() {
        // 현관 앞을 서성이는 경우다. 나갔다 들어오면 다시 세야 한다.
        val f = fence()
        assertNull(f.offer(fix(home)))
        assertNull(f.offer(fix(north(home, 400.0)))) // 1회
        assertNull(f.offer(fix(north(home, 20.0))))  // 초기화
        assertNull(f.offer(fix(north(home, 400.0)))) // 다시 1회
        assertEquals(TripGeofence.Phase.BEFORE_DEPARTURE, f.phase)

        assertTrue(f.offer(fix(north(home, 400.0))) is TripEvent.Departed)
    }

    @Test
    fun `흐린 fix 는 판정에 쓰이지 않는다`() {
        // 오차 200m 인 좌표로 "150m 벗어남" 을 말할 수 없다.
        val f = fence()
        assertNull(f.offer(fix(home)))
        assertNull(f.offer(fix(north(home, 400.0), accuracy = 200f)))
        assertNull(f.offer(fix(north(home, 400.0), accuracy = 60f)))
        assertEquals(TripGeofence.Phase.BEFORE_DEPARTURE, f.phase)
    }

    @Test
    fun `흐린 fix 가 쌓인 연속 기록을 지우지 않는다`() {
        // 나가는 중에 흐린 fix 하나가 끼어들어도 판정이 뒤로 밀리면 안 된다.
        val f = fence()
        assertNull(f.offer(fix(home)))
        assertNull(f.offer(fix(north(home, 400.0))))                    // 1회
        assertNull(f.offer(fix(home, accuracy = 300f)))                 // 버려짐
        assertTrue(f.offer(fix(north(home, 450.0))) is TripEvent.Departed) // 2회
    }

    @Test
    fun `첫 위치부터 집 밖이면 출발을 기록하지 않는다`() {
        // 외박했거나 알람을 늦게 해제한 경우다. 언제 나갔는지 알 수 없으므로
        // 시각을 지어내지 않는다.
        val f = fence()
        val event = f.offer(fix(north(home, 3_000.0)))

        assertNull("출발을 만들어내면 안 된다", event)
        assertTrue(f.departureMissed)
        assertEquals(TripGeofence.Phase.IN_TRANSIT, f.phase)
    }

    @Test
    fun `집 좌표가 없으면 처음부터 이동 중이다`() {
        val f = fence(home = null)
        assertEquals(TripGeofence.Phase.IN_TRANSIT, f.phase)
        assertFalse(f.departureMissed)
    }

    // --- 도착 판정 --------------------------------------------------------

    @Test
    fun `목적지 반경 안에 두 번 들어오면 도착이다`() {
        val f = fence(home = null)
        assertNull(f.offer(fix(north(dest, 30.0))))

        val event = f.offer(fix(north(dest, 20.0)))
        assertTrue("도착 판정을 기대했는데 $event", event is TripEvent.Arrived)
        assertEquals(TripGeofence.Phase.ARRIVED, f.phase)
        assertTrue((event as TripEvent.Arrived).distanceM <= 50.0)
    }

    @Test
    fun `반경 바로 밖은 도착이 아니다`() {
        val f = fence(home = null)
        assertNull(f.offer(fix(north(dest, 60.0))))
        assertNull(f.offer(fix(north(dest, 55.0))))
        assertEquals(TripGeofence.Phase.IN_TRANSIT, f.phase)
    }

    @Test
    fun `목적지를 스쳐 지나가면 도착이 아니다`() {
        // 버스가 목적지 앞을 지나갈 때 한 fix 만 반경에 들어올 수 있다.
        val f = fence(home = null)
        assertNull(f.offer(fix(north(dest, 200.0))))
        assertNull(f.offer(fix(north(dest, 20.0))))
        assertNull(f.offer(fix(north(dest, 300.0))))
        assertEquals(TripGeofence.Phase.IN_TRANSIT, f.phase)
    }

    @Test
    fun `도착한 뒤에는 더 판정하지 않는다`() {
        val f = fence(home = null)
        f.offer(fix(north(dest, 30.0)))
        assertTrue(f.offer(fix(north(dest, 20.0))) is TripEvent.Arrived)

        // 도착 후 다시 멀어져도 새 사건을 만들지 않는다.
        assertNull(f.offer(fix(north(dest, 5_000.0))))
        assertNull(f.offer(fix(north(dest, 10.0))))
        assertEquals(TripGeofence.Phase.ARRIVED, f.phase)
    }

    @Test
    fun `목적지 좌표가 없으면 도착 판정이 없다`() {
        val f = fence(destination = null)
        assertNull(f.offer(fix(home)))
        assertNull(f.offer(fix(north(home, 400.0))))
        assertTrue(f.offer(fix(north(home, 500.0))) is TripEvent.Departed)

        // 출발은 잡았지만 도착은 볼 기준이 없다.
        assertNull(f.offer(fix(dest)))
        assertNull(f.offer(fix(dest)))
        assertEquals(TripGeofence.Phase.IN_TRANSIT, f.phase)
        assertNull(f.distanceToDestinationM)
    }

    // --- 전체 흐름 --------------------------------------------------------

    @Test
    fun `집에서 목적지까지 한 번의 이동`() {
        val f = fence()
        var departed: TripEvent.Departed? = null
        var arrived: TripEvent.Arrived? = null

        // 준비하는 동안 집에 있다
        repeat(3) { f.offer(fix(home)) }
        assertEquals(TripGeofence.Phase.BEFORE_DEPARTURE, f.phase)

        // 나가서 목적지까지 이동한다
        val path = listOf(200.0, 600.0, 1_500.0, 2_500.0, 3_000.0)
        path.forEach { meters ->
            val point = GeoPoint(
                lat = home.lat + (dest.lat - home.lat) * (meters / 3_300.0),
                lng = home.lng + (dest.lng - home.lng) * (meters / 3_300.0),
            )
            when (val e = f.offer(fix(point))) {
                is TripEvent.Departed -> departed = e
                is TripEvent.Arrived -> arrived = e
                null -> Unit
            }
        }

        assertTrue("출발이 잡혀야 한다", departed != null)
        assertEquals(TripGeofence.Phase.IN_TRANSIT, f.phase)

        // 목적지에 닿는다
        f.offer(fix(north(dest, 40.0)))
        arrived = f.offer(fix(north(dest, 15.0))) as? TripEvent.Arrived

        assertTrue("도착이 잡혀야 한다", arrived != null)
        assertEquals(TripGeofence.Phase.ARRIVED, f.phase)
        assertTrue(
            "출발이 도착보다 먼저여야 한다",
            departed!!.fix.atMillis < arrived!!.fix.atMillis,
        )
    }

    @Test
    fun `남은 거리를 알려준다`() {
        val f = fence()
        assertNull("아직 fix 가 없으면 알 수 없다", f.distanceToDestinationM)

        f.offer(fix(home))
        val remaining = f.distanceToDestinationM
        assertTrue("3.3km 근처를 기대했는데 ${remaining}m", remaining!! in 3_150.0..3_450.0)
    }

    @Test
    fun `반경을 직접 정할 수 있다`() {
        // 사용자가 원하는 10m 반경도 설정으로 가능하다. 기본값이 아닌 이유는
        // 도심 GPS 오차가 10~30m 라서 대부분의 아침에 판정이 나지 않기 때문이다.
        val strict = TripGeofence(
            home = null,
            destination = dest,
            arrivalRadiusM = 10.0,
        )
        assertNull(strict.offer(fix(north(dest, 25.0))))
        assertNull(strict.offer(fix(north(dest, 25.0))))
        assertEquals(TripGeofence.Phase.IN_TRANSIT, strict.phase)

        strict.offer(fix(north(dest, 8.0)))
        assertTrue(strict.offer(fix(north(dest, 5.0))) is TripEvent.Arrived)
    }
}
