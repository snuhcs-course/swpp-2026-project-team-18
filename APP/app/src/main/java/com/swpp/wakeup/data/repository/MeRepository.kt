package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.PasswordCheckResponse
import com.swpp.wakeup.domain.model.SocialProvider

/** Account settings operations. Server owns password rules, verification and linking permissions. */
interface MeRepository {
    suspend fun checkPassword(value: String): Result<PasswordCheckResponse>
    suspend fun changePassword(current: String, password: String, confirm: String): AuthRepository.PasswordChangeResult
    suspend fun requestEmailVerify(): AuthRepository.EmailVerifyResult
    suspend fun confirmEmailVerify(code: String): AuthRepository.EmailVerifyResult
    suspend fun currentUser(): AuthRepository.AccountResult
    suspend fun linkSocial(provider: SocialProvider, token: String): AuthRepository.AccountResult
    suspend fun unlinkSocial(provider: SocialProvider): AuthRepository.AccountResult
}

/** Adapter retains AuthRepository's token rotation/storage and server error mapping. */
class AuthMeRepository(private val auth: AuthRepository) : MeRepository {
    override suspend fun checkPassword(value: String) = auth.checkPassword(value)
    override suspend fun changePassword(current: String, password: String, confirm: String) =
        auth.changePassword(current, password, confirm)
    override suspend fun requestEmailVerify() = auth.requestEmailVerify()
    override suspend fun confirmEmailVerify(code: String) = auth.confirmEmailVerify(code)
    override suspend fun currentUser() = auth.currentUser()
    override suspend fun linkSocial(provider: SocialProvider, token: String) = auth.linkSocial(provider, token)
    override suspend fun unlinkSocial(provider: SocialProvider) = auth.unlinkSocial(provider)
}
