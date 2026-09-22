package com.swpp.wakeup.ui.morning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.domain.model.MorningBlock
import com.swpp.wakeup.domain.model.MorningSession
import com.swpp.wakeup.ui.common.JitCard
import com.swpp.wakeup.ui.common.JitChip
import com.swpp.wakeup.ui.common.JitDotLabel
import com.swpp.wakeup.ui.common.JitPrimaryButton
import com.swpp.wakeup.ui.common.JitProgressBar
import com.swpp.wakeup.ui.events.ScreenHeader
import com.swpp.wakeup.ui.theme.JitColor
import com.swpp.wakeup.ui.theme.JitRadius
import com.swpp.wakeup.ui.theme.JitSpace
import com.swpp.wakeup.ui.theme.JitTheme
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 아침 기록. Figma S7 계열.
 *
 * ## 왜 이 화면이 필요한가
 *
 * **준비 시간 학습의 유일한 입구다.** 사용자가 신고한 "샤워 12~18분" 은 분포의
 * 사전값일 뿐이다. 실제 소요가 쌓이지 않으면 평균이 영원히 움직이지 않고,
 * `confidence_basis` 는 `declared_range` 에 머문다. 주간 리포트의 `prep_over`
 * 도 전부 "측정 안 됨" 으로 나온다.
 *
 * ## 탭 한 번으로 끝낸다
 *
 * 시작 탭과 종료 탭을 둘 다 받으면 정확하지만 아침에 탭이 두 배가 된다. 그러면
 * 사용자가 아예 안 쓴다. **다음 블록 하나만 강조**해 순서대로 마치게 하고,
 * 마친 시각으로 앞 블록의 소요를 계산한다. 순서를 건너뛰면 그 블록의 소요에
 * 앞 블록 시간이 섞이는데, 그 부정확함이 기록이 없는 것보다 낫다.
 *
 * ## 남은 여유를 크게 보여준다
 *
 * 이 화면의 두 번째 값어치다. "출발까지 18분" 을 보면 지금 커피를 내릴지
 * 판단할 수 있다. 계획 소요를 뺀 **순 여유**를 보여준다 — 남은 시간을 그냥
 * 보여주면 아직 할 일이 남은 것을 잊는다.
 */
