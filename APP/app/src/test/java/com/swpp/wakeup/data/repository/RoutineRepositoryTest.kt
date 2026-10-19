package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.BlockSelectionRequest
import com.swpp.wakeup.data.remote.EventBlocksResponse
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.RoutineBlockDto
import com.swpp.wakeup.data.remote.RoutineBlockWriteRequest
import com.swpp.wakeup.data.repository.RoutineRepository.RoutineResult
import com.swpp.wakeup.domain.model.BlockDraft
import com.swpp.wakeup.domain.model.DropCost
import com.swpp.wakeup.domain.model.RoutineBlockView
import com.swpp.wakeup.testing.FakeRoutinesApi
import com.swpp.wakeup.testing.errorJson
import com.swpp.wakeup.testing.fromJson
import com.swpp.wakeup.testing.httpError
import com.swpp.wakeup.testing.ok
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * 루틴 블록 저장소.
 *
 * ## 이 테스트가 지키는 것
 *
 * - 학습 상태 문구: 실측 평균이 신고 범위를 **벗어나면 그렇다고 말한다.**
 *   조용히 학습만 하면 사용자는 자기 설정이 맞다고 믿은 채 알람이 왜 이른지 모른다.
 * - 부분 수정은 **바꾼 필드만** 보낸다. 전체를 보내면 다른 기기에서 바뀐 값을
 *   덮어쓰고, 일정별 체크는 손대지 않은 블록까지 "직접 바꿈" 으로 굳는다.
 * - 서버의 필드별 오류(이름 중복 등)는 버리지 않고 해당 칸으로 간다.
 * - 잘못된 입력은 서버에 보내지 않는다(왕복과 400 을 아낀다).
 */
class RoutineRepositoryTest {

    private fun block(json: String): RoutineBlockDto = fromJson(json)

    private val shower = block(
        """{"id":1,"name":"샤워","default_min_minutes":12,"default_max_minutes":18,
            "drop_cost":"large","parallelizable":false,"included_by_default":true,"order":0,
            "observation_count":0}"""
    )

    private fun blocksApi(vararg items: RoutineBlockDto) = object : FakeRoutinesApi() {
        override suspend fun blocks() = ok(items.toList())
    }

    private suspend fun RoutineRepository.onlyBlock(): RoutineBlockView {
        val result = blocks()
        assertTrue("성공이어야 한다: $result", result is RoutineResult.Success)
        return (result as RoutineResult.Success).data.single()
    }

    // --- 목록 매핑 ------------------------------------------------------------

    @Test
    fun `관측이 없으면 신고 범위를 사전값으로 쓴다고 말한다`() = runTest {
        val view = RoutineRepository(blocksApi(shower)).onlyBlock()

        assertEquals("12~18분", view.rangeLabel)
        assertEquals(DropCost.LARGE, view.dropCost)
        assertNull(view.observedMeanLabel)
        assertEquals("관측 없음 · 신고 범위를 사전값으로 씀", view.learningNote)
        assertFalse(view.rangeMismatch)
    }

    @Test
    fun `범위가 한 점이면 변동 폭이 없다고 알린다`() = runTest {
        val point = block(
            """{"id":2,"name":"양치","default_min_minutes":5,"default_max_minutes":5}"""
        )

        val view = RoutineRepository(blocksApi(point)).onlyBlock()

        assertEquals("5분", view.rangeLabel)
        assertEquals("관측 없음 · 범위가 한 점이라 변동 폭이 없음", view.learningNote)
    }

    @Test
    fun `실측 평균이 신고 최대보다 길면 범위를 늘리라고 권한다`() = runTest {
        val slow = block(
            """{"id":1,"name":"샤워","default_min_minutes":12,"default_max_minutes":18,
                "observation_count":4,"observed_mean_minutes":22.0}"""
        )

        val view = RoutineRepository(blocksApi(slow)).onlyBlock()

        assertEquals("실측 평균 22분", view.observedMeanLabel)
        assertEquals("실측 평균 22분 · 신고 최대보다 4분 길다. 범위를 늘리는 것을 권함", view.learningNote)
        assertTrue(view.rangeMismatch)
    }

    @Test
    fun `실측 평균이 신고 최소보다 짧으면 범위를 줄이라고 권한다`() = runTest {
        val fast = block(
            """{"id":1,"name":"샤워","default_min_minutes":12,"default_max_minutes":18,
                "observation_count":3,"observed_mean_minutes":8.4}"""
        )

        val view = RoutineRepository(blocksApi(fast)).onlyBlock()

        assertEquals("실측 평균 8.4분", view.observedMeanLabel)
        assertEquals("실측 평균 8.4분 · 신고 최소보다 4분 짧다. 범위를 줄이면 알람이 늦춰짐", view.learningNote)
        assertTrue(view.rangeMismatch)
    }

