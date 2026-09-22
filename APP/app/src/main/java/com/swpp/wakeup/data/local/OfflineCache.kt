package com.swpp.wakeup.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.swpp.wakeup.data.remote.EventDto
import com.swpp.wakeup.data.remote.ProfileDto
import com.swpp.wakeup.data.remote.RoutineBlockDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

/**
 * 오프라인 캐시.
 *
 * ## 규칙
 *
 * 1. **읽기는 실패하지 않는다.** 캐시가 없거나 깨졌으면 빈 결과를 준다. 캐시
 *    문제로 화면에 오류를 띄우면 사용자는 네트워크 문제와 구별할 수 없다.
 * 2. **쓰기 실패는 삼킨다.** 캐시를 못 썼다고 해서 방금 성공한 조회를 실패로
 *    바꿀 이유가 없다. 로그만 남긴다.
 * 3. **소유자 없이는 아무것도 하지 않는다.** 로그인 전(이메일 없음)에는 읽기도
 *    쓰기도 건너뛴다. 소유자 없는 행은 다음 사용자가 읽을 수 있다.
 *
 * ## 신선도를 숨기지 않는다
 *
 * [Cached.ageMinutes] 를 함께 준다. 오프라인에서 세 시간 전 알람 시각을 아무
 * 표시 없이 보여주면 사용자는 그게 지금 값이라고 믿는다. 알람 시각은 교통 상황에
 * 따라 바뀌는 값이라 그 오해가 지각으로 이어진다.
 */
