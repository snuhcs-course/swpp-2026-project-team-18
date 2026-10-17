package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.EventCreateRequest
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.EventTagDto
import com.swpp.wakeup.data.remote.EventUpdateRequest
import com.swpp.wakeup.data.remote.Paged
import com.swpp.wakeup.data.remote.PlaceInput
import com.swpp.wakeup.data.remote.PlaceReverseResponse
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.PlaceSearchResponse
import com.swpp.wakeup.data.remote.ProfileDto
import com.swpp.wakeup.data.remote.ProfileUpdateRequest
import com.swpp.wakeup.data.remote.RouteCandidateResponse
import com.swpp.wakeup.data.repository.EventRepository.HomeData
import com.swpp.wakeup.data.repository.EventRepository.Result
import com.swpp.wakeup.testing.FakeEventsApi
import com.swpp.wakeup.testing.FakeProfileApi
import com.swpp.wakeup.testing.errorJson
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * 일정 저장소. 캐시 없이(`cache = null`) 서버 응답만으로 도는 경로를 본다.
 * 오프라인 캐시(Room)는 기기에서 도는 통합 테스트가 맡는다.
 *
 * ## 이 테스트가 지키는 것
 *
 * - 홈은 **지난 일정을 빼고**, 다음 알람은 **서버가 켠(alarm_on) 일정 중에서만** 고른다.
 *   알람 등록 목록도 같은 기준이다. 꺼진 일정의 알람이 울리면 앱을 못 믿게 된다.
 * - 요청 본문은 **바꾼 것만** 담는다. 알람 스위치에 제목을 실어 보내면 다른 기기의
 *   수정을 덮어쓰고, 준비 시간 저장에 좌표를 실으면 집을 지운다.
 * - 경로를 고를 때 쓴 출발지를 일정 생성에도 그대로 보낸다. 빠지면 서버가 집 기준으로
 *   다시 계산해 사용자가 본 소요시간과 알람이 어긋난다.
 * - 서버가 "부분 실패(degraded)" 라고 하면 빈 결과를 성공으로 보여주지 않는다.
 */
class EventRepositoryTest {

    // --- 픽스처 -------------------------------------------------------------

    private val profile = fromJson<ProfileDto>(
        """{"home_lat":37.48,"home_lng":126.95,"home_label":"우리 집","has_home":true,
            "default_tau":0.9,"onboarding_prep_min":25,"timezone":"Asia/Seoul"}"""
    )

    private fun event(
        id: Long,
        startAt: String,
        alarmOn: Boolean? = true,
        plan: String? = """{"status":"ok","alarm_at":"${shiftHours(startAt, -2)}","prep_minutes":30,
            "travel_minutes":40,"travel_mode":"지하철","route_summary":"2호선"}""",
    ): EventDto = fromJson(
        """{"id":$id,"title":"일정 $id","start_at":"$startAt","source":"manual",
            "place":{"id":1,"name":"서울대","address":"관악로 1","lat":37.46,"lng":126.95},
            "alarm_enabled":true,"alarm_on":$alarmOn,"alarm_plan":$plan}"""
    )

    private fun shiftHours(iso: String, hours: Long): String =
        OffsetDateTime.parse(iso).plusHours(hours).toString()

    /** 항상 미래인 시각. 홈은 지난 일정을 빼므로 고정 날짜를 쓰면 언젠가 깨진다. */
    private fun future(days: Long, hour: Int = 9): String =
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(days).withHour(hour).withMinute(0)
            .withSecond(0).withNano(0).toString()

    private val past = "2000-01-01T00:00:00Z"

    private fun listApi(vararg events: EventDto) = object : FakeEventsApi() {
        override suspend fun list(from: String?, to: String?, limit: Int) =
            ok(Paged(count = events.size, results = events.toList()))
    }

    private fun profileApi(dto: ProfileDto = profile) = object : FakeProfileApi() {
        override suspend fun get() = ok(dto)
    }

    private fun Result<HomeData>.data(): HomeData {
        assertTrue("성공이어야 한다: $this", this is Result.Success)
        return (this as Result.Success).data
    }

    // --- 홈 -----------------------------------------------------------------

    @Test
    fun `홈은 지난 일정을 빼고 시작 시각 순으로 센다`() = runTest {
        val later = event(1, future(days = 3))
        val sooner = event(2, future(days = 1))
        val gone = event(3, past)

        val home = EventRepository(listApi(later, gone, sooner), profileApi()).loadHome().data()

        assertEquals(2, home.totalCount)
        assertEquals(listOf(2L, 1L), home.sections.flatMap { it.events }.map { it.id })
        assertFalse(home.fromCache)
        assertNull(home.ageLabel)
    }

