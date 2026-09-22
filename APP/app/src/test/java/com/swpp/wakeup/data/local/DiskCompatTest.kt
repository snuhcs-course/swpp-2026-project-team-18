package com.swpp.wakeup.data.local

import com.google.gson.Gson
import com.swpp.wakeup.domain.model.AlarmSchedule
import com.swpp.wakeup.domain.model.MorningBlock
import com.swpp.wakeup.domain.model.MorningSession
import com.swpp.wakeup.domain.model.ScheduledBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 구버전이 저장한 디스크 사본을 되살리는 것.
 *
 * **이것이 없으면 업그레이드가 알람을 죽인다.** 구버전 앱이 저장한 알람 JSON 에는
 * 뒤에 추가된 `prepBlocks` 키가 없다. Gson 은 그 자리를 Kotlin 기본값이 아니라
 * null 로 남기고, `canLogBlocks` 는 알람을 해제하는 순간 그 null 을 읽는다.
 *
 * 테스트가 필드 이름에 기대는 부분을 최소로 뒀다. 마지막 두 검사는 "정상
 * 인스턴스에는 있는데 되살린 인스턴스에는 없는 키" 를 찾으므로, 앞으로 누가
 * non-null 참조 필드를 더 추가하고 [withDiskDefaults] 를 고치는 것을 잊어도
 * 여기서 걸린다.
 */
class DiskCompatTest {

    private val gson = Gson()

    /** 구버전 0.2.x 가 저장했던 모양. 새 필드 키가 하나도 없다. */
    private val legacyScheduleJson = """
        {
          "eventId": 7,
          "alarmAtMillis": 1000,
          "startAtMillis": 5000,
          "alarmLabel": "7:40",
          "meridiem": "AM",
          "eventLine": "09:00 자료구조"
        }
    """.trimIndent()

    private fun keysOf(value: Any): Set<String> =
        gson.toJsonTree(value).asJsonObject.keySet()

    // ── 전제 ────────────────────────────────────────────────────────────────

    @Test
    fun `Gson 은 빠진 키에 Kotlin 기본값을 넣지 않는다`() {
        val raw = gson.fromJson(legacyScheduleJson, AlarmSchedule::class.java)

        // prepBlocks 는 non-null 로 선언됐지만 여기서는 null 이다. 이 전제가
        // 깨지면(예: Gson 이 Kotlin 을 이해하게 되면) withDiskDefaults 는 필요
        // 없어진다. 그때 이 테스트가 먼저 실패해 알려 준다.
        assertNull(
            "Gson 이 기본값을 적용하게 됐다면 DiskCompat 을 없앨 수 있다",
            raw.prepBlocks as List<ScheduledBlock>?,
        )
    }

    // ── 알람 사본 ───────────────────────────────────────────────────────────

    @Test
    fun `구버전 알람을 되살려도 블록 판정이 죽지 않는다`() {
        val restored = gson.fromJson(legacyScheduleJson, AlarmSchedule::class.java)
            .withDiskDefaults()

        // 정규화 전이라면 이 줄에서 NullPointerException 이 난다.
        assertFalse(restored.canLogBlocks)
        assertTrue(restored.prepBlocks.isEmpty())
    }

    @Test
    fun `되살려도 원래 있던 값은 그대로다`() {
        val restored = gson.fromJson(legacyScheduleJson, AlarmSchedule::class.java)
            .withDiskDefaults()

        assertEquals(7L, restored.eventId)
        assertEquals(1000L, restored.alarmAtMillis)
        assertEquals("7:40", restored.alarmLabel)
        assertNull(restored.departByMillis)
        assertNull(restored.prepMinutes)
    }

    @Test
    fun `블록이 있던 알람은 손대지 않는다`() {
        val full = AlarmSchedule(
            eventId = 7,
            alarmAtMillis = 1000,
            departByMillis = 2000,
            arriveAtMillis = 3000,
            startAtMillis = 5000,
            alarmLabel = "7:40",
            meridiem = "AM",
            eventLine = "09:00 자료구조",
            placeName = null,
            arrivalLine = null,
            prepMinutes = 40,
            prepBlocks = listOf(ScheduledBlock(1, "샤워", 12.0)),
        )

        val restored = gson.fromJson(gson.toJson(full), AlarmSchedule::class.java)
            .withDiskDefaults()

        assertEquals(full, restored)
        assertTrue(restored.canLogBlocks)
    }

    // ── 아침 기록 ───────────────────────────────────────────────────────────

    @Test
    fun `블록 키가 없는 세션을 되살려도 죽지 않는다`() {
        val partial = """{"eventId":7,"startedAtMillis":1000}"""

        val restored = gson.fromJson(partial, MorningSession::class.java)
            .withDiskDefaults()

        // 정규화 전이라면 isComplete 에서 NullPointerException 이 난다.
        assertFalse(restored.isComplete)
        assertTrue(restored.blocks.isEmpty())
        assertNull(restored.current)
    }

    @Test
    fun `진행 중인 세션은 손대지 않는다`() {
        val session = MorningSession(
            eventId = 7,
            startedAtMillis = 1000,
            departByMillis = 2000,
            plannedPrepMinutes = 40,
            blocks = listOf(
                MorningBlock(1, "샤워", 12.0, doneAtMillis = 1500),
                MorningBlock(2, "아침", 10.0),
            ),
        )

        val restored = gson.fromJson(gson.toJson(session), MorningSession::class.java)
            .withDiskDefaults()

        assertEquals(session, restored)
        assertEquals(2L, restored.current?.blockId)
    }

    // ── 앞으로 추가되는 필드까지 ────────────────────────────────────────────

    @Test
    fun `알람에 새 필드가 추가되면 정규화도 따라와야 한다`() {
        // 필수 인자만 넘긴 인스턴스에는 non-null 필드가 기본값으로 들어 있다.
        // Gson 은 null 필드를 직렬화에서 빼므로, 되살린 쪽에 없는 키 = 정규화가
        // 놓친 non-null 필드다.
        val reference = AlarmSchedule(
            eventId = 7,
            alarmAtMillis = 1000,
            departByMillis = null,
            arriveAtMillis = null,
            startAtMillis = 5000,
            alarmLabel = "7:40",
            meridiem = "AM",
            eventLine = "09:00 자료구조",
            placeName = null,
            arrivalLine = null,
        )
        val restored = gson.fromJson(legacyScheduleJson, AlarmSchedule::class.java)
            .withDiskDefaults()

        assertEquals(
            "AlarmSchedule 에 non-null 필드가 늘었다. DiskCompat 도 고쳐야 한다",
            emptySet<String>(),
            keysOf(reference) - keysOf(restored),
        )
    }

    @Test
    fun `세션에 새 필드가 추가되면 정규화도 따라와야 한다`() {
        val reference = MorningSession(
            eventId = 7,
            startedAtMillis = 1000,
            departByMillis = null,
            plannedPrepMinutes = null,
            blocks = emptyList(),
        )
        val restored = gson.fromJson("""{"eventId":7,"startedAtMillis":1000}""", MorningSession::class.java)
            .withDiskDefaults()

        assertEquals(
            "MorningSession 에 non-null 필드가 늘었다. DiskCompat 도 고쳐야 한다",
            emptySet<String>(),
            keysOf(reference) - keysOf(restored),
        )
    }
}