class OfflineCache(
    context: Context,
    private val ownerEmail: String?,
) {

    private val dao: CacheDao = JitDatabase.get(context).cache()
    private val gson = Gson()

    companion object {
        private const val TAG = "OfflineCache"

        /**
         * 화면 수명과 분리된 스코프.
         *
         * 로그아웃 캐시 삭제가 여기서 돈다. `viewModelScope` 로 하면 **지워지지
         * 않는다** — 로그아웃은 곧바로 액티비티를 끝내고, 그러면 ViewModel 이
         * 정리되면서 그 코루틴이 취소된다. 삭제가 화면보다 오래 살아야 한다.
         */
        private val detachedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * 모든 계정의 캐시를 지운다. **호출 즉시 화면이 사라져도 끝난다.**
         *
         * 캐시에는 집 위치·목적지·일정 제목이 남아 있다. 로그아웃한 사용자의
         * 동선을 기기에 남겨 둘 이유가 없다.
         *
         * 교차 계정 노출은 이 삭제가 아니라 조회의 소유자 범위가 막는다
         * ([JitDatabase] 주석). 여기는 **보존 기간** 문제를 다룬다 — 그래서
         * 실패해도 로그만 남기고 사용자 흐름을 막지 않는다.
         */
        fun wipeDetached(context: Context) {
            val dao = JitDatabase.get(context.applicationContext).cache()
            detachedScope.launch {
                runCatching { dao.wipe() }
                    .onSuccess { Log.i(TAG, "로그아웃: 캐시를 지웠다") }
                    .onFailure { Log.w(TAG, "로그아웃 캐시 삭제 실패", it) }
            }
        }
    }

    /** 로그인 상태이고 캐시를 쓸 수 있는지. */
    val isUsable: Boolean get() = !ownerEmail.isNullOrBlank()

    private val owner: String get() = ownerEmail!!.trim().lowercase()

    /**
     * 캐시에서 읽은 것.
     *
     * [cachedAt] 이 0 이면 캐시가 없다. [items] 가 비었다는 것과 "캐시에는 빈
     * 목록이 들어 있다" 는 구분할 수 없으므로 [hit] 로 구분한다 — 서버에 정말
     * 일정이 0개인 계정도 있다.
     */
    data class Cached<T>(
        val items: List<T>,
        val cachedAt: Long,
        val hit: Boolean,
    ) {
        val ageMinutes: Long
            get() = if (!hit) 0 else ((System.currentTimeMillis() - cachedAt) / 60_000)
                .coerceAtLeast(0)

        /** "12분 전 정보" / "3시간 전 정보" / "2일 전 정보" */
        val ageLabel: String?
            get() {
                if (!hit) return null
                val minutes = ageMinutes
                return when {
                    minutes < 1 -> "방금 정보"
                    minutes < 60 -> "${minutes}분 전 정보"
                    minutes < 60 * 24 -> "${minutes / 60}시간 전 정보"
                    else -> "${minutes / (60 * 24)}일 전 정보"
                }
            }

        companion object {
            fun <T> miss(): Cached<T> = Cached(emptyList(), 0L, false)
        }
    }

    // --- 일정 -------------------------------------------------------------

    suspend fun saveEvents(events: List<EventDto>) {
        if (!isUsable) return
        val now = System.currentTimeMillis()
        runCatching {
            dao.replaceEvents(
                owner,
                events.map { event ->
                    CachedEventEntity(
                        id = event.id,
                        ownerEmail = owner,
                        startAtEpochSecond = epochSecond(event.startAt),
                        payload = gson.toJson(event),
                        cachedAt = now,
                    )
                },
            )
        }.onFailure { Log.w(TAG, "일정 캐시 저장 실패", it) }
    }

    /** 일정 하나만 갱신한다. 블록 체크 저장·재계산 응답을 반영할 때 쓴다. */
    suspend fun saveEvent(event: EventDto) {
        if (!isUsable) return
        runCatching {
            dao.putEvent(
                CachedEventEntity(
                    id = event.id,
                    ownerEmail = owner,
                    startAtEpochSecond = epochSecond(event.startAt),
                    payload = gson.toJson(event),
                    cachedAt = System.currentTimeMillis(),
                )
            )
        }.onFailure { Log.w(TAG, "일정 캐시 갱신 실패", it) }
    }

    suspend fun deleteEvent(id: Long) {
        if (!isUsable) return
        runCatching { dao.deleteEvent(owner, id) }
            .onFailure { Log.w(TAG, "일정 캐시 삭제 실패", it) }
    }

    suspend fun events(): Cached<EventDto> {
        if (!isUsable) return Cached.miss()
        val rows = runCatching { dao.events(owner) }
            .onFailure { Log.w(TAG, "일정 캐시 읽기 실패", it) }
            .getOrNull()
            ?: return Cached.miss()
        if (rows.isEmpty()) return Cached.miss()

        return Cached(
            items = rows.mapNotNull { decode(it.payload, EventDto::class.java) },
            // 목록의 신선도는 **가장 오래된** 행을 따른다. 낙관적으로 가장 최근
            // 행을 쓰면 일정 하나만 갱신된 뒤 전체가 최신인 것처럼 보인다.
            cachedAt = rows.minOf { it.cachedAt },
            hit = true,
        )
    }

    suspend fun event(id: Long): EventDto? {
        if (!isUsable) return null
        val row = runCatching { dao.event(owner, id) }
            .onFailure { Log.w(TAG, "일정 캐시 읽기 실패", it) }
            .getOrNull()
            ?: return null
        return decode(row.payload, EventDto::class.java)
    }

    // --- 프로필 -----------------------------------------------------------

    suspend fun saveProfile(profile: ProfileDto) {
        if (!isUsable) return
        runCatching {
            dao.putProfile(
                CachedProfileEntity(
                    ownerEmail = owner,
                    payload = gson.toJson(profile),
                    cachedAt = System.currentTimeMillis(),
                )
            )
        }.onFailure { Log.w(TAG, "프로필 캐시 저장 실패", it) }
    }

    suspend fun profile(): ProfileDto? {
        if (!isUsable) return null
        val row = runCatching { dao.profile(owner) }
            .onFailure { Log.w(TAG, "프로필 캐시 읽기 실패", it) }
            .getOrNull()
            ?: return null
        return decode(row.payload, ProfileDto::class.java)
    }

    // --- 루틴 블록 --------------------------------------------------------

    suspend fun saveBlocks(blocks: List<RoutineBlockDto>) {
        if (!isUsable) return
        val now = System.currentTimeMillis()
        runCatching {
            dao.replaceBlocks(
                owner,
                blocks.mapIndexed { index, block ->
                    CachedBlockEntity(
                        id = block.id,
                        ownerEmail = owner,
                        // 서버가 준 순서를 그대로 보존한다. block.order 는 같은
                        // 값이 여럿일 수 있어(기본 0) 단독으로는 순서가 흔들린다.
                        sortOrder = index,
                        payload = gson.toJson(block),
                        cachedAt = now,
                    )
                },
            )
        }.onFailure { Log.w(TAG, "블록 캐시 저장 실패", it) }
    }

    suspend fun blocks(): Cached<RoutineBlockDto> {
        if (!isUsable) return Cached.miss()
        val rows = runCatching { dao.blocks(owner) }
            .onFailure { Log.w(TAG, "블록 캐시 읽기 실패", it) }
            .getOrNull()
            ?: return Cached.miss()
        if (rows.isEmpty()) return Cached.miss()

        return Cached(
            items = rows.mapNotNull { decode(it.payload, RoutineBlockDto::class.java) },
            cachedAt = rows.minOf { it.cachedAt },
            hit = true,
        )
    }

    // --- 삭제 -------------------------------------------------------------

    /**
     * 모든 계정의 캐시를 지운다.
     *
     * **로그아웃과 로그인 양쪽에서 부른다.** 로그아웃만 지우면 앱이 강제 종료된
     * 뒤 다른 계정으로 로그인하는 경로가 남는다. 조회가 소유자를 요구하므로
     * 그 경로에서도 정보가 새지는 않지만, 남은 행이 계속 자란다.
     */
    suspend fun wipe() {
        runCatching { dao.wipe() }.onFailure { Log.w(TAG, "캐시 삭제 실패", it) }
    }

    // --- 내부 -------------------------------------------------------------

    /**
     * JSON 을 되살린다. 깨졌으면 null.
     *
     * 앱을 업데이트해 DTO 모양이 바뀌면 옛 JSON 이 남아 있을 수 있다. Gson 은
     * 대개 조용히 넘기지만 형식이 깨졌으면 예외를 던진다. 그때 화면 전체를
     * 비우지 않고 그 행만 버린다.
     */
    private fun <T> decode(payload: String, type: Class<T>): T? = try {
        gson.fromJson(payload, type)
    } catch (e: JsonSyntaxException) {
        Log.w(TAG, "캐시 항목을 읽을 수 없어 버린다: ${type.simpleName}", e)
        null
    }

    /**
     * ISO 8601 → epoch 초. 파싱 실패는 0.
     *
     * 0 이면 목록 맨 앞으로 밀린다. 정렬 키가 잘못된 것보다 눈에 보이는 편이
     * 낫다 — 조용히 빠뜨리면 일정이 사라진 것처럼 보인다.
     */
    private fun epochSecond(startAt: String): Long =
        runCatching { OffsetDateTime.parse(startAt).toEpochSecond() }.getOrDefault(0L)
}
