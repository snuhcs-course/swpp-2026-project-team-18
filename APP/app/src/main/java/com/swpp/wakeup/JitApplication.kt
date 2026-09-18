package com.swpp.wakeup

import android.app.Application
import com.swpp.wakeup.data.local.TokenStore
import com.swpp.wakeup.data.remote.ApiClient

/**
 * 앱 진입점.
 *
 * [ApiClient] 가 [TokenStore] 를 필요로 하고 그건 Context 를 요구한다.
 * 액티비티마다 초기화하면 순서에 따라 초기화 전 접근이 생길 수 있어서
 * Application 에서 한 번만 한다.
 *
 * Hilt 를 넣으면 `@HiltAndroidApp` 이 붙고 이 초기화는 모듈로 옮겨진다.
 */
class JitApplication : Application() {

    /** 앱 전체가 공유하는 토큰 저장소. */
    lateinit var tokenStore: TokenStore
        private set

    override fun onCreate() {
        super.onCreate()
        tokenStore = TokenStore(this)
        ApiClient.init(tokenStore)
    }
}
