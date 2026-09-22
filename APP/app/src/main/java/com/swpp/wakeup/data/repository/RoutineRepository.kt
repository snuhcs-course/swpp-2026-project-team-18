package com.swpp.wakeup.data.repository

import android.util.Log
import com.swpp.wakeup.BuildConfig
import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.BlockSelectionItem
import com.swpp.wakeup.data.remote.BlockSelectionRequest
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.RoutineBlockDto
import com.swpp.wakeup.data.remote.RoutineBlockWriteRequest
import com.swpp.wakeup.data.remote.RoutinesApi
import com.swpp.wakeup.domain.model.BlockDraft
import com.swpp.wakeup.domain.model.DropCost
import com.swpp.wakeup.domain.model.RoutineBlockView
import retrofit2.Response
import java.io.IOException
import kotlin.math.abs

/**
 * 루틴 블록 저장소.
 *
 * 규칙은 [EventRepository] 와 같다 — **예외를 밖으로 던지지 않는다.** 다만
 * 결과 타입이 따로다: 블록 편집은 **필드별 오류**가 필요하다. 이름 중복은
 * 서버만 확실히 알 수 있는 검증이고("전체 목록이 최신" 가정은 깨지기 쉽다),
 * 그 오류는 이름 칸 아래에 붙어야 사용자가 무엇을 고칠지 안다.
 */
