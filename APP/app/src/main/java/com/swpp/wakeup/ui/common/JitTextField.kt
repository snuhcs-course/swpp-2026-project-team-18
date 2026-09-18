package com.swpp.wakeup.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
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
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) JitColor.Accent else JitColor.Track
    val labelColor = if (focused) JitColor.Accent else JitColor.TextSecondary

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
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            textStyle = LocalTextStyle.current.merge(
                TextStyle(
                    color = JitColor.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(JitColor.Accent),
            visualTransformation =
                if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
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
    }
}

/** Figma 입력 필드 높이 */
private val FIELD_HEIGHT = 46.dp
