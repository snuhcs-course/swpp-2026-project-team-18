package com.swpp.wakeup.data.remote

// Partly AI-generated (Claude)

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [AppVersionInterceptor] — 서버의 API 요청 기록(db-schema.md A4)이 어느 빌드의 요청인지 안다.
 * 네트워크를 쓰지 않는다. 체인 끝의 가짜 인터셉터가 실제로 나간 요청을 받는다.
 */
class AppVersionInterceptorTest {

    private fun send(version: String): Request {
        var seen: Request? = null
        val terminal = Interceptor { chain ->
            seen = chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("ok")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }
        OkHttpClient.Builder()
            .addInterceptor(AppVersionInterceptor(version))
            .addInterceptor(terminal)
            .build()
            .newCall(Request.Builder().url("https://example.com/api/events").build())
            .execute()
            .close()
        return seen!!
    }

    @Test
    fun `요청마다 앱 버전을 붙인다`() {
        val label = AppVersionInterceptor.label("0.11.0", 11)
        assertEquals("0.11.0 (11)", label)
        assertEquals(label, send(label).header(AppVersionInterceptor.HEADER))
    }

    @Test
    fun `버전을 모르면 헤더를 붙이지 않는다`() {
        assertNull(send("  ").header(AppVersionInterceptor.HEADER))
    }
}
