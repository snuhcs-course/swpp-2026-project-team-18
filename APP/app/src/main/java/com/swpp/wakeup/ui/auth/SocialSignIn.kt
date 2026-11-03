package com.swpp.wakeup.ui.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.kakao.sdk.auth.model.OAuthToken
import com.kakao.sdk.common.model.AuthError
import com.kakao.sdk.common.model.AuthErrorCause
import com.kakao.sdk.common.model.ClientError
import com.kakao.sdk.common.model.ClientErrorCause
import com.kakao.sdk.user.UserApiClient
import com.swpp.wakeup.BuildConfig
import com.swpp.wakeup.domain.model.SocialProvider
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

// Partly AI-generated (Claude)
/**
 * 카카오·구글 SDK 로 로그인해 **ID 토큰만** 받는다(docs/iter3/social-login-api.md §6). 받은 토큰은
 * 서버가 검증하고 JWT 를 준다([com.swpp.wakeup.data.repository.AuthRepository.socialLogin]).
 *
 * 둘 다 화면(Activity)이 있어야 한다 — 카카오톡 앱·브라우저·구글 계정 선택 창을 띄운다. 그래서
 * ViewModel 이 아니라 화면이 부르고 결과만 넘긴다.
 */
object SocialSignIn {

    private const val TAG = "SocialSignIn"

    sealed interface Outcome {
        data class Token(val idToken: String) : Outcome

        /** 사용자가 닫았다. 아무 말도 하지 않는다 */
        data object Cancelled : Outcome

        /** 이 빌드에 앱 키가 없다 */
        data object NotConfigured : Outcome

        data class Failed(val message: String) : Outcome
    }

    /** 이 빌드가 그 제공자 앱 키를 가졌는가 */
    fun isConfigured(provider: SocialProvider): Boolean = when (provider) {
        SocialProvider.KAKAO -> BuildConfig.KAKAO_NATIVE_APP_KEY.isNotBlank()
        SocialProvider.GOOGLE -> BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()
    }

    suspend fun idToken(activity: Activity, provider: SocialProvider): Outcome {
        if (!isConfigured(provider)) return Outcome.NotConfigured
        return when (provider) {
            SocialProvider.KAKAO -> kakao(activity)
            SocialProvider.GOOGLE -> google(activity)
        }
    }

    /**
     * 로그아웃 때 SDK 쪽 로그인도 끝낸다. 그래야 다음 로그인 때 계정 선택이 다시 뜬다(계약 §5).
     * 실패해도 앱 로그아웃은 이미 끝났다. 조용히 넘긴다.
     */
    suspend fun signOutQuietly(context: Context) {
        if (isConfigured(SocialProvider.KAKAO)) {
            runCatching { UserApiClient.instance.logout { error -> error?.let { Log.i(TAG, "카카오 로그아웃: $it") } } }
                .onFailure { Log.i(TAG, "카카오 로그아웃을 건너뛴다", it) }
        }
        if (isConfigured(SocialProvider.GOOGLE)) {
            runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
                .onFailure { Log.i(TAG, "Google 로그인 상태를 지우지 못했다", it) }
        }
    }

    // --- 카카오 -----------------------------------------------------------------

    /**
     * 카카오톡이 있으면 카카오톡으로, 없거나 실패하면 카카오계정(브라우저)으로. 카카오톡에서 사용자가
     * 취소했으면 계정 로그인으로 넘기지 않는다(카카오 권장 흐름).
     */
    private suspend fun kakao(activity: Activity): Outcome = suspendCancellableCoroutine { cont ->
        val client = runCatching { UserApiClient.instance }.getOrElse {
            cont.resume(Outcome.Failed("카카오 로그인을 준비하지 못했음"))
            return@suspendCancellableCoroutine
        }
        fun finish(outcome: Outcome) {
            if (cont.isActive) cont.resume(outcome)
        }
        fun viaAccount() = client.loginWithKakaoAccount(activity) { token, error -> finish(kakaoOutcome(token, error)) }

        if (client.isKakaoTalkLoginAvailable(activity)) {
            client.loginWithKakaoTalk(activity) { token, error ->
                when {
                    error != null && error.isKakaoCancel() -> finish(Outcome.Cancelled)
                    // 카카오톡에 연결된 계정이 없거나 오류. 카카오계정으로 다시 한다.
                    error != null -> viaAccount()
                    else -> finish(kakaoOutcome(token, null))
                }
            }
        } else {
            viaAccount()
        }
    }

    private fun kakaoOutcome(token: OAuthToken?, error: Throwable?): Outcome = when {
        error != null && error.isKakaoCancel() -> Outcome.Cancelled
        error != null -> {
            Log.w(TAG, "카카오 로그인 실패", error)
            Outcome.Failed("카카오 로그인에 실패함. ${error.message.orEmpty()}".trim())
        }
        // 콘솔의 OpenID Connect 가 꺼져 있으면 ID 토큰이 오지 않는다(계약 §6-1).
        token?.idToken.isNullOrBlank() -> Outcome.Failed("카카오가 ID 토큰을 주지 않았음. 카카오 콘솔의 OpenID Connect 를 켜야 함")
        else -> Outcome.Token(token!!.idToken!!)
    }

    private fun Throwable.isKakaoCancel(): Boolean =
        (this is ClientError && reason == ClientErrorCause.Cancelled) ||
            (this is AuthError && reason == AuthErrorCause.AccessDenied)

    // --- 구글 -------------------------------------------------------------------

    private suspend fun google(activity: Activity): Outcome = try {
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = CredentialManager.create(activity).getCredential(activity, request).credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            Outcome.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
        } else {
            Outcome.Failed("Google 계정 정보를 받지 못했음")
        }
    } catch (e: GetCredentialCancellationException) {
        Outcome.Cancelled
    } catch (e: NoCredentialException) {
        Outcome.Failed("이 기기에 Google 계정이 없음. 기기 설정에서 계정을 추가해야 함")
    } catch (e: GetCredentialException) {
        Log.w(TAG, "Google 로그인 실패: ${e.type}", e)
        Outcome.Failed("Google 로그인에 실패함. ${e.message.orEmpty()}".trim())
    } catch (e: GoogleIdTokenParsingException) {
        Log.w(TAG, "Google ID 토큰을 읽지 못했다", e)
        Outcome.Failed("Google 계정 정보를 읽지 못했음")
    }
}
