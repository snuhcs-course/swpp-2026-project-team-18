// Partly AI-generated (Claude)
package com.swpp.wakeup.ui.onboarding

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 첫 실행 안내 세 장과 홈 코치마크(iter4 H2-4, Figma `545:1305` · `546:2214` · `547:2009` · `549:1845`).
 *
 * 자리 계산은 Figma 프레임(360×800, 1dp = 1px)의 실제 좌표로 검사한다 — 홈 카드 324×165 @18,113, + 일정 추가
 * 120×48 @222,664, 이동 탭 칸 90×80 @90,720.
 */
class FirstRunGuideTest {

    private val dp = 1f
    private val card = Rect(18f, 113f, 342f, 278f)
    private val fab = Rect(222f, 664f, 342f, 712f)
    private val tripTab = Rect(90f, 720f, 180f, 800f)

    @Test
    fun `세 장의 제목과 버튼`() {
        assertEquals(3, IntroPages.COUNT)
        assertEquals("도착 시각만 알려 주세요", IntroPages.title(0))
        assertEquals("알람 시각은 직접 바꿀 수 있어요", IntroPages.title(1))
        assertEquals("쓸수록 나에게 맞춰져요", IntroPages.title(2))
        assertEquals(listOf("다음", "다음", "시작하기"), (0 until 3).map(IntroPages::cta))
    }

    @Test
    fun `2장 본문은 버튼 이름 시각 바꾸기를 담는다`() {
        assertTrue(IntroPages.body(1).contains(IntroPages.BODY_EMPHASIS))
        // 이동은 GPS 로 저절로 기록된다. "이동을 기록하면" 이라고 쓰지 않는다(Figma 캡션).
        assertTrue(IntroPages.body(2).contains("집을 나서면"))
        assertFalse(IntroPages.body(2).contains("이동을 기록"))
    }

    @Test
    fun `다음 알람이 있으면 카드, 일정이 없으면 빈 상태를 밝힌다`() {
        assertEquals(CoachTarget.NEXT_ALARM, CoachmarkLogic.cardTarget(hasNextAlarm = true, isEmpty = false))
        assertEquals(CoachTarget.EMPTY, CoachmarkLogic.cardTarget(hasNextAlarm = false, isEmpty = true))
        assertNull("일정은 있는데 알람을 다 꺼 뒀으면 카드 자리가 없다", CoachmarkLogic.cardTarget(false, false))
    }

    @Test
    fun `말풍선은 Figma 네 개, 빈 상태면 셋, 카드가 없으면 둘`() {
        val full = CoachmarkLogic.bubbles(CoachTarget.NEXT_ALARM)
        assertEquals(
            listOf(
                "다음 알람이에요.\n누르면 왜 이 시각인지\n보여 드려요",
                "시간 오른쪽의\n시각 바꾸기로 알람을\n직접 고칠 수 있어요",
                "+ 일정 추가로 새 일정을\n간편하게 등록해요",
                "이동 탭에서\n오늘 갈 길을 봐요",
            ),
            full.map { it.fullText },
        )
        assertEquals(listOf("시간 오른쪽의\n시각 바꾸기", "+ 일정 추가", "이동 탭"), full.mapNotNull { it.emphasis })

        assertEquals(
            listOf(CoachTarget.EMPTY, CoachTarget.ADD_EVENT, CoachTarget.TRIP_TAB),
            CoachmarkLogic.bubbles(CoachTarget.EMPTY).map { it.target },
        )
        assertEquals(listOf(CoachTarget.ADD_EVENT, CoachTarget.TRIP_TAB), CoachmarkLogic.bubbles(null).map { it.target })
    }

    @Test
    fun `링은 Figma 와 같은 자리`() {
        val targets = mapOf(
            CoachTarget.NEXT_ALARM to card,
            CoachTarget.ADD_EVENT to fab,
            CoachTarget.TRIP_TAB to tripTab,
        )
        val rings = CoachmarkLogic.rings(targets, CoachTarget.NEXT_ALARM, dp).associateBy { it.target }

        // ring 다음 알람 @14,109 r20 — 카드보다 4 크다(Figma 는 아래만 6)
        assertEquals(Rect(14f, 109f, 346f, 282f), rings.getValue(CoachTarget.NEXT_ALARM).rect)
        assertEquals(20f, rings.getValue(CoachTarget.NEXT_ALARM).radius)
        // ring 일정 추가 128×56 @218,660 r28
        assertEquals(Rect(218f, 660f, 346f, 716f), rings.getValue(CoachTarget.ADD_EVENT).rect)
        assertEquals(28f, rings.getValue(CoachTarget.ADD_EVENT).radius)
        // ring 이동 탭 78×51, 탭 칸 가운데
        val trip = rings.getValue(CoachTarget.TRIP_TAB)
        assertEquals(78f, trip.rect.width)
        assertEquals(51f, trip.rect.height)
        assertEquals(tripTab.center, trip.rect.center)
    }

