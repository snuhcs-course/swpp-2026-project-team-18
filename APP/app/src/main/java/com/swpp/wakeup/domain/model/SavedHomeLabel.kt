// Partly AI-generated (Claude)
package com.swpp.wakeup.domain.model

import java.text.Normalizer

/**
 * 저장한 집 이름 규칙(BE-H9, 서버 계약 `docs/iter4/server-saved-place-labels-and-report-tone.md` 1절). 서버와 같은
 * 비교 키로 저장 전에 먼저 막는다 — 서버가 마지막 판정이고, 조금 달라도 409 를 이름 칸에 보이면 된다.
 */
object SavedHomeLabel {

    /** 서버 `details.label` 문구와 같다 */
    enum class Problem(val message: String) {
        EMPTY("이름을 입력해 주세요."),
        RESERVED("'집'은 기본 집 이름이라 쓸 수 없어요. 다른 이름을 써 주세요."),
        DUPLICATE("같은 이름의 집이 이미 있어요. 다른 이름을 써 주세요."),
    }

    private val RESERVED_KEY = key("집")

    /**
     * 비교 키: NFKC → 서식 문자(Cf) 빼기 → 앞뒤 공백 빼고 안의 공백(탭 포함)은 하나로 → 대소문자 무시.
     * "본가2" 와 "본가 2" 는 다르다(공백을 없애지는 않는다).
     */
    fun key(label: String): String =
        Normalizer.normalize(label, Normalizer.Form.NFKC)
            .filterNot { Character.getType(it) == Character.FORMAT.toInt() }
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .lowercase()

    /** 저장 전 검사. [others] 는 이 사용자의 다른 저장한 집 이름. 문제가 없으면 null */
    fun problem(label: String, others: List<String>): Problem? {
        val k = key(label)
        return when {
            k.isEmpty() -> Problem.EMPTY
            k == RESERVED_KEY -> Problem.RESERVED
            others.any { key(it) == k } -> Problem.DUPLICATE
            else -> null
        }
    }

    /**
     * `bulk` 가 겹치는 이름에 번호를 붙여 저장했을 때 한 번 보이는 안내. "같은 이름이 있어 '본가 2'로 바꿨어요".
     * 여러 곳이면 "같은 이름이 있어 2곳의 이름에 번호를 붙였어요". 없으면 null.
     */
    fun renamedNotice(renamed: List<String>): String? = when {
        renamed.isEmpty() -> null
        renamed.size == 1 -> renamed[0].let { "같은 이름이 있어 '$it'${RerouteAlert.roParticle(it)} 바꿨어요" }
        else -> "같은 이름이 있어 ${renamed.size}곳의 이름에 번호를 붙였어요"
    }
}
