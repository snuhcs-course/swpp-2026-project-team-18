package com.swpp.wakeup.sensing

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.gson.Gson
import com.swpp.wakeup.alarm.AlarmNotifications
import com.swpp.wakeup.data.remote.TripObservationInput
import com.swpp.wakeup.domain.model.AlarmSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 한 아침의 이동을 따라가며 출발·도착을 판별하는 포그라운드 서비스.
 *
 * **왜 포그라운드 서비스인가.** 사용자는 알람을 끄고 주머니에 휴대폰을 넣는다.
 * 화면이 꺼진 채로 30분을 이동한다. 그동안 위치를 받아야 하고, 그건 백그라운드
 * 프로세스에게 허용되지 않는다. 상태바에 무엇을 왜 하고 있는지 계속 보여주는
 * 대가로 위치를 계속 받는다.
 *
 * **정확도를 단계별로 바꾼다.** 집을 나서는지 보는 동안은 150m 를 구분하면
 * 되므로 저전력 모드로 30초에 한 번만 본다. 나간 뒤에는 50m 반경을 봐야 하니
 * 고정확도로 10초에 한 번 본다. 처음부터 고정확도로 켜 두면 준비하는 30분
 * 동안 GPS 를 헛돌린다.
 *
 * 스스로 멈추는 조건 — 도착 판정이 났거나, 일정 시작 후 한 시간이 지났거나,
 * 사용자가 알림에서 중지를 눌렀을 때.
 */
class TripTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var queue: TripObservationQueue
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var schedule: AlarmSchedule? = null
    private var geofence: TripGeofence? = null

    /** 지금 어떤 정확도로 받고 있는지. 단계가 바뀔 때만 재요청한다. */
    private var highAccuracy = false
    private var startedUpdates = false

    private val deadlineRunnable = Runnable {
        Log.i(TAG, "마감 시각이 지나 추적을 멈춘다")
        stopSelf()
    }

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::handleLocation)
        }
    }

    override fun onCreate() {
        super.onCreate()
        queue = TripObservationQueue(this)
        AlarmNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "사용자가 추적을 중지했다")
            stopSelf()
            return START_NOT_STICKY
        }

        val raw = intent?.getStringExtra(EXTRA_SCHEDULE)
        val parsed = raw?.let {
            runCatching { gson.fromJson(it, AlarmSchedule::class.java) }.getOrNull()
        }

        if (parsed == null) {
            Log.w(TAG, "추적할 일정 정보가 없다. 멈춘다")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!LocationPermissions.granted(this)) {
            // Android 14+ 는 위치 권한 없이 location 타입 포그라운드 서비스를
            // 시작하면 SecurityException 을 던진다. 미리 막는다.
            Log.w(TAG, "위치 권한이 없다. 추적하지 않는다")
            stopSelf()
            return START_NOT_STICKY
        }

        schedule = parsed
        geofence = TripGeofence(
            home = parsed.homeLat?.let { lat ->
                parsed.homeLng?.let { lng -> GeoPoint(lat, lng) }
            },
            destination = parsed.destLat?.let { lat ->
                parsed.destLng?.let { lng -> GeoPoint(lat, lng) }
            },
        )

        // startForeground 는 즉시 불러야 한다. 늦으면 시스템이 죽인다.
        promoteToForeground(initialNotificationText(parsed))

        val remaining = parsed.trackingDeadlineMillis - System.currentTimeMillis()
        if (remaining <= 0) {
            Log.i(TAG, "이미 마감 시각이 지났다. 추적하지 않는다")
            stopSelf()
            return START_NOT_STICKY
        }
        handler.postDelayed(deadlineRunnable, remaining)

        requestUpdates(highAccuracy = geofence?.phase != TripGeofence.Phase.BEFORE_DEPARTURE)

        // 밀린 관측이 있으면 이 기회에 올린다.
        scope.launch { queue.flush() }

        Log.i(
            TAG,
            "추적 시작: 일정 ${parsed.eventId} " +
                "출발판별=${parsed.canDetectDeparture} 도착판별=${parsed.canDetectArrival} " +
                "마감=${remaining / 60_000}분 뒤",
        )
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(deadlineRunnable)
        runCatching { fused.removeLocationUpdates(callback) }
        // 남은 관측을 마지막으로 한 번 올려 본다. scope 를 바로 닫으면
        // 이 요청이 취소되므로 별도 스코프를 쓴다.
        val pending = queue.pendingCount
        if (pending > 0) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                TripObservationQueue(applicationContext).flush()
            }
        }
        scope.cancel()
        Log.i(TAG, "추적 종료 (대기 관측 ${pending}건)")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- 위치 처리 ---------------------------------------------------------

    private fun handleLocation(location: Location) {
        val current = schedule ?: return
        val fence = geofence ?: return

        if (System.currentTimeMillis() > current.trackingDeadlineMillis) {
            stopSelf()
            return
        }

        val fix = LocationFix(
            lat = location.latitude,
            lng = location.longitude,
            // accuracy 를 보고하지 않는 fix 는 신뢰할 수 없다. 최악으로 본다.
            accuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
            atMillis = if (location.time > 0) location.time else System.currentTimeMillis(),
        )

        val phaseBefore = fence.phase
        when (val event = fence.offer(fix)) {
            is TripEvent.Departed -> {
                Log.i(TAG, "출발 판정: 집에서 ${event.distanceM.roundToInt()}m")
                record(current, TripObservationInput.KIND_DEPART, event.fix, event.distanceM)
            }

            is TripEvent.Arrived -> {
                Log.i(TAG, "도착 판정: 목적지 ${event.distanceM.roundToInt()}m")
                record(current, TripObservationInput.KIND_ARRIVE, event.fix, event.distanceM)
                updateNotification("도착을 기록했다", arrivedText(current))
                stopSelf()
                return
            }

            null -> Unit
        }

        // 집을 나섰으면 목적지 반경을 봐야 하므로 정확도를 올린다.
        if (phaseBefore == TripGeofence.Phase.BEFORE_DEPARTURE &&
            fence.phase != TripGeofence.Phase.BEFORE_DEPARTURE
        ) {
            if (fence.departureMissed) {
                Log.i(TAG, "첫 위치부터 집 밖이다. 출발 시각은 기록하지 않는다")
            }
            requestUpdates(highAccuracy = true)
        }

        refreshProgressNotification(current, fence)
    }

    /** 판정 결과를 큐에 적고 올린다. 저장이 먼저다. */
    private fun record(
        current: AlarmSchedule,
        kind: String,
        fix: LocationFix,
        distanceM: Double,
    ) {
        val observation = TripObservationInput(
            event = current.eventId,
            kind = kind,
            observedAt = OffsetDateTime
                .ofInstant(Instant.ofEpochMilli(fix.atMillis), ZoneId.systemDefault())
                .toString(),
            lat = fix.lat,
            lng = fix.lng,
            accuracyM = fix.accuracyM.toDouble(),
            distanceM = distanceM,
            // 판정 시점에 만든다. 재전송할 때 새로 만들면 멱등성이 깨진다.
            clientUuid = UUID.randomUUID().toString(),
        )
        queue.enqueue(observation)
        scope.launch { queue.flush() }
    }

    // --- 위치 요청 ---------------------------------------------------------

    /**
     * 위치 갱신 요청.
     *
     * 이미 같은 정확도로 받고 있으면 아무것도 하지 않는다 — 재요청하면 GPS 가
     * 다시 warm-up 하면서 첫 fix 가 늦어진다.
     */
    private fun requestUpdates(highAccuracy: Boolean) {
        if (this.highAccuracy == highAccuracy && startedUpdates) return
        this.highAccuracy = highAccuracy

        val interval = if (highAccuracy) TRANSIT_INTERVAL_MS else IDLE_INTERVAL_MS
        val request = LocationRequest.Builder(
            if (highAccuracy) Priority.PRIORITY_HIGH_ACCURACY
            else Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            interval,
        )
            // 더 자주 오는 fix 도 받는다. 다른 앱이 이미 GPS 를 켰으면 공짜다.
            .setMinUpdateIntervalMillis(interval / 2)
            // 묶어서 늦게 주는 것을 막는다. 반경 판정은 제때 와야 의미가 있다.
            .setMaxUpdateDelayMillis(interval)
            .setWaitForAccurateLocation(highAccuracy)
            .build()

        runCatching {
            fused.removeLocationUpdates(callback)
            fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            startedUpdates = true
        }.onFailure {
            Log.e(TAG, "위치 갱신 요청 실패", it)
            stopSelf()
        }

        Log.i(
            TAG,
            "위치 갱신: ${if (highAccuracy) "고정확도" else "저전력"} ${interval / 1000}초 주기",
        )
    }

    // --- 알림 -------------------------------------------------------------

    private fun promoteToForeground(text: String) {
        ServiceCompat.startForeground(
            this,
            AlarmNotifications.NOTIFICATION_TRIP,
            AlarmNotifications.tripNotification(
                context = this,
                title = "이동 기록 중",
                text = text,
                stopIntent = stopPendingIntent(),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
    }

    private fun updateNotification(title: String, text: String) {
        runCatching {
            NotificationManagerCompat.from(this).notify(
                AlarmNotifications.NOTIFICATION_TRIP,
                AlarmNotifications.tripNotification(
                    context = this,
                    title = title,
                    text = text,
                    stopIntent = stopPendingIntent(),
                ),
            )
        }
    }

    private fun refreshProgressNotification(
        current: AlarmSchedule,
        fence: TripGeofence,
    ) {
        val title = when (fence.phase) {
            TripGeofence.Phase.BEFORE_DEPARTURE -> "출발을 기다리는 중"
            TripGeofence.Phase.IN_TRANSIT -> "이동 중"
            TripGeofence.Phase.ARRIVED -> "도착"
        }
        val remaining = fence.distanceToDestinationM
        val text = buildString {
            append(current.eventLine)
            if (remaining != null) {
                append(" · 목적지까지 ")
                append(formatDistance(remaining))
            }
        }
        updateNotification(title, text)
    }

    private fun initialNotificationText(current: AlarmSchedule): String = buildString {
        append(current.eventLine)
        append(" · ")
        append(
            when {
                current.canDetectDeparture && current.canDetectArrival ->
                    "집을 나서는 시각과 도착 시각을 기록함"

                current.canDetectArrival -> "도착 시각을 기록함"
                else -> "집을 나서는 시각을 기록함"
            }
        )
    }

    private fun arrivedText(current: AlarmSchedule): String = buildString {
        append(current.eventLine)
        current.arrivalLine?.let {
            append(" · 계획 ")
            append(it)
        }
    }

    private fun formatDistance(meters: Double): String =
        if (meters >= 1000) "%.1fkm".format(meters / 1000)
        else "${meters.roundToInt()}m"

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, TripTrackingService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val TAG = "TripTrackingService"

        const val ACTION_START = "com.swpp.wakeup.action.TRIP_START"
        const val ACTION_STOP = "com.swpp.wakeup.action.TRIP_STOP"
        private const val EXTRA_SCHEDULE = "schedule"

        /** 출발을 기다리는 동안. 150m 를 구분하면 되므로 느슨하게 본다. */
        private const val IDLE_INTERVAL_MS = 30_000L

        /** 이동 중. 50m 반경을 놓치지 않으려면 자주 봐야 한다. */
        private const val TRANSIT_INTERVAL_MS = 10_000L

        /**
         * 추적을 시작한다. 알람 해제 시점에 호출된다.
         *
         * 호출 전에 [LocationPermissions.granted] 를 확인해야 한다. 권한이
         * 없으면 서비스가 스스로 멈추지만, 그러면 사용자에게 이유를 알릴 기회가
         * 없다.
         */
        fun start(context: Context, schedule: AlarmSchedule) {
            if (!schedule.canTrack) {
                Log.i(TAG, "기준점이 없어 추적하지 않는다 (집·목적지 좌표 없음)")
                return
            }
            val intent = Intent(context, TripTrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SCHEDULE, Gson().toJson(schedule))
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TripTrackingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
