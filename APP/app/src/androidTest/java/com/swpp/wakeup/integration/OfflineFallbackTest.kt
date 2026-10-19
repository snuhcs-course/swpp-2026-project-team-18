package com.swpp.wakeup.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.wakeup.data.local.CachedEventEntity
import com.swpp.wakeup.data.local.JitDatabase
import com.swpp.wakeup.data.local.OfflineCache
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.Paged
import com.swpp.wakeup.data.remote.ProfileDto
import com.swpp.wakeup.data.repository.EventRepository
import com.swpp.wakeup.data.repository.EventRepository.HomeData
import com.swpp.wakeup.data.repository.EventRepository.Result
import com.swpp.wakeup.testing.FakeEventsApi
import com.swpp.wakeup.testing.FakeProfileApi
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import java.io.IOException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response

/**
 * 일정 저장소 + 오프라인 캐시 + 실제 Room DB.
 *
 * JVM 단위 테스트(`EventRepositoryTest`)는 캐시 없이 서버 응답만 본다. 여기서는
 * 서버가 안 될 때 **실제 디스크 캐시**로 화면을 채우는 경로를 기기에서 돌린다.
 *
 * ## 이 테스트가 지키는 것
 *
 * - 서버가 실패하면(네트워크·5xx 모두) 마지막으로 받은 일정으로 홈을 채우고,
 *   **캐시에서 왔다는 것과 얼마나 오래됐는지**를 함께 준다. 호출부는 그 표시를 보고
 *   알람을 다시 등록하지 않는다.
 * - 다른 계정의 사본은 보이지 않는다. 이메일 대소문자·공백은 같은 계정으로 본다.
 * - 로그인 전(이메일 없음)에는 캐시에 쓰지 않는다.
 * - 깨진 행 하나가 목록 전체를 비우지 않는다.
 *
 * 앱의 실제 캐시 파일을 쓰므로 테스트 앞뒤로 전부 지운다.
 */
