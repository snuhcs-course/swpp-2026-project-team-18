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

    // AI-generated (Claude)
    @Test
    fun `칸에 붙지 않는 저장 실패는 폼 한 줄에 무엇을 못 했는지와 함께 남긴다`() {
        // Given 검증을 통과한 폼을 보냈는데 통신 실패 · 5xx 처럼 칸 오류 없이 실패했다
        val sent = draft().validate()

        // When 저장 실패를 폼에 붙인다
        val failed = sent.withSaveFailure("서버에 연결할 수 없다. 네트워크와 서버 상태를 확인한다.")

        // Then 폼에 보이는 줄에 이유가 남고, 저장소의 문장 앞에 저장하지 못했다고 먼저 말한다. 목록의 오류 줄은 폼이 열려
        // 있는 동안 그려지지 않는다
        assertEquals(
            mapOf(BlockDraft.FORM_ERROR to "저장하지 못했어요. 서버에 연결할 수 없다. 네트워크와 서버 상태를 확인한다."),
            failed.errors,
        )
        assertEquals("샤워", failed.name)
    }

    // AI-generated (Claude)
    @Test
    fun `칸 오류가 있으면 칸 아래에만 그대로 붙이고 폼 줄은 두지 않는다`() {
        // Given 서버가 이름 중복을 칸 오류로 돌려줬다
        val fieldErrors = mapOf("name" to "같은 이름의 항목이 이미 있다.")

        // When 저장 실패를 폼에 붙인다
        val failed = draft().withSaveFailure("입력값을 확인해야 한다.", fieldErrors)

        // Then 같은 이유를 두 번 보이지 않고, 칸 오류 글은 고치지 않는다
        assertEquals(fieldErrors, failed.errors)
        assertNull(failed.errors[BlockDraft.FORM_ERROR])
    }

    // AI-generated (Claude)
    @Test
    fun `삭제 실패는 폼 한 줄에 삭제하지 못했다고 먼저 말한다`() {
        // Given 이미 있는 항목의 폼
        val open = BlockDraft(id = 7, name = "샤워", minText = "12", maxText = "18")

        // When 다른 기기에서 먼저 지워 삭제가 404 로 실패했다
        val failed = open.withDeleteFailure("대상을 찾을 수 없다.")

        // Then 저장소의 문장만 두지 않고 무엇을 못 했는지 앞에 적는다
        assertEquals(mapOf(BlockDraft.FORM_ERROR to "삭제하지 못했어요. 대상을 찾을 수 없다."), failed.errors)
    }

    // AI-generated (Claude)
    @Test
    fun `폼 줄은 칸 오류 키와 겹치지 않고 다시 저장하면 지워진다`() {
        // Given 저장 실패 한 줄이 남은 폼
        val failed = draft().withFailure("서버에 문제가 생겼다.")

        // When 다시 저장하려고 검증한다
        val again = failed.validate()

        // Then 칸 오류 키와 다르고, 검증이 오류를 새로 만들어 낡은 줄이 남지 않는다
        assertFalse(BlockDraft.FORM_ERROR in setOf("name", "min", "max"))
        assertTrue(again.errors.isEmpty())
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
    fun `확률이 없으면 아직 계산할 수 없는 상태다`() {
        assertTrue(confidence(null).isUnavailable)
        assertFalse(confidence(92).isUnavailable)
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

    // AI-generated (Claude)
    @Test
    fun `받는 동안 다른 블록 화면을 열었으면 늦게 온 목록을 쓰지 않는다`() {
        // Given 일정 5 의 블록을 받는 중에 일정 6 의 블록 화면을 열었다
        val now = RoutineEditorState(loading = true, eventId = 6)

        // When / Then 5 의 목록은 버리고 6 · 정의 편집은 제 것만 받는다
        assertFalse(now.acceptsBlocksFor(5))
        assertFalse(now.acceptsBlocksFor(null))
        assertTrue(now.acceptsBlocksFor(6))
        assertTrue(RoutineEditorState(loading = true).acceptsBlocksFor(null))
    }

    // AI-generated (Claude)
    @Test
    fun `저장을 다시 보내기 시작하면 앞 실패 줄이 도는 버튼 위에 남지 않는다`() {
        // Given 통신 실패로 저장하지 못한 폼이 열려 있다
        val failed = BlockDraft(id = 1, name = " 샤워 ", minText = "12", maxText = "18")
            .withSaveFailure("서버에 연결할 수 없다.")
        val state = RoutineEditorState(blocks = listOf(block(1)), editing = failed, error = "앞 오류", notice = "안내")

        // When 고치지 않고 저장을 다시 누른다 — 검증을 통과한 폼을 보낸다
        val sent = failed.validate()
        val sending = state.startingSave(sent)

        // Then 보내는 폼이 곧 화면의 폼이라 앞 실패 줄이 없고, 버튼은 돈다
        assertTrue(sending.saving)
        assertEquals(sent, sending.editing)
        assertNull(sending.editing?.errors?.get(BlockDraft.FORM_ERROR))
        assertNull(sending.error)
        assertNull(sending.notice)
    }

    // AI-generated (Claude)
    @Test
    fun `삭제를 다시 보내기 시작하면 앞 실패 줄만 지운다`() {
        // Given 삭제에 실패한 폼과, 저장을 눌러 이름 칸 오류가 난 폼
        val deleteFailed = BlockDraft(id = 1, name = "샤워", minText = "12", maxText = "18")
            .withDeleteFailure("서버에 연결할 수 없다.")
        val nameMissing = BlockDraft(id = 1, name = "", minText = "12", maxText = "18").validate()

        // When 항목 삭제를 누른다
        val retry = RoutineEditorState(editing = deleteFailed).startingDelete()
        val withField = RoutineEditorState(editing = nameMissing).startingDelete()

        // Then 앞 실패 줄은 지우고, 지금 입력에 대한 칸 오류는 둔다. 폼이 닫혀 있으면 폼을 만들지 않는다
        assertTrue(retry.saving)
        assertEquals(deleteFailed.copy(errors = emptyMap()), retry.editing)
        assertEquals(nameMissing, withField.editing)
        assertNull(RoutineEditorState().startingDelete().editing)
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