@Composable
fun MorningProgressScreen(
    session: MorningSession?,
    onBack: () -> Unit,
    onMarkDone: (Long) -> Unit,
    onUndo: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JitColor.Bg)
            .verticalScroll(rememberScrollState())
            .padding(
                start = JitSpace.ScreenHorizontal,
                end = JitSpace.ScreenHorizontal,
                top = JitSpace.ScreenTop,
                bottom = JitSpace.ScreenBottom,
            ),
        verticalArrangement = Arrangement.spacedBy(JitSpace.Section),
    ) {
        ScreenHeader(title = "아침 기록", onBack = onBack)

        if (session == null) {
            EmptyCard()
            return@Column
        }

        // 1초마다 다시 그린다. 남은 여유가 멈춰 있으면 사용자가 화면을 믿지
        // 않는다. 여기서만 쓰는 시계라 ViewModel 에 두지 않는다.
        var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(session.eventId) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(1_000)
            }
        }

        SlackCard(session, nowMillis)
        ProgressCard(session, nowMillis)

        session.blocks.forEach { block ->
            BlockRow(
                block = block,
                session = session,
                nowMillis = nowMillis,
                isCurrent = session.current?.blockId == block.blockId,
                onMarkDone = { onMarkDone(block.blockId) },
            )
        }

        Spacer(Modifier.height(4.dp))

        if (session.isComplete) {
            JitPrimaryButton(label = "기록 끝내기", onClick = onFinish)
            Text(
                text = "기록은 서버로 올라가 다음 알람 계산에 반영됨. 네트워크가 없으면 " +
                    "연결될 때 자동으로 올라감",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        } else {
            JitPrimaryButton(
                label = session.current?.let { "\"${it.name}\" 마침" } ?: "기록 끝내기",
                onClick = { session.current?.let { onMarkDone(it.blockId) } ?: onFinish() },
            )
            Text(
                text = "남은 항목을 하지 않았으면 그냥 두고 나가도 됨. 안 한 것은 기록되지 않음",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }

        if (session.done.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "마지막 기록 되돌리기",
                    color = JitColor.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onUndo)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            // 서버에 이미 올라간 값은 지우지 않는다. 그 한계를 숨기지 않는다.
            Text(
                text = "화면의 진행만 되돌림. 이미 올라간 기록은 서버에 남고, 다시 마쳐도 " +
                    "같은 건으로 취급돼 값이 바뀌지 않음",
                color = JitColor.TextSecondary,
                fontSize = 9.sp,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!session.isComplete) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "기록 끝내기",
                    color = JitColor.TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onFinish)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * 남은 여유.
 *
 * **순 여유를 보여준다** — 남은 시간에서 아직 할 일의 계획 소요를 뺀 값이다.
 * 남은 시간만 보여주면 "18분 남았다" 를 보고 커피를 내리는데 실은 옷 갈아입기
 * 10분이 남아 있다.
 */
@Composable
private fun SlackCard(session: MorningSession, nowMillis: Long) {
    val departBy = session.departByMillis
    if (departBy == null) {
        JitCard(padding = 16.dp, gap = 6.dp) {
            JitDotLabel(
                text = "출발 시각을 계산하지 못했음",
                dotColor = JitColor.TextSecondary,
                fontSize = 13,
                dotSize = 8.dp,
            )
            Text(
                text = "여유는 보여줄 수 없지만 기록은 남음. 이 기록이 다음 알람을 정확하게 만듦",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
            )
        }
        return
    }

    val leftMinutes = (departBy - nowMillis) / 60_000.0
    val netSlack = leftMinutes - session.remainingPlannedMinutes()
    val color = when {
        netSlack < 0 -> JitColor.Red
        netSlack < 5 -> JitColor.Amber
        else -> JitColor.Green
    }

    JitCard(padding = 18.dp, gap = 7.dp, accented = netSlack < 0) {
        Text(
            text = if (netSlack >= 0) "여유" else "이미 늦음",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = minutesLabel(abs(netSlack)),
                color = color,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (netSlack >= 0) "남음" else "초과",
                color = JitColor.TextSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 7.dp),
            )
        }
        Text(
            text = "출발까지 ${minutesLabel(leftMinutes)} · 남은 계획 " +
                minutesLabel(session.remainingPlannedMinutes()),
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
        Text(
            text = "여유는 남은 시간에서 아직 할 일의 계획 소요를 뺀 값임",
            color = JitColor.TextSecondary,
            fontSize = 9.sp,
        )
    }
}

