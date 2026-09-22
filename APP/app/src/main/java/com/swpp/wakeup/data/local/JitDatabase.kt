package com.swpp.wakeup.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * 로컬 캐시 DB.
 *
 * ## 무엇이 들어 있는가 — 그리고 무엇이 안 들어 있는가
 *
 * **서버 응답의 사본만** 담는다. 정본은 서버다. 이 DB 를 통째로 잃어도 네트워크
 * 왕복 한 번이면 복구된다.
 *
 * 앱에서 **생성되고 서버에만 있는** 데이터(이동 관측 큐)는 여기 두지 않는다.
 * 그건 잃으면 복구할 수 없고, 이미
 * [com.swpp.wakeup.sensing.TripObservationQueue] 가 따로 보관한다. 둘을 섞으면
 * "지워도 되는 것" 과 "지우면 안 되는 것" 이 한 파일에 들어가서, 아래의 파괴적
 * 재생성이 데이터 손실로 바뀐다.
 *
 * ## 왜 응답 JSON 을 통째로 저장하는가
 *
 * 필드를 컬럼으로 펼치지 않고 `payload` 한 칸에 JSON 을 넣는다. 이유가 둘이다.
 *
 * 1. **표시가 갈리지 않는다.** 화면 매핑(`EventRepository` 의 `toUpcoming`,
 *    `toPlanView`)은 이미 `EventDto` 를 입력으로 받는다. JSON 을 되살려 같은
 *    함수를 타면 온라인과 오프라인의 화면이 **정의상** 같다. 컬럼으로 펼치면
 *    매핑이 두 벌이 되고, 두 벌은 반드시 갈린다.
 * 2. **서버 스키마가 아직 움직인다.** 필드를 추가할 때마다 Room 마이그레이션을
 *    쓰는 비용이 사본 하나에는 과하다.
 *
 * 대신 조회·정렬에 필요한 값만 별도 컬럼으로 뽑는다. JSON 안을 SQL 로 뒤질 수는
 * 없기 때문이다.
 *
 * ## 마이그레이션을 쓰지 않는다
 *
 * [fallbackToDestructiveMigration] 이다. 스키마가 바뀌면 캐시를 버리고 다시
 * 만든다. 사본을 위해 마이그레이션을 쓰는 것은 얻는 것 없이 틀릴 기회만 늘린다.
 *
 * **이 판단은 위의 "사본만 담는다" 에 전적으로 기댄다.** 복구 불가능한 데이터를
 * 이 DB 에 넣는 순간 이 줄은 데이터 손실 버그가 된다.
 *
 * ## 소유자 격리
 *
 * 모든 행에 [CachedEventEntity.ownerEmail] 같은 소유자 칸이 있고 **모든 조회가
 * 그 값을 요구한다.** 로그아웃·로그인 때 지우는 것만으로는 부족하다 — 지우는
 * 호출을 한 곳에서 빠뜨리면 기기를 공유하거나 계정을 바꿨을 때 앞 사용자의
 * 일정과 집 근처 장소가 그대로 보인다. 조회가 소유자를 요구하면 그 실수가
 * 정보 노출로 이어지지 않는다.
 */
@Database(
    entities = [
        CachedEventEntity::class,
        CachedProfileEntity::class,
        CachedBlockEntity::class,
    ],
    version = 1,
    // 사본이라 마이그레이션을 쓰지 않으므로 스키마를 내보낼 이유가 없다.
    exportSchema = false,
)
abstract class JitDatabase : RoomDatabase() {

    abstract fun cache(): CacheDao

    companion object {
        private const val NAME = "jit_cache.db"

        @Volatile
        private var instance: JitDatabase? = null

        /**
         * 프로세스에 하나만 둔다.
         *
         * Room 인스턴스를 여러 개 만들면 각자 커넥션 풀을 들고 있어 쓰기가
         * 서로를 막는다. 이중 검사 잠금은 Room 문서의 표준 형태다.
         */
        fun get(context: Context): JitDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        JitDatabase::class.java,
                        NAME,
                    )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}

/**
 * 일정 사본.
 *
 * [payload] 는 `EventDto` 를 직렬화한 JSON 이다. [startAtEpochSecond] 는 정렬과
 * 기간 질의에 필요해서만 뽑아 둔다.
 */
@Entity(
    tableName = "cached_event",
    indices = [Index(value = ["ownerEmail", "startAtEpochSecond"])],
)
data class CachedEventEntity(
    @PrimaryKey val id: Long,
    /** 이 행을 읽을 수 있는 계정. 모든 조회가 이 값을 요구한다 */
    val ownerEmail: String,
    val startAtEpochSecond: Long,
    val payload: String,
    /** 서버에서 받은 시각(epoch 밀리초). 화면이 신선도를 표시한다 */
    val cachedAt: Long,
)