class RoutineRepository(
    private val api: RoutinesApi = ApiClient.routines,
) {

    sealed interface RoutineResult<out T> {
        data class Success<T>(val data: T) : RoutineResult<T>

        /**
         * [fieldErrors] 의 키는 **서버 필드 이름**이다
         * (`name` / `default_min_minutes` / `default_max_minutes`).
         * 화면 키로 바꾸는 것은 [toDraftErrors] 가 한다.
         */
        data class Failure(
            val message: String,
            val fieldErrors: Map<String, String> = emptyMap(),
        ) : RoutineResult<Nothing>
    }

    /** 블록 정의 목록. 일정 문맥이 없으므로 `checked` 는 기본 포함값을 따른다. */
    suspend fun blocks(): RoutineResult<List<RoutineBlockView>> = guard {
        val response = api.blocks()
        val body = unwrap(response) ?: return@guard failure(response)
        RoutineResult.Success(body.map { it.toView() })
    }

    /**
     * 이 일정의 블록 체크 상태.
     *
     * 블록 **전체**를 주고 각 행에 `checked` 를 붙여 준다. 체크된 것만 주면
     * 화면이 "뭘 더 켤 수 있는지" 를 보여줄 수 없다.
     */
    suspend fun eventBlocks(eventId: Long): RoutineResult<List<RoutineBlockView>> = guard {
        val response = api.eventBlocks(eventId)
        val body = unwrap(response) ?: return@guard failure(response)
        RoutineResult.Success(body.blocks.map { it.toView() })
    }

    suspend fun createBlock(draft: BlockDraft): RoutineResult<RoutineBlockView> = guard {
        val body = draft.toWriteRequest()
            ?: return@guard RoutineResult.Failure("입력값을 확인해야 한다.")
        val response = api.createBlock(body)
        unwrap(response)?.let { RoutineResult.Success(it.toView()) } ?: failure(response)
    }

    /**
     * 블록 수정.
     *
     * **서버는 이때 알람을 재계산하지 않는다.** 정의 변경은 설정 변경이라
     * 다음 계산에 반영되면 되고, 매번 재계산하면 카카오 경로 쿼터(일 1,000건)를
     * 수정 횟수만큼 먹는다. 즉시 반영이 필요하면 호출부가 일정 재계산을 부른다.
     */
    suspend fun updateBlock(draft: BlockDraft): RoutineResult<RoutineBlockView> = guard {
        val id = draft.id ?: return@guard RoutineResult.Failure("수정할 블록이 없다.")
        val body = draft.toWriteRequest()
            ?: return@guard RoutineResult.Failure("입력값을 확인해야 한다.")
        val response = api.updateBlock(id, body)
        unwrap(response)?.let { RoutineResult.Success(it.toView()) } ?: failure(response)
    }

    /**
     * 기본 포함 여부만 바꾼다.
     *
     * 목록에서 스위치 하나를 누르는 흐름이다. 전체 폼을 보내면 사용자가 열지도
     * 않은 칸이 서버로 가고, 그 사이 다른 기기에서 바뀐 값을 덮어쓴다.
     * 여기서는 **한 필드만** 담아 Gson 이 나머지를 빼게 한다.
     */
    suspend fun setIncludedByDefault(
        id: Long,
        included: Boolean,
    ): RoutineResult<RoutineBlockView> = guard {
        val response = api.updateBlock(
            id,
            RoutineBlockWriteRequest(includedByDefault = included),
        )
        unwrap(response)?.let { RoutineResult.Success(it.toView()) } ?: failure(response)
    }

    suspend fun deleteBlock(id: Long): RoutineResult<Unit> = guard {
        val response = api.deleteBlock(id)
        if (response.isSuccessful) RoutineResult.Success(Unit) else failure(response)
    }

    /**
     * 일정별 체크를 바꾸고 **즉시 재계산**한다.
     *
     * 응답이 갱신된 일정이라 호출부가 새 알람 시각을 바로 쓸 수 있다.
     * 체크는 "이 아침에 뭘 할지" 를 정하는 행위라 사용자가 즉시 결과를 기대한다.
     *
     * 바뀐 것만 보낸다. 전체를 보내면 손대지 않은 블록까지 `explicit` 로
     * 표시돼 이후에 기본값을 고쳐도 이 일정에는 반영되지 않는다.
     */
    suspend fun setEventBlocks(
        eventId: Long,
        changes: Map<Long, Boolean>,
    ): RoutineResult<EventDto> = guard {
        if (changes.isEmpty()) return@guard RoutineResult.Failure("바뀐 항목이 없다.")
        val response = api.setEventBlocks(
            eventId,
            BlockSelectionRequest(
                selections = changes.map { (block, checked) ->
                    BlockSelectionItem(block = block, checked = checked)
                }
            ),
        )
        unwrap(response)?.let { RoutineResult.Success(it) } ?: failure(response)
    }

    // --- 내부 -------------------------------------------------------------

    private inline fun <T> guard(block: () -> RoutineResult<T>): RoutineResult<T> = try {
        block()
    } catch (e: IOException) {
        Log.w(TAG, "network failure", e)
        RoutineResult.Failure(MESSAGE_NETWORK)
    } catch (e: Exception) {
        Log.e(TAG, "unexpected failure", e)
        RoutineResult.Failure(
            if (BuildConfig.DEV_TOOLS) "$MESSAGE_UNKNOWN (${e.javaClass.simpleName})"
            else MESSAGE_UNKNOWN
        )
    }

    private fun <T> unwrap(response: Response<T>): T? =
        if (response.isSuccessful) response.body() else null

    /**
     * 실패 응답을 결과로. 본문을 **한 번만** 읽는다.
     *
     * `errorBody().string()` 은 스트림을 소비한다. 두 번 부르면 두 번째가 빈
     * 문자열이라 필드 오류가 조용히 사라진다.
     */
    private fun failure(response: Response<*>): RoutineResult.Failure {
        val raw = response.errorBody()?.string()
        val message = ApiClient.parseErrorMessage(raw) ?: when (response.code()) {
            400 -> "입력값을 확인해야 한다."
            401 -> "다시 로그인해야 한다."
            404 -> "대상을 찾을 수 없다."
            429 -> "요청이 너무 잦다. 잠시 후 다시 시도한다."
            in 500..599 -> "서버에 문제가 생겼다."
            else -> MESSAGE_UNKNOWN
        }
        return RoutineResult.Failure(message, ApiClient.parseErrorFields(raw))
    }

    companion object {
        private const val TAG = "RoutineRepository"
        private const val MESSAGE_NETWORK =
            "서버에 연결할 수 없다. 네트워크와 서버 상태를 확인한다."
        private const val MESSAGE_UNKNOWN = "알 수 없는 오류가 발생했다."

        /**
         * 서버 필드 이름을 [BlockDraft.errors] 의 키로 바꾼다.
         *
         * 매핑에 없는 필드는 버리지 않고 `name` 칸으로 모은다 — 서버가 준
         * 이유가 화면에서 사라지면 사용자가 왜 저장이 안 되는지 알 수 없다.
         */
        fun toDraftErrors(fieldErrors: Map<String, String>): Map<String, String> {
            if (fieldErrors.isEmpty()) return emptyMap()
            val out = mutableMapOf<String, String>()
            val spill = mutableListOf<String>()
            fieldErrors.forEach { (field, text) ->
                when (field) {
                    "name" -> out["name"] = text
                    "default_min_minutes" -> out["min"] = text
                    "default_max_minutes" -> out["max"] = text
                    else -> spill += text
                }
            }
            if (spill.isNotEmpty() && "name" !in out) {
                out["name"] = spill.joinToString(" ")
            }
            return out
        }
    }
}

// ---------------------------------------------------------------------------
// 매핑
// ---------------------------------------------------------------------------

/** "12~18분" / 한 점이면 "5분" */
private fun rangeLabel(lo: Int, hi: Int): String =
    if (lo == hi) "${lo}분" else "${lo}~${hi}분"