@Composable
private fun ProgressCard(session: MorningSession, nowMillis: Long) {
    val total = session.blocks.size
    val done = session.done.size
    val elapsed = (nowMillis - session.startedAtMillis) / 60_000.0

    JitCard(padding = 14.dp, gap = 7.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JitDotLabel(
                text = "$done / ${total}개 마침",
                dotColor = JitColor.Accent,
                fontSize = 13,
                dotSize = 7.dp,
            )
            Text(
                text = "일어난 뒤 ${minutesLabel(elapsed)}",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
            )
        }
        JitProgressBar(
            fraction = if (total == 0) 0f else done.toFloat() / total,
            color = JitColor.Accent,
        )
        session.plannedPrepMinutes?.let {
            Text(
                text = "계획 준비 ${it}분",
                color = JitColor.TextSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun BlockRow(
    block: MorningBlock,
    session: MorningSession,
    nowMillis: Long,
    isCurrent: Boolean,
    onMarkDone: () -> Unit,
) {
    val doneAt = block.doneAtMillis
    val actual = doneAt?.let { session.durationOf(block.blockId, it) }

    // 진행 중인 블록만 지금까지 걸린 시간을 보여준다. 아직 시작하지 않은
    // 블록에 시간을 띄우면 이미 하고 있는 것처럼 읽힌다.
    val running = if (isCurrent) {
        (nowMillis - session.startOf(block.blockId)) / 60_000.0
    } else {
        null
    }

    val over = actual != null && actual > block.plannedMinutes

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(JitRadius.Card))
            .background(if (isCurrent) JitColor.Surface2 else JitColor.Surface)
            .clickable(enabled = doneAt == null, onClick = onMarkDone)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(
                    when {
                        doneAt != null -> JitColor.Green
                        isCurrent -> JitColor.Accent
                        else -> JitColor.Track
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (doneAt != null) {
                Text("✓", color = JitColor.Bg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = block.name,
                    color = if (doneAt == null && !isCurrent) JitColor.TextSecondary
                    else JitColor.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (block.parallelizable) {
                    Spacer(Modifier.width(6.dp))
                    JitChip("병렬", JitColor.Blue, fontSize = 9)
                }
            }

            Text(
                text = when {
                    actual != null -> "실제 ${minutesLabel(actual)} · 계획 " +
                        minutesLabel(block.plannedMinutes)

                    running != null -> "진행 중 ${minutesLabel(running)} · 계획 " +
                        minutesLabel(block.plannedMinutes)

                    else -> "계획 ${minutesLabel(block.plannedMinutes)}"
                },
                color = when {
                    over -> JitColor.Amber
                    actual != null -> JitColor.Green
                    else -> JitColor.TextSecondary
                },
                fontSize = 10.sp,
            )
        }

        if (doneAt == null) {
            Text(
                text = if (isCurrent) "마침" else "대기",
                color = if (isCurrent) JitColor.Accent else JitColor.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun EmptyCard() {
    JitCard(padding = 16.dp, gap = 7.dp) {
        JitDotLabel(
            text = "기록할 아침이 없음",
            dotColor = JitColor.TextSecondary,
            fontSize = 13,
            dotSize = 8.dp,
        )
        Text(
            text = "알람을 해제하면 그 아침의 루틴 항목이 여기 나타남. 항목을 마칠 때마다 " +
                "탭하면 실제 소요가 기록되고, 그 기록이 준비 시간 학습의 재료가 됨",
            color = JitColor.TextSecondary,
            fontSize = 11.sp,
        )
        Text(
            text = "루틴 항목이 하나도 없으면 이 화면은 뜨지 않음",
            color = JitColor.TextSecondary,
            fontSize = 10.sp,
        )
    }
}

/** "12분" / "4.5분". 음수는 호출부가 부호를 처리한다. */
private fun minutesLabel(value: Double): String {
    val rounded = Math.round(abs(value) * 10) / 10.0
    return if (abs(rounded - Math.floor(rounded)) < 1e-9) "${rounded.roundToLong()}분"
    else "${rounded}분"
}

// ---------------------------------------------------------------------------

@Preview(widthDp = 360, heightDp = 1000, showBackground = true, backgroundColor = 0xFF0E1320)
@Composable
private fun MorningProgressPreview() {
    val now = System.currentTimeMillis()
    JitTheme {
        MorningProgressScreen(
            session = MorningSession(
                eventId = 3,
                startedAtMillis = now - 22 * 60_000,
                departByMillis = now + 18 * 60_000,
                plannedPrepMinutes = 32,
                blocks = listOf(
                    MorningBlock(1, "샤워", 15.0, false, now - 4 * 60_000),
                    MorningBlock(2, "아침 식사", 9.0, false, null),
                    MorningBlock(3, "세탁기", 5.0, true, null),
                    MorningBlock(4, "옷 갈아입기", 6.0, false, null),
                ),
            ),
            onBack = {},
            onMarkDone = {},
            onUndo = {},
            onFinish = {},
        )
    }
}
