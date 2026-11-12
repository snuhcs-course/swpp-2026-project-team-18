// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.wakeup.ui.common.LineIcons
import com.swpp.wakeup.ui.theme.JitColor
import kotlin.math.roundToInt

/**
 * 코치마크가 밝힐 요소의 실제 자리. 요소 쪽이 [coachmarkTarget] 으로 알려 주고 코치마크가 읽는다.
 *
 * 요소가 화면에서 사라지면(다른 화면으로 가거나 목록 밖으로 밀림) 자리를 지운다 — 남겨 두면 빈 곳에 구멍이 난다.
 */
@Stable
internal class CoachmarkTargets {

    private val rects = mutableStateMapOf<CoachTarget, Rect>()

    /** 지금 아는 자리(앱 화면 기준 px). 읽으면 바뀔 때 다시 그린다 */
    val snapshot: Map<CoachTarget, Rect> get() = rects.toMap()

    fun put(target: CoachTarget, rect: Rect) {
        if (rects[target] != rect) rects[target] = rect
    }

    fun remove(target: CoachTarget) {
        rects.remove(target)
    }
}

/** 홈 · 탭 바가 자리를 알릴 곳. 없으면(미리보기 · 테스트) 아무것도 하지 않는다 */
internal val LocalCoachmarkTargets = staticCompositionLocalOf<CoachmarkTargets?> { null }

/** 이 요소의 실제 자리를 코치마크에 알린다(Figma 캡션: "구멍은 그 요소의 실제 위치로 잡고") */
internal fun Modifier.coachmarkTarget(target: CoachTarget): Modifier = composed {
    val targets = LocalCoachmarkTargets.current ?: return@composed Modifier
    DisposableEffect(targets, target) { onDispose { targets.remove(target) } }
    Modifier.onGloballyPositioned { targets.put(target, it.boundsInRoot()) }
}

/**
 * 홈 첫 방문 안내(iter4 H2-4, Figma `549:1845`). 화면 전체를 bg 85% 로 덮고 다음 알람 카드(없으면 빈 상태) ·
 * + 일정 추가 · 이동 탭만 밝힌다. 단계를 넘기지 않고 한 장에 모두 보인다.
 *
 * [알겠어요] 나 화면 아무 곳을 누르면 닫힌다. 밝힌 요소를 눌러도 그 기능은 실행하지 않고 닫기만 한다 — 막 전체가
 * 누름을 받는다. 탭 바까지 덮어야 하므로 `Scaffold` 바깥에 그린다(`MainActivity`).
 *
 * @param card 밝힐 카드([CoachmarkLogic.cardTarget]). null 이면 + 일정 추가와 이동 탭만
 */
@Composable
internal fun HomeCoachmark(
    targets: CoachmarkTargets,
    card: CoachTarget?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 요소의 자리는 앱 화면 기준이다. 이 막의 왼쪽 위를 빼서 막 기준으로 바꾼다.
    var origin by remember { mutableStateOf<Offset?>(null) }
    val known = targets.snapshot
    val local = origin?.let { o -> known.mapValues { (_, rect) -> rect.translate(-o) } }.orEmpty()
    val dp = LocalDensity.current.density

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .testTag("home_coachmark")
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "안내 닫기",
                onClick = onDismiss,
            ),
    ) {
        // 막의 자리를 알기 전에는 구멍을 어디에 낼지 모른다. 한 프레임 뒤에 그린다.
        val rings = if (origin == null) emptyList() else CoachmarkLogic.rings(local, card, dp)
        val ringed = rings.mapTo(HashSet()) { it.target }
        val bubbles = CoachmarkLogic.bubbles(card).filter { it.target in ringed }

        Canvas(Modifier.fillMaxSize()) {
            // 짝수-홀수 규칙으로 막에 구멍을 뚫는다(Figma 는 BooleanOperation).
            val scrim = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                rings.forEach { addRoundRect(RoundRect(it.rect, CornerRadius(it.radius))) }
            }
            drawPath(scrim, JitColor.Bg.copy(alpha = SCRIM_ALPHA))
            rings.forEach {
                drawRoundRect(
                    color = JitColor.Accent,
                    topLeft = it.rect.topLeft,
                    size = it.rect.size,
                    cornerRadius = CornerRadius(it.radius),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }

        CoachmarkLayout(rings = rings, bubbles = bubbles, targets = local, card = card, onDismiss = onDismiss)
    }
}

