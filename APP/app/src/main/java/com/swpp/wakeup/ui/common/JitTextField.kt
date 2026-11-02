package com.swpp.wakeup.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius

// Partly AI-generated (Claude)
/**
 * Figma ⑦·⑩ 의 입력 필드.
 *
 * Material3 `OutlinedTextField` 를 쓰지 않는다. 그쪽은 최소 높이 56dp 와 고유한
 * 라벨 애니메이션·인디케이터를 강제해서, Figma 의 46dp / 라벨 상단 고정 형태를
 * 재현하려면 오히려 더 많은 우회가 필요하다. [BasicTextField] 로 직접 그린다.
 *
 * 라벨은 항상 위에 붙어 있다(플로팅하지 않는다). 값이 비어 있어도 무엇을 넣는
 * 칸인지 보이는 편이 폼 4개를 연달아 채울 때 덜 헷갈린다.
 */
@Composable
fun JitTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
    /** 키보드의 확인·검색 키를 눌렀을 때. null 이면 기본 동작(포커스 이동) */
    onImeAction: (() -> Unit)? = null,
    /** 입력칸 오른쪽에 붙는 표시(예: 비밀번호 확인 일치 ✓). null 이면 없음 */
    trailing: (@Composable () -> Unit)? = null,
    inputTag: String? = null,
    /** 오른쪽 끝에 "보기"/"숨김" 글자 버튼을 붙인다([isPassword] 와 함께 쓴다). 기본은 가려 둔다 */
    passwordToggle: Boolean = false,
    /** 포커스를 얻거나 잃을 때 부른다. 이메일 형식 오류를 칸을 벗어난 뒤에 보이게 하는 데 쓴다 */
    onFocusChange: ((Boolean) -> Unit)? = null,
    /** 오류 상태. 테두리와 라벨이 빨개진다(Figma F-37 `505:14`). 오류 문구는 호출자가 칸 아래에 그린다 */
    isError: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    val masked = isPassword && !(passwordToggle && revealed)
    val borderColor = when {
        isError -> JitColor.Red
        focused -> JitColor.Accent
        else -> JitColor.Track
    }
    val labelColor = when {
        isError -> JitColor.Red
        focused -> JitColor.Accent
        else -> JitColor.TextSecondary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FIELD_HEIGHT)
            .clip(RoundedCornerShape(JitRadius.Button))
            .background(JitColor.Surface)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(JitRadius.Button)
            )
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            color = labelColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                modifier = Modifier
                    .then(if (inputTag != null) Modifier.testTag(inputTag) else Modifier)
                    .weight(1f)
                    .onFocusChanged {
                        focused = it.isFocused
                        onFocusChange?.invoke(it.isFocused)
                    },
                textStyle = LocalTextStyle.current.merge(
                    TextStyle(
                        color = JitColor.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(JitColor.Accent),
                visualTransformation =
                    if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction
                ),
                keyboardActions = if (onImeAction == null) {
                    KeyboardActions.Default
                } else {
                    KeyboardActions(
                        onSearch = { onImeAction() },
                        onDone = { onImeAction() },
                        onGo = { onImeAction() },
                    )
                },
            )
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (isPassword && passwordToggle) {
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 40.dp, minHeight = 40.dp)
                        .then(if (inputTag != null) Modifier.testTag("${inputTag}_toggle") else Modifier)
                        .semantics { contentDescription = if (revealed) "비밀번호 숨기기" else "비밀번호 보기" }
                        .clickable(enabled = enabled) { revealed = !revealed },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (revealed) "숨김" else "보기",
                        color = JitColor.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** Figma 입력 필드 높이 */
private val FIELD_HEIGHT = 46.dp