    @Test
    fun `다음 알람은 서버가 켠 일정 중 가장 이른 알람이다`() = runTest {
        val off = event(1, future(days = 1), alarmOn = false)
        val on = event(2, future(days = 2))
        val onLater = event(3, future(days = 4))

        val home = EventRepository(listApi(off, onLater, on), profileApi()).loadHome().data()

        assertEquals(2L, home.nextAlarm?.id)
        assertEquals(
            "등록 목록도 서버가 켠 일정만 담는다",
            setOf(2L, 3L),
            home.schedules.map { it.eventId }.toSet(),
        )
    }

    @Test
    fun `알람이 계산되지 않은 일정 수를 따로 센다`() = runTest {
        val planned = event(1, future(days = 1))
        val noPlan = event(2, future(days = 2), plan = null)

        val home = EventRepository(listApi(planned, noPlan), profileApi()).loadHome().data()

        assertEquals(1, home.unplannedCount)
        assertEquals(1, home.schedules.size)
    }

    @Test
    fun `프로필의 집과 준비 시간을 그대로 올린다`() = runTest {
        val home = EventRepository(listApi(), profileApi()).loadHome().data()

        assertTrue(home.hasHome)
        assertEquals("우리 집", home.homeLabel)
        assertEquals(37.48, home.homeLat!!, 1e-9)
        assertEquals(25, home.onboardingPrepMin)
        assertTrue(home.sections.isEmpty())
        assertNull(home.nextAlarm)
    }

    @Test
    fun `준비 시간을 답하지 않았으면 null 로 남긴다`() = runTest {
        // 0 이나 기본값을 넣으면 "답한 30분" 과 "안 물어봐서 30분" 이 구분되지 않아
        // 준비 시간 온보딩을 띄울 근거가 사라진다.
        val unanswered = fromJson<ProfileDto>(
            """{"has_home":false,"home_lat":null,"home_lng":null,"home_label":"",
                "onboarding_prep_min":0}"""
        )

        val home = EventRepository(listApi(), profileApi(unanswered)).loadHome().data()

        assertNull(home.onboardingPrepMin)
        assertNull("빈 집 이름은 없는 것으로 본다", home.homeLabel)
        assertFalse(home.hasHome)
    }

    @Test
    fun `프로필 조회가 실패하면 일정 목록을 부르지 않고 실패한다`() = runTest {
        // FakeEventsApi 의 list 는 부르면 테스트가 실패한다.
        val profileDown = object : FakeProfileApi() {
            override suspend fun get() = httpError<ProfileDto>(401)
        }

        val result = EventRepository(FakeEventsApi(), profileDown).loadHome()

        assertEquals(Result.Failure("다시 로그인해야 한다."), result)
    }

    @Test
    fun `네트워크가 끊기고 캐시도 없으면 네트워크 문구로 실패한다`() = runTest {
        val offline = object : FakeProfileApi() {
            override suspend fun get(): Response<ProfileDto> = throw IOException("offline")
        }

        val result = EventRepository(FakeEventsApi(), offline).loadHome()

        assertTrue((result as Result.Failure).message.startsWith("서버에 연결할 수 없다"))
    }

    // --- 알람 스위치 ----------------------------------------------------------

    @Test
    fun `알람 스위치는 alarm_enabled 하나만 보내고 홈을 다시 읽는다`() = runTest {
        var sent: EventUpdateRequest? = null
        val toggled = event(1, future(days = 1), alarmOn = false)
        val api = object : FakeEventsApi() {
            override suspend fun update(id: Long, body: EventUpdateRequest) =
                ok(toggled).also { sent = body }

            override suspend fun list(from: String?, to: String?, limit: Int) =
                ok(Paged(results = listOf(toggled)))
        }

        val home = EventRepository(api, profileApi()).setAlarmEnabled(1, enabled = false).data()

        assertEquals(EventUpdateRequest(alarmEnabled = false), sent)
        assertNull("다시 읽은 목록에서 꺼진 일정은 다음 알람이 아니다", home.nextAlarm)
    }

    @Test
    fun `알람 스위치 저장이 실패하면 홈을 다시 읽지 않는다`() = runTest {
        val api = object : FakeEventsApi() {
            override suspend fun update(id: Long, body: EventUpdateRequest) =
                httpError<EventDto>(404)
        }

        val result = EventRepository(api, FakeProfileApi()).setAlarmEnabled(1, enabled = true)

        assertEquals(Result.Failure("대상을 찾을 수 없다."), result)
    }

    // --- 일정 생성 ------------------------------------------------------------

    private val snu = PlaceSearchItem(
        kakaoPlaceId = "8123",
        name = "서울대학교",
        address = "관악로 1",
        lat = 37.46,
        lng = 126.95,
        category = "대학교",
    )

