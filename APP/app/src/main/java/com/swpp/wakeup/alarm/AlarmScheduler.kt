package com.swpp.wakeup.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.swpp.wakeup.domain.model.AlarmSchedule
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 정확 알람 등록·해제.
 *
 * **`setAlarmClock` 을 쓴다.** `setExact` 는 Doze 에서 밀릴 수 있고
 * `setExactAndAllowWhileIdle` 는 9분 간격 제한이 있다. `setAlarmClock` 은
 * 시스템이 "사용자가 기다리는 알람" 으로 취급해 Doze 를 뚫고, 상태바에
 * 다음 알람 아이콘을 띄워 준다 — 사용자가 등록됐는지 눈으로 확인할 수 있다.
 * 이 앱은 알람이 한 번 안 울리면 존재 이유가 없으므로 가장 강한 API 를 쓴다.
 *
 * `USE_EXACT_ALARM` 을 매니페스트에 선언했고 설치 시 부여되므로 런타임 요청이
 * 필요 없다. 알람이 주 기능인 앱만 이 권한을 쓸 수 있다.
 */
class AlarmScheduler(private val context: Context) {

    private val manager = context.getSystemService(AlarmManager::class.java)
    private val store = ScheduledAlarmStore(context)

    /**
     * 서버에서 받은 계획으로 등록 상태를 맞춘다.
     *
     * 목록을 그대로 반영한다 — 사라진 일정의 알람은 해제하고, 시각이 바뀐
     * 알람은 새 시각으로 다시 등록한다. 서버가 진실의 근원이므로 로컬에서
     * 병합하지 않고 갈아 끼운다.
     *
     * **이미 지난 알람은 등록하지 않는다.** 등록하면 즉시 울린다.
     */
    fun sync(schedules: List<AlarmSchedule>) {
        val now = System.currentTimeMillis()

        // 이전에 등록한 것을 먼저 해제한다. eventId 가 PendingIntent 의
        // requestCode 라서 정확히 같은 알람만 지워진다.
        store.all().forEach { cancelPendingIntent(it.eventId) }

        val upcoming = schedules
            .filter { it.alarmAtMillis > now }
            .filter { it.alarmAtMillis - now <= HORIZON_MILLIS }
            .sortedBy { it.alarmAtMillis }
            .take(MAX_REGISTERED)

        store.replaceAll(upcoming)
        upcoming.forEach { register(it) }

        Log.i(
            TAG,
            "알람 동기화: 후보 ${schedules.size}건 → 등록 ${upcoming.size}건" +
                (upcoming.firstOrNull()?.let { " (가장 이른 것 ${it.alarmLabel})" } ?: ""),
        )
    }

    /**
     * 미루기. 지금부터 [minutes] 분 뒤로 다시 등록한다.
     *
     * **표시 라벨도 함께 고친다.** `alarmLabel` 은 계획을 받을 때 만들어 둔
     * 문자열이라 시각만 바꾸면 화면과 로그가 원래 시각을 그대로 보여준다 —
     * 11:10 으로 미뤘는데 화면에 11:04 가 뜨는 것을 에뮬레이터에서 확인했다.
     */
    fun snooze(schedule: AlarmSchedule, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        val local = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())

