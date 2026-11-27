package com.swpp.wakeup.data.repository

import com.google.gson.Gson
import com.swpp.wakeup.data.remote.SavedPlaceDto
import com.swpp.wakeup.data.remote.SavedPlaceRename
import com.swpp.wakeup.testing.FakeSavedPlacesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class SavedHomeRenameTest {
    @Test fun `rename sends only trimmed label and uses confirmed server row`() = runTest {
        var sent: SavedPlaceRename? = null
        var sentId: Long? = null
        val repository = SavedPlacesRepository(object : FakeSavedPlacesApi() {
            override suspend fun rename(id: Long, body: SavedPlaceRename): Response<SavedPlaceDto> {
                sent = body; sentId = id
                return Response.success(SavedPlaceDto(id = id, clientUuid = "home1", label = "기숙사", name = "관악학생생활관", address = "관악로 1", lat = 37.46, lng = 126.95))
            }
        })
        val result = repository.rename(12, "  기숙사  ") as SavedPlacesResult.Ok
        assertEquals(12L, sentId)
        assertEquals("{\"label\":\"기숙사\"}", Gson().toJson(sent))
        assertEquals("기숙사", result.homes.single().label)
        assertEquals(37.46, result.homes.single().lat, 0.0)
        assertEquals(12L, result.homes.single().serverId)
    }

    @Test fun `deleted row is failure rather than unsupported local rename`() = runTest {
        val repository = SavedPlacesRepository(object : FakeSavedPlacesApi() {
            override suspend fun rename(id: Long, body: SavedPlaceRename): Response<SavedPlaceDto> = Response.error(404, "{}".toResponseBody())
        })
        val result = repository.rename(12, "기숙사") as SavedPlacesResult.Failure
        assertEquals(SavedPlacesRepository.CODE_NOT_FOUND, result.code)
    }

    @Test fun `rename cancellation is not reported as a failed save`() = runTest {
        val repository = SavedPlacesRepository(object : FakeSavedPlacesApi() {
            override suspend fun rename(id: Long, body: SavedPlaceRename): Response<SavedPlaceDto> = throw CancellationException("leaving screen")
        })
        try { repository.rename(12, "기숙사"); fail("cancellation must propagate") }
        catch (_: CancellationException) { }
    }
}
