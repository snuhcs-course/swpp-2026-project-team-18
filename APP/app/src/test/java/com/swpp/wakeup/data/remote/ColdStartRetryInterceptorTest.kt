package com.swpp.wakeup.data.remote

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ColdStartRetryInterceptor] 검증.
 *
 * 가장 중요한 것은 **쓰기 요청을 재시도하지 않는다**는 것이다. POST 를
 * 재시도하면 서버가 첫 요청을 이미 처리했는데 응답만 늦은 경우 같은 일정이
 * 두 번 생긴다. 이 동작이 깨지면 데이터가 조용히 중복된다.
 *
 * 네트워크를 쓰지 않는다. 가짜 인터셉터를 체인 끝에 두고 호출 횟수를 센다.
 */
class ColdStartRetryInterceptorTest {

    /** 백오프를 짧게 준다. 실제 값(1초·2초)으로 테스트하면 느리다. */
    private fun client(terminal: Interceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(ColdStartRetryInterceptor(maxAttempts = 3))
            .addInterceptor(terminal)
            .build()

    private fun get(url: String = "https://example.com/api/health") =
        Request.Builder().url(url).get().build()

    private fun post(url: String = "https://example.com/api/events") =
        Request.Builder()
            .url(url)
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

    private fun ok(request: Request, code: Int = 200): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("t")
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()

    // -----------------------------------------------------------------
    // 쓰기 요청은 재시도하지 않는다 — 이 테스트가 가장 중요하다
    // -----------------------------------------------------------------

    @Test
    fun `POST 는 타임아웃이 나도 한 번만 보낸다`() {
        val calls = AtomicInteger()
        val c = client { chain ->
            calls.incrementAndGet()
            throw SocketTimeoutException("timeout")
        }

        val thrown = runCatching { c.newCall(post()).execute() }.exceptionOrNull()

        assertTrue("IOException 이 올라와야 한다", thrown is IOException)
        assertEquals(
            "POST 를 재시도하면 같은 일정이 두 번 생긴다",
            1,
            calls.get(),
        )
    }

    @Test
    fun `POST 는 503 이어도 재시도하지 않는다`() {
        val calls = AtomicInteger()
        val c = client { chain ->
            calls.incrementAndGet()
            ok(chain.request(), 503)
        }

        val response = c.newCall(post()).execute()

        assertEquals(503, response.code)
        assertEquals(1, calls.get())
        response.close()
    }

    // -----------------------------------------------------------------
    // GET 은 재시도한다
    // -----------------------------------------------------------------

    @Test
    fun `GET 은 타임아웃 뒤 성공하면 결과를 돌려준다`() {
        val calls = AtomicInteger()
        val c = client { chain ->
            if (calls.incrementAndGet() < 3) throw SocketTimeoutException("waking")
            ok(chain.request())
        }

        val response = c.newCall(get()).execute()

        assertEquals(200, response.code)
        assertEquals("두 번 실패하고 세 번째에 성공해야 한다", 3, calls.get())
        response.close()
    }

    @Test
    fun `GET 은 503 을 받으면 재시도한다`() {
        val calls = AtomicInteger()
        val c = client { chain ->
            if (calls.incrementAndGet() == 1) ok(chain.request(), 503)
            else ok(chain.request(), 200)
        }

        val response = c.newCall(get()).execute()

        assertEquals(200, response.code)
        assertEquals(2, calls.get())
        response.close()
    }

    @Test
    fun `GET 재시도 한도를 넘으면 마지막 오류를 올린다`() {
        val calls = AtomicInteger()
        val c = client { _ ->
            calls.incrementAndGet()
            throw SocketTimeoutException("still waking")
        }

        val thrown = runCatching { c.newCall(get()).execute() }.exceptionOrNull()

        assertTrue(thrown is IOException)
        assertEquals("첫 시도 + 재시도 2회", 3, calls.get())
    }

    @Test
    fun `GET 이 바로 성공하면 재시도하지 않는다`() {
        val calls = AtomicInteger()
        val c = client { chain ->
            calls.incrementAndGet()
            ok(chain.request())
        }

        val response = c.newCall(get()).execute()

        assertEquals(200, response.code)
        assertEquals("정상 응답에 추가 호출이 붙으면 안 된다", 1, calls.get())
        response.close()
    }

    // -----------------------------------------------------------------
    // 4xx 는 우리 잘못이므로 재시도하지 않는다
    // -----------------------------------------------------------------

    @Test
    fun `GET 이 401 이면 재시도하지 않는다`() {
        val calls = AtomicInteger()
        val c = client { chain ->
            calls.incrementAndGet()
            ok(chain.request(), 401)
        }

        val response = c.newCall(get()).execute()

        assertEquals(401, response.code)
        assertEquals("인증 실패를 재시도해도 결과가 같다", 1, calls.get())
        response.close()
    }

    @Test
    fun `GET 이 404 이면 재시도하지 않는다`() {
        val calls = AtomicInteger()
        val c = client { chain ->
            calls.incrementAndGet()
            ok(chain.request(), 404)
        }

        val response = c.newCall(get()).execute()

        assertEquals(404, response.code)
        assertEquals(1, calls.get())
        response.close()
    }
}
