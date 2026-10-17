package com.swpp.wakeup.integration

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.wakeup.data.local.CacheDao
import com.swpp.wakeup.data.local.CachedBlockEntity
import com.swpp.wakeup.data.local.CachedEventEntity
import com.swpp.wakeup.data.local.CachedProfileEntity
import com.swpp.wakeup.data.local.JitDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 오프라인 캐시 DAO 를 실제 SQLite(메모리 DB)로 돌린다.
 *
 * ## 이 테스트가 지키는 것
 *
 * - **모든 조회는 소유자 범위 안에서만 돈다.** 기기를 함께 쓰거나 계정을 바꿨을 때
 *   앞 사용자의 일정·집 근처 장소가 보이면 안 된다([JitDatabase] 주석).
 * - 목록 교체는 **그 소유자의 행만** 갈아 끼운다.
 * - 정렬은 SQL 이 한다. JVM 테스트로는 쿼리 문자열이 맞는지 알 수 없어서 기기에서 돈다.
 */
@RunWith(AndroidJUnit4::class)
class CacheDaoTest {

    private lateinit var db: JitDatabase
    private lateinit var dao: CacheDao

    private val me = "me@snu.ac.kr"
    private val other = "other@snu.ac.kr"

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, JitDatabase::class.java).build()
        dao = db.cache()
    }

    @After
    fun close() = db.close()

    private fun event(id: Long, owner: String, start: Long, payload: String = "{}") =
        CachedEventEntity(id = id, ownerEmail = owner, startAtEpochSecond = start, payload = payload, cachedAt = 0)

    private fun block(id: Long, owner: String, order: Int) =
        CachedBlockEntity(id = id, ownerEmail = owner, sortOrder = order, payload = "{}", cachedAt = 0)

    @Test
    fun `일정은 내 것만 시작 시각 순으로 읽힌다`() = runBlocking<Unit> {
        dao.putEvents(listOf(event(1, me, 300), event(2, me, 100), event(3, other, 200), event(4, me, 200)))

        assertEquals(listOf(2L, 4L, 1L), dao.events(me).map { it.id })
        assertEquals(listOf(3L), dao.events(other).map { it.id })
    }

    @Test
    fun `일정 하나를 읽을 때도 소유자가 다르면 없다`() = runBlocking<Unit> {
        dao.putEvent(event(1, me, 100))

        assertEquals(1L, dao.event(me, 1)?.id)
        assertNull("id 를 알아도 다른 계정은 읽지 못한다", dao.event(other, 1))
    }

    @Test
    fun `목록 교체는 그 계정의 행만 갈아 끼운다`() = runBlocking<Unit> {
        dao.putEvents(listOf(event(1, me, 100), event(2, me, 200), event(3, other, 300)))

        dao.replaceEvents(me, listOf(event(5, me, 500)))

        assertEquals("지운 일정이 오프라인에서 되살아나지 않는다", listOf(5L), dao.events(me).map { it.id })
        assertEquals("다른 계정의 사본은 그대로다", listOf(3L), dao.events(other).map { it.id })
    }

    @Test
    fun `같은 일정을 다시 넣으면 새 응답으로 덮어쓴다`() = runBlocking<Unit> {
        dao.putEvent(event(1, me, 100, payload = """{"v":1}"""))

        dao.putEvent(event(1, me, 100, payload = """{"v":2}"""))

        assertEquals(1, dao.events(me).size)
        assertEquals("""{"v":2}""", dao.event(me, 1)?.payload)
    }

    @Test
    fun `일정 삭제는 내 행만 지운다`() = runBlocking<Unit> {
        dao.putEvent(event(1, me, 100))

        dao.deleteEvent(other, 1)
        assertEquals("다른 계정 이름으로는 지울 수 없다", 1L, dao.event(me, 1)?.id)

        dao.deleteEvent(me, 1)
        assertNull(dao.event(me, 1))
    }

    @Test
    fun `블록은 서버가 준 순서를 지키고 순서가 같으면 id 순이다`() = runBlocking<Unit> {
        dao.replaceBlocks(me, listOf(block(30, me, 1), block(20, me, 0), block(10, me, 0)))
        dao.replaceBlocks(other, listOf(block(40, other, 0)))

        assertEquals(listOf(10L, 20L, 30L), dao.blocks(me).map { it.id })

        dao.replaceBlocks(me, listOf(block(50, me, 0)))
        assertEquals(listOf(50L), dao.blocks(me).map { it.id })
        assertEquals(listOf(40L), dao.blocks(other).map { it.id })
    }

    @Test
    fun `프로필은 계정마다 한 행이고 새 응답이 덮어쓴다`() = runBlocking<Unit> {
        dao.putProfile(CachedProfileEntity(me, """{"v":1}""", cachedAt = 0))
        dao.putProfile(CachedProfileEntity(me, """{"v":2}""", cachedAt = 0))

        assertEquals("""{"v":2}""", dao.profile(me)?.payload)
        assertNull(dao.profile(other))
    }

    @Test
    fun `전체 삭제는 모든 계정의 일정 프로필 블록을 지운다`() = runBlocking<Unit> {
        dao.putEvents(listOf(event(1, me, 100), event(2, other, 100)))
        dao.putProfile(CachedProfileEntity(me, "{}", cachedAt = 0))
        dao.replaceBlocks(other, listOf(block(1, other, 0)))

        dao.wipe()

        assertTrue(dao.events(me).isEmpty())
        assertTrue(dao.events(other).isEmpty())
        assertNull(dao.profile(me))
        assertTrue(dao.blocks(other).isEmpty())
    }
}
