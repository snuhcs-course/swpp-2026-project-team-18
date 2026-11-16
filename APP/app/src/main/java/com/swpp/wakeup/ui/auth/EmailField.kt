package com.swpp.wakeup.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.common.JitTextField
import com.swpp.wakeup.ui.theme.JitColor

// Partly AI-generated (Claude)
/**
 * 이메일 입력칸. 형식이 틀리면 빨간 테두리와 "이메일 형식이 아님" 한 줄을 보인다(Figma F-37 `505:14`).
 * 칸에 처음 들어와 입력하는 도중에는 보이지 않고, 칸을 벗어나거나 키보드의 완료 키를 누르면 보인다.
 * 앞 화면에서 가져와 손대지 않은 값은 바로 판정한다([emailFormatErrorVisible]). 막는 일은 이 칸이 아니라
 * 호출자의 버튼 조건이 한다(로그인은 막지 않는다 — 서버에 로그인 형식 규칙이 없다).
 */
@Composable
internal fun EmailField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    inputTag: String,
    errorTag: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
) {
    var hadFocus by remember { mutableStateOf(false) }
    var blurred by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val showError = emailFormatErrorVisible(value, focused = focused, leftOnce = blurred)

    Column(modifier = modifier) {
        JitTextField(
            label = label,
            value = value,
            onValueChange = onValueChange,
            keyboardType = KeyboardType.Email,
            inputTag = inputTag,
            enabled = enabled,
            imeAction = imeAction,
            // 완료 키는 포커스를 옮기지 않는다(칸이 하나뿐인 재설정 화면). 눌렀으면 다 썼다는 뜻이라 판정한다.
            // null 이면 그대로 둬서 기본 동작(다음 칸으로 이동 → 칸을 벗어남)을 지킨다.
            onImeAction = onImeAction?.let { action -> { blurred = true; action() } },
            isError = showError,
            onFocusChange = { now ->
                focused = now
                if (now) hadFocus = true else if (hadFocus) blurred = true
            },
        )
        if (showError) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "이메일 형식이 아님",
                modifier = Modifier.testTag(errorTag),
                color = JitColor.Red,
                fontSize = 11.sp,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}