    @Test
    fun `자리를 모르는 요소에는 구멍을 내지 않는다`() {
        val rings = CoachmarkLogic.rings(mapOf(CoachTarget.ADD_EVENT to fab), CoachTarget.NEXT_ALARM, dp)
        assertEquals(listOf(CoachTarget.ADD_EVENT), rings.map { it.target })
    }

    @Test
    fun `카드 아래 두 말풍선은 Figma 자리`() {
        // Figma 의 ring 다음 알람 332×175 @14,109
        val ring = Rect(14f, 109f, 346f, 284f)
        // bubble 다음 알람 141×77 @18,296, 꼬리 @52,288(가운데 59)
        val start = CoachmarkLogic.placeBubble(BubbleAnchor.BELOW_START, card, ring, 141f, 77f, 360f, dp)
        assertEquals(Offset(18f, 296f), start.topLeft)
        assertEquals(59f, start.tailCenterX)
        assertTrue(start.tailUp)
        // bubble 시각 바꾸기 138×77 @204,296, 꼬리 @262,288(가운데 269)
        val end = CoachmarkLogic.placeBubble(BubbleAnchor.BELOW_END, card, ring, 138f, 77f, 360f, dp)
        assertEquals(Offset(204f, 296f), end.topLeft)
        assertEquals(269f, end.tailCenterX)
    }

    @Test
    fun `아래 두 말풍선은 링 위에 꼬리를 아래로`() {
        // bubble 일정 추가 152×58 @192,591 — Figma 는 링 위 11, 꼬리 가운데 282(버튼 가운데)
        val add = CoachmarkLogic.placeBubble(BubbleAnchor.ABOVE_END, fab, fab.inflate(4f), 152f, 58f, 360f, dp)
        assertEquals(192f, add.topLeft.x)
        assertEquals(660f - 12f - 58f, add.topLeft.y)
        assertEquals(282f, add.tailCenterX)
        assertFalse(add.tailUp)

        // bubble 이동 탭 117×58, 탭 가운데(135)에 맞춘다
        val ring = CoachmarkLogic.rings(mapOf(CoachTarget.TRIP_TAB to tripTab), null, dp).single().rect
        val trip = CoachmarkLogic.placeBubble(BubbleAnchor.ABOVE_CENTER, tripTab, ring, 117f, 58f, 360f, dp)
        assertEquals(135f - 58.5f, trip.topLeft.x)
        assertEquals(135f, trip.tailCenterX)
        assertEquals(ring.top - 12f - 58f, trip.topLeft.y)
    }

    @Test
    fun `말풍선은 화면 밖으로 나가지 않고 꼬리는 모서리에 걸리지 않는다`() {
        val narrow = Rect(300f, 600f, 330f, 640f)
        val bubble = CoachmarkLogic.placeBubble(BubbleAnchor.ABOVE_CENTER, narrow, narrow, 150f, 58f, 360f, dp)
        assertEquals(360f - 16f - 150f, bubble.topLeft.x)
        assertEquals(315f, bubble.tailCenterX)

        val edge = Rect(0f, 600f, 20f, 640f)
        val left = CoachmarkLogic.placeBubble(BubbleAnchor.ABOVE_CENTER, edge, edge, 150f, 58f, 360f, dp)
        assertEquals(16f, left.topLeft.x)
        assertEquals("모서리 12 + 꼬리 반 7 안쪽", 16f + 19f, left.tailCenterX)
    }

    @Test
    fun `알겠어요는 위 말풍선 아래 40, 모자라면 사이 가운데`() {
        // done 201×63 @80,413 — 위 말풍선 아래(373) + 40
        assertEquals(Offset(79.5f, 413f), CoachmarkLogic.placeDone(201f, 63f, 360f, 800f, 373f, 591f, dp))

        // 카드가 길어 아래 말풍선과 겹치면 둘 사이 가운데
        val squeezed = CoachmarkLogic.placeDone(201f, 63f, 360f, 800f, 500f, 591f, dp)
        assertEquals((500f + 591f - 63f) / 2, squeezed.y)

        // 밝힐 카드가 없으면 화면 가운데
        assertEquals((800f - 63f) / 2, CoachmarkLogic.placeDone(201f, 63f, 360f, 800f, null, 591f, dp).y)
    }
}
