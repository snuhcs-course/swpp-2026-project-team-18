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

    /**
     * fix 하나를 만든다. 시계를 [afterMillis] 만큼 진전시킨다.
     *
     * 기본값이 10초인 이유는 이동 중 위치 갱신 주기가 10초라서다
     * ([TripTrackingService] `TRANSIT_INTERVAL_MS`).
     */
    private fun fix(
        point: GeoPoint,
        accuracy: Float = 10f,
        afterMillis: Long = 10_000,
    ): LocationFix {
        clock += afterMillis
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
    fun be24_firstValidFixAt80mArrivesImmediately() {
        val f = fence(home = null)
        val first = fix(north(dest, 80.0))
        val arrival = f.offer(first)

        assertTrue("80m 안 첫 위치에서 바로 도착해야 한다: $arrival", arrival is TripEvent.Arrived)
        arrival as TripEvent.Arrived
        assertEquals(first.atMillis, arrival.enteredAtMillis)
        assertEquals(0L, arrival.dwellMillis)
        assertNull("도착은 한 번만 기록한다", f.offer(fix(dest)))
    }

    @Test
    fun `반경 바로 밖은 도착이 아니다`() {
        val f = fence(home = null)
        assertNull(f.offer(fix(north(dest, 101.0))))
        assertNull(f.offer(fix(north(dest, 150.0))))
        assertEquals(TripGeofence.Phase.IN_TRANSIT, f.phase)
    }

    @Test
    fun `흐린 위치는 도착으로 쓰지 않고 다음 유효 위치에서 확정한다`() {
        val f = fence(home = null)
        for (accuracy in listOf(60f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull(f.offer(fix(north(dest, 80.0), accuracy = accuracy)))
        }
        assertEquals(TripGeofence.Phase.IN_TRANSIT, f.phase)
        assertTrue(f.offer(fix(north(dest, 80.0))) is TripEvent.Arrived)
    }

    @Test
    fun `도착한 뒤에는 더 판정하지 않는다`() {
        val f = fence(home = null)
        assertTrue(f.offer(fix(north(dest, 30.0))) is TripEvent.Arrived)

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

        // 목적지 반경에 들어온 첫 위치에서 도착한다.
        arrived = f.offer(fix(north(dest, 40.0))) as? TripEvent.Arrived

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

        assertTrue(strict.offer(fix(north(dest, 8.0))) is TripEvent.Arrived)
    }

    // --- 움직임 감지 ------------------------------------------------------
    //
    // **반경만으로는 늦다.** 아파트 단지나 캠퍼스는 그 자체가 150m 를 넘어서,
    // 집을 나서 몇 분을 걸어도 반경 안이라 "준비 중" 으로 남았다. 사용자가 보는
    // 단계가 실제와 어긋나면 진행률과 도착 예정도 함께 어긋난다.
    //
    // 출발 전 위치 주기가 30초라 아래 테스트는 그 간격을 쓴다.

    /** 출발 전 갱신 주기(30초)만큼 시계를 진전시킨 fix. */
    private fun idleFix(point: GeoPoint, accuracy: Float = 10f) =
        fix(point, accuracy, afterMillis = 30_000)

    @Test
    fun `반경 안에서 걸어도 이동으로 감지된다`() {
        // 30초마다 33m — 도보 1.1m/s 다. 90초에 99m 로 기준 80m 를 넘는다.
        // 전부 집 반경 150m 안이라 반경 규칙으로는 절대 안 잡힌다.
        val f = fence()
        assertNull(f.offer(idleFix(home)))
        assertNull(f.offer(idleFix(north(home, 33.0))))
        assertNull(f.offer(idleFix(north(home, 66.0))))
        val event = f.offer(idleFix(north(home, 99.0)))

        assertTrue("걷고 있는데 출발로 안 봤다", event is TripEvent.Departed)
        assertEquals(TripGeofence.Phase.IN_TRANSIT, f.phase)
        // 반경 안에서 판정했으므로 거리는 150m 미만이다.
        assertTrue((event as TripEvent.Departed).distanceM < TripGeofence.DEFAULT_DEPARTURE_RADIUS_M)
    }

    @Test
    fun `집에 앉아 있으면 산포가 쌓여도 감지되지 않는다`() {
        // 25m 안에서 왔다 갔다 한다. 오래 있어도 기준이 앞으로 밀리므로
        // 누적되지 않는다. 밀지 않으면 앉아 있는 사람이 "이동 중" 이 된다.
        val f = fence()
        repeat(40) { i ->
            val jitter = if (i % 2 == 0) 25.0 else -25.0
            assertNull("i=$i 에서 출발로 봤다", f.offer(idleFix(north(home, jitter))))
        }
        assertEquals(TripGeofence.Phase.BEFORE_DEPARTURE, f.phase)
    }

    @Test
    fun `한 번 크게 튄 좌표로는 감지되지 않는다`() {
        // 정확도 게이트가 50m 라 변위가 오차만으로 100m 까지 벌어질 수 있다.
        // 창을 요구하지 않으면 그 한 점이 그대로 판정이 된다.
        val f = fence()
        assertNull(f.offer(fix(home, accuracy = 45f, afterMillis = 30_000)))
        assertNull(f.offer(fix(north(home, 100.0), accuracy = 45f, afterMillis = 10_000)))
        assertEquals(TripGeofence.Phase.BEFORE_DEPARTURE, f.phase)
    }

    @Test
    fun `튄 좌표가 제자리로 돌아오면 기준이 다시 잡힌다`() {
        val f = fence()
        f.offer(idleFix(home))
        f.offer(idleFix(north(home, 100.0)))   // 튐. 창이 모자라 판정 없음
        f.offer(idleFix(home))                  // 돌아왔다 → 기준 재설정
        // 여기서 다시 100m 로 가도 창이 이제부터 시작이므로 한 번에 안 잡힌다.
        assertNull(f.offer(fix(north(home, 100.0), afterMillis = 10_000)))
        assertEquals(TripGeofence.Phase.BEFORE_DEPARTURE, f.phase)
    }

    @Test
    fun `창을 채우지 못하면 거리를 넘겨도 기다린다`() {
        val f = fence()
        f.offer(idleFix(home))
        // 20초 만에 90m — 창(60초)이 모자라다.
        assertNull(f.offer(fix(north(home, 90.0), afterMillis = 20_000)))
        assertEquals(TripGeofence.Phase.BEFORE_DEPARTURE, f.phase)
    }

    @Test
    fun `반경 이탈이 더 빠르면 그쪽으로 판정한다`() {
        // 둘 중 먼저 오는 것을 쓴다. 차를 타면 10초 만에 200m 를 간다.
        val f = fence()
        f.offer(fix(home))
        f.offer(fix(north(home, 200.0)))
        val event = f.offer(fix(north(home, 400.0)))
        assertTrue(event is TripEvent.Departed)
        assertTrue((event as TripEvent.Departed).distanceM > 300.0)
    }

    @Test
    fun `흐린 fix 는 이동 감지에도 쓰이지 않는다`() {
        // 게이트 0 에서 걸러지므로 기준 fix 가 되지도 않는다.
        val f = fence()
        f.offer(idleFix(home))
        assertNull(f.offer(fix(north(home, 500.0), accuracy = 200f, afterMillis = 30_000)))
        assertNull(f.offer(idleFix(north(home, 20.0))))
        assertEquals(TripGeofence.Phase.BEFORE_DEPARTURE, f.phase)
    }

    @Test
    fun `이동으로 출발한 뒤에는 도착 판정으로 넘어간다`() {
        val f = fence()
        f.offer(idleFix(home))
        f.offer(idleFix(north(home, 40.0)))
        f.offer(idleFix(north(home, 90.0)))
        assertEquals(TripGeofence.Phase.IN_TRANSIT, f.phase)

        val arrived = f.offer(fix(dest))
        assertTrue("이동 감지로 출발한 여정이 도착을 못 잡는다", arrived is TripEvent.Arrived)
    }

    @Test
    fun `시계가 거꾸로 가면 창을 다시 시작한다`() {
        val f = fence()
        f.offer(idleFix(home))
        // 캐시된 좌표가 과거 시각으로 들어온다. 그것을 기준으로 삼으면 창이
        // 이미 채워진 것처럼 보인다.
        val stale = LocationFix(north(home, 120.0).lat, home.lng, 10f, clock - 600_000L)
        assertNull(f.offer(stale))
        assertEquals(TripGeofence.Phase.BEFORE_DEPARTURE, f.phase)
    }

    @Test
    fun `이동 감지 상수가 서로 어긋나지 않는다`() {
        // 재설정 거리가 감지 거리보다 크면 영영 감지되지 않는다.
        assertTrue(TripGeofence.MOVING_RESET_M < TripGeofence.MOVING_DISTANCE_M)
        // 창은 출발 전 갱신 주기(30초)보다 길어야 좌표 둘 이상을 본다.
        assertTrue(TripGeofence.MOVING_WINDOW_MILLIS >= 60_000L)
    }

}
