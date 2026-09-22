package com.swpp.wakeup.data.local

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 세션이 끝났다는 사실을 화면에 알린다.
 *
 * ## 왜 필요한가
 *
 * refresh 토큰이 만료되면 [com.swpp.wakeup.data.remote.TokenRefreshAuthenticator]
 * 가 토큰을 지운다. 그런데 그 일은 **OkHttp 워커 스레드에서 조용히** 일어나서
 * 화면은 아무것도 모른다. 사용자는 홈에 그대로 남아 "다시 로그인해야 한다" 라는
 * 오류만 반복해서 보고, 로그인 화면으로 가려면 계정 메뉴를 직접 열어야 한다.
 *
 * 그 구멍이 지금까지 증상으로 드러나지 않은 이유가 있다. 앱을 다시 열면 **어차피
 * 로그인 화면이 떴기 때문**이다. 로그인 유지를 넣는 순간 이 구멍이 바로 보이게
 * 되므로 함께 막는다.
 *
 * ## 왜 전역 객체인가
 *
 * 만드는 쪽(OkHttp authenticator)과 읽는 쪽(액티비티)이 서로를 모른다.
 * `ApiClient`·`ServerWarmup`·`JitWork` 와 같은 방식을 따른다. 상태가 `Boolean`
 * 하나이고 프로세스 전체에서 하나뿐이라 주입할 이유가 없다.
 *
 * ## 무엇을 하지 않는가
 *
 * **토큰을 지우지 않는다.** 지우는 것은 authenticator 의 책임이고 여기는 알림만
 * 한다. 둘을 한 곳에 두면 "화면에 알리려고 토큰을 지운다" 같은 순서 의존이 생긴다.
 */
object SessionState {

    private const val TAG = "SessionState"

    private val _expired = MutableStateFlow(false)

    /**
     * 서버가 refresh 를 거절해 다시 로그인이 필요한가.
     *
     * 네트워크 실패로는 켜지지 않는다 — 그 판정은
     * [com.swpp.wakeup.data.remote.RefreshOutcome] 이 한다.
     */
    val expired: StateFlow<Boolean> = _expired.asStateFlow()

    /** authenticator 가 refresh 거절을 확인했을 때 부른다. */
    fun markExpired(reason: String) {
        if (_expired.value) return
        Log.i(TAG, "세션이 끝났다: $reason")
        _expired.value = true
    }

    /**
     * 로그인 화면으로 보낸 뒤, 또는 새로 로그인한 뒤에 내린다.
     *
     * 내리지 않으면 다음 로그인 직후에 다시 로그인 화면으로 튕긴다.
     */
    fun clearExpired() {
        _expired.value = false
    }
}
