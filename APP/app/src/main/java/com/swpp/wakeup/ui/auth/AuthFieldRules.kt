// AI-generated (Claude) — fix/iter4-he-bugs 의 같은 이름 파일에서 이메일 칸 규칙만 옮겼다(11-19)
package com.swpp.wakeup.ui.auth

import com.swpp.wakeup.domain.model.EmailRule

/**
 * 이메일 형식 오류 줄을 보일지. 칸에 처음 들어와 입력하는 동안만 조용하다.
 *
 * 칸을 한 번 벗어났으면([leftOnce]) 판정한다. 지금 칸에 있지 않으면(`!focused`) 한 번도 들어온 적이 없어도 판정한다.
 * 로그인 화면에서 가져온 값처럼 손대지 않은 값은 칸을 벗어날 일이 없어서, 벗어남만 기준으로 삼으면 형식 때문에 버튼이
 * 꺼져 있는데 이유가 끝내 보이지 않았다. 빈 칸은 오류가 아니다.
 */
internal fun emailFormatErrorVisible(value: String, focused: Boolean, leftOnce: Boolean): Boolean =
    value.isNotBlank() && !EmailRule.isValid(value) && (leftOnce || !focused)

internal const val EMAIL_FORMAT_ERROR = "이메일 형식을 확인해 주세요"
