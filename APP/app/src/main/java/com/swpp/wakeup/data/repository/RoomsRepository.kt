package com.swpp.wakeup.data.repository

import com.swpp.wakeup.data.remote.ApiClient
import com.swpp.wakeup.data.remote.RoomCreateInput
import com.swpp.wakeup.data.remote.RoomDto
import com.swpp.wakeup.data.remote.RoomJoinInput
import com.swpp.wakeup.data.remote.RoomsApi
import com.swpp.wakeup.domain.model.AppointmentRoom
import com.swpp.wakeup.domain.model.RoomDetail
import com.swpp.wakeup.domain.model.RoomMember
import com.swpp.wakeup.domain.model.RoomWakeSuggestion
import kotlinx.coroutines.CancellationException
import retrofit2.Response
import java.io.IOException
import java.time.OffsetDateTime

sealed interface RoomsResult<out T> {
    data class Success<T>(val data: T) : RoomsResult<T>
    data object Unsupported : RoomsResult<Nothing>
    data class Failure(
        val message: String,
        val fields: Map<String, String> = emptyMap(),
        val status: Int? = null,
    ) : RoomsResult<Nothing>
}

interface RoomsRepository {
    suspend fun list(): RoomsResult<List<AppointmentRoom>>
    suspend fun create(input: RoomCreateInput): RoomsResult<RoomDetail>
    suspend fun join(code: String): RoomsResult<RoomDetail>
    suspend fun detail(id: Long): RoomsResult<RoomDetail>
}

/** Response adapter only. Group probability allocation and personal alarms belong to the server. */
class RemoteRoomsRepository(private val api: RoomsApi) : RoomsRepository {
    override suspend fun list(): RoomsResult<List<AppointmentRoom>> = guarded {
        val response = api.list()
        if (!response.isSuccessful) return@guarded failure(response, collection = true)
        val rows = response.body()?.results ?: return@guarded malformed()
        val rooms = rows.map { it.toAppointmentRoom() ?: return@guarded malformed() }
        RoomsResult.Success(rooms)
    }

    override suspend fun create(input: RoomCreateInput): RoomsResult<RoomDetail> = guarded {
        readDetail(api.create(input), collection = true)
    }

    override suspend fun join(code: String): RoomsResult<RoomDetail> = guarded {
        readDetail(api.join(RoomJoinInput(code)))
    }

    override suspend fun detail(id: Long): RoomsResult<RoomDetail> = guarded {
        readDetail(api.detail(id))
    }

    private fun readDetail(response: Response<RoomDto>, collection: Boolean = false): RoomsResult<RoomDetail> {
        if (!response.isSuccessful) return failure(response, collection)
        return response.body()?.toRoomDetail()?.let { RoomsResult.Success(it) } ?: malformed()
    }

    private fun failure(response: Response<*>, collection: Boolean): RoomsResult<Nothing> {
        val raw = response.errorBody()?.string()
        val code = ApiClient.parseErrorCode(raw)
        if ((collection && response.code() in setOf(404, 405)) || code == "rooms_unavailable" || response.code() == 501) {
            return RoomsResult.Unsupported
        }
        val fallback = when (response.code()) {
            401 -> "다시 로그인한 뒤 약속방을 열어 주세요"
            404 -> "방을 찾을 수 없어요. 초대 코드나 참가 여부를 확인해 주세요"
            429 -> "요청이 많아요. 잠시 뒤 다시 시도해 주세요"
            else -> "약속방을 불러오지 못했어요. 잠시 뒤 다시 시도해 주세요"
        }
        return RoomsResult.Failure(
            ApiClient.parseErrorMessage(raw) ?: fallback,
            fields = ApiClient.parseErrorFields(raw),
            status = response.code(),
        )
    }

    private suspend fun <T> guarded(block: suspend () -> RoomsResult<T>): RoomsResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        RoomsResult.Failure("연결하지 못했어요. 인터넷 연결을 확인하고 다시 시도해 주세요")
    } catch (_: Exception) {
        malformed()
    }

    private fun malformed() = RoomsResult.Failure("약속방 정보를 읽지 못했어요. 새로고침해 주세요")
}

internal fun RoomDto.toAppointmentRoom(): AppointmentRoom? {
    val validId = id?.takeIf { it > 0 } ?: return null
    val validTitle = title?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val start = parseRoomTime(startAt) ?: return null
    val place = destinationName?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val target = groupTargetProbability?.takeIf { it.isFinite() && it in 0.01..0.99 } ?: return null
    val count = memberCount?.takeIf { it > 0 } ?: return null
    return AppointmentRoom(validId, validTitle, start, place, target, count)
}

internal fun RoomDto.toRoomDetail(): RoomDetail? {
    val summary = toAppointmentRoom() ?: return null
    val code = inviteCode?.takeIf { it.matches(Regex("[A-Z0-9]{6}")) } ?: return null
    val rows = members ?: return null
    if (rows.size != summary.memberCount) return null
    val people = rows.map { row ->
        val name = row.nickname?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val me = row.isMe ?: return null
        val at = row.alarmAt?.let { parseRoomTime(it) ?: return null }
        val earlier = row.earlierMinutes
        if (earlier != null && earlier < 0) return null
        RoomMember(name, me, at, earlier)
    }
    if (people.count { it.isMe } != 1) return null
    val suggestion = wakeSuggestion?.let { dto ->
        val nickname = dto.nickname?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val minutes = dto.earlierMinutes?.takeIf { it > 0 } ?: return null
        if (people.none { it.nickname == nickname && it.earlierMinutes == minutes }) return null
        // Only public typed fields. Opaque prose can accidentally contain a member's home or route.
        RoomWakeSuggestion(nickname, minutes)
    }
    return RoomDetail(summary, code, people, suggestion)
}

private fun parseRoomTime(value: String?): OffsetDateTime? =
    value?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