    private val cafe = PlaceSearchItem(
        kakaoPlaceId = null,
        name = "학교 앞 카페",
        address = null,
        lat = 37.47,
        lng = 126.94,
        category = null,
    )

    @Test
    fun `일정 생성은 장소와 경로 선택 때의 출발지를 함께 보낸다`() = runTest {
        var sent: EventCreateRequest? = null
        val created = event(9, future(days = 1))
        val api = object : FakeEventsApi() {
            override suspend fun create(body: EventCreateRequest) = ok(created).also { sent = body }
        }
        val startAt = OffsetDateTime.parse("2030-03-02T09:00+09:00")

        val result = EventRepository(api, FakeProfileApi()).createEvent(
            title = "  수업 ",
            startAt = startAt,
            place = snu,
            tagKey = "class",
            routeKey = "transit:2호선",
            origin = cafe,
        )

        assertEquals(Result.Success(created), result)
        assertEquals(
            EventCreateRequest(
                title = "수업",
                startAt = startAt.toString(),
                place = PlaceInput(
                    name = "서울대학교",
                    lat = 37.46,
                    lng = 126.95,
                    address = "관악로 1",
                    kakaoPlaceId = "8123",
                ),
                tagKey = "class",
                routeKey = "transit:2호선",
                originLat = 37.47,
                originLng = 126.94,
                originLabel = "학교 앞 카페",
            ),
            sent,
        )
    }

    @Test
    fun `집에서 출발하고 경로를 고르지 않았으면 출발지와 경로를 보내지 않는다`() = runTest {
        var sent: EventCreateRequest? = null
        val api = object : FakeEventsApi() {
            override suspend fun create(body: EventCreateRequest) =
                ok(event(9, future(days = 1))).also { sent = body }
        }

        EventRepository(api, FakeProfileApi()).createEvent(
            title = "수업",
            startAt = OffsetDateTime.parse("2030-03-02T09:00+09:00"),
            place = null,
            tagKey = null,
            routeKey = "  ",
            origin = null,
        )

        assertNull(sent!!.place)
        assertNull("빈 경로 키는 '자동 선택' 이다", sent!!.routeKey)
        assertNull(sent!!.originLat)
        assertNull(sent!!.originLabel)
    }

    @Test
    fun `일정 생성 실패는 서버 문구를 돌려준다`() = runTest {
        val api = object : FakeEventsApi() {
            override suspend fun create(body: EventCreateRequest) =
                httpError<EventDto>(400, errorJson("출발지와 도착지가 같다."))
        }

        val result = EventRepository(api, FakeProfileApi()).createEvent(
            title = "수업",
            startAt = OffsetDateTime.parse("2030-03-02T09:00+09:00"),
            place = snu,
            tagKey = null,
        )

        assertEquals(Result.Failure("출발지와 도착지가 같다."), result)
    }

    // --- 삭제·태그 ------------------------------------------------------------

    @Test
    fun `삭제 성공과 실패`() = runTest {
        val okApi = object : FakeEventsApi() {
            override suspend fun delete(id: Long): Response<Unit> = ok(Unit)
        }
        val goneApi = object : FakeEventsApi() {
            override suspend fun delete(id: Long) = httpError<Unit>(404)
        }

        assertEquals(Result.Success(Unit), EventRepository(okApi, FakeProfileApi()).deleteEvent(1))
        assertEquals(
            Result.Failure("대상을 찾을 수 없다."),
            EventRepository(goneApi, FakeProfileApi()).deleteEvent(1),
        )
    }

    @Test
    fun `태그 목록을 그대로 돌려주고 실패하면 실패한다`() = runTest {
        val tags = listOf(fromJson<EventTagDto>("""{"id":1,"key":"exam","label":"시험","default_tau":0.97}"""))
        val okApi = object : FakeEventsApi() {
            override suspend fun tags() = ok(tags)
        }
        val downApi = object : FakeEventsApi() {
            override suspend fun tags() = httpError<List<EventTagDto>>(500)
        }

        assertEquals(Result.Success(tags), EventRepository(okApi, FakeProfileApi()).tags())
        assertTrue(EventRepository(downApi, FakeProfileApi()).tags() is Result.Failure)
    }

    // --- 프로필 수정 -----------------------------------------------------------

    @Test
    fun `집 저장은 준비 시간을 함께 보내지 않는다`() = runTest {
        // 함께 보내면 onboarding_prep_min 이 채워져 준비 시간 온보딩이 뜨지 않는다.
        var sent: ProfileUpdateRequest? = null
        val api = object : FakeProfileApi() {
            override suspend fun update(body: ProfileUpdateRequest) = ok(profile).also { sent = body }
        }

        EventRepository(FakeEventsApi(), api).setHome("우리 집", 37.48, 126.95)

        assertEquals(
            ProfileUpdateRequest(homeLat = 37.48, homeLng = 126.95, homeLabel = "우리 집"),
            sent,
        )
    }

