// Partly AI-generated (Claude)
package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.SavedPlaceDto
import com.swpp.wakeup.data.remote.SavedPlaceInput
import com.swpp.wakeup.data.remote.SavedPlacesBulkRequest
import com.swpp.wakeup.data.remote.SavedPlacesListDto
import com.swpp.wakeup.domain.model.SavedHome
import com.swpp.wakeup.testing.FakeSavedPlacesApi
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/** 저장한 집 서버 저장소(B2): 결과 매핑, 404·405 = 이전 서버, 오류 코드. */
class SavedPlacesRepositoryTest {
    private val device = SavedHome("u-1", "본가", "인계동", null, 37.26, 127.03)
    private val listJson = """{"default":null,"results":[{"id":5,"client_uuid":"u-1","label":"본가","name":"인계동","lat":37.26,"lng":127.03}]}"""

    private fun err(code: String, message: String = "m") =
        """{"error":{"code":"$code","message":"$message","details":{}}}"""

    @Test
    fun `목록은 results 를 앱 모델로 바꾼다`() = runTest {
        val api = object : FakeSavedPlacesApi() {
            override suspend fun list() = ok(fromJson<SavedPlacesListDto>(listJson))
        }
        val result = SavedPlacesRepository(api).list() as SavedPlacesResult.Ok
        assertEquals(listOf(5L), result.homes.map { it.serverId })
        assertEquals("u-1", result.homes.single().id)
    }

    @Test
    fun `bulk 200 에서 한도 skip 하나와 기본 집 skip 하나는 skippedOverLimit 1 이다`() = runTest {
        var sent: SavedPlacesBulkRequest? = null
        val api = object : FakeSavedPlacesApi() {
            override suspend fun bulk(body: SavedPlacesBulkRequest): Response<SavedPlacesListDto> {
                sent = body
                return ok(
                    fromJson(
                        """{"default":null,"results":[],"skipped":[
                            {"client_uuid":"u-1","reason":"limit"},{"client_uuid":"u-2","reason":"same_as_default"}]}""",
                    ),
                )
            }
        }
        val result = SavedPlacesRepository(api).bulk(listOf(device)) as SavedPlacesResult.Ok
        assertEquals(1, result.skippedOverLimit)
        assertEquals("u-1", sent!!.items.single().clientUuid)
    }

    @Test
    fun `404 와 405 는 이전 서버라 Unsupported 다`() = runTest {
        for (code in listOf(404, 405)) {
            val api = object : FakeSavedPlacesApi() {
                override suspend fun list() = httpError<SavedPlacesListDto>(code)
                override suspend fun bulk(body: SavedPlacesBulkRequest) = httpError<SavedPlacesListDto>(code)
                override suspend fun create(body: SavedPlaceInput) = httpError<SavedPlaceDto>(code)
            }
            val repo = SavedPlacesRepository(api)
            assertEquals("list $code", SavedPlacesResult.Unsupported, repo.list())
            assertEquals("bulk $code", SavedPlacesResult.Unsupported, repo.bulk(listOf(device)))
            assertEquals("create $code", SavedPlacesResult.Unsupported, repo.create(SavedPlaceInput("a", 37.0, 127.0)))
        }
    }

    @Test
    fun `통신이 끊기면 network 실패다`() = runTest {
        val api = object : FakeSavedPlacesApi() {
            override suspend fun bulk(body: SavedPlacesBulkRequest): Response<SavedPlacesListDto> = throw IOException("offline")
        }
        val result = SavedPlacesRepository(api).bulk(listOf(device)) as SavedPlacesResult.Failure
        assertTrue(result.network)
    }

