// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.onboarding

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min

/** 홈 코치마크(iter4 H2-4, Figma `549:1845`)가 밝히는 자리 */
internal enum class CoachTarget {
    /** 다음 알람 카드 */
    NEXT_ALARM,

    /** 다음 알람이 없고 일정도 없을 때 그 자리의 빈 상태 */
    EMPTY,

    /** 떠 있는 [+ 일정 추가] */
    ADD_EVENT,

    /** 하단 탭의 "이동" */
    TRIP_TAB,
}

/**
 * 말풍선을 밝힌 자리의 어디에 붙이는가.
 *
 * 아래쪽은 링 바로 밑(꼬리가 위를 가리킨다), 위쪽은 링 바로 위(꼬리가 아래를 가리킨다)다.
 */
internal enum class BubbleAnchor { BELOW_START, BELOW_END, BELOW_CENTER, ABOVE_END, ABOVE_CENTER }

/**
 * 말풍선 하나. [emphasis] 는 앞에 주황 굵게 쓰는 기능 이름("+ 일정 추가"), [text] 는 그 뒤에 이어지는 말이다.
 */
internal data class BubbleSpec(
    val target: CoachTarget,
    val anchor: BubbleAnchor,
    val emphasis: String?,
    val text: String,
) {
    val fullText: String get() = (emphasis ?: "") + text
}

/** 밝힌 자리(구멍)와 그 둘레의 주황 링. 같은 둥근 사각형이다 */
internal data class CoachRing(val target: CoachTarget, val rect: Rect, val radius: Float)

/** 말풍선 몸통의 왼쪽 위와 꼬리 끝의 x. 꼬리는 몸통 위(아래 붙은 말풍선)나 아래에 붙는다 */
internal data class BubblePlacement(val topLeft: Offset, val tailCenterX: Float, val tailUp: Boolean)

/**
 * 코치마크의 문구와 자리 계산. 화면([HomeCoachmark])과 떼어 JVM 테스트로 고정한다(`FirstRunGuideTest`).
 *
 * 길이는 모두 px 이고 [dp] 는 1dp 의 px 이다. 자리 값은 Figma `549:1845`(360×800)의 간격에서 왔다 — 링은 요소보다
 * 4 크고, 말풍선은 링에서 12 떨어지며 그 사이 8 에 꼬리(14×8)가 들어간다.
 */
internal object CoachmarkLogic {

    /** 다음 알람이 있으면 그 카드, 일정이 하나도 없으면 빈 상태를 밝힌다. 둘 다 아니면(알람을 다 꺼 둠) 밝히지 않는다 */
    fun cardTarget(hasNextAlarm: Boolean, isEmpty: Boolean): CoachTarget? = when {
        hasNextAlarm -> CoachTarget.NEXT_ALARM
        isEmpty -> CoachTarget.EMPTY
        else -> null
    }

    /** 띄울 말풍선. [card] 가 null 이면 + 일정 추가와 이동 탭만 */
    fun bubbles(card: CoachTarget?): List<BubbleSpec> = buildList {
        when (card) {
            CoachTarget.NEXT_ALARM -> {
                add(BubbleSpec(card, BubbleAnchor.BELOW_START, null, "다음 알람이에요.\n누르면 왜 이 시각인지\n보여 드려요"))
                add(BubbleSpec(card, BubbleAnchor.BELOW_END, "시간 오른쪽의\n시각 바꾸기", "로 알람을\n직접 고칠 수 있어요"))
            }
            // Figma 에 없는 경우다. 다음 알람 카드가 생길 자리라는 것만 말한다.
            CoachTarget.EMPTY ->
                add(BubbleSpec(card, BubbleAnchor.BELOW_CENTER, null, "일정을 추가하면\n여기에 다음 알람이 떠요"))
            else -> Unit
        }
        add(BubbleSpec(CoachTarget.ADD_EVENT, BubbleAnchor.ABOVE_END, "+ 일정 추가", "로 새 일정을\n간편하게 등록해요"))
        add(BubbleSpec(CoachTarget.TRIP_TAB, BubbleAnchor.ABOVE_CENTER, "이동 탭", "에서\n오늘 갈 길을 봐요"))
    }

