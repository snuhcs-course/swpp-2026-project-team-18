package com.swpp.wakeup.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 기기 캘린더 읽기.
 *
 * ## `Events` 가 아니라 `Instances` 를 읽는다
 *
 * `CalendarContract.Events` 는 **반복 규칙의 원본**을 담는다. "매주 월요일 9시
 * 수업" 이 한 행으로 들어 있고 시작 시각은 첫 주의 것이다. 그걸 그대로 쓰면
 * 다음 주 수업이 보이지 않는다. `Instances` 는 시스템이 반복을 펼쳐 준 결과라
 * 기간으로 질의하면 실제 발생분이 나온다.
 *
 * ## 무엇을 걸러내는가
 *
 * - **종일 일정** — 시작 시각이 자정이다. "9시까지 가야 함" 이 아니므로 알람
 *   대상이 아니다. 자정 알람을 만들면 전날 밤에 울린다.
 * - **지난 일정** — 서버가 거부한다(과거 계획은 알람이 즉시 울릴 시각이 된다).
 * - **취소·거절** — `STATUS_CANCELED` 와 내가 거절한 일정.
 * - **제목 없음** — 화면에 보여줄 것이 없고 서버가 거부한다.
 *
 * ## 식별자
 *
 * [CalendarEvent.externalId] 는 `"<이벤트 id>:<시작 밀리초>"` 다. 반복 일정은
 * 여러 발생분이 같은 이벤트 id 를 공유하므로 id 만 쓰면 매주 수업이 한 건으로
 * 합쳐진다. 시작 시각을 붙여 발생분마다 구분한다.
 */
object DeviceCalendar {

