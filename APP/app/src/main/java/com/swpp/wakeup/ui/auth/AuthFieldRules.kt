// AI-generated (Claude)
package com.swpp.wakeup.ui.auth

import com.swpp.wakeup.domain.model.EmailRule
import com.swpp.wakeup.domain.model.SocialLogin
import com.swpp.wakeup.domain.model.codePointLength

/*
 * 가입·로그인 입력칸 아래 오류 줄을 언제, 무엇으로 보일지 정하는 순수 함수.
 *
 * 칸의 포커스 상태는 컴포저블([EmailField], 가입 화면의 닉네임 칸)이 갖고 판정만 여기로 뺐다.
 * AuthViewModel 은 Application 이 있어야 만들 수 있어 JVM 에서 시험하지 못한다. `AuthFieldRulesTest` 가 본다.
 */

/**
 * 이메일 형식 오류 줄을 보일지. 칸에 처음 들어와 입력하는 동안만 조용하다.
 *
 * 칸을 한 번 벗어났거나 키보드의 완료 키를 눌렀으면([leftOnce]) 판정한다. 지금 칸에 있지 않으면(`!focused`)
 * 한 번도 들어온 적이 없어도 판정한다. 앞 화면에서 가져온 값처럼 손대지 않은 값은 칸을 벗어날 일이 없어서,
 * 벗어남만 기준으로 삼으면 형식 때문에 버튼이 꺼져 있는데 이유가 끝내 보이지 않는다. 빈 칸은 오류가 아니다.
 */
internal fun emailFormatErrorVisible(value: String, focused: Boolean, leftOnce: Boolean): Boolean =
    value.isNotBlank() && !EmailRule.isValid(value) && (leftOnce || !focused)

/** 닉네임 글자 수. 서버(`strip` 뒤 Python `len`)처럼 앞뒤 공백을 자르고 코드 포인트로 센다 */
internal fun nicknameLength(nickname: String): Int = nickname.trim().codePointLength()

/** 서버 한도(이메일 가입·소셜 가입 모두 20자)를 넘는다. 서버가 거절할 값이라 가입 버튼을 끈다 */
internal fun isNicknameTooLong(nickname: String): Boolean = nicknameLength(nickname) > SocialLogin.NICKNAME_MAX

/**
 * 닉네임 칸 아래 오류 줄. 없으면 null.
 *
 * 한도를 넘으면 입력하는 도중에도 바로 알린다 — 더 쓰기 전에 알아야 한다. 서버가 닉네임을 거절했으면 그 문구를
 * 그대로 쓴다. 비어 있다는 말은 칸을 한 번 벗어난 뒤([leftOnce])에만 한다. 가입 화면을 열자마자 빨간 줄이 뜨지
 * 않게 하려는 것이고, 이메일 칸과 같은 규칙이다.
 */
internal fun nicknameError(nickname: String, serverError: String?, leftOnce: Boolean): String? = when {
    isNicknameTooLong(nickname) -> "닉네임은 ${SocialLogin.NICKNAME_MAX}자까지 쓸 수 있어요"
    !serverError.isNullOrBlank() -> serverError
    leftOnce && nickname.isBlank() -> "닉네임을 입력해 주세요"
    else -> null
}

/** 한도를 넘었을 때 오류 줄 오른쪽에 두는 글자 수(예 "23/20"). 넘지 않았으면 null */
internal fun nicknameOverLimitCount(nickname: String): String? =
    nicknameLength(nickname).takeIf { it > SocialLogin.NICKNAME_MAX }?.let { "$it/${SocialLogin.NICKNAME_MAX}" }