/**
 * 프로필 사본. 계정당 한 행이다.
 *
 * 기본 키를 [ownerEmail] 로 둔다. 상수 키(0)로 두고 소유자를 컬럼에만 넣으면
 * 계정을 바꿀 때 덮어쓰기와 격리 중 어느 쪽인지 모호해진다.
 */
@Entity(tableName = "cached_profile")
data class CachedProfileEntity(
    @PrimaryKey val ownerEmail: String,
    val payload: String,
    val cachedAt: Long,
)

/** 루틴 블록 사본. [sortOrder] 는 서버가 정한 순서를 보존하기 위한 것이다. */
@Entity(
    tableName = "cached_block",
    indices = [Index(value = ["ownerEmail", "sortOrder"])],
)
data class CachedBlockEntity(
    @PrimaryKey val id: Long,
    val ownerEmail: String,
    val sortOrder: Int,
    val payload: String,
    val cachedAt: Long,
)

/**
 * 캐시 접근.
 *
 * `abstract class` 다. `@Transaction` 을 붙인 메서드가 다른 메서드를 부르려면
 * 본문이 필요하고, 인터페이스 기본 구현보다 이 형태가 Room 에서 오해 없이
 * 처리된다.
 */
@Dao
abstract class CacheDao {

    // --- 일정 -------------------------------------------------------------

    @Query(
        "SELECT * FROM cached_event WHERE ownerEmail = :owner " +
            "ORDER BY startAtEpochSecond ASC"
    )
    abstract suspend fun events(owner: String): List<CachedEventEntity>

    @Query("SELECT * FROM cached_event WHERE ownerEmail = :owner AND id = :id")
    abstract suspend fun event(owner: String, id: Long): CachedEventEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putEvents(rows: List<CachedEventEntity>)

    @Query("DELETE FROM cached_event WHERE ownerEmail = :owner")
    abstract suspend fun clearEvents(owner: String)

    /**
     * 목록을 통째로 갈아 끼운다.
     *
     * **한 트랜잭션이어야 한다.** 지우고 넣는 사이에 프로세스가 죽으면 캐시가
     * 빈 상태로 남고, 그러면 오프라인에서 "일정이 없음" 이 보인다. 서버에는
     * 일정이 있는데 앱이 없다고 말하는 것이 가장 나쁜 실패다.
     */
    @Transaction
    open suspend fun replaceEvents(owner: String, rows: List<CachedEventEntity>) {
        clearEvents(owner)
        putEvents(rows)
    }

    /**
     * 일정 하나만 갱신한다.
     *
     * 블록 체크를 저장하면 서버가 그 일정만 다시 계산해 돌려준다. 그때 목록
     * 전체를 다시 받지 않고 이 행만 고친다.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putEvent(row: CachedEventEntity)

    @Query("DELETE FROM cached_event WHERE ownerEmail = :owner AND id = :id")
    abstract suspend fun deleteEvent(owner: String, id: Long)

    // --- 프로필 -----------------------------------------------------------

    @Query("SELECT * FROM cached_profile WHERE ownerEmail = :owner")
    abstract suspend fun profile(owner: String): CachedProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putProfile(row: CachedProfileEntity)

    // --- 루틴 블록 --------------------------------------------------------

    @Query(
        "SELECT * FROM cached_block WHERE ownerEmail = :owner " +
            "ORDER BY sortOrder ASC, id ASC"
    )
    abstract suspend fun blocks(owner: String): List<CachedBlockEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putBlocks(rows: List<CachedBlockEntity>)

    @Query("DELETE FROM cached_block WHERE ownerEmail = :owner")
    abstract suspend fun clearBlocks(owner: String)

    @Transaction
    open suspend fun replaceBlocks(owner: String, rows: List<CachedBlockEntity>) {
        clearBlocks(owner)
        putBlocks(rows)
    }

    // --- 전체 삭제 --------------------------------------------------------

    @Query("DELETE FROM cached_event")
    abstract suspend fun wipeEvents()

    @Query("DELETE FROM cached_profile")
    abstract suspend fun wipeProfiles()

    @Query("DELETE FROM cached_block")
    abstract suspend fun wipeBlocks()

    /**
     * 모든 계정의 캐시를 지운다. 로그아웃·계정 전환에서 부른다.
     *
     * 소유자를 가리지 않는 것이 의도다. 로그아웃 시점에 어떤 계정의 잔재가
     * 남아 있는지 앱이 확신할 수 없다 — 앱을 지웠다 깔지 않고 계정을 여러 번
     * 바꿨을 수 있다.
     */
    @Transaction
    open suspend fun wipe() {
        wipeEvents()
        wipeProfiles()
        wipeBlocks()
    }
}