    /** 요소의 실제 자리([targets], 코치마크 기준 px)로 구멍과 링을 만든다. 아직 자리를 모르는 요소는 뺀다 */
    fun rings(targets: Map<CoachTarget, Rect>, card: CoachTarget?, dp: Float): List<CoachRing> = buildList {
        val cardRect = card?.let { targets[it] }
        if (card != null && cardRect != null) cardRect.let { rect ->
            add(
                when (card) {
                    // 카드 모서리 16 + 4
                    CoachTarget.NEXT_ALARM -> CoachRing(card, rect.inflate(4 * dp), 20 * dp)
                    // 빈 상태는 배경 없는 글 묶음이라 위아래로 더 띄운다
                    else -> CoachRing(
                        card,
                        Rect(rect.left - 4 * dp, rect.top - 12 * dp, rect.right + 4 * dp, rect.bottom + 12 * dp),
                        20 * dp,
                    )
                }
            )
        }
        targets[CoachTarget.ADD_EVENT]?.let { fab ->
            // 알약이라 링도 알약이다(48 높이 → 56, 모서리 28)
            add(CoachRing(CoachTarget.ADD_EVENT, fab.inflate(4 * dp), fab.height / 2 + 4 * dp))
        }
        targets[CoachTarget.TRIP_TAB]?.let { tab ->
            // 탭 칸 전체가 아니라 아이콘 · 이름 둘레만(Figma 78×51, 모서리 16)
            val width = min(78 * dp, tab.width - 4 * dp)
            val height = min(51 * dp, tab.height)
            add(CoachRing(CoachTarget.TRIP_TAB, tab.inflateTo(width, height), 16 * dp))
        }
    }

    /**
     * 말풍선 자리. [target] 은 요소, [ring] 은 그 링이다. 화면 밖으로 나가지 않게 좌우 16 안에 둔다.
     * 꼬리는 몸통 모서리(12)에 걸리지 않게 몸통 안쪽으로 당긴다.
     */
    fun placeBubble(
        anchor: BubbleAnchor,
        target: Rect,
        ring: Rect,
        width: Float,
        height: Float,
        screenWidth: Float,
        dp: Float,
    ): BubblePlacement {
        val (rawX, rawTail) = when (anchor) {
            // Figma: 카드 왼쪽에 맞추고 꼬리는 왼쪽에서 41
            BubbleAnchor.BELOW_START -> target.left to target.left + 41 * dp
            // 카드 오른쪽에 맞추고 꼬리는 [시각 바꾸기] 쪽(오른쪽에서 73)
            BubbleAnchor.BELOW_END -> (target.right - width) to target.right - 73 * dp
            // + 일정 추가 오른쪽 끝에 맞추고 꼬리는 버튼 가운데
            BubbleAnchor.ABOVE_END -> (target.right + 2 * dp - width) to target.center.x
            BubbleAnchor.BELOW_CENTER, BubbleAnchor.ABOVE_CENTER -> (ring.center.x - width / 2) to ring.center.x
        }
        val margin = 16 * dp
        val x = rawX.coerceIn(margin, max(margin, screenWidth - margin - width))
        val below = anchor == BubbleAnchor.BELOW_START || anchor == BubbleAnchor.BELOW_END ||
            anchor == BubbleAnchor.BELOW_CENTER
        val y = if (below) ring.bottom + GAP * dp else ring.top - GAP * dp - height
        val inset = (12 + TAIL_WIDTH / 2) * dp
        val tail = if (width > 2 * inset) rawTail.coerceIn(x + inset, x + width - inset) else x + width / 2
        return BubblePlacement(Offset(x, y), tail, tailUp = below)
    }

    /**
     * [알겠어요] 묶음의 왼쪽 위. 가로 가운데, 세로는 위 말풍선들 아래 40(Figma). 아래 말풍선과 겹치면 둘 사이 가운데.
     *
     * @param upperBottom 카드 쪽 말풍선(없으면 링)의 가장 아래. 없으면 null
     * @param lowerTop + 일정 추가 · 이동 탭 말풍선의 가장 위. 없으면 null
     */
    fun placeDone(
        width: Float,
        height: Float,
        screenWidth: Float,
        screenHeight: Float,
        upperBottom: Float?,
        lowerTop: Float?,
        dp: Float,
    ): Offset {
        val x = (screenWidth - width) / 2
        val floor = lowerTop ?: screenHeight
        val y = if (upperBottom == null) {
            min((screenHeight - height) / 2, floor - 24 * dp - height)
        } else {
            val preferred = upperBottom + 40 * dp
            if (preferred + height + 24 * dp <= floor) preferred else (upperBottom + floor - height) / 2
        }
        return Offset(x, max(0f, y))
    }

    /** 링과 말풍선 사이. 그 안에 꼬리(8)가 들어간다 */
    const val GAP = 12f
    const val TAIL_WIDTH = 14f
    const val TAIL_HEIGHT = 8f

    private fun Rect.inflateTo(width: Float, height: Float) =
        Rect(center.x - width / 2, center.y - height / 2, center.x + width / 2, center.y + height / 2)
}
