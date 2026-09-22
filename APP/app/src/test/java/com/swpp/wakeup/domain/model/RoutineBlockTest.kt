package com.swpp.wakeup.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 루틴 블록 도메인 규칙.
 *
 * 여기 있는 것은 전부 **틀리면 조용히 잘못 동작하는** 판단이다. 화면은
 * 그대로 그려지고 서버도 200 을 주는데 결과만 어긋난다. 그래서 테스트로 박아
 * 둔다.
 */
class BlockDraftTest {

    private fun draft(
        name: String = "샤워",
        min: String = "12",
        max: String = "18",
    ) = BlockDraft(name = name, minText = min, maxText = max)

    @Test
    fun `정상 입력은 오류가 없다`() {
        assertTrue(draft().validate().errors.isEmpty())
    }

    @Test
    fun `이름이 비면 막는다`() {
        val errors = draft(name = "   ").validate().errors
        assertTrue(errors.containsKey("name"))
    }

    @Test
    fun `검증이 이름의 공백을 떼어낸다`() {
        // 서버의 유일성 제약은 공백을 포함한 문자열로 비교한다. 앱이 떼지
        // 않으면 "샤워" 와 "샤워 " 가 다른 블록이 되어 관측이 갈린다.
        assertEquals("샤워", draft(name = "  샤워  ").validate().name)
    }

    @Test
    fun `이름 길이 상한을 넘기면 막는다`() {
        val long = "가".repeat(BlockDraft.MAX_NAME + 1)
        assertTrue(draft(name = long).validate().errors.containsKey("name"))
    }

    @Test
    fun `숫자가 아니면 막는다`() {
        val errors = draft(min = "", max = "abc").validate().errors
        assertTrue(errors.containsKey("min"))
        assertTrue(errors.containsKey("max"))
    }

    @Test
    fun `뒤집힌 범위를 막는다`() {
        // 이걸 통과시키면 서버에서 표준편차가 음수가 되어 분포 생성이 터진다.
        // DB 제약도 있지만 여기서 막아야 왕복 없이 알려줄 수 있다.
        val errors = draft(min = "20", max = "10").validate().errors
        assertEquals("최대가 최소보다 작을 수 없다.", errors["max"])
    }

    @Test
    fun `범위가 같은 것은 허용한다`() {
        // 한 점 범위는 "변동성이 없다" 는 사용자의 신고다. 막을 이유가 없다.
        assertTrue(draft(min = "5", max = "5").validate().errors.isEmpty())
    }

    @Test
    fun `상한을 넘는 분을 막는다`() {
        val over = (BlockDraft.MAX_MINUTES + 1).toString()
        assertTrue(draft(min = "0", max = over).validate().errors.containsKey("max"))
    }

    @Test
    fun `새 블록과 수정을 구분한다`() {
        assertTrue(BlockDraft().isNew)
        assertFalse(BlockDraft(id = 7).isNew)
    }
}

class ConfidenceViewTest {

    private fun confidence(percent: Int?) = ConfidenceView(
        percent = percent,
        headline = "headline",
        reason = null,
        action = null,
    )

    @Test
    fun `확률이 없으면 학습 중이다`() {
        assertTrue(confidence(null).isLearning)
        assertFalse(confidence(92).isLearning)
    }

    @Test
    fun `목표치를 tau 로 비교한다`() {
        // 90 고정으로 비교하면 tau=0.99 를 고른 사용자에게 95% 를 초록으로
        // 보여주게 된다. 목표 미달인데 안심시키는 셈이다.
        assertTrue(confidence(95).meets(0.90))
        assertFalse(confidence(95).meets(0.99))
        assertTrue(confidence(99).meets(0.99))
    }

    @Test
    fun `tau 가 없으면 90 을 기준으로 삼는다`() {
        assertTrue(confidence(90).meets(null))
        assertFalse(confidence(89).meets(null))
    }

