package com.swpp.wakeup.alarm

// Partly AI-generated (Claude)

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.swpp.wakeup.data.remote.AlarmLogBatchRequest
import com.swpp.wakeup.data.remote.AlarmLogBatchResponse
import com.swpp.wakeup.data.remote.AlarmLogInput
import com.swpp.wakeup.domain.model.AlarmSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime

/**
 * 알람 기록(db-schema.md A3)의 값 변환과 직렬화.
 *
 * 큐 자체는 `Context` 가 있어야 돌아 JVM 에서 못 본다(관측 큐와 같다). 대신 큐에 들어가는
 * 값 — 종류·시각·미룬 분·멱등 키 — 과 서버가 읽는 이름을 여기서 고정한다. 이름이 어긋나면
 * 서버가 400 을 주고, 400 이면 큐가 그 배치를 통째로 버려 기록이 조용히 사라진다.
 */
class AlarmLogTest {

    private val gson = Gson()
    private val alarmAt = OffsetDateTime.parse("2026-10-05T07:00:00+09:00").toInstant().toEpochMilli()

    private fun schedule(eventId: Long = 7, at: Long = alarmAt) = AlarmSchedule(
        eventId = eventId,
        alarmAtMillis = at,
        departByMillis = null,
        arriveAtMillis = null,
        startAtMillis = at + 2 * 60 * 60 * 1000L,
        alarmLabel = "7:00",
        meridiem = "AM",
        eventLine = "09:00 수업",
        placeName = null,
        arrivalLine = null,
    )

    @Test
    fun `울림 기록은 일정과 등록 시각과 일어난 시각을 담는다`() {
        val now = alarmAt + 3_000
        val log = alarmLogOf(AlarmLogInput.KIND_FIRED, schedule(), nowMillis = now)

        assertEquals(7L, log.event)
        assertEquals("fired", log.kind)
        assertEquals(now, OffsetDateTime.parse(log.occurredAt).toInstant().toEpochMilli())
        assertEquals(alarmAt, OffsetDateTime.parse(log.alarmAt).toInstant().toEpochMilli())
        assertNull(log.snoozeMinutes)
        assertTrue(log.clientUuid.isNotBlank())
    }

    @Test
    fun `미룬 분은 미루기에만 붙는다`() {
        val snoozed = alarmLogOf(AlarmLogInput.KIND_SNOOZED, schedule(), snoozeMinutes = 5)
        val dismissed = alarmLogOf(AlarmLogInput.KIND_DISMISSED, schedule(), snoozeMinutes = 5)

        assertEquals(5, snoozed.snoozeMinutes)
        // 다른 종류에 붙으면 서버가 배치 전체를 400 으로 막는다.
        assertNull(dismissed.snoozeMinutes)
    }

    @Test
    fun `멱등 키는 사건마다 새로 만든다`() {
        val a = alarmLogOf(AlarmLogInput.KIND_FIRED, schedule())
        val b = alarmLogOf(AlarmLogInput.KIND_FIRED, schedule())
        assertNotEquals(a.clientUuid, b.clientUuid)
    }

    @Test
    fun `서버가 읽는 이름으로 직렬화한다`() {
        val log = alarmLogOf(
            AlarmLogInput.KIND_SNOOZED, schedule(), snoozeMinutes = 5, clientUuid = "k-1",
        )
        val json = JsonParser.parseString(gson.toJson(AlarmLogBatchRequest(listOf(log))))
            .asJsonObject
        val item = json.getAsJsonArray("logs")[0].asJsonObject

        assertEquals(
            setOf("event", "kind", "occurred_at", "alarm_at", "snooze_minutes", "client_uuid"),
            item.keySet(),
        )
        assertEquals("k-1", item["client_uuid"].asString)
        assertEquals(5, item["snooze_minutes"].asInt)
    }

    @Test
    fun `비어 있는 선택 값은 보내지 않는다`() {
        val log = alarmLogOf(AlarmLogInput.KIND_DISMISSED, schedule(at = 0))
        val item = JsonParser.parseString(gson.toJson(log)).asJsonObject

        assertFalse(item.has("snooze_minutes"))
        assertFalse("시각을 모르면 null 이다", item.has("alarm_at"))
    }

    @Test
    fun `큐에 적은 기록을 그대로 다시 읽는다`() {
        val logs = listOf(
            alarmLogOf(AlarmLogInput.KIND_FIRED, schedule()),
            alarmLogOf(AlarmLogInput.KIND_SNOOZED, schedule(), snoozeMinutes = 5),
        )
        val type = object : TypeToken<List<AlarmLogInput>>() {}.type
        val back: List<AlarmLogInput> = gson.fromJson(gson.toJson(logs), type)
        assertEquals(logs, back)
    }

    @Test
    fun `응답 키가 없어도 0 건으로 읽는다`() {
        val body = gson.fromJson("{}", AlarmLogBatchResponse::class.java)
        assertEquals(0, body.accepted)
        assertEquals(0, body.duplicated)
    }

    @Test
    fun `등록 기록은 새로 생기거나 시각이 바뀐 일정만 남긴다`() {
        val before = listOf(schedule(eventId = 1), schedule(eventId = 2))
        val after = listOf(
            schedule(eventId = 1),                       // 같은 시각으로 다시 등록
            schedule(eventId = 2, at = alarmAt - 600_000), // 10분 당겨짐
            schedule(eventId = 3),                       // 새 일정
        )

        assertEquals(listOf(2L, 3L), changedRegistrations(before, after).map { it.eventId })
        assertTrue(changedRegistrations(after, after).isEmpty())
    }
}
