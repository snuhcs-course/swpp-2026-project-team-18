package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.EventCreateRequest
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.EventsApi
import com.swpp.wakeup.data.remote.PlaceSearchItem
import com.swpp.wakeup.data.remote.ProfileApi
import java.lang.reflect.Proxy
import java.time.OffsetDateTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class HomeDestinationRequestTest {
    @Test
    fun `집 선택은 장소 사본을 보내지 않고 일반 장소 선택은 좌표를 보낸다`() = runBlocking {
        val gson = Gson()
        val requests = mutableListOf<EventCreateRequest>()
        val event = gson.fromJson(
            """{"id":1,"title":"귀가","start_at":"2030-01-01T01:00:00Z","source":"manual"}""",
            EventDto::class.java,
        )
        val api = Proxy.newProxyInstance(
            EventsApi::class.java.classLoader, arrayOf(EventsApi::class.java),
        ) { _, method, args ->
            check(method.name == "create")
            requests += args[0] as EventCreateRequest
            Response.success(event)
        } as EventsApi
        val profileApi = Proxy.newProxyInstance(
            ProfileApi::class.java.classLoader, arrayOf(ProfileApi::class.java),
        ) { _, _, _ -> error("Profile lookup is not required for creation") } as ProfileApi
        val repository = EventRepository(api = api, profileApi = profileApi)
        val place = gson.fromJson(
            """{"name":"집 좌표","lat":37.46,"lng":126.95}""", PlaceSearchItem::class.java,
        )
        for (home in listOf(true, false)) {
            val result = repository.createEvent(
                title = "귀가", startAt = OffsetDateTime.parse("2030-01-01T01:00:00Z"),
                place = place, tagKey = null, destinationIsHome = home,
            )
            assertTrue(result is EventRepository.Result.Success)
        }
        assertTrue(requests[0].destinationIsHome)
        assertNull(requests[0].place)
        assertFalse(gson.toJsonTree(requests[0]).asJsonObject.has("place"))
        assertFalse(requests[1].destinationIsHome)
        assertEquals(place.lat, requests[1].place!!.lat, 0.0)
    }
}
