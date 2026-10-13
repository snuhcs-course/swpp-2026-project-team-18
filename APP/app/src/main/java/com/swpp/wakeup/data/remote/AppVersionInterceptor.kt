package com.swpp.wakeup.data.remote

// Partly AI-generated (Claude)

import okhttp3.Interceptor
import okhttp3.Response

/**
 * 우리 서버로 가는 요청에 `X-App-Version` 을 붙인다(db-schema.md A4).
 *
 * 서버의 API 요청 기록이 어느 빌드가 보낸 요청인지 남긴다. 구버전 앱과 새 앱이 섞여 도는
 * 동안 "이 400 은 어느 버전에서 나왔나" 를 가를 수 있다. **버전 문자열만 보낸다** — 기기·
 * 사용자 정보는 넣지 않는다. 이 클라이언트는 우리 서버만 부른다(외부 API 는 서버가 부른다).
 */
class AppVersionInterceptor(private val version: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (version.isBlank()) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(HEADER, version).build())
    }

    companion object {
        const val HEADER = "X-App-Version"

        /** 예: `0.11.0 (11)`. 서버는 영숫자와 `.+-_ ()` 만 남기고 32자로 자른다. */
        fun label(versionName: String, versionCode: Int): String = "$versionName ($versionCode)"
    }
}
