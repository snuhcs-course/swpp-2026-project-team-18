package com.swpp.wakeup.data.remote

import java.io.IOException

/**
 * refresh 시도의 결과. **토큰을 지울지 말지를 가른다.**
 *
 * ## 왜 구분해야 하는가
 *
 * 예전에는 `runCatching { ... }.getOrNull()` 로 감싸서 **실패를 한 종류로만**
 * 봤다. 그래서 refresh 왕복이 지하철에서 끊겨도 "refresh 가 만료됐다" 와 똑같이
 * 취급해 토큰을 지웠다. 사용자 입장에서는 **네트워크가 잠깐 나갔다고 로그아웃**
 * 되는 것이다. 아침 출근길에 쓰는 앱에서 이건 치명적이다.
 *
 * 구분 기준은 "서버가 판단을 내렸는가" 다.
 *
 * ```
 * 200 + access 있음   → 갱신됨.       새 토큰으로 재시도
 * 401 · 403 · 400     → 거절됐다.     토큰을 지우고 로그인 화면으로
 * 5xx · 그 외 코드     → 서버 문제다.  토큰 유지, 다음 요청에서 다시 시도
 * IOException         → 닿지 못했다.  토큰 유지
 * ```
 *
 * 애매한 경우를 **유지 쪽으로** 기울인 것이 의도다. 잘못 지우면 사용자가 다시
 * 로그인해야 하고, 잘못 유지하면 다음 요청이 401 을 한 번 더 받을 뿐이다. 비용이
 * 비대칭이다.
 */
internal sealed interface RefreshOutcome {

    /** 갱신 성공. */
    data class Renewed(val access: String) : RefreshOutcome

    /**
     * 서버가 refresh 를 거절했다. 다시 로그인해야 한다.
     *
     * `reason` 은 로그용이다. 사용자에게 그대로 보여주지 않는다.
     */
    data class Rejected(val reason: String) : RefreshOutcome

    /**
     * 판단할 수 없었다. **토큰을 지우지 않는다.**
     *
     * 네트워크 실패, 서버 5xx, 200 인데 본문이 비어 있는 경우.
     */
    data class Undecided(val reason: String) : RefreshOutcome

    companion object {
        /**
         * refresh 응답(또는 그때 던져진 예외)을 결과로 바꾼다.
         *
         * @param code HTTP 상태 코드. 응답을 못 받았으면 null.
         * @param access 응답 본문의 새 access 토큰. 없으면 null.
         * @param error 요청 중 던져진 예외. 없으면 null.
         */
        fun of(code: Int?, access: String?, error: Throwable? = null): RefreshOutcome {
            if (error != null) {
                // IOException 은 "닿지 못했다" 다. 그 외 예외(직렬화 오류 등)도
                // 서버의 판단이 아니므로 같게 다룬다 — 지우지 않는다.
                val kind = if (error is IOException) "네트워크" else error.javaClass.simpleName
                return Undecided("refresh 요청 실패($kind)")
            }
            if (code == null) return Undecided("응답이 없다")

            return when {
                code in 200..299 && !access.isNullOrBlank() -> Renewed(access)

                // 200 인데 토큰이 없다. 서버 계약이 깨진 것이라 재로그인으로
                // 몰 근거가 안 된다. 다음 요청에서 다시 시도한다.
                code in 200..299 -> Undecided("성공 응답에 access 가 없다")

                // **서버가 명시적으로 거절한 경우만 지운다.**
                code == 400 || code == 401 || code == 403 -> Rejected("refresh 거절($code)")

                // 5xx 와 그 밖의 코드는 서버 쪽 사정이다. 토큰은 멀쩡할 수 있다.
                else -> Undecided("refresh 응답 $code")
            }
        }
    }
}
