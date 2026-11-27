package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.RoomCreateInput
import com.swpp.wakeup.data.remote.RoomDestinationInput
import com.swpp.wakeup.data.remote.RoomDto
import com.swpp.wakeup.data.remote.RoomJoinInput
import com.swpp.wakeup.data.remote.RoomMemberDto
import com.swpp.wakeup.data.remote.RoomWakeSuggestionDto
import com.swpp.wakeup.data.remote.RoomsApi
import com.swpp.wakeup.data.remote.RoomsListDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import java.io.IOException
import java.time.ZoneId

class RoomsRepositoryTest {
    private fun room() = RoomDto(12, "금요일 모임", "2026-12-04T09:00:00+09:00", "서울대입구역", .9, 2,
        "ABC123", listOf(RoomMemberDto("진호", true, "2026-12-04T07:10:00+09:00", 0),
            RoomMemberDto("준하", false, null, null)))

    private fun input() = RoomCreateInput("모임", "2026-12-04T09:00:00+09:00",
        RoomDestinationInput("역", "서울", 37.4, 126.9, null), .9, "uuid")

    private class Api : RoomsApi {
        var list: Response<RoomsListDto> = Response.success(RoomsListDto(emptyList()))
        var detail: Response<RoomDto> = Response.success(RoomDto())
        var fail: Exception? = null
        var created: RoomCreateInput? = null
        var joined: RoomJoinInput? = null
        override suspend fun list(): Response<RoomsListDto> { fail?.let { throw it }; return list }
        override suspend fun detail(id: Long): Response<RoomDto> = detail
        override suspend fun create(input: RoomCreateInput): Response<RoomDto> { created = input; return detail }
        override suspend fun join(input: RoomJoinInput): Response<RoomDto> { joined = input; return detail }
    }

    private fun <T> error(status: Int, body: String = "{}") =
        Response.error<T>(status, body.toResponseBody("application/json".toMediaType()))

    @Test fun `nullable DTO never creates a successful empty room`() = runTest {
        val api = Api()
        api.list = Response.success(RoomsListDto())
        assertTrue(RemoteRoomsRepository(api).list() is RoomsResult.Failure)
        api.list = Response.success(RoomsListDto(listOf(RoomDto())))
        assertTrue(RemoteRoomsRepository(api).list() is RoomsResult.Failure)
        assertNull(RoomDto().toRoomDetail())
    }

    @Test fun `actual empty list is successful`() = runTest {
        val result = RemoteRoomsRepository(Api()).list() as RoomsResult.Success
        assertEquals(emptyList<Any>(), result.data)
    }

    @Test fun `list 404 is unsupported but detail and join 404 preserve missing room meaning`() = runTest {
        val api = Api().apply { list = error(404); detail = error(404) }
        val repo = RemoteRoomsRepository(api)
        assertEquals(RoomsResult.Unsupported, repo.list())
        assertEquals(RoomsResult.Unsupported, repo.create(input()))
        assertEquals(404, (repo.detail(12) as RoomsResult.Failure).status)
        assertEquals(404, (repo.join("ABC123") as RoomsResult.Failure).status)
    }

    @Test fun `explicit unavailable code is supported on any route`() = runTest {
        val api = Api().apply {
            detail = error(503, """{"error":{"code":"rooms_unavailable","message":"준비 중이에요"}}""")
        }
        assertEquals(RoomsResult.Unsupported, RemoteRoomsRepository(api).join("ABC123"))
    }

    @Test fun `field failure preserves server message and field location`() = runTest {
        val api = Api().apply {
            detail = error(400, """{"error":{"code":"invalid","message":"입력을 확인해 주세요","details":{"code":["초대 코드가 달라요"]}}}""")
        }
        val result = RemoteRoomsRepository(api).join("ABC123") as RoomsResult.Failure
        assertEquals("입력을 확인해 주세요", result.message)
        assertEquals("초대 코드가 달라요", result.fields["code"])
    }