    @Test
    fun `실측 평균이 범위 안이면 관측 횟수와 함께 그렇다고 말한다`() = runTest {
        val fine = block(
            """{"id":1,"name":"샤워","default_min_minutes":12,"default_max_minutes":18,
                "observation_count":5,"observed_mean_minutes":15.0}"""
        )

        val view = RoutineRepository(blocksApi(fine)).onlyBlock()

        assertEquals("실측 평균 15분 · 관측 5회, 신고 범위 안에 있음", view.learningNote)
        assertFalse(view.rangeMismatch)
    }

    @Test
    fun `일정 문맥이 없으면 체크 상태는 기본 포함값을 따른다`() = runTest {
        val optional = block(
            """{"id":3,"name":"화장","default_min_minutes":10,"default_max_minutes":20,
                "included_by_default":false}"""
        )

        val view = RoutineRepository(blocksApi(optional)).onlyBlock()

        assertFalse(view.checked)
        assertFalse(view.explicit)
    }

    @Test
    fun `모르는 drop_cost 는 보통으로 떨어진다`() = runTest {
        // 서버가 선택지를 늘려도 화면이 비지 않아야 한다.
        val future = block(
            """{"id":4,"name":"운동","default_min_minutes":10,"default_max_minutes":20,
                "drop_cost":"catastrophic"}"""
        )

        assertEquals(DropCost.MEDIUM, RoutineRepository(blocksApi(future)).onlyBlock().dropCost)
    }

    @Test
    fun `목록 조회가 실패하고 캐시도 없으면 실패를 돌려준다`() = runTest {
        val api = object : FakeRoutinesApi() {
            override suspend fun blocks(): Response<List<RoutineBlockDto>> = throw IOException("offline")
        }

        val result = RoutineRepository(api).blocks()

        assertTrue(result is RoutineResult.Failure)
        assertTrue((result as RoutineResult.Failure).message.startsWith("서버에 연결할 수 없다"))
    }

    @Test
    fun `일정별 블록은 서버가 준 체크 상태를 쓴다`() = runTest {
        val response = fromJson<EventBlocksResponse>(
            """{"event":10,"blocks":[
                {"id":1,"name":"샤워","default_min_minutes":12,"default_max_minutes":18,
                 "included_by_default":true,"checked":false,"explicit":true}]}"""
        )
        val api = object : FakeRoutinesApi() {
            override suspend fun eventBlocks(eventId: Long) = ok(response)
        }

        val result = RoutineRepository(api).eventBlocks(10)

        val view = (result as RoutineResult.Success).data.single()
        assertFalse("기본 포함이어도 이 일정에서 끈 것을 따른다", view.checked)
        assertTrue(view.explicit)
    }

    // --- 생성·수정 ------------------------------------------------------------

    @Test
    fun `생성은 입력값을 서버 필드로 옮겨 보낸다`() = runTest {
        var sent: RoutineBlockWriteRequest? = null
        val api = object : FakeRoutinesApi() {
            override suspend fun createBlock(body: RoutineBlockWriteRequest) =
                ok(shower).also { sent = body }
        }
        val draft = BlockDraft(
            name = " 샤워 ",
            minText = "12",
            maxText = " 18 ",
            dropCost = DropCost.LARGE,
            parallelizable = true,
            includedByDefault = false,
        )

        val result = RoutineRepository(api).createBlock(draft)

        assertTrue(result is RoutineResult.Success)
        assertEquals(
            RoutineBlockWriteRequest(
                name = "샤워",
                minMinutes = 12,
                maxMinutes = 18,
                dropCost = "large",
                parallelizable = true,
                includedByDefault = false,
            ),
            sent,
        )
        assertNull("순서는 서버 기본값에 맡긴다", sent!!.order)
    }

    @Test
    fun `잘못된 입력은 서버에 보내지 않는다`() = runTest {
        // FakeRoutinesApi 기본 구현은 부르면 실패한다. 즉 호출 자체가 없어야 통과한다.
        val repository = RoutineRepository(FakeRoutinesApi())
        val invalid = listOf(
            BlockDraft(name = "", minText = "5", maxText = "10"),
            BlockDraft(name = "샤워", minText = "abc", maxText = "10"),
            BlockDraft(name = "샤워", minText = "20", maxText = "10"),
            BlockDraft(name = "샤워", minText = "5", maxText = "999"),
        )

        invalid.forEach { draft ->
            val result = repository.createBlock(draft)
            assertEquals("$draft", RoutineResult.Failure("입력값을 확인해야 한다."), result)
        }
    }

