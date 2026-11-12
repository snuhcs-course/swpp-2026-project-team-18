// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.theme.JitColor
import java.time.Instant
import java.time.ZoneId

/**
 * "정시 도착 확률이 뭐예요?" 시트에 넣을 값. [percent] 는 화면에 보이는 확률 그대로(정수 %)다.
 *
 * @property alarmLabel "오전 7:40"
 * @property startLabel 일정 시작. "오전 9:00"
 */
internal data class OnTimeSheetSpec(val percent: Int, val alarmLabel: String, val startLabel: String)

/**
 * i 시트(iter4 H2-4 · H2-3, Figma `548:1798`)의 문구. 계산 방식 · 내부 용어는 쓰지 않는다(캡션 `549:1844`).
 * JVM 테스트로 고정한다(`HelpFaqTest`).
 */
internal object OnTimeSheetText {

    const val TITLE = "정시 도착 확률이 뭐예요?"
    const val HOW_TITLE = "이렇게 정해요"
    val HOW = listOf(
        "준비·이동·여유 시간을 내 지난 기록과 견줘서 정해요",
        "기록이 쌓일수록 더 정확해져요",
        "리포트 탭에서 실제로 맞았는지 볼 수 있어요",
    )
    const val RAISE_TITLE = "확률을 높이려면"
    const val RAISE_BODY = "알람을 앞당기거나 ‘여유 있게’를 고르면 돼요"
    const val CHANGE_TIME = "알람 시각 바꾸기"
    const val OK = "알겠어요"

    /**
     * 원 10개 중 칠할 수(= "10번 중 n번"). 확률을 10% 단위로 **내린다** — 97% 는 9번이다. 반올림하면 95% 가 10번이
     * 되어 "늘 제시간" 처럼 읽힌다. 지나치게 장담하지 않는다(캡션).
     */
    fun timesOutOfTen(percent: Int): Int = percent.coerceIn(0, 100) / 10

    fun head(alarmLabel: String): String = "${alarmLabel}에 일어나면"

    fun percentLabel(percent: Int): String = "정시 도착 확률 $percent%"

    /** 문장. [emphasis] 만 굵게 확률 색으로 쓴다 */
    data class Sentence(val lead: String, val emphasis: String, val tail: String) {
        val text: String get() = lead + emphasis + tail
    }

    fun sentence(percent: Int, startLabel: String): Sentence {
        val n = timesOutOfTen(percent)
        return if (n == 0) {
            Sentence(LEAD, "한 번도", " $startLabel 전에 도착하기 어렵다는 뜻이에요.")
        } else {
            Sentence(LEAD, "${n}번", "은 $startLabel 전에 도착한다는 뜻이에요.")
        }
    }

    /** "오전 7:40" · "오후 12:05" · 자정은 "오전 12:00" */
    fun clock(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = Instant.ofEpochMilli(millis).atZone(zone).toLocalTime()
        val word = if (time.hour < 12) "오전" else "오후"
        val hour = (time.hour % 12).let { if (it == 0) 12 else it }
        return "$word $hour:%02d".format(time.minute)
    }

    private const val LEAD = "같은 아침이 10번 온다면\n"
}

/**
 * "정시 도착 확률이 뭐예요?" 바텀시트(Figma `548:1798`). ④ 오른쪽 위 i 와 홈 다음 알람 카드의 확률 칩에서 연다.
 *
 * 원 10개 중 [OnTimeSheetText.timesOutOfTen] 개를 [tone] 으로 칠하고, "10번 중 n번" 문장 · 어떻게 정하는지 ·
 * 확률을 높이는 법을 적는다. [알람 시각 바꾸기] 는 H2-3 시트 자리다 — 시트가 생기기 전에는 부르는 쪽이 ⑤ 지각 위험
 * 고르기로 보낸다. null 이면 버튼을 그리지 않는다. [알겠어요] · 바깥 누르기 · 아래로 밀기로 닫힌다.
 *
 * @param tone 확률을 보여 준 곳과 같은 색. 칩과 시트의 색이 다르면 다른 값처럼 읽힌다
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OnTimeProbabilitySheet(
    spec: OnTimeSheetSpec,
    tone: Color,
    onChangeTime: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = JitColor.Surface,
        scrimColor = JitColor.Bg.copy(alpha = 0.6f),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(JitColor.Track),
            )
        },
        modifier = Modifier.testTag("on_time_sheet"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(OnTimeSheetText.TITLE, color = JitColor.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Illustration(spec, tone)
            How()
            Raise(onChangeTime)
            JitPrimaryButton(
                label = OnTimeSheetText.OK,
                onClick = onDismiss,
                modifier = Modifier.testTag("on_time_ok"),
            )
        }
    }
}

/** 원 10개 그림(Figma `illustration 10번 중 9번`) */
@Composable
private fun Illustration(spec: OnTimeSheetSpec, tone: Color) {
    val filled = OnTimeSheetText.timesOutOfTen(spec.percent)
    val sentence = OnTimeSheetText.sentence(spec.percent, spec.startLabel)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(JitColor.Surface2)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = OnTimeSheetText.head(spec.alarmLabel),
                color = JitColor.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(tone),
                )
                Text(
                    text = OnTimeSheetText.percentLabel(spec.percent),
                    color = tone,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("on_time_percent"),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("on_time_circles")
                .semantics { contentDescription = "10번 중 ${filled}번 정시 도착" },
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            repeat(10) { index ->
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (index < filled) tone else JitColor.Track),
                )
            }
        }
        Text(
            text = buildAnnotatedString {
                append(sentence.lead)
                withStyle(SpanStyle(color = tone, fontWeight = FontWeight.Bold)) { append(sentence.emphasis) }
                append(sentence.tail)
            },
            color = JitColor.TextPrimary,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            modifier = Modifier.testTag("on_time_sentence"),
        )
    }
}

@Composable
private fun How() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(OnTimeSheetText.HOW_TITLE, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        OnTimeSheetText.HOW.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(JitColor.TextSecondary),
                )
                Text(line, color = JitColor.TextSecondary, fontSize = 12.sp, lineHeight = 14.sp)
            }
        }
    }
}

@Composable
private fun Raise(onChangeTime: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(OnTimeSheetText.RAISE_TITLE, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(OnTimeSheetText.RAISE_BODY, color = JitColor.TextSecondary, fontSize = 12.sp, lineHeight = 14.sp)
        onChangeTime?.let { change ->
            Row(
                modifier = Modifier
                    .testTag("on_time_change_time")
                    .height(36.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(JitColor.Surface2)
                    .clickable(role = Role.Button, onClick = change)
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LineIcons.pencil, contentDescription = null, tint = JitColor.TextPrimary, modifier = Modifier.size(16.dp))
                Text(OnTimeSheetText.CHANGE_TIME, color = JitColor.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