/** 말풍선 몸통 · 꼬리 · [알겠어요] 를 요소 자리에 맞춰 놓는다. 자리 계산은 [CoachmarkLogic] 에 있다 */
@Composable
private fun CoachmarkLayout(
    rings: List<CoachRing>,
    bubbles: List<BubbleSpec>,
    targets: Map<CoachTarget, Rect>,
    card: CoachTarget?,
    onDismiss: () -> Unit,
) {
    Layout(
        content = {
            bubbles.forEach { BubbleBody(it) }
            bubbles.forEach { BubbleTail(up = it.anchor.below) }
            DoneGroup(onDismiss)
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val dp = density
        val n = bubbles.size
        val loose = constraints.copy(minWidth = 0, minHeight = 0)

        val bodies = measurables.subList(0, n).mapIndexed { i, measurable ->
            val spec = bubbles[i]
            // 카드 아래 두 말풍선은 나란히 놓이므로 각각 카드 폭의 절반을 넘지 않는다.
            val side = spec.anchor == BubbleAnchor.BELOW_START || spec.anchor == BubbleAnchor.BELOW_END
            val max = if (side) (targets.getValue(spec.target).width - 8 * dp) / 2 else width - 32 * dp
            measurable.measure(loose.copy(maxWidth = max.roundToInt().coerceIn(0, constraints.maxWidth)))
        }
        val tails = measurables.subList(n, 2 * n).map { it.measure(loose) }
        val done = measurables[2 * n].measure(loose)

        val placements = bubbles.mapIndexed { i, spec ->
            CoachmarkLogic.placeBubble(
                anchor = spec.anchor,
                target = targets.getValue(spec.target),
                ring = rings.first { it.target == spec.target }.rect,
                width = bodies[i].width.toFloat(),
                height = bodies[i].height.toFloat(),
                screenWidth = width,
                dp = dp,
            )
        }
        val upperBottom = placements.indices.filter { placements[it].tailUp }
            .maxOfOrNull { placements[it].topLeft.y + bodies[it].height }
            ?: rings.firstOrNull { it.target == card }?.rect?.bottom
        val lowerTop = placements.filter { !it.tailUp }.minOfOrNull { it.topLeft.y }
        val doneAt = CoachmarkLogic.placeDone(
            width = done.width.toFloat(),
            height = done.height.toFloat(),
            screenWidth = width,
            screenHeight = height,
            upperBottom = upperBottom,
            lowerTop = lowerTop,
            dp = dp,
        )

        layout(constraints.maxWidth, constraints.maxHeight) {
            bodies.forEachIndexed { i, body ->
                body.place(placements[i].topLeft.x.roundToInt(), placements[i].topLeft.y.roundToInt())
            }
            tails.forEachIndexed { i, tail ->
                val at = placements[i]
                val x = (at.tailCenterX - tail.width / 2f).roundToInt()
                // 1px 겹쳐 몸통과 꼬리 사이에 틈이 보이지 않게 한다.
                val y = if (at.tailUp) at.topLeft.y.roundToInt() - tail.height + 1 else at.topLeft.y.roundToInt() + bodies[i].height - 1
                tail.place(x, y)
            }
            done.place(doneAt.x.roundToInt(), doneAt.y.roundToInt())
        }
    }
}

private val BubbleAnchor.below: Boolean
    get() = this == BubbleAnchor.BELOW_START || this == BubbleAnchor.BELOW_END || this == BubbleAnchor.BELOW_CENTER

/** 말풍선 몸통. 기능 이름은 주황 굵게(Figma: "+ 일정 추가" 13 Bold Accent) */
@Composable
private fun BubbleBody(spec: BubbleSpec) {
    Box(
        modifier = Modifier
            .testTag("coachmark_bubble_${spec.target.name.lowercase()}_${spec.anchor.name.lowercase()}")
            .clip(RoundedCornerShape(12.dp))
            .background(JitColor.Surface2)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = buildAnnotatedString {
                spec.emphasis?.let { strong ->
                    withStyle(SpanStyle(color = JitColor.Accent, fontWeight = FontWeight.Bold)) { append(strong) }
                }
                append(spec.text)
            },
            color = JitColor.TextPrimary,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** 말풍선 꼬리(14×8). 위쪽 말풍선이면 아래를, 아래쪽이면 위를 가리킨다 */
@Composable
private fun BubbleTail(up: Boolean) {
    Canvas(Modifier.size(CoachmarkLogic.TAIL_WIDTH.dp, CoachmarkLogic.TAIL_HEIGHT.dp)) {
        val path = Path().apply {
            if (up) {
                moveTo(0f, size.height)
                lineTo(size.width / 2, 0f)
                lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width / 2, size.height)
                lineTo(size.width, 0f)
            }
            close()
        }
        drawPath(path, JitColor.Surface2)
    }
}

/** [알겠어요] + "내 정보 → 도움말에서 다시 볼 수 있어요" */
@Composable
private fun DoneGroup(onDismiss: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .testTag("coachmark_done")
                .height(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(JitColor.Accent)
                .clickable(role = Role.Button, onClick = onDismiss)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = LineIcons.check, contentDescription = null, tint = JitColor.Bg, modifier = Modifier.size(16.dp))
            Text("알겠어요", color = JitColor.Bg, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(JitColor.Bg)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text = "내 정보 → 도움말에서 다시 볼 수 있어요",
                color = JitColor.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 막의 진하기(Figma: bg 85%) */
private const val SCRIM_ALPHA = 0.85f