    @Test
    fun `id 없는 초안은 수정할 수 없다`() = runTest {
        val result = RoutineRepository(FakeRoutinesApi())
            .updateBlock(BlockDraft(name = "샤워", minText = "5", maxText = "10"))

        assertEquals(RoutineResult.Failure("수정할 블록이 없다."), result)
    }

    @Test
    fun `서버의 필드별 오류를 함께 돌려준다`() = runTest {
        val api = object : FakeRoutinesApi() {
            override suspend fun updateBlock(id: Long, body: RoutineBlockWriteRequest) =
                httpError<RoutineBlockDto>(
                    400,
                    errorJson("입력값이 올바르지 않다.", mapOf("name" to "같은 이름의 블록이 이미 있다.")),
                )
        }

        val result = RoutineRepository(api)
            .updateBlock(BlockDraft(id = 1, name = "샤워", minText = "5", maxText = "10"))

        result as RoutineResult.Failure
        assertEquals("입력값이 올바르지 않다.", result.message)
        assertEquals(mapOf("name" to "같은 이름의 블록이 이미 있다."), result.fieldErrors)
    }

    @Test
    fun `기본 포함 스위치는 그 필드 하나만 보낸다`() = runTest {
        var sent: RoutineBlockWriteRequest? = null
        val api = object : FakeRoutinesApi() {
            override suspend fun updateBlock(id: Long, body: RoutineBlockWriteRequest) =
                ok(shower).also { sent = body }
        }

        RoutineRepository(api).setIncludedByDefault(id = 1, included = false)

        assertEquals(RoutineBlockWriteRequest(includedByDefault = false), sent)
    }

    @Test
    fun `삭제 실패는 상태 코드 문구로 알린다`() = runTest {
        val api = object : FakeRoutinesApi() {
            override suspend fun deleteBlock(id: Long) = httpError<Unit>(404)
        }

        assertEquals(RoutineResult.Failure("대상을 찾을 수 없다."), RoutineRepository(api).deleteBlock(9))
    }

    @Test
    fun `삭제 성공`() = runTest {
        val api = object : FakeRoutinesApi() {
            override suspend fun deleteBlock(id: Long): Response<Unit> = ok(Unit)
        }

        assertEquals(RoutineResult.Success(Unit), RoutineRepository(api).deleteBlock(1))
    }

    // --- 일정별 체크 ------------------------------------------------------------

    @Test
    fun `일정별 체크는 바뀐 블록만 보낸다`() = runTest {
        var sent: BlockSelectionRequest? = null
        val updated = fromJson<EventDto>(
            """{"id":10,"title":"수업","start_at":"2030-03-02T00:00:00Z","source":"manual"}"""
        )
        val api = object : FakeRoutinesApi() {
            override suspend fun setEventBlocks(eventId: Long, body: BlockSelectionRequest) =
                ok(updated).also { sent = body }
        }

        val result = RoutineRepository(api).setEventBlocks(10, mapOf(1L to false, 3L to true))

        assertEquals(RoutineResult.Success(updated), result)
        assertEquals(
            setOf(1L to false, 3L to true),
            sent!!.selections.map { it.block to it.checked }.toSet(),
        )
    }

    @Test
    fun `바뀐 것이 없으면 서버를 부르지 않는다`() = runTest {
        val result = RoutineRepository(FakeRoutinesApi()).setEventBlocks(10, emptyMap())

        assertEquals(RoutineResult.Failure("바뀐 항목이 없다."), result)
    }

    // --- 필드 오류 매핑 ---------------------------------------------------------

    @Test
    fun `서버 필드 이름을 화면 칸 이름으로 바꾼다`() {
        val mapped = RoutineRepository.toDraftErrors(
            mapOf(
                "name" to "이름 오류",
                "default_min_minutes" to "최소 오류",
                "default_max_minutes" to "최대 오류",
            )
        )

        assertEquals(mapOf("name" to "이름 오류", "min" to "최소 오류", "max" to "최대 오류"), mapped)
    }

    @Test
    fun `모르는 필드의 오류는 버리지 않고 이름 칸에 모은다`() {
        val mapped = RoutineRepository.toDraftErrors(
            mapOf("non_field_errors" to "범위가 뒤집혔다.", "default_min_minutes" to "최소 오류")
        )

        assertEquals("범위가 뒤집혔다.", mapped["name"])
        assertEquals("최소 오류", mapped["min"])
    }

    @Test
    fun `이름 칸에 오류가 있으면 모르는 필드 오류가 덮어쓰지 않는다`() {
        val mapped = RoutineRepository.toDraftErrors(
            mapOf("name" to "이름 오류", "non_field_errors" to "다른 오류")
        )

        assertEquals("이름 오류", mapped["name"])
    }
}
