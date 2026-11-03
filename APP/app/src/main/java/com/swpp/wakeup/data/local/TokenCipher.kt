package com.swpp.wakeup.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 토큰 문자열을 암호화해 저장 형태로 바꾼다. [TokenStore] 가 쓴다.
 *
 * 저장 형태는 `v1:` 로 시작한다. 이 접두어가 없는 값은 암호화 전에 저장한 옛 평문이다.
 */
interface TokenCipher {
    /** @throws GeneralSecurityException 기기 보안 저장소를 쓸 수 없을 때 */
    fun encrypt(plain: String): String

    /** @throws GeneralSecurityException 키가 바뀌었거나 값이 깨졌을 때 */
    fun decrypt(stored: String): String

    /** 키를 지운다. 키가 망가졌을 때 새로 만들게 한다 */
    fun reset() {}

    companion object {
        const val PREFIX = "v1:"
    }
}

/**
 * 저장할 수 없을 때. 로그인한 상태를 남길 수 없으므로 화면이 다시 로그인하게 한다.
 */
class TokenStorageException(cause: Throwable) : Exception("토큰을 안전하게 저장하지 못했다", cause)

// Partly AI-generated (Claude)
/**
 * Android Keystore 의 AES-256/GCM 키로 암호화한다(todo §2 "토큰 암호화 저장").
 *
 * ## 왜 security-crypto 가 아닌가
 *
 * `androidx.security:security-crypto`(EncryptedSharedPreferences)는 1.1.0-alpha07 에서 모든
 * API 가 deprecated 됐고 2025-07 정식 1.1.0 도 그대로다. 새 의존성 없이 플랫폼 API(`AndroidKeyStore` +
 * `javax.crypto`)로 같은 일을 한다. 키는 기기 밖으로 나가지 않고(하드웨어 보안 모듈이 있으면
 * 그 안), 앱을 지우면 함께 사라진다. 사용자 인증(지문 등)은 요구하지 않는다 — 알람이
 * 잠금 화면에서 서버를 불러야 한다.
 *
 * 값마다 새 IV(12바이트)를 쓰고 GCM 태그(128비트)로 변조를 막는다.
 */
object KeystoreTokenCipher : TokenCipher {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "jit_token_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val b64 = Base64.getEncoder()
        return TokenCipher.PREFIX + b64.encodeToString(cipher.iv) + ":" + b64.encodeToString(sealed)
    }

    override fun decrypt(stored: String): String {
        if (!stored.startsWith(TokenCipher.PREFIX)) throw GeneralSecurityException("모르는 저장 형태")
        val parts = stored.removePrefix(TokenCipher.PREFIX).split(':')
        if (parts.size != 2) throw GeneralSecurityException("저장 형태가 깨졌다")
        val b64 = Base64.getDecoder()
        val iv = runCatching { b64.decode(parts[0]) }.getOrElse { throw GeneralSecurityException(it) }
        val sealed = runCatching { b64.decode(parts[1]) }.getOrElse { throw GeneralSecurityException(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }

    override fun reset() {
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
