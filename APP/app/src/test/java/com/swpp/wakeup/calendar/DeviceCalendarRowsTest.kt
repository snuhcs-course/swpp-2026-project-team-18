package com.swpp.wakeup.calendar

// Partly AI-generated (Claude)

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.ZoneId

/**
 * 기기 캘린더 행 → 가져올 일정. 커서는 인터페이스라 [Proxy] 로 만든 가짜 커서에 행을 넣는다.
 *
 * 종일 · 제목 없음 · 이미 시작한 일정을 거르고, 반복 일정은 발생분마다 따로 센다. 틀리면 매주
 * 수업이 한 건으로 합쳐지거나 자정에 알람이 잡힌다.
 */
class DeviceCalendarRowsTest {

    private val seoul = ZoneId.of("Asia/Seoul")

    /** 열 이름과 행(열 순서대로의 값)으로 만든 읽기 전용 커서 */
    private fun cursor(columns: List<String>, rows: List<List<Any?>>): Cursor {
        var position = -1
        return Proxy.newProxyInstance(
            Cursor::class.java.classLoader,
            arrayOf(Cursor::class.java),
        ) { _, method, args ->
            fun cell(): Any? = rows[position][args!![0] as Int]
            when (method.name) {
                "getColumnIndexOrThrow" -> columns.indexOf(args!![0] as String).also {
                    require(it >= 0) { "없는 열: ${args[0]}" }
                }
                "moveToNext" -> (++position < rows.size)
                "getString" -> cell() as String?
                "getLong" -> (cell() as Number).toLong()
                "getInt" -> (cell() as Number).toInt()
                "isNull" -> cell() == null
                "close" -> Unit
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Cursor
    }

    private val instanceColumns = listOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
    )

    private val now = 1_800_000_000_000L

    private fun instance(
        id: Long = 1,
        begin: Long = now + 3_600_000,
        title: String? = "해석개론",
        location: String? = " 302동 105호 ",
        allDay: Int = 0,
        calendar: String? = " 학교 ",
    ): List<Any?> = listOf(id, begin, title, location, allDay, calendar)

    @Test
    fun `앞으로의 일정을 가져올 일정으로 바꾼다`() {
        val events = DeviceCalendar.collect(cursor(instanceColumns, listOf(instance())), seoul, now)

        val e = events.single()
        assertEquals("1:${now + 3_600_000}", e.externalId)
        assertEquals("해석개론", e.title)
        assertEquals(now + 3_600_000, e.startAtMillis)
        assertTrue(e.startAtIso.endsWith("+09:00"))
        assertEquals("302동 105호", e.location)
        assertEquals("학교", e.calendarName)
    }

    @Test
    fun `반복 일정은 발생분마다 다른 id 를 쓴다`() {
        val week = 7 * 24 * 3_600_000L
        val events = DeviceCalendar.collect(
            cursor(instanceColumns, listOf(instance(id = 5), instance(id = 5, begin = now + 3_600_000 + week))),
            seoul,
            now,
        )

        assertEquals(2, events.map { it.externalId }.toSet().size)
    }

    @Test
    fun `종일 일정 제목 없는 일정 이미 시작한 일정은 거른다`() {
        val events = DeviceCalendar.collect(
            cursor(
                instanceColumns,
                listOf(
                    instance(id = 1, allDay = 1),
                    instance(id = 2, title = "   "),
                    instance(id = 3, title = null),
                    instance(id = 4, begin = now),
                    instance(id = 5, begin = now - 1),
                    instance(id = 6),
                ),
            ),
            seoul,
            now,
        )

        assertEquals(listOf("6:${now + 3_600_000}"), events.map { it.externalId })
    }

    @Test
    fun `빈 장소는 없는 것으로 보고 긴 제목은 자른다`() {
        val events = DeviceCalendar.collect(
            cursor(instanceColumns, listOf(instance(title = "가".repeat(200), location = "  ", calendar = null))),
            seoul,
            now,
        )

        assertEquals(120, events.single().title.length)
        assertNull(events.single().location)
        assertNull(events.single().calendarName)
    }

    @Test
    fun `한 번에 200건까지만 가져온다`() {
        val rows = (1..250L).map { instance(id = it, begin = now + it * 60_000) }

        assertEquals(200, DeviceCalendar.collect(cursor(instanceColumns, rows), seoul, now).size)
    }

    // --- 캘린더 목록 -----------------------------------------------------------

    private val calendarColumns = listOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        CalendarContract.Calendars.ACCOUNT_NAME,
        CalendarContract.Calendars.CALENDAR_COLOR,
        CalendarContract.Calendars.IS_PRIMARY,
        CalendarContract.Calendars.ACCOUNT_TYPE,
    )