        val next = schedule.copy(
            alarmAtMillis = at,
            alarmLabel = local.format(LABEL_FORMAT),
            meridiem = if (local.hour < 12) "AM" else "PM",
        )
        store.upsert(next)
        register(next)
        Log.i(TAG, "알람 미루기: 일정 ${next.eventId} → ${minutes}분 뒤 (${next.alarmLabel})")
    }

    /**
     * 재부팅·앱 교체·시간대 변경 후 다시 등록한다.
     *
     * 저장소만 읽고 네트워크를 쓰지 않는다. 부팅 직후에는 네트워크가 없고
     * 토큰이 만료됐을 수도 있다.
     */
    fun restoreAll(): Int {
        val now = System.currentTimeMillis()
        val (alive, expired) = store.all().partition { it.alarmAtMillis > now }

        // 지난 알람은 버린다. 남겨 두면 다음 복원에서 또 검사 대상이 된다.
        if (expired.isNotEmpty()) store.replaceAll(alive)

        alive.forEach { register(it) }
        Log.i(TAG, "알람 복원: ${alive.size}건 (만료 ${expired.size}건 버림)")
        return alive.size
    }

    fun cancel(eventId: Long) {
        cancelPendingIntent(eventId)
        store.remove(eventId)
    }

    /**
     * 등록된 알람 전부 해제. **로그아웃에서 부른다.**
     *
     * 사본만 지우고 `PendingIntent` 를 남기면 **로그아웃한 계정의 알람이 그대로
     * 울린다.** 그때는 사본이 없으니 앱이 그 알람이 무엇인지 설명할 수도 없다.
     *
     * @return 해제한 건수
     */
    fun cancelAll(): Int {
        val scheduled = store.all()
        scheduled.forEach { cancelPendingIntent(it.eventId) }
        store.replaceAll(emptyList())
        Log.i(TAG, "알람 전체 해제: ${scheduled.size}건")
        return scheduled.size
    }

    /**
     * 지금 등록된 알람. 알람 시각 오름차순.
     *
     * 홈 화면이 "몇 개 등록됐고 다음이 언제인지" 를 보여줄 때 쓴다. 실기기에서
     * 알람이 실제로 걸렸는지 확인할 창구가 이것과 상태바 아이콘뿐이다.
     */
    fun registered(): List<AlarmSchedule> {
        val now = System.currentTimeMillis()
        return store.all().filter { it.alarmAtMillis > now }
    }

    // --- 내부 -------------------------------------------------------------

    private fun register(schedule: AlarmSchedule) {
        val am = manager ?: run {
            Log.e(TAG, "AlarmManager 를 얻을 수 없다")
            return
        }

        val operation = firePendingIntent(schedule.eventId)

        // showIntent 는 사용자가 상태바의 알람 아이콘을 눌렀을 때 열리는 화면이다.
        val show = PendingIntent.getActivity(
            context,
            SHOW_REQUEST_OFFSET + schedule.eventId.toInt(),
            Intent(context, com.swpp.wakeup.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        am.setAlarmClock(
            AlarmManager.AlarmClockInfo(schedule.alarmAtMillis, show),
            operation,
        )
    }

    private fun cancelPendingIntent(eventId: Long) {
        manager?.cancel(firePendingIntent(eventId))
    }

    /**
     * 발화용 PendingIntent.
     *
     * `requestCode` 를 `eventId` 로 쓴다. 같은 일정은 항상 같은 PendingIntent 로
     * 해석되어야 재등록이 덮어쓰기가 되고 해제가 정확히 그 알람만 지운다.
     */
    private fun firePendingIntent(eventId: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            eventId.toInt(),
            Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_FIRE
                putExtra(AlarmReceiver.EXTRA_EVENT_ID, eventId)
                // Intent 동등성은 extras 를 보지 않는다. data 를 달리 둬야
                // 일정별로 다른 PendingIntent 가 된다.
                data = android.net.Uri.parse("jit://alarm/$eventId")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val TAG = "AlarmScheduler"

        /**
         * 등록 상한. 알람은 시스템 자원이고 한 사용자가 수백 개를 잡을 이유가
         * 없다. 다가오는 것부터 채우고 나머지는 다음 동기화에서 들어온다.
         */
        const val MAX_REGISTERED = 20

        /** 7일. 그보다 먼 알람은 어차피 그전에 다시 동기화된다. */
        const val HORIZON_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** showIntent 의 requestCode 가 발화용과 겹치지 않게 띄운다. */
        const val SHOW_REQUEST_OFFSET = 1_000_000

        /** 알람 화면의 큰 시각. `EventRepository` 의 형식과 같아야 한다. */
        val LABEL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("H:mm")
    }
}