    @Test fun `network failure offers retry while cancellation propagates`() = runTest {
        val api = Api().apply { fail = IOException() }
        assertTrue((RemoteRoomsRepository(api).list() as RoomsResult.Failure).message.contains("인터넷"))
        api.fail = CancellationException("left screen")
        try {
            RemoteRoomsRepository(api).list()
            fail("Cancellation must not become a UI error")
        } catch (_: CancellationException) { }
    }

    @Test fun `shared data maps without exposing private member payload`() {
        val json = """{"id":12,"title":"모임","start_at":"2026-12-04T09:00:00+09:00","destination_name":"역","group_target_probability":0.9,"member_count":1,"invite_code":"ABC123","members":[{"nickname":"진호","is_me":true,"alarm_at":"2026-12-04T07:10:00+09:00","earlier_minutes":0,"home_address":"PRIVATE_HOME","home_lat":37.555123,"route":{"private":"PRIVATE_ROUTE"}}]}"""
        val detail = Gson().fromJson(json, RoomDto::class.java).toRoomDetail()!!
        assertEquals("오전 7:10", detail.members.single().alarmLabel(ZoneId.of("Asia/Seoul")))
        assertFalse(detail.toString().contains("PRIVATE_"))
        assertFalse(detail.toString().contains("37.555123"))
        assertEquals("90%", detail.room.targetLabel)
    }

    @Test fun `missing alarm is waiting and does not fabricate a clock`() {
        val detail = room().toRoomDetail()!!
        assertEquals("아직 계산 중이에요", detail.members.last().alarmLabel())
        assertEquals("알람 계산 결과를 기다리고 있어요", detail.members.last().adjustmentLabel)
    }

    @Test fun `member count me flag probability and dates must be coherent`() {
        assertNull(room().copy(memberCount = 3).toRoomDetail())
        assertNull(room().copy(members = room().members!!.map { it.copy(isMe = true) }).toRoomDetail())
        assertNull(room().copy(groupTargetProbability = Double.NaN).toRoomDetail())
        assertNull(room().copy(groupTargetProbability = 1.0).toRoomDetail())
        assertNull(room().copy(startAt = "later").toRoomDetail())
        assertNull(room().copy(inviteCode = "<bad>").toRoomDetail())
        assertNull(room().copy(members = listOf(RoomMemberDto("진호", true, "broken", 0), room().members!!.last())).toRoomDetail())
    }

    @Test fun `earlier wake recommendation comes from the matching server member`() {
        val dto = room().copy(members = listOf(RoomMemberDto("진호", true, "2026-12-04T07:00:00+09:00", 5),
            RoomMemberDto("준하", false, "2026-12-04T06:55:00+09:00", 0)),
            wakeSuggestion = RoomWakeSuggestionDto("진호", 5))
        assertEquals("진호 님의 알람을 5분 앞당기면 목표에 가까워져요", dto.toRoomDetail()!!.wakeSuggestion!!.label)
        assertNull(dto.copy(wakeSuggestion = RoomWakeSuggestionDto("없는 멤버", 5)).toRoomDetail())
    }

    @Test fun `creation and join forward the selected destination and code`() = runTest {
        val api = Api().apply { detail = Response.success(room()) }
        val repo = RemoteRoomsRepository(api)
        val request = input()
        assertTrue(repo.create(request) is RoomsResult.Success)
        assertEquals(request, api.created)
        assertTrue(repo.join("ABC123") is RoomsResult.Success)
        assertEquals("ABC123", api.joined!!.code)
        val body = Gson().toJson(request)
        assertTrue(body.contains("\"group_target_probability\":0.9"))
        assertTrue(body.contains("\"client_uuid\":\"uuid\""))
        assertTrue(body.contains("\"start_at\""))
        assertFalse(body.contains("home_lat"))
    }
}