    @Test
    fun `캘린더 행을 고를 수 있는 캘린더로 바꾼다`() {
        val sources = DeviceCalendar.readCalendarSources(
            cursor(calendarColumns, listOf(listOf(3L, " 수업 ", " me@snu.ac.kr ", -16776961, 1, " com.google "))),
        )

        val s = sources.single()
        assertEquals(3L, s.id)
        assertEquals("수업", s.name)
        assertEquals("me@snu.ac.kr", s.accountName)
        assertEquals(-16776961, s.color)
        assertTrue(s.primary)
        assertEquals("com.google", s.accountType)
    }

    @Test
    fun `이름이 없으면 계정 이름으로 둘 다 없으면 이름 없는 캘린더다`() {
        val sources = DeviceCalendar.readCalendarSources(
            cursor(
                calendarColumns,
                listOf(
                    listOf(1L, " ", "me@snu.ac.kr", null, null, null),
                    listOf(2L, null, "  ", null, 0, "  "),
                ),
            ),
        )

        assertEquals(listOf("me@snu.ac.kr", "이름 없는 캘린더"), sources.map { it.name })
        assertNull(sources[0].color)
        assertFalse(sources[0].primary)
        assertFalse(sources[1].primary)
        assertNull(sources[1].accountName)
        assertNull(sources[1].accountType)
    }

    // --- 질의 조건 -------------------------------------------------------------

    @Test
    fun `취소와 거절을 빼는 조건을 단다`() {
        val (selection, args) = DeviceCalendar.instancesSelection(null)

        assertTrue(selection.contains("IS NULL OR"))
        assertFalse(selection.contains(" IN ("))
        assertEquals(2, args.size)
        assertEquals(selection.count { it == '?' }, args.size)
    }

    @Test
    fun `고른 캘린더만 읽도록 자리표시자를 하나씩 단다`() {
        val (selection, args) = DeviceCalendar.instancesSelection(linkedSetOf(4L, 9L))

        assertTrue(selection.endsWith(" IN (?,?)"))
        assertEquals(listOf("4", "9"), args.drop(2))
        assertEquals(selection.count { it == '?' }, args.size)
    }

    // --- 권한 -------------------------------------------------------------------

    private fun context(granted: Boolean): Context = object : ContextWrapper(null) {
        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
    }

    @Test
    fun `권한이 없으면 읽지 않는다`() {
        assertFalse(DeviceCalendar.hasPermission(context(granted = false)))
        assertTrue(DeviceCalendar.read(context(granted = false)).isEmpty())
        assertTrue(DeviceCalendar.listCalendars(context(granted = false)).isEmpty())
    }

    @Test
    fun `고른 캘린더가 하나도 없으면 질의하지 않는다`() {
        assertTrue(DeviceCalendar.hasPermission(context(granted = true)))
        assertTrue(DeviceCalendar.read(context(granted = true), calendarIds = emptySet()).isEmpty())
    }

    @Test
    fun `목록 조회가 실패하면 빈 목록이다`() {
        // 스텁 Context 는 contentResolver 가 없다. 예외를 삼키고 빈 목록을 돌려줘야 한다.
        assertTrue(DeviceCalendar.listCalendars(context(granted = true)).isEmpty())
    }
}
