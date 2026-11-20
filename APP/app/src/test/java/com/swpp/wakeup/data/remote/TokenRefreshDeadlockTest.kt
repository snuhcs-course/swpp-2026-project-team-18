// Partly AI-generated (Claude)
package com.swpp.wakeup.data.remote

import com.swpp.wakeup.data.local.SessionState
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.testing.FakeTokenCipher
import com.swpp.wakeup.testing.InMemoryPrefs
import com.swpp.wakeup.testing.prefsContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * access 가 만료된 채 요청 5개 이상이 동시에 401 을 받아도 앱의 네트워크가 멈추지 않는다.
 *
 * 진짜 소켓으로 돌린다. 교착은 OkHttp 디스패처의 동시 실행 한도에서 생기므로 가짜 API 로는 재현되지 않는다.
 */
class TokenRefreshDeadlockTest {

    private val store = TokenStore(prefsContext(InMemoryPrefs()), FakeTokenCipher()).apply {
        save(access = EXPIRED, refresh = "refresh-token", email = "me@snu.ac.kr", nickname = "진호")
    }
    private var server: TokenServer? = null

    @After
    fun tearDown() {
        server?.close()
        SessionState.clearExpired()
    }

    @Test
    fun `요청 여덟 개가 동시에 401 을 받아도 갱신은 한 번만 하고 모두 성공한다`() {
        val server = start(refreshCode = 200)

        val codes = sendConcurrently(server, count = 8)

        assertEquals(List(8) { 200 }, codes)
        assertEquals(1, server.refreshCalls.get())
        assertEquals(FRESH, store.accessToken)
    }

    @Test
    fun `갱신이 거절되면 멈추지 않고 모두 401 로 끝나며 세션을 닫는다`() {
        val server = start(refreshCode = 401)

        val codes = sendConcurrently(server, count = 8)

        assertEquals(List(8) { 401 }, codes)
        assertEquals(1, server.refreshCalls.get())
        assertFalse(store.isLoggedIn)
        assertTrue(SessionState.expired.value)
    }

    @Test
    fun `갱신 클라이언트는 디스패처를 따로 쓰고 다시 갱신하지 않는다`() {
        val client = OkHttpClient.Builder()
            .authenticator(TokenRefreshAuthenticator(store) { error("부르지 않는다") })
            .build()

        val refresh = client.forTokenRefresh()

        assertNotSame(client.dispatcher, refresh.dispatcher)
        assertSame(Authenticator.NONE, refresh.authenticator)
    }

    private fun start(refreshCode: Int): TokenServer =
        TokenServer(firstWave = MAX_REQUESTS_PER_HOST, refreshCode = refreshCode).also { server = it }

    /** 앱과 같은 구성으로 요청을 한꺼번에 보내고 상태 코드를 모은다. 멈추면 시간 안에 끝나지 않아 실패한다 */
    private fun sendConcurrently(server: TokenServer, count: Int): List<Int> {
        lateinit var client: OkHttpClient
        val refreshApi by lazy {
            Retrofit.Builder()
                .baseUrl(server.url)
                .client(client.forTokenRefresh())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(AuthApi::class.java)
        }
        client = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(store))
            .authenticator(TokenRefreshAuthenticator(store) { refreshApi })
            .build()

        val finished = CountDownLatch(count)
        val codes = ConcurrentLinkedQueue<Int>()
        repeat(count) { index ->
            val request = Request.Builder().url("${server.url}api/events/$index").build()
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    finished.countDown()
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { codes.add(it.code) }
                    finished.countDown()
                }
            })
        }

        assertTrue("요청이 끝나지 않는다. 토큰 갱신이 교착했다", finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        return codes.toList()
    }

    /**
     * 만료된 토큰에 401 을 주는 서버. 첫 [firstWave] 개가 **모두 도착한 뒤에** 응답해 요청들이 동시에
     * 401 을 받게 한다. 그래야 디스패처의 실행 칸이 전부 찬 상태에서 갱신이 시작된다.
     */
    private class TokenServer(firstWave: Int, private val refreshCode: Int) : Closeable {
        private val socket = ServerSocket(0, 64, InetAddress.getLoopbackAddress())
        private val firstWaveArrived = CountDownLatch(firstWave)

        val refreshCalls = AtomicInteger()
        val url: String = "http://${socket.inetAddress.hostAddress}:${socket.localPort}/"

        init {
            thread(isDaemon = true) {
                runCatching {
                    while (true) {
                        val client = socket.accept()
                        thread(isDaemon = true) { client.use(::handle) }
                    }
                }
            }
        }

        private fun handle(client: Socket) {
            val reader = client.getInputStream().bufferedReader()
            val path = reader.readLine().orEmpty().split(" ").getOrElse(1) { "" }
            val headers = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            val bodyLength = headers.headerValue("content-length")?.toIntOrNull() ?: 0
            repeat(bodyLength) { reader.read() }

            val (code, body) = when {
                path.endsWith("/api/auth/token/refresh") -> {
                    refreshCalls.incrementAndGet()
                    refreshCode to if (refreshCode == 200) """{"access":"$FRESH"}""" else "{}"
                }

                headers.headerValue("authorization") == "Bearer $FRESH" -> 200 to "{}"

                else -> {
                    firstWaveArrived.countDown()
                    firstWaveArrived.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    401 to "{}"
                }
            }
            val response = "HTTP/1.1 $code Test\r\nContent-Type: application/json\r\n" +
                "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
            client.getOutputStream().apply {
                write(response.toByteArray())
                flush()
            }
        }

        private fun List<String>.headerValue(name: String): String? =
            firstOrNull { it.substringBefore(":").trim().equals(name, ignoreCase = true) }
                ?.substringAfter(":")?.trim()

        override fun close() = socket.close()
    }

    private companion object {
        const val EXPIRED = "expired-access"
        const val FRESH = "fresh-access"

        /** OkHttp `Dispatcher.maxRequestsPerHost` 기본값 */
        const val MAX_REQUESTS_PER_HOST = 5
        const val TIMEOUT_SECONDS = 15L
    }
}