    @Test
    fun `추가의 409 코드는 code 로 구분하고 201 과 200 은 성공이다`() = runTest {
        for ((status, code) in listOf(409 to "same_as_default", 409 to "saved_place_limit")) {
            val api = object : FakeSavedPlacesApi() {
                override suspend fun create(body: SavedPlaceInput) = httpError<SavedPlaceDto>(status, err(code))
            }
            val result = SavedPlacesRepository(api).create(SavedPlaceInput("a", 37.0, 127.0)) as SavedPlacesResult.Failure
            assertEquals(code, result.code)
            assertEquals(409, result.status)
        }
        for (status in listOf(201, 200)) {
            val api = object : FakeSavedPlacesApi() {
                override suspend fun create(body: SavedPlaceInput) =
                    Response.success(status, SavedPlaceDto(5, "u", "본가", "인계동", null, 37.26, 127.03))
            }
            val ok = SavedPlacesRepository(api).create(SavedPlaceInput("본가", 37.26, 127.03)) as SavedPlacesResult.Ok
            assertEquals("HTTP $status", 5L, ok.homes.single().serverId)
        }
    }

    @Test
    fun `추가 요청 본문은 client_uuid 와 좌표를 싣는다`() = runTest {
        var sent: SavedPlaceInput? = null
        val api = object : FakeSavedPlacesApi() {
            override suspend fun create(body: SavedPlaceInput): Response<SavedPlaceDto> {
                sent = body
                return Response.success(201, SavedPlaceDto(1, "u", "본가", "x", null, 37.2, 127.0))
            }
        }
        SavedPlacesRepository(api).create(SavedPlaceInput("본가", 37.2, 127.0, name = "x", clientUuid = "u"))
        val json = Gson().toJson(sent)
        assertTrue(json, json.contains("\"client_uuid\":\"u\""))
        assertTrue(json, json.contains("\"lat\":37.2"))
    }

    @Test
    fun `삭제는 204 와 이미 없는 404 모두 성공이다`() = runTest {
        for (status in listOf(204, 404)) {
            val api = object : FakeSavedPlacesApi() {
                override suspend fun delete(id: Long): Response<Unit> =
                    if (status == 204) Response.success(204, Unit) else httpError(404)
            }
            assertTrue("HTTP $status", SavedPlacesRepository(api).delete(5) is SavedPlacesResult.Ok)
        }
        val api = object : FakeSavedPlacesApi() {
            override suspend fun delete(id: Long) = httpError<Unit>(500)
        }
        assertTrue(SavedPlacesRepository(api).delete(5) is SavedPlacesResult.Failure)
    }

    @Test
    fun `기본으로는 200 에서 전체 목록을 돌려주고 404 는 not_found 다`() = runTest {
        val okApi = object : FakeSavedPlacesApi() {
            override suspend fun makeDefault(id: Long) = ok(fromJson<SavedPlacesListDto>(listJson))
        }
        val ok = SavedPlacesRepository(okApi).makeDefault(5) as SavedPlacesResult.Ok
        assertEquals(1, ok.homes.size)
        val goneApi = object : FakeSavedPlacesApi() {
            override suspend fun makeDefault(id: Long) = httpError<SavedPlacesListDto>(404)
        }
        val gone = SavedPlacesRepository(goneApi).makeDefault(5) as SavedPlacesResult.Failure
        assertEquals(SavedPlacesRepository.CODE_NOT_FOUND, gone.code)
    }

    @Test
    fun `추가 실패 문구는 코드와 통신 여부로 고른다`() {
        fun f(code: String? = null, status: Int? = null, network: Boolean = false, fields: Map<String, String> = emptyMap()) =
            SavedPlacesResult.Failure("서버 문구", code = code, status = status, fields = fields, network = network)
        assertEquals("기본 집과 같은 곳이에요(50m 안). 다른 곳을 골라 주세요", savedPlaceAddMessage(f("same_as_default", 409)))
        assertEquals("다른 집은 5곳까지 저장할 수 있어요", savedPlaceAddMessage(f("saved_place_limit", 409)))
        assertEquals("서버에 연결하지 못해 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요", savedPlaceAddMessage(f(network = true)))
        assertEquals("한국 안의 좌표만", savedPlaceAddMessage(f("invalid", 400, fields = mapOf("lat" to "한국 안의 좌표만"))))
        assertEquals("서버 문구", savedPlaceAddMessage(f("invalid", 400)))
        assertEquals("서버 문구", savedPlaceAddMessage(f(status = 500)))
    }}