    /** 읽기 권한이 있는지. 없으면 [read] 가 빈 목록을 준다. */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR,
        ) == PackageManager.PERMISSION_GRANTED

    const val PERMISSION: String = Manifest.permission.READ_CALENDAR

    /**
     * 앞으로 [days] 일 안의 시간 지정 일정.
     *
     * 권한이 없거나 조회가 실패하면 **빈 목록**을 준다. 예외를 던지지 않는다 —
     * 캘린더는 부가 기능이고, 여기서 터지면 일정 추가 화면 전체가 막힌다.
     *
     * @param days 조회 기간. 기본 14일. 알람 등록 지평이 7일이므로 그보다
     *   넉넉하게 보여 주고 등록은 서버·스케줄러가 알아서 미룬다.
     */
    fun read(
        context: Context,
        days: Int = DEFAULT_DAYS,
        // Partly AI-generated (Claude)
        /**
         * 읽을 캘린더(`Calendars._ID`). null 이면 전부, 빈 집합이면 아무것도 읽지 않는다.
         * 가져오기 화면은 사용자가 연동한 캘린더만 넘긴다(⑱ 캘린더 연동).
         */
        calendarIds: Set<Long>? = null,
    ): List<CalendarEvent> {
        if (!hasPermission(context)) {
            Log.i(TAG, "캘린더 권한이 없다")
            return emptyList()
        }
        // 고른 캘린더가 없으면 질의하지 않는다. "IN ()" 은 SQL 오류이기도 하다.
        if (calendarIds != null && calendarIds.isEmpty()) return emptyList()

        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val until = now + days.coerceIn(1, MAX_DAYS) * DAY_MILLIS

        // Instances 질의는 기간을 URI 에 붙인다. selection 으로는 안 된다.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, until)
            it.build()
        }

        // 취소된 일정과 내가 거절한 일정을 뺀다. NULL 은 통과시킨다 —
        // 상태를 기록하지 않는 캘린더 제공자가 있다.
        val selection = StringBuilder(
            "(${CalendarContract.Instances.STATUS} IS NULL OR " +
                "${CalendarContract.Instances.STATUS} != ?) AND " +
                "(${CalendarContract.Instances.SELF_ATTENDEE_STATUS} IS NULL OR " +
                "${CalendarContract.Instances.SELF_ATTENDEE_STATUS} != ?)"
        )
        val args = mutableListOf(
            CalendarContract.Instances.STATUS_CANCELED.toString(),
            CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED.toString(),
        )
        // 연동하지 않은 캘린더는 읽지도 않는다. 화면에서 거르면 읽은 사실 자체는 남는다.
        calendarIds?.let { ids ->
            selection.append(" AND ${CalendarContract.Instances.CALENDAR_ID} IN (")
            selection.append(ids.joinToString(",") { "?" })
            selection.append(")")
            args += ids.map(Long::toString)
        }

        return try {
            context.contentResolver.query(
                uri,
                PROJECTION,
                selection.toString(),
                args.toTypedArray(),
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor -> collect(cursor, zone, now) } ?: emptyList()
        } catch (e: SecurityException) {
            // 권한이 도중에 회수될 수 있다(설정에서 끄기).
            Log.w(TAG, "캘린더 권한이 회수됐다", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "캘린더 조회 실패", e)
            emptyList()
        }
    }

    // Partly AI-generated (Claude)
    /**
     * 기기에 있는 캘린더 목록. ⑱ 캘린더 연동과 ⑯ 설정이 이 중에서 고르게 한다.
     *
     * **캘린더 앱에서 보이게 켜 둔 것만** 준다(`VISIBLE`). 숨긴 캘린더는 사용자가 이미
     * 안 보기로 한 것이라 여기서 다시 묻지 않는다. 일정은 읽지 않는다 — 목록에는
     * 캘린더 이름과 계정만 필요하다.
     *
     * **동기화를 끈 캘린더도 뺀다**(`SYNC_EVENTS = 0`). 구글 캘린더 앱에서 캘린더별
     * 동기화를 끄면 그 일정은 휴대폰에 저장되지 않는다. 목록에 두면 켜도 가져올 일정이
     * 나오지 않아 사용자는 앱이 고장 났다고 읽는다. 다시 켜는 길은 화면의 "캘린더가 안
     * 보이면" 안내가 말한다.
     *
     * 권한이 없거나 조회가 실패하면 빈 목록을 준다. [read] 와 같은 이유다.
     */
    fun listCalendars(context: Context): List<DeviceCalendarSource> {
        if (!hasPermission(context)) return emptyList()
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                CALENDAR_PROJECTION,
                // NULL 은 통과시킨다. 값을 기록하지 않는 캘린더 제공자가 있다.
                "(${CalendarContract.Calendars.VISIBLE} IS NULL OR " +
                    "${CalendarContract.Calendars.VISIBLE} = 1) AND " +
                    "(${CalendarContract.Calendars.SYNC_EVENTS} IS NULL OR " +
                    "${CalendarContract.Calendars.SYNC_EVENTS} = 1)",
                null,
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                val colorIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR)
                val primaryIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
                val accountTypeIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_TYPE)
                buildList {
                    while (cursor.moveToNext()) {
                        val account = cursor.getString(accountIndex)?.trim()?.takeIf { it.isNotEmpty() }
                        add(
                            DeviceCalendarSource(
                                id = cursor.getLong(idIndex),
                                // 이름이 빈 캘린더도 있다. 무엇인지 알 수 있게 계정 이름으로 대신한다.
                                name = cursor.getString(nameIndex)?.trim()?.takeIf { it.isNotEmpty() }
                                    ?: account
                                    ?: "이름 없는 캘린더",
                                accountName = account,
                                color = if (cursor.isNull(colorIndex)) null else cursor.getInt(colorIndex),
                                primary = !cursor.isNull(primaryIndex) && cursor.getInt(primaryIndex) == 1,
                                accountType = cursor.getString(accountTypeIndex)?.trim()
                                    ?.takeIf { it.isNotEmpty() },
                            )
                        )
                    }
                }
            } ?: emptyList()
        } catch (e: SecurityException) {
            Log.w(TAG, "캘린더 권한이 회수됐다", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "캘린더 목록 조회 실패", e)
            emptyList()
        }
    }

    private fun collect(cursor: Cursor, zone: ZoneId, now: Long): List<CalendarEvent> {
        val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
        val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
        val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
        val locationIndex =
            cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
        val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
        val calendarIndex =
            cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)

        val out = mutableListOf<CalendarEvent>()
        var skippedAllDay = 0
        var skippedNoTitle = 0

        while (cursor.moveToNext() && out.size < MAX_RESULTS) {
            // 종일 일정은 자정이 시작이다. "그날 안에 하면 됨" 이라 도착 시각이
            // 없고, 자정으로 알람을 잡으면 전날 밤에 울린다.
            if (cursor.getInt(allDayIndex) == 1) {
                skippedAllDay++
                continue
            }

            val title = cursor.getString(titleIndex)?.trim()
            if (title.isNullOrEmpty()) {
                skippedNoTitle++
                continue
            }

            val begin = cursor.getLong(beginIndex)
            // 진행 중인 일정은 이미 늦었거나 가는 중이다. 서버도 과거를 거부한다.
            if (begin <= now) continue

            val eventId = cursor.getLong(idIndex)
            val startsAt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(begin), zone)

            out += CalendarEvent(
                // 반복 일정은 발생분마다 같은 이벤트 id 를 쓴다. 시작 시각을
                // 붙이지 않으면 매주 수업이 한 건으로 합쳐진다.
                externalId = "$eventId:$begin",
                title = title.take(MAX_TITLE),
                startAtMillis = begin,
                startAtIso = startsAt.toOffsetDateTime().toString(),
                location = cursor.getString(locationIndex)?.trim()?.takeIf { it.isNotEmpty() },
                calendarName = cursor.getString(calendarIndex)?.trim(),
            )
        }

        Log.i(
            TAG,
            "캘린더 조회: ${out.size}건 (종일 ${skippedAllDay}건, 제목없음 " +
                "${skippedNoTitle}건 제외)",
        )
        return out
    }

    private const val TAG = "DeviceCalendar"
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** 기본 조회 기간. */
    const val DEFAULT_DAYS = 14

    /** 조회 기간 상한. 더 멀리 보면 목록이 길어져 고르기 어렵다. */
    private const val MAX_DAYS = 60

    /**
     * 목록 상한.
     *
     * 서버 배치 상한(50)보다 넉넉히 두고 화면에서 고르게 한다. 캘린더가 빽빽한
     * 사용자가 있어서 무제한으로 읽으면 화면이 감당하지 못한다.
     */
    private const val MAX_RESULTS = 200

    /** 서버 `title` 이 120자다. 넘기면 400 이 되므로 여기서 자른다. */
    private const val MAX_TITLE = 120

    private val PROJECTION = arrayOf(
        CalendarContract.Instances.EVENT_ID,
        CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.TITLE,
        CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.ALL_DAY,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
    )

    private val CALENDAR_PROJECTION = arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
        CalendarContract.Calendars.ACCOUNT_NAME,
        CalendarContract.Calendars.CALENDAR_COLOR,
        CalendarContract.Calendars.IS_PRIMARY,
        CalendarContract.Calendars.ACCOUNT_TYPE,
    )

    // Partly AI-generated (Claude)
    /**
     * 구글 캘린더 앱. ⑱ · ⑯ 의 "Google Calendar 열기" 가 쓴다.
     *
     * '다른 앱과 Google Calendar 데이터 공유' 설정이 이 앱 안에만 있다. 끄면 캘린더 권한을
     * 받은 앱도 캘린더 제공자로 구글 일정을 읽지 못할 수 있다
     * (support.google.com/calendar/answer/16089827). 앱이 그 화면으로 바로 보내는 공개
     * 인텐트는 없어서 앱을 연다. 매니페스트 `<queries>` 에 같은 이름을 적어야 설치 여부를
     * 알 수 있다(Android 11+ 패키지 가시성).
     */
    const val GOOGLE_CALENDAR_PACKAGE = "com.google.android.calendar"
}

