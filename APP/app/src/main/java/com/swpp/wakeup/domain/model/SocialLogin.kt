package com.swpp.wakeup.domain.model

import com.google.gson.JsonParser
import java.util.Base64

// Partly AI-generated (Claude)
/**
 * 소셜 로그인 제공자(docs/iter3/social-login-api.md). [path] 는 서버 주소의 마지막 칸이자
 * `user.login_methods` 의 값이다.
 */
enum class SocialProvider(
    val path: String,
    val label: String,
    /** 목적격 조사. "카카오를", "Google을"(구글을) */
    private val objectParticle: String,
) {
    KAKAO("kakao", "카카오", "를"),
    GOOGLE("google", "Google", "을"),
    ;

    /** "카카오를" / "Google을" */
    val asObject: String get() = label + objectParticle

    companion object {
        fun of(path: String?): SocialProvider? = entries.firstOrNull { it.path == path }
    }
}

/** 로그인 화면 오류 자리에 띄우는 카드(Figma ⑦ 변형 `496:9`). [amber] 면 강조 테두리를 두른다 */
data class SocialNotice(
    val title: String,
    val body: String,
    val footnote: String? = null,
    val amber: Boolean = false,
)

/** 소셜 로그인·연결에서 화면이 쓰는 문구와 작은 규칙. 순수 함수다(JVM 테스트 대상). */
object SocialLogin {

    /** 이메일을 주지 않은 소셜 계정의 자리 표시 주소. 화면에 보이지 않는다(계약 §5) */
    const val PLACEHOLDER_EMAIL_DOMAIN = "@social.invalid"

    /** 서버 닉네임 한도(계약 §2) */
    const val NICKNAME_MAX = 20

    const val METHOD_PASSWORD = "password"

    /** 화면에 보여도 되는 이메일. 자리 표시 주소·빈 값이면 null */
    fun visibleEmail(email: String?): String? =
        email?.trim()?.takeIf { it.isNotEmpty() && !it.endsWith(PLACEHOLDER_EMAIL_DOMAIN, ignoreCase = true) }

    /** `login_methods` 값을 사람이 읽는 이름으로. "password" 는 "이메일" 이다 */
    fun methodLabel(method: String): String = when (method) {
        METHOD_PASSWORD -> "이메일"
        else -> SocialProvider.of(method)?.label ?: method
    }

    /**
     * 409 `social_email_in_use`: 그 이메일로 가입한 계정이 이미 있다. 자동으로 합치지 않으므로 원래
     * 방법으로 로그인한 뒤 설정에서 연결하라고 안내한다.
     */
    fun emailInUseNotice(provider: SocialProvider, loginMethods: List<String>): SocialNotice {
        val methods = loginMethods.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val how = methods.joinToString("·") { methodLabel(it) }
        // 이메일·카카오·Google(구글) 모두 "로" 가 붙는다.
        val via = when {
            methods.isEmpty() -> "원래 로그인 방법으로"
            methods == listOf(METHOD_PASSWORD) -> "이메일·비밀번호로"
            else -> "${how}로"
        }
        return SocialNotice(
            title = "이 이메일은 이미 가입돼 있어요",
            body = "$via 로그인한 뒤 설정 › 로그인 연결에서 ${provider.asObject} 이어 주세요",
            footnote = if (methods.isEmpty()) "자동으로 합치지 않음" else "자동으로 합치지 않음. 원래 로그인 방법: $how",
            amber = true,
        )
    }

    /** 503: 서버에 그 제공자 키가 없거나, 이 빌드에 앱 키가 없다 */
    fun unavailableNotice(provider: SocialProvider): SocialNotice = SocialNotice(
        title = "지금은 ${provider.label} 로그인을 쓸 수 없어요",
        body = "이메일로 로그인해 주세요",
    )

    // Partly AI-generated (Claude)
    /**
     * ID 토큰(JWT) 본문의 닉네임. 처음 가입 화면의 닉네임 칸을 미리 채운다. 서명은 보지 않는다 —
     * 검증은 서버가 하고, 이 값은 사용자가 고칠 수 있는 제안일 뿐이다. 카카오는 `nickname`,
     * 구글은 `name` 이다. 읽지 못하면 null.
     */
    fun nicknameHint(idToken: String): String? = runCatching {
        val payload = idToken.split('.').getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
        val json = String(Base64.getUrlDecoder().decode(payload.padBase64()), Charsets.UTF_8)
        val claims = JsonParser.parseString(json).asJsonObject
        listOf("nickname", "name")
            .firstNotNullOfOrNull { key ->
                claims.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
            }
            ?.take(NICKNAME_MAX)
    }.getOrNull()

    private fun String.padBase64(): String = when (length % 4) {
        2 -> "$this=="
        3 -> "$this="
        else -> this
    }
}
