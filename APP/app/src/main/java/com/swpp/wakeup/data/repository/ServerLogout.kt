package com.swpp.wakeup.data.repository

import android.util.Log
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.AuthApi
import com.swpp.wakeup.data.remote.RefreshRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Partly AI-generated (Claude)
/**
 * 로그아웃한 refresh 를 서버 취소 목록에 넣는다(docs/iter3/token-security-api.md §5).
 *
 * 기기의 토큰은 **결과와 상관없이** 먼저 지운다(호출부). 서버 호출은 화면 수명과 떼어 둔다 —
 * 로그아웃하면 액티비티가 끝나 `viewModelScope` 가 취소되고, 무료 서버가 잠들어 있으면 응답에
 * 수십 초가 걸린다. 사용자를 기다리게 하지 않는다. 닿지 못하면 그 refresh 는 만료(14일)까지
 * 남지만 이 기기에는 더 이상 없다.
 */
object ServerLogout {

    private const val TAG = "ServerLogout"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun revoke(refresh: String?, api: () -> AuthApi = { ApiClient.auth }) {
        if (refresh.isNullOrBlank() || !ApiClient.isReady) return
        scope.launch {
            runCatching { api().logout(RefreshRequest(refresh)) }
                .onSuccess { Log.i(TAG, "서버 로그아웃 응답 ${it.code()}") }
                .onFailure { Log.w(TAG, "서버 로그아웃 실패(${it.javaClass.simpleName}). 기기 토큰은 이미 지웠다") }
        }
    }
}