@RunWith(AndroidJUnit4::class)
class OfflineFallbackTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val me = "me@snu.ac.kr"

    @Before
    @After
    fun wipe() = runBlocking<Unit> { OfflineCache(context, ownerEmail = null).wipe() }

    // --- 픽스처 -------------------------------------------------------------

    private val profile = fromJson<ProfileDto>(
        """{"home_lat":37.48,"home_lng":126.95,"home_label":"우리 집","has_home":true,
            "default_tau":0.9,"onboarding_prep_min":25,"timezone":"Asia/Seoul"}"""
    )

    /** 항상 미래인 시각. 홈은 지난 일정을 빼므로 고정 날짜를 쓰면 언젠가 깨진다. */
    private fun future(days: Long): OffsetDateTime =
        OffsetDateTime.now(ZoneOffset.UTC).plusDays(days).withHour(9).withMinute(0)
            .withSecond(0).withNano(0)

    private fun event(id: Long, start: OffsetDateTime = future(days = id)): EventDto = fromJson(
        """{"id":$id,"title":"일정 $id","start_at":"$start","source":"manual",
            "place":{"id":1,"name":"서울대","address":"관악로 1","lat":37.46,"lng":126.95},
            "alarm_enabled":true,"alarm_on":true,
            "alarm_plan":{"status":"ok","alarm_at":"${start.minusHours(2)}","prep_minutes":30,
              "travel_minutes":40,"travel_mode":"지하철","route_summary":"2호선"}}"""
    )

    /** 서버가 정상일 때. 받은 응답을 캐시에 남긴다. */
    private fun online(owner: String?, vararg events: EventDto) = EventRepository(
        api = object : FakeEventsApi() {
            override suspend fun list(from: String?, to: String?, limit: Int) =
                ok(Paged(count = events.size, results = events.toList()))

            override suspend fun get(id: Long) = ok(events.first { it.id == id })
        },
        profileApi = object : FakeProfileApi() {
            override suspend fun get() = ok(profile)
        },
        cache = OfflineCache(context, owner),
    )

    /** 지하철 안. 모든 요청이 네트워크 오류로 끝난다. */
    private fun offline(owner: String?) = EventRepository(
        api = object : FakeEventsApi() {
            override suspend fun list(from: String?, to: String?, limit: Int): Response<Paged<EventDto>> =
                throw IOException("offline")

            override suspend fun get(id: Long): Response<EventDto> = throw IOException("offline")
        },
        profileApi = object : FakeProfileApi() {
            override suspend fun get(): Response<ProfileDto> = throw IOException("offline")
        },
        cache = OfflineCache(context, owner),
    )

    private fun Result<HomeData>.data(): HomeData {
        assertTrue("성공이어야 한다: $this", this is Result.Success)
        return (this as Result.Success).data
    }

    private fun HomeData.ids() = sections.flatMap { it.events }.map { it.id }

    // --- 홈 -----------------------------------------------------------------

    @Test
    fun `서버가 안 되면 마지막으로 받은 일정으로 홈을 채우고 캐시라고 표시한다`() = runBlocking<Unit> {
        val fresh = online(me, event(1), event(2)).loadHome().data()
        assertFalse(fresh.fromCache)

        val cached = offline(me).loadHome().data()

        assertTrue("호출부가 알람을 다시 등록하지 않게 캐시라고 알린다", cached.fromCache)
        assertEquals(fresh.ids(), cached.ids())
        assertEquals(fresh.nextAlarm?.id, cached.nextAlarm?.id)
        assertEquals("방금 정보", cached.ageLabel)
        assertTrue(cached.hasHome)
    }

    @Test
    fun `서버가 5xx 를 돌려줘도 캐시로 홈을 채운다`() = runBlocking<Unit> {
        online(me, event(1)).loadHome().data()
        val down = EventRepository(
            api = FakeEventsApi(),
            profileApi = object : FakeProfileApi() {
                override suspend fun get() = httpError<ProfileDto>(503)
            },
            cache = OfflineCache(context, me),
        )

        val cached = down.loadHome().data()

        assertTrue(cached.fromCache)
        assertEquals(listOf(1L), cached.ids())
    }

    @Test
    fun `캐시가 없으면 원래 실패를 그대로 돌려준다`() = runBlocking<Unit> {
        val result = offline(me).loadHome()

        assertTrue("빈 목록을 성공으로 보여주면 일정이 사라진 것처럼 보인다", result is Result.Failure)
    }

    @Test
    fun `다른 계정의 사본은 보이지 않는다`() = runBlocking<Unit> {
        online("other@snu.ac.kr", event(1)).loadHome().data()

        val result = offline(me).loadHome()

        assertTrue(result is Result.Failure)
    }

    @Test
    fun `이메일 대소문자와 공백이 달라도 같은 계정의 사본이다`() = runBlocking<Unit> {
        online("Me@SNU.ac.kr ", event(1)).loadHome().data()

        val cached = offline(" me@snu.ac.kr").loadHome().data()

        assertEquals(listOf(1L), cached.ids())
    }

    @Test
    fun `로그인 전에는 캐시에 쓰지 않는다`() = runBlocking<Unit> {
        online(owner = null, event(1)).loadHome().data()

        assertTrue(offline(me).loadHome() is Result.Failure)
    }

    @Test
    fun `서버가 다시 되면 캐시는 새 목록으로 바뀐다`() = runBlocking<Unit> {
        online(me, event(1), event(2)).loadHome().data()
        // 다른 기기에서 일정 1 을 지웠다.
        online(me, event(2)).loadHome().data()

        val cached = offline(me).loadHome().data()

        assertEquals("지운 일정이 오프라인에서 되살아나지 않는다", listOf(2L), cached.ids())
    }

    @Test
    fun `깨진 캐시 행은 버리고 나머지를 보여준다`() = runBlocking<Unit> {
        online(me, event(1), event(2)).loadHome().data()
        // 앱 업데이트로 DTO 모양이 바뀌어 옛 JSON 하나를 읽을 수 없게 된 상황.
        JitDatabase.get(context).cache().putEvent(
            CachedEventEntity(
                id = 2,
                ownerEmail = me,
                startAtEpochSecond = future(days = 2).toEpochSecond(),
                payload = "{broken",
                cachedAt = System.currentTimeMillis(),
            )
        )

        val cached = offline(me).loadHome().data()

        assertEquals(listOf(1L), cached.ids())
    }

    // --- 알람 근거 화면 --------------------------------------------------------

    @Test
    fun `알람 근거 화면은 오프라인에서도 마지막으로 받은 계획으로 열린다`() = runBlocking<Unit> {
        val fresh = online(me, event(1)).loadPlan(1)
        assertTrue(fresh is Result.Success)

        val cached = offline(me).loadPlan(1)

        assertTrue("지하철 안에서 '왜 지금 일어나야 하는가' 를 볼 수 있어야 한다", cached is Result.Success)
        assertEquals(
            (fresh as Result.Success).data.alarmAt,
            (cached as Result.Success).data.alarmAt,
        )
    }
}