// Partly AI-generated (Claude)
/**
 * 기기 캘린더 하나(일정이 아니라 캘린더 자체).
 *
 * [id] 는 이 기기 안에서만 뜻이 있다(`CalendarContract.Calendars._ID`). 계정을 지웠다
 * 다시 넣거나 기기를 바꾸면 달라진다. 그래서 연동 목록은 서버가 아니라 기기에 둔다
 * ([com.swpp.wakeup.data.local.CalendarLinkStore]).
 */
data class DeviceCalendarSource(
    val id: Long,
    val name: String,
    /** 구글 계정 주소 같은 계정 이름. 기기 로컬 캘린더면 없을 수 있다 */
    val accountName: String?,
    /** ARGB. 캘린더 앱과 같은 색 점을 그린다 */
    val color: Int?,
    /** 계정의 기본 캘린더인가 */
    val primary: Boolean,
    /**
     * 동기화 계정 종류(`com.google` 등). 카카오 캘린더가 기기에 있는지 가릴 때 쓴다
     * ([com.swpp.wakeup.domain.model.CalendarSources.isKakao]).
     */
    val accountType: String? = null,
)

/**
 * 기기 캘린더의 일정 한 건.
 *
 * **아직 서버에 보내지 않은 상태다.** [location] 은 사람이 쓴 문자열이라
 * 좌표가 아니다. 좌표로 바꾸려면 장소 검색을 거쳐야 하고, 그 결과가 맞는지는
 * 사용자가 봐야 한다 — 자동으로 첫 결과를 고르면 엉뚱한 곳으로 알람이 잡힌다.
 */
data class CalendarEvent(
    val externalId: String,
    val title: String,
    val startAtMillis: Long,
    /** ISO 8601 (오프셋 포함). 서버가 받는 형식이다 */
    val startAtIso: String,
    /** 캘린더에 적힌 장소 문자열. 없을 수 있다 */
    val location: String?,
    /** 어느 캘린더에서 왔는지. 여러 계정을 쓰는 사용자에게 필요하다 */
    val calendarName: String?,
)