    @Test
    fun `범위를 벗어난 tau 는 무시하고 기본값을 쓴다`() {
        // 서버가 이상값을 주더라도 화면이 늘 판정할 수 있어야 한다.
        assertTrue(confidence(90).meets(1.5))
        assertTrue(confidence(90).meets(-0.2))
    }

    @Test
    fun `확률이 없으면 목표 달성이 아니다`() {
        assertFalse(confidence(null).meets(0.90))
    }
}

class DropCostTest {

    @Test
    fun `와이어 값을 열거형으로 바꾼다`() {
        assertEquals(DropCost.NONE, DropCost.from("none"))
        assertEquals(DropCost.IMPOSSIBLE, DropCost.from("impossible"))
    }

    @Test
    fun `모르는 값은 보통으로 떨어진다`() {
        // 서버가 선택지를 늘렸을 때 앱이 죽지 않아야 한다. 열거형 파싱 실패로
        // 화면 전체가 비는 것보다 하나가 기본값으로 보이는 편이 낫다.
        assertEquals(DropCost.MEDIUM, DropCost.from("brand_new_value"))
        assertEquals(DropCost.MEDIUM, DropCost.from(null))
    }
}

class RoutineEditorStateTest {

    private fun block(
        id: Long,
        min: Int = 10,
        max: Int = 20,
        checked: Boolean = true,
        mismatch: Boolean = false,
    ) = RoutineBlockView(
        id = id,
        name = "블록$id",
        minMinutes = min,
        maxMinutes = max,
        rangeLabel = "$min~${max}분",
        dropCost = DropCost.MEDIUM,
        parallelizable = false,
        includedByDefault = true,
        order = id.toInt(),
        observationCount = 0,
        observedMeanLabel = null,
        learningNote = "note",
        rangeMismatch = mismatch,
        checked = checked,
    )

    @Test
    fun `바뀐 체크만 보낸다`() {
        // 전부 보내면 손대지 않은 블록까지 explicit 으로 표시되고, 그러면
        // 나중에 기본 포함값을 고쳐도 이 일정에는 반영되지 않는다.
        val blocks = listOf(block(1, checked = true), block(2, checked = false))
        val state = RoutineEditorState(
            blocks = blocks,
            original = mapOf(1L to true, 2L to true),
        )
        assertEquals(mapOf(2L to false), state.changes)
        assertTrue(state.dirty)
    }

    @Test
    fun `바뀐 것이 없으면 저장할 것이 없다`() {
        val blocks = listOf(block(1), block(2))
        val state = RoutineEditorState(
            blocks = blocks,
            original = blocks.associate { it.id to it.checked },
        )
        assertTrue(state.changes.isEmpty())
        assertFalse(state.dirty)
    }

    @Test
    fun `단순 합은 포함된 블록만 더한다`() {
        val state = RoutineEditorState(
            blocks = listOf(
                block(1, min = 10, max = 20, checked = true),
                block(2, min = 5, max = 5, checked = true),
                block(3, min = 30, max = 40, checked = false),
            ),
        )
        assertEquals("단순 합 15~25분", state.simpleSumLabel)
        assertEquals(2, state.includedCount)
    }

    @Test
    fun `범위가 한 점이면 물결표를 쓰지 않는다`() {
        val state = RoutineEditorState(blocks = listOf(block(1, min = 7, max = 7)))
        assertEquals("단순 합 7분", state.simpleSumLabel)
    }

    @Test
    fun `포함된 블록이 없으면 합계가 없다`() {
        val state = RoutineEditorState(blocks = listOf(block(1, checked = false)))
        assertNull(state.simpleSumLabel)
    }

    @Test
    fun `일정 모드는 eventId 로 갈린다`() {
        assertFalse(RoutineEditorState().isEventMode)
        assertTrue(RoutineEditorState(eventId = 3).isEventMode)
    }

    @Test
    fun `신고 범위를 벗어난 블록을 센다`() {
        val state = RoutineEditorState(
            blocks = listOf(
                block(1, mismatch = true),
                block(2, mismatch = false),
                block(3, mismatch = true),
            ),
        )
        assertEquals(2, state.outOfRangeCount)
    }
}
