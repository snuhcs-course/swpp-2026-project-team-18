// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.NicknameRule
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.theme.JitColor

/**
 * 가입 닉네임 칸(iter4 H2-11, Figma `549:2029`). 칸 아래 한 줄: 왼쪽 안내 · 오른쪽 글자 수.
 *
 * - 평소: 회색 "다른 사람에게 보이는 이름이에요 · 20자까지" · 회색 "7/20"
 * - 20자 넘음(치는 동안 바로): 빨간 테두리 · 라벨 · 느낌표, "닉네임은 20자까지 쓸 수 있어요" · 빨간 "23/20"
 * - 비었음(칸을 벗어난 뒤): 같은 빨간 표시, "닉네임을 입력해 주세요" · 회색 "0/20"
 * - 서버가 닉네임을 거절함([serverError]): 그 이유. 닉네임을 고치면 사라진다
 *
 * 글자를 20자에서 자르지 않는다. 붙여 넣은 긴 이름이 말없이 잘리면 무엇이 빠졌는지 모른다.
 */
@Composable
internal fun NicknameField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    serverError: String? = null,
) {
    var hadFocus by remember { mutableStateOf(false) }
    var blurred by remember { mutableStateOf(false) }
    val error = NicknameRule.error(value, blurred, serverError)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        JitTextField(
            label = label,
            inputTag = "signup_nickname",
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            isError = error != null,
            onFocusChange = { focused ->
                if (focused) hadFocus = true else if (hadFocus) blurred = true
            },
            trailing = if (error != null) {
                { Icon(LineIcons.alert, contentDescription = "오류", tint = JitColor.Red, modifier = Modifier.size(16.dp)) }
            } else {
                null
            },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = error ?: NicknameRule.HINT,
                color = if (error != null) JitColor.Red else JitColor.TextSecondary,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                modifier = Modifier
                    .weight(1f)
                    .testTag(if (error != null) "signup_nickname_error" else "signup_nickname_hint"),
            )
            Text(
                text = NicknameRule.counter(value),
                color = if (NicknameRule.tooLong(value)) JitColor.Red else JitColor.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag("signup_nickname_count"),
            )
        }
    }
}

/** 가입 요청이 거절됐을 때의 빨간 카드(Figma `549:2094`, surface-danger · 둥근 12 · 느낌표). 버튼 바로 위 */
@Composable
internal fun SignupErrorCard(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(JitColor.SurfaceDanger)
            .testTag("signup_error")
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LineIcons.alert, contentDescription = null, tint = JitColor.Red, modifier = Modifier.size(16.dp))
        Text(text = message, color = JitColor.TextPrimary, fontSize = 12.sp, lineHeight = 14.sp)
    }
}
