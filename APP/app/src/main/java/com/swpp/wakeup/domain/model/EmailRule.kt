package com.swpp.wakeup.domain.model

import java.net.Inet6Address
import java.net.InetAddress

// Partly AI-generated (Claude)
/**
 * 이메일 형식 검사. 서버(Django 5.2 `EmailValidator`)와 같은 규칙을 옮겼다.
 *
 * 앞뒤 공백을 자른 뒤, 320자 이하이고 마지막 `@` 로 나눈 앞부분(dot-atom 또는 따옴표 문자열)과
 * 뒷부분(도메인, `localhost`, `[IPv4]`/`[IPv6]`)이 모두 맞아야 한다.
 *
 * IPv6 리터럴은 `java.net` 이 읽을 수 있으면 통과시켜 Django 보다 아주 조금 느슨하다.
 * 그 외에는 서버와 같다. 이 검사는 입력 도중의 안내용이고, 최종 판단은 서버가 한다.
 */
object EmailRule {

    private const val MAX_LENGTH = 320

    private val userDotAtom = Regex(
        """^[-!#${'$'}%&'*+/=?^_`{}|~0-9A-Z]+(\.[-!#${'$'}%&'*+/=?^_`{}|~0-9A-Z]+)*\z""",
        RegexOption.IGNORE_CASE,
    )

    private val userQuoted = Regex(
        """^"([\u0001-\u0008\u000b\u000c\u000e-\u001f!#-\[\]-\u007f]|\\[\u0001-\u0009\u000b\u000c\u000e-\u007f])*"\z""",
        RegexOption.IGNORE_CASE,
    )

    private val domain = Regex(
        """^[a-z\u00a1-\uffff0-9](?:[a-z\u00a1-\uffff0-9-]{0,61}[a-z\u00a1-\uffff0-9])?""" +
            """(?:\.(?!-)[a-z\u00a1-\uffff0-9-]{1,63}(?<!-))*""" +
            """\.(?!-)(?:[a-z\u00a1-\uffff-]{2,63}|xn--[a-z0-9]{1,59})(?<!-)\z""",
        RegexOption.IGNORE_CASE,
    )

    private val literal = Regex("""^\[([A-F0-9:.]+)]\z""", RegexOption.IGNORE_CASE)

    fun isValid(raw: String): Boolean {
        val value = raw.trim()
        if (value.isEmpty() || value.length > MAX_LENGTH) return false
        val at = value.lastIndexOf('@')
        if (at < 0) return false
        val user = value.substring(0, at)
        val host = value.substring(at + 1)
        if (!userDotAtom.containsMatchIn(user) && !userQuoted.containsMatchIn(user)) return false
        if (host == "localhost") return true
        if (domain.containsMatchIn(host)) return true
        val inner = literal.find(host)?.groupValues?.get(1) ?: return false
        return isIpv4(inner) || isIpv6(inner)
    }

    /** 점으로 나뉜 네 수, 각 0~255, 앞자리 0 없음 */
    private fun isIpv4(text: String): Boolean {
        val parts = text.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                (part.length == 1 || part[0] != '0') && part.toInt() <= 255
        }
    }

    private fun isIpv6(text: String): Boolean {
        if (!text.contains(':')) return false
        // 숫자 리터럴만 넘기므로 DNS 조회는 일어나지 않는다.
        return runCatching { InetAddress.getByName(text) is Inet6Address }.getOrDefault(false)
    }
}
