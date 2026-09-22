package com.swpp.wakeup

import android.app.Application
import com.swpp.wakeup.background.JitWork
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

        // 정기 동기화를 예약한다. KEEP 정책이라 프로세스가 몇 번 깨어나도
        // 주기가 초기화되지 않는다 — UPDATE 면 앱을 자주 여는 사용자에게
        // 정기 실행이 영영 오지 않는다.
        //
        // 로그아웃 상태에서도 예약한다. 워커가 로그인 여부를 확인해 바로
        // 끝내고, 다시 로그인하면 별도 등록 없이 동기화가 이어진다.
        JitWork.ensurePeriodicSync(this)
    }
}