    @Test
    fun `준비 시간 저장은 집 좌표를 건드리지 않는다`() = runTest {
        var sent: ProfileUpdateRequest? = null
        val api = object : FakeProfileApi() {
            override suspend fun update(body: ProfileUpdateRequest) = ok(profile).also { sent = body }
        }

        val result = EventRepository(FakeEventsApi(), api).setOnboardingPrep(40)

        assertEquals(Result.Success(profile), result)
        assertEquals(ProfileUpdateRequest(onboardingPrepMin = 40), sent)
    }

    // --- 장소 ---------------------------------------------------------------

    @Test
    fun `장소 검색이 부분 실패면 빈 결과를 성공으로 보이지 않는다`() = runTest {
        val api = object : FakeEventsApi() {
            override suspend fun searchPlaces(
                query: String, lat: Double?, lng: Double?, page: Int, sort: String?, rect: String?,
            ) = ok(PlaceSearchResponse(degraded = true))
        }

        val result = EventRepository(api, FakeProfileApi()).searchPlaces("서울대")

        assertEquals(Result.Failure("장소 검색 결과를 불러오지 못했다."), result)
    }

    @Test
    fun `장소 검색은 현재 위치를 함께 보내 거리를 받는다`() = runTest {
        var sentLat: Double? = null
        val api = object : FakeEventsApi() {
            override suspend fun searchPlaces(
                query: String, lat: Double?, lng: Double?, page: Int, sort: String?, rect: String?,
            ): Response<PlaceSearchResponse> {
                sentLat = lat
                return ok(PlaceSearchResponse(results = listOf(snu)))
            }
        }

        val result = EventRepository(api, FakeProfileApi())
            .searchPlaces("서울대", near = com.swpp.wakeup.sensing.GeoPoint(37.5, 127.0))

        assertEquals(37.5, sentLat!!, 1e-9)
        assertEquals(listOf(snu), (result as Result.Success).data.results)
    }

    @Test
    fun `역지오코딩이 부분 실패면 실패로 알린다`() = runTest {
        val degraded = object : FakeEventsApi() {
            override suspend fun reversePlace(lat: Double, lng: Double) =
                ok(PlaceReverseResponse(degraded = true))
        }
        val found = object : FakeEventsApi() {
            override suspend fun reversePlace(lat: Double, lng: Double) =
                ok(PlaceReverseResponse(result = snu))
        }

        assertEquals(
            Result.Failure("현재 위치의 주소를 불러오지 못했다."),
            EventRepository(degraded, FakeProfileApi()).reversePlace(37.0, 127.0),
        )
        assertEquals(Result.Success(snu), EventRepository(found, FakeProfileApi()).reversePlace(37.0, 127.0))
    }

    // --- 경로·알람 근거 ---------------------------------------------------------

    @Test
    fun `경로 후보가 하나도 없으면 장소를 다시 확인하라고 한다`() = runTest {
        val api = object : FakeEventsApi() {
            override suspend fun routeCandidates(
                destLat: Double, destLng: Double, originLat: Double?, originLng: Double?, originLabel: String?,
                destLabel: String?,
            ) = ok(fromJson<RouteCandidateResponse>("""{"origin":null,"results":[]}"""))
        }

        val result = EventRepository(api, FakeProfileApi()).routeCandidates(snu)

        assertEquals(Result.Failure("경로를 찾지 못했다. 장소를 다시 확인한다."), result)
    }

    @Test
    fun `알람 계획이 없는 일정은 근거 화면을 열 수 없다고 알린다`() = runTest {
        val api = object : FakeEventsApi() {
            override suspend fun get(id: Long) = ok(event(1, future(days = 1), plan = null))
        }

        val result = EventRepository(api, FakeProfileApi()).loadPlan(1)

        assertEquals(Result.Failure("알람이 아직 계산되지 않았다."), result)
    }

    @Test
    fun `알람 계획이 있으면 근거 화면 데이터를 만든다`() = runTest {
        val api = object : FakeEventsApi() {
            override suspend fun get(id: Long) = ok(event(1, future(days = 1)))
        }

        assertTrue(EventRepository(api, FakeProfileApi()).loadPlan(1) is Result.Success)
    }

    @Test
    fun `다시 계산 실패는 서버 문구를 돌려준다`() = runTest {
        val api = object : FakeEventsApi() {
            override suspend fun recompute(id: Long) =
                httpError<EventDto>(429, errorJson("경로 조회 한도를 넘었다."))
        }

        val result = EventRepository(api, FakeProfileApi()).recomputePlan(1)

        assertEquals(Result.Failure("경로 조회 한도를 넘었다."), result)
    }
}