/**
 * 학습 상태 한 줄.
 *
 * 관측이 신고 범위를 **벗어났을 때 그 사실을 말한다.** front-spec S4 의 동작이다.
 * 사용자가 "샤워 12~18분" 이라고 답했는데 실제가 평균 22분이면, 그걸 알려야
 * 범위를 고치거나 알람을 더 이르게 받아들인다. 조용히 학습만 하면 사용자는
 * 자기 설정이 맞다고 믿은 채 알람이 왜 이른지 의아해한다.
 */
private fun learningNote(dto: RoutineBlockDto): String {
    val mean = dto.observedMeanMinutes
    val count = dto.observationCount

    if (count == 0 || mean == null) {
        return if (dto.minMinutes == dto.maxMinutes) {
            "관측 없음 · 범위가 한 점이라 확률 계산에 쓰이지 않음"
        } else {
            "관측 없음 · 신고 범위를 사전값으로 씀"
        }
    }

    val rounded = Math.round(mean * 10) / 10.0
    val meanText = if (rounded == Math.floor(rounded)) "${rounded.toInt()}분" else "${rounded}분"

    return when {
        mean > dto.maxMinutes -> {
            val over = Math.round(mean - dto.maxMinutes)
            "실측 평균 $meanText · 신고 최대보다 ${over}분 길다. 범위를 늘리는 것을 권함"
        }

        mean < dto.minMinutes -> {
            val under = Math.round(dto.minMinutes - mean)
            "실측 평균 $meanText · 신고 최소보다 ${under}분 짧다. 범위를 줄이면 알람이 늦춰짐"
        }

        else -> "실측 평균 $meanText · 관측 ${count}회, 신고 범위 안에 있음"
    }
}

private fun RoutineBlockDto.toView(): RoutineBlockView {
    val mean = observedMeanMinutes
    val meanLabel = if (observationCount > 0 && mean != null) {
        val rounded = Math.round(mean * 10) / 10.0
        val text = if (abs(rounded - Math.floor(rounded)) < 1e-9) "${rounded.toInt()}"
        else "$rounded"
        "실측 평균 ${text}분"
    } else {
        null
    }

    // 실측 평균이 신고 범위를 벗어났는지. 벗어났으면 사용자가 범위를 고쳐야
    // 알람이 정확해진다. 화면이 배너로 모아 알린다.
    val mismatch = observationCount > 0 && mean != null &&
        (mean > maxMinutes || mean < minMinutes)

    return RoutineBlockView(
        id = id,
        name = name,
        minMinutes = minMinutes,
        maxMinutes = maxMinutes,
        rangeLabel = rangeLabel(minMinutes, maxMinutes),
        dropCost = DropCost.from(dropCost),
        parallelizable = parallelizable,
        includedByDefault = includedByDefault,
        order = order,
        observationCount = observationCount,
        observedMeanLabel = meanLabel,
        learningNote = learningNote(this),
        rangeMismatch = mismatch,
        // 일정 문맥이 없으면 서버가 checked 를 주지 않는다. 그때는 기본 포함값이
        // 곧 이 일정의 상태다.
        checked = checked ?: includedByDefault,
        explicit = explicit ?: false,
    )
}

/**
 * 입력값을 요청 본문으로.
 *
 * 검증을 통과하지 못하면 null 을 준다. 호출부가 null 을 받으면 저장을 멈춘다 —
 * 잘못된 값을 보내 서버 400 을 받는 왕복을 아낀다.
 *
 * PATCH 도 **전체 필드**를 담는다. 화면이 폼 하나를 저장 버튼 하나로 제출하니
 * 무엇이 바뀌었는지 추적할 이유가 없고, 부분만 보내면 어떤 칸이 비었을 때
 * "안 바꿈" 인지 "지움" 인지 구분할 수 없다.
 *
 * `order` 는 보내지 않는다. 화면에서 순서를 편집하지 않으므로 생성 시 서버
 * 기본값(0)을 쓰고, 목록이 `(order, id)` 로 정렬되니 추가한 순서대로 쌓인다.
 * 수정 때 보내지 않으므로 기존 순서도 유지된다.
 */
private fun BlockDraft.toWriteRequest(): RoutineBlockWriteRequest? {
    val checked = validate()
    if (checked.errors.isNotEmpty()) return null

    val lo = checked.minText.trim().toIntOrNull() ?: return null
    val hi = checked.maxText.trim().toIntOrNull() ?: return null

    return RoutineBlockWriteRequest(
        name = checked.name,
        minMinutes = lo,
        maxMinutes = hi,
        dropCost = checked.dropCost.wire,
        parallelizable = checked.parallelizable,
        includedByDefault = checked.includedByDefault,
    )
}
